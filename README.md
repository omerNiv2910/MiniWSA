# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

It's one Spring Boot app plus two containers: PostgreSQL (where the events and alerts live) and Redis (counters for calculations and rate limiter). 
Inside the app there are two paths:
- **The Ingestion path** is async. Adding events, they go onto an in-memory queue, and a `201` is sent right away. A background worker picks them up, enriches them and saves them.
- **The read path** is plain request/response that contains most endpoints: Stats, samples and alerts. all go straight to PostgreSQL and answer from there. They never touch the queue.
In addition, as part from the read path, there is a rate limiter that counts stats and samples requests. if there are too much requests in X time, it will return an exceptions.

A diagram sums it up in a different file.

**How an event travels:**

1. **Ingest.** `POST /v1/events/ingest` takes a JSON array (or a single event), checks it, and drops the whole batch on the queue. You get back `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}`. If the queue doesn't have room, nothing is queued and you get `503` with `Retry-After`, so slow down and try again. if a batch (or event) fails, the server do retires with exponential backoff.
2. **Process.** The worker grabs up to `batch-size` events, stamps `receivedAt`, and works out each event's `attackType` and `threatScore`. Part of the score is how busy that client IP has been in the last 10 minutes, and Redis keeps track of that.
3. **Store.** The batch goes into PostgreSQL in one transaction with `ON CONFLICT (event_id) DO NOTHING`. If the write fails, the worker just retries the same batch with backoff. Duplicates get skipped, so nothing is saved twice. The schema is managed by Flyway (`src/main/resources/db/migration`).
4. **Read.** `GET /v1/stats/summary`, `/v1/stats/timeseries` and `/v1/events/samples` run their SQL in one read-only snapshot, so the numbers always add up. Slow queries get cancelled by PostgreSQL and come back as `503`. These three are rate limited per IP via Redis. `/v1/alerts/define` and `/v1/alerts/evaluate` store alert rules and check which ones fire, also straight in SQL.


## Design choice: in-memory event queue

Ingestion and storage are connected by a bounded queue in the application's own memory (`queue/EventQueue`), not by an external message broker.

**Why**

- **Fast, steady ingest:** the API answers as soon as a batch is queued, so a slow PostgreSQL write doesn't slow down clients.
- **Efficient writes:** the worker takes whatever is queued, up to `batch-size` events, and stores it in one transaction (a JDBC batch insert) instead of one insert per request. Under light load a batch can be a single event; a large request can span several batches.
- **Nothing extra to run:** the service needs only PostgreSQL and Redis. That keeps local setup, tests and deployment simple, which fits a single-instance service of this size.

**Trade-offs and how they are handled**

| Trade-off | Handling |
|---|---|
| Queued events are lost if the process crashes or is killed | A normal shutdown stops the web server first, then drains the queue to PostgreSQL (`shutdown-timeout`). Events are lost only on a crash, and at most `capacity` of them. |
| Memory is limited | The queue is bounded (`capacity`). A request that doesn't fit is rejected whole with `503` and `Retry-After`, so clients slow down instead of the service running out of memory. |
| PostgreSQL can be down | The worker retries the same batch with exponential backoff. The queue fills up meanwhile, which turns into `503` for clients (backpressure). |
| A batch can be written more than once after a retry | Storage is idempotent: duplicate `event_id`s are skipped. |
| Only works within one instance | Each instance has its own queue. Running several instances still works, since they all write to the same PostgreSQL, but no queue is shared between them. |

**When to change it:** if losing queued events on a crash is not acceptable, or ingest needs to scale separately from storage, replace the queue with a durable one (an external message broker).

**Settings** (`miniwsa.queue.*` in `application.yml`):

| Setting | Default | Meaning |
|---|---|---|
| `capacity` | `100000` | Most events held in memory |
| `batch-size` | `10000` | Most events stored in one transaction |
| `poll-timeout` | `500ms` | Longest the worker waits for an event when the queue is empty, before checking for shutdown |
| `retry-initial-interval` / `retry-max-interval` | `1s` / `30s` | Backoff between retries of a failed batch |
| `shutdown-timeout` | `10s` | Time the worker gets on shutdown to store what is still queued |

## Storage choice: PostgreSQL + Redis

Two stores, each holding the kind of data it's good at:

- **PostgreSQL** holds everything that must last: enriched events in one `events` and alert rules in `alerts`.
- **Redis** used as cache. holds only short-lived counters that are fine to lose: each client IP's events from the last 10 minutes (for the threat score) and the per-IP rate-limit counters. Every key expires on its own.

**Alternatives considered**

| Option | Why not |
|---|---|
| Document store (e.g. MongoDB) | Events all have the same fixed shape, so a flexible schema buys nothing, and every read is a `GROUP BY` count, which SQL does more simply than an aggregation pipeline. |
| Columnar store (e.g. ClickHouse) | The main reason I didn't choose it is that I have no experience with it. I think It's the best fit for billions of rows of analytics but it has no primary-key dedup on insert (merges happen later), no multi-statement transactions, and more to run than this volume needs. It's the planned next step if volume outgrows PostgreSQL. |

**Why PostgreSQL**

- **The data has a fixed shape.** Every event has the same fields, so typed columns with `NOT NULL` and `CHECK` constraints (`threat_score BETWEEN 0 AND 100`) reject bad rows at the database.
- **Every read is a filter plus a count.** Stats, samples, timeseries and alert evaluation are time-range filters with `GROUP BY` (`category`, `action`, top IPs and paths, `date_bin` buckets). SQL does this in the database, so only small result rows come back to Java.
- **Exactly-once storage after retry comes for free.** `event_id` is the primary key and each batch is inserted in one transaction with `ON CONFLICT (event_id) DO NOTHING`, so a batch retried after a failure is stored once. That is what lets the worker retry blindly.
- **Consistent answers.** Each stats or samples request runs its queries in one read-only snapshot, so the total and the per-category counts always add up, even while the worker is writing.
- **Protection from expensive queries.** PostgreSQL cancels a samples or timeseries query that runs past its timeout (`miniwsa.samples.query-timeout`, `miniwsa.timeseries.query-timeout`), and the API turns that into `503` instead of tying up a connection.

**Why Redis next to it**

- **Shared by all instances.** Unlike an in-process map, the 10-minute IP window and the rate limit stay correct when the service runs on several instances.
- **Survives an app crash.** Redis runs as its own process, so if the app crashes, the counts in Redis are kept and the restarted app picks them up (each key still expires on its own TTL).

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
| `--count <n>` | `1000` | How many events to generate, a whole number from 1 to 20,000. Exactly this many events are written: 20% of them form the attack wave, the rest are background. The upper limit is a fifth of the server's default queue capacity, so one file fits in one ingest request even while the server is still storing earlier events. |
| `--output <file>` | `data/events.json` | The file the events are written to, relative to the folder you run from (or an absolute path). Missing folders are created, and an existing file is overwritten. |

If an option is given twice, the last value wins. A bad value (e.g. `--count 0` or `--count abc`), an unknown option, or an option without a value prints an error and the usage text.

Exit codes: `0` done, `1` the file could not be written, `2` bad option.

**What the data looks like:**

- **Random from lists:** `ReferenceData` holds one list per field: config ids, policy ids, client IPs, hostnames, paths, methods, user agents, rule names, rule messages, severities, categories, actions and geo locations. Every field of an event is picked at random from its own list, independently of the other fields. The event id and the rule id are random UUIDs; event ids never repeat within a run.
- **Computed fields:** `requestSize` is 200 to 2,000 bytes; `responseSize` is 100 to 600 bytes for a `DENY` and 500 to 50,500 otherwise. The status code follows the action: a `DENY` gets `429` (rate limit), `503` (DoS) or `403`; anything else is mostly `200`, sometimes `404`, `302` or `500`.
- **Attack wave (20% of the events):** one client IP and one path, picked at random, with all their timestamps within 5 seconds of one random moment in the week. Their other fields are random like any other event.
- **Background (the rest):** everything random from the lists, timestamps spread over the whole week. No timestamp is in the future.
- **Valid input:** every event passes the ingest validation. Server-set fields (`receivedAt`, `attackType`, `threatScore`) are never written.
- **Time order:** the events are sorted by timestamp, so when it is fed to the API the server sees each IP's earlier events first. At most 20,000 events, a fifth of the server's default queue capacity, so one file fits in one request.

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

## What I would improve with more time

1. **Dead letter queue for ingestion:** Events that fail to be ingested would go to a dead letter queue instead of being dropped, and would later be written back to the event queue to be processed again.
2. **Redis counters for alerting:** The alerting endpoint would also write to Redis a count of events per alert definition over its window (a set number of minutes). The alert check could then often read that count from Redis and skip the database query.
3. **ClickHouse for storage:** I have no experience with this kind of storage and didn't have time to learn to use it well (see [Alternatives considered](#storage-choice-postgresql--redis)).
4. **Claude code reviews in Git:** Use a Claude skill in the Git workflow so every change gets an automatic code review.

## Challanges and Solutions

1. **Rate Limiter counting:** Deciding when the rate limiter should count a request, before the request is handled or after it. I solved it by using Spring’s HandlerInterceptor.preHandle, which runs before the controller and before parameter binding and validation. I overrided this function and wrote to the Redis over there.
2. **Making retries safe (no duplicates, no double counting):** When the worker retries a failed batch, so same events can be written twice. I solved it by using the primary key, each batch is one transaction with ON CONFLICT DO NOTHING, and Redis uses ZADD NX with the eventId as the member. All events in a batch are added to Redis before any is counted, so a retry gets exactly the same counts.