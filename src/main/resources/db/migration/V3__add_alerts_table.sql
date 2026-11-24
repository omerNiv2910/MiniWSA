-- Alert definitions from POST /v1/alerts/define: "more than num_of_events events of category
-- within window_minutes minutes". One row per definition; GET /v1/alerts/evaluate reads them all.
-- The API validates every field first; the CHECKs keep bad rows out even if something bypasses it.
-- category is not CHECKed against the list of values, same as events.category, so adding a
-- Category value needs no migration.
CREATE TABLE alerts (
    id             BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name           VARCHAR(128) NOT NULL CONSTRAINT alerts_name_not_blank CHECK (btrim(name) <> ''),
    num_of_events  INT          NOT NULL CONSTRAINT alerts_num_of_events_range CHECK (num_of_events >= 0),
    category       VARCHAR(32)  NOT NULL,
    window_minutes INT          NOT NULL CONSTRAINT alerts_window_minutes_range CHECK (window_minutes BETWEEN 1 AND 1440)
);
