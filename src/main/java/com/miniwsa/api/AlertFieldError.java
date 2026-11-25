package com.miniwsa.api;

/**
 * One problem with one field of an alert definition body.
 *
 * @param field   the JSON field name, e.g. {@code windowMinutes}; empty if the whole body is wrong
 * @param message what is wrong with it
 */
public record AlertFieldError(String field, String message) {
}
