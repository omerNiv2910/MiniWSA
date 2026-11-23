package com.miniwsa.api;

/**
 * One problem with one query parameter.
 *
 * @param field   the parameter name, e.g. {@code from}
 * @param message what is wrong with it
 */
public record QueryParamError(String field, String message) {
}
