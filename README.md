# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` validates the request and puts the whole batch on a bounded in-memory queue, then returns `201` with `{"status": 201, "message": "Events accepted for processing", "accepted": n}` (the events are not echoed back, since they are enriched later). When the queue has no room for the batch, nothing is queued and the response is `503` with `Retry-After`.
- **Processing:** a background worker in the same application takes batches from the queue, stamps `receivedAt` (the time it takes the batch), classifies and scores them (Redis holds each client IP's recent events for the 10-minute window) and writes them to PostgreSQL. A failed write is retried with backoff until it succeeds. Queued events live only in memory: they are lost if the process dies (a normal shutdown drains the queue first).
- **Storage:** PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration`). Each batch is one transaction: events are inserted with `ON CONFLICT (event_id) DO NOTHING`, so a retried batch is stored exactly once.
- **Stats:** `GET /v1/stats/summary` is answered in the request, straight from PostgreSQL (not through the queue), with five `GROUP BY` queries (total, per category, per action, top 10 client IPs, top 10 paths) over the events in the time window, run in one read-only snapshot. Events still waiting in the queue are not counted yet.

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

## Package layout

| Package | Responsibility |
|---|---|
| `model` | Security event model and its enums, shared by every stage |
| `api` | REST controllers and response DTOs |
| `ingest` | Stamping events and queueing them |
| `queue` | Bounded in-memory event queue |
| `consumer` | Worker that takes batches off the queue and stores them |
| `enrichment` | Classification and threat scoring |
| `storage` | PostgreSQL event writes and stats queries |
| `stats` | Stats response types |
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
