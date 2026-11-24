package com.miniwsa.api;

import com.miniwsa.ratelimit.RedisRateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Objects;

/**
 * Counts each read request against its client IP's budget and rejects it with a 429 once the budget is
 * spent. Registered on the read endpoints only by {@link com.miniwsa.config.RateLimitWebConfig}; it is not
 * a bean, so it exists only when a {@link RedisRateLimiter} does.
 *
 * <p>An interceptor rather than code in the controllers because it runs before argument binding and
 * validation: a request that ends up as a 400 still costs the client one request, so sending garbage
 * is not a way around the limit.
 *
 * <p>The client IP is {@link HttpServletRequest#getRemoteAddr()}. With
 * {@code server.forward-headers-strategy: native}, Tomcat already replaces it with the
 * {@code X-Forwarded-For} value when, and only when, the peer is a trusted proxy. Reading the header here
 * would let any client pick its own IP and so its own budget.
 *
 * <p>Fail-open: when the limiter is unavailable (Redis down or slow) the request goes through, so a Redis
 * outage does not take the read API down with it.
 */
public class RateLimitInterceptor implements HandlerInterceptor {

    /** Key used when the container gives no peer address; all such requests share one budget. */
    static final String UNKNOWN_CLIENT = "unknown";

    private final RedisRateLimiter limiter;

    public RateLimitInterceptor(RedisRateLimiter limiter) {
        this.limiter = Objects.requireNonNull(limiter, "limiter");
    }

    /**
     * Called by Spring, not by code: {@code DispatcherServlet.doDispatch} runs it for every request whose
     * path matches the patterns in {@link com.miniwsa.config.RateLimitWebConfig}, after Tomcat has set the
     * client IP and the controller method has been found, but before parameters are bound/validated and
     * before the controller runs. Returning {@code true} lets the controller run; throwing
     * {@link RateLimitExceededException} skips it and {@link ApiExceptionHandler} answers 429.
     * possible return values:
     * true - carries on. binds the parameters and calls the controller.
     * false - stop at once and call nothing else.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String ip = request.getRemoteAddr();
        if (ip == null || ip.isBlank()) {
            ip = UNKNOWN_CLIENT;
        }
        if (!limiter.tryAcquire(ip)) {
            throw new RateLimitExceededException();
        }
        return true;
    }
}
