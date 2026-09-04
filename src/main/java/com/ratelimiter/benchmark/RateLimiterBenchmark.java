package com.ratelimiter.benchmark;

import com.ratelimiter.core.InMemorySlidingWindowRateLimiter;
import com.ratelimiter.core.RateLimiter;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Standalone throughput/latency benchmark for the in-memory sliding-window
 * limiter (Phase 3).
 *
 * <p>Deliberately not a JMH microbenchmark: {@code tryAcquire} does real,
 * non-trivial work per call (lock acquisition + deque mutation), so the
 * JIT dead-code-elimination and loop-hoisting pitfalls JMH exists to guard
 * against are not the dominant risk here. A hand-rolled harness with an
 * explicit warmup phase is simpler to fully explain end-to-end and is
 * sufficient to observe contention effects across thread counts.
 *
 * <p>Two scenarios are measured at each thread count, to isolate
 * contention from raw per-call cost:
 * <ul>
 *   <li><b>single-hot-client</b> - every thread hits the same clientId,
 *       so every call serializes on that one client's lock. This is the
 *       worst case for contention.</li>
 *   <li><b>per-thread-client</b> - every thread uses its own clientId,
 *       so calls never contend on the same lock. This isolates the
 *       per-call cost of the algorithm itself from lock contention.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>mvn -q compile exec:java -Dexec.mainClass=com.ratelimiter.benchmark.RateLimiterBenchmark</pre>
 * or directly:
 * <pre>java -cp target/classes com.ratelimiter.benchmark.RateLimiterBenchmark</pre>
 */
public final class RateLimiterBenchmark {

    private static final int LIMIT = 1_000;
    private static final long WINDOW_MILLIS = 1_000;
    private static final int WARMUP_OPS_PER_THREAD = 5_000;
    private static final int MEASURED_OPS_PER_THREAD = 20_000;

    public static void main(String[] args) throws InterruptedException {
        int[] threadCounts = {1, 10, 50, 100};

        System.out.println(
                "scenario,threads,totalOps,wallTimeMs,throughputOpsPerSec,"
                        + "avgLatencyUs,p50LatencyUs,p95LatencyUs,p99LatencyUs,maxLatencyUs");

        for (int threads : threadCounts) {
            runScenario("single-hot-client", threads, true);
        }
        for (int threads : threadCounts) {
            runScenario("per-thread-client", threads, false);
        }
    }

    private static void runScenario(String scenarioName, int threadCount, boolean sharedClient)
            throws InterruptedException {
        RateLimiter limiter = new InMemorySlidingWindowRateLimiter(LIMIT, WINDOW_MILLIS);

        // Warm up the JIT on the exact code path before measuring.
        runBurst(limiter, threadCount, WARMUP_OPS_PER_THREAD, sharedClient, null);

        long[][] latenciesPerThread = new long[threadCount][];
        for (int i = 0; i < threadCount; i++) {
            latenciesPerThread[i] = new long[MEASURED_OPS_PER_THREAD];
        }

        long wallStartNanos = System.nanoTime();
        runBurst(limiter, threadCount, MEASURED_OPS_PER_THREAD, sharedClient, latenciesPerThread);
        long wallElapsedNanos = System.nanoTime() - wallStartNanos;

        long totalOps = (long) threadCount * MEASURED_OPS_PER_THREAD;
        long[] all = new long[(int) totalOps];
        int idx = 0;
        for (long[] arr : latenciesPerThread) {
            System.arraycopy(arr, 0, all, idx, arr.length);
            idx += arr.length;
        }
        Arrays.sort(all);

        double wallMs = wallElapsedNanos / 1_000_000.0;
        double throughput = totalOps / (wallElapsedNanos / 1_000_000_000.0);
        double avgLatencyUs = average(all) / 1000.0;
        double p50 = percentile(all, 50) / 1000.0;
        double p95 = percentile(all, 95) / 1000.0;
        double p99 = percentile(all, 99) / 1000.0;
        double max = all[all.length - 1] / 1000.0;

        System.out.printf(
                "%s,%d,%d,%.2f,%.2f,%.3f,%.3f,%.3f,%.3f,%.3f%n",
                scenarioName, threadCount, totalOps, wallMs, throughput, avgLatencyUs, p50, p95, p99, max);
    }

    private static void runBurst(
            RateLimiter limiter, int threadCount, int opsPerThread, boolean sharedClient, long[][] latenciesOut)
            throws InterruptedException {
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(threadCount);
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);

        for (int t = 0; t < threadCount; t++) {
            int threadIndex = t;
            String clientId = sharedClient ? "hot-client" : "client-" + threadIndex;
            pool.submit(() -> {
                try {
                    startGate.await();
                    long[] latencies = latenciesOut == null ? null : latenciesOut[threadIndex];
                    for (int i = 0; i < opsPerThread; i++) {
                        long callStart = System.nanoTime();
                        limiter.tryAcquire(clientId);
                        if (latencies != null) {
                            latencies[i] = System.nanoTime() - callStart;
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneGate.countDown();
                }
            });
        }

        startGate.countDown();
        doneGate.await();
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
    }

    private static double average(long[] values) {
        double sum = 0;
        for (long v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static long percentile(long[] sortedValues, int p) {
        int idx = (int) Math.ceil(p / 100.0 * sortedValues.length) - 1;
        return sortedValues[Math.max(0, Math.min(idx, sortedValues.length - 1))];
    }
}
