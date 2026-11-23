package com.miniwsa.consumer;

import com.miniwsa.enrichment.ClientIpTracker;
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
 *       result. The frequent-client-IP count comes from the {@link ClientIpTracker}.</li>
 * </ul>
 * {@link EventEnricher#enrich} sets all three.
 *
 * <p>If the tracker fails (Redis down), nothing is stored and the exception reaches the worker, which
 * retries the batch. Storing without the bonus would leave wrong scores forever, since a stored row is
 * never rewritten.
 */
@Component
class StoringEventBatchHandler implements EventBatchHandler {

    private final ClientIpTracker tracker;
    private final EventStore store;
    private final Clock clock;

    StoringEventBatchHandler(ClientIpTracker tracker, EventStore store, Clock clock) {
        this.tracker = tracker;
        this.store = store;
        this.clock = clock;
    }

    @Override
    public void handle(List<SecurityEvent> events) {
        List<Long> priorCounts = tracker.priorCounts(events);
        if (priorCounts.size() != events.size()) {
            throw new IllegalStateException(
                    "Tracker returned " + priorCounts.size() + " counts for " + events.size() + " events");
        }
        Instant receivedAt = clock.instant();
        for (int i = 0; i < events.size(); i++) {
            EventEnricher.enrich(events.get(i), receivedAt, priorCounts.get(i));
        }
        store.saveBatch(events);
    }
}
