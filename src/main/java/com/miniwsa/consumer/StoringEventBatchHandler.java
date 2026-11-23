package com.miniwsa.consumer;

import com.miniwsa.enrichment.EventEnricher;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.storage.EventStore;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Stamps, enriches and stores each consumed batch.
 *
 * <p>All server-set fields are filled in here, in the consumer, not at ingest: the ingest path only
 * validates and publishes.
 * <ul>
 *   <li>{@code receivedAt}: the time the consumer handles the batch, the same for every event in it. A
 *       retried batch is stamped again with the retry time.</li>
 *   <li>{@code attackType} and {@code threatScore}: recomputed on every attempt, which gives the same
 *       result.</li>
 * </ul>
 * {@link EventEnricher#enrich} sets all three.
 *
 * <p>The repeat-offender count is always 0 until the per-IP Redis tracker is wired in, so no event gets
 * the repeat-offender bonus yet.
 */
@Component
class StoringEventBatchHandler implements EventBatchHandler {

    private final EventStore store;
    private final Clock clock;

    StoringEventBatchHandler(EventStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void handle(List<SecurityEvent> events) {
        Instant receivedAt = clock.instant();
        events.forEach(event -> EventEnricher.enrich(event, receivedAt, 0));
        store.saveBatch(events);
    }
}
