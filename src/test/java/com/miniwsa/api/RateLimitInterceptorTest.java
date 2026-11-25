package com.miniwsa.api;

import com.miniwsa.samples.SamplesService;
import com.miniwsa.stats.StatsService;
import com.miniwsa.ingest.IngestService;
import com.miniwsa.ratelimit.RedisRateLimiter;
import com.miniwsa.storage.EventStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rate-limit interceptor as wired by {@code RateLimitWebConfig}: the mocked {@link RedisRateLimiter} bean is
 * what makes the config register it, so the other controller slice tests (no limiter bean) run without it.
 */
@WebMvcTest({StatsController.class, SamplesController.class, IngestController.class})
@Import({StatsService.class, SamplesService.class})
class RateLimitInterceptorTest {

    private static final String CLIENT = "198.51.100.7";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private RedisRateLimiter rateLimiter;

    @MockitoBean
    private EventStore eventStore;

    @MockitoBean
    private IngestService ingestService;

    @Test
    void allowedRequestGoesThrough() throws Exception {
        when(rateLimiter.tryAcquire(CLIENT)).thenReturn(true);

        mvc.perform(summary().with(from(CLIENT)))
                .andExpect(status().isOk());

        verify(rateLimiter).tryAcquire(CLIENT);
        verify(eventStore).summarize(null, Instant.parse("2026-05-20T14:00:00Z"),
                Instant.parse("2026-05-20T15:00:00Z"));
    }

    @Test
    void deniedSummaryIs429() throws Exception {
        expectDenied(summary());
    }

    @Test
    void deniedTimeseriesIs429() throws Exception {
        expectDenied(get("/v1/stats/timeseries").param("from", "2026-05-20T14:00:00Z")
                .param("to", "2026-05-20T15:00:00Z").param("interval", "5m"));
    }

    @Test
    void deniedSamplesIs429() throws Exception {
        expectDenied(get("/v1/events/samples").param("configId", "14227").param("limit", "10"));
    }

    @Test
    void invalidParametersStillCount() throws Exception {
        when(rateLimiter.tryAcquire(CLIENT)).thenReturn(true);

        mvc.perform(get("/v1/events/samples").param("limit", "101").with(from(CLIENT)))
                .andExpect(status().isBadRequest());

        verify(rateLimiter).tryAcquire(CLIENT);
        verifyNoInteractions(eventStore);
    }

    @Test
    void deniedBeatsInvalidParameters() throws Exception {
        when(rateLimiter.tryAcquire(CLIENT)).thenReturn(false);

        mvc.perform(get("/v1/events/samples").param("limit", "101").with(from(CLIENT)))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void ingestIsNotRateLimited() throws Exception {
        when(ingestService.ingest(anyList())).thenReturn(0);

        mvc.perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[]")
                .with(from(CLIENT)));

        verifyNoInteractions(rateLimiter);
    }

    @Test
    void xForwardedForIsIgnored() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

        mvc.perform(summary().header("X-Forwarded-For", "203.0.113.99").with(from(CLIENT)))
                .andExpect(status().isOk());

        verify(rateLimiter).tryAcquire(CLIENT);
    }

    @Test
    void blankRemoteAddressIsCountedAsUnknown() throws Exception {
        when(rateLimiter.tryAcquire(anyString())).thenReturn(true);

        mvc.perform(summary().with(from(" ")))
                .andExpect(status().isOk());

        verify(rateLimiter).tryAcquire("unknown");
    }

    // #################### Helpers ####################

    private void expectDenied(MockHttpServletRequestBuilder request) throws Exception {
        when(rateLimiter.tryAcquire(CLIENT)).thenReturn(false);

        mvc.perform(request.with(from(CLIENT)))
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Rate limit exceeded; try again later"));

        verify(rateLimiter).tryAcquire(CLIENT);
        verifyNoInteractions(eventStore);
    }

    private static MockHttpServletRequestBuilder summary() {
        return get("/v1/stats/summary").param("from", "2026-05-20T14:00:00Z").param("to", "2026-05-20T15:00:00Z");
    }

    private static RequestPostProcessor from(String remoteAddr) {
        return req -> {
            req.setRemoteAddr(remoteAddr);
            return req;
        };
    }
}
