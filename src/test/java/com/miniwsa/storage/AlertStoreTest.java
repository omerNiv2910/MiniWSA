package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.alerts.AlertDefinition;
import com.miniwsa.model.Category;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL in Docker, with the production Flyway migrations applied. */
@Testcontainers
class AlertStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    private static JdbcTemplate jdbc;
    private static AlertStore store;

    @BeforeAll
    static void connect() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new AlertStore(jdbc);
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE alerts RESTART IDENTITY");
    }

    @Test
    void storesEveryFieldAndReturnsTheAssignedId() {
        AlertDefinition stored = store.define(new AlertDefinition(null, "SQLi burst", Category.INJECTION, 100, 5));

        assertThat(stored).isEqualTo(new AlertDefinition(1L, "SQLi burst", Category.INJECTION, 100, 5));
        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM alerts WHERE id = ?", stored.id());
        assertThat(row).containsExactlyInAnyOrderEntriesOf(Map.of(
                "id", 1L,
                "name", "SQLi burst",
                "num_of_events", 100,
                "category", "INJECTION",
                "window_minutes", 5));
    }

    @Test
    void theSameDefinitionTwiceIsTwoRules() {
        AlertDefinition rule = new AlertDefinition(null, "rule", Category.XSS, 3, 10);
        long first = store.define(rule).id();
        long second = store.define(rule).id();

        assertThat(second).isNotEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM alerts", Long.class)).isEqualTo(2L);
    }

    @Test
    void theTableRejectsInvalidRows() {
        assertRejected("'  '", 3, 10);
        assertRejected("'rule'", -1, 10);
        assertRejected("'rule'", 3, 0);
        assertRejected("'rule'", 3, 1441);
        assertRejected("NULL", 3, 10);
    }

    private static void assertRejected(String name, int events, int windowMinutes) {
        String sql = "INSERT INTO alerts (name, num_of_events, category, window_minutes) VALUES ("
                + name + ", " + events + ", 'XSS', " + windowMinutes + ")";
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
