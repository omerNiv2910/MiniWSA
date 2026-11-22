package com.miniwsa.api;

/**
 * One problem with one event in an ingest request.
 *
 * @param index   position of the event in the request (0 for a single-object request)
 * @param field   path of the offending field, e.g. {@code rule.category}; empty if the whole event is wrong
 * @param message what is wrong with it
 */
public record EventError(int index, String field, String message) {
}
