package com.miniwsa.consumer;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.RedisTestConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole Part 2 pipeline with the real wiring: HTTP ingest → in-memory queue → worker → Redis client-IP
 * tracker → enrichment → PostgreSQL. Each test uses its own client IP and eventId prefix and only looks at
 * its own rows.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class EnrichedEventPipelineTest {

    private static final Instant T0 = Instant.parse("2026-05-20T14:50:00Z");
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void sixthEventFromTheSameIpWithinTenMinutesGetsTheBonus() throws Exception {
        String ip = "198.51.100.1";
        ingest(IntStream.range(0, 6).mapToObj(i -> event("six-" + i, ip, T0.plusSeconds(60L * i))).toList(), 6);

        Map<String, Integer> scores = awaitScores("six-", 6);

        assertThat(scores).containsExactlyInAnyOrderEntriesOf(Map.of(
                "six-0", 75, "six-1", 75, "six-2", 75, "six-3", 75, "six-4", 75, "six-5", 90));
        assertThat(jdbc.queryForList("SELECT attack_type FROM events WHERE event_id LIKE 'six-%'", String.class))
                .containsOnly("SQL/Command Injection");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM events WHERE event_id LIKE 'six-%' AND received_at IS NOT NULL", Integer.class))
                .isEqualTo(6);
    }

    @Test
    void fiveEventsGetNoBonus() throws Exception {
        String ip = "198.51.100.2";
        ingest(IntStream.range(0, 5).mapToObj(i -> event("five-" + i, ip, T0.plusSeconds(60L * i))).toList(), 5);

        assertThat(awaitScores("five-", 5).values()).containsOnly(75);
    }

    @Test
    void earlierRequestsFromTheSameIpCount() throws Exception {
        String ip = "198.51.100.3";
        ingest(IntStream.range(0, 5).mapToObj(i -> event("split-" + i, ip, T0.plusSeconds(60L * i))).toList(), 5);
        awaitScores("split-", 5);

        ingest(List.of(event("split-5", ip, T0.plusSeconds(300))), 1);

        assertThat(awaitScores("split-", 6)).containsEntry("split-5", 90);
    }

    @Test
    void eventsMoreThanTenMinutesApartDoNotCount() throws Exception {
        String ip = "198.51.100.4";
        List<String> events = IntStream.range(0, 5)
                .mapToObj(i -> event("gap-" + i, ip, T0.plusSeconds(60L * i)))
                .collect(Collectors.toList());
        events.add(event("gap-5", ip, T0.plus(Duration.ofMinutes(20))));
        ingest(events, 6);

        assertThat(awaitScores("gap-", 6)).containsEntry("gap-5", 75);
    }

    @Test
    void otherIpsDoNotCount() throws Exception {
        List<String> events = IntStream.range(0, 5)
                .mapToObj(i -> event("mix-" + i, "198.51.100.5", T0.plusSeconds(60L * i)))
                .collect(Collectors.toList());
        events.add(event("mix-5", "198.51.100.6", T0.plusSeconds(300)));
        ingest(events, 6);

        assertThat(awaitScores("mix-", 6)).containsEntry("mix-5", 75);
    }

    @Test
    void clientSuppliedScoreIsIgnored() throws Exception {
        String body = event("fake-0", "198.51.100.7", T0)
                .replace("\"action\": \"DENY\"",
                        "\"action\": \"DENY\", \"attackType\": \"x\", \"threatScore\": 99");
        ingest(List.of(body), 1);

        assertThat(awaitScores("fake-", 1)).containsEntry("fake-0", 75);
        assertThat(jdbc.queryForObject("SELECT attack_type FROM events WHERE event_id = 'fake-0'", String.class))
                .isEqualTo("SQL/Command Injection");
    }

    @Test
    void resendingTheSameEventsStoresThemOnceWithTheSameScores() throws Exception {
        String ip = "198.51.100.8";
        List<String> events = IntStream.range(0, 6)
                .mapToObj(i -> event("again-" + i, ip, T0.plusSeconds(60L * i)))
                .toList();
        ingest(events, 6);
        Map<String, Integer> first = awaitScores("again-", 6);

        ingest(events, 6);
        // The resend goes through the queue too; wait until the worker has handled it before checking.
        ingest(List.of(event("again-marker", "198.51.100.9", T0)), 1);
        awaitScores("again-marker", 1);

        assertThat(scores("again-")).hasSize(7).containsAllEntriesOf(first);
    }

    private void ingest(List<String> events, int accepted) throws Exception {
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + String.join(",", events) + "]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(accepted));
    }

    /** Waits until {@code count} rows with this eventId prefix are stored, then returns eventId -> score. */
    private Map<String, Integer> awaitScores(String prefix, int count) {
        await().atMost(WAIT).until(() -> scores(prefix).size() >= count);
        return scores(prefix);
    }

    private Map<String, Integer> scores(String prefix) {
        return jdbc.queryForList("SELECT event_id, threat_score FROM events WHERE event_id LIKE ?", prefix + "%")
                .stream()
                .collect(Collectors.toMap(row -> (String) row.get("event_id"),
                        row -> ((Number) row.get("threat_score")).intValue()));
    }

    /** CRITICAL + DENY + "/api/v1/login" = 75, or 90 with the client-IP bonus. */
    private static String event(String id, String ip, Instant timestamp) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": 14227,
                  "clientIp": "%s",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "statusCode": 403,
                  "rule": {"id": "950001", "name": "SQL_INJECTION", "severity": "CRITICAL", "category": "INJECTION"},
                  "action": "DENY"
                }
                """.formatted(id, timestamp, ip);
    }
}
