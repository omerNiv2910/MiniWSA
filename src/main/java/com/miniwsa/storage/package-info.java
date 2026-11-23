/**
 * PostgreSQL storage: events (idempotent by eventId), written in one transaction per batch, and the
 * aggregate query behind the stats summary. Schema lives in db/migration (Flyway).
 */
package com.miniwsa.storage;
