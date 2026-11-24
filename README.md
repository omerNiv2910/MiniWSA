# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` validates the request and puts the whole batch on a bounded in-memory queue, then returns `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}` (the events are not echoed back, since they are enriched later). When the queue has no room for the batch, nothing is queued and the response is `503` with `Retry-After`.
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

`scripts/timeseries-curl-tests.sh` sends a few test events and runs 17 example requests (successes and errors) against a running app.

## Rate limiting

The read APIs (`/v1/stats/summary`, `/v1/stats/timeseries` and `/v1/events/samples`) allow **100 requests per minute per client IP**. The next request in that minute gets `429`:

```json
{ "title": "Too Many Requests", "status": 429, "detail": "Rate limit exceeded; try again later" }
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

## Package layout

| Package | Responsibility |
|---|---|
| `model` | Security event model and its enums, shared by every stage |
| `api` | REST controllers and response DTOs, the rate-limit interceptor and error handling |
| `ingest` | Stamping events and queueing them |
| `queue` | Bounded in-memory event queue |
| `consumer` | Worker that takes batches off the queue and stores them |
| `enrichment` | Classification and threat scoring |
| `storage` | PostgreSQL event writes, stats, timeseries and samples queries, and the shared read-query timeout |
| `stats` | Stats summary and timeseries response types, and the timeseries intervals |
| `samples` | Samples query and response types |
| `ratelimit` | Redis per-IP request counter and fixed-window maths |
| `generator` | Synthetic data generator |
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
