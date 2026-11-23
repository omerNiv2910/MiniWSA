/**
 * PostgreSQL storage: events (idempotent by eventId) and per-minute rollup counters, written in one transaction per batch. Schema lives in db/migration (Flyway).
 */
package com.miniwsa.storage;
