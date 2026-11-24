package com.miniwsa.samples;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.RedisTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.storage.EventStore;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/events/samples} with the real wiring: Spring context, exception handler, EventStore and
 * PostgreSQL. The database is shared with the other {@code @SpringBootTest}s, so every test uses its own
 * configId and only queries that.
 *
 * <p>Redis, and so the rate limiter, is shared too, and MockMvc requests all come from 127.0.0.1 by
 * default; with the Awaitility polling the suite could spend the 100-per-window read budget and get a 429.
 * So every request goes through {@link #perform} with a client IP of its own per test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class SamplesEndToEndTest {

    private static final String URL = "/v1/events/samples";
    private static final Instant T0 = Instant.parse("2026-05-21T10:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EventStore eventStore;

    /** Hands each test its own client IP (192.0.2.1-99 here; other e2e classes use other ranges). */
    private static final AtomicInteger NEXT_CLIENT = new AtomicInteger(1);

    private String clientIp;

    @BeforeEach
    void useOwnClientIp() {
        clientIp = "192.0.2." + NEXT_CLIENT.getAndIncrement();
    }

    /** Performs the request from this test's client IP, so it has its own rate-limit budget. */
    private ResultActions perform(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request.with(req -> {
            req.setRemoteAddr(clientIp);
            return req;
        }));
    }

    @Test
    void ingestedEventsComeBackEnrichedNewestFirst() throws Exception {
        String config = "990001";
        perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("""
                        [%s, %s]
                        """.formatted(ingestBody("e2e-old", config, T0), ingestBody("e2e-new", config, T0.plusSeconds(60)))))
                .andExpect(status().isCreated());

        // Ingest is asynchronous: wait until the worker has stored both events.
        await().atMost(WAIT).untilAsserted(() -> perform(get(URL).param("configId", config))
                .andExpect(jsonPath("$.total").value(2)));

        perform(get(URL).param("configId", config))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.events[*].eventId").value(contains("e2e-new", "e2e-old")))
                .andExpect(jsonPath("$.events[0].timestamp").value("2026-05-21T10:01:00Z"))
                .andExpect(jsonPath("$.events[0].attackType").value("SQL/Command Injection"))
                .andExpect(jsonPath("$.events[0].threatScore").value(75))
                .andExpect(jsonPath("$.events[0].receivedAt").isNotEmpty())
                .andExpect(jsonPath("$.events[0].rule.category").value("INJECTION"));
    }

    @Test
    void filtersAndPagesTogether() throws Exception {
        long config = 990002L;
        eventStore.saveBatch(List.of(
                stored("f-1", config, T0.plusSeconds(10), Category.XSS, Action.DENY),
                stored("f-2", config, T0.plusSeconds(20), Category.XSS, Action.DENY),
                stored("f-3", config, T0.plusSeconds(30), Category.XSS, Action.DENY),
                stored("f-4", config, T0.plusSeconds(40), Category.XSS, Action.DENY),
                stored("f-5", config, T0.plusSeconds(40), Category.XSS, Action.DENY),  // same time as f-4
                stored("f-alert", config, T0.plusSeconds(25), Category.XSS, Action.ALERT),
                stored("f-bot", config, T0.plusSeconds(25), Category.BOT, Action.DENY),
                stored("f-late", config, T0.plusSeconds(3600), Category.XSS, Action.DENY),
                stored("f-other", 990099L, T0.plusSeconds(25), Category.XSS, Action.DENY)));

        // [T0+10s, T0+1h): f-1 is exactly at "from" (included), f-late exactly at "to" (excluded).
        String from = "2026-05-21T10:00:10Z";
        String to = "2026-05-21T11:00:00Z";

        perform(get(URL).param("configId", Long.toString(config)).param("from", from).param("to", to)
                        .param("category", "XSS").param("action", "DENY").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 5}
                        """, JsonCompareMode.LENIENT))
                .andExpect(jsonPath("$.events[*].eventId").value(contains("f-4", "f-5")));

        perform(get(URL).param("configId", Long.toString(config)).param("from", from).param("to", to)
                        .param("category", "XSS").param("action", "DENY").param("limit", "2").param("offset", "2"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.events[*].eventId").value(contains("f-3", "f-2")));

        perform(get(URL).param("configId", Long.toString(config)).param("from", from).param("to", to)
                        .param("category", "XSS").param("action", "DENY").param("limit", "2").param("offset", "4"))
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.events[*].eventId").value(contains("f-1")));
    }

    @Test
    void unknownConfigIsAnEmptyPage() throws Exception {
        perform(get(URL).param("configId", "990777"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 0, "events": []}
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void invalidParametersAreReportedByTheRealHandler() throws Exception {
        perform(get(URL).param("configId", "0").param("limit", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid query parameters"))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("configId", "limit")));

        perform(get(URL).param("category", "xss"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("category"));
    }

    private static String ingestBody(String id, String configId, Instant timestamp) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": %s,
                  "clientIp": "198.51.100.200",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "statusCode": 403,
                  "rule": {"id": "950001", "name": "SQL_INJECTION", "severity": "CRITICAL", "category": "INJECTION"},
                  "action": "DENY"
                }
                """.formatted(id, timestamp, configId);
    }

    private static SecurityEvent stored(String id, long configId, Instant timestamp, Category category, Action action) {
        return new SecurityEvent(id, timestamp, configId, null, "203.0.113.42", "www.example.com", "/", "GET", 403,
                null, new SecurityEvent.Rule("1", "R", null, Severity.HIGH, category), action, null, null, null,
                timestamp, null, null);
    }
}
