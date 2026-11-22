package com.miniwsa.api;

import com.miniwsa.ingest.IngestService;
import com.miniwsa.model.SecurityEvent;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * {@code POST /v1/events/ingest}: accepts a JSON array of events, or a single event object, which
 * Jackson reads as a one-event list ({@code spring.jackson.deserialization.accept-single-value-as-array}).
 *
 * <p>Jackson parses the body and Spring validates every element before this method runs; both
 * kinds of failure are turned into a 400 with per-event errors by {@link ApiExceptionHandler}.
 * If any event is invalid the whole request is rejected and nothing is ingested.
 */
@RestController
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping(path = "/v1/events/ingest", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public IngestResponse ingest(@RequestBody List<@Valid @NotNull SecurityEvent> events) {
        if (events.isEmpty()) {
            throw new InvalidEventsException("Request must contain at least one event", List.of());
        }
        return new IngestResponse(ingestService.ingest(events).size());
    }
}
