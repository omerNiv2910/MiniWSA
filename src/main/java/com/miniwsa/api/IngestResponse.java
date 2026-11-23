package com.miniwsa.api;

import com.miniwsa.model.SecurityEvent;

import java.util.List;

/**
 * Body of a successful ingest response.
 *
 * @param accepted how many events were accepted
 * @param events   the accepted events as queued, including the server-set {@code receivedAt}
 */
public record IngestResponse(int accepted, List<SecurityEvent> events) {

    public static IngestResponse of(List<SecurityEvent> events) {
        return new IngestResponse(events.size(), events);
    }
}
