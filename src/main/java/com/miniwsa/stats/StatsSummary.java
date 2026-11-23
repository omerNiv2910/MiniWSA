package com.miniwsa.stats;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistics for one time window, for one configuration or for all of them.
 *
 * <p>{@code configId} is left out of the JSON when null (all configurations). {@code from} and
 * {@code to} are written together as {@code "timeRange": {"from": ..., "to": ...}}.
 * {@code byCategory} and {@code byAction} hold only the keys that have events, in the order given
 * (the store gives them highest count first). {@code topAttackers} and {@code topTargetedPaths} hold
 * at most 10 entries each, most events first.
 */
@JsonPropertyOrder({"configId", "timeRange", "totalEvents", "byCategory", "byAction",
        "topAttackers", "topTargetedPaths"})
public record StatsSummary(
        @JsonInclude(JsonInclude.Include.NON_NULL) Long configId,
        @JsonIgnore Instant from,
        @JsonIgnore Instant to,
        long totalEvents,
        Map<Category, CategoryStats> byCategory,
        Map<Action, Long> byAction,
        List<AttackerStats> topAttackers,
        List<PathStats> topTargetedPaths) {

    public StatsSummary {
        // Copies that keep the caller's key order (Map.copyOf would not).
        byCategory = Collections.unmodifiableMap(new LinkedHashMap<>(byCategory));
        byAction = Collections.unmodifiableMap(new LinkedHashMap<>(byAction));
        topAttackers = List.copyOf(topAttackers);
        topTargetedPaths = List.copyOf(topTargetedPaths);
    }

    /** The requested window as written to JSON: {@code from} inclusive, {@code to} exclusive. */
    @JsonProperty("timeRange")
    public Map<String, Instant> timeRange() {
        Map<String, Instant> range = new LinkedHashMap<>();
        range.put("from", from);
        range.put("to", to);
        return range;
    }

    /**
     * Events of one category.
     *
     * @param avgThreatScore mean of the events that have a threat score, rounded to 1 decimal;
     *                       null when none of them has one
     */
    public record CategoryStats(long count, BigDecimal avgThreatScore) {
    }

    /**
     * Events from one client IP.
     *
     * @param avgThreatScore mean of the events that have a threat score, rounded to 1 decimal;
     *                       null when none of them has one
     */
    public record AttackerStats(String clientIp, long count, BigDecimal avgThreatScore) {
    }

    /** Events on one request path. */
    public record PathStats(String path, long count) {
    }
}
