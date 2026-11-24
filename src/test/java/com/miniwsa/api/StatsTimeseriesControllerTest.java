package com.miniwsa.api;

import com.miniwsa.stats.Timeseries;
import com.miniwsa.stats.Timeseries.Bucket;
import com.miniwsa.stats.TimeseriesInterval;
import com.miniwsa.storage.EventStore;
import com.miniwsa.storage.ReadQueryTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StatsController.class)
class StatsTimeseriesControllerTest {

    private static final String URL = "/v1/stats/timeseries";
    private static final Instant FROM = Instant.parse("2026-05-20T14:00:00Z");
    private static final Instant TO = Instant.parse("2026-05-20T14:15:00Z");
    private static final String FROM_TEXT = "2026-05-20T14:00:00Z";
    private static final String TO_TEXT = "2026-05-20T14:15:00Z";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private EventStore eventStore;

    // ---- success ----

    @Test
    void returnsTheTimeseriesForOneConfig() throws Exception {
        when(eventStore.timeseries(14227L, FROM, TO, TimeseriesInterval.FIVE_MINUTES))
                .thenReturn(new Timeseries(14227L, FROM, TO, TimeseriesInterval.FIVE_MINUTES, List.of(
                        new Bucket(Instant.parse("2026-05-20T14:00:00Z"), 12),
                        new Bucket(Instant.parse("2026-05-20T14:05:00Z"), 0),
                        new Bucket(Instant.parse("2026-05-20T14:10:00Z"), 30))));

        mvc.perform(get(URL).param("configId", "14227").param("from", FROM_TEXT).param("to", TO_TEXT)
                        .param("interval", "5m"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(content().json("""
                        {
                          "configId": 14227,
                          "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T14:15:00Z" },
                          "interval": "5m",
                          "totalEvents": 42,
                          "buckets": [
                            { "start": "2026-05-20T14:00:00Z", "count": 12 },
                            { "start": "2026-05-20T14:05:00Z", "count": 0 },
                            { "start": "2026-05-20T14:10:00Z", "count": 30 }
                          ]
                        }
                        """, JsonCompareMode.STRICT));
    }

    @Test
    void withoutConfigIdCoversAllConfigsAndLeavesConfigIdOut() throws Exception {
        when(eventStore.timeseries(null, FROM, TO, TimeseriesInterval.ONE_HOUR))
                .thenReturn(new Timeseries(null, FROM, TO, TimeseriesInterval.ONE_HOUR, List.of(new Bucket(FROM, 3))));

        mvc.perform(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", "1h"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {
                          "timeRange": { "from": "2026-05-20T14:00:00Z", "to": "2026-05-20T14:15:00Z" },
                          "interval": "1h",
                          "totalEvents": 3,
                          "buckets": [ { "start": "2026-05-20T14:00:00Z", "count": 3 } ]
                        }
                        """, JsonCompareMode.STRICT));
        verify(eventStore).timeseries(isNull(), eq(FROM), eq(TO), eq(TimeseriesInterval.ONE_HOUR));
    }

    @Test
    void offsetsAreConvertedToUtc() throws Exception {
        stubEmpty();

        // %2B is a "+" in a query string. A URI (not a template string) so MockMvc does not encode it again.
        mvc.perform(get(URI.create(URL + "?from=2026-05-20T16:00:00%2B02:00&to=2026-05-20T14:15:00.5Z&interval=1m")))
                .andExpect(status().isOk());

        verify(eventStore).timeseries(null, FROM, Instant.parse("2026-05-20T14:15:00.500Z"),
                TimeseriesInterval.ONE_MINUTE);
    }

    @Test
    void unknownParametersAreIgnored() throws Exception {
        stubEmpty();

        mvc.perform(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", "1m")
                        .param("foo", "bar"))
                .andExpect(status().isOk());
    }

    // ---- required parameters ----

    @Test
    void eachMissingParameterIsRequired() throws Exception {
        expectOneError(get(URL).param("to", TO_TEXT).param("interval", "1m"), "from", "is required");
        expectOneError(get(URL).param("from", FROM_TEXT).param("interval", "1m"), "to", "is required");
        expectOneError(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT), "interval", "is required");
    }

    @Test
    void severalMissingReportTheFirstOne() throws Exception {
        // Spring stops at the first missing required parameter, as on the summary.
        expectOneError(get(URL), "from", "is required");
        expectOneError(get(URL).param("from", FROM_TEXT), "to", "is required");
        expectOneError(get(URL).param("configId", "1").param("interval", "1m"), "from", "is required");
    }

    @Test
    void emptyTimestampsAreRequiredAndAnEmptyIntervalIsNotALabel() throws Exception {
        expectOneError(get(URL).param("from", "").param("to", TO_TEXT).param("interval", "1m"), "from", "is required");
        expectOneError(get(URL).param("from", FROM_TEXT).param("to", "").param("interval", "1m"), "to", "is required");
        expectOneError(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", ""),
                "interval", TimeseriesInterval.ALLOWED_VALUES_MESSAGE);
        expectOneError(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", " "),
                "interval", TimeseriesInterval.ALLOWED_VALUES_MESSAGE);
    }

    @Test
    void allEmptyValuesAreReportedTogether() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("from", "").param("to", "").param("interval", "")))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("from"))
                .andExpect(jsonPath("$.errors[0].message").value("is required"))
                .andExpect(jsonPath("$.errors[1].field").value("to"))
                .andExpect(jsonPath("$.errors[1].message").value("is required"))
                .andExpect(jsonPath("$.errors[2].field").value("interval"))
                .andExpect(jsonPath("$.errors[2].message").value(TimeseriesInterval.ALLOWED_VALUES_MESSAGE));
    }

    // ---- single bad values ----

    @Test
    void badConfigIdsAreRejected() throws Exception {
        for (String bad : new String[] {"", "abc", "0", "-5", "1.5", "9223372036854775808"}) {
            expectOneError(valid().param("configId", bad), "configId", "must be a positive integer");
        }
    }

    @Test
    void badTimestampFormatsAreRejected() throws Exception {
        for (String bad : new String[] {"2026-05-20", "2026-05-20T14:00:00", "yesterday",
                "Wed, 20 May 2026 14:00:00 GMT", "1716214330"}) {
            expectOneError(get(URL).param("from", bad).param("to", TO_TEXT).param("interval", "1m"),
                    "from", ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE);
            expectOneError(get(URL).param("from", FROM_TEXT).param("to", bad).param("interval", "1m"),
                    "to", ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE);
        }
    }

    @Test
    void onlyTheExactIntervalLabelsAreAccepted() throws Exception {
        for (String bad : new String[] {"2m", "5M", "1H", " 5m", "5m ", "60", "1d", "abc", "5 minutes"}) {
            expectOneError(get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", bad),
                    "interval", TimeseriesInterval.ALLOWED_VALUES_MESSAGE);
        }
        assertMessageListsTheLabels();
    }

    @Test
    void fromMustBeBeforeTo() throws Exception {
        for (String from : new String[] {TO_TEXT, "2026-05-20T15:00:00Z"}) {
            expectOneError(get(URL).param("from", from).param("to", TO_TEXT).param("interval", "1m"),
                    "from", "must be before to");
        }
    }

    // ---- several problems ----

    @Test
    void allProblemsAreReportedTogetherInParameterOrder() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("interval", "2m").param("to", "")
                        .param("from", "bad").param("configId", "x")))
                .andExpect(jsonPath("$.errors", hasSize(4)))
                .andExpect(jsonPath("$.errors[0].field").value("configId"))
                .andExpect(jsonPath("$.errors[0].message").value("must be a positive integer"))
                .andExpect(jsonPath("$.errors[1].field").value("from"))
                .andExpect(jsonPath("$.errors[1].message").value(ApiExceptionHandler.ISO_TIMESTAMP_MESSAGE))
                .andExpect(jsonPath("$.errors[2].field").value("to"))
                .andExpect(jsonPath("$.errors[2].message").value("is required"))
                .andExpect(jsonPath("$.errors[3].field").value("interval"))
                .andExpect(jsonPath("$.errors[3].message").value(TimeseriesInterval.ALLOWED_VALUES_MESSAGE));
    }

    @Test
    void reversedWindowIsReportedAfterTheIntervalError() throws Exception {
        expectBadRequest(mvc.perform(get(URL).param("configId", "0").param("from", "2026-05-20T16:00:00Z")
                        .param("to", TO_TEXT).param("interval", "2m")))
                .andExpect(jsonPath("$.errors", hasSize(3)))
                .andExpect(jsonPath("$.errors[0].field").value("configId"))
                .andExpect(jsonPath("$.errors[1].field").value("interval"))
                .andExpect(jsonPath("$.errors[2].field").value("from"))
                .andExpect(jsonPath("$.errors[2].message").value("must be before to"));
    }

    @Test
    void bucketCapIsOnlyCheckedWhenEverythingElseIsValid() throws Exception {
        // A year of 1m buckets is far over the cap, but the bad configId is the only error reported.
        expectOneError(get(URL).param("configId", "0").param("from", "2025-05-20T14:00:00Z").param("to", TO_TEXT)
                .param("interval", "1m"), "configId", "must be a positive integer");
        // A reversed window has no bucket count: only "must be before to".
        expectOneError(get(URL).param("from", "2027-05-20T14:00:00Z").param("to", TO_TEXT).param("interval", "1m"),
                "from", "must be before to");
    }

    // ---- bucket cap ----

    @Test
    void exactlyMaxBucketsIsAcceptedForEveryInterval() throws Exception {
        for (TimeseriesInterval interval : TimeseriesInterval.values()) {
            stubEmpty();
            Instant to = FROM.plus(interval.duration().multipliedBy(Timeseries.MAX_BUCKETS));

            mvc.perform(get(URL).param("from", FROM_TEXT).param("to", to.toString())
                            .param("interval", interval.label()))
                    .andExpect(status().isOk());

            verify(eventStore).timeseries(null, FROM, to, interval);
            clearInvocations(eventStore);
        }
    }

    @Test
    void maxBucketsPlusOneIsRejectedForEveryInterval() throws Exception {
        for (TimeseriesInterval interval : TimeseriesInterval.values()) {
            Instant to = FROM.plus(interval.duration().multipliedBy(Timeseries.MAX_BUCKETS + 1));
            expectOneError(get(URL).param("from", FROM_TEXT).param("to", to.toString())
                    .param("interval", interval.label()), "interval", StatsController.TOO_MANY_BUCKETS_MESSAGE);
        }
    }

    @Test
    void anUnalignedFromCanPushMaxBucketsOverTheCap() throws Exception {
        // to - from is exactly MAX_BUCKETS minutes, but the first bucket starts at the floor of from (14:00),
        // so the window touches MAX_BUCKETS + 1 one-minute buckets.
        Instant from = FROM.plusSeconds(30);
        Instant to = from.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS));
        expectOneError(get(URL).param("from", from.toString()).param("to", to.toString()).param("interval", "1m"),
                "interval", StatsController.TOO_MANY_BUCKETS_MESSAGE);

        // Ending on the bucket boundary instead keeps it at MAX_BUCKETS.
        stubEmpty();
        Instant alignedTo = FROM.plus(Duration.ofMinutes(Timeseries.MAX_BUCKETS));
        mvc.perform(get(URL).param("from", from.toString()).param("to", alignedTo.toString()).param("interval", "1m"))
                .andExpect(status().isOk());
        verify(eventStore).timeseries(null, from, alignedTo, TimeseriesInterval.ONE_MINUTE);
    }

    @Test
    void theCapMessageIsTheDocumentedOne() {
        assertThat(StatsController.TOO_MANY_BUCKETS_MESSAGE)
                .isEqualTo("too many buckets for this range: at most 2880; use a larger interval or a shorter range");
    }

    // ---- other ----

    @Test
    void aTimedOutQueryIsServiceUnavailableWithoutRetryAfter() throws Exception {
        when(eventStore.timeseries(any(), any(), any(), any()))
                .thenThrow(new ReadQueryTimeoutException("cancelled", new RuntimeException()));

        mvc.perform(valid())
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Query took too long; narrow the filters or the time range"));
    }

    // ---- helpers ----

    private MockHttpServletRequestBuilder valid() {
        return get(URL).param("from", FROM_TEXT).param("to", TO_TEXT).param("interval", "5m");
    }

    private void stubEmpty() {
        when(eventStore.timeseries(any(), any(), any(), any()))
                .thenReturn(new Timeseries(null, FROM, TO, TimeseriesInterval.ONE_MINUTE, List.of()));
    }

    private static void assertMessageListsTheLabels() {
        assertThat(TimeseriesInterval.ALLOWED_VALUES_MESSAGE)
                .isEqualTo("must be one of [1m, 5m, 1h]");
    }

    private void expectOneError(MockHttpServletRequestBuilder request, String field, String message) throws Exception {
        expectBadRequest(mvc.perform(request))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value(field))
                .andExpect(jsonPath("$.errors[0].message").value(message));
    }

    private ResultActions expectBadRequest(ResultActions result) throws Exception {
        verifyNoInteractions(eventStore);
        return result.andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value("Invalid query parameters"));
    }
}
