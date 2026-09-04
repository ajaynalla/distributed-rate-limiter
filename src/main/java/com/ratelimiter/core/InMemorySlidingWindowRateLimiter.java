package com.ratelimiter.core;

import java.time.Clock;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Sliding-window-log rate limiter: each client's recent request
 * timestamps are tracked explicitly, so the limit is enforced over a
 * continuously moving window rather than fixed calendar buckets.
 *
 * <p><b>Windowing rule:</b> a request at time {@code now} counts against
 * the client's quota if it landed strictly after {@code now - windowMillis}.
 * A timestamp exactly {@code windowMillis} old has expired and is evicted.
 *
 * <h2>Concurrency model (Phase 2)</h2>
 * <p>Two independent hazards have to be closed:
 * <ol>
 *   <li><b>Registering a new client.</b> Two threads racing to be the
 *       first caller for a never-seen client must not create two separate
 *       {@link ClientWindow} instances (the second would silently reset
 *       the client's quota). {@link ConcurrentHashMap#computeIfAbsent} is
 *       used specifically because it guarantees the mapping function runs
 *       at most once per key even under concurrent callers.</li>
 *   <li><b>Mutating one client's window.</b> The read-check-write in
 *       {@code decide()} (count timestamps, compare to limit, add a
 *       timestamp) must be atomic, or two threads can both observe
 *       "capacity available" and both be admitted, pushing the client over
 *       its limit (a classic check-then-act race). Each {@link ClientWindow}
 *       synchronizes on itself around that sequence.</li>
 * </ol>
 *
 * <p><b>Why not a single global lock?</b> A global lock would make step 2
 * correct too, but it serializes <em>every</em> client through one lock,
 * so an unrelated hot client (or client B) waits behind client A's
 * request even though they share no state. Locking per-client instead
 * means only concurrent requests for the <em>same</em> client contend;
 * throughput across distinct clients scales with core count. The
 * contention that remains — many threads hammering one hot client — is
 * inherent to the algorithm: a shared mutable counter for a single key
 * must be serialized somewhere, no locking strategy removes that.
 *
 * <p><b>Why not {@code AtomicLong}/{@code AtomicInteger}?</b> A single
 * atomic counter is sufficient for a much simpler fixed-window algorithm,
 * but the sliding-window log needs to inspect and evict individual
 * timestamps from an ordered collection as a joint operation — that is
 * more than one field changing atomically together, which plain atomics
 * cannot express. A lock around a plain {@code ArrayDeque} is the correct
 * and simplest tool here.
 *
 * <p>{@code ConcurrentHashMap} itself is lock-free/striped internally for
 * reads and per-bucket for writes, so client registration does not become
 * a contention point either.
 */
public class InMemorySlidingWindowRateLimiter implements RateLimiter {

    private final int limit;
    private final long windowMillis;
    private final Clock clock;
    private final ConcurrentMap<String, ClientWindow> clients = new ConcurrentHashMap<>();

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

        ClientWindow window = clients.computeIfAbsent(clientId, id -> new ClientWindow());
        return window.recordAttempt(clock.millis());
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    /**
     * Per-client mutable state. Every access is synchronized on the
     * instance itself, so contention is scoped to callers sharing the
     * same clientId — never to unrelated clients.
     */
    private final class ClientWindow {
        private final Deque<Long> timestamps = new ArrayDeque<>();

        synchronized RateLimitDecision recordAttempt(long now) {
            evictExpired(now - windowMillis);

            if (timestamps.size() < limit) {
                timestamps.addLast(now);
                return RateLimitDecision.allow(limit - timestamps.size());
            }

            long oldest = timestamps.peekFirst();
            return RateLimitDecision.deny((oldest + windowMillis) - now);
        }

        private void evictExpired(long windowStart) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() <= windowStart) {
                timestamps.pollFirst();
            }
        }
    }
}
