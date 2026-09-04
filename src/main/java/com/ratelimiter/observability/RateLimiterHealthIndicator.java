package com.ratelimiter.observability;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.resilience.ResilientRateLimiter;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Always reports UP for the rate limiter itself: whether Redis is
 * reachable or not, the service keeps answering /v1/rate-limit/check
 * (that is the entire point of the fail-open/fail-closed design in
 * Phase 5). What operators actually need is visibility into *whether*
 * it is currently degraded, surfaced as a detail rather than as a
 * liveness/readiness failure -- see docs/ARCHITECTURE.md for why this
 * indicator deliberately does not gate readiness.
 */
@Component
public class RateLimiterHealthIndicator implements HealthIndicator {

    private final RateLimiter rateLimiter;
    private final RateLimiterProperties properties;

    public RateLimiterHealthIndicator(RateLimiter rateLimiter, RateLimiterProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up().withDetail("mode", properties.getMode());

        RateLimiter unwrapped = rateLimiter instanceof MetricsRateLimiter metrics ? metrics.getDelegate() : rateLimiter;

        if (unwrapped instanceof ResilientRateLimiter resilient) {
            builder.withDetail("redisCircuitOpen", resilient.isCircuitOpen())
                    .withDetail("failurePolicy", resilient.getFailurePolicy());
        }

        return builder.build();
    }
}
