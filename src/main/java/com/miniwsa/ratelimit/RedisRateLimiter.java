package com.miniwsa.ratelimit;

import com.miniwsa.config.MiniWsaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counts read requests per client IP against a shared fixed window, with one Redis counter per client IP
 * and window.
 *
 * <p>Key {@code miniwsa:ratelimit:<clientIp>:<window start epoch second>}. Windows are aligned to the
 * epoch (see {@link FixedWindow}), so every instance hits the same key for the same moment and the limit
 * holds across instances. A new window means a new key; the old one just expires.
 *
 * <p>Fail-open: if Redis errors or times out, the request is let through and a warning is
 * logged at most once per window, so an outage does not flood the log with one line per request.
 *
 * <p>Off when {@code miniwsa.rate-limit.enabled=false}: then no bean exists and nothing is counted.
 */
@Component
@ConditionalOnBooleanProperty(name = "miniwsa.rate-limit.enabled", matchIfMissing = true)
public class RedisRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimiter.class);

    static final String KEY_PREFIX = "miniwsa:ratelimit:";
    /** Keeps the key a little past its window, so clock skew between instances can't drop a live count. */
    static final Duration TTL_MARGIN = Duration.ofSeconds(5);

    /**
     * Increments the counter and, on the first request of the window, sets its expiry.
     *
     * <p>Lua so it is one round trip and INCR + PEXPIRE are atomic: with two separate commands, a failure
     * between them would leave a counter with no TTL, which never resets and blocks that IP forever.
     * ARGV[1] is the TTL in millis.
     */
    static final DefaultRedisScript<Long> INCR_WITH_EXPIRY = new DefaultRedisScript<>("""
            local c = redis.call('INCR', KEYS[1])
            if c == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[1]) end
            return c
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final int limit;
    private final Duration window;
    private final String ttlMillis;
    /** Epoch millis of the last "Redis unavailable" warning; guards the once-per-window logging. */
    private final AtomicLong lastWarnEpochMilli = new AtomicLong(Long.MIN_VALUE);

    public RedisRateLimiter(StringRedisTemplate redis, Clock clock, MiniWsaProperties props) {
        this.redis = redis;
        this.clock = clock;
        this.limit = props.rateLimit().limit();
        this.window = props.rateLimit().window();
        this.ttlMillis = Long.toString(window.plus(TTL_MARGIN).toMillis());
    }

    /**
     * Counts this request against {@code clientIp}'s current window and says whether it fits.
     *
     * @param clientIp the client IP as resolved by the caller; used as given
     * @return {@code true} if the request is within the limit, {@code false} if it is over it. Also
     *         {@code true} when Redis is unavailable (down or timed out): fail-open, so a Redis outage
     *         never takes the read API down.
     */
    public boolean tryAcquire(String clientIp) {
        Instant now = clock.instant();
        FixedWindow current = FixedWindow.at(now, window);
        try {
            Long count = redis.execute(INCR_WITH_EXPIRY, List.of(key(clientIp, current)), ttlMillis);
            return count == null || count <= limit;   // null never happens with this script; treat it as "allow"
        } catch (DataAccessException e) {
            // Connection failures and timeouts: let the request through.
            warnOncePerWindow(now, e.getMessage());
            return true;
        }
    }

    static String key(String clientIp, FixedWindow window) {
        return KEY_PREFIX + clientIp + ":" + window.start().getEpochSecond();
    }

    /** Logs at most once per window length; the CAS makes sure only one thread wins when many fail at once. */
    private void warnOncePerWindow(Instant now, String reason) {
        long nowMillis = now.toEpochMilli();
        long last = lastWarnEpochMilli.get();
        if (last != Long.MIN_VALUE && nowMillis - last < window.toMillis()) {
            return;
        }
        if (lastWarnEpochMilli.compareAndSet(last, nowMillis)) {
            log.warn("Rate limiter unavailable, letting requests through (next warning in {} at the earliest): {}",
                    window, reason);
        }
    }
}
