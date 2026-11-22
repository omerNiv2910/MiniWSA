package com.miniwsa.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Application settings bound from the {@code miniwsa.*} namespace.
 */
@ConfigurationProperties(prefix = "miniwsa")
public record MiniWsaProperties(Topics topics) {

    public record Topics(String raw, String dlq) {
    }
}
