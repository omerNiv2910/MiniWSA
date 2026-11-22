package com.miniwsa.ingest;

import com.miniwsa.model.SecurityEvent;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * Accepts already-validated events, stamps them with the server receive time and hands them on.
 */
@Service
public class IngestService {

    private final Clock clock;

    public IngestService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Stamps every event in the batch with the same {@code receivedAt} and forwards them.
     *
     * @return the stamped events
     */
    public List<SecurityEvent> ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.instant();
        List<SecurityEvent> stamped = events.stream()
                .map(event -> event.withReceivedAt(receivedAt))
                .toList();

        // TODO: produce each event to the raw Kafka topic, keyed by clientIp, and wait for the acks.

        return stamped;
    }
}
