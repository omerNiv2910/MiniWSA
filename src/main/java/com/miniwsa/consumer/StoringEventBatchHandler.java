package com.miniwsa.consumer;

import com.miniwsa.model.SecurityEvent;
import com.miniwsa.storage.EventStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Stores each consumed batch in PostgreSQL. Enrichment (classification and threat score) goes here,
 * before the save, once it exists.
 */
@Component
class StoringEventBatchHandler implements EventBatchHandler {

    private final EventStore store;

    StoringEventBatchHandler(EventStore store) {
        this.store = store;
    }

    @Override
    public void handle(List<SecurityEvent> events) {
        store.saveBatch(events);
    }
}
