package com.ratelimiter.core;

/**
 * A per-client rate limiter.
 *
 * <p>{@link #decide(String)} is the atomic primitive: it evaluates and
 * records a request in one step, returning why the decision was made
 * (remaining quota / retry hint). {@link #tryAcquire(String)} is the
 * boolean convenience view of the same decision.
 */
public interface RateLimiter {

    /**
     * Evaluate and record a request for {@code clientId}, atomically.
     *
     * @throws IllegalArgumentException if clientId is null/blank
     */
    RateLimitDecision decide(String clientId);

    /**
     * @return true if the request is allowed under the client's current
     *     quota, false otherwise. Equivalent to {@code decide(clientId).allowed()}.
     */
    default boolean tryAcquire(String clientId) {
        return decide(clientId).allowed();
    }
}
