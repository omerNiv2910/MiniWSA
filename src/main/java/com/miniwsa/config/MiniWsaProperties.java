package com.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings bound from the {@code miniwsa.*} namespace.
 */
@ConfigurationProperties(prefix = "miniwsa")
public record MiniWsaProperties(Topics topics, Ingest ingest) {

    public record Topics(String raw, String dlq) {
    }

    /**
     * @param sendTimeout how long an ingest request waits for Kafka to acknowledge its whole batch;
     *                    keep it above the producer's {@code delivery.timeout.ms}
     */
    public record Ingest(Duration sendTimeout) {
    }
}
