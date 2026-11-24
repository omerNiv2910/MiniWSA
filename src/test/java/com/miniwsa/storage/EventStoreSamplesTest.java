package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.samples.SampleQuery;
import com.miniwsa.samples.SampleResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link EventStore#findSamples} against a real PostgreSQL with the production migrations. */
@Testcontainers
class EventStoreSamplesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    private static final long CONFIG = 14227L;
    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static EventStore store;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new EventStore(jdbc, tx, Duration.ofSeconds(5));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE events");
    }

    // ---- filters ----

    @Test
    void filtersByConfigId() {
        store.saveBatch(List.of(
                event("a", CONFIG, T0, Category.BOT, Action.DENY),
                event("b", 999L, T0, Category.BOT, Action.DENY)));

        SampleResponse page = store.findSamples(query(CONFIG, null, null, null, null, 100, 0));

        assertThat(page.total()).isEqualTo(1);
        assertThat(ids(page)).containsExactly("a");
    }

    @Test
    void noFiltersReturnsEveryConfig() {
        store.saveBatch(List.of(
                event("a", CONFIG, T0, Category.BOT, Action.DENY),
                event("b", 999L, T0.plusSeconds(1), Category.XSS, Action.ALERT)));

        SampleResponse page = store.findSamples(query(null, null, null, null, null, 100, 0));

        assertThat(page.total()).isEqualTo(2);
        assertThat(ids(page)).containsExactly("b", "a");
    }

    @Test
    void fromIsInclusiveAndToIsExclusive() {
        Instant from = T0;
        Instant to = T0.plusSeconds(60);
        store.saveBatch(List.of(
                event("before", CONFIG, from.minusNanos(1_000), Category.BOT, Action.DENY),
                event("at-from", CONFIG, from, Category.BOT, Action.DENY),
                event("inside", CONFIG, from.plusSeconds(30), Category.BOT, Action.DENY),
                event("just-before-to", CONFIG, to.minusNanos(1_000), Category.BOT, Action.DENY),
                event("at-to", CONFIG, to, Category.BOT, Action.DENY)));

        SampleResponse page = store.findSamples(query(CONFIG, from, to, null, null, 100, 0));

        assertThat(page.total()).isEqualTo(3);
        assertThat(ids(page)).containsExactly("just-before-to", "inside", "at-from");
    }

    @Test
    void microsecondBoundariesAreExact() {
        Instant from = Instant.parse("2026-05-20T14:00:00.123456Z");
        Instant to = Instant.parse("2026-05-20T14:00:00.123458Z");
        store.saveBatch(List.of(
                event("x5", CONFIG, Instant.parse("2026-05-20T14:00:00.123455Z"), Category.BOT, Action.DENY),
                event("x6", CONFIG, from, Category.BOT, Action.DENY),
                event("x7", CONFIG, Instant.parse("2026-05-20T14:00:00.123457Z"), Category.BOT, Action.DENY),
                event("x8", CONFIG, to, Category.BOT, Action.DENY)));

        SampleResponse page = store.findSamples(query(CONFIG, from, to, null, null, 100, 0));

        assertThat(ids(page)).containsExactly("x7", "x6");
    }

    @Test
    void filtersByCategory() {
        store.saveBatch(List.of(
                event("a", CONFIG, T0, Category.INJECTION, Action.DENY),
                event("b", CONFIG, T0, Category.XSS, Action.DENY)));

        SampleResponse page = store.findSamples(query(CONFIG, null, null, Category.XSS, null, 100, 0));

        assertThat(page.total()).isEqualTo(1);
        assertThat(ids(page)).containsExactly("b");
    }

    @Test
    void filtersByAction() {
        store.saveBatch(List.of(
                event("a", CONFIG, T0, Category.BOT, Action.DENY),
                event("b", CONFIG, T0, Category.BOT, Action.MONITOR)));

        SampleResponse page = store.findSamples(query(CONFIG, null, null, null, Action.MONITOR, 100, 0));

        assertThat(page.total()).isEqualTo(1);
        assertThat(ids(page)).containsExactly("b");
    }

    @Test
    void allFiltersAreCombinedWithAnd() {
        Instant from = T0;
        Instant to = T0.plusSeconds(60);
        Instant inside = T0.plusSeconds(10);
        store.saveBatch(List.of(
                event("match", CONFIG, inside, Category.INJECTION, Action.DENY),
                event("other-config", 999L, inside, Category.INJECTION, Action.DENY),
                event("too-early", CONFIG, from.minusSeconds(1), Category.INJECTION, Action.DENY),
                event("too-late", CONFIG, to, Category.INJECTION, Action.DENY),
                event("other-category", CONFIG, inside, Category.XSS, Action.DENY),
                event("other-action", CONFIG, inside, Category.INJECTION, Action.ALERT)));

        SampleResponse page = store.findSamples(
                query(CONFIG, from, to, Category.INJECTION, Action.DENY, 100, 0));

        assertThat(page.total()).isEqualTo(1);
        assertThat(ids(page)).containsExactly("match");
    }

    @Test
    void unknownConfigIdIsEmpty() {
        store.saveBatch(List.of(event("a", CONFIG, T0, Category.BOT, Action.DENY)));

        SampleResponse page = store.findSamples(query(424242L, null, null, null, null, 100, 0));

        assertThat(page.total()).isZero();
        assertThat(page.events()).isEmpty();
    }

    // ---- ordering and paging ----

    @Test
    void newestFirstAndEventIdAscendingOnTies() {
        store.saveBatch(List.of(
                event("c", CONFIG, T0, Category.BOT, Action.DENY),
                event("b", CONFIG, T0.plusSeconds(5), Category.BOT, Action.DENY),
                event("a", CONFIG, T0, Category.BOT, Action.DENY),
                event("d", CONFIG, T0.plusSeconds(5), Category.BOT, Action.DENY)));

        SampleResponse page = store.findSamples(query(CONFIG, null, null, null, null, 100, 0));

        assertThat(ids(page)).containsExactly("b", "d", "a", "c");
    }

    @Test
    void consecutivePagesAreDisjointAndCoverEverything() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int i = 0; i < 23; i++) {
            // Several events share a timestamp, so the tie-break decides the page boundaries.
            events.add(event(String.format("evt-%02d", i), CONFIG, T0.plusSeconds(i / 3), Category.BOT, Action.DENY));
        }
        store.saveBatch(events);
        List<String> all = ids(store.findSamples(query(CONFIG, null, null, null, null, 100, 0)));

        List<String> paged = new ArrayList<>();
        for (int offset = 0; offset < 23; offset += 5) {
            SampleResponse page = store.findSamples(query(CONFIG, null, null, null, null, 5, offset));
            assertThat(page.total()).isEqualTo(23);
            assertThat(paged).doesNotContainAnyElementsOf(ids(page));
            paged.addAll(ids(page));
        }

        assertThat(all).hasSize(23);
        assertThat(paged).containsExactlyElementsOf(all);
    }

    @Test
    void limitOneAndLimitHundred() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            events.add(event(String.format("evt-%03d", i), CONFIG, T0.plusSeconds(i), Category.BOT, Action.DENY));
        }
        store.saveBatch(events);

        SampleResponse one = store.findSamples(query(CONFIG, null, null, null, null, 1, 0));
        SampleResponse hundred = store.findSamples(query(CONFIG, null, null, null, null, 100, 0));

        assertThat(ids(one)).containsExactly("evt-119");
        assertThat(hundred.events()).hasSize(100);
        assertThat(hundred.events().getFirst().eventId()).isEqualTo("evt-119");
        assertThat(hundred.events().getLast().eventId()).isEqualTo("evt-020");
        assertThat(one.total()).isEqualTo(120);
        assertThat(hundred.total()).isEqualTo(120);
    }

    @Test
    void offsetAtOrPastTheTotalIsEmptyWithTheTotal() {
        store.saveBatch(List.of(
                event("a", CONFIG, T0, Category.BOT, Action.DENY),
                event("b", CONFIG, T0, Category.BOT, Action.DENY)));

        SampleResponse atTotal = store.findSamples(query(CONFIG, null, null, null, null, 10, 2));
        SampleResponse past = store.findSamples(query(CONFIG, null, null, null, null, 10, 50));

        assertThat(atTotal.total()).isEqualTo(2);
        assertThat(atTotal.events()).isEmpty();
        assertThat(past.total()).isEqualTo(2);
        assertThat(past.events()).isEmpty();
    }

    @Test
    void totalIgnoresLimitAndOffset() {
        List<SecurityEvent> events = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            events.add(event("evt-" + i, CONFIG, T0.plusSeconds(i), Category.BOT, Action.DENY));
        }
        store.saveBatch(events);

        SampleResponse page = store.findSamples(query(CONFIG, null, null, null, null, 2, 3));

        assertThat(page.total()).isEqualTo(7);
        assertThat(ids(page)).containsExactly("evt-3", "evt-2");
    }

    // ---- mapping ----

    @Test
    void returnsEveryStoredField() {
        Instant timestamp = Instant.parse("2026-05-20T14:32:10.123456Z");
        Instant receivedAt = Instant.parse("2026-05-20T14:32:11.654321Z");
        SecurityEvent stored = new SecurityEvent("evt-1", timestamp, CONFIG, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, "Mozilla/5.0",
                new SecurityEvent.Rule("950001", "SQL_INJECTION", "SQL Injection Attack Detected",
                        Severity.CRITICAL, Category.INJECTION),
                Action.DENY, new SecurityEvent.GeoLocation("CN", "Beijing"), 1024L, 256L,
                receivedAt, "SQL/Command Injection", 75);
        store.saveBatch(List.of(stored));

        SecurityEvent read = store.findSamples(query(CONFIG, null, null, null, null, 10, 0)).events().getFirst();

        assertThat(read).usingRecursiveComparison().isEqualTo(stored);
    }

    @Test
    void missingGeoIsNullAndCountryOnlyGeoIsKept() {
        SecurityEvent noGeo = event("no-geo", CONFIG, T0.plusSeconds(1), Category.BOT, Action.DENY);
        SecurityEvent countryOnly = new SecurityEvent("country-only", T0, CONFIG, null,
                "203.0.113.42", "www.example.com", "/", "GET", 200, null,
                new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT), Action.DENY,
                new SecurityEvent.GeoLocation("US", null), null, null, T0, null, null);
        store.saveBatch(List.of(noGeo, countryOnly));

        List<SecurityEvent> events = store.findSamples(query(CONFIG, null, null, null, null, 10, 0)).events();

        assertThat(events.get(0).eventId()).isEqualTo("no-geo");
        assertThat(events.get(0).geoLocation()).isNull();
        assertThat(events.get(0).requestSize()).isNull();
        assertThat(events.get(0).responseSize()).isNull();
        assertThat(events.get(0).userAgent()).isNull();
        assertThat(events.get(1).geoLocation()).isEqualTo(new SecurityEvent.GeoLocation("US", null));
    }

    @Test
    void legacyRowWithoutEnrichmentHasNullAttackTypeAndScore() {
        // Written like a row stored before V2: enrichment and geo columns left NULL.
        jdbc.update("""
                INSERT INTO events (event_id, timestamp, config_id, client_ip, hostname, path, method, status_code,
                                    rule_id, rule_name, severity, category, action, received_at)
                VALUES ('legacy', ?, ?, '198.51.100.7', 'old.example.com', '/', 'GET', 200,
                        '1', 'R', 'LOW', 'BOT', 'ALERT', ?)
                """, EventStore.utc(T0), CONFIG, EventStore.utc(T0));

        SecurityEvent read = store.findSamples(query(CONFIG, null, null, null, null, 10, 0)).events().getFirst();

        assertThat(read.eventId()).isEqualTo("legacy");
        assertThat(read.attackType()).isNull();
        assertThat(read.threatScore()).isNull();
        assertThat(read.geoLocation()).isNull();
        assertThat(read.policyId()).isNull();
        assertThat(read.rule().message()).isNull();
        assertThat(read.timestamp()).isEqualTo(T0);
    }

    // ---- timeout ----

    @Test
    void queryPastTheTimeoutIsCancelled() throws SQLException {
        store.saveBatch(List.of(event("a", CONFIG, T0, Category.BOT, Action.DENY)));
        var shortTimeout = new EventStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Duration.ofMillis(200));

        // Another transaction holds an exclusive lock on events, so the count blocks until the
        // statement timeout cancels it. Deterministic, unlike racing a 1ms timeout against a fast query.
        try (Connection locker = dataSource.getConnection(); Statement st = locker.createStatement()) {
            locker.setAutoCommit(false);
            st.execute("LOCK TABLE events IN ACCESS EXCLUSIVE MODE");
            long start = System.nanoTime();

            assertThatThrownBy(() -> shortTimeout.findSamples(query(CONFIG, null, null, null, null, 10, 0)))
                    .isInstanceOf(ReadQueryTimeoutException.class)
                    // What PostgreSQL's "canceling statement due to statement timeout" (57014) maps to.
                    .cause().isInstanceOf(QueryTimeoutException.class)
                    .rootCause().isInstanceOf(SQLException.class)
                    .extracting(t -> ((SQLException) t).getSQLState()).isEqualTo("57014");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            locker.rollback();
        }

        // The store (and the connection's session) still work once the lock is gone.
        assertThat(shortTimeout.findSamples(query(CONFIG, null, null, null, null, 10, 0)).total()).isEqualTo(1);
    }

    @Test
    void timeoutBelowOneMillisecondIsRejected() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> new EventStore(jdbc, tx, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EventStore(jdbc, tx, Duration.of(500, ChronoUnit.MICROS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeLimitOrOffsetIsRejected() {
        assertThatThrownBy(() -> query(CONFIG, null, null, null, null, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> query(CONFIG, null, null, null, null, 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static SampleQuery query(Long configId, Instant from, Instant to, Category category, Action action,
                                     int limit, int offset) {
        return new SampleQuery(configId, from, to, category, action, limit, offset);
    }

    private static List<String> ids(SampleResponse page) {
        return page.events().stream().map(SecurityEvent::eventId).toList();
    }

    private static SecurityEvent event(String id, long configId, Instant timestamp, Category category, Action action) {
        return new SecurityEvent(id, timestamp, configId, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "RULE", null, Severity.HIGH, category),
                action, null, null, null, T0, null, null);
    }
}
