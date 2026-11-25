-- GET /v1/alerts/evaluate counts, per alert rule, the events of one category in a time window:
--   WHERE category = ? AND timestamp >= ? AND timestamp < ?
-- With this index each count is one range of the index (often an index-only scan) instead of reading
-- every event in the window and checking its category. num_of_events is not here: it lives in the
-- alerts table and is compared with the count afterwards, so it never filters events.
-- Cost: one more index to maintain on every insert into events.
-- A plain CREATE INDEX blocks writes to events while it builds; on a large production table use
-- CREATE INDEX CONCURRENTLY in a non-transactional migration instead.
CREATE INDEX events_category_time_idx ON events (category, timestamp);
