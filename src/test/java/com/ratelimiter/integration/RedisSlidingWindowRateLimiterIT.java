package com.ratelimiter.integration;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.redis.RedisSlidingWindowRateLimiter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 4/8: proves the Redis-backed limiter is correct against a real
 * Redis server (via Testcontainers), including the property that matters
 * most for a "distributed" rate limiter: two independent limiter
 * instances -- standing in for two application pods with no shared
 * in-process state -- correctly share one quota because they only agree
 * on Redis.
 *
 * <p>Requires a Docker daemon reachable from the test runner. If your
 * environment cannot pull images from Docker Hub, the same script logic
 * is exercised directly against Redis in the project's development notes
 * (see docs/ARCHITECTURE.md); this test is what CI and local development
 * with Docker available should run.
 */
@Testcontainers
class RedisSlidingWindowRateLimiterIT {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    private RedisSlidingWindowRateLimiter newLimiter(int limit, long windowMillis, String keyPrefix) {
        RedisStandaloneConfiguration standaloneConfig =
                new RedisStandaloneConfiguration(redis.getHost(), redis.getMappedPort(6379));
        LettuceClientConfiguration clientConfig =
                LettuceClientConfiguration.builder().commandTimeout(Duration.ofSeconds(2)).build();
        LettuceConnectionFactory factory = new LettuceConnectionFactory(standaloneConfig, clientConfig);
        factory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
        return new RedisSlidingWindowRateLimiter(template, limit, windowMillis, keyPrefix);
    }

    @Test
    void allowsUpToTheLimitThenDenies() {
        RedisSlidingWindowRateLimiter limiter = newLimiter(3, 60_000, "test-basic:");

        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("client-A")).as("request #%d", i + 1).isTrue();
        }

        RateLimitDecision decision = limiter.decide("client-A");
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    void windowExpiresAndAdmitsAgain() throws InterruptedException {
        RedisSlidingWindowRateLimiter limiter = newLimiter(1, 500, "test-expiry:");

        assertThat(limiter.tryAcquire("client-A")).isTrue();
        assertThat(limiter.tryAcquire("client-A")).isFalse();

        Thread.sleep(600);

        assertThat(limiter.tryAcquire("client-A")).isTrue();
    }

    @Test
    void clientsAreIsolated() {
        RedisSlidingWindowRateLimiter limiter = newLimiter(2, 60_000, "test-isolation:");

        assertThat(limiter.tryAcquire("client-A")).isTrue();
        assertThat(limiter.tryAcquire("client-A")).isTrue();
        assertThat(limiter.tryAcquire("client-A")).isFalse();

        assertThat(limiter.tryAcquire("client-B")).as("client-B has its own quota").isTrue();
    }

    @Test
    void unknownClientStartsWithFullQuota() {
        RedisSlidingWindowRateLimiter limiter = newLimiter(5, 60_000, "test-unknown:");

        RateLimitDecision decision = limiter.decide("never-seen-before");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(4);
    }

    @Test
    @Timeout(30)
    void stateIsSharedCorrectlyAcrossMultipleApplicationInstances() throws InterruptedException {
        int limit = 50;
        String keyPrefix = "test-multi-instance:";

        // Two separate limiter instances with their own Redis connection,
        // simulating two separate application processes/pods that share
        // no in-process state -- only Redis.
        RedisSlidingWindowRateLimiter instanceA = newLimiter(limit, 60_000, keyPrefix);
        RedisSlidingWindowRateLimiter instanceB = newLimiter(limit, 60_000, keyPrefix);

        int threadsPerInstance = 20;
        int attemptsPerThread = 10; // 400 total attempts against a shared limit of 50
        AtomicInteger allowed = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threadsPerInstance * 2);

        try {
            List<Future<?>> futures = IntStream.range(0, threadsPerInstance * 2)
                    .mapToObj(t -> pool.submit(() -> {
                        RedisSlidingWindowRateLimiter instance = t % 2 == 0 ? instanceA : instanceB;
                        awaitUninterruptibly(startGate);
                        for (int i = 0; i < attemptsPerThread; i++) {
                            if (instance.tryAcquire("shared-client")) {
                                allowed.incrementAndGet();
                            }
                        }
                    }))
                    .collect(Collectors.toList());

            startGate.countDown();
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }

        assertThat(allowed.get())
                .as("two limiter instances sharing one Redis backend must together honor a single limit")
                .isEqualTo(limit);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }
}
