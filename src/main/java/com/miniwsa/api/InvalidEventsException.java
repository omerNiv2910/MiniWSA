package com.miniwsa.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Thrown when an ingest request is rejected. Spring renders it as a 400 problem-details response,
 * with the individual problems in its {@code errors} property.
 */
public class InvalidEventsException extends ResponseStatusException {

    private final List<EventError> errors;

    public InvalidEventsException(String reason, List<EventError> errors) {
        super(HttpStatus.BAD_REQUEST, reason);
        this.errors = List.copyOf(errors);
        getBody().setProperty("errors", this.errors);
    }

    public List<EventError> getErrors() {
        return errors;
    }
}
