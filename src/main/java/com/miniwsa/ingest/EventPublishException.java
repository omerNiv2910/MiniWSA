package com.miniwsa.ingest;

/**
 * Thrown when a batch could not be queued because the in-memory queue has no room for all of it.
 * Nothing from the batch was queued, so the client can retry the whole request later.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message) {
        super(message);
    }
}
