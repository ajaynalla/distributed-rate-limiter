package com.ratelimiter.resilience;

import java.time.Clock;

/**
 * A minimal three-state circuit breaker guarding calls to Redis.
 *
 * <p><b>Why a circuit breaker at all, on top of bounded retries?</b>
 * Bounded retries only cap the damage of <em>one</em> request against a
 * failing Redis -- every request still tries at least once, and every
 * failing request still pays the retry/backoff cost. Once Redis is
 * confirmed down, that adds up across a whole fleet of instances into a
 * retry storm: thousands of requests per second each doing 2-3 doomed
 * attempts against a backend that cannot answer. The breaker remembers
 * "Redis is down" across requests so that, once open, calls skip Redis
 * (and skip retrying) entirely until a cooldown passes.
 *
 * <h2>States</h2>
 * <ul>
 *   <li>{@code CLOSED} - normal operation, calls go through.</li>
 *   <li>{@code OPEN} - calls are short-circuited immediately (no network
 *       call at all) until {@code openDurationMillis} has elapsed.</li>
 *   <li>{@code HALF_OPEN} - after the cooldown, calls are allowed through
 *       again as trial traffic. A single failure while half-open reopens
 *       the breaker immediately; any success closes it.</li>
 * </ul>
 *
 * <p><b>Simplification vs. a textbook implementation:</b> a stricter
 * half-open state admits exactly one trial call and holds every other
 * caller back until that one resolves. This implementation instead lets
 * all callers through once half-open, and reopens on the first failure
 * seen from any of them. That is simpler to reason about and to test,
 * and for this system's request volume the difference is a handful of
 * extra trial calls against a recovering Redis, not a meaningful
 * additional storm risk.
 *
 * <p>Thread-safe: all state transitions happen inside a single
 * {@code synchronized} block, since breaker state changes are rare
 * relative to request volume and do not need to be lock-free.
 */
public final class CircuitBreaker {

    private enum State {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final long openDurationMillis;
    private final Clock clock;

    private State state = State.CLOSED;
    private int consecutiveFailures = 0;
    private long openedAtMillis = 0;

    public CircuitBreaker(int failureThreshold, long openDurationMillis, Clock clock) {
        if (failureThreshold <= 0) {
            throw new IllegalArgumentException("failureThreshold must be positive, got " + failureThreshold);
        }
        if (openDurationMillis <= 0) {
            throw new IllegalArgumentException("openDurationMillis must be positive, got " + openDurationMillis);
        }
        this.failureThreshold = failureThreshold;
        this.openDurationMillis = openDurationMillis;
        this.clock = clock;
    }

    /** @return true if a call should be attempted; false if it should be short-circuited. */
    public synchronized boolean permitCall() {
        if (state == State.OPEN) {
            if (clock.millis() - openedAtMillis >= openDurationMillis) {
                state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
        return true; // CLOSED or HALF_OPEN
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == State.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            trip();
        }
    }

    private void trip() {
        state = State.OPEN;
        openedAtMillis = clock.millis();
    }

    public synchronized boolean isOpen() {
        return state == State.OPEN;
    }
}
