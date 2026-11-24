package com.miniwsa.config;

import com.miniwsa.api.RateLimitInterceptor;
import com.miniwsa.ratelimit.RedisRateLimiter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Puts the {@link RateLimitInterceptor} in front of the read endpoints. Ingest is not limited: it is
 * written to by WAF edges, not by people browsing the API, and is guarded by the queue's capacity instead.
 */
@Configuration
public class RateLimitWebConfig implements WebMvcConfigurer {

    private final ObjectProvider<RedisRateLimiter> limiter;

    public RateLimitWebConfig(ObjectProvider<RedisRateLimiter> limiter) {
        this.limiter = limiter;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        RedisRateLimiter l = limiter.getIfAvailable();
        if (l == null) {
            // No limiter bean: either miniwsa.rate-limit.enabled=false, or a @WebMvcTest slice, which loads
            // WebMvcConfigurers but not @Components such as RedisRateLimiter. Either way, nothing to count.
            return;
        }
        registry.addInterceptor(new RateLimitInterceptor(l))
                .addPathPatterns("/v1/stats/**", "/v1/events/samples");
    }
}
