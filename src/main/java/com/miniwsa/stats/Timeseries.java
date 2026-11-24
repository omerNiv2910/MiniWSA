package com.miniwsa.stats;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Event counts per time bucket for one window, for one configuration or for all of them.
 *
 * <p>Shaped like {@link StatsSummary}: {@code configId} is left out of the JSON when null, and
 * {@code from}/{@code to} are written together as {@code "timeRange": {"from": ..., "to": ...}}.
 * {@code buckets} is expected to be zero-filled and ascending (see {@link #zeroFilled}).
 * {@code totalEvents} is derived from the buckets rather than passed in, so it cannot disagree with
 * them.
 */
@JsonPropertyOrder({"configId", "timeRange", "interval", "totalEvents", "buckets"})
public record Timeseries(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long configId,
        @JsonIgnore Instant from,
        @JsonIgnore Instant to,
        TimeseriesInterval interval,
        List<Bucket> buckets) {

    /** Most buckets one response may hold; keeps the zero-filled list and the JSON bounded. */
    public static final int MAX_BUCKETS = 2880;

    public Timeseries {
        buckets = List.copyOf(buckets);
    }

    /**
     * The timeseries of {@code [from, to)} with every bucket present, in ascending order, each with
     * its count from {@code counts} (keyed by bucket start) or 0 when absent. Keys that are not a
     * bucket start of this window are ignored.
     *
     * @throws IllegalArgumentException if {@code from} is not before {@code to}, or the window
     *                                  needs more than {@link #MAX_BUCKETS} buckets
     */
    public static Timeseries zeroFilled(Long configId, Instant from, Instant to,
                                        TimeseriesInterval interval, Map<Instant, Long> counts) {
        long count = interval.bucketCount(from, to);
        if (count > MAX_BUCKETS) {
            throw new IllegalArgumentException(
                    "window needs " + count + " buckets, more than " + MAX_BUCKETS);
        }
        List<Bucket> buckets = new ArrayList<>((int) count);
        Instant start = interval.floor(from);
        for (long i = 0; i < count; i++) {
            buckets.add(new Bucket(start, counts.getOrDefault(start, 0L)));
            start = start.plusSeconds(interval.seconds());
        }
        return new Timeseries(configId, from, to, interval, buckets);
    }

    /** The requested window as written to JSON: {@code from} inclusive, {@code to} exclusive. */
    @JsonProperty("timeRange")
    public Map<String, Instant> timeRange() {
        Map<String, Instant> range = new LinkedHashMap<>();
        range.put("from", from);
        range.put("to", to);
        return range;
    }

    /** Sum of all bucket counts. */
    @JsonProperty("totalEvents")
    public long totalEvents() {
        return buckets.stream().mapToLong(Bucket::count).sum();
    }

    /** Events in the bucket {@code [start, start + interval)}. */
    public record Bucket(Instant start, long count) {
    }
}
