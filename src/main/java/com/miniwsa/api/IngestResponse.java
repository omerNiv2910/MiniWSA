package com.miniwsa.api;

import org.springframework.http.HttpStatus;

/**
 * Body of a successful ({@code 201 Created}) ingest response.
 *
 * <p>The events themselves are not echoed back: they are enriched later by the queue consumer, so the
 * copy the client would get is not the stored result anyway.
 *
 * @param status   the HTTP status code, repeated in the body like the {@code status} of error responses
 * @param message  human-readable confirmation
 * @param accepted how many events were accepted and queued
 */
public record IngestResponse(int status, String message, int accepted) {

    static final String SUCCESS_MESSAGE = "Events accepted for processing";

    public static IngestResponse of(int accepted) {
        return new IngestResponse(HttpStatus.CREATED.value(), SUCCESS_MESSAGE, accepted);
    }
}
