package com.ratelimiter.core;

/**
 * The outcome of a single rate-limit check.
 *
 * @param allowed      whether the request may proceed
 * @param remaining    requests left in the current window after this
 *                     decision (0 when denied, -1 when unknown because
 *                     the decision was {@code degraded})
 * @param retryAfterMs how long the caller should wait before the next
 *                     slot is expected to free up (0 when allowed)
 * @param degraded     true if this decision was not computed from real
 *                      quota state -- e.g. the Redis backend was
 *                      unavailable and a fail-open/fail-closed policy
 *                      decided the outcome instead. Callers (and the API
 *                      layer) can surface this so clients/operators know
 *                      the number is not precise.
 */
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterMs, boolean degraded) {

    public static RateLimitDecision allow(int remaining) {
        return new RateLimitDecision(true, remaining, 0, false);
    }

    public static RateLimitDecision deny(long retryAfterMs) {
        return new RateLimitDecision(false, 0, Math.max(retryAfterMs, 0), false);
    }

    public static RateLimitDecision degradedAllow() {
        return new RateLimitDecision(true, -1, 0, true);
    }

    public static RateLimitDecision degradedDeny() {
        return new RateLimitDecision(false, 0, 0, true);
    }
}
