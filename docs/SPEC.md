# MiniWSA specification

Condensed from the assignment brief. Agents use this as the source of truth; decisions taken
where the brief is ambiguous are marked **DECISION**.

## Event (DLR)

Fields: `eventId, timestamp (ISO-8601), configId, policyId, clientIp, hostname, path, method,
statusCode, userAgent, rule{id,name,message,severity,category}, action, geoLocation{country,city},
requestSize, responseSize`. Server adds `receivedAt`, `attackType`, `threatScore`.

- `rule.category`: INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT
- `rule.severity`: CRITICAL, HIGH, MEDIUM, LOW
- `action`: DENY, ALERT, MONITOR

## 1. Ingestion - `POST /v1/events/ingest`

Single event or array. Validate required fields, enums, timestamp. `201` on success,
`400` with per-event details on validation errors. Stamp `receivedAt`. *(Done in v0.1.)*

## 2. Classification and enrichment

| category | attackType |
|---|---|
| INJECTION | SQL/Command Injection |
| XSS | Cross-Site Scripting |
| PROTOCOL_VIOLATION | Protocol Anomaly |
| DATA_LEAKAGE | Data Exfiltration |
| BOT | Bot Activity |
| DOS | Denial of Service |
| RATE_LIMIT | Rate Limiting |

Threat score (0-100, capped): severity CRITICAL 40 / HIGH 30 / MEDIUM 20 / LOW 10;
action DENY +20 / ALERT +10 / MONITOR +0; path contains `/admin` or `/login` +15;
more than 5 events from the same `clientIp` in the last 10 minutes +15.

**DECISION:** the 10-minute window is measured on event `timestamp` and counts the events
before the current one (so the 6th+ event in the window gets the bonus).

Store original fields + `attackType`, `threatScore`, `receivedAt`.

**DECISION:** all three server-set fields are filled in by the queue consumer, not at ingest.
`receivedAt` is the time the consumer handles the batch (the same for the whole batch); a retried
batch is stamped again with the retry time. Ingest only validates and queues.

## 3. Stats - `GET /v1/stats/summary?configId&from&to`

`configId, timeRange, totalEvents, byCategory{count, avgThreatScore}, byAction, topAttackers[10]
{clientIp,count,avgThreatScore}, topTargetedPaths[10]{path,count}`. `configId` optional
(all configs).

**DECISION:** `from`/`to` are required; `from` inclusive, `to` exclusive; `from >= to` is 400.

## 4. Samples - `GET /v1/events/samples`

Filters (all optional): `configId, from, to, category, action`. `limit` default 20, max 100;
`offset`. Sorted by `timestamp` desc. Response includes `total`.

## 5. Data generator

Realistic random events, attack waves (same IP + same path bursts), configurable count
(e.g. 10,000), output feedable to the ingest API.

## Technical

Java + Spring Boot; storage justified for big-data scale (PostgreSQL with per-minute rollups, time partitioning as the scale-out path, plus Redis);
unit tests for classification/enrichment; at least one API integration test; README with
build/run, curl docs, architecture (+diagram), storage justification, improvements, challenges,
one thing learned. Bonus: docker compose for the full stack; one of alerting / timeseries /
rate limiting. Milestone tags: `v0.1-ingestion`, `v0.2-enrichment`, `v0.3-stats`, ...
