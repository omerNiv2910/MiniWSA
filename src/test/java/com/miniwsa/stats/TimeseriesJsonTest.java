package com.miniwsa.stats;

import com.miniwsa.stats.Timeseries.Bucket;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TimeseriesJsonTest {

    private static final JsonMapper mapper = JsonMapper.builder().build();
    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T14:15:00Z");
    private static final List<Bucket> BUCKETS = List.of(
            new Bucket(Instant.parse("2026-05-20T14:00:00Z"), 12),
            new Bucket(Instant.parse("2026-05-20T14:05:00Z"), 0),
            new Bucket(Instant.parse("2026-05-20T14:10:00Z"), 30));

    @Test
    void writesTheDocumentedShape() {
        String json = mapper.writeValueAsString(
                new Timeseries(14227L, FROM, TO, TimeseriesInterval.FIVE_MINUTES, BUCKETS));

        assertThat(json).isEqualTo("""
                {"configId":14227,\
                "timeRange":{"from":"2026-05-20T14:00:00Z","to":"2026-05-20T14:15:00Z"},\
                "interval":"5m",\
                "totalEvents":42,\
                "buckets":[{"start":"2026-05-20T14:00:00Z","count":12},\
                {"start":"2026-05-20T14:05:00Z","count":0},\
                {"start":"2026-05-20T14:10:00Z","count":30}]}""");
    }

    @Test
    void leavesOutConfigIdWhenNull() {
        String json = mapper.writeValueAsString(
                new Timeseries(null, FROM, TO, TimeseriesInterval.ONE_HOUR, BUCKETS));

        assertThat(json).doesNotContain("configId").startsWith("{\"timeRange\":").contains("\"interval\":\"1h\"");
    }

    @Test
    void totalEventsIsTheSumOfBuckets() {
        assertThat(new Timeseries(1L, FROM, TO, TimeseriesInterval.FIVE_MINUTES, BUCKETS).totalEvents())
                .isEqualTo(42);
        assertThat(new Timeseries(1L, FROM, TO, TimeseriesInterval.FIVE_MINUTES,
                List.of(new Bucket(FROM, 0))).totalEvents()).isZero();
    }
}
