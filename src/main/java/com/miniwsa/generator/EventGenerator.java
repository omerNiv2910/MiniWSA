package com.miniwsa.generator;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.SecurityEvent.Rule;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Builds {@code count} random security events over the last {@link #SPAN}, ready for {@code POST /v1/events/ingest}.
 * No I/O: {@link GeneratorCli} writes the result.
 *
 * <p>Every field is picked at random from its list in {@link ReferenceData}, independently of the others. Only the
 * sizes and the status code are made up here: sizes are random numbers, and the status code follows the action and
 * category.
 *
 * <p><b>Attack wave.</b> {@link #WAVE_SHARE} of the events are one attack wave: one client IP and one path, both
 * picked at random, with all their timestamps within {@link #WAVE_SPREAD} of one random moment. Their other fields
 * are random like any other event. The rest of the events have random timestamps across the whole span.
 *
 * <p>The list is sorted by timestamp. Server-set fields ({@code receivedAt}, {@code attackType},
 * {@code threatScore}) stay null.
 */
final class EventGenerator {

    /** Most events in one run; equal to the server's default queue capacity, so one file fits in one request. */
    static final int MAX_COUNT = 100000;

    /** The events cover this long, ending at {@code end}. */
    static final Duration SPAN = Duration.ofDays(7);

    /** Share of the events that belong to the attack wave. */
    static final double WAVE_SHARE = 0.2;

    /** All wave events fall within this long of the wave's start. */
    static final Duration WAVE_SPREAD = Duration.ofSeconds(5);

    private final Random rng;
    private final long startMillis;
    private final long spanMillis = SPAN.toMillis();
    private final List<SecurityEvent> events;
    /** Running number put into each event id, so ids never repeat within a run. */
    private long eventNumber;

    private EventGenerator(int count, Random rng, Instant end) {
        this.rng = rng;
        this.startMillis = end.toEpochMilli() - spanMillis;
        this.events = new ArrayList<>(count);
    }

    /**
     * Exactly {@code count} events with timestamps in {@code [end - SPAN, end)}, sorted by timestamp.
     *
     * @throws IllegalArgumentException if {@code count} is not between 1 and {@link #MAX_COUNT}
     */
    static List<SecurityEvent> generate(int count, Random rng, Instant end) {
        if (count < 1 || count > MAX_COUNT) {
            throw new IllegalArgumentException("count must be between 1 and " + MAX_COUNT + ", got " + count);
        }
        EventGenerator generator = new EventGenerator(count, rng, end);
        int waveCount = (int) Math.round(count * WAVE_SHARE);
        generator.addWave(waveCount);
        generator.addRandom(count - waveCount);
        generator.events.sort(Comparator.comparing(SecurityEvent::timestamp).thenComparing(SecurityEvent::eventId));
        return generator.events;
    }

    /** {@code size} events from one random client IP to one random path, all within WAVE_SPREAD. */
    private void addWave(int size) {
        String clientIp = pick(ReferenceData.CLIENT_IPS);
        String path = pick(ReferenceData.PATHS);
        long spreadMillis = WAVE_SPREAD.toMillis();
        long waveStart = startMillis + rng.nextLong(spanMillis - spreadMillis);
        for (int i = 0; i < size; i++) {
            addEvent(Instant.ofEpochMilli(waveStart + rng.nextLong(spreadMillis)), clientIp, path);
        }
    }

    /** {@code count} events with random IPs, paths and timestamps across the whole span. */
    private void addRandom(int count) {
        for (int i = 0; i < count; i++) {
            addEvent(Instant.ofEpochMilli(startMillis + rng.nextLong(spanMillis)),
                    pick(ReferenceData.CLIENT_IPS), pick(ReferenceData.PATHS));
        }
    }

    /** One event with the given timestamp, IP and path; every other field is random. */
    private void addEvent(Instant ts, String clientIp, String path) {
        Rule rule = new Rule(randomUuid().toString(), pick(ReferenceData.RULE_NAMES),
                pick(ReferenceData.RULE_MESSAGES), pick(ReferenceData.SEVERITIES), pick(ReferenceData.CATEGORIES));
        Action action = pick(ReferenceData.ACTIONS);
        long requestSize = 200 + rng.nextInt(1800);
        long responseSize = action == Action.DENY ? 100 + rng.nextInt(500) : 500 + rng.nextInt(50_000);
        events.add(new SecurityEvent(nextEventId(), ts, pick(ReferenceData.CONFIG_IDS),
                pick(ReferenceData.POLICY_IDS), clientIp, pick(ReferenceData.HOSTNAMES), path,
                pick(ReferenceData.METHODS), statusCode(action, rule.category()), pick(ReferenceData.USER_AGENTS),
                rule, action, pick(ReferenceData.GEO_LOCATIONS), requestSize, responseSize, null, null, null));
    }

    /** A blocked request gets the edge's error; one let through gets an ordinary origin response. */
    private int statusCode(Action action, Category category) {
        if (action == Action.DENY) {
            return switch (category) {
                case RATE_LIMIT -> 429;
                case DOS -> 503;
                default -> 403;
            };
        }
        int r = rng.nextInt(100);
        return r < 80 ? 200 : r < 90 ? 404 : r < 96 ? 302 : 500;
    }

    /** A random version-4 UUID with the running event number in its low 32 bits, so no two event ids are equal. */
    private String nextEventId() {
        UUID random = randomUuid();
        long lsb = (random.getLeastSignificantBits() & 0xFFFFFFFF00000000L) | eventNumber++;
        return new UUID(random.getMostSignificantBits(), lsb).toString();
    }

    /** A random version-4 UUID drawn from {@code rng}. */
    private UUID randomUuid() {
        long msb = (rng.nextLong() & ~0xF000L) | 0x4000L;                       // version 4
        long lsb = (rng.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // IETF variant
        return new UUID(msb, lsb);
    }

    private <T> T pick(List<T> values) {
        return values.get(rng.nextInt(values.size()));
    }
}
