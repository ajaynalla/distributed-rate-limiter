package com.ratelimiter.redis;

/**
 * Wraps any failure talking to the Redis backend (connection refused,
 * command timeout, unexpected script error, ...). Kept generic and
 * unchecked so callers that want failure-handling policy (fail-open vs
 * fail-closed, retries) can catch this one type rather than a grab-bag
 * of Lettuce/Spring exception classes.
 */
public class RateLimiterBackendException extends RuntimeException {

    public RateLimiterBackendException(String message, Throwable cause) {
        super(message, cause);
    }
}
