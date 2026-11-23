package com.miniwsa.enrichment;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;

import java.time.Instant;

/**
 * Computes the server-side fields stored with each event: the human-readable {@code attackType}, the
 * {@code threatScore} (0-100), and the {@code receivedAt} stamp.
 *
 * <p>Pure functions only: no I/O and no clock. The repeat-offender count comes from the per-IP tracker and
 * is passed in, so all scoring rules can be unit-tested here.
 *
 * <p>The switches are exhaustive and have no {@code default} branch, so adding an enum constant without a
 * mapping is a compile error rather than a silent fallback at runtime. Unknown or null enum values never
 * reach this class: the ingest API rejects them with a 400.
 */
public final class EventEnricher {

    /** Prior events from the same clientIp in the window needed for the bonus ("more than 5" including this one). */
    private static final long REPEAT_OFFENDER_PRIOR_EVENTS = 5;

    private static final int SENSITIVE_PATH_BONUS = 15;
    private static final int REPEAT_OFFENDER_BONUS = 15;
    private static final int MAX_SCORE = 100;

    private EventEnricher() {
    }

    /**
     * Sets all server-side fields on {@code event} in place: {@code receivedAt}, {@code attackType} and
     * {@code threatScore}, replacing any earlier values. Called by the queue consumer, never at ingest.
     *
     * @param receivedAt          the server receive time to stamp; the caller supplies it, so this class
     *                            stays free of clocks
     * @param priorEventsInWindow events from the same clientIp in the 10 minutes before this one,
     *                            not counting this event
     */
    public static void enrich(SecurityEvent event, Instant receivedAt, long priorEventsInWindow) {
        event.setReceivedAt(receivedAt);
        event.setAttackType(attackType(event.rule().category()));
        event.setThreatScore(threatScore(event.rule().severity(), event.action(), event.path(), priorEventsInWindow));
    }

    /**
     * Returns the attack type label for {@code category}.
     *
     * @throws NullPointerException if {@code category} is null
     */
    public static String attackType(Category category) {
        return switch (category) {
            case INJECTION -> "SQL/Command Injection";
            case XSS -> "Cross-Site Scripting";
            case PROTOCOL_VIOLATION -> "Protocol Anomaly";
            case DATA_LEAKAGE -> "Data Exfiltration";
            case BOT -> "Bot Activity";
            case DOS -> "Denial of Service";
            case RATE_LIMIT -> "Rate Limiting";
        };
    }

    /**
     * Returns the threat score: severity weight + action weight + sensitive-path bonus + repeat-offender
     * bonus, capped at 100 as the brief says. The real range is 10-90, so the cap is only a guard.
     *
     * @param priorEventsInWindow events from the same clientIp in the 10 minutes before this one,
     *                            not counting this event
     * @throws NullPointerException if {@code severity} or {@code action} is null
     */
    public static int threatScore(Severity severity, Action action, String path, long priorEventsInWindow) {
        int score = severityWeight(severity) + actionWeight(action);
        if (isSensitivePath(path)) {
            score += SENSITIVE_PATH_BONUS;
        }
        if (isRepeatOffender(priorEventsInWindow)) {
            score += REPEAT_OFFENDER_BONUS;
        }
        return Math.min(score, MAX_SCORE);
    }

    private static int severityWeight(Severity severity) {
        return switch (severity) {
            case CRITICAL -> 40;
            case HIGH -> 30;
            case MEDIUM -> 20;
            case LOW -> 10;
        };
    }

    private static int actionWeight(Action action) {
        return switch (action) {
            case DENY -> 20;
            case ALERT -> 10;
            case MONITOR -> 0;
        };
    }

    /** Case-sensitive substring match, as the brief says "path contains". A null path never matches. */
    private static boolean isSensitivePath(String path) {
        return path != null && (path.contains("/admin") || path.contains("/login"));
    }

    /** "More than 5 events in the last 10 minutes" counting the current one, i.e. at least 5 prior events. */
    private static boolean isRepeatOffender(long priorEventsInWindow) {
        return priorEventsInWindow >= REPEAT_OFFENDER_PRIOR_EVENTS;
    }
}
