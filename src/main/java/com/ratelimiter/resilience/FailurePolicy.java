package com.ratelimiter.resilience;

/**
 * What to do when the Redis backend cannot be reached after retries are
 * exhausted (or the circuit breaker is open).
 *
 * <p><b>Tradeoff:</b>
 * <ul>
 *   <li>{@link #FAIL_OPEN} - allow the request through. Availability wins:
 *       a Redis outage does not take down the protected service, but the
 *       rate limit is not enforced at all while Redis is down (a client
 *       could burst unbounded). Appropriate when the limiter protects
 *       against abuse/cost overrun but the protected service itself must
 *       stay up (e.g. a public API where "occasionally under-throttled
 *       during an incident" is better than "fully down").</li>
 *   <li>{@link #FAIL_CLOSED} - reject the request. Correctness/protection
 *       wins: the limit is never silently bypassed, but a Redis outage
 *       becomes a full outage of everything the limiter guards.
 *       Appropriate when the limiter is the only thing standing between
 *       traffic and an expensive or fragile downstream resource, and
 *       letting that resource get overwhelmed is worse than rejecting
 *       requests.</li>
 * </ul>
 *
 * <p>There is no universally-correct choice; it is a per-deployment
 * decision exposed as configuration ({@code rate-limiter.redis.failure-policy})
 * rather than hardcoded.
 */
public enum FailurePolicy {
    FAIL_OPEN,
    FAIL_CLOSED
}
