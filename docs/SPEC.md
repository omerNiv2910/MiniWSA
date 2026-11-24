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

*(Done.)*

**DECISION — parameters.** Spring binds and validates them as typed request parameters (like the
ingest body), so the controller holds no parsing code. Errors are a `400` problem+json with
`detail: "Invalid query parameters"` and `errors: [{field, message}]`.
- Every parameter is optional, and an empty value (`category=`, `limit=`) counts as absent: no
  filter, or the default. (Unlike stats, where an empty `configId=` is an error.)
- A value of the wrong type is reported on its own, since Spring stops at the first one:
  - `configId` not a whole number → `must be a positive integer`;
  - `from`/`to` not ISO-8601 with an offset (`Z` or `+02:00`, sent as `%2B02:00`) → the ingest
    timestamp message. Date only, no offset, RFC-1123 dates and epoch numbers are all rejected, the
    same as stats (they are bound as `OffsetDateTime`, since Spring's plain `Instant` conversion would
    take the last two);
  - `category`/`action` not an exact, uppercase enum name (`xss`, `SQLI`) → `must be one of [...]`
    with the allowed values;
  - `limit`/`offset` not an `int` → `must be an integer`.
- Out-of-range values are reported together: `configId` ≤ 0 → `must be a positive integer`;
  `limit` outside 1..100 → `must be greater than or equal to 1` / `less than or equal to 100`
  (rejected, never clamped); `offset` < 0 → `must be greater than or equal to 0`.
- `offset` has no maximum beyond fitting an `int`, as in the brief; the query timeout bounds deep pages.
- `from` inclusive, `to` exclusive, on event `timestamp` (not `receivedAt`), as in stats. With both
  given, `from >= to` → field `from`, `must be before to`.
- A repeated parameter (`limit=5&limit=6`) takes its first value. Unknown parameters are ignored.

**DECISION — response.** `{"total": n, "events": [...]}`, only what the brief asks for.
- `total` counts every matching event, regardless of `limit` and `offset`.
- `events` are full records: the ingested fields plus `receivedAt`, `attackType` and `threatScore`
  (`attackType`/`threatScore` left out when not set; `geoLocation` is `null` when none was sent).
- Order is `timestamp` desc, then `eventId` asc, so events with the same timestamp keep a fixed
  order and pages never overlap or skip.
- An `offset` past the end, or an unknown `configId`, is `200` with the real `total` and `[]`.

**DECISION — implementation.** `EventStore.findSamples` runs `count(*)` and one
`ORDER BY … LIMIT ? OFFSET ?` query in one read-only `REPEATABLE READ` transaction, so `total`
matches the page while new events are saved. The WHERE clause is built only from fixed fragments
with bound values. Both are bounded by `SET LOCAL statement_timeout`

## 5. Data generator

Realistic random events, attack waves (same IP + same path bursts), configurable count
(e.g. 10,000), output feedable to the ingest API.

## 6. Timeseries (bonus) - `GET /v1/stats/timeseries?configId&from&to&interval`

Event counts per time bucket (`1m`, `5m` or `1h`) over `[from, to)`, for drawing a line chart.

*(Done.)*

**DECISION — parameters.** Same rules and error format as the stats summary (§3): a `400`
problem+json with `detail: "Invalid query parameters"` and `errors: [{field, message}]`.
- `from`, `to` and `interval` are required. A missing one is rejected by Spring before any other check,
  so only the first missing one is reported (`from`, then `to`, then `interval`) as `is required`.
- `configId` and `from`/`to` are checked exactly as in §3 (empty `from=`/`to=` → `is required`;
  ISO-8601 with an offset only, converted to UTC; `configId` a positive `long`).
- `interval` must be exactly `1m`, `5m` or `1h`, case-sensitive, so `1M` cannot be read as a month.
  Anything else, including an empty `interval=`, → `must be one of [1m, 5m, 1h]`.
- `from >= to` → field `from`, `must be before to`.
- Problems are reported together in the order `configId`, `from`, `to`, `interval`, then `from`/`to`.
- **Bucket limit:** at most 2,880 buckets (2 days at `1m`, 10 days at `5m`, 120 days at `1h`). It is
  checked only when everything else is valid; more → field `interval`,
  `too many buckets for this range: at most 2880; use a larger interval or a shorter range`.
  The count is `ceil((to - floor(from)) / interval)`, so an unaligned `from` can add one bucket.
- Unknown parameters are ignored.

**DECISION — response.** `{configId?, timeRange{from,to}, interval, totalEvents, buckets[{start,count}]}`.
- `configId` is left out when absent (all configurations); `timeRange` echoes `from`/`to` in UTC, not
  rounded to a bucket; `interval` is the label (`"5m"`).
- Buckets are aligned to the UTC epoch (whole minutes, 5-minute marks, hours), not to `from`, so an event
  always lands in the same bucket whatever window is asked for. They run in ascending order from
  `floor(from)` up to, not including, `to`; `buckets[0].start` can be earlier than `from`.
- Only events with `timestamp` in `[from, to)` are counted, so the first and last buckets can be partial.
  An event exactly on a bucket boundary is in the later bucket.
- Every bucket is present: empty ones have `count: 0`, so the chart has no gaps. The list is never empty.
  No events, or an unknown `configId`, → `200` with all buckets `0`, not `404`.
- Events are bucketed by event `timestamp`, never `receivedAt`; events still in the ingest queue are not
  counted yet.
- `totalEvents` is the sum of the bucket counts, and equals the summary's `totalEvents` for the same window.
- Counts only; no split by category or action (the brief asks only for counts).

**DECISION — implementation.** `EventStore.timeseries` runs one query,
`SELECT date_bin(make_interval(secs => ?), timestamp, TIMESTAMPTZ '1970-01-01 00:00:00+00') AS bucket,
count(*) … GROUP BY bucket`, with the bucket width bound as `TimeseriesInterval.seconds()` (no request text
in the SQL) and the same WHERE clauses as the summary. Empty buckets are filled in Java
(`Timeseries.zeroFilled`), using the same epoch alignment (`TimeseriesInterval.floor`), so at most 2,880
small rows come back. It runs in a read-only `REPEATABLE READ` transaction with
`SET LOCAL statement_timeout` (`miniwsa.timeseries.query-timeout`, default `5s`); a cancelled query →
`503` problem+json, as for samples (`ReadQueryTimeoutException`, shared by both).

**DECISION — indexes.** No new index. Measured with `EXPLAIN ANALYZE` on 1,000,000 events (20 configs,
30 days), inserted in a transaction that was rolled back:

| Query | Matching events | Buckets | Time |
|---|---|---|---|
| `configId` + 1 day, `1m` | 1,655 | 1,440 | 8 ms |
| `configId` + 10 days, `5m` | 16,458 | 2,880 | 47 ms |
| `configId` + 30 days, `1h` | 50,000 | 720 | 32 ms |
| all configs + 2 days, `1m` | 66,392 | 2,880 | 92 ms |
| all configs + 30 days, `1h` | 1,000,000 | 720 | 314 ms (sequential scan) |

The cost grows with the number of matching events, not the number of buckets. At much larger volumes the
widest all-config windows reach the timeout and return `503`; the scale-out path is the per-minute rollup
table (commented out in V1), which would make a window read one row per minute instead of every event.

## Technical

Java + Spring Boot; storage justified for big-data scale (PostgreSQL, with per-minute rollups and time partitioning as the scale-out path, plus Redis);
unit tests for classification/enrichment; at least one API integration test; README with
build/run, curl docs, architecture (+diagram), storage justification, improvements, challenges,
one thing learned. Bonus: docker compose for the full stack; one of alerting / timeseries /
rate limiting. Milestone tags: `v0.1-ingestion`, `v0.2-enrichment`, `v0.3-stats`, ...
