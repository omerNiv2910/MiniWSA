package com.miniwsa.api;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.samples.SampleResponse;
import com.miniwsa.samples.SamplesService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;

/**
 * {@code GET /v1/events/samples?configId=&from=&to=&category=&action=&limit=&offset=}: the individual
 * events matching the filters, newest first (ties by eventId), one page at a time.
 *
 * <p>Every parameter is optional, and an empty value ({@code category=}) counts as absent. Spring binds
 * and validates them before this method runs, like the ingest body:
 * <ul>
 *   <li>A value of the wrong type ({@code limit=abc}, {@code category=xss}, {@code from=yesterday}) is
 *       reported by {@link ApiExceptionHandler}. Spring stops at the first such parameter.</li>
 *   <li>Out-of-range values ({@code configId=0}, {@code limit=101}, {@code offset=-1}) are reported
 *       together.</li>
 *   <li>{@code from} must be before {@code to}, checked in {@link SamplesService} since it involves two
 *       parameters.</li>
 * </ul>
 * All are a 400 with {@code errors: [{field, message}]}. A query PostgreSQL cancels for running past
 * {@code miniwsa.samples.query-timeout} is a 503.
 *
 * <p>{@code offset} has no upper limit beyond fitting an int, as in the brief. A very deep offset makes
 * PostgreSQL read and discard every skipped row; the query timeout is what bounds that.
 *
 * <p>{@code from} is inclusive and {@code to} exclusive, on event time. They are ISO-8601 timestamps with
 * an offset ({@code Z} or e.g. {@code +02:00}, sent as {@code %2B02:00}), the same as the stats summary;
 * they are bound as {@code OffsetDateTime} because Spring's plain {@code Instant} conversion also takes
 * RFC-1123 dates and epoch numbers. A repeated parameter
 * ({@code limit=5&limit=6}) takes its first value.
 */
@RestController
public class SamplesController {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    private final SamplesService samplesService;

    public SamplesController(SamplesService samplesService) {
        this.samplesService = samplesService;
    }

    @GetMapping("/v1/events/samples")
    public SampleResponse samples(
            @RequestParam(name = "configId", required = false) @Positive(message = "must be a positive integer")
            Long configId,
            @RequestParam(name = "from", required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam(name = "to", required = false) @DateTimeFormat(iso = ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(name = "category", required = false) Category category,
            @RequestParam(name = "action", required = false) Action action,
            @RequestParam(name = "limit", defaultValue = "" + DEFAULT_LIMIT) @Min(1) @Max(MAX_LIMIT) int limit,
            @RequestParam(name = "offset", defaultValue = "0") @Min(0) int offset) {
        return samplesService.samples(configId, from, to, category, action, limit, offset);
    }
}
