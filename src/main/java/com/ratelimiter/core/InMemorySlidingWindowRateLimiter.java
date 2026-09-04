package com.ratelimiter.core;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Sliding-window-log rate limiter: each client's recent request
 * timestamps are tracked explicitly, so the limit is enforced over a
 * continuously moving window rather than fixed calendar buckets.
 *
 * <p><b>Windowing rule:</b> a request at time {@code now} counts against
 * the client's quota if it landed strictly after {@code now - windowMillis}.
 * A timestamp exactly {@code windowMillis} old has expired and is evicted.
 *
 * <p><b>Phase 1 baseline:</b> this class is intentionally NOT thread-safe.
 * {@code HashMap} and {@code ArrayDeque} are plain, unsynchronized
 * collections, and {@code decide()} performs a read-check-write sequence
 * with no locking. Two threads calling concurrently for the same client
 * can interleave inside {@code decide()} and both observe capacity,
 * allowing the client over its limit. The single-threaded correctness of
 * the algorithm is validated first (see the unit tests); concurrency
 * safety is added as a deliberate, separate step.
 */
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final Map<String, Deque<Long>> requestLog = new HashMap<>();

    public InMemorySlidingWindowRateLimiter(int limit, long windowMillis) {
        this(limit, windowMillis, Clock.systemUTC());
    }

    public InMemorySlidingWindowRateLimiter(int limit, long windowMillis, Clock clock) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got " + windowMillis);
        }
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision decide(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be null/blank");
        }

        long now = clock.millis();
        long windowStart = now - windowMillis;

        Deque<Long> timestamps = requestLog.computeIfAbsent(clientId, id -> new ArrayDeque<>());
        evictExpired(timestamps, windowStart);

        if (timestamps.size() < limit) {
            timestamps.addLast(now);
            return RateLimitDecision.allow(limit - timestamps.size());
        }

        long oldest = timestamps.peekFirst();
        long retryAfterMs = (oldest + windowMillis) - now;
        return RateLimitDecision.deny(retryAfterMs);
    }

    private void evictExpired(Deque<Long> timestamps, long windowStart) {
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
            timestamps.pollFirst();
        }
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowMillis() {
        return windowMillis;
    }
}
