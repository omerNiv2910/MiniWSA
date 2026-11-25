package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** V2 on a database that already has V1 rows: the new columns are added as NULL, nothing is lost. */
@Testcontainers
class EnrichmentMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    @Test
    void existingRowsGetNullEnrichmentColumns() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        Flyway.configure().dataSource(dataSource).target("1").load().migrate();
        jdbc.update("""
                INSERT INTO events (event_id, timestamp, config_id, client_ip, hostname, path, method, status_code,
                                    rule_id, rule_name, severity, category, action, received_at)
                VALUES ('old-1', now(), 14227, '203.0.113.42', 'www.example.com', '/admin', 'GET', 403,
                        '1', 'R', 'HIGH', 'XSS', 'DENY', now())
                """);

        Flyway.configure().dataSource(dataSource).target("2").load().migrate();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM events WHERE event_id = 'old-1'");
        assertThat(row).containsEntry("path", "/admin")
                .containsEntry("attack_type", null)
                .containsEntry("threat_score", null);
        assertThat(jdbc.queryForObject("SELECT max(version) FROM flyway_schema_history", String.class)).isEqualTo("2");
    }
}
