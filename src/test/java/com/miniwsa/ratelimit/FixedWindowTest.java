package com.miniwsa.ratelimit;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class FixedWindowTest {

    private static final Duration MINUTE = Duration.ofMinutes(1);
    private static final Instant BOUNDARY = Instant.parse("2026-05-20T15:00:00Z");

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    @Test
    void everyInstantInTheMinuteGetsTheSameWindow() {
        assertThat(FixedWindow.at(BOUNDARY, MINUTE).start()).isEqualTo(BOUNDARY);
        assertThat(FixedWindow.at(at("2026-05-20T15:00:59.999Z"), MINUTE).start()).isEqualTo(BOUNDARY);
        assertThat(FixedWindow.at(at("2026-05-20T15:01:00Z"), MINUTE).start()).isEqualTo(at("2026-05-20T15:01:00Z"));
    }

    @Test
    void oneMilliBeforeTheBoundaryIsThePreviousWindow() {
        assertThat(FixedWindow.at(BOUNDARY.minusMillis(1), MINUTE).start()).isEqualTo(at("2026-05-20T14:59:00Z"));
    }

    @Test
    void usesTheConfiguredWindowLength() {
        Duration window = Duration.ofSeconds(30);
        assertThat(FixedWindow.at(at("2026-05-20T15:00:29.999Z"), window).start()).isEqualTo(BOUNDARY);
        assertThat(FixedWindow.at(at("2026-05-20T15:00:45Z"), window).start()).isEqualTo(at("2026-05-20T15:00:30Z"));
    }
}
