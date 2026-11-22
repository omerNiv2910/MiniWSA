package com.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings bound from the {@code miniwsa.*} namespace.
 */
@ConfigurationProperties(prefix = "miniwsa")
public record MiniWsaProperties(Topics topics, Ingest ingest) {

    public record Topics(String raw, String dlq) {
    }

    /**
     * @param maxBatchSize the most events a single ingest request may carry
     */
    public record Ingest(int maxBatchSize) {
    }
}
