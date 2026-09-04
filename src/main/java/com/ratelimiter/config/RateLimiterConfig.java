package com.ratelimiter.config;

import com.ratelimiter.core.InMemorySlidingWindowRateLimiter;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.redis.RedisSlidingWindowRateLimiter;
import com.ratelimiter.resilience.CircuitBreaker;
import com.ratelimiter.resilience.ResilientRateLimiter;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Wires the single {@link RateLimiter} bean the rest of the app depends
 * on, chosen by {@code rate-limiter.mode}. This is the one place that
 * knows both implementations exist; every other class (controller,
 * health indicator, metrics) depends only on the {@link RateLimiter}
 * interface.
 */
@Configuration
@EnableConfigurationProperties(RateLimiterProperties.class)
public class RateLimiterConfig {

    @Bean
    public RateLimiter rateLimiter(RateLimiterProperties properties) {
        if (properties.getMode() == RateLimiterProperties.Mode.IN_MEMORY) {
            return new InMemorySlidingWindowRateLimiter(properties.getLimit(), properties.getWindowMillis());
        }
        return buildRedisBackedLimiter(properties);
    }

    private RateLimiter buildRedisBackedLimiter(RateLimiterProperties properties) {
        RateLimiterProperties.Redis redisProps = properties.getRedis();

        StringRedisTemplate template = buildRedisTemplate(redisProps);
        RedisSlidingWindowRateLimiter redisLimiter = new RedisSlidingWindowRateLimiter(
                template, properties.getLimit(), properties.getWindowMillis(), redisProps.getKeyPrefix());

        CircuitBreaker breaker = new CircuitBreaker(
                redisProps.getCircuitBreakerFailureThreshold(),
                redisProps.getCircuitBreakerOpenDurationMillis(),
                Clock.systemUTC());

        return new ResilientRateLimiter(
                redisLimiter,
                redisProps.getFailurePolicy(),
                breaker,
                redisProps.getMaxRetries(),
                redisProps.getBaseBackoffMillis(),
                redisProps.getMaxBackoffMillis());
    }

    private StringRedisTemplate buildRedisTemplate(RateLimiterProperties.Redis redisProps) {
        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration(redisProps.getHost(), redisProps.getPort());

        ClientOptions clientOptions = ClientOptions.builder()
                .socketOptions(SocketOptions.builder()
                        .connectTimeout(Duration.ofMillis(redisProps.getConnectTimeoutMillis()))
                        .build())
                .build();

        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .clientOptions(clientOptions)
                .commandTimeout(Duration.ofMillis(redisProps.getCommandTimeoutMillis()))
                .build();

        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();

        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return template;
    }
}
