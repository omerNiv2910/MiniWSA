package com.miniwsa.storage;

import com.miniwsa.model.SecurityEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * Writes event batches to PostgreSQL.
 *
 * <p>A batch can be handled more than once (the worker retries it after a failure), so saving
 * must be safe to repeat. Every event is inserted with {@code ON CONFLICT (event_id) DO NOTHING},
 * so an event already stored (including a repeat of an eventId earlier in the same batch) is
 * skipped. The whole batch is one transaction: a failed attempt rolls back entirely and the retry
 * starts clean.
 */
@Component
public class EventStore {

    static final String INSERT_EVENT = """
            INSERT INTO events (event_id, timestamp, config_id, policy_id, client_ip, hostname, path,
                                method, status_code, user_agent, rule_id, rule_name, rule_message,
                                severity, category, action, geo_country, geo_city,
                                request_size, response_size, received_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (event_id) DO NOTHING
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public EventStore(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    public void saveBatch(List<SecurityEvent> events) {
        if (events.isEmpty()) {
            return;
        }
        tx.executeWithoutResult(status ->
                jdbc.batchUpdate(INSERT_EVENT, events, events.size(), EventStore::bindEvent));
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
