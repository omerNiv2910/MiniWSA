# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` validates the request, stamps `receivedAt` and puts the whole batch on a bounded in-memory queue, then returns `201`. When the queue has no room for the batch, nothing is queued and the response is `503` with `Retry-After`.
- **Processing:** a background worker in the same application takes batches from the queue, classifies and scores them (Redis holds the per-IP repeat-offender window) and writes them to PostgreSQL. A failed write is retried with backoff until it succeeds. Queued events live only in memory: they are lost if the process dies (a normal shutdown drains the queue first).
- **Storage:** PostgreSQL, schema managed by Flyway (`src/main/resources/db/migration`). Each batch is one transaction: events are inserted with `ON CONFLICT (event_id) DO NOTHING`, and only the newly inserted ones are added to the `event_rollups_minute` counters (one row per tenant, minute, category, severity and action). A retried batch is therefore stored and counted exactly once. The stats endpoints read the rollups instead of scanning events.

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
