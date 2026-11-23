package com.miniwsa.consumer;

import com.miniwsa.model.SecurityEvent;

import java.util.List;

/**
 * Processes a batch of valid events read from the raw topic (enrichment and storage).
 *
 * <p>Must be idempotent: after a failure the whole batch is redelivered, including events that
 * were already handled. Throwing makes the consumer retry the batch with backoff; nothing is
 * committed until this returns.
 */
public interface EventBatchHandler {

    void handle(List<SecurityEvent> events);
}
