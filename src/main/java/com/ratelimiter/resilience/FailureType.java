package com.ratelimiter.resilience;

import io.lettuce.core.RedisCommandTimeoutException;
import io.lettuce.core.RedisConnectionException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;

/**
 * Coarse classification of a Redis backend failure, used purely for
 * observability (metric labels, log fields) -- retry behavior itself
 * does not currently branch on this (see {@link ResilientRateLimiter}),
 * but knowing *why* Redis calls are failing is operationally important:
 * a spike in {@link #TIMEOUT} points at Redis being overloaded or a
 * network path getting slow, while {@link #CONNECTION} points at Redis
 * being fully down or unreachable -- different pages, different runbooks.
 */
public enum FailureType {
    TIMEOUT,
    CONNECTION,
    UNKNOWN;

    public static FailureType classify(Throwable t) {
        Throwable current = t;
        while (current != null) {
            if (current instanceof RedisCommandTimeoutException || current instanceof QueryTimeoutException) {
                return TIMEOUT;
            }
            if (current instanceof RedisConnectionException || current instanceof RedisConnectionFailureException) {
                return CONNECTION;
            }
            current = current.getCause();
        }
        return UNKNOWN;
    }
}
