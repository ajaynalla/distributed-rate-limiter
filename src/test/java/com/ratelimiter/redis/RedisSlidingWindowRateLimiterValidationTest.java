package com.ratelimiter.redis;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Input validation is verified with a mocked template (no real Redis
 * needed); correctness of the atomic algorithm itself is proven against
 * a real Redis instance in
 * {@code integration.RedisSlidingWindowRateLimiterIntegrationTest}.
 */
class RedisSlidingWindowRateLimiterValidationTest {

    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    @Test
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new RedisSlidingWindowRateLimiter(template, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisSlidingWindowRateLimiter(template, -5, 1000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNonPositiveWindow() {
        assertThatThrownBy(() -> new RedisSlidingWindowRateLimiter(template, 10, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RedisSlidingWindowRateLimiter(template, 10, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullOrBlankClientId() {
        RedisSlidingWindowRateLimiter limiter = new RedisSlidingWindowRateLimiter(template, 10, 1000);

        assertThatThrownBy(() -> limiter.decide(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.decide("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.decide("   ")).isInstanceOf(IllegalArgumentException.class);
    }
}
