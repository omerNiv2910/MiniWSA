package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.stats.Timeseries;
import com.miniwsa.stats.Timeseries.Bucket;
import com.miniwsa.stats.TimeseriesInterval;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link EventStore#timeseries} against a real PostgreSQL with the production migrations. */
@Testcontainers
class EventStoreTimeseriesTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    // Config ids not used by the other storage tests, in case the events table is ever shared.
    private static final long CONFIG = 71001L;
    private static final long OTHER_CONFIG = 71002L;
    private static final Instant T0 = Instant.parse("2026-05-20T14:00:00Z");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static EventStore store;
    private int nextId;

    @BeforeAll
    static void connect() {
        dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new EventStore(jdbc, tx, Duration.ofSeconds(5), Duration.ofSeconds(5));
    }

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE events");
    }

    // ---- bucketing ----

    @Test
    void oneMinuteBuckets() {
        save(CONFIG, T0, T0.plusSeconds(59), T0.plusSeconds(60), T0.plusSeconds(179));

        Timeseries series = store.timeseries(CONFIG, T0, T0.plusSeconds(240), TimeseriesInterval.ONE_MINUTE);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 2),
                bucket("2026-05-20T14:01:00Z", 1),
                bucket("2026-05-20T14:02:00Z", 1),
                bucket("2026-05-20T14:03:00Z", 0));
    }

    @Test
    void fiveMinuteBuckets() {
        save(CONFIG, T0.plusSeconds(10), T0.plusSeconds(299), T0.plusSeconds(301), T0.plusSeconds(14 * 60));

        Timeseries series = store.timeseries(CONFIG, T0, T0.plusSeconds(15 * 60), TimeseriesInterval.FIVE_MINUTES);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 2),
                bucket("2026-05-20T14:05:00Z", 1),
                bucket("2026-05-20T14:10:00Z", 1));
    }

    @Test
    void oneHourBuckets() {
        save(CONFIG, T0, T0.plusSeconds(3599), T0.plusSeconds(2 * 3600 + 1));

        Timeseries series = store.timeseries(CONFIG, T0, T0.plusSeconds(3 * 3600), TimeseriesInterval.ONE_HOUR);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 2),
                bucket("2026-05-20T15:00:00Z", 0),
                bucket("2026-05-20T16:00:00Z", 1));
        assertThat(series.configId()).isEqualTo(CONFIG);
        assertThat(series.from()).isEqualTo(T0);
        assertThat(series.to()).isEqualTo(T0.plusSeconds(3 * 3600));
        assertThat(series.interval()).isEqualTo(TimeseriesInterval.ONE_HOUR);
        assertThat(series.totalEvents()).isEqualTo(3);
    }

    @Test
    void fromIsInclusiveAndToIsExclusive() {
        Instant from = T0;
        Instant to = T0.plusSeconds(120);
        save(CONFIG, from.minusNanos(1_000), from, to.minusNanos(1_000), to);

        Timeseries series = store.timeseries(CONFIG, from, to, TimeseriesInterval.ONE_MINUTE);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 1),
                bucket("2026-05-20T14:01:00Z", 1));
    }

    @Test
    void anEventExactlyOnABucketBoundaryIsInTheLaterBucket() {
        save(CONFIG, T0.plusSeconds(300));

        Timeseries series = store.timeseries(CONFIG, T0, T0.plusSeconds(600), TimeseriesInterval.FIVE_MINUTES);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 0),
                bucket("2026-05-20T14:05:00Z", 1));
    }

    @Test
    void bucketsByEventTimeNotReceivedAt() {
        Instant inside = T0.plusSeconds(30);
        Instant outside = T0.minusSeconds(3600);
        // Event time inside the window, received long after it: counted at its event time.
        // Event time outside, received inside: not counted.
        store.saveBatch(List.of(
                event(CONFIG, inside, T0.plusSeconds(7200)),
                event(CONFIG, outside, inside)));

        Timeseries series = store.timeseries(CONFIG, T0, T0.plusSeconds(120), TimeseriesInterval.ONE_MINUTE);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 1),
                bucket("2026-05-20T14:01:00Z", 0));
    }

    @Test
    void unalignedFromGivesPartialEdgeBuckets() {
        Instant from = Instant.parse("2026-05-20T14:02:30Z");
        Instant to = Instant.parse("2026-05-20T14:12:30Z");
        save(CONFIG,
                Instant.parse("2026-05-20T14:01:00Z"),  // in the first bucket, but before from
                Instant.parse("2026-05-20T14:02:30Z"),  // at from
                Instant.parse("2026-05-20T14:04:59Z"),
                Instant.parse("2026-05-20T14:12:29Z"),  // last bucket, before to
                Instant.parse("2026-05-20T14:12:30Z"),  // at to
                Instant.parse("2026-05-20T14:14:00Z")); // in the last bucket, but after to

        Timeseries series = store.timeseries(CONFIG, from, to, TimeseriesInterval.FIVE_MINUTES);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 2),
                bucket("2026-05-20T14:05:00Z", 0),
                bucket("2026-05-20T14:10:00Z", 1));
        assertThat(series.from()).isEqualTo(from);
        assertThat(series.to()).isEqualTo(to);
    }

    // ---- configId ----

    @Test
    void configIdExcludesOtherConfigsAndNullCoversAll() {
        save(CONFIG, T0, T0.plusSeconds(61));
        save(OTHER_CONFIG, T0.plusSeconds(1), T0.plusSeconds(2));

        Timeseries one = store.timeseries(CONFIG, T0, T0.plusSeconds(120), TimeseriesInterval.ONE_MINUTE);
        Timeseries other = store.timeseries(OTHER_CONFIG, T0, T0.plusSeconds(120), TimeseriesInterval.ONE_MINUTE);
        Timeseries all = store.timeseries(null, T0, T0.plusSeconds(120), TimeseriesInterval.ONE_MINUTE);

        assertThat(one.buckets()).containsExactly(bucket("2026-05-20T14:00:00Z", 1), bucket("2026-05-20T14:01:00Z", 1));
        assertThat(other.buckets()).containsExactly(bucket("2026-05-20T14:00:00Z", 2), bucket("2026-05-20T14:01:00Z", 0));
        assertThat(all.buckets()).containsExactly(bucket("2026-05-20T14:00:00Z", 3), bucket("2026-05-20T14:01:00Z", 1));
        assertThat(all.configId()).isNull();
    }

    @Test
    void unknownConfigIdIsAllZeros() {
        save(CONFIG, T0, T0.plusSeconds(90));

        Timeseries series = store.timeseries(71999L, T0, T0.plusSeconds(180), TimeseriesInterval.ONE_MINUTE);

        assertThat(series.buckets()).containsExactly(
                bucket("2026-05-20T14:00:00Z", 0),
                bucket("2026-05-20T14:01:00Z", 0),
                bucket("2026-05-20T14:02:00Z", 0));
        assertThat(series.totalEvents()).isZero();
    }

    // ---- agreement with Java and with summarize ----

    @Test
    void javaFloorAgreesWithDateBinIncludingBefore1970() {
        List<Instant> instants = List.of(
                Instant.parse("1969-12-31T23:58:30Z"),
                Instant.parse("1969-12-31T23:59:59.999999Z"),
                Instant.parse("1969-12-31T22:00:00Z"),
                Instant.parse("1969-06-15T07:33:12.5Z"),
                Instant.parse("1970-01-01T00:00:00Z"),
                Instant.parse("2026-05-20T14:07:59.999999Z"));
        for (TimeseriesInterval interval : TimeseriesInterval.values()) {
            for (Instant t : instants) {
                OffsetDateTime binned = jdbc.queryForObject(
                        "SELECT date_bin(make_interval(secs => ?), ?, TIMESTAMPTZ '1970-01-01 00:00:00+00')",
                        OffsetDateTime.class, interval.seconds(), EventStore.utc(t));
                assertThat(binned.toInstant()).as("%s at %s", interval, t)
                        .isEqualTo(interval.floor(t));
            }
        }
    }

    @Test
    void aPre1970EventLandsInTheBucketJavaExpects() {
        Instant t = Instant.parse("1969-12-31T23:58:30Z");
        save(CONFIG, t);

        Timeseries series = store.timeseries(CONFIG, Instant.parse("1969-12-31T23:50:00Z"),
                Instant.parse("1970-01-01T00:05:00Z"), TimeseriesInterval.FIVE_MINUTES);

        assertThat(series.buckets()).containsExactly(
                bucket("1969-12-31T23:50:00Z", 0),
                bucket("1969-12-31T23:55:00Z", 1),
                bucket("1970-01-01T00:00:00Z", 0));
    }

    @Test
    void totalEventsMatchesTheSummaryForTheSameWindow() {
        Instant from = Instant.parse("2026-05-20T14:02:30Z");
        Instant to = Instant.parse("2026-05-20T15:17:45Z");
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            times.add(T0.plusSeconds(i * 47L));
        }
        save(CONFIG, times.toArray(Instant[]::new));
        save(OTHER_CONFIG, T0.plusSeconds(1000), T0.plusSeconds(2000));

        for (TimeseriesInterval interval : TimeseriesInterval.values()) {
            assertThat(store.timeseries(CONFIG, from, to, interval).totalEvents())
                    .isEqualTo(store.summarize(CONFIG, from, to).totalEvents());
            assertThat(store.timeseries(null, from, to, interval).totalEvents())
                    .isEqualTo(store.summarize(null, from, to).totalEvents());
        }
    }

    // ---- timeout ----

    @Test
    void queryPastTheTimeoutIsCancelled() throws SQLException {
        save(CONFIG, T0);
        // Samples gets a long timeout so the cancellation can only come from the timeseries setting.
        var shortTimeout = new EventStore(jdbc, new TransactionTemplate(new DataSourceTransactionManager(dataSource)),
                Duration.ofSeconds(30), Duration.ofMillis(200));

        // Another transaction holds an exclusive lock on events, so the query blocks until the
        // statement timeout cancels it.
        try (Connection locker = dataSource.getConnection(); Statement st = locker.createStatement()) {
            locker.setAutoCommit(false);
            st.execute("LOCK TABLE events IN ACCESS EXCLUSIVE MODE");
            long start = System.nanoTime();

            assertThatThrownBy(() -> shortTimeout.timeseries(CONFIG, T0, T0.plusSeconds(60), TimeseriesInterval.ONE_MINUTE))
                    .isInstanceOf(ReadQueryTimeoutException.class)
                    .cause().isInstanceOf(QueryTimeoutException.class)
                    .rootCause().isInstanceOf(SQLException.class)
                    .extracting(t -> ((SQLException) t).getSQLState()).isEqualTo("57014");

            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(5));
            locker.rollback();
        }

        assertThat(shortTimeout.timeseries(CONFIG, T0, T0.plusSeconds(60), TimeseriesInterval.ONE_MINUTE)
                .totalEvents()).isEqualTo(1);
    }

    @Test
    void timeseriesTimeoutBelowOneMillisecondIsRejected() {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        assertThatThrownBy(() -> new EventStore(jdbc, tx, Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("miniwsa.timeseries.query-timeout");
        assertThatThrownBy(() -> new EventStore(jdbc, tx, Duration.ofSeconds(5), Duration.ofNanos(500_000)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- helpers ----

    private void save(long configId, Instant... timestamps) {
        List<SecurityEvent> events = new ArrayList<>();
        for (Instant t : timestamps) {
            events.add(event(configId, t, t));
        }
        store.saveBatch(events);
    }

    private static Bucket bucket(String start, long count) {
        return new Bucket(Instant.parse(start), count);
    }

    private SecurityEvent event(long configId, Instant timestamp, Instant receivedAt) {
        return new SecurityEvent("ts-" + nextId++, timestamp, configId, "pol_web1",
                "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "RULE", null, Severity.HIGH, Category.BOT),
                Action.DENY, null, null, null, receivedAt, null, null);
    }
}
