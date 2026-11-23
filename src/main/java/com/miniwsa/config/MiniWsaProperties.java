package com.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * Application settings bound from the {@code miniwsa.*} namespace.
 */
@ConfigurationProperties(prefix = "miniwsa")
public record MiniWsaProperties(Queue queue) {

    /**
     * @param capacity             most events held in memory; ingest answers 503 when a request doesn't fit
     * @param batchSize            most events the worker hands to storage at once
     * @param pollTimeout          how long the worker waits for an event before checking for shutdown
     * @param retryInitialInterval first wait after a failed batch; doubles on each further failure
     * @param retryMaxInterval     longest wait between retries
     * @param shutdownTimeout      how long shutdown waits for the worker to drain the queue
     */
    public record Queue(int capacity, int batchSize, Duration pollTimeout, Duration retryInitialInterval,
                        Duration retryMaxInterval, Duration shutdownTimeout) {
    }
}
