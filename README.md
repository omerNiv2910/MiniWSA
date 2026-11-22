# MiniWSA

Mini Security Analytics Pipeline: a Spring Boot service that ingests security events, enriches them (classification and threat score), and serves summary statistics and samples.

## Architecture

- **Ingestion:** `POST /v1/events/ingest` validates the request, produces it to Kafka (`dlr.events.raw`, keyed by client IP, `acks=all`) and returns `201` once the broker acknowledges.
- **Processing:** Kafka consumers run in the same application. They batch-consume raw events, classify and score them (Redis holds the per-IP repeat-offender window), write to MongoDB and commit offsets only after the write. Records that can't be processed go to `dlr.events.dlq`.
- **Storage:** sharded MongoDB. Events are stored with `_id = eventId` so retries are idempotent. Per-minute rollup counter documents are updated with `$inc` upserts and back the stats endpoints.

## Package layout

| Package | Responsibility |
|---|---|
| `api` | REST controllers and DTOs |
| `ingest` | Validation and producing to Kafka |
| `consumer` | Kafka batch consumers and DLQ routing |
| `enrichment` | Classification and threat scoring |
| `storage` | Mongo documents, repositories, rollups |
| `stats` | Summary statistics and paginated samples |
| `generator` | Synthetic data generator |
| `config` | Kafka topics and application properties |

## Running locally

Requirements: Java 21 and Docker.

```bash
docker compose up -d        # Kafka, MongoDB, Redis
./mvnw spring-boot:run      # starts the service on :8080
```

Health check: `curl localhost:8080/actuator/health`

Connection settings can be overridden with `KAFKA_BOOTSTRAP_SERVERS`, `MONGODB_URI`, `REDIS_HOST` and `REDIS_PORT`.

## Building and testing

```bash
./mvnw verify
```
