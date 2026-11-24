package com.miniwsa.samples;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;

import java.time.Instant;

/**
 * Filters and page for {@link com.miniwsa.storage.EventStore#findSamples}. A {@code null} filter means "no filter on
 * that field". The time window is {@code [from, to)} on event time.
 *
 * <p>Checking user input (allowed limit range, from before to) is the API's job; this only rejects
 * values no caller should pass.
 *
 * @param limit  most events in the page, at least 1
 * @param offset events to skip (newest first), at least 0
 */
public record SampleQuery(Long configId, Instant from, Instant to, Category category, Action action,
                          int limit, int offset) {

    public SampleQuery {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, was " + limit);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative, was " + offset);
        }
    }
}
