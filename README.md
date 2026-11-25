# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## How to build and run

### What you need

- **Git**
- **Java 21** (JDK). Check with `java -version`.
- **Docker** with Docker Compose, and Docker running. Docker runs PostgreSQL and Redis for the service, and the tests start their own PostgreSQL in Docker too.
- Ports **8080** (the service), **5432** (PostgreSQL) and **6379** (Redis) free on your machine.

The steps below work in **Bash** (Linux, macOS, or Git Bash on Windows) and in **Windows PowerShell**. Where the command differs, each shell gets its own; otherwise one command is shown for both. In PowerShell, `./mvnw` becomes `.\mvnw.cmd` and `curl` becomes `curl.exe`; PowerShell's own `curl` is a different command and fails with `The URI prefix is not recognized`.

You need **two terminal windows** (Bash or PowerShell). The service runs in the foreground and holds its window until you stop it, so:
- **First window:** steps 1 to 4. Step 4 starts the service; leave this window open.
- **Second window:** steps 5 to 7, once the service has started (its log shows `Started MiniWsaApplication`).
- Step 8 uses both: stop the service in the first window, then stop Docker in either.

### Steps

**1. Clone the repository and go into it:**

###### Bash and PowerShell

```bash
git clone https://github.com/omerNiv2910/MiniWSA.git
```

```bash
cd MiniWSA
```

All the following commands run from this folder.

**2. Start PostgreSQL and Redis:**

###### Bash and PowerShell

```bash
docker compose up -d
```

**3. Build the project:**

###### Bash

```bash
./mvnw package -DskipTests
```

###### PowerShell

```powershell
.\mvnw.cmd package -DskipTests
```

**4. Start the service:**

###### Bash and PowerShell

```bash
java -jar target/miniwsa-0.0.1-SNAPSHOT.jar
```

It listens on port 8080 and keeps running in this window, so leave it open. The database schema is created automatically on startup.

**Now open a second window** (another Bash or PowerShell) once the log shows `Started MiniWsaApplication`, and go to the project folder in it with `cd` (for example `cd C:\path\to\MiniWSA` in PowerShell). Run steps 5 to 7 in this second window, while the service keeps running in the first one.

**5. Check it's up** (in the second window):

###### Bash

```bash
curl localhost:8080/actuator/health
```

###### PowerShell

```powershell
curl.exe localhost:8080/actuator/health
```

The answer includes `"status":"UP"`.

**6. Generate test data and send it in** (second window). The repository has no data file, so first create one with the data generator. It runs from the jar built in step 3, so there is nothing else to set up. It writes random events from the last 7 days to `data/events.json`, and 20% of them are one attack wave: a burst from one IP hitting one path within 5 seconds. Set `--count` to the number of events you want, from 1 to 20,000 (here 5,000):

###### Bash and PowerShell

```bash
java -cp target/miniwsa-0.0.1-SNAPSHOT.jar "-Dloader.main=com.miniwsa.generator.GeneratorCli" org.springframework.boot.loader.launch.PropertiesLauncher --count 5000
```

Then send the file to the service:

###### Bash

```bash
curl -X POST localhost:8080/v1/events/ingest -H 'Content-Type: application/json' --data-binary @data/events.json
```

###### PowerShell

```powershell
curl.exe -X POST localhost:8080/v1/events/ingest -H "Content-Type: application/json" --data-binary "@data/events.json"
```

In PowerShell, keep the quotes around `"-Dloader.main=..."` (without them Java fails with `Could not find or load main class`) and around `"@data/events.json"` (without them PowerShell reads `@data` as one of its own variables and the service answers `400 Request body is not valid JSON`).

The generator prints `wrote 5000 events to data/events.json`; leave out `--count` to get the default of 1,000. The service answers `201` with `"accepted"` equal to your count (`"accepted": 5000` here). The events are stored in the background within a second or two.

**7. Read the results** (second window):

###### Bash

```bash
curl "localhost:8080/v1/stats/summary?from=2026-01-01T00:00:00Z&to=2030-01-01T00:00:00Z"
```

```bash
curl localhost:8080/v1/events/samples
```

###### PowerShell

```powershell
curl.exe "localhost:8080/v1/stats/summary?from=2026-01-01T00:00:00Z&to=2030-01-01T00:00:00Z"
```

```powershell
curl.exe localhost:8080/v1/events/samples
```

**8. Stop everything:** press `Ctrl+C` in the first window (where the service runs) to stop the service, then stop PostgreSQL and Redis and delete their data:

###### Bash and PowerShell

```bash
docker compose down -v
```

Connection settings can be overridden with `DATABASE_URL` (JDBC URL), `DATABASE_USER`, `DATABASE_PASSWORD`, `REDIS_HOST` and `REDIS_PORT`; the defaults match `docker-compose.yml`.

## Architecture

It's one Spring Boot app plus two containers: PostgreSQL (where the events and alerts live) and Redis (counters for calculations and rate limiter).
Inside the app there are two paths:
- **The Ingestion path** is async. Adding events, they go onto an in-memory queue, and a `201` is sent right away. A background worker picks them up, enriches them and saves them.
- **The read path** is plain request/response that contains most endpoints: Stats, samples and alerts. all go straight to PostgreSQL and answer from there. They never touch the queue.
  In addition, as part from the read path, there is a rate limiter that counts stats and samples requests. if there are too much requests in X time, it will return an exceptions.

A diagram sums it up in a different file.

**How an event travels:**

1. **Ingest.** `POST /v1/events/ingest` takes a JSON array (or a single event), checks it, and drops the whole batch on the queue. You get back `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}`. If the queue doesn't have room, nothing is queued and you get `503` with `Retry-After`, so slow down and try again. if a batch (or event) fails, the server do retries with exponential backoff.
2. **Process.** The worker grabs up to `batch-size` events, stamps `receivedAt`, and works out each event's `attackType` and `threatScore`. Part of the score is how busy that client IP has been in the last 10 minutes, and Redis keeps track of that.
3. **Store.** The batch goes into PostgreSQL in one transaction with `ON CONFLICT (event_id) DO NOTHING`. If the write fails, the worker just retries the same batch with backoff. Duplicates get skipped, so nothing is saved twice. The schema is managed by Flyway (`src/main/resources/db/migration`).
4. **Read.** `GET /v1/stats/summary`, `/v1/stats/timeseries` and `/v1/events/samples` run their SQL in one read-only snapshot, so the numbers always add up. Slow queries get cancelled by PostgreSQL and come back as `503`. These three are rate limited per IP via Redis. `/v1/alerts/define` and `/v1/alerts/evaluate` store alert rules and check which ones fire, also straight in SQL.


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

- **Shared by all instances.** In case of scailing into multi instances app, Unlike an in-process map, the 10-minute IP window and the rate limit stay correct when the service runs on several instances.
- **Survives an app crash.** Redis runs as its own process, so if the app crashes, the counts in Redis are kept and the restarted app picks them up (each key still expires on its own TTL).

**Another design choice: in-memory event queue**

Ingestion and storage are connected by a bounded queue in the application's own memory (`queue/EventQueue`), not by an external message broker.
- **Fast, steady ingest:** the API answers as soon as a batch is queued, so a slow PostgreSQL write doesn't slow down clients.
- **Efficient writes:** the worker takes whatever is queued, up to `batch-size` events, and stores it in one transaction (a JDBC batch insert) instead of one insert per request. Under light load a batch can be a single event; a large request can span several batches.
- **Nothing extra to run:** the service needs only PostgreSQL and Redis. That keeps local setup, tests and deployment simple, which fits a single-instance service of this size.


## What I would improve with more time

1. **Dead letter queue for ingestion:** Events that fail to be ingested would go to a dead letter queue instead of being dropped, and would later be written back to the event queue to be processed again.
2. **Redis counters for alerting:** The alerting endpoint would also write to Redis a count of events per alert definition over its window (a set number of minutes). The alert check could then often read that count from Redis and skip the database query.
3. **ClickHouse for storage:** I have no experience with this kind of storage and didn't have time to learn to use it well (see [Alternatives considered](#storage-choice-postgresql--redis)).
4. **Claude code reviews in Git:** Use a Claude skill in the Git workflow so every change gets an automatic code review.


## Challanges and Solutions

1. **Rate Limiter counting:** Deciding when the rate limiter should count a request, before the request is handled or after it. I solved it by using Spring’s HandlerInterceptor.preHandle, which runs before the controller and before parameter binding and validation. I overrided this function and wrote to the Redis over there.
2. **Making retries safe (no duplicates, no double counting):** When the worker retries a failed batch, so same events can be written twice. I solved it by using the primary key, each batch is one transaction with ON CONFLICT DO NOTHING, and Redis uses ZADD NX with the eventId as the member. All events in a batch are added to Redis before any is counted, so a retry gets exactly the same counts.


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
