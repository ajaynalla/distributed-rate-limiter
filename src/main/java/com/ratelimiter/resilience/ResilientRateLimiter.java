package com.ratelimiter.resilience;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.redis.RateLimiterBackendException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Wraps a (Redis-backed) {@link RateLimiter} with the failure-handling
 * behavior a distributed backend needs: bounded retries with exponential
 * backoff, a circuit breaker to stop retrying once the backend is known
 * to be down, and a configurable fail-open/fail-closed policy for when
 * all of that is exhausted.
 *
 * <p>Deliberately a separate decorator rather than folding this into
 * {@code RedisSlidingWindowRateLimiter}: that class's only job is
 * correctness of the atomic algorithm against a reachable Redis. This
 * class's only job is deciding what to do when Redis is <em>not</em>
 * reachable. Composing them keeps each independently testable and
 * independently explainable.
 *
 * <h2>Call sequence</h2>
 * <ol>
 *   <li>If the circuit breaker is open, skip Redis entirely and apply
 *       the failure policy immediately -- this is what actually prevents
 *       a retry storm once an outage is established.</li>
 *   <li>Otherwise, call the delegate. On a backend failure, retry up to
 *       {@code maxRetries} times with exponential backoff + jitter.</li>
 *   <li>A successful call (first try or after retries) closes the
 *       breaker. Exhausting all retries records one breaker failure and
 *       applies the failure policy.</li>
 * </ol>
 */
public class ResilientRateLimiter implements RateLimiter {

    private static final Logger log = LoggerFactory.getLogger(ResilientRateLimiter.class);

    private final RateLimiter delegate;
    private final FailurePolicy failurePolicy;
    private final CircuitBreaker circuitBreaker;
    private final int maxRetries;
    private final long baseBackoffMillis;
    private final long maxBackoffMillis;

    public ResilientRateLimiter(
            RateLimiter delegate,
            FailurePolicy failurePolicy,
            CircuitBreaker circuitBreaker,
            int maxRetries,
            long baseBackoffMillis,
            long maxBackoffMillis) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must be >= 0, got " + maxRetries);
        }
        this.delegate = delegate;
        this.failurePolicy = failurePolicy;
        this.circuitBreaker = circuitBreaker;
        this.maxRetries = maxRetries;
        this.baseBackoffMillis = baseBackoffMillis;
        this.maxBackoffMillis = maxBackoffMillis;
    }

    @Override
    public RateLimitDecision decide(String clientId) {
        if (!circuitBreaker.permitCall()) {
            log.warn(
                    "event=redis_circuit_open action=skip_call client_id={} policy={}",
                    clientId,
                    failurePolicy);
            return applyFailurePolicy();
        }

        RateLimiterBackendException lastFailure = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                RateLimitDecision decision = delegate.decide(clientId);
                circuitBreaker.recordSuccess();
                return decision;
            } catch (RateLimiterBackendException e) {
                lastFailure = e;
                FailureType type = FailureType.classify(e);
                boolean willRetry = attempt < maxRetries;
                log.warn(
                        "event=redis_call_failed attempt={} max_retries={} failure_type={} "
                                + "client_id={} will_retry={} error={}",
                        attempt + 1,
                        maxRetries + 1,
                        type,
                        clientId,
                        willRetry,
                        e.getMessage());

                if (!willRetry) {
                    break;
                }
                sleepWithBackoff(attempt);
            }
        }

        circuitBreaker.recordFailure();
        log.error(
                "event=redis_unavailable action=apply_failure_policy policy={} client_id={}",
                failurePolicy,
                clientId,
                lastFailure);
        return applyFailurePolicy();
    }

    private RateLimitDecision applyFailurePolicy() {
        return failurePolicy == FailurePolicy.FAIL_OPEN
                ? RateLimitDecision.degradedAllow()
                : RateLimitDecision.degradedDeny();
    }

    private void sleepWithBackoff(int attempt) {
        long exponential = baseBackoffMillis * (1L << attempt);
        long capped = Math.min(exponential, maxBackoffMillis);
        long jittered = capped / 2 + ThreadLocalRandom.current().nextLong(capped / 2 + 1);
        try {
            Thread.sleep(jittered);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
