package com.ratelimiter.api;

/**
 * @param allowed      whether the request may proceed
 * @param remaining    quota remaining in the current window (-1 if
 *                     {@code degraded} and therefore unknown)
 * @param retryAfterMs how long to wait before retrying, when denied
 * @param degraded     true if this was a fail-open/fail-closed fallback
 *                     decision, not a real quota check (see
 *                     {@code com.ratelimiter.resilience.FailurePolicy})
 */
public record RateLimitCheckResponse(boolean allowed, int remaining, long retryAfterMs, boolean degraded) {}
