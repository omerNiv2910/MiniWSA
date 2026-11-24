package com.miniwsa.ratelimit;

import com.miniwsa.PostgresTestConfiguration;
import com.miniwsa.RedisTestConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The read-API rate limit with the real wiring: Spring context, {@code RateLimitWebConfig} registering the
 * interceptor on the real paths, {@link RedisRateLimiter} running its Lua script against a real Redis, and
 * the exception handler turning a rejection into a 429 problem. Requests go through MockMvc, so there is no
 * Tomcat; the client IP is set on each request instead.
 *
 * <p>The limit is 3 so a test spends a budget in a few requests instead of 101, and the window is 1h so a
 * test cannot straddle a window boundary and get a fresh budget half way. These properties give the class
 * its own Spring context (and so its own containers).
 *
 * <p>Every test uses a random client IP from the IPv6 documentation range, so tests never share a budget,
 * even when re-run within the hour against the same Redis.
 */
@SpringBootTest(properties = {"miniwsa.rate-limit.limit=3", "miniwsa.rate-limit.window=1h"})
@AutoConfigureMockMvc
@Import({PostgresTestConfiguration.class, RedisTestConfiguration.class})
class RateLimitEndToEndTest {

    private static final String FROM = "2026-05-23T10:00:00Z";
    private static final String TO = "2026-05-23T11:00:00Z";
    /** Window (1h) plus RedisRateLimiter.TTL_MARGIN (5s), in millis. */
    private static final long MAX_TTL_MILLIS = 3_605_000L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redis;

    private String clientIp;

    @BeforeEach
    void useOwnClientIp() {
        clientIp = randomClientIp();
    }

    @Test
    void sharedBudgetAcrossTheThreeEndpoints() throws Exception {
        perform(summary(), clientIp).andExpect(status().isOk());
        perform(timeseries(), clientIp).andExpect(status().isOk());
        perform(samples(), clientIp).andExpect(status().isOk());

        perform(summary(), clientIp)
                .andExpect(status().isTooManyRequests())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.title").value("Too Many Requests"))
                .andExpect(jsonPath("$.detail").value("Rate limit exceeded; try again later"));
    }

    @Test
    void eachIpHasItsOwnBudget() throws Exception {
        exhaustBudget(clientIp);

        perform(summary(), randomClientIp()).andExpect(status().isOk());
    }

    @Test
    void ingestAndActuatorAreNotLimited() throws Exception {
        exhaustBudget(clientIp);

        perform(post("/v1/events/ingest").contentType(MediaType.APPLICATION_JSON).content("[" + ingestBody() + "]"),
                clientIp)
                .andExpect(status().isCreated());
        perform(get("/actuator/health"), clientIp).andExpect(status().isOk());
    }

    @Test
    void counterIsStoredInRedisWithAnExpiry() throws Exception {
        perform(summary(), clientIp).andExpect(status().isOk());

        Set<String> keys = redis.keys(RedisRateLimiter.KEY_PREFIX + clientIp + ":*");
        assertThat(keys).hasSize(1);
        String key = keys.iterator().next();
        assertThat(redis.opsForValue().get(key)).isEqualTo("1");
        assertThat(redis.getExpire(key, TimeUnit.MILLISECONDS)).isPositive().isLessThanOrEqualTo(MAX_TTL_MILLIS);
    }

    // #################### Helpers ####################

    /** Spends the whole budget of 3 and checks that the next request is rejected. */
    private void exhaustBudget(String ip) throws Exception {
        for (int i = 0; i < 3; i++) {
            perform(summary(), ip).andExpect(status().isOk());
        }
        perform(summary(), ip).andExpect(status().isTooManyRequests());
    }

    private ResultActions perform(MockHttpServletRequestBuilder request, String ip) throws Exception {
        return mvc.perform(request.with(req -> {
            req.setRemoteAddr(ip);
            return req;
        }));
    }

    /** An address in 2001:db8::/32 (documentation range) that no other test or earlier run uses. */
    private static String randomClientIp() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "2001:db8::%x:%x".formatted(random.nextInt(0x10000), random.nextInt(0x10000));
    }

    private static MockHttpServletRequestBuilder summary() {
        return get("/v1/stats/summary").param("from", FROM).param("to", TO);
    }

    private static MockHttpServletRequestBuilder timeseries() {
        return get("/v1/stats/timeseries").param("from", FROM).param("to", TO).param("interval", "5m");
    }

    private static MockHttpServletRequestBuilder samples() {
        return get("/v1/events/samples").param("configId", "990301");
    }

    private static String ingestBody() {
        return """
                {
                  "eventId": "rl-1",
                  "timestamp": "2026-05-23T10:00:10Z",
                  "configId": 990301,
                  "clientIp": "198.51.100.202",
                  "hostname": "www.example.com",
                  "path": "/api/v1/login",
                  "method": "POST",
                  "statusCode": 403,
                  "rule": {"id": "950001", "name": "SQL_INJECTION", "severity": "CRITICAL", "category": "INJECTION"},
                  "action": "DENY"
                }
                """;
    }
}
