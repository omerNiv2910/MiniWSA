package com.miniwsa.enrichment;

import com.miniwsa.model.SecurityEvent;
import org.springframework.data.redis.connection.DefaultStringRedisConnection;
import org.springframework.data.redis.connection.RedisZSetCommands.ZAddArgs;
import org.springframework.data.redis.connection.StringRedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link ClientIpTracker} backed by one Redis sorted set per client IP.
 *
 * <p>Key {@code miniwsa:ip:<clientIp>}; member = {@code eventId}; score = event {@code timestamp} in epoch
 * millis. A whole batch is one pipelined round trip:
 * <ol>
 *   <li>{@code ZADD NX} every event. Using the eventId as member means a resent event is stored once,
 *       and NX keeps its first timestamp.</li>
 *   <li>{@code ZCOUNT [ts - 10 min, ts]} for every event, minus 1 for the event itself.</li>
 *   <li>Per IP: drop entries older than {@code min(oldest ts in batch, now) - 10 min}, keep at most the
 *       newest {@value #MAX_ENTRIES_PER_IP} entries, and reset the key's TTL.</li>
 * </ol>
 * All events are added before any is counted, so the counts depend only on what is in the set: the order
 * of events inside a batch does not matter and a retried batch gets the same counts.
 *
 * <p>Redis does the counting; only one number per event comes back, and only the keys of IPs in the
 * batch are touched.
 */
@Component
public class RedisClientIpTracker implements ClientIpTracker {

    static final String KEY_PREFIX = "miniwsa:ip:";
    static final Duration WINDOW = Duration.ofMinutes(10);
    /** Only frees memory for IPs that went quiet; the window itself is enforced by timestamps. */
    static final Duration KEY_TTL = Duration.ofMinutes(15);
    /** Bounds memory when one IP floods; far above the threshold of 5, so counts that matter are exact. */
    static final int MAX_ENTRIES_PER_IP = 1000;

    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisClientIpTracker(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    @Override
    public List<Long> priorCounts(List<SecurityEvent> events) {
        // Empty batch: no Redis call.
        if (events.isEmpty()) {
            return List.of();
        }

        // Times in epoch millis, to match the Redis scores.
        long windowMillis = WINDOW.toMillis();
        long nowMillis = clock.millis();

        // Each IP's oldest event in the batch: which keys to clean, and what the cleanup must keep.
        Map<String, Long> oldestPerKey = new LinkedHashMap<>();
        for (SecurityEvent e : events) {
            oldestPerKey.merge(key(e), millis(e), Math::min);
        }

        // All commands go to Redis in one round trip; replies come back in the same order.
        List<Object> results = redis.executePipelined((RedisCallback<Object>) connection -> {
            // String commands (the connection is a proxy, so wrap instead of cast).
            StringRedisConnection conn = new DefaultStringRedisConnection(connection);

            // 1. Add each event (NX: a resent event keeps its first timestamp).
            for (SecurityEvent e : events) {
                conn.zAdd(key(e), millis(e), e.eventId(), ZAddArgs.ifNotExists());
            }

            // 2. Count each event's window [ts - 10 min, ts], itself included.
            for (SecurityEvent e : events) {
                long ts = millis(e);
                conn.zCount(key(e), ts - windowMillis, ts);
            }

            // 3. Clean up once per IP - Remove old events.
            oldestPerKey.forEach((key, oldest) -> {
                // Keep what this batch needs; cap at now so a future timestamp can't wipe history.
                long cutoff = Math.min(oldest, nowMillis) - windowMillis;
                // Remove entries before the cutoff.
                conn.zRemRangeByScore(key, -Double.MAX_VALUE, cutoff - 1);
                // Keep only the newest MAX_ENTRIES_PER_IP.
                conn.zRemRange(key, 0, -(MAX_ENTRIES_PER_IP + 1));
                // Delete the key once the IP is quiet for KEY_TTL.
                conn.expire(key, KEY_TTL.toSeconds());
            });

            // Required by RedisCallback; replies come from the pipeline.
            return null;
        });

        // Replies: [n ZADD | n ZCOUNT | 3 per IP]. Wrong size: fail rather than guess.
        int n = events.size();
        int expected = 2 * n + 3 * oldestPerKey.size();
        if (results.size() != expected) {
            throw new IllegalStateException("Expected " + expected + " Redis replies, got " + results.size());
        }

        // ZCOUNT replies minus the event itself = earlier events.
        List<Long> counts = new ArrayList<>(n);
        for (Object reply : results.subList(n, 2 * n)) {
            counts.add(Math.max(0, ((Number) reply).longValue() - 1));
        }
        return counts;
    }

    static String key(SecurityEvent event) {
        return KEY_PREFIX + event.clientIp();
    }

    private static long millis(SecurityEvent event) {
        return event.timestamp().toEpochMilli();
    }
}
