package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real PostgreSQL in Docker, with the production Flyway migrations applied. */
@Testcontainers
class EventStoreTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    private static JdbcTemplate jdbc;
    private static EventStore store;

    @BeforeAll
    static void connect() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        store = new EventStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE events");
    }

    @Test
    void storesEveryFieldUnderTheEventId() {
        SecurityEvent event = new SecurityEvent("evt-1", Instant.parse("2026-05-20T14:32:10Z"), 14227L, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, "Mozilla/5.0",
                new SecurityEvent.Rule("950001", "SQL_INJECTION", "SQL Injection Attack Detected",
                        Severity.CRITICAL, Category.INJECTION),
                Action.DENY, new SecurityEvent.GeoLocation("CN", "Beijing"), 1024L, 256L,
                Instant.parse("2026-05-20T14:32:11Z"));

        store.saveBatch(List.of(event));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM events WHERE event_id = 'evt-1'");
        assertThat(row).containsEntry("config_id", 14227L)
                .containsEntry("policy_id", "pol_web1")
                .containsEntry("client_ip", "203.0.113.42")
                .containsEntry("path", "/api/v1/login")
                .containsEntry("status_code", 403)
                .containsEntry("rule_id", "950001")
                .containsEntry("rule_message", "SQL Injection Attack Detected")
                .containsEntry("severity", "CRITICAL")
                .containsEntry("category", "INJECTION")
                .containsEntry("action", "DENY")
                .containsEntry("geo_country", "CN")
                .containsEntry("geo_city", "Beijing")
                .containsEntry("request_size", 1024L)
                .containsEntry("response_size", 256L);
        assertThat(instant("SELECT timestamp FROM events")).isEqualTo(Instant.parse("2026-05-20T14:32:10Z"));
        assertThat(instant("SELECT received_at FROM events")).isEqualTo(Instant.parse("2026-05-20T14:32:11Z"));
    }

    @Test
    void storesOptionalFieldsAsNull() {
        store.saveBatch(List.of(event("evt-1", 14227L, "2026-05-20T14:32:10Z", Category.BOT, Severity.LOW, Action.MONITOR)));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM events WHERE event_id = 'evt-1'");
        assertThat(row).containsEntry("geo_country", null)
                .containsEntry("request_size", null)
                .containsEntry("user_agent", null);
    }

    @Test
    void redeliveredBatchIsNotStoredTwice() {
        List<SecurityEvent> batch = List.of(
                event("evt-1", 14227L, "2026-05-20T14:32:10Z", Category.INJECTION, Severity.CRITICAL, Action.DENY),
                event("evt-2", 14227L, "2026-05-20T14:32:20Z", Category.INJECTION, Severity.CRITICAL, Action.DENY));

        store.saveBatch(batch);
        store.saveBatch(batch);

        assertThat(storedIds()).containsExactly("evt-1", "evt-2");
    }

    @Test
    void overlappingBatchWithRepeatsStoresOnlyTheNewEvents() {
        store.saveBatch(List.of(event("evt-1", 14227L, "2026-05-20T14:32:10Z", Category.INJECTION, Severity.CRITICAL, Action.DENY)));
        store.saveBatch(List.of(
                event("evt-1", 14227L, "2026-05-20T14:32:10Z", Category.INJECTION, Severity.CRITICAL, Action.DENY),
                event("evt-2", 14227L, "2026-05-20T14:32:20Z", Category.XSS, Severity.HIGH, Action.DENY),
                event("evt-2", 14227L, "2026-05-20T14:32:20Z", Category.XSS, Severity.HIGH, Action.DENY)));

        assertThat(storedIds()).containsExactly("evt-1", "evt-2");
    }

    @Test
    void failedBatchLeavesNothingBehindSoTheRetryStoresItOnce() {
        SecurityEvent good = event("evt-1", 14227L, "2026-05-20T14:32:10Z", Category.INJECTION, Severity.CRITICAL, Action.DENY);
        // Path longer than the column allows: the insert fails part-way through the batch.
        SecurityEvent bad = new SecurityEvent("evt-2", Instant.parse("2026-05-20T14:32:20Z"), 14227L, null,
                "203.0.113.42", "www.example.com", "/" + "x".repeat(3000), "GET", 403, null,
                new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT), Action.DENY,
                null, null, null, Instant.parse("2026-05-20T14:32:21Z"));

        assertThatThrownBy(() -> store.saveBatch(List.of(good, bad))).isInstanceOf(RuntimeException.class);
        assertThat(storedIds()).isEmpty();

        store.saveBatch(List.of(good));
        assertThat(storedIds()).containsExactly("evt-1");
    }

    private static List<String> storedIds() {
        return jdbc.queryForList("SELECT event_id FROM events ORDER BY event_id", String.class);
    }

    private static Instant instant(String sql) {
        return jdbc.queryForObject(sql, OffsetDateTime.class).toInstant();
    }

    private static SecurityEvent event(String id, long configId, String timestamp,
                                       Category category, Severity severity, Action action) {
        return new SecurityEvent(id, Instant.parse(timestamp), configId, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "RULE", null, severity, category),
                action, null, null, null, Instant.parse("2026-05-20T14:32:11Z"));
    }
}
