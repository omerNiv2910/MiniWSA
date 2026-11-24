package com.miniwsa.ratelimit;

import java.time.Duration;
import java.time.Instant;

/**
 * One fixed rate-limit window: {@code [start, start + length)}.
 *
 * <p>Windows are aligned to the Unix epoch ({@code start} is a multiple of {@code length}), so every
 * instance computes the same window for the same moment without sharing any state; the window's start
 * second is what makes the Redis key unique per window.
 *
 * <p>A fixed window is the simplest scheme that needs one counter per client, but it is not smooth: a
 * client can spend its whole limit just before a boundary and again just after it, so up to
 * {@code 2 x limit} requests can get through in a span of one window length. That is acceptable here;
 * a sliding window would cost a sorted set per client instead of one counter.
 *
 * <p>Pure maths: no Spring, no Redis, no clock. Callers pass {@code now} in. Inputs are not re-checked:
 * {@code window} comes from {@code miniwsa.rate-limit.window}, validated at startup (at least 1s), and
 * {@code now} from the injected {@code Clock}.
 */
public record FixedWindow(Instant start, Duration length) {

    /** The window containing {@code now}: {@code now} floored to a multiple of {@code window} since the epoch. */
    static FixedWindow at(Instant now, Duration window) {
        long ms = window.toMillis();
        return new FixedWindow(Instant.ofEpochMilli(Math.floorDiv(now.toEpochMilli(), ms) * ms), window);
    }
}
