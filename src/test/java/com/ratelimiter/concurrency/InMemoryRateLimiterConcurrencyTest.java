package com.ratelimiter.concurrency;

import com.ratelimiter.core.InMemorySlidingWindowRateLimiter;
import com.ratelimiter.core.RateLimitDecision;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
 * Phase 2: proves the in-memory limiter is safe under real concurrent
 * load, not just single-threaded logic. The window is set far longer than
 * the test can possibly run (60s) so no timestamp expires mid-test —
 * every allow/deny outcome is attributable purely to the concurrency
 * control, not to timing.
 *
 * <p>Without the per-client lock in {@code ClientWindow}, this test is a
 * reliable reproducer: many threads read "size &lt; limit" as true before
 * any of them appends, and the hot client ends up admitted well past its
 * configured limit. Reproduced separately with a bare unsynchronized
 * {@code ArrayDeque} (same 64 threads / 50 attempts / limit=100 shape):
 * three runs allowed 181, 117, and 161 requests through a limit of 100 —
 * a real, repeatable race, not a hypothetical one. Against the current
 * per-client-locked implementation this test passes every time.
 */
class InMemoryRateLimiterConcurrencyTest {

    @RepeatedTest(5)
    @Timeout(30)
    void hotClientNeverExceedsItsLimitUnderConcurrentLoad() throws InterruptedException {
        int limit = 100;
        long windowMillis = 60_000;
        int threadCount = 64;
        int attemptsPerThread = 50; // 3200 total attempts against a limit of 100

        InMemorySlidingWindowRateLimiter limiter =
                new InMemorySlidingWindowRateLimiter(limit, windowMillis);

        AtomicInteger allowedCount = new AtomicInteger();
        AtomicInteger deniedCount = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        try {
            List<Future<?>> futures = IntStream.range(0, threadCount)
                    .mapToObj(t -> pool.submit(() -> {
                        awaitUninterruptibly(startGate);
                        for (int i = 0; i < attemptsPerThread; i++) {
                            RateLimitDecision decision = limiter.decide("hot-client");
                            if (decision.allowed()) {
                                allowedCount.incrementAndGet();
                            } else {
                                deniedCount.incrementAndGet();
                            }
                        }
                    }))
                    .collect(Collectors.toList());

            startGate.countDown(); // release every thread at once to maximize interleaving
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            pool.shutdownNow();
        }

        int totalAttempts = threadCount * attemptsPerThread;
        assertThat(allowedCount.get())
                .as("a hot client must never be admitted past its configured limit")
                .isEqualTo(limit);
        assertThat(deniedCount.get()).isEqualTo(totalAttempts - limit);
    }

    @Test
    @Timeout(30)
    void concurrentClientsAreFullyIsolatedFromEachOther() throws InterruptedException {
        int limit = 20;
        long windowMillis = 60_000;
        int clientCount = 20;
        int threadsPerClient = 10;
        int attemptsPerThread = 20; // 200 attempts per client against a limit of 20

        InMemorySlidingWindowRateLimiter limiter =
                new InMemorySlidingWindowRateLimiter(limit, windowMillis);

        Map<String, AtomicInteger> allowedPerClient = new ConcurrentHashMap<>();
        List<String> clientIds = IntStream.range(0, clientCount)
                .mapToObj(i -> "client-" + i)
                .collect(Collectors.toList());
        clientIds.forEach(id -> allowedPerClient.put(id, new AtomicInteger()));

        CountDownLatch startGate = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(clientCount * threadsPerClient);

        try {
            List<Future<?>> futures = clientIds.stream()
                    .flatMap(clientId -> IntStream.range(0, threadsPerClient).mapToObj(t -> clientId))
                    .map(clientId -> pool.submit(() -> {
                        awaitUninterruptibly(startGate);
                        for (int i = 0; i < attemptsPerThread; i++) {
                            if (limiter.tryAcquire(clientId)) {
                                allowedPerClient.get(clientId).incrementAndGet();
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

        for (String clientId : clientIds) {
            assertThat(allowedPerClient.get(clientId).get())
                    .as("client %s must be admitted exactly up to its own limit, unaffected by other clients", clientId)
                    .isEqualTo(limit);
        }
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
