package com.miniwsa.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

/**
 * Thrown when a GET request has bad query parameters. Spring renders it as a 400 problem-details
 * response, with the individual problems in its {@code errors} property.
 */
public class InvalidQueryParametersException extends ResponseStatusException {

    private final List<QueryParamError> errors;

    public InvalidQueryParametersException(List<QueryParamError> errors) {
        super(HttpStatus.BAD_REQUEST, "Invalid query parameters");
        this.errors = List.copyOf(errors);
        getBody().setProperty("errors", this.errors);
    }

    public List<QueryParamError> getErrors() {
        return errors;
    }
}
