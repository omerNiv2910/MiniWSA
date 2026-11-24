package com.miniwsa.samples;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.miniwsa.model.SecurityEvent;

import java.util.List;

/**
 * One page of sample events, newest first. Also the samples response body: {@code {"total": n, "events": [...]}}.
 *
 * @param total  how many events match the filters, regardless of limit and offset
 * @param events the events in this page
 */
@JsonPropertyOrder({"total", "events"})
public record SampleResponse(long total, List<SecurityEvent> events) {

    public SampleResponse {
        events = List.copyOf(events);
    }
}
