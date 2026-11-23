package com.miniwsa.enrichment;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventEnricherTest {

    private static final String PLAIN_PATH = "/api/v1/products";

    @Nested
    class Enrich {

        private final SecurityEvent event = new SecurityEvent("evt-1", Instant.parse("2026-05-20T14:32:10Z"),
                14227L, "pol_web1", "203.0.113.42", "www.example.com", "/api/v1/login", "POST", 403, null,
                new SecurityEvent.Rule("950001", "SQL_INJECTION", null, Severity.CRITICAL, Category.INJECTION),
                Action.DENY, null, null, null, null, null, null);

        private final Instant now = Instant.parse("2026-05-20T14:32:11Z");

        @Test
        void setsAllServerFieldsOnTheEventItself() {
            EventEnricher.enrich(event, now, 4);

            assertThat(event.receivedAt()).isEqualTo(now);
            assertThat(event.attackType()).isEqualTo("SQL/Command Injection");
            assertThat(event.threatScore()).isEqualTo(75);
            assertThat(event.eventId()).isEqualTo("evt-1");
        }

        @Test
        void addsTheRepeatOffenderBonusFromThePriorCount() {
            EventEnricher.enrich(event, now, 5);

            assertThat(event.threatScore()).isEqualTo(90);
        }

        @Test
        void overwritesExistingValues() {
            event.setReceivedAt(Instant.parse("2020-01-01T00:00:00Z"));
            event.setAttackType("x");
            event.setThreatScore(99);

            EventEnricher.enrich(event, now, 0);

            assertThat(event.receivedAt()).isEqualTo(now);
            assertThat(event.attackType()).isEqualTo("SQL/Command Injection");
            assertThat(event.threatScore()).isEqualTo(75);
        }
    }

    @Nested
    class AttackType {

        @ParameterizedTest
        @CsvSource(delimiter = '|', textBlock = """
                INJECTION          | SQL/Command Injection
                XSS                | Cross-Site Scripting
                PROTOCOL_VIOLATION | Protocol Anomaly
                DATA_LEAKAGE       | Data Exfiltration
                BOT                | Bot Activity
                DOS                | Denial of Service
                RATE_LIMIT         | Rate Limiting
                """)
        void mapsEachCategoryToTheBriefsLabel(Category category, String expected) {
            assertThat(EventEnricher.attackType(category)).isEqualTo(expected);
        }

        @ParameterizedTest
        @EnumSource(Category.class)
        void everyLabelFitsTheColumn(Category category) {
            assertThat(EventEnricher.attackType(category)).isNotBlank().hasSizeLessThanOrEqualTo(32);
        }

        @Test
        void labelsAreDistinct() {
            assertThat(Arrays.stream(Category.values()).map(EventEnricher::attackType))
                    .doesNotHaveDuplicates()
                    .hasSize(Category.values().length);
        }

        @Test
        void nullCategoryIsRejected() {
            assertThatThrownBy(() -> EventEnricher.attackType(null)).isInstanceOf(NullPointerException.class);
        }
    }

    @Nested
    class ThreatScore {

        @ParameterizedTest
        @CsvSource({
                "CRITICAL, DENY,    60", "CRITICAL, ALERT, 50", "CRITICAL, MONITOR, 40",
                "HIGH,     DENY,    50", "HIGH,     ALERT, 40", "HIGH,     MONITOR, 30",
                "MEDIUM,   DENY,    40", "MEDIUM,   ALERT, 30", "MEDIUM,   MONITOR, 20",
                "LOW,      DENY,    30", "LOW,      ALERT, 20", "LOW,      MONITOR, 10",
        })
        void severityPlusAction(Severity severity, Action action, int expected) {
            assertThat(EventEnricher.threatScore(severity, action, PLAIN_PATH, 0)).isEqualTo(expected);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/admin", "/login", "/api/v1/login", "/administrator", "/foo/admin?x=1",
                "/x?next=/login"})
        void sensitivePathAdds15(String path) {
            assertThat(EventEnricher.threatScore(Severity.LOW, Action.MONITOR, path, 0)).isEqualTo(25);
        }

        @ParameterizedTest
        @ValueSource(strings = {"/Admin", "/LOGIN", "/adm", "admin", "login", "/", ""})
        @NullSource
        void otherPathsAddNothing(String path) {
            assertThat(EventEnricher.threatScore(Severity.LOW, Action.MONITOR, path, 0)).isEqualTo(10);
        }

        @Test
        void bothAdminAndLoginAdd15Once() {
            assertThat(EventEnricher.threatScore(Severity.LOW, Action.MONITOR, "/admin/login", 0)).isEqualTo(25);
        }

        @ParameterizedTest
        @CsvSource({"0, 10", "4, 10", "5, 25", "6, 25", "500, 25"})
        void repeatOffenderBonusStartsAtFivePriorEvents(long prior, int expected) {
            assertThat(EventEnricher.threatScore(Severity.LOW, Action.MONITOR, PLAIN_PATH, prior))
                    .isEqualTo(expected);
        }

        @Test
        void briefExampleWithAndWithoutRepeatBonus() {
            assertThat(EventEnricher.threatScore(Severity.CRITICAL, Action.DENY, "/api/v1/login", 4)).isEqualTo(75);
            assertThat(EventEnricher.threatScore(Severity.CRITICAL, Action.DENY, "/api/v1/login", 5)).isEqualTo(90);
        }

        @Test
        void highestAndLowestPossibleScores() {
            assertThat(EventEnricher.threatScore(Severity.CRITICAL, Action.DENY, "/admin", 5)).isEqualTo(90);
            assertThat(EventEnricher.threatScore(Severity.LOW, Action.MONITOR, PLAIN_PATH, 0)).isEqualTo(10);
        }

        @Test
        void everyCombinationStaysWithin0To100() {
            for (Severity severity : Severity.values()) {
                for (Action action : Action.values()) {
                    for (String path : new String[] {PLAIN_PATH, "/admin"}) {
                        for (long prior : new long[] {0, 5}) {
                            assertThat(EventEnricher.threatScore(severity, action, path, prior)).isBetween(0, 100);
                        }
                    }
                }
            }
        }

        @Test
        void nullSeverityOrActionIsRejected() {
            assertThatThrownBy(() -> EventEnricher.threatScore(null, Action.DENY, PLAIN_PATH, 0))
                    .isInstanceOf(NullPointerException.class);
            assertThatThrownBy(() -> EventEnricher.threatScore(Severity.LOW, null, PLAIN_PATH, 0))
                    .isInstanceOf(NullPointerException.class);
        }
    }
}
