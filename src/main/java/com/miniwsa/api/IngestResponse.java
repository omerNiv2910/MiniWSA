package com.miniwsa.api;

/**
 * Body of a successful ingest response.
 *
 * @param accepted how many events were accepted
 */
public record IngestResponse(int accepted) {
}
