package com.miniwsa.stats;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

import static com.miniwsa.stats.TimeseriesInterval.FIVE_MINUTES;
import static com.miniwsa.stats.TimeseriesInterval.ONE_HOUR;
import static com.miniwsa.stats.TimeseriesInterval.ONE_MINUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

class TimeseriesIntervalTest {

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    // #################### Labels ####################

    @Test
    void parsesTheThreeLabels() {
        assertThat(TimeseriesInterval.fromLabel("1m")).contains(ONE_MINUTE);
        assertThat(TimeseriesInterval.fromLabel("5m")).contains(FIVE_MINUTES);
        assertThat(TimeseriesInterval.fromLabel("1h")).contains(ONE_HOUR);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"5M", "1H", "2m", "15m", " 1m", "1m ", " ", "ONE_MINUTE", "60"})
    void rejectsAnythingElse(String label) {
        assertThat(TimeseriesInterval.fromLabel(label)).isEmpty();
    }

    @Test
    void knowsItsWidth() {
        assertThat(ONE_MINUTE.seconds()).isEqualTo(60);
        assertThat(FIVE_MINUTES.seconds()).isEqualTo(300);
        assertThat(ONE_HOUR.seconds()).isEqualTo(3600);
        assertThat(FIVE_MINUTES.duration()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void listsAllowedValues() {
        assertThat(TimeseriesInterval.ALLOWED_VALUES_MESSAGE).isEqualTo("must be one of [1m, 5m, 1h]");
    }

    @Test
    void serializesAsLabel() {
        assertThat(JsonMapper.builder().build().writeValueAsString(FIVE_MINUTES))
                .isEqualTo("\"5m\"");
    }

    // #################### floor ####################

    @Test
    void floorsToEpochAlignedBoundaries() {
        Instant t = at("2026-05-20T14:07:42Z");

        assertThat(ONE_MINUTE.floor(t)).isEqualTo(at("2026-05-20T14:07:00Z"));
        assertThat(FIVE_MINUTES.floor(t)).isEqualTo(at("2026-05-20T14:05:00Z"));
        assertThat(ONE_HOUR.floor(t)).isEqualTo(at("2026-05-20T14:00:00Z"));
    }

    @Test
    void keepsAlignedInstants() {
        Instant t = at("2026-05-20T14:00:00Z");

        assertThat(ONE_MINUTE.floor(t)).isEqualTo(t);
        assertThat(FIVE_MINUTES.floor(t)).isEqualTo(t);
        assertThat(ONE_HOUR.floor(t)).isEqualTo(t);
    }

    @Test
    void dropsFractionalSeconds() {
        assertThat(FIVE_MINUTES.floor(at("2026-05-20T14:04:59.999999999Z")))
                .isEqualTo(at("2026-05-20T14:00:00Z"));
        assertThat(FIVE_MINUTES.floor(at("2026-05-20T14:05:00.001Z")))
                .isEqualTo(at("2026-05-20T14:05:00Z"));
    }

    @Test
    void floorsDownBefore1970() {
        assertThat(ONE_MINUTE.floor(at("1969-12-31T23:59:30Z"))).isEqualTo(at("1969-12-31T23:59:00Z"));
        assertThat(ONE_HOUR.floor(at("1969-12-31T23:59:59.5Z"))).isEqualTo(at("1969-12-31T23:00:00Z"));
    }

    // #################### bucketCount ####################

    @Test
    void countsAlignedWindows() {
        Instant from = at("2026-05-20T14:00:00Z");
        Instant to = at("2026-05-20T15:00:00Z");

        assertThat(ONE_MINUTE.bucketCount(from, to)).isEqualTo(60);
        assertThat(FIVE_MINUTES.bucketCount(from, to)).isEqualTo(12);
        assertThat(ONE_HOUR.bucketCount(from, to)).isEqualTo(1);
    }

    @Test
    void countsUnalignedWindows() {
        // floor(from) is 14:00 for every interval here; to is mid-bucket so the last one is partial.
        Instant from = at("2026-05-20T14:00:30Z");
        Instant to = at("2026-05-20T15:02:10Z");

        assertThat(ONE_MINUTE.bucketCount(from, to)).isEqualTo(63);
        assertThat(FIVE_MINUTES.bucketCount(from, to)).isEqualTo(13);
        assertThat(ONE_HOUR.bucketCount(from, to)).isEqualTo(2);
    }

    @Test
    void fractionalToOpensAnotherBucket() {
        assertThat(FIVE_MINUTES.bucketCount(at("2026-05-20T14:00:00Z"), at("2026-05-20T14:05:00.000000001Z")))
                .isEqualTo(2);
    }

    @Test
    void windowShorterThanOneIntervalIsOneBucket() {
        assertThat(ONE_HOUR.bucketCount(at("2026-05-20T14:10:00Z"), at("2026-05-20T14:10:01Z"))).isEqualTo(1);
        assertThat(ONE_MINUTE.bucketCount(at("2026-05-20T14:00:00.1Z"), at("2026-05-20T14:00:00.2Z"))).isEqualTo(1);
    }

    @Test
    void exactlyMaxBucketsThenOneMore() {
        Instant from = at("2026-05-20T00:00:00Z");
        Instant maxMinutes = from.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS));

        assertThat(ONE_MINUTE.bucketCount(from, maxMinutes)).isEqualTo(Timeseries.MAX_BUCKETS);
        assertThat(ONE_MINUTE.bucketCount(from, maxMinutes.plusSeconds(1))).isEqualTo(Timeseries.MAX_BUCKETS + 1);
    }

    @Test
    void unalignedFromPushesOverMax() {
        // Same MAX_BUCKETS-minute length, but floor(from) is 30s earlier, so one more bucket is needed.
        Instant from = at("2026-05-20T00:00:30Z");

        assertThat(ONE_MINUTE.bucketCount(from, from.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS))))
                .isEqualTo(Timeseries.MAX_BUCKETS + 1);
    }

    @Test
    void rejectsEmptyOrReversedWindows() {
        Instant t = at("2026-05-20T14:00:00Z");

        assertThatIllegalArgumentException().isThrownBy(() -> ONE_MINUTE.bucketCount(t, t));
        assertThatIllegalArgumentException().isThrownBy(() -> ONE_MINUTE.bucketCount(t, t.minusSeconds(1)));
    }
}
