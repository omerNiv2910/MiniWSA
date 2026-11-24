package com.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Application settings bound from the {@code miniwsa.*} namespace.
 */
@ConfigurationProperties(prefix = "miniwsa")
public record MiniWsaProperties(Queue queue, @DefaultValue Samples samples, @DefaultValue Timeseries timeseries,
                                @DefaultValue RateLimit rateLimit) {

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

    /**
     * @param queryTimeout longest one samples request (count plus page) may run in PostgreSQL before
     *                     it is cancelled; at least 1ms. Defaults to 5s, also when the whole
     *                     {@code miniwsa.samples} block is left out.
     */
    public record Samples(@DefaultValue("5s") Duration queryTimeout) {
    }

    /**
     * @param queryTimeout longest one timeseries request (one bucketed count) may run in PostgreSQL
     *                     before it is cancelled; at least 1ms. Defaults to 5s, also when the whole
     *                     {@code miniwsa.timeseries} block is left out.
     */
    public record Timeseries(@DefaultValue("5s") Duration queryTimeout) {
    }

    /**
     * Per-client-IP request budget for the read endpoints ({@code GET /v1/stats/**} and
     * {@code /v1/events/samples}), which share one fixed window. Each key defaults on its own, also
     * when the whole {@code miniwsa.rate-limit} block is left out. Bad values fail startup rather
     * than silently disabling or breaking the limiter.
     *
     * @param enabled whether requests are counted and limited at all; defaults to true
     * @param limit   most requests one client IP may make per window; at least 1, defaults to 100
     * @param window  length of the fixed window; at least 1s, defaults to 1m
     */
    public record RateLimit(@DefaultValue("true") boolean enabled, @DefaultValue("100") int limit,
                            @DefaultValue("1m") Duration window) {

        public RateLimit {
            if (limit < 1) {
                throw new IllegalArgumentException("miniwsa.rate-limit.limit must be at least 1");
            }
            if (window == null || window.compareTo(Duration.ofSeconds(1)) < 0) {
                throw new IllegalArgumentException("miniwsa.rate-limit.window must be at least 1s");
            }
        }
    }
}
