package com.miniwsa.config;

import org.junit.jupiter.api.Test;
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
}
