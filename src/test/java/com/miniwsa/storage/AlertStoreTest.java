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

import java.time.Instant;
import java.time.ZoneOffset;
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
        jdbc.execute("TRUNCATE alerts, events RESTART IDENTITY");
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

    // ---- findFiring ----

    private static final Instant NOW = Instant.parse("2026-11-25T10:00:00Z");

    @Test
    void noRulesMeansNothingFires() {
        insertEvent("e1", Category.INJECTION, NOW.minusSeconds(10), NOW);

        assertThat(store.findFiring(NOW)).isEmpty();
    }

    @Test
    void firesOnlyWhenTheCountIsMoreThanTheThreshold() {
        AlertDefinition belowCount = store.define(new AlertDefinition(null, "2", Category.INJECTION, 2, 5));
        store.define(new AlertDefinition(null, "3", Category.INJECTION, 3, 5));
        insertEvents(3, Category.INJECTION, NOW.minusSeconds(10));

        // 3 > 2 fires; 3 > 3 does not.
        assertThat(store.findFiring(NOW)).containsExactly(belowCount);
    }

    @Test
    void windowStartIsIncludedAndNowIsExcluded() {
        AlertDefinition fires = store.define(new AlertDefinition(null, "over 1", Category.INJECTION, 1, 5));
        store.define(new AlertDefinition(null, "over 2", Category.INJECTION, 2, 5));
        insertEvent("at-start", Category.INJECTION, NOW.minusSeconds(300), NOW);
        insertEvent("just-before-now", Category.INJECTION, NOW.minusMillis(1), NOW);
        insertEvent("before-start", Category.INJECTION, NOW.minusSeconds(300).minusMillis(1), NOW);
        insertEvent("at-now", Category.INJECTION, NOW, NOW);
        insertEvent("future", Category.INJECTION, NOW.plusSeconds(60), NOW);

        // Exactly the 2 events inside the window count: more than 1, not more than 2.
        assertThat(store.findFiring(NOW)).containsExactly(fires);
    }

    @Test
    void countsOnlyTheRulesCategory() {
        AlertDefinition xss = store.define(new AlertDefinition(null, "xss", Category.XSS, 0, 5));
        store.define(new AlertDefinition(null, "leak", Category.DATA_LEAKAGE, 0, 5));
        insertEvent("xss", Category.XSS, NOW.minusSeconds(10), NOW);
        insertEvent("sqli", Category.INJECTION, NOW.minusSeconds(10), NOW);

        assertThat(store.findFiring(NOW)).containsExactly(xss);
    }

    @Test
    void theWindowIsOnEventTimeNotReceivedAt() {
        AlertDefinition fires = store.define(new AlertDefinition(null, "over 0", Category.BOT, 0, 5));
        store.define(new AlertDefinition(null, "over 1", Category.BOT, 1, 5));
        insertEvent("old-but-just-received", Category.BOT, NOW.minusSeconds(3600), NOW.minusSeconds(1));
        insertEvent("recent-received-late", Category.BOT, NOW.minusSeconds(60), NOW.plusSeconds(3600));

        // Only the recent event counts: more than 0, not more than 1.
        assertThat(store.findFiring(NOW)).containsExactly(fires);
    }

    @Test
    void eachRuleUsesItsOwnWindowAndFiringRulesComeInIdOrder() {
        store.define(new AlertDefinition(null, "5m", Category.DOS, 1, 5));
        AlertDefinition hour = store.define(new AlertDefinition(null, "60m", Category.DOS, 1, 60));
        AlertDefinition day = store.define(new AlertDefinition(null, "1d", Category.DOS, 1, 1440));
        insertEvent("recent", Category.DOS, NOW.minusSeconds(60), NOW);
        insertEvent("half-hour-ago", Category.DOS, NOW.minusSeconds(1800), NOW);

        // 5m sees 1 event (not more than 1); 60m and 1d see 2.
        assertThat(store.findFiring(NOW)).containsExactly(hour, day);
    }

    private static void insertEvents(int count, Category category, Instant timestamp) {
        for (int i = 0; i < count; i++) {
            insertEvent(category + "-" + i, category, timestamp, NOW);
        }
    }

    /** Only the columns the count reads matter; the rest are filler that satisfies NOT NULL. */
    private static void insertEvent(String id, Category category, Instant timestamp, Instant receivedAt) {
        jdbc.update("""
                INSERT INTO events (event_id, timestamp, config_id, client_ip, hostname, path, method, status_code,
                                    rule_id, rule_name, severity, category, action, received_at)
                VALUES (?, ?, 1, '203.0.113.1', 'h', '/', 'GET', 403, '1', 'R', 'HIGH', ?, 'DENY', ?)
                """, id, timestamp.atOffset(ZoneOffset.UTC), category.name(), receivedAt.atOffset(ZoneOffset.UTC));
    }

    private static void assertRejected(String name, int events, int windowMinutes) {
        String sql = "INSERT INTO alerts (name, num_of_events, category, window_minutes) VALUES ("
                + name + ", " + events + ", 'XSS', " + windowMinutes + ")";
        assertThatThrownBy(() -> jdbc.update(sql)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
