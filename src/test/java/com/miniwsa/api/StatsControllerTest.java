package com.miniwsa.api;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.stats.StatsSummary;
import com.miniwsa.stats.StatsSummary.AttackerStats;
import com.miniwsa.stats.StatsSummary.CategoryStats;
import com.miniwsa.stats.StatsSummary.PathStats;
import com.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
class StatsControllerTest {

    private static final String URL = "/v1/stats/summary";
    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T15:00:00Z");

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventStore eventStore;

    @Test
    void returnsTheSummaryForOneConfig() throws Exception {
        Map<Category, CategoryStats> byCategory = new LinkedHashMap<>();
        byCategory.put(Category.INJECTION, new CategoryStats(450, new BigDecimal("72.3")));
        byCategory.put(Category.BOT, new CategoryStats(380, new BigDecimal("45.0")));
        Map<Action, Long> byAction = new LinkedHashMap<>();
        byAction.put(Action.DENY, 600L);
        byAction.put(Action.ALERT, 230L);
        List<AttackerStats> topAttackers = List.of(
                new AttackerStats("203.0.113.42", 87, new BigDecimal("81.2")),
                new AttackerStats("198.51.100.7", 12, null));
        List<PathStats> topPaths = List.of(new PathStats("/api/v1/login", 234));
        when(eventStore.summarize(14227L, FROM, TO))
                .thenReturn(new StatsSummary(14227L, FROM, TO, 830, byCategory, byAction, topAttackers, topPaths));

        mvc.perform(get(URL).param("configId", "14227")
                        .param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "configId": 14227,
                          "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T15:00:00Z" },
                          "totalEvents": 830,
                          "byCategory": {
                            "INJECTION": { "count": 450, "avgThreatScore": 72.3 },
                            "BOT": { "count": 380, "avgThreatScore": 45.0 }
                          },
                          "byAction": { "DENY": 600, "ALERT": 230 },
                          "topAttackers": [
                            { "clientIp": "203.0.113.42", "count": 87, "avgThreatScore": 81.2 },
                            { "clientIp": "198.51.100.7", "count": 12, "avgThreatScore": null }
                          ],
                          "topTargetedPaths": [
                            { "path": "/api/v1/login", "count": 234 }
                          ]
                        }
                        """, JsonCompareMode.STRICT))
                // jsonPath cannot tell 45 from 45.0, so check the raw text too.
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"avgThreatScore\":45.0")));
    }

    @Test
    void withoutConfigIdSummarizesAllConfigsAndLeavesConfigIdOut() throws Exception {
        when(eventStore.summarize(null, FROM, TO))
                .thenReturn(new StatsSummary(null, FROM, TO, 0, Map.of(), Map.of(), List.of(), List.of()));

        mvc.perform(get(URL).param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T15:00:00Z" },
                          "totalEvents": 0,
                          "byCategory": {},
                          "byAction": {},
                          "topAttackers": [],
                          "topTargetedPaths": []
                        }
                        """, JsonCompareMode.STRICT));
        verify(eventStore).summarize(isNull(), eq(FROM), eq(TO));
    }

    @Test
    void offsetsAndFractionsAreConvertedToUtc() throws Exception {
        when(eventStore.summarize(any(), any(), any()))
                .thenReturn(new StatsSummary(null, FROM, TO, 0, Map.of(), Map.of(), List.of(), List.of()));

        // %2B is a "+" in a query string. A URI (not a template string) so MockMvc does not encode it again.
        mvc.perform(get(URI.create("/v1/stats/summary?from=2026-05-20T16:00:00%2B02:00&to=2026-05-20T15:00:00.5Z")))
                .andExpect(status().isOk());

        verify(eventStore).summarize(null, FROM, Instant.parse("2026-05-20T15:00:00.500Z"));
    }

    @Test
    void missingFromIsRequired() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("to", "2026-05-20T15:00:00Z")))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void missingToIsRequired() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("configId", "14227").param("from", "2026-05-20T14:00:00Z")))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("to"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void missingBothReportsTheFirstOne() throws Exception {
        // Spring stops at the first missing required parameter.
        expectBadRequest(mvc.perform(get(URL)))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void blankFromIsRequired() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("from", " ").param("to", "2026-05-20T15:00:00Z")))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"));
    }

    @Test
    void badTimestampFormatsAreRejected() throws Exception {
        for (String bad : new String[] {"2026-05-20", "2026-05-20T14:00:00", "yesterday",
                "Wed, 20 May 2026 14:00:00 GMT", "1716214330"}) {
            expectBadRequest(mvc.perform(get(URL).param("from", bad).param("to", "2026-05-20T15:00:00Z")))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("from"))
                    .andExpect(jsonPath("$.errors[0].message").value(ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE));
        }
    }

    @Test
    void fromMustBeBeforeTo() throws Exception {
        for (String from : new String[] {"2026-05-20T15:00:00Z", "2026-05-20T16:00:00Z"}) {
            expectBadRequest(mvc.perform(get(URL).param("from", from).param("to", "2026-05-20T15:00:00Z")))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("from"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be before to"));
        }
    }

    @Test
    void badConfigIdsAreRejected() throws Exception {
        for (String bad : new String[] {"", "abc", "0", "-5", "1.5", "9223372036854775808", "99999999999999999999"}) {
            expectBadRequest(mvc.perform(get(URL).param("configId", bad)
                            .param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z")))
                    .andExpect(jsonPath("$.errors", hasSize(1)))
                    .andExpect(jsonPath("$.errors[0].field").value("configId"))
                    .andExpect(jsonPath("$.errors[0].message").value("must be a positive integer"));
        }
    }

    @Test
    void allProblemsAreReportedTogether() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("configId", "x").param("from", "bad").param("to", "")))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("configId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a positive integer"))
                .andExpect(jsonPath("$.errors[1].field").value("from"))
                .andExpect(jsonPath("$.errors[1].message").value(ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE))
                .andExpect(jsonPath("$.errors[2].field").value("to"))
                .andExpect(jsonPath("$.errors[2].message").value("is required"));
    }

    @Test
    void badConfigIdAndReversedWindowAreReportedTogether() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("configId", "0")
                        .param("from", "2026-05-20T16:00:00Z").param("to", "2026-05-20T15:00:00Z")))
                .andExpect(jsonPath("$.errors", hasSize(2)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("configId", "from")));
    }

    @Test
    void unknownParametersAreIgnored() throws Exception {
        when(eventStore.summarize(any(), any(), any()))
                .thenReturn(new StatsSummary(null, FROM, TO, 0, Map.of(), Map.of(), List.of(), List.of()));

        mvc.perform(get(URL).param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z")
                        .param("foo", "bar"))
                .andExpect(status().isOk());
    }

    @Test
    void postIsNotAllowed() throws Exception {
        mvc.perform(post(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    private ResultActions expectBadRequest(ResultActions result) throws Exception {
        verifyNoInteractions(eventStore);
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid query parameters"));
    }
}
