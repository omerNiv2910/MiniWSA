# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` validates the request and puts the whole batch on a bounded in-memory queue, then returns `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}` (the events are not echoed back, since they are enriched later). When the queue has no room for the batch, nothing is queued and the response is `503` with `Retry-After`.
- **Processing:** a background worker in the same application takes batches from the queue, stamps `receivedAt` (the time it takes the batch), classifies and scores them (Redis holds each client IP's recent events for the 10-minute window) and writes them to PostgreSQL. A failed write is retried with backoff until it succeeds. Queued events live only in memory: they are lost if the process dies (a normal shutdown drains the queue first).
- **Storage:** PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration`). Each batch is one transaction: events are inserted with `ON CONFLICT (event_id) DO NOTHING`, and only the newly inserted ones are added to the `event_rollups_minute` counters (one row per tenant, minute, category, severity and action). A retried batch is therefore stored and counted exactly once. The stats endpoints read the rollups instead of scanning events.

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
| A batch can be written more than once after a retry | Storage is idempotent: duplicate `event_id`s are skipped, and only new events are added to the rollups. |
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

## Package layout

| Package | Responsibility |
|---|---|
| `model` | Security event model and its enums, shared by every stage |
| `api` | REST controllers and response DTOs |
| `ingest` | Stamping events and queueing them |
| `queue` | Bounded in-memory event queue |
| `consumer` | Worker that takes batches off the queue and stores them |
| `enrichment` | Classification and threat scoring |
| `storage` | PostgreSQL event and rollup writes |
| `stats` | Summary statistics and paginated samples |
| `generator` | Synthetic data generator |
| `config` | Application properties and shared beans |

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
