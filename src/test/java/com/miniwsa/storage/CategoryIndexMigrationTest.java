package com.miniwsa.storage;

import com.miniwsa.PostgresTestConfiguration;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V4: the (category, timestamp) index exists and serves the per-category window count. */
@Testcontainers
class CategoryIndexMigrationTest {

    @Container
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestConfiguration.IMAGE);

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void indexIsOnCategoryThenTimestamp() {
        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'events' AND indexname = 'events_category_time_idx'",
                String.class);

        assertThat(definition).endsWith("(category, \"timestamp\")");
    }

    /**
     * The table is empty, so the planner would pick a sequential scan on cost alone; turning that off
     * shows whether the index can serve the query at all. This is not a timing test.
     */
    @Test
    void theCountForOneCategoryAndWindowUsesTheIndex() {
        List<String> plan = jdbc.execute((Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                var rs = statement.executeQuery("""
                        EXPLAIN SELECT count(*) FROM events
                        WHERE category = 'INJECTION'
                          AND timestamp >= TIMESTAMPTZ '2026-11-25T09:55:00Z'
                          AND timestamp <  TIMESTAMPTZ '2026-11-25T10:00:00Z'
                        """);
                List<String> lines = new ArrayList<>();
                while (rs.next()) {
                    lines.add(rs.getString(1));
                }
                return lines;
            }
        });

        assertThat(String.join("\n", plan)).contains("events_category_time_idx");
    }
}
