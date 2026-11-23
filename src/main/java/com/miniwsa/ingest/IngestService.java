package com.miniwsa.ingest;

import com.miniwsa.model.SecurityEvent;
import com.miniwsa.queue.EventQueue;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Accepts already-validated events, stamps them with the server receive time and puts them on
 * the in-memory {@link EventQueue} for the storage worker.
 */
@Service
public class IngestService {

    private final EventQueue queue;
    private final Clock clock;

    public IngestService(EventQueue queue, Clock clock) {
        this.queue = queue;
        this.clock = clock;
    }

    /**
     * Stamps every event in the batch with the same {@code receivedAt} and queues them all.
     *
     * @return the stamped events
     * @throws EventPublishException if the queue has no room for the whole batch (nothing is queued)
     */
    public List<SecurityEvent> ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.instant();
        List<SecurityEvent> stamped = events.stream()
                .map(event -> event.withReceivedAt(receivedAt))
                .toList();
        queue.publish(stamped);
        return stamped;
    }
}
