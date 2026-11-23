-- Enrichment computed by the queue consumer: the readable attack type and the threat score.
-- Both allow NULL: rows stored before this migration are not backfilled, and an event that was
-- never enriched is stored as "not scored" instead of being rejected. Readers treat NULL as
-- not scored (SQL AVG/COUNT(column) already skip NULLs).
-- The CHECK applies only when a score is present (a NULL passes a CHECK in PostgreSQL).
ALTER TABLE events
    ADD COLUMN attack_type  VARCHAR(32),
    ADD COLUMN threat_score SMALLINT CONSTRAINT events_threat_score_range CHECK (threat_score BETWEEN 0 AND 100);
