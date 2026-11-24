package com.miniwsa.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MiniWsaPropertiesTest {

    @Configuration
    @EnableConfigurationProperties(MiniWsaProperties.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(Config.class);

    @Test
    void samplesQueryTimeoutDefaultsToFiveSecondsWhenTheBlockIsMissing() {
        runner.run(context -> assertThat(context.getBean(MiniWsaProperties.class).samples().queryTimeout())
                .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void samplesQueryTimeoutDefaultsToFiveSecondsWhenTheKeyIsMissing() {
        runner.withPropertyValues("miniwsa.samples.unrelated=x")
                .run(context -> assertThat(context.getBean(MiniWsaProperties.class).samples().queryTimeout())
                        .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void samplesQueryTimeoutCanBeSet() {
        runner.withPropertyValues("miniwsa.samples.query-timeout=750ms")
                .run(context -> assertThat(context.getBean(MiniWsaProperties.class).samples().queryTimeout())
                        .isEqualTo(Duration.ofMillis(750)));
    }

    @Test
    void timeseriesQueryTimeoutDefaultsToFiveSecondsWhenTheBlockIsMissing() {
        runner.run(context -> assertThat(context.getBean(MiniWsaProperties.class).timeseries().queryTimeout())
                .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void timeseriesQueryTimeoutDefaultsToFiveSecondsWhenTheKeyIsMissing() {
        runner.withPropertyValues("miniwsa.timeseries.unrelated=x")
                .run(context -> assertThat(context.getBean(MiniWsaProperties.class).timeseries().queryTimeout())
                        .isEqualTo(Duration.ofSeconds(5)));
    }

    @Test
    void timeseriesQueryTimeoutCanBeSetIndependentlyOfSamples() {
        runner.withPropertyValues("miniwsa.timeseries.query-timeout=2s", "miniwsa.samples.query-timeout=750ms")
                .run(context -> {
                    MiniWsaProperties props = context.getBean(MiniWsaProperties.class);
                    assertThat(props.timeseries().queryTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.samples().queryTimeout()).isEqualTo(Duration.ofMillis(750));
                });
    }

    @Test
    void rateLimitDefaultsWhenTheBlockIsMissing() {
        runner.run(context -> {
            MiniWsaProperties.RateLimit rateLimit = context.getBean(MiniWsaProperties.class).rateLimit();
            assertThat(rateLimit.enabled()).isTrue();
            assertThat(rateLimit.limit()).isEqualTo(100);
            assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void rateLimitDefaultsEachMissingKey() {
        runner.withPropertyValues("miniwsa.rate-limit.limit=7")
                .run(context -> {
                    MiniWsaProperties.RateLimit rateLimit = context.getBean(MiniWsaProperties.class).rateLimit();
                    assertThat(rateLimit.enabled()).isTrue();
                    assertThat(rateLimit.limit()).isEqualTo(7);
                    assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
                });
        runner.withPropertyValues("miniwsa.rate-limit.enabled=false")
                .run(context -> {
                    MiniWsaProperties.RateLimit rateLimit = context.getBean(MiniWsaProperties.class).rateLimit();
                    assertThat(rateLimit.enabled()).isFalse();
                    assertThat(rateLimit.limit()).isEqualTo(100);
                    assertThat(rateLimit.window()).isEqualTo(Duration.ofMinutes(1));
                });
    }

    @Test
    void rateLimitCanBeSet() {
        runner.withPropertyValues("miniwsa.rate-limit.enabled=false", "miniwsa.rate-limit.limit=5",
                        "miniwsa.rate-limit.window=30s")
                .run(context -> {
                    MiniWsaProperties.RateLimit rateLimit = context.getBean(MiniWsaProperties.class).rateLimit();
                    assertThat(rateLimit.enabled()).isFalse();
                    assertThat(rateLimit.limit()).isEqualTo(5);
                    assertThat(rateLimit.window()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1"})
    void rateLimitBelowOneFailsStartup(String limit) {
        runner.withPropertyValues("miniwsa.rate-limit.limit=" + limit)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessage("miniwsa.rate-limit.limit must be at least 1"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0s", "500ms"})
    void rateLimitWindowBelowOneSecondFailsStartup(String window) {
        runner.withPropertyValues("miniwsa.rate-limit.window=" + window)
                .run(context -> assertThat(context).hasFailed()
                        .getFailure().rootCause().hasMessage("miniwsa.rate-limit.window must be at least 1s"));
    }
}
