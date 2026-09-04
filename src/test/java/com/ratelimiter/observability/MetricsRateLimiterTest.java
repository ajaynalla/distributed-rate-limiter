package com.ratelimiter.observability;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MetricsRateLimiterTest {

    @Test
    void countsAllowedAndDeniedSeparately() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A")).thenReturn(RateLimitDecision.allow(9), RateLimitDecision.deny(500));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsRateLimiter limiter = new MetricsRateLimiter(delegate, registry);

        limiter.decide("client-A");
        limiter.decide("client-A");

        assertThat(registry.counter("rate_limiter.decisions", "outcome", "allowed").count()).isEqualTo(1.0);
        assertThat(registry.counter("rate_limiter.decisions", "outcome", "denied").count()).isEqualTo(1.0);
    }

    @Test
    void countsDegradedDecisionsSeparately() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A")).thenReturn(RateLimitDecision.allow(9), RateLimitDecision.degradedAllow());
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsRateLimiter limiter = new MetricsRateLimiter(delegate, registry);

        limiter.decide("client-A");
        limiter.decide("client-A");

        assertThat(registry.counter("rate_limiter.decisions.degraded").count()).isEqualTo(1.0);
    }

    @Test
    void recordsDecisionLatency() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A")).thenReturn(RateLimitDecision.allow(9));
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MetricsRateLimiter limiter = new MetricsRateLimiter(delegate, registry);

        limiter.decide("client-A");

        assertThat(registry.timer("rate_limiter.decision.duration").count()).isEqualTo(1);
    }

    @Test
    void passesTheDecisionThroughUnchanged() {
        RateLimiter delegate = mock(RateLimiter.class);
        when(delegate.decide("client-A")).thenReturn(RateLimitDecision.allow(7));
        MetricsRateLimiter limiter = new MetricsRateLimiter(delegate, new SimpleMeterRegistry());

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(7);
    }
}
