package com.miniwsa.enrichment;

import com.miniwsa.model.SecurityEvent;

import java.util.List;

/**
 * Remembers recent events per {@code clientIp} and answers, for each event in a batch, how many events
 * from the same IP fall in the 10 minutes before it.
 */
public interface ClientIpTracker {

    /**
     * Records the batch and returns one count per event, in the same order: the number of events from
     * the same clientIp with a timestamp in {@code [timestamp - 10 min, timestamp]}, not counting the
     * event itself. Earlier events of the same batch count too.
     *
     * <p>Must be safe to call again with the same batch (a retry): it returns the same counts and does
     * not count any event twice.
     *
     * @throws RuntimeException if the counts cannot be computed (e.g. Redis is down); the caller must
     *                          not store the batch without them
     */
    List<Long> priorCounts(List<SecurityEvent> events);
}
