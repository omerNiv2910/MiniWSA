-- Raw enriched events. One row per event; event_id is the primary key so a redelivered
-- event is skipped (INSERT ... ON CONFLICT DO NOTHING) instead of stored twice.
-- Nested JSON objects (rule, geoLocation) are flattened into columns.
CREATE TABLE events (
    event_id      VARCHAR(128)  PRIMARY KEY,
    event_time    TIMESTAMPTZ   NOT NULL,   -- the event's "timestamp"
    config_id     BIGINT        NOT NULL,
    policy_id     VARCHAR(128),
    client_ip     VARCHAR(45)   NOT NULL,
    hostname      VARCHAR(255)  NOT NULL,
    path          VARCHAR(2048) NOT NULL,
    method        VARCHAR(16)   NOT NULL,
    status_code   SMALLINT      NOT NULL,
    user_agent    VARCHAR(1024),
    rule_id       VARCHAR(64)   NOT NULL,
    rule_name     VARCHAR(128)  NOT NULL,
    rule_message  VARCHAR(512),
    severity      VARCHAR(16)   NOT NULL,
    category      VARCHAR(32)   NOT NULL,
    action        VARCHAR(16)   NOT NULL,
    geo_country   CHAR(2),
    geo_city      VARCHAR(128),
    request_size  BIGINT,
    response_size BIGINT,
    received_at   TIMESTAMPTZ   NOT NULL
);

-- Samples: a tenant's newest events first, and newest events across all tenants.
CREATE INDEX events_config_time_idx ON events (config_id, event_time DESC);
CREATE INDEX events_time_idx ON events (event_time DESC);

-- Event counts per tenant, minute of event time and (category, severity, action).
-- At most 7 x 4 x 3 = 84 rows per tenant-minute, so the stats endpoints read these instead of
-- scanning events. Rows are only ever changed by upserts that add to event_count.
-- CREATE TABLE event_rollups_minute (
--     config_id   BIGINT      NOT NULL,
--     minute      TIMESTAMPTZ NOT NULL,
--     category    VARCHAR(32) NOT NULL,
--     severity    VARCHAR(16) NOT NULL,
--     action      VARCHAR(16) NOT NULL,
--     event_count BIGINT      NOT NULL,
--     PRIMARY KEY (config_id, minute, category, severity, action)
-- );
--
-- -- Stats with configId omitted: all tenants over a time range.
-- CREATE INDEX event_rollups_minute_minute_idx ON event_rollups_minute (minute);
