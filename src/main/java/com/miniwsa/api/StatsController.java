package com.miniwsa.api;

import com.miniwsa.stats.StatsSummary;
import com.miniwsa.storage.EventStore;
import org.springframework.web.bind.annotation.GetMapping;
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
 * counts cover all configurations.
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
public class StatsController {

    private final EventStore eventStore;

    public StatsController(EventStore eventStore) {
        this.eventStore = eventStore;
    }

    @GetMapping("/v1/stats/summary")
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
}
