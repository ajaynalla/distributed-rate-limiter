package com.ratelimiter.dashboard;

import com.ratelimiter.config.RateLimiterProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Read-only metadata for the existing production endpoint. Lab traffic has separate metrics. */
@RestController
@RequestMapping("/v1/dashboard")
public class DashboardController {
    private final RateLimiterProperties properties;
    private final MeterRegistry registry;
    private final boolean enabled;
    private final boolean redisEnabled;

    public DashboardController(RateLimiterProperties properties, MeterRegistry registry,
            @Value("${dashboard.enabled:false}") boolean enabled,
            @Value("${dashboard.redis-enabled:false}") boolean redisEnabled) {
        this.properties = properties;
        this.registry = registry;
        this.enabled = enabled;
        this.redisEnabled = redisEnabled;
    }

    @GetMapping("/config")
    public Map<String, Object> config() {
        return Map.of("mode", properties.getMode(), "limit", properties.getLimit(),
                "windowMillis", properties.getWindowMillis(),
                "failurePolicy", properties.getRedis().getFailurePolicy(),
                "demoEnabled", enabled, "redisEnabled", enabled && redisEnabled,
                "algorithm", "SLIDING_WINDOW_LOG");
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        var allowed = registry.find("rate_limiter.decisions").tag("outcome", "allowed").counter();
        var denied = registry.find("rate_limiter.decisions").tag("outcome", "denied").counter();
        var degraded = registry.find("rate_limiter.decisions.degraded").counter();
        var timer = registry.find("rate_limiter.decision.duration").timer();
        return Map.of("allowed", allowed == null ? 0 : allowed.count(),
                "rejected", denied == null ? 0 : denied.count(),
                "degraded", degraded == null ? 0 : degraded.count(),
                "meanLatencyMs", timer == null ? 0 : timer.mean(TimeUnit.MILLISECONDS),
                "scope", "production instance since startup; excludes isolated lab");
    }
}
