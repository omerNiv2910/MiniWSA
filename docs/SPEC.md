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
before the current one (so the 6th+ event in the window gets the bonus). The window is
`[timestamp - 10 min, timestamp]`, both ends included; events with a later timestamp do not count.

**DECISION:** recent events per `clientIp` live in Redis, one sorted set per IP (member `eventId`,
score = timestamp), updated and counted in one pipelined call per batch. Redis over an in-memory map
because the history survives restarts and is shared if more than one instance runs. Old entries are
trimmed on every batch, a quiet IP's key expires after 15 minutes, and each IP keeps at most its
newest 1000 entries. If Redis is down the batch is not stored and is retried, so no event is ever
stored with a score missing its bonus.

Store original fields + `attackType`, `threatScore`, `receivedAt`.

**DECISION:** all three server-set fields are filled in by the queue consumer, not at ingest.
`receivedAt` is the time the consumer handles the batch (the same for the whole batch); a retried
batch is stamped again with the retry time. Ingest only validates and queues.

## 3. Stats - `GET /v1/stats/summary?configId&from&to`

`configId, timeRange, totalEvents, byCategory{count, avgThreatScore}, byAction, topAttackers[10]
{clientIp,count,avgThreatScore}, topTargetedPaths[10]{path,count}`. `configId` optional
(all configs).

**DECISION:** `from`/`to` are required; `from` inclusive, `to` exclusive; `from >= to` is 400.

*(Done: all fields.)*

**DECISION — parameters.** Errors are a `400` problem+json with
`detail: "Invalid query parameters"` and `errors: [{field, message}]`.
- `from`/`to` are required request parameters: a missing one is rejected by Spring before any other
  check, so only that one is reported (`from` first) as `is required`. Once both are present, every
  problem with `configId`, `from` and `to` is collected and reported together, in that order. An empty value (`from=`) → `is required`. They must be ISO-8601 with an offset (`Z` or
  `+02:00`, sent as `%2B02:00` since a bare `+` in a query string is a space); fractional seconds are
  allowed; other formats (date only, no offset, epoch numbers) → the ingest timestamp message.
  Offsets are converted to UTC. `from >= to` → field `from`, `must be before to`.
- `configId`: absent means all configurations. When present it must be a positive integer that fits
  a `long`; anything else, including an empty `configId=`, → `must be a positive integer`.
- Unknown parameters are ignored.

**DECISION — response.**
- `configId` is left out of the JSON when absent (all configurations).
- `timeRange` echoes `from`/`to` as UTC instants (`...Z`).
- Events are selected by event `timestamp`, never `receivedAt`.
- `totalEvents` equals the sum of the `byCategory` counts and the sum of the `byAction` counts.
- `byCategory`/`byAction` contain only keys with events, highest count first (ties by name); an
  empty window gives `totalEvents: 0` and `{}` for both.
- `avgThreatScore` averages the events that have a score (unscored events still count in `count`),
  rounded half up to 1 decimal and always written with one decimal (`75.0`); it is `null` when no
  event in the group has a score. A score of `0` is a real score. Same rule for categories and attackers.
- `topAttackers` (`{clientIp, count, avgThreatScore}`) and `topTargetedPaths` (`{path, count}`) hold at
  most 10 entries, most events first; ties are broken by the IP or path in byte order (`COLLATE "C"`,
  so `10.0.0.2` comes before `9.0.0.1`). Paths are grouped by their exact text. Without `configId`
  an IP or path seen in several configurations is one entry. An empty window gives `[]` for both.

**DECISION — implementation.** Computed directly from `events` with five plain SQL queries
(`count(*)`, `GROUP BY category`, `GROUP BY action`, `GROUP BY client_ip … LIMIT 10`,
`GROUP BY path … LIMIT 10`) run in one read-only `REPEATABLE READ` transaction, so they all see the
same events, using the `(config_id, timestamp)` and `(timestamp)` indexes. The per-minute rollup table is not built yet
(its DDL in V1 is commented out). The request is answered synchronously from PostgreSQL, not through
the ingest queue, so events still waiting in the queue are not counted yet.

## 4. Samples - `GET /v1/events/samples`

Filters (all optional): `configId, from, to, category, action`. `limit` default 20, max 100;
`offset`. Sorted by `timestamp` desc. Response includes `total`.

## 5. Data generator

Realistic random events, attack waves (same IP + same path bursts), configurable count
(e.g. 10,000), output feedable to the ingest API.

## Technical

Java + Spring Boot; storage justified for big-data scale (PostgreSQL, with per-minute rollups and time partitioning as the scale-out path, plus Redis);
unit tests for classification/enrichment; at least one API integration test; README with
build/run, curl docs, architecture (+diagram), storage justification, improvements, challenges,
one thing learned. Bonus: docker compose for the full stack; one of alerting / timeseries /
rate limiting. Milestone tags: `v0.1-ingestion`, `v0.2-enrichment`, `v0.3-stats`, ...
