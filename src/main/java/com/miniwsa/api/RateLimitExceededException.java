package com.miniwsa.api;

/**
 * Thrown by {@link RateLimitInterceptor} when a client has used up its read budget for the current window.
 * {@link ApiExceptionHandler} turns it into a 429 Too Many Requests.
 */
public class RateLimitExceededException extends RuntimeException {

    public RateLimitExceededException() {
        // No client IP or other request data in the message: it may end up in logs.
        super("Rate limit exceeded");
    }
}
