package com.miniwsa.storage;

import com.miniwsa.alerts.AlertDefinition;
import com.miniwsa.model.Category;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Stores alert definitions in the {@code alerts} table and finds the ones currently firing
 * ({@link #findFiring}).
 *
 * <p>Every {@link #define} adds a new row, so defining the same rule twice gives two rules with
 * different ids.
 */
@Component
public class AlertStore {

    private final String INSERT_ALERT = """
            INSERT INTO alerts (name, num_of_events, category, window_minutes)
            VALUES (?, ?, ?, ?)
            RETURNING id""";

    /**
     * The alert rules that are firing at {@code now}: more than {@code num_of_events} events of the
     * rule's category have a {@code timestamp} in {@code [now - window_minutes, now)}.
     *
     * <ul>
     *   <li>{@code FROM alerts}: every defined rule; the table is small and all of it is needed.</li>
     *   <li>{@code CROSS JOIN LATERAL (...)}: for each rule row, run the subquery once.
     *       LATERAL lets the subquery read that rule's {@code category} and {@code window_minutes};
     *       CROSS JOIN pairs the rule with the subquery's result. {@code count(*)} always returns exactly
     *       one row (0 when nothing matches), so every rule gets exactly one {@code event_count}.</li>
     *   <li>{@code events.category = alerts.category AND events.timestamp >= now - window AND
     *       events.timestamp < now}: the events of this rule's category in its window. The start is
     *       included and {@code now} is excluded, like the stats endpoints' {@code [from, to)}, so a
     *       future-dated event never counts. The window is on the event's own {@code timestamp}, not
     *       {@code received_at}.</li>
     *   <li>That filter is exactly the {@code events_category_time_idx (category, timestamp)} index
     *       (V4), so each count reads one range of the index, not every event in the window.</li>
     *   <li>{@code WHERE event_count > alerts.num_of_events}: keep only the rules that fire ("more than
     *       N", so exactly N does not).</li>
     *   <li>Both {@code ?} are the same {@code now}: one instant for the whole evaluation. Being one
     *       statement, every rule is also counted against the same snapshot of {@code events}.</li>
     * </ul>
     */
    private final String FIND_FIRING_ALERTS = """
            SELECT alerts.id, alerts.name, alerts.category, alerts.num_of_events, alerts.window_minutes
            FROM alerts
            CROSS JOIN LATERAL (
                SELECT count(*) AS event_count
                FROM events
                WHERE events.category  = alerts.category
                  AND events.timestamp >= CAST(? AS timestamptz) - make_interval(mins => alerts.window_minutes)
                  AND events.timestamp <  CAST(? AS timestamptz)
            )
            WHERE event_count > alerts.num_of_events
            ORDER BY alerts.id""";

    private final JdbcTemplate jdbc;

    public AlertStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts one (already validated) definition and returns it with the id the database assigned. */
    public AlertDefinition define(AlertDefinition definition) {
        Long id = jdbc.queryForObject(INSERT_ALERT, Long.class, definition.name(), definition.events(),
                definition.category().name(), definition.windowMinutes());
        return definition.withId(id);
    }

    /**
     * The alert rules firing at {@code now}, ordered by id; empty when none fires or none is defined.
     * See {@link #FIND_FIRING_ALERTS}.
     */
    public List<AlertDefinition> findFiring(Instant now) {
        OffsetDateTime at = now.atOffset(ZoneOffset.UTC);
        return jdbc.query(FIND_FIRING_ALERTS, (rs, rowNum) -> new AlertDefinition(
                rs.getLong("id"),
                rs.getString("name"),
                Category.valueOf(rs.getString("category")),
                rs.getInt("num_of_events"),
                rs.getInt("window_minutes")), at, at);
    }
}
