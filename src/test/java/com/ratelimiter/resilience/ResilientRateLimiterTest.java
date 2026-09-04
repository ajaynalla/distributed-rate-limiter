package com.ratelimiter.resilience;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.redis.RateLimiterBackendException;
import com.ratelimiter.testsupport.ManualClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 5: proves the resilience wrapper's actual failure-handling
 * behavior -- not just that it compiles -- using a scriptable fake
 * delegate rather than real Redis, so these tests are fast and
 * deterministic while still exercising the real retry/circuit-breaker
 * code paths.
 */
class ResilientRateLimiterTest {

    private static final RateLimiterBackendException BACKEND_DOWN =
            new RateLimiterBackendException("simulated Redis outage", new RuntimeException("connection refused"));

    private ResilientRateLimiter withPolicy(RateLimiter delegate, FailurePolicy policy, int maxRetries) {
        CircuitBreaker breaker = new CircuitBreaker(100, 60_000, new ManualClock(0));
        return new ResilientRateLimiter(delegate, policy, breaker, maxRetries, 1, 5, new SimpleMeterRegistry());
    }

    @Test
    void passesThroughASuccessfulDecisionUnchanged() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A")).thenReturn(RateLimitDecision.allow(5));

        ResilientRateLimiter limiter = withPolicy(delegate, FailurePolicy.FAIL_CLOSED, 2);

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(5);
        assertThat(decision.degraded()).isFalse();
    }

    @Test
    void retriesOnBackendFailureAndSucceedsWithinBudget() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A"))
                .thenThrow(BACKEND_DOWN)
                .thenThrow(BACKEND_DOWN)
                .thenReturn(RateLimitDecision.allow(9));

        ResilientRateLimiter limiter = withPolicy(delegate, FailurePolicy.FAIL_CLOSED, 2);

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.degraded()).isFalse();
        verify(delegate, times(3)).decide("client-A");
    }

    @Test
    void failOpenAllowsTheRequestOnceRetriesAreExhausted() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide(any())).thenThrow(BACKEND_DOWN);

        ResilientRateLimiter limiter = withPolicy(delegate, FailurePolicy.FAIL_OPEN, 2);

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).as("fail-open: let the request through").isTrue();
        assertThat(decision.degraded()).as("but flagged as degraded, not a real quota decision").isTrue();
        verify(delegate, times(3)).decide("client-A"); // 1 initial + 2 retries
    }

    @Test
    void failClosedRejectsTheRequestOnceRetriesAreExhausted() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide(any())).thenThrow(BACKEND_DOWN);

        ResilientRateLimiter limiter = withPolicy(delegate, FailurePolicy.FAIL_CLOSED, 2);

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).as("fail-closed: reject the request").isFalse();
        assertThat(decision.degraded()).isTrue();
    }

    @Test
    void neverRetriesMoreThanTheConfiguredBudget() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide(any())).thenThrow(BACKEND_DOWN);

        ResilientRateLimiter limiter = withPolicy(delegate, FailurePolicy.FAIL_OPEN, 0);

        limiter.decide("client-A");

        verify(delegate, times(1)).decide("client-A"); // no retries at all
    }

    @Test
    void openCircuitSkipsCallingTheDelegateEntirely() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide(any())).thenThrow(BACKEND_DOWN);

        ManualClock clock = new ManualClock(0);
        CircuitBreaker breaker = new CircuitBreaker(1, 60_000, clock);
        ResilientRateLimiter limiter = new ResilientRateLimiter(
                delegate, FailurePolicy.FAIL_OPEN, breaker, 0, 1, 5, new SimpleMeterRegistry());

        // First call: delegate fails once, trips the breaker (threshold=1).
        limiter.decide("client-A");
        verify(delegate, times(1)).decide("client-A");

        // Second call: breaker is open, must not touch the delegate at all.
        RateLimitDecision decision = limiter.decide("client-A");
        verify(delegate, times(1)).decide("client-A"); // still just the one call from before
        assertThat(decision.degraded()).isTrue();
        assertThat(decision.allowed()).isTrue(); // still fail-open
    }

    @Test
    void aSuccessAfterFailuresClosesTheBreakerForSubsequentCalls() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A"))
                .thenThrow(BACKEND_DOWN)
                .thenReturn(RateLimitDecision.allow(9))
                .thenThrow(BACKEND_DOWN);

        ManualClock clock = new ManualClock(0);
        CircuitBreaker breaker = new CircuitBreaker(1, 60_000, clock);
        ResilientRateLimiter limiter = new ResilientRateLimiter(
                delegate, FailurePolicy.FAIL_OPEN, breaker, 1, 1, 5, new SimpleMeterRegistry());

        RateLimitDecision first = limiter.decide("client-A"); // fails once, retries, succeeds
        assertThat(first.degraded()).isFalse();
        assertThat(breaker.isOpen()).as("success closed the breaker again").isFalse();

        // Third underlying call fails; with maxRetries=1 and threshold=1 this trips it again.
        RateLimitDecision third = limiter.decide("client-A");
        assertThat(third.degraded()).isTrue();
    }
}
