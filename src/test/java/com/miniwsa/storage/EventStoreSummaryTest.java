package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.stats.StatsSummary;
import com.miniwsa.stats.StatsSummary.AttackerStats;
import com.miniwsa.stats.StatsSummary.CategoryStats;
import com.miniwsa.stats.StatsSummary.PathStats;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** {@link EventStore#summarize} against a real PostgreSQL with the production migrations. */
@Testcontainers
class EventStoreSummaryTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T15:00:00Z");
    private static final Instant INSIDE = Instant.parse("2026-05-20T14:30:00Z");

    private static JdbcTemplate jdbc;
    private static EventStore store;
    private int nextId;

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
    void countsPerCategoryAndActionWithTheAverageScore() {
        store.saveBatch(List.of(
                event(14227L, INSIDE, Category.INJECTION, Action.DENY, 80),
                event(14227L, INSIDE, Category.INJECTION, Action.DENY, 65),
                event(14227L, INSIDE, Category.INJECTION, Action.ALERT, 70),
                event(14227L, INSIDE, Category.BOT, Action.MONITOR, 40)));

        StatsSummary summary = store.summarize(14227L, FROM, TO);

        assertThat(summary.configId()).isEqualTo(14227L);
        assertThat(summary.from()).isEqualTo(FROM);
        assertThat(summary.to()).isEqualTo(TO);
        assertThat(summary.totalEvents()).isEqualTo(4);
        assertThat(summary.byCategory()).containsExactly(
                entry(Category.INJECTION, 3, "71.7"),
                entry(Category.BOT, 1, "40.0"));
        assertThat(summary.byAction()).containsExactly(
                java.util.Map.entry(Action.DENY, 2L),
                java.util.Map.entry(Action.ALERT, 1L),
                java.util.Map.entry(Action.MONITOR, 1L));
    }

    @Test
    void emptyWindowHasZeroTotalAndNoGroups() {
        StatsSummary summary = store.summarize(14227L, FROM, TO);

        assertThat(summary.totalEvents()).isZero();
        assertThat(summary.byCategory()).isEmpty();
        assertThat(summary.byAction()).isEmpty();
    }

    @Test
    void averageSkipsUnscoredEventsButCountIncludesThem() {
        store.saveBatch(List.of(
                event(1L, INSIDE, Category.XSS, Action.DENY, 50),
                event(1L, INSIDE, Category.XSS, Action.DENY, null),
                event(1L, INSIDE, Category.DOS, Action.DENY, null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.byCategory()).containsExactly(
                entry(Category.XSS, 2, "50.0"),
                java.util.Map.entry(Category.DOS, new CategoryStats(1, null)));
    }

    @Test
    void averageIsRoundedHalfUpToOneDecimal() {
        // (10 + 11 + 11 + 11) / 4 = 10.75 -> 10.8; a score of 0 is a real score.
        store.saveBatch(List.of(
                event(1L, INSIDE, Category.BOT, Action.DENY, 10),
                event(1L, INSIDE, Category.BOT, Action.DENY, 11),
                event(1L, INSIDE, Category.BOT, Action.DENY, 11),
                event(1L, INSIDE, Category.BOT, Action.DENY, 11),
                event(1L, INSIDE, Category.DOS, Action.DENY, 0)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.byCategory().get(Category.BOT).avgThreatScore()).isEqualByComparingTo("10.8");
        assertThat(summary.byCategory().get(Category.DOS).avgThreatScore()).isEqualByComparingTo("0.0");
    }

    @Test
    void windowIncludesFromAndExcludesTo() {
        store.saveBatch(List.of(
                event(1L, FROM.minusNanos(1_000), Category.BOT, Action.DENY, null),
                event(1L, FROM, Category.BOT, Action.DENY, null),
                event(1L, TO.minusNanos(1_000), Category.BOT, Action.DENY, null),
                event(1L, TO, Category.BOT, Action.DENY, null)));

        assertThat(store.summarize(1L, FROM, TO).totalEvents()).isEqualTo(2);
    }

    @Test
    void filtersOnEventTimeNotReceiveTime() {
        Instant outside = TO.plusSeconds(3600);
        store.saveBatch(List.of(
                event(1L, INSIDE, outside, Category.BOT, Action.DENY, null),
                event(1L, outside, INSIDE, Category.XSS, Action.DENY, null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.totalEvents()).isEqualTo(1);
        assertThat(summary.byCategory()).containsOnlyKeys(Category.BOT);
    }

    @Test
    void configIdFiltersAndNullCountsAllConfigs() {
        store.saveBatch(List.of(
                event(1L, INSIDE, Category.BOT, Action.DENY, 10),
                event(1L, INSIDE, Category.BOT, Action.DENY, 20),
                event(2L, INSIDE, Category.BOT, Action.ALERT, 60)));

        assertThat(store.summarize(1L, FROM, TO).totalEvents()).isEqualTo(2);
        assertThat(store.summarize(3L, FROM, TO).totalEvents()).isZero();

        StatsSummary all = store.summarize(null, FROM, TO);
        assertThat(all.configId()).isNull();
        assertThat(all.totalEvents()).isEqualTo(3);
        assertThat(all.byCategory()).containsExactly(entry(Category.BOT, 3, "30.0"));
        assertThat(all.byAction()).containsExactly(
                java.util.Map.entry(Action.DENY, 2L), java.util.Map.entry(Action.ALERT, 1L));
    }

    @Test
    void totalEqualsTheCategoryAndActionSums() {
        store.saveBatch(List.of(
                event(1L, INSIDE, Category.XSS, Action.DENY, 10),
                event(1L, INSIDE, Category.DOS, Action.ALERT, 20),
                event(1L, INSIDE, Category.RATE_LIMIT, Action.MONITOR, null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.byCategory().values().stream().mapToLong(CategoryStats::count).sum())
                .isEqualTo(summary.totalEvents());
        assertThat(summary.byAction().values().stream().mapToLong(Long::longValue).sum())
                .isEqualTo(summary.totalEvents());
    }

    @Test
    void groupsAreOrderedByCountThenName() {
        store.saveBatch(List.of(
                event(1L, INSIDE, Category.INJECTION, Action.MONITOR, null),
                event(1L, INSIDE, Category.DOS, Action.MONITOR, null),
                event(1L, INSIDE, Category.DOS, Action.MONITOR, null),
                event(1L, INSIDE, Category.XSS, Action.ALERT, null),
                event(1L, INSIDE, Category.XSS, Action.DENY, null),
                event(1L, INSIDE, Category.XSS, Action.DENY, null),
                event(1L, INSIDE, Category.BOT, Action.ALERT, null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        // XSS 3, DOS 2, then BOT and INJECTION with 1 each, by name.
        assertThat(summary.byCategory().keySet())
                .containsExactly(Category.XSS, Category.DOS, Category.BOT, Category.INJECTION);
        // MONITOR 3, then ALERT and DENY with 2 each, by name.
        assertThat(summary.byAction().keySet()).containsExactly(Action.MONITOR, Action.ALERT, Action.DENY);
    }

    @Test
    void topAttackersHaveCountAndAverageScore() {
        store.saveBatch(List.of(
                attack("203.0.113.42", "/login", 80),
                attack("203.0.113.42", "/login", 83),
                attack("203.0.113.42", "/admin", null),
                attack("198.51.100.7", "/", null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        // (80 + 83) / 2 = 81.5; the unscored event still counts. No scored event -> null average.
        assertThat(summary.topAttackers()).containsExactly(
                new AttackerStats("203.0.113.42", 3, new BigDecimal("81.5")),
                new AttackerStats("198.51.100.7", 1, null));
        assertThat(summary.topTargetedPaths()).containsExactly(
                new PathStats("/login", 2), new PathStats("/", 1), new PathStats("/admin", 1));
    }

    @Test
    void topListsKeepTheTenWithTheMostEvents() {
        List<SecurityEvent> events = new ArrayList<>();
        // 12 IPs and 12 paths: number i gets 12 - i events.
        for (int i = 0; i < 12; i++) {
            for (int n = 0; n < 12 - i; n++) {
                events.add(attack("10.0.0." + i, "/p" + i, null));
            }
        }
        store.saveBatch(events);

        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.topAttackers()).extracting(AttackerStats::count)
                .containsExactly(12L, 11L, 10L, 9L, 8L, 7L, 6L, 5L, 4L, 3L);
        assertThat(summary.topAttackers().getFirst().clientIp()).isEqualTo("10.0.0.0");
        assertThat(summary.topTargetedPaths()).extracting(PathStats::path)
                .containsExactly("/p0", "/p1", "/p2", "/p3", "/p4", "/p5", "/p6", "/p7", "/p8", "/p9");
    }

    @Test
    void topListTiesAreBrokenInByteOrder() {
        store.saveBatch(List.of(
                attack("9.0.0.1", "/a", null),
                attack("10.0.0.2", "/B", null)));

        StatsSummary summary = store.summarize(1L, FROM, TO);

        // Compared as text, byte by byte: '1' < '9' and 'B' < 'a'.
        assertThat(summary.topAttackers()).extracting(AttackerStats::clientIp).containsExactly("10.0.0.2", "9.0.0.1");
        assertThat(summary.topTargetedPaths()).extracting(PathStats::path).containsExactly("/B", "/a");
    }

    @Test
    void topListsFollowTheConfigFilter() {
        store.saveBatch(List.of(
                attack(1L, "203.0.113.42", "/login", 10),
                attack(1L, "203.0.113.42", "/login", 20),
                attack(2L, "203.0.113.42", "/login", 60),
                attack(2L, "198.51.100.7", "/", 50)));

        assertThat(store.summarize(2L, FROM, TO).topAttackers()).containsExactly(
                new AttackerStats("198.51.100.7", 1, new BigDecimal("50.0")),
                new AttackerStats("203.0.113.42", 1, new BigDecimal("60.0")));
        // Across configs the same IP and path are one entry each.
        StatsSummary all = store.summarize(null, FROM, TO);
        assertThat(all.topAttackers().getFirst()).isEqualTo(new AttackerStats("203.0.113.42", 3, new BigDecimal("30.0")));
        assertThat(all.topTargetedPaths().getFirst()).isEqualTo(new PathStats("/login", 3));
    }

    @Test
    void emptyWindowHasEmptyTopLists() {
        StatsSummary summary = store.summarize(1L, FROM, TO);

        assertThat(summary.topAttackers()).isEmpty();
        assertThat(summary.topTargetedPaths()).isEmpty();
    }

    private SecurityEvent attack(String clientIp, String path, Integer score) {
        return attack(1L, clientIp, path, score);
    }

    private SecurityEvent attack(long configId, String clientIp, String path, Integer score) {
        return new SecurityEvent("evt-" + nextId++, INSIDE, configId, null, clientIp, "www.example.com",
                path, "GET", 403, null, new SecurityEvent.Rule("1", "RULE", null, Severity.LOW, Category.BOT),
                Action.DENY, null, null, null, INSIDE.plusSeconds(1), null, score);
    }

    private static java.util.Map.Entry<Category, CategoryStats> entry(Category category, long count, String avg) {
        return java.util.Map.entry(category, new CategoryStats(count, new BigDecimal(avg)));
    }

    private SecurityEvent event(long configId, Instant timestamp, Category category, Action action, Integer score) {
        return event(configId, timestamp, timestamp.plusSeconds(1), category, action, score);
    }

    private SecurityEvent event(long configId, Instant timestamp, Instant receivedAt, Category category,
                                Action action, Integer score) {
        return new SecurityEvent("evt-" + nextId++, timestamp, configId, null, "203.0.113.42", "www.example.com",
                "/api/v1/login", "GET", 403, null, new SecurityEvent.Rule("1", "RULE", null, Severity.LOW, category),
                action, null, null, null, receivedAt, null, score);
    }
}
