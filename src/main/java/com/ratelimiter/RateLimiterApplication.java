package com.ratelimiter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisReactiveAutoConfiguration;

/**
 * Excludes Spring Boot's default Redis auto-configuration: it would
 * otherwise build its own {@code RedisConnectionFactory} from generic
 * {@code spring.data.redis.*} properties (default localhost:6379) and
 * register a "redis" health indicator against *that* connection --
 * entirely separate from, and unaware of, the connection this app
 * actually builds from {@code rate-limiter.redis.*} in
 * {@link com.ratelimiter.config.RateLimiterConfig}. Two independent
 * Redis connections with two independent health signals is confusing at
 * best and misleading at worst (the generic indicator could report UP
 * while the one this service actually depends on is down, or vice
 * versa). {@link com.ratelimiter.observability.RateLimiterHealthIndicator}
 * reports on the connection that matters.
 */
@SpringBootApplication(exclude = {RedisAutoConfiguration.class, RedisReactiveAutoConfiguration.class})
public class RateLimiterApplication {

    public static void main(String[] args) {
        SpringApplication.run(RateLimiterApplication.class, args);
    }
}
