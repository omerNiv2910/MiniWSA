package com.miniwsa.api;

import com.miniwsa.stats.StatsService;
import com.miniwsa.stats.StatsSummary;
import com.miniwsa.stats.Timeseries;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /v1/stats/summary?configId=&from=&to=}: event counts for a time window, in total,
 * per category (with the average threat score), per action, and for the top 10 client IPs and
 * paths. Without {@code configId} the counts cover all configurations.
 *
 * <p>{@code GET /v1/stats/timeseries?configId=&from=&to=&interval=}: event counts per {@code interval}
 * bucket ({@code 1m}, {@code 5m} or {@code 1h}) over {@code [from, to)}, every bucket present (0 when
 * empty), oldest first. Buckets are aligned to the UTC epoch, so the first may start before
 * {@code from}. A query cancelled for running past {@code miniwsa.timeseries.query-timeout} is a 503.
 *
 * <p>Answered straight from PostgreSQL in the request, not through the ingest queue, so events
 * still in the queue are not counted yet.
 *
 * <p>A missing {@code from}, {@code to} or {@code interval} is rejected by Spring before these methods
 * run (first missing one only), and {@link ApiExceptionHandler} reports it as
 * {@code errors: [{field, "is required"}]}. Otherwise the parameters are passed as plain strings to
 * {@link StatsService}, which checks them and reports every problem together in one 400.
 */
@RestController
@RequestMapping("/v1/stats")
public class StatsController {

    private final StatsService statsService;

    public StatsController(StatsService statsService) {
        this.statsService = statsService;
    }

    @GetMapping("/summary")
    public StatsSummary summary(@RequestParam(name = "configId", required = false) String configId,
                                @RequestParam(name = "from") String from,
                                @RequestParam(name = "to") String to) {
        return statsService.summary(configId, from, to);
    }

    @GetMapping("/timeseries")
    public Timeseries timeseries(@RequestParam(name = "configId", required = false) String configId,
                                 @RequestParam(name = "from") String from,
                                 @RequestParam(name = "to") String to,
                                 @RequestParam(name = "interval") String interval) {
        return statsService.timeseries(configId, from, to, interval);
    }
}
