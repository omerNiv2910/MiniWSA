package com.miniwsa.ingest;

import com.miniwsa.model.SecurityEvent;
import com.miniwsa.queue.EventQueue;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Puts already-validated events on the in-memory {@link EventQueue} for the storage worker. It sets no
 * server-side fields: the consumer stamps {@code receivedAt} and enriches the events.
 */
@Service
public class IngestService {

    private final EventQueue queue;

    public IngestService(EventQueue queue) {
        this.queue = queue;
    }

    /**
     * Queues the whole batch as is.
     *
     * <p>The queued events belong to the consumer from here on (it changes them in place), so they are
     * not handed back to the caller.
     *
     * @return how many events were queued
     * @throws EventPublishException if the queue has no room for the whole batch (nothing is queued)
     */
    public int ingest(List<SecurityEvent> events) {
        queue.publish(events);
        return events.size();
    }
}
