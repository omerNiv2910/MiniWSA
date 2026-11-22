package com.miniwsa.ingest;

import com.miniwsa.config.MiniWsaProperties;
import com.miniwsa.model.SecurityEvent;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Accepts already-validated events, stamps them with the server receive time and produces them to
 * the raw events topic, keyed by {@code clientIp} so all events from one IP land on one partition.
 */
@Service
public class IngestService {

    private final KafkaTemplate<String, SecurityEvent> kafkaTemplate;
    private final Clock clock;
    private final String topic;
    private final Duration sendTimeout;

    public IngestService(KafkaTemplate<String, SecurityEvent> kafkaTemplate, Clock clock,
                         MiniWsaProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.clock = clock;
        this.topic = props.topics().raw();
        this.sendTimeout = props.ingest().sendTimeout();
    }

    /**
     * Stamps every event in the batch with the same {@code receivedAt}, produces them all and waits
     * until the broker has acknowledged every one.
     *
     * @return the stamped events
     * @throws EventPublishException if any event could not be written within the send timeout
     */
    public List<SecurityEvent> ingest(List<SecurityEvent> events) {
        Instant receivedAt = clock.instant();
        List<SecurityEvent> stamped = events.stream()
                .map(event -> event.withReceivedAt(receivedAt))
                .toList();

        List<CompletableFuture<SendResult<String, SecurityEvent>>> eventsToSend = new ArrayList<>(stamped.size());
        try {
            // Start every send before waiting, so the producer can batch them.
            for (SecurityEvent event : stamped) {
                eventsToSend.add(kafkaTemplate.send(topic, event.clientIp(), event));
            }
            CompletableFuture.allOf(eventsToSend.toArray(CompletableFuture[]::new))
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (KafkaException e) {
            // send() itself fails, e.g. when no broker metadata arrives within max.block.ms.
            throw new EventPublishException("Could not send events to Kafka", e);
        } catch (ExecutionException e) {
            throw new EventPublishException("Kafka did not accept all events", e.getCause());
        } catch (TimeoutException e) {
            throw new EventPublishException("Kafka did not acknowledge all events within " + sendTimeout, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EventPublishException("Interrupted while waiting for Kafka", e);
        }

        return stamped;
    }
}
