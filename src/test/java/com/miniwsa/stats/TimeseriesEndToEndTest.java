package com.miniwsa.stats;

import com.jayway.jsonpath.JsonPath;
import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.RedisTestConfiguration;
import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code GET /v1/stats/timeseries} with the real wiring: ingest endpoint and queue worker, Spring context,
 * exception handler, EventStore and PostgreSQL. The database is shared with the other
 * {@code @SpringBootTest}s, so every test uses its own configId and time window.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class TimeseriesEndToEndTest {

    private static final String URL = "/v1/stats/timeseries";
    private static final String SUMMARY_URL = "/v1/stats/summary";
    private static final Instant T0 = Instant.parse("2026-05-22T10:00:00Z");
    private static final Duration WAIT = Duration.ofSeconds(15);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private EventStore eventStore;

    @Test
    void ingestedEventsAreCountedPerBucket() throws Exception {
        String config = "990201";
        // 10:00:10 and 10:03:00 share the first 5m bucket, 10:12:59 is in the third, and 10:15:00 is
        // exactly at "to" so it is not counted.
        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[%s, %s, %s, %s]".formatted(
                        ingestBody("ts-1", config, T0.plusSeconds(10)),
                        ingestBody("ts-2", config, T0.plusSeconds(180)),
                        ingestBody("ts-3", config, T0.plusSeconds(779)),
                        ingestBody("ts-at-to", config, T0.plusSeconds(900)))))
                .andExpect(status().isCreated());

        // Ingest is asynchronous: wait until the worker has stored all four events.
        await().atMost(WAIT).untilAsserted(() -> mvc.perform(get(SUMMARY_URL).param("configId", config)
                        .param("from", "2026-05-22T10:00:00Z").param("to", "2026-05-22T11:00:00Z"))
                .andExpect(jsonPath("$.totalEvents").value(4)));

        mvc.perform(timeseries(config, "2026-05-22T10:00:00Z", "2026-05-22T10:15:00Z", "5m"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "configId": 990201,
                          "timeRange": {"from": "2026-05-22T10:00:00Z", "to": "2026-05-22T10:15:00Z"},
                          "interval": "5m",
                          "totalEvents": 3,
                          "buckets": [
                            {"start": "2026-05-22T10:00:00Z", "count": 2},
                            {"start": "2026-05-22T10:05:00Z", "count": 0},
                            {"start": "2026-05-22T10:10:00Z", "count": 1}
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void totalEventsMatchesTheSummaryForTheSameWindow() throws Exception {
        long config = 990202L;
        // A window no other test writes to, so "all configs" only sees these events.
        Instant base = Instant.parse("2031-03-04T08:00:00Z");
        eventStore.saveBatch(List.of(
                stored("m-1", config, base.plusSeconds(5)),
                stored("m-2", config, base.plusSeconds(61)),
                stored("m-3", config, base.plusSeconds(3599)),
                stored("m-4", config, base.plusSeconds(7300)),
                stored("m-other", 990203L, base.plusSeconds(120))));

        // Unaligned on purpose, so the first and last buckets are partial.
        String from = "2031-03-04T08:00:30Z";
        String to = "2031-03-04T10:01:00Z";
        for (String interval : new String[] {"1m", "5m", "1h"}) {
            for (String configId : new String[] {Long.toString(config), null}) {
                long summaryTotal = totalEvents(summary(configId, from, to));
                long timeseriesTotal = totalEvents(timeseries(configId, from, to, interval));

                assertThat(timeseriesTotal).as("%s, configId %s", interval, configId).isEqualTo(summaryTotal);
            }
        }
        // m-1 (08:00:05) is before "from" and m-4 (10:01:40) after "to"; m-2 and m-3 are in, plus m-other
        // when all configs are counted.
        assertThat(totalEvents(timeseries(Long.toString(config), from, to, "1h"))).isEqualTo(2);
        assertThat(totalEvents(timeseries(null, from, to, "1h"))).isEqualTo(3);
    }

    @Test
    void unknownConfigIsAllZeroBuckets() throws Exception {
        mvc.perform(timeseries("990777", "2026-05-22T10:00:00Z", "2026-05-22T13:00:00Z", "1h"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalEvents").value(0))
                .andExpect(jsonPath("$.buckets", hasSize(3)))
                .andExpect(jsonPath("$.buckets[*].count", everyItem(is(0))));
    }

    @Test
    void invalidParametersAreReportedByTheRealHandler() throws Exception {
        mvc.perform(timeseries("0", "2026-05-22T10:00:00Z", "2026-05-22T09:00:00Z", "2m"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.detail").value("Invalid query parameters"))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("configId"))
                .andExpect(jsonPath("$.errors[1].field").value("interval"))
                .andExpect(jsonPath("$.errors[2].field").value("from"))
                .andExpect(jsonPath("$.errors[2].message").value("must be before to"));

        mvc.perform(get(URL).param("from", "2026-05-22T10:00:00Z").param("to", "2026-05-22T11:00:00Z"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.errors[0].field").value("interval"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    // #################### Helpers ####################

    private static MockHttpServletRequestBuilder timeseries(String configId, String from, String to, String interval) {
        MockHttpServletRequestBuilder request = get(URL).param("from", from).param("to", to).param("interval", interval);
        return configId == null ? request : request.param("configId", configId);
    }

    private static MockHttpServletRequestBuilder summary(String configId, String from, String to) {
        MockHttpServletRequestBuilder request = get(SUMMARY_URL).param("from", from).param("to", to);
        return configId == null ? request : request.param("configId", configId);
    }

    private long totalEvents(MockHttpServletRequestBuilder request) throws Exception {
        String body = mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return ((Number) JsonPath.read(body, "$.totalEvents")).longValue();
    }

    private static String ingestBody(String id, String configId, Instant timestamp) {
        return """
                {
                  "eventId": "%s",
                  "timestamp": "%s",
                  "configId": %s,
                  "clientIp": "198.51.100.201",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "statusCode": 403,
                  "rule": {"id": "950001", "name": "SQL_INJECTION", "severity": "CRITICAL", "category": "INJECTION"},
                  "action": "DENY"
                }
                """.formatted(id, timestamp, configId);
    }

    private static SecurityEvent stored(String id, long configId, Instant timestamp) {
        return new SecurityEvent(id, timestamp, configId, null, "203.0.113.42", "www.example.com", "/", "GET", 403,
                null, new SecurityEvent.Rule("1", "R", null, Severity.HIGH, Category.XSS), Action.DENY, null, null, null,
                timestamp, null, null);
    }
}
