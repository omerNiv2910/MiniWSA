package com.miniwsa.storage;

import com.miniwsa.alerts.AlertDefinition;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Stores alert definitions in the {@code alerts} table. Every call adds a new row, so defining the
 * same rule twice gives two rules with different ids.
 */
@Component
public class AlertStore {

    private static final String INSERT_ALERT = """
            INSERT INTO alerts (name, num_of_events, category, window_minutes)
            VALUES (?, ?, ?, ?)
            RETURNING id""";

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
}
