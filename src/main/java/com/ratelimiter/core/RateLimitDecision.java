package com.ratelimiter.core;

/**
 * The outcome of a single rate-limit check.
 *
 * @param allowed      whether the request may proceed
 * @param remaining    requests left in the current window after this
 *                     decision (0 when denied)
 * @param retryAfterMs how long the caller should wait before the next
 *                     slot is expected to free up (0 when allowed)
 */
public record RateLimitDecision(boolean allowed, int remaining, long retryAfterMs) {

    public static RateLimitDecision allow(int remaining) {
        return new RateLimitDecision(true, remaining, 0);
    }

    public static RateLimitDecision deny(long retryAfterMs) {
        return new RateLimitDecision(false, 0, Math.max(retryAfterMs, 0));
    }
}
