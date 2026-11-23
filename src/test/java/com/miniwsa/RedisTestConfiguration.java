package com.miniwsa;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real Redis in Docker for {@code @SpringBootTest}s. {@code @ServiceConnection(name = "redis")} points
 * {@code spring.data.redis} at it. Import it with {@code @Import(RedisTestConfiguration.class)}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RedisTestConfiguration {

    public static final String IMAGE = "redis:7.4";

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>(DockerImageName.parse(IMAGE)).withExposedPorts(6379);
    }
}
