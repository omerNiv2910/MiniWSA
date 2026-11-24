# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` takes a JSON array of events, or a single event object. It validates the request and puts the whole batch on a bounded in-memory queue, then returns `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}` (the events are not echoed back, since they are enriched later). When the queue has no room for the batch, nothing is queued and the response is `503` with `Retry-After`.
- **Processing:** a background worker in the same application takes batches from the queue, stamps `receivedAt` (the time it takes the batch), classifies and scores them (Redis holds each client IP's recent events for the 10-minute window) and writes them to PostgreSQL. A failed write is retried with backoff until it succeeds. Queued events live only in memory: they are lost if the process dies (a normal shutdown drains the queue first).
- **Storage:** PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration`). Each batch is one transaction: events are inserted with `ON CONFLICT (event_id) DO NOTHING`, so a retried batch is stored exactly once.
- **Stats:** `GET /v1/stats/summary` is answered in the request, straight from PostgreSQL (not through the queue), with five `GROUP BY` queries (total, per category, per action, top 10 client IPs, top 10 paths) over the events in the time window, run in one read-only snapshot. Events still waiting in the queue are not counted yet.
- **Samples:** `GET /v1/events/samples` is also answered in the request, straight from PostgreSQL: a count and one page of matching events, newest first, in one read-only snapshot, cancelled by PostgreSQL if it runs past the query timeout.

## Design choice: in-memory event queue

Ingestion and storage are connected by a bounded queue in the application's own memory (`queue/EventQueue`), not by an external message broker.

**Why**

- **Fast, steady ingest:** the API answers as soon as a batch is queued, so a slow PostgreSQL write doesn't slow down clients.
- **Efficient writes:** the worker stores events in batches of up to `batch-size`, one transaction each, instead of one insert per request.
- **Nothing extra to run:** the service needs only PostgreSQL and Redis. That keeps local setup, tests and deployment simple, which fits a single-instance service of this size.

**Trade-offs and how they are handled**

| Trade-off | Handling |
|---|---|
| Queued events are lost if the process crashes or is killed | A normal shutdown stops the web server first, then drains the queue to PostgreSQL (`shutdown-timeout`). Events are lost only on a crash, and at most `capacity` of them. |
| Memory is limited | The queue is bounded (`capacity`). A request that doesn't fit is rejected whole with `503` and `Retry-After`, so clients slow down instead of the service running out of memory. |
| PostgreSQL can be down | The worker retries the same batch with exponential backoff. The queue fills up meanwhile, which turns into `503` for clients (backpressure). |
| A batch can be written more than once after a retry | Storage is idempotent: duplicate `event_id`s are skipped. |
| Only works within one instance | Each instance has its own queue. Running several instances still works, since they all write to the same PostgreSQL, but no queue is shared between them. |

**When to change it:** if losing queued events on a crash is not acceptable, or ingest needs to scale separately from storage, replace the queue with a durable one (an external message broker, or an outbox table in PostgreSQL). Only `IngestService` and `EventQueueWorker` would change, because the rest of the pipeline works on batches through `EventBatchHandler`.

**Settings** (`miniwsa.queue.*` in `application.yml`):

| Setting | Default | Meaning |
|---|---|---|
| `capacity` | `100000` | Most events held in memory |
| `batch-size` | `500` | Most events stored in one transaction |
| `poll-timeout` | `500ms` | Longest the worker waits for an event when the queue is empty, before checking for shutdown |
| `retry-initial-interval` / `retry-max-interval` | `1s` / `30s` | Backoff between retries of a failed batch |
| `shutdown-timeout` | `10s` | Time the worker gets on shutdown to store what is still queued |

## Design choice: PostgreSQL for storage

Enriched events are stored in one PostgreSQL table, `events` (one row per event, nested objects flattened into columns), not in a document store, a search engine or a column store.

**Why**

- **The data has a fixed shape.** Every event has the same fields, so typed columns with `NOT NULL` and `CHECK` constraints (`threat_score BETWEEN 0 AND 100`) reject bad rows at the database, and Flyway migrations change the schema in versioned, reviewable steps.
- **Every read is a filter plus a count.** Stats, samples and timeseries are time-range filters with `GROUP BY` (`category`, `action`, top IPs and paths, `date_bin` buckets). SQL does this in the database, so only small result rows come back to Java. The `(config_id, timestamp DESC)` and `(timestamp DESC)` indexes serve both "newest first" pages and time-range scans.
- **Exactly-once storage comes for free.** `event_id` is the primary key and each batch is inserted in one transaction with `ON CONFLICT (event_id) DO NOTHING`, so a batch retried after a failure is stored once. That is what lets the worker retry blindly.
- **Consistent answers.** Each stats or samples request runs its queries in one read-only snapshot, so the total and the per-category counts always add up, even while the worker is writing.
- **Protection from expensive queries.** PostgreSQL cancels a samples or timeseries query that runs past its timeout (`miniwsa.samples.query-timeout`, `miniwsa.timeseries.query-timeout`), and the API turns that into `503` instead of tying up a connection.
- **Simple to run and test.** One `postgres:17` container in Docker Compose, and tests run against a real PostgreSQL with Testcontainers and the production migrations, not an in-memory imitation.

**Trade-offs and how they are handled**

| Trade-off | Handling |
|---|---|
| Counting raw rows gets slower as the table grows | Queries are bounded by the time window, and samples and timeseries queries are cancelled at their timeout. Measured: a 30-day timeseries over 1,000,000 events takes about 300 ms (see [Timeseries](#timeseries)). |
| One database node limits write throughput | Writes are batched (up to `batch-size` events per transaction), which keeps the write rate well below what a single node handles at this service's scale. |
| Not built for full-text search over paths or user agents | The APIs only need exact-match filters, which B-tree indexes handle. |

**Scaling to big-data volumes** (the path, without changing database):

1. **Per-minute rollups.** An `event_rollups_minute` table (designed in `V1`, commented out) keeps counts per tenant, minute, category, severity and action, updated by upsert on each batch. A stats or timeseries window then reads at most 84 rows per tenant-minute instead of every event, so its cost depends on the window length, not the event volume.
2. **Time partitioning.** Partition `events` by `timestamp` (for example one partition per day). Queries for a time window touch only the matching partitions, and retention becomes dropping an old partition instead of a large `DELETE`.
3. **Read replicas** for the stats and samples endpoints, so reads don't compete with ingest.

**When to change it:** if raw-event volume outgrows a single partitioned PostgreSQL even with rollups, or ad-hoc analytics over arbitrary columns becomes the main use, move raw events to a columnar analytics store (such as ClickHouse) and keep PostgreSQL for rollups and configuration. Only `storage/EventStore` talks to the database, so that change stays inside the storage package.

## Stats summary

`GET /v1/stats/summary?configId={configId}&from={ISO8601}&to={ISO8601}` counts the events whose event `timestamp` is in `[from, to)`: in total, per category (with the average threat score), per action, and for the 10 client IPs (with the average threat score) and 10 paths with the most events.

```bash
curl "localhost:8080/v1/stats/summary?configId=14227&from=2026-05-20T14:00:00Z&to=2026-05-20T15:00:00Z"
```

```json
{
  "configId": 14227,
  "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T15:00:00Z" },
  "totalEvents": 830,
  "byCategory": {
    "INJECTION": { "count": 450, "avgThreatScore": 72.3 },
    "BOT": { "count": 380, "avgThreatScore": 45.0 }
  },
  "byAction": { "DENY": 600, "ALERT": 230 },
  "topAttackers": [
    { "clientIp": "203.0.113.42", "count": 87, "avgThreatScore": 81.2 }
  ],
  "topTargetedPaths": [
    { "path": "/api/v1/login", "count": 234 }
  ]
}
```

- `from` and `to` are required ISO-8601 timestamps with an offset. Send a `+` offset as `%2B` (`2026-05-20T16:00:00%2B02:00`); a bare `+` in a query string arrives as a space.
- `configId` is optional. Without it every configuration is counted and `configId` is left out of the response.
- `byCategory` and `byAction` list only categories and actions that have events, highest count first (ties by name). `avgThreatScore` averages the scored events to one decimal, and is `null` if none of them has a score.
- `topAttackers` and `topTargetedPaths` hold at most 10 entries, most events first; ties are ordered by the IP or path as plain text.
- Bad parameters return `400` problem+json. A missing `from` or `to` is reported on its own; once both are present, every problem (with `configId`, `from` and `to`) is listed together:

```json
{ "status": 400, "detail": "Invalid query parameters",
  "errors": [ { "field": "from", "message": "is required" } ] }
```

**Performance:** each of the five queries reads the matching events, using the `(config_id, timestamp)` index for one configuration and the `(timestamp)` index for all of them. Its cost grows with the number of events in the window, so very wide windows over a large table are slow. The scale-out path is per-minute rollup tables, so a window reads at most one row per minute and group instead of every event.

## Samples

`GET /v1/events/samples?configId={configId}&from={ISO8601}&to={ISO8601}&category={category}&action={action}&limit={limit}&offset={offset}` returns the individual events matching the filters, newest first, one page at a time, with the total number of matches.

```bash
curl "localhost:8080/v1/events/samples?configId=14227&category=INJECTION&action=DENY&limit=2"
```

```json
{
  "total": 1234,
  "events": [
    {
      "eventId": "evt_8f3a2b1c",
      "timestamp": "2026-05-20T14:32:10Z",
      "configId": 14227,
      "policyId": "pol_web1",
      "clientIp": "203.0.113.42",
      "hostname": "www.example.com",
      "path": "/api/v1/login",
      "method": "POST",
      "statusCode": 403,
      "userAgent": "Mozilla/5.0",
      "rule": { "id": "950001", "name": "SQL_INJECTION", "message": "SQL injection attempt",
                "severity": "CRITICAL", "category": "INJECTION" },
      "action": "DENY",
      "geoLocation": { "country": "US", "city": "Ashburn" },
      "requestSize": 512,
      "responseSize": 128,
      "receivedAt": "2026-05-20T14:32:11Z",
      "attackType": "SQL/Command Injection",
      "threatScore": 75
    }
  ]
}
```

- Every parameter is optional; an empty value (`category=`) is the same as leaving it out. Without filters the newest events of all configurations are returned.
- `from` (inclusive) and `to` (exclusive) filter on the event `timestamp`. They are ISO-8601 with an offset, as in stats: send a `+` offset as `%2B` (`2026-05-20T16:00:00%2B02:00`).
- `category` and `action` must be exact, uppercase enum names (`INJECTION`, `DENY`).
- `limit` is 1–100 (default 20); a larger value is rejected, not cut down. `offset` is 0 or more (default 0).
- `total` counts every match, whatever the `limit` and `offset`. Events with the same timestamp are ordered by `eventId`, so pages never overlap. An `offset` past the end, or an unknown `configId`, gives `200` with `"events": []`.
- Bad parameters return `400` problem+json with `errors: [{field, message}]`. Out-of-range values are all listed together; a value of the wrong type (`limit=abc`, `category=xss`) is reported on its own.
- A query that runs past `miniwsa.samples.query-timeout` (default `5s`) is cancelled by PostgreSQL and returns `503`: narrow the filters or the time range.

**Performance:** measured with `EXPLAIN ANALYZE` on 1,000,000 events (20 configurations, 30 days of data), using the existing `(config_id, timestamp)` and `(timestamp)` indexes:

| Query | Matches | Count + page |
|---|---|---|
| `configId` + 1 day | 1,654 | 0.5 ms |
| 1 day, all configurations | 33,060 | 5 ms |
| No filters | 1,000,000 | 80 ms |
| A rare `category` only | 967 | 80 ms |
| `configId` + a rare `category` | 46 | 200 ms |
| `offset=500000` | 1,000,000 | 0.75 s |

No extra index is needed: a `(category, timestamp DESC)` index would make a rare category without `configId` near-instant, but every query is already far under the timeout and the index would cost space and slow every insert.

**Known limits:**
- **Deep pages get slower.** `OFFSET` makes PostgreSQL read and discard every skipped row, so the cost grows with `offset`; the query timeout bounds it. The fix is keyset pagination: the client sends the last `(timestamp, eventId)` it saw and the query continues from there, so every page costs the same.
- **Pages shift while events arrive.** New events go to the front, so paging through live data can show an event twice. Keyset pagination fixes this too.
- **Events still in the ingest queue are not returned yet**, as with stats.

## Timeseries

`GET /v1/stats/timeseries?configId={configId}&from={ISO8601}&to={ISO8601}&interval={1m|5m|1h}` counts the events per time bucket over `[from, to)`, ready for drawing a line chart.

```bash
curl "localhost:8080/v1/stats/timeseries?configId=14227&from=2026-05-20T14:00:00Z&to=2026-05-20T14:15:00Z&interval=5m"
```

```json
{
  "configId": 14227,
  "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T14:15:00Z" },
  "interval": "5m",
  "totalEvents": 22,
  "buckets": [
    { "start": "2026-05-20T14:00:00Z", "count": 12 },
    { "start": "2026-05-20T14:05:00Z", "count": 0 },
    { "start": "2026-05-20T14:10:00Z", "count": 10 }
  ]
}
```

- `from`, `to` and `interval` are required. `from`/`to` follow the same rules as the stats summary (ISO-8601 with an offset; send `+` as `%2B`). `interval` must be exactly `1m`, `5m` or `1h`.
- `configId` is optional. Without it every configuration is counted and `configId` is left out of the response.
- Each bucket's `start` is when it begins; it covers `[start, start + interval)`. Buckets line up on whole UTC minutes, 5-minute marks or hours, so when `from` is not on a boundary the first bucket starts before it (but only counts events from `from` on).
- Every bucket is returned, with `count: 0` when empty, so the line has no gaps. An unknown `configId` gives `200` with all zeros.
- `totalEvents` is the sum of the buckets and matches `/v1/stats/summary` for the same window.
- One request returns at most 2,880 buckets: 2 days at `1m`, 10 days at `5m`, 120 days at `1h`. A longer range is rejected with `400`; use a larger interval or a shorter range.
- Bad parameters return `400` problem+json with `errors: [{field, message}]`, all listed together, as in the summary:

```json
{ "status": 400, "detail": "Invalid query parameters",
  "errors": [ { "field": "interval", "message": "must be one of [1m, 5m, 1h]" } ] }
```

- A query that runs past `miniwsa.timeseries.query-timeout` (default `5s`) is cancelled and returns `503`.

**Performance:** PostgreSQL does the counting (`date_bin` + `GROUP BY`), so at most 2,880 small rows come back; empty buckets are filled in Java. Measured with `EXPLAIN ANALYZE` on 1,000,000 events (20 configurations, 30 days), using the existing indexes:

| Query | Matching events | Time |
|---|---|---|
| `configId` + 1 day at `1m` | 1,655 | 8 ms |
| `configId` + 10 days at `5m` | 16,458 | 47 ms |
| All configurations + 2 days at `1m` | 66,392 | 92 ms |
| All configurations + 30 days at `1h` | 1,000,000 | 314 ms |

The cost grows with the number of events in the window, not with the number of buckets, so no new index is needed. At much larger volumes the scale-out path is a per-minute rollup table, as for the summary.

## Rate limiting

The read APIs (`/v1/stats/summary`, `/v1/stats/timeseries` and `/v1/events/samples`) allow **100 requests per minute per client IP**. The next request in that minute gets `429`:

```json
{ "title": "Too Many Requests", "status": 429, "detail": "Rate limit exceeded; try again later",
  "instance": "/v1/stats/summary" }
```

- **One budget per IP, shared by the three endpoints.** 60 samples requests plus 40 summary requests use it up.
- **Not limited:** `POST /v1/events/ingest` (it has its own backpressure, the queue's `503`) and `/actuator/**`.
- **Every request counts,** including ones that end up `400`: the check runs before the parameters are validated. A rejected request never reaches the controller or PostgreSQL.
- **Fixed window:** the minute is the clock minute (12:00:00–12:00:59). At 12:01:00 every IP starts again from 0. A client can therefore send 100 requests at 12:00:59 and another 100 at 12:01:00, up to 2× the limit across a boundary. That is the accepted cost of one counter per IP; a sliding window would need a sorted set per IP.
- **Fail-open:** if Redis is down or slow (1s timeout), requests are let through and a warning is logged at most once per window. A Redis outage does not take the read API down.

**How it works:** a Spring MVC interceptor (`RateLimitInterceptor`, registered on the three paths by `RateLimitWebConfig`) runs before the controller. It asks `RedisRateLimiter`, which runs one Lua script on the key `miniwsa:ratelimit:<ip>:<window start, epoch seconds>`: `INCR`, and on the first request of the window `PEXPIRE` (window + 5s), so old counters delete themselves. Redis is shared by all instances, so the limit holds when the service is scaled out, and the script makes the count and the expiry one atomic step. Over the limit, the interceptor throws and `ApiExceptionHandler` answers `429`.

**Client IP:** the counter key is `request.getRemoteAddr()`. With `server.forward-headers-strategy: native`, Tomcat replaces it with the client IP from `X-Forwarded-For` **only** when the request comes from a trusted proxy (`server.tomcat.remoteip.internal-proxies`). The default trusts all private ranges, including the Docker bridge (`172.17.0.1`); behind a proxy, set it to that proxy's address. Without a proxy, set it to `""` so a client can't choose its own IP, and so its own budget, by sending the header.

| Setting (`miniwsa.rate-limit.*`) | Default | Meaning |
|---|---|---|
| `enabled` | `true` | `false` turns rate limiting off entirely |
| `limit` | `100` | Requests per client IP per window (at least 1) |
| `window` | `1m` | Window length (at least `1s`) |

## Data generator

`com.miniwsa.generator.GeneratorCli` makes a configurable number of random security events over the last 7 days and writes them to a file as a JSON array, a body `POST /v1/events/ingest` accepts as-is. It does not call the API or touch the database, and it runs on its own: the app, PostgreSQL and Redis don't need to be up.

### How to run it

Run these from the project root.

**1. Build the jar.** Step 2 runs the generator from this jar, so build it before the first run, and build it again whenever you change the generator's code; otherwise step 2 still runs the old version.

```bash
./mvnw -q package -DskipTests
```

On Windows PowerShell use `.\mvnw.cmd -q package -DskipTests`.

**2. Run the generator:**

```bash
java -cp target/miniwsa-0.0.1-SNAPSHOT.jar "-Dloader.main=com.miniwsa.generator.GeneratorCli" org.springframework.boot.loader.launch.PropertiesLauncher
```

It prints `wrote 1000 events to data/events.json`. The jar's normal entry point starts the Spring app; `-Dloader.main=...GeneratorCli` with `PropertiesLauncher` runs the generator's `main` instead. Keep the quotes around `"-Dloader.main=..."`: without them PowerShell splits the argument at the dot and Java fails with `Could not find or load main class .main=...`.

Options go at the end of the command:

```bash
# 5,000 events into data/my-events.json
java -cp target/miniwsa-0.0.1-SNAPSHOT.jar "-Dloader.main=com.miniwsa.generator.GeneratorCli" org.springframework.boot.loader.launch.PropertiesLauncher --count 5000 --output data/my-events.json

# show the options
java -cp target/miniwsa-0.0.1-SNAPSHOT.jar "-Dloader.main=com.miniwsa.generator.GeneratorCli" org.springframework.boot.loader.launch.PropertiesLauncher --help
```

From an IDE you can instead run `GeneratorCli.main` directly, with the options as program arguments (e.g. `--count 5000 --output data/my-events.json`) and the project root as the working directory.

**3. Optional: feed the file to the ingestion API** while the app is running:

```bash
curl -X POST localhost:8080/v1/events/ingest -H 'Content-Type: application/json' --data @data/events.json
```

### Options

Both options are optional; each one you leave out uses its default.

| Option | Default | Meaning |
|---|---|---|
| `--count <n>` | `1000` | How many events to generate, a whole number from 1 to 100,000. Exactly this many events are written: 20% of them form the attack wave, the rest are background. The upper limit matches the server's default queue capacity, so one file always fits in one ingest request. |
| `--output <file>` | `data/events.json` | The file the events are written to, relative to the folder you run from (or an absolute path). Missing folders are created, and an existing file is overwritten. |

If an option is given twice, the last value wins. A bad value (e.g. `--count 0` or `--count abc`), an unknown option, or an option without a value prints an error and the usage text.

Exit codes: `0` done, `1` the file could not be written, `2` bad option.

**What the data looks like:**

- **Random from lists:** `ReferenceData` holds one list per field: config ids, policy ids, client IPs, hostnames, paths, methods, user agents, rule names, rule messages, severities, categories, actions and geo locations. Every field of an event is picked at random from its own list, independently of the other fields. The event id and the rule id are random UUIDs; event ids never repeat within a run.
- **Computed fields:** `requestSize` is 200 to 2,000 bytes; `responseSize` is 100 to 600 bytes for a `DENY` and 500 to 50,500 otherwise. The status code follows the action: a `DENY` gets `429` (rate limit), `503` (DoS) or `403`; anything else is mostly `200`, sometimes `404`, `302` or `500`.
- **Attack wave (20% of the events):** one client IP and one path, picked at random, with all their timestamps within 5 seconds of one random moment in the week. Their other fields are random like any other event.
- **Background (the rest):** everything random from the lists, timestamps spread over the whole week. No timestamp is in the future.
- **Valid input:** every event passes the ingest validation. Server-set fields (`receivedAt`, `attackType`, `threatScore`) are never written.
- **Time order:** the events are sorted by timestamp, so when it is fed to the API the server sees each IP's earlier events first. At most 100,000 events, the server's default queue capacity, so one file fits in one request.

## Package layout

| Package | Responsibility |
|---|---|
| `model` | Security event model and its enums, shared by every stage |
| `api` | REST controllers, response DTOs and error handling |
| `ingest` | Stamping events and queueing them |
| `queue` | Bounded in-memory event queue |
| `consumer` | Worker that takes batches off the queue and stores them |
| `enrichment` | Classification and threat scoring |
| `storage` | PostgreSQL event writes, stats, timeseries and samples queries, and the shared read-query timeout |
| `stats` | Stats summary and timeseries response types, and the timeseries intervals |
| `samples` | Samples query and response types |
| `ratelimit` | Rate limiting: the interceptor in front of the read endpoints, the Redis per-IP counter, the fixed-window maths and the 429 exception |
| `generator` | Synthetic data generator: reference data, event and wave generation, and the command line that outputs the events as an ingest request body |
| `config` | Application properties, shared beans and the rate-limit path registration |

## Running locally

Requirements: Java 21 and Docker.

```bash
docker compose up -d        # PostgreSQL, Redis
./mvnw spring-boot:run      # starts the service on :8080
```

Health check: `curl localhost:8080/actuator/health`

Connection settings can be overridden with `DATABASE_URL` (JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST` and `REDIS_PORT`. The schema is created on startup by Flyway, so PostgreSQL must be reachable when the service starts.

## Building and testing

```bash
./mvnw verify
```

Docker must be running: storage and Spring context tests start a real PostgreSQL with Testcontainers.
