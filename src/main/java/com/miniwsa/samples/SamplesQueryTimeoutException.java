package com.miniwsa.samples;

/**
 * A samples query was cancelled by PostgreSQL because it ran past {@code miniwsa.samples.query-timeout}.
 * The database is fine; the request was too expensive, so the API reports it as temporarily unavailable.
 */
public class SamplesQueryTimeoutException extends RuntimeException {

    public SamplesQueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
