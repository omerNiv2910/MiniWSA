package com.miniwsa.storage;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.stats.StatsSummary;
import com.miniwsa.stats.StatsSummary.AttackerStats;
import com.miniwsa.stats.StatsSummary.CategoryStats;
import com.miniwsa.stats.StatsSummary.PathStats;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Writes event batches to PostgreSQL and reads the statistics for a time window ({@link #summarize}).
 *
 * <p>A batch can be handled more than once (the worker retries it after a failure), so saving
 * must be safe to repeat. Every event is inserted with {@code ON CONFLICT (event_id) DO NOTHING},
 * so an event already stored (including a repeat of an eventId earlier in the same batch) is
 * skipped. The whole batch is one transaction: a failed attempt rolls back entirely and the retry
 * starts clean.
 *
 * <p>{@code attackType} and {@code threatScore} are stored as given and may be null (not enriched).
 */
@Component
public class EventStore {

    static final String INSERT_EVENT = """
            INSERT INTO events (event_id, timestamp, config_id, policy_id, client_ip, hostname, path,
                                method, status_code, user_agent, rule_id, rule_name, rule_message,
                                severity, category, action, geo_country, geo_city,
                                request_size, response_size, received_at, attack_type, threat_score)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    // Stats for a window: the events whose time is in [from, to), for one configuration or all of
    // them. The filter is on event time, so the (config_id, timestamp) and (timestamp) indexes apply.
    // Each query template below takes one of these two WHERE clauses (%s), chosen once per request,
    // rather than "(? IS NULL OR config_id = ?)", which can stop the index from being used.
    private static final String WINDOW = "timestamp >= ? AND timestamp < ?";
    private static final String WINDOW_AND_CONFIG = WINDOW + " AND config_id = ?";

    private static final String TOTAL = """
            SELECT count(*)
            FROM events
            WHERE %s
            """;

    // avg() skips events without a threat score and is null when none has one; round() rounds the
    // (always non-negative) mean half up to 1 decimal. Highest count first, ties by name.
    private static final String BY_CATEGORY = """
            SELECT category, count(*) AS event_count, round(avg(threat_score), 1) AS avg_threat_score
            FROM events
            WHERE %s
            GROUP BY category
            ORDER BY event_count DESC, category
            """;

    private static final String BY_ACTION = """
            SELECT action, count(*) AS event_count
            FROM events
            WHERE %s
            GROUP BY action
            ORDER BY event_count DESC, action
            """;

    // The 10 client IPs and the 10 paths with the most events. Ties are broken by the value in byte
    // order (COLLATE "C"), so the result does not depend on the database's locale.
    private static final String TOP_ATTACKERS = """
            SELECT client_ip, count(*) AS event_count, round(avg(threat_score), 1) AS avg_threat_score
            FROM events
            WHERE %s
            GROUP BY client_ip
            ORDER BY event_count DESC, client_ip COLLATE "C"
            LIMIT 10
            """;

    private static final String TOP_PATHS = """
            SELECT path, count(*) AS event_count
            FROM events
            WHERE %s
            GROUP BY path
            ORDER BY event_count DESC, path COLLATE "C"
            LIMIT 10
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;

    public EventStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
        // The stats queries run in one read-only snapshot, so they all see the same events (the
        // total always equals the category and action sums) even while new batches are being saved.
        this.readTx = new TransactionTemplate(Objects.requireNonNull(tx.getTransactionManager()));
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }

    public void saveBatch(List<SecurityEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status ->
                jdbc.batchUpdate(INSERT_EVENT, events, events.size(), EventStore::bindEvent));
    }

    /**
     * Counts the events whose {@code timestamp} is in {@code [from, to)}: in total, per category
     * (with the average threat score), per action, and for the 10 client IPs (with the average
     * threat score) and 10 paths with the most events.
     *
     * <p>The caller ({@code StatsController}) has already checked the window: {@code from} and
     * {@code to} are not null and {@code from} is before {@code to}.
     *
     * @param configId the configuration to count, or {@code null} for all configurations
     */
    public StatsSummary summarize(Long configId, Instant from, Instant to) {
        // Chosen once: the WHERE clause and its parameters, shared by every query below.
        boolean allConfigs = configId == null;
        String where = allConfigs ? WINDOW : WINDOW_AND_CONFIG;
        Object[] args = allConfigs
                ? new Object[] {utc(from), utc(to)}
                : new Object[] {utc(from), utc(to), configId};

        return readTx.execute(status -> {
            Long total = jdbc.queryForObject(TOTAL.formatted(where), Long.class, args);

            // LinkedHashMap keeps the order of the rows: highest count first.
            // Return Grouping by Categories
            Map<Category, CategoryStats> byCategory = new LinkedHashMap<>();
            jdbc.query(BY_CATEGORY.formatted(where), rs -> {
                byCategory.put(Category.valueOf(rs.getString("category")),
                        new CategoryStats(rs.getLong("event_count"), rs.getBigDecimal("avg_threat_score")));
            }, args);

            // Return Grouping by Actions
            Map<Action, Long> byAction = new LinkedHashMap<>();
            jdbc.query(BY_ACTION.formatted(where), rs -> {
                byAction.put(Action.valueOf(rs.getString("action")), rs.getLong("event_count"));
            }, args);

            // Top 10 client IPs and paths, most events first
            List<AttackerStats> topAttackers = jdbc.query(TOP_ATTACKERS.formatted(where),
                    (rs, row) -> new AttackerStats(rs.getString("client_ip"), rs.getLong("event_count"),
                            rs.getBigDecimal("avg_threat_score")),
                    args);
            List<PathStats> topTargetedPaths = jdbc.query(TOP_PATHS.formatted(where),
                    (rs, row) -> new PathStats(rs.getString("path"), rs.getLong("event_count")),
                    args);

            return new StatsSummary(configId, from, to, total, byCategory, byAction, topAttackers, topTargetedPaths);
        });
    }

    private static void bindEvent(PreparedStatement ps, SecurityEvent e) throws SQLException {
        SecurityEvent.Rule rule = e.rule();
        SecurityEvent.GeoLocation geo = e.geoLocation();
        ps.setString(1, e.eventId());
        ps.setObject(2, utc(e.timestamp()));
        ps.setLong(3, e.configId());
        ps.setString(4, e.policyId());
        ps.setString(5, e.clientIp());
        ps.setString(6, e.hostname());
        ps.setString(7, e.path());
        ps.setString(8, e.method());
        ps.setInt(9, e.statusCode());
        ps.setString(10, e.userAgent());
        ps.setString(11, rule.id());
        ps.setString(12, rule.name());
        ps.setString(13, rule.message());
        ps.setString(14, rule.severity().name());
        ps.setString(15, rule.category().name());
        ps.setString(16, e.action().name());
        ps.setString(17, geo == null ? null : geo.country());
        ps.setString(18, geo == null ? null : geo.city());
        setNullableLong(ps, 19, e.requestSize());
        setNullableLong(ps, 20, e.responseSize());
        ps.setObject(21, utc(e.receivedAt()));
        ps.setString(22, e.attackType());
        if (e.threatScore() == null) {
            ps.setNull(23, Types.SMALLINT);
        } else {
            ps.setInt(23, e.threatScore());
        }
    }

    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /** The PostgreSQL driver maps {@code OffsetDateTime} (not {@code Instant}) to {@code timestamptz}. */
    static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
