package com.ratelimiter.observability;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.concurrent.TimeUnit;

/**
 * Wraps any {@link RateLimiter} with request-volume and latency metrics.
 * The outermost layer in the decorator chain (core/Redis limiter -&gt;
 * {@code ResilientRateLimiter} -&gt; this), so it measures what a caller
 * actually experiences: total time including any Redis round trip,
 * retries, and backoff.
 *
 * <p><b>Why a latency histogram and not a hand-rolled "lock wait time"
 * metric for the in-memory limiter?</b> The Phase 3 benchmark showed
 * contention on a hot client shows up as tail latency (p99/max), not
 * aggregate throughput -- median latency stayed flat while max latency
 * grew by 3-4 orders of magnitude under load. A percentile histogram on
 * total decision latency captures exactly that signal without invasively
 * instrumenting the lock itself (which would add overhead to the one
 * code path that most needs to stay cheap).
 */
public class MetricsRateLimiter implements RateLimiter {

    private final RateLimiter delegate;
    private final Timer decisionTimer;
    private final Counter allowedCounter;
    private final Counter deniedCounter;
    private final Counter degradedCounter;

    public MetricsRateLimiter(RateLimiter delegate, MeterRegistry registry) {
        this.delegate = delegate;
        this.decisionTimer = Timer.builder("rate_limiter.decision.duration")
                .description("Time to evaluate one rate-limit decision, including any Redis round trip")
                .publishPercentileHistogram()
                .register(registry);
        this.allowedCounter = Counter.builder("rate_limiter.decisions")
                .description("Rate-limit decisions by outcome")
                .tag("outcome", "allowed")
                .register(registry);
        this.deniedCounter = Counter.builder("rate_limiter.decisions")
                .description("Rate-limit decisions by outcome")
                .tag("outcome", "denied")
                .register(registry);
        this.degradedCounter = Counter.builder("rate_limiter.decisions.degraded")
                .description("Decisions made by fail-open/fail-closed policy instead of real quota state")
                .register(registry);
    }

    /** Exposed so components like the health indicator can inspect the wrapped limiter's own state. */
    public RateLimiter getDelegate() {
        return delegate;
    }

    @Override
    public RateLimitDecision decide(String clientId) {
        long startNanos = System.nanoTime();
        try {
            RateLimitDecision decision = delegate.decide(clientId);
            (decision.allowed() ? allowedCounter : deniedCounter).increment();
            if (decision.degraded()) {
                degradedCounter.increment();
            }
            return decision;
        } finally {
            decisionTimer.record(System.nanoTime() - startNanos, TimeUnit.NANOSECONDS);
        }
    }
}
