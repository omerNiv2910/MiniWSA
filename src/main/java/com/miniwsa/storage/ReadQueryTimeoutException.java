package com.miniwsa.storage;

/**
 * A read query was cancelled by PostgreSQL because it ran past its configured timeout
 * ({@code miniwsa.samples.query-timeout} or {@code miniwsa.timeseries.query-timeout}).
 * The database is fine; the request was too expensive, so the API reports it as temporarily unavailable.
 */
public class ReadQueryTimeoutException extends RuntimeException {

    public ReadQueryTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
