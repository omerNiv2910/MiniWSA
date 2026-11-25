package com.miniwsa.api;

import com.miniwsa.model.Action;
import com.miniwsa.model.Category;
import com.miniwsa.model.SecurityEvent;
import com.miniwsa.model.Severity;
import com.miniwsa.samples.SampleQuery;
import com.miniwsa.samples.SampleResponse;
import com.miniwsa.samples.SamplesService;
import com.miniwsa.storage.EventStore;
import com.miniwsa.storage.ReadQueryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SamplesController.class)
@Import(SamplesService.class)
class SamplesControllerTest {

    private static final String URL = "/v1/events/samples";
    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T15:00:00Z");
    private static final String ENUM_CATEGORIES =
            "must be one of [INJECTION, XSS, PROTOCOL_VIOLATION, DATA_LEAKAGE, BOT, DOS, RATE_LIMIT]";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventStore eventStore;

    // ---- binding ----

    @Test
    void withoutParametersUsesTheDefaultsAndNoFilters() throws Exception {
        stubEmpty();

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {"total": 0, "events": []}
                        """, JsonCompareMode.STRICT));

        verify(eventStore).findSamples(new SampleQuery(null, null, null, null, null, 20, 0));
    }

    @Test
    void everyParameterReachesTheQuery() throws Exception {
        stubEmpty();

        mvc.perform(get(URL).param("configId", "14227")
                        .param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z")
                        .param("category", "XSS").param("action", "DENY")
                        .param("limit", "5").param("offset", "10"))
                .andExpect(status().isOk());

        verify(eventStore).findSamples(new SampleQuery(14227L, FROM, TO, Category.XSS, Action.DENY, 5, 10));
    }

    @Test
    void offsetsAndFractionsAreConvertedToUtc() throws Exception {
        stubEmpty();

        // %2B is a "+" in a query string. A URI (not a template string) so MockMvc does not encode it again.
        mvc.perform(get(URI.create(URL + "?from=2026-05-20T16:00:00%2B02:00&to=2026-05-20T15:00:00.5Z")))
                .andExpect(status().isOk());

        verify(eventStore).findSamples(
                new SampleQuery(null, FROM, Instant.parse("2026-05-20T15:00:00.500Z"), null, null, 20, 0));
    }

    @Test
    void emptyValuesCountAsAbsent() throws Exception {
        stubEmpty();

        mvc.perform(get(URL).param("configId", "").param("from", "").param("to", "")
                        .param("category", "").param("action", "").param("limit", "").param("offset", ""))
                .andExpect(status().isOk());

        verify(eventStore).findSamples(new SampleQuery(null, null, null, null, null, 20, 0));
    }

    @Test
    void aRepeatedParameterTakesItsFirstValue() throws Exception {
        stubEmpty();

        mvc.perform(get(URL).param("limit", "5", "6").param("category", "XSS", "BOT"))
                .andExpect(status().isOk());

        verify(eventStore).findSamples(new SampleQuery(null, null, null, Category.XSS, null, 5, 0));
    }

    @Test
    void limitAndOffsetBoundariesAreAccepted() throws Exception {
        for (String[] ok : new String[][] {{"1", "0"}, {"100", "10001"}, {"20", "2147483647"}}) {
            reset(eventStore);
            stubEmpty();
            mvc.perform(get(URL).param("limit", ok[0]).param("offset", ok[1])).andExpect(status().isOk());
        }
    }

    @Test
    void unknownParametersAreIgnored() throws Exception {
        stubEmpty();

        mvc.perform(get(URL).param("foo", "bar")).andExpect(status().isOk());
    }

    // ---- response ----

    @Test
    void writesTheTotalAndTheEvents() throws Exception {
        SecurityEvent event = new SecurityEvent("evt-1", FROM, 14227L, "pol_web1", "203.0.113.42",
                "www.example.com", "/api/v1/login", "POST", 403, "curl/8.0",
                new SecurityEvent.Rule("950001", "SQLi", "SQL injection", Severity.HIGH, Category.INJECTION),
                Action.DENY, new SecurityEvent.GeoLocation("US", "Ashburn"), 512L, 128L,
                Instant.parse("2026-05-20T14:00:01Z"), "SQL_INJECTION", 87);
        when(eventStore.findSamples(any())).thenReturn(new SampleResponse(25, List.of(event)));

        mvc.perform(get(URL).param("limit", "1").param("offset", "3"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "total": 25,
                          "events": [{
                            "eventId": "evt-1", "timestamp": "2026-05-20T14:00:00Z", "configId": 14227,
                            "policyId": "pol_web1", "clientIp": "203.0.113.42", "hostname": "www.example.com",
                            "path": "/api/v1/login", "method": "POST", "statusCode": 403, "userAgent": "curl/8.0",
                            "rule": {"id": "950001", "name": "SQLi", "message": "SQL injection",
                                     "severity": "HIGH", "category": "INJECTION"},
                            "action": "DENY", "geoLocation": {"country": "US", "city": "Ashburn"},
                            "requestSize": 512, "responseSize": 128, "receivedAt": "2026-05-20T14:00:01Z",
                            "attackType": "SQL_INJECTION", "threatScore": 87
                          }]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void unenrichedEventsLeaveOutAttackTypeAndThreatScore() throws Exception {
        SecurityEvent legacy = new SecurityEvent("legacy", FROM, 14227L, null, "198.51.100.7", "old.example.com",
                "/", "GET", 200, null, new SecurityEvent.Rule("1", "R", null, Severity.LOW, Category.BOT),
                Action.ALERT, null, null, null, FROM, null, null);
        when(eventStore.findSamples(any())).thenReturn(new SampleResponse(1, List.of(legacy)));

        mvc.perform(get(URL))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].geoLocation").isEmpty())
                .andExpect(jsonPath("$.events[0].attackType").doesNotExist())
                .andExpect(jsonPath("$.events[0].threatScore").doesNotExist());
    }

    @Test
    void pastTheEndIsAnEmptyPageWithTheTotal() throws Exception {
        when(eventStore.findSamples(any())).thenReturn(new SampleResponse(2, List.of()));

        mvc.perform(get(URL).param("offset", "50"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"total": 2, "events": []}
                        """, JsonCompareMode.STRICT));
    }

    // ---- type errors (Spring stops at the first) ----

    @Test
    void badNumbersAreRejected() throws Exception {
        for (String bad : new String[] {"abc", "1.5", "99999999999"}) {
            expectOneError(get(URL).param("limit", bad), "limit", "must be an integer");
            expectOneError(get(URL).param("offset", bad), "offset", "must be an integer");
        }
        for (String bad : new String[] {"abc", "1.5", "99999999999999999999"}) {
            expectOneError(get(URL).param("configId", bad), "configId", "must be a positive integer");
        }
    }

    @Test
    void badEnumsAreRejectedWithTheAllowedValues() throws Exception {
        for (String bad : new String[] {"SQLI", "xss", "'; DROP TABLE events;--"}) {
            expectOneError(get(URL).param("category", bad), "category", ENUM_CATEGORIES);
        }
        expectOneError(get(URL).param("action", "deny"), "action", "must be one of [DENY, ALERT, MONITOR]");
    }

    @Test
    void badTimestampsAreRejected() throws Exception {
        for (String bad : new String[] {"2026-05-20", "2026-05-20T14:00:00", "yesterday",
                "Wed, 20 May 2026 14:00:00 GMT", "1716214330"}) {
            expectOneError(get(URL).param("from", bad), "from", ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE);
            expectOneError(get(URL).param("to", bad), "to", ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE);
        }
    }

    // ---- range errors (reported together) ----

    @Test
    void outOfRangeValuesAreRejected() throws Exception {
        expectOneError(get(URL).param("limit", "0"), "limit", "must be greater than or equal to 1");
        expectOneError(get(URL).param("limit", "101"), "limit", "must be less than or equal to 100");
        expectOneError(get(URL).param("offset", "-1"), "offset", "must be greater than or equal to 0");
        expectOneError(get(URL).param("configId", "0"), "configId", "must be a positive integer");
        expectOneError(get(URL).param("configId", "-5"), "configId", "must be a positive integer");
    }

    @Test
    void outOfRangeValuesAreReportedTogether() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("configId", "0").param("limit", "101").param("offset", "-1")))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("configId", "limit", "offset")));
    }

    @Test
    void fromMustBeBeforeTo() throws Exception {
        for (String from : new String[] {"2026-05-20T15:00:00Z", "2026-05-20T16:00:00Z"}) {
            expectOneError(get(URL).param("from", from).param("to", "2026-05-20T15:00:00Z"),
                    "from", "must be before to");
        }
    }

    // ---- other ----

    @Test
    void aTimedOutQueryIsServiceUnavailable() throws Exception {
        when(eventStore.findSamples(any()))
                .thenThrow(new ReadQueryTimeoutException("cancelled", new RuntimeException()));

        mvc.perform(get(URL))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Query took too long; narrow the filters or the time range"));
    }

    @Test
    void postIsNotAllowed() throws Exception {
        mvc.perform(post(URL))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON));
    }

    // ---- helpers ----

    private void stubEmpty() {
        when(eventStore.findSamples(any())).thenReturn(new SampleResponse(0, List.of()));
    }

    private void expectOneError(org.springframework.test.web.servlet.RequestBuilder request, String field,
                                String message) throws Exception {
        expectBadRequest(mvc.perform(request))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(message))
                .andExpect(jsonPath("$.errors[0].index").doesNotExist());
    }

    private ResultActions expectBadRequest(ResultActions result) throws Exception {
        verifyNoInteractions(eventStore);
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid query parameters"));
    }
}
