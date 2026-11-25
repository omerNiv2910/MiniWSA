package com.miniwsa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The whole flow through the public API only, with the real wiring (Spring context, queue worker, Redis,
 * PostgreSQL): {@code POST /v1/events/ingest} → enrichment by the worker → {@code GET /v1/stats/summary}
 * → {@code GET /v1/events/samples}.
 *
 * <p>The database and Redis are shared with the other {@code @SpringBootTest}s, so this test uses its own
 * configId, time window and attacker IPs (203.0.113.150-152), and sends its requests from its own client IP
 * (192.0.2.201) so the rate limiter's read budget is not shared with the other e2e classes.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class PipelineEndToEndTest {

    private static final String CONFIG = "990500";
    private static final Instant T0 = Instant.parse("2026-05-23T10:00:00Z");
    private static final String FROM = "2026-05-23T10:00:00Z";
    private static final String TO = "2026-05-23T11:00:00Z";
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final String REQUEST_IP = "192.0.2.201";

    private static final String INJECTOR = "203.0.113.150";
    private static final String XSS_CLIENT = "203.0.113.151";
    private static final String BOT_CLIENT = "203.0.113.152";

    @Autowired
    private MockMvc mvc;

    @Test
    void ingestedEventsAreEnrichedCountedAndSampled() throws Exception {
        // 6 injections from one IP a minute apart: CRITICAL(40) + DENY(20) + /login(15) = 75,
        // and the 6th has 5 earlier ones within 10 minutes, so +15 = 90.
        List<String> events = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            events.add(event("pipe-inj-" + i, INJECTOR, T0.plusSeconds(60L * i),
                    "/api/v1/login", "CRITICAL", "INJECTION", "DENY"));
        }
        // HIGH(30) + ALERT(10) = 40.
        events.add(event("pipe-xss", XSS_CLIENT, T0.plusSeconds(600), "/search", "HIGH", "XSS", "ALERT"));
        // MEDIUM(20) + MONITOR(0) + /admin(15) = 35.
        events.add(event("pipe-bot", BOT_CLIENT, T0.plusSeconds(900), "/admin/panel", "MEDIUM", "BOT", "MONITOR"));

        // 1. Ingest: accepted straight away, processed later by the worker.
        perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + String.join(",", events) + "]"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accepted").value(8));

        // 2. Wait until the worker has enriched and stored all of them (stats read PostgreSQL directly).
        await().atMost(WAIT).untilAsserted(() -> summary().andExpect(jsonPath("$.totalEvents").value(8)));

        // 3. Stats: the counts and average scores come from the server-side enrichment.
        summary()
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "configId": 990500,
                          "timeRange": {"from": "2026-05-23T10:00:00Z", "to": "2026-05-23T11:00:00Z"},
                          "totalEvents": 8,
                          "byCategory": {
                            "INJECTION": {"count": 6, "avgThreatScore": 77.5},
                            "XSS": {"count": 1, "avgThreatScore": 40.0},
                            "BOT": {"count": 1, "avgThreatScore": 35.0}
                          },
                          "byAction": {"DENY": 6, "ALERT": 1, "MONITOR": 1}
                        }
                        """, JsonCompareMode.LENIENT))
                .andExpect(jsonPath("$.topAttackers", hasSize(3)))
                .andExpect(jsonPath("$.topAttackers[0].clientIp").value(INJECTOR))
                .andExpect(jsonPath("$.topAttackers[0].count").value(6))
                .andExpect(jsonPath("$.topAttackers[0].avgThreatScore").value(77.5))
                .andExpect(jsonPath("$.topTargetedPaths", hasSize(3)))
                .andExpect(jsonPath("$.topTargetedPaths[0].path").value("/api/v1/login"))
                .andExpect(jsonPath("$.topTargetedPaths[0].count").value(6));

        // 4. Samples: the same events come back newest first, with the enrichment fields set.
        perform(get("/v1/events/samples").param("configId", CONFIG).param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(8))
                .andExpect(jsonPath("$.events[*].eventId").value(contains(
                        "pipe-bot", "pipe-xss", "pipe-inj-5", "pipe-inj-4", "pipe-inj-3",
                        "pipe-inj-2", "pipe-inj-1", "pipe-inj-0")))
                .andExpect(jsonPath("$.events[*].threatScore").value(contains(35, 40, 90, 75, 75, 75, 75, 75)))
                .andExpect(jsonPath("$.events[*].attackType").value(contains(
                        "Bot Activity", "Cross-Site Scripting", "SQL/Command Injection", "SQL/Command Injection",
                        "SQL/Command Injection", "SQL/Command Injection", "SQL/Command Injection",
                        "SQL/Command Injection")))
                .andExpect(jsonPath("$.events[*].receivedAt").value(everyItem(notNullValue())));

        // 5. Samples filters line up with the stats breakdown.
        perform(get("/v1/events/samples").param("configId", CONFIG).param("from", FROM).param("to", TO)
                        .param("category", "INJECTION").param("action", "DENY").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(6))
                .andExpect(jsonPath("$.events[*].eventId").value(contains("pipe-inj-5", "pipe-inj-4")));
    }

    private ResultActions summary() throws Exception {
        return perform(get("/v1/stats/summary").param("configId", CONFIG).param("from", FROM).param("to", TO));
    }

    /** Performs the request from this class's own client IP, so it has its own rate-limit budget. */
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(req -> {
            req.setRemoteAddr(REQUEST_IP);
            return req;
        }));
    }

    private static String event(String id, String clientIp, Instant timestamp, String path,
                                String severity, String category, String action) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": %s,
                  "clientIp": "%s",
                  "hostname": "www.example.com",
                  "path": "%s",
                  "method": "POST",
                  "statusCode": 403,
                  "rule": {"id": "950001", "name": "RULE", "severity": "%s", "category": "%s"},
                  "action": "%s"
                }
                """.formatted(id, timestamp, CONFIG, clientIp, path, severity, category, action);
    }
}
