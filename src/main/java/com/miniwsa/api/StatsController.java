package com.miniwsa.api;

import com.miniwsa.stats.StatsSummary;
import com.miniwsa.stats.Timeseries;
import com.miniwsa.stats.TimeseriesInterval;
import com.miniwsa.storage.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code GET /v1/stats/summary?configId=&from=&to=}: event counts for a time window, in total,
 * per category (with the average threat score), per action, and for the top 10 client IPs and
 * paths. Without {@code configId} the
 * counts cover all configurations. Also {@code GET /v1/stats/timeseries}, counts per time bucket,
 * whose parameters follow the same rules (see {@link #timeseries}).
 *
 * <p>Answered straight from PostgreSQL in the request, not through the ingest queue, so events
 * still in the queue are not counted yet.
 *
 * <p>{@code from} and {@code to} are required: when one is missing Spring rejects the request before
 * this method runs, and {@link ApiExceptionHandler} reports it as {@code errors: [{field, "is required"}]}.
 * Otherwise the parameters are taken as plain strings and checked here; every problem is collected
 * and they are all reported together in one 400 with {@code errors: [{field, message}]}:
 * <ul>
 *   <li>{@code configId} may be absent (all configurations); when present it must be a positive
 *       integer, so an empty {@code configId=} is an error, not "all".</li>
 *   <li>{@code from} and {@code to} are required ISO-8601 timestamps with an offset ({@code Z} or e.g.
 *       {@code +02:00}, sent as {@code %2B02:00} since a bare {@code +} arrives as a space), and
 *       {@code from} must be before {@code to}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    static final String TOO_MANY_BUCKETS_MESSAGE = "too many buckets for this range: at most "
            + Timeseries.MAX_BUCKETS + "; use a larger interval or a shorter range";

    private final EventStore eventStore;

    public StatsController(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping("/summary")
    public StatsSummary summary(@RequestParam(name = "configId", required = false) String configId,
                                @RequestParam(name = "from") String from,
                                @RequestParam(name = "to") String to) {
        List<QueryParamError> errors = new ArrayList<>();
        Long parsedConfigId = parseConfigId(configId, errors);
        Instant parsedFrom = parseTimestamp("from", from, errors);
        Instant parsedTo = parseTimestamp("to", to, errors);
        if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) {
            errors.add(new QueryParamError("from", "must be before to"));
        }
        if (!errors.isEmpty()) {
            throw new InvalidQueryParametersException(errors);
        }
        return eventStore.summarize(parsedConfigId, parsedFrom, parsedTo);
    }

    /**
     * {@code GET /v1/stats/timeseries?configId=&from=&to=&interval=}: event counts per {@code interval}
     * bucket ({@code 1m}, {@code 5m} or {@code 1h}) over {@code [from, to)}, every bucket present (0 when
     * empty), oldest first. Buckets are aligned to the UTC epoch, so the first may start before
     * {@code from}.
     *
     * <p>Checked like the summary: a missing {@code from}, {@code to} or {@code interval} is rejected by
     * Spring (first missing one only); otherwise all problems are collected and reported together, in
     * the order configId, from, to, interval, then {@code from} before {@code to}. Only when everything
     * else is valid is the bucket count checked, so a wide range with a small interval is rejected
     * (at most {@link Timeseries#MAX_BUCKETS}) rather than building a huge response. A query
     * cancelled for running past {@code miniwsa.timeseries.query-timeout} is a 503.
     */
    @GetMapping("/timeseries")
    public Timeseries timeseries(@RequestParam(name = "configId", required = false) String configId,
                                 @RequestParam(name = "from") String from,
                                 @RequestParam(name = "to") String to,
                                 @RequestParam(name = "interval") String interval) {
        List<QueryParamError> errors = new ArrayList<>();
        Long parsedConfigId = parseConfigId(configId, errors);
        Instant parsedFrom = parseTimestamp("from", from, errors);
        Instant parsedTo = parseTimestamp("to", to, errors);
        TimeseriesInterval parsedInterval = parseInterval(interval, errors);
        if (parsedFrom != null && parsedTo != null && !parsedFrom.isBefore(parsedTo)) {
            errors.add(new QueryParamError("from", "must be before to"));
        }
        if (errors.isEmpty()
                && parsedInterval.bucketCount(parsedFrom, parsedTo) > Timeseries.MAX_BUCKETS) {
            errors.add(new QueryParamError("interval", TOO_MANY_BUCKETS_MESSAGE));
        }
        if (!errors.isEmpty()) {
            throw new InvalidQueryParametersException(errors);
        }
        return eventStore.timeseries(parsedConfigId, parsedFrom, parsedTo, parsedInterval);
    }

    // #################### Private Methods ####################

    private static Long parseConfigId(String value, List<QueryParamError> errors) {
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(value);
            if (id > 0) {
                return id;
            }
        } catch (NumberFormatException e) {
            // Not a whole number, empty ("configId="), or too large for a long: reported below.
        }
        errors.add(new QueryParamError("configId", "must be a positive integer"));
        return null;
    }

    private static Instant parseTimestamp(String name, String value, List<QueryParamError> errors) {
        // Present but empty, e.g. "from=" (a missing parameter never reaches this method).
        if (value.isBlank()) {
            errors.add(new QueryParamError(name, "is required"));
            return null;
        }
        try {
            return OffsetDateTime.parse(value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException e) {
            errors.add(new QueryParamError(name, ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE));
            return null;
        }
    }

    private static TimeseriesInterval parseInterval(String value, List<QueryParamError> errors) {
        // value is never null: a missing "interval" is rejected by Spring before this method runs.
        return TimeseriesInterval.fromLabel(value).orElseGet(() -> {
            errors.add(new QueryParamError("interval", TimeseriesInterval.ALLOWED_VALUES_MESSAGE));
            return null;
        });
    }
}
