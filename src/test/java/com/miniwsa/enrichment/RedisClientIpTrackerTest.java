package com.miniwsa.enrichment;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Runs against a real Redis in Docker. */
@Testcontainers
class RedisClientIpTrackerTest {

    @Container
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4")).withExposedPorts(6379);

    private static final Instant T0 = Instant.parse("2026-05-20T14:50:00Z");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-20T15:00:00Z"), ZoneOffset.UTC);
    private static final String IP = "203.0.113.42";

    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisClientIpTracker tracker;

    @BeforeAll
    static void connect() {
        factory = connectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redis = new StringRedisTemplate(factory);
        tracker = new RedisClientIpTracker(redis, CLOCK);
    }

    @AfterAll
    static void disconnect() {
        factory.destroy();
    }

    @BeforeEach
    void flush() {
        try (var connection = factory.getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void sixthEventInTheWindowIsTheFirstWithFivePriorEvents() {
        List<SecurityEvent> batch = IntStream.range(0, 6)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusSeconds(60L * i)))
                .toList();

        assertThat(tracker.priorCounts(batch)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void earlierBatchesCount() {
        tracker.priorCounts(IntStream.range(0, 5)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusSeconds(60L * i)))
                .toList());

        assertThat(tracker.priorCounts(List.of(event("evt-5", IP, T0.plusSeconds(300))))).containsExactly(5L);
    }

    @Test
    void eventExactlyTenMinutesEarlierCountsButOneMillisecondMoreDoesNot() {
        tracker.priorCounts(List.of(
                event("edge", IP, T0),
                event("too-old", IP, T0.minusMillis(1))));

        assertThat(tracker.priorCounts(List.of(event("now", IP, T0.plus(Duration.ofMinutes(10))))))
                .containsExactly(1L);
    }

    @Test
    void laterEventsDoNotCountForAnEarlierOne() {
        List<Long> counts = tracker.priorCounts(List.of(
                event("late", IP, T0.plusSeconds(120)),
                event("early", IP, T0)));

        assertThat(counts).containsExactly(1L, 0L);
    }

    @Test
    void orderInsideTheBatchDoesNotMatter() {
        List<SecurityEvent> sorted = IntStream.range(0, 8)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusSeconds(30L * i)))
                .toList();
        List<SecurityEvent> shuffled = new ArrayList<>(sorted);
        Collections.shuffle(shuffled, new Random(42));

        List<Long> shuffledCounts = tracker.priorCounts(shuffled);

        for (int i = 0; i < shuffled.size(); i++) {
            assertThat(shuffledCounts.get(i)).isEqualTo(sorted.indexOf(shuffled.get(i)));
        }
    }

    @Test
    void retriedBatchGetsTheSameCountsAndAddsNothing() {
        List<SecurityEvent> batch = IntStream.range(0, 6)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusSeconds(60L * i)))
                .toList();

        List<Long> first = tracker.priorCounts(batch);
        List<Long> retry = tracker.priorCounts(batch);

        assertThat(retry).isEqualTo(first);
        assertThat(redis.opsForZSet().zCard(RedisClientIpTracker.key(batch.getFirst()))).isEqualTo(6);
    }

    @Test
    void duplicateEventIdIsCountedOnce() {
        SecurityEvent event = event("dup", IP, T0);

        List<Long> counts = tracker.priorCounts(List.of(event, event, event("other", IP, T0.plusSeconds(1))));

        assertThat(counts).containsExactly(0L, 0L, 1L);
    }

    @Test
    void differentIpsDoNotAffectEachOther() {
        List<SecurityEvent> batch = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            batch.add(event("a-" + i, "198.51.100.7", T0.plusSeconds(i)));
        }
        batch.add(event("b-0", "2001:db8::1", T0.plusSeconds(10)));

        assertThat(tracker.priorCounts(batch)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 0L);
    }

    @Test
    void oldEntriesAreTrimmedOnLaterBatches() {
        tracker.priorCounts(List.of(event("old-1", IP, T0), event("old-2", IP, T0.plusSeconds(1))));

        Instant later = T0.plus(Duration.ofMinutes(30));
        var laterTracker = new RedisClientIpTracker(redis, Clock.fixed(later, ZoneOffset.UTC));
        assertThat(laterTracker.priorCounts(List.of(event("new", IP, later)))).containsExactly(0L);

        assertThat(redis.opsForZSet().range(KEY, 0, -1)).containsExactly("new");
    }

    @Test
    void historicalTimestampsStillGetCounted() {
        var novemberClock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
        var tracker = new RedisClientIpTracker(redis, novemberClock);

        List<Long> counts = tracker.priorCounts(IntStream.range(0, 6)
                .mapToObj(i -> event("may-" + i, IP, T0.plusSeconds(i)))
                .toList());

        assertThat(counts.getLast()).isEqualTo(5L);
    }

    @Test
    void farFutureEventDoesNotWipeTheHistory() {
        tracker.priorCounts(IntStream.range(0, 5)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusSeconds(i)))
                .toList());

        tracker.priorCounts(List.of(event("future", IP, Instant.parse("2030-01-01T00:00:00Z"))));

        assertThat(redis.opsForZSet().zCard(KEY)).isEqualTo(6);
    }

    @Test
    void keepsAtMostTheNewestEntriesPerIp() {
        int total = RedisClientIpTracker.MAX_ENTRIES_PER_IP + 50;
        tracker.priorCounts(IntStream.range(0, total)
                .mapToObj(i -> event("evt-" + i, IP, T0.plusMillis(i)))
                .toList());

        assertThat(redis.opsForZSet().zCard(KEY)).isEqualTo(RedisClientIpTracker.MAX_ENTRIES_PER_IP);
        assertThat(redis.opsForZSet().range(KEY, 0, 0)).containsExactly("evt-50");
    }

    @Test
    void keyHasTheEventsUnderTheirIdsAndATtl() {
        tracker.priorCounts(List.of(event("evt-1", IP, T0)));

        assertThat(redis.opsForZSet().score(KEY, "evt-1")).isEqualTo((double) T0.toEpochMilli());
        Long ttl = redis.getExpire(KEY);
        assertThat(ttl).isBetween(1L, RedisClientIpTracker.KEY_TTL.toSeconds());
    }

    @Test
    void emptyBatchReturnsNoCounts() {
        assertThat(tracker.priorCounts(List.of())).isEmpty();
    }

    @Test
    void redisDownFailsFast() {
        LettuceConnectionFactory dead = connectionFactory("localhost", 1);
        try {
            var deadTracker = new RedisClientIpTracker(new StringRedisTemplate(dead), CLOCK);
            long start = System.nanoTime();

            assertThatThrownBy(() -> deadTracker.priorCounts(List.of(event("evt-1", IP, T0))))
                    .isInstanceOf(RuntimeException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
        } finally {
            dead.destroy();
        }
    }

    private static final String KEY = RedisClientIpTracker.KEY_PREFIX + IP;

    private static LettuceConnectionFactory connectionFactory(String host, int port) {
        var clientConfig = LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(1)).build();
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
        factory.afterPropertiesSet();
        factory.start();
        return factory;
    }

    private static SecurityEvent event(String id, String ip, Instant timestamp) {
        return new SecurityEvent(id, timestamp, 14227L, null, ip, "www.example.com", "/", "GET", 403, null,
                new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT),
                Action.DENY, null, null, null, null, null, null);
    }
}
