package com.miniwsa.stats;

import com.miniwsa.stats.Timeseries.Bucket;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.miniwsa.stats.TimeseriesInterval.FIVE_MINUTES;
import static com.miniwsa.stats.TimeseriesInterval.ONE_MINUTE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** {@link Timeseries#zeroFilled}: building the response with every bucket present. */
class TimeseriesTest {

    private static Instant at(String iso) {
        return Instant.parse(iso);
    }

    private static List<Bucket> zeroFilled(Instant from, Instant to, TimeseriesInterval interval,
                                           Map<Instant, Long> counts) {
        return Timeseries.zeroFilled(null, from, to, interval, counts).buckets();
    }

    @Test
    void keepsTheWindowAndConfig() {
        Instant from = at("2026-05-20T14:02:00Z");
        Instant to = at("2026-05-20T14:10:00Z");

        Timeseries ts = Timeseries.zeroFilled(42L, from, to, FIVE_MINUTES, Map.of());

        assertThat(ts.configId()).isEqualTo(42L);
        assertThat(ts.from()).isEqualTo(from);
        assertThat(ts.to()).isEqualTo(to);
        assertThat(ts.interval()).isEqualTo(FIVE_MINUTES);
    }

    @Test
    void keepsGapsAsZero() {
        List<Bucket> buckets = zeroFilled(at("2026-05-20T14:00:00Z"), at("2026-05-20T14:15:00Z"), FIVE_MINUTES,
                Map.of(at("2026-05-20T14:00:00Z"), 3L, at("2026-05-20T14:10:00Z"), 7L));

        assertThat(buckets).containsExactly(
                new Bucket(at("2026-05-20T14:00:00Z"), 3),
                new Bucket(at("2026-05-20T14:05:00Z"), 0),
                new Bucket(at("2026-05-20T14:10:00Z"), 7));
    }

    @Test
    void startsAtFlooredFromAndStopsBeforeBoundaryTo() {
        List<Bucket> buckets = zeroFilled(
                at("2026-05-20T14:02:00Z"), at("2026-05-20T14:10:00Z"), FIVE_MINUTES, Map.of());

        assertThat(buckets).extracting(Bucket::start)
                .containsExactly(at("2026-05-20T14:00:00Z"), at("2026-05-20T14:05:00Z"));
    }

    @Test
    void noCountsIsAllZeroButNotEmpty() {
        Timeseries ts = Timeseries.zeroFilled(
                null, at("2026-05-20T14:00:00Z"), at("2026-05-20T15:00:00Z"), ONE_MINUTE, Map.of());

        assertThat(ts.buckets()).hasSize(60).allSatisfy(b -> assertThat(b.count()).isZero());
        assertThat(ts.totalEvents()).isZero();
    }

    @Test
    void isAscendingWithoutGapsOrDuplicates() {
        Instant from = at("2026-05-20T13:47:13.5Z");
        Instant to = at("2026-05-20T16:31:02Z");

        List<Bucket> buckets = zeroFilled(from, to, FIVE_MINUTES, Map.of());

        assertThat(buckets).hasSize((int) FIVE_MINUTES.bucketCount(from, to));
        assertThat(buckets.getFirst().start()).isEqualTo(at("2026-05-20T13:45:00Z"));
        assertThat(buckets.getLast().start()).isEqualTo(at("2026-05-20T16:30:00Z"));
        for (int i = 1; i < buckets.size(); i++) {
            assertThat(Duration.between(buckets.get(i - 1).start(), buckets.get(i).start()))
                    .isEqualTo(Duration.ofMinutes(5));
        }
    }

    @Test
    void refusesMoreThanMaxBuckets() {
        Instant from = at("2026-05-20T00:00:00Z");

        assertThat(zeroFilled(from, from.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS)), ONE_MINUTE, Map.of()))
                .hasSize(Timeseries.MAX_BUCKETS);
        assertThatIllegalArgumentException().isThrownBy(() -> zeroFilled(
                from, from.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS + 1)), ONE_MINUTE, Map.of()));
    }
}
