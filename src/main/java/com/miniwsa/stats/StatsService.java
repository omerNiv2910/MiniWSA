package com.miniwsa.stats;

import com.miniwsa.api.ApiExceptionHandler;
import com.miniwsa.api.InvalidQueryParametersException;
import com.miniwsa.api.QueryParamError;
import com.miniwsa.storage.EventStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * The logic behind {@code /v1/stats}: checks the raw query parameters, collecting every problem into one
 * {@link InvalidQueryParametersException}, then reads the answer from {@link EventStore}.
 *
 * <ul>
 *   <li>{@code configId} may be absent (all configurations); when present it must be a positive
 *       integer, so an empty {@code configId=} is an error, not "all".</li>
 *   <li>{@code from} and {@code to} are ISO-8601 timestamps with an offset ({@code Z} or e.g.
 *       {@code +02:00}), and {@code from} must be before {@code to}.</li>
 *   <li>{@code interval} (timeseries only) is {@code 1m}, {@code 5m} or {@code 1h}; only when everything
 *       else is valid is the bucket count checked (at most {@link Timeseries#MAX_BUCKETS}).</li>
 * </ul>
 */
@Service
public class StatsService {

    public static final String TOO_MANY_BUCKETS_MESSAGE = "too many buckets for this range: at most "
            + Timeseries.MAX_BUCKETS + "; use a larger interval or a shorter range";

    private final EventStore eventStore;

    public StatsService(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    public StatsSummary summary(String configId, String from, String to) {
        List<QueryParamError> errors = new ArrayList<>();
        Long parsedConfigId = parseConfigId(configId, errors);
        Instant parsedFrom = parseTimestamp("from", from, errors);
        Instant parsedTo = parseTimestamp("to", to, errors);
        checkFromBeforeTo(parsedFrom, parsedTo, errors);
        if (!errors.isEmpty()) {
            throw new InvalidQueryParametersException(errors);
        }
        return eventStore.summarize(parsedConfigId, parsedFrom, parsedTo);
    }

    public Timeseries timeseries(String configId, String from, String to, String interval) {
        List<QueryParamError> errors = new ArrayList<>();
        Long parsedConfigId = parseConfigId(configId, errors);
        Instant parsedFrom = parseTimestamp("from", from, errors);
        Instant parsedTo = parseTimestamp("to", to, errors);
        TimeseriesInterval parsedInterval = parseInterval(interval, errors);
        checkFromBeforeTo(parsedFrom, parsedTo, errors);
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

    private static void checkFromBeforeTo(Instant from, Instant to, List<QueryParamError> errors) {
        if (from != null && to != null && !from.isBefore(to)) {
            errors.add(new QueryParamError("from", "must be before to"));
        }
    }

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
        // Present but empty, e.g. "from=" (a missing parameter is rejected by Spring before this runs).
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
        // value is never null: a missing "interval" is rejected by Spring before this runs.
        return TimeseriesInterval.fromLabel(value).orElseGet(() -> {
            errors.add(new QueryParamError("interval", TimeseriesInterval.ALLOWED_VALUES_MESSAGE));
            return null;
        });
    }
}
