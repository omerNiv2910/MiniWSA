package com.miniwsa;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL in Docker for {@code @SpringBootTest}s. {@code @ServiceConnection} points the
 * datasource at it, and Flyway migrates it on startup like in production. Import it with
 * {@code @Import(PostgresTestConfiguration.class)}; the container is shared by every test class
 * that uses the same Spring context.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestConfiguration {

    public static final String IMAGE = "postgres:17";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer(IMAGE);
    }
}
