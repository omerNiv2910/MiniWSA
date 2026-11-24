package com.miniwsa.stats;

import com.fasterxml.jackson.annotation.JsonValue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;

/**
 * Bucket width of a timeseries, as given in the {@code interval} query parameter, and the bucket
 * arithmetic that depends on it.
 *
 * <p>Only these three widths are accepted, and labels match exactly (case-sensitive, no
 * whitespace), so a request cannot ask for an arbitrary width. The database query binds
 * {@link #seconds()} as its bucket width too, so Java and PostgreSQL always bucket alike.
 *
 * <p>Buckets are aligned to the UTC epoch (like PostgreSQL {@code date_bin} with origin
 * {@code 1970-01-01T00:00:00Z}), not to the request's {@code from}, so the same event always lands
 * in the same bucket whatever window is asked for. The first bucket of a window therefore starts at
 * {@code floor(from)}, which can be before {@code from}; the last one is the one containing the
 * instant just before {@code to} (exclusive).
 */
public enum TimeseriesInterval {
    ONE_MINUTE("1m", 60),
    FIVE_MINUTES("5m", 300),
    ONE_HOUR("1h", 3600);

    /** Error text for an unknown label, listing the accepted ones in declaration order. */
    public static final String ALLOWED_VALUES_MESSAGE = "must be one of " + Arrays.toString(
            Arrays.stream(values()).map(TimeseriesInterval::label).toArray(String[]::new));

    private final String label;
    private final long seconds;

    TimeseriesInterval(String label, long seconds) {
        this.label = label;
        this.seconds = seconds;
    }

    /**
     * The interval with exactly this label.
     *
     * @return empty for null, blank, differently cased ({@code "5M"}) or unknown ({@code "2m"}) labels
     */
    public static Optional<TimeseriesInterval> fromLabel(String label) {
        return Arrays.stream(values()).filter(i -> i.label.equals(label)).findFirst();
    }

    /** The label as used in requests and written to JSON, e.g. {@code "5m"}. */
    @JsonValue
    public String label() {
        return label;
    }

    /** Bucket width in seconds. */
    public long seconds() {
        return seconds;
    }

    /** Bucket width as a {@link Duration}. */
    public Duration duration() {
        return Duration.ofSeconds(seconds);
    }

    /**
     * Start of the bucket containing {@code t}. Fractional seconds are dropped (floored), and
     * {@link Math#floorDiv} keeps instants before 1970 flooring down rather than towards zero.
     */
    public Instant floor(Instant t) {
        return Instant.ofEpochSecond(Math.floorDiv(t.getEpochSecond(), seconds) * seconds);
    }

    /**
     * Number of buckets covering {@code [from, to)}: {@code ceil((to - floor(from)) / width)}.
     *
     * @throws IllegalArgumentException if {@code from} is not before {@code to}
     */
    public long bucketCount(Instant from, Instant to) {
        if (!from.isBefore(to)) {
            throw new IllegalArgumentException("from must be before to");
        }
        Duration span = Duration.between(floor(from), to);
        long whole = span.getSeconds() / seconds;
        boolean partial = span.getSeconds() % seconds != 0 || span.getNano() != 0;
        return partial ? whole + 1 : whole;
    }
}
