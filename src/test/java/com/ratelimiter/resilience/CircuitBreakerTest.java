package com.ratelimiter.resilience;

import com.ratelimiter.testsupport.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private static final int FAILURE_THRESHOLD = 3;
    private static final long OPEN_DURATION_MILLIS = 1000;

    private ManualClock clock;
    private CircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(0);
        breaker = new CircuitBreaker(FAILURE_THRESHOLD, OPEN_DURATION_MILLIS, clock);
    }

    @Test
    void startsClosedAndPermitsCalls() {
        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.permitCall()).isTrue();
    }

    @Test
    void tripsOpenAfterConsecutiveFailuresReachThreshold() {
        breaker.recordFailure();
        breaker.recordFailure();
        assertThat(breaker.isOpen()).as("below threshold, still closed").isFalse();

        breaker.recordFailure();
        assertThat(breaker.isOpen()).as("hit threshold, now open").isTrue();
        assertThat(breaker.permitCall()).isFalse();
    }

    @Test
    void aSuccessResetsTheFailureCount() {
        breaker.recordFailure();
        breaker.recordFailure();
        breaker.recordSuccess();
        breaker.recordFailure();
        breaker.recordFailure();

        assertThat(breaker.isOpen()).as("count was reset by the success, only 2 failures since").isFalse();
    }

    @Test
    void movesToHalfOpenAndPermitsATrialCallAfterCooldown() {
        tripBreaker();
        assertThat(breaker.permitCall()).isFalse();

        clock.advance(OPEN_DURATION_MILLIS);

        assertThat(breaker.permitCall()).as("cooldown elapsed, trial call permitted").isTrue();
    }

    @Test
    void aSuccessInHalfOpenClosesTheBreaker() {
        tripBreaker();
        clock.advance(OPEN_DURATION_MILLIS);
        breaker.permitCall(); // transitions to HALF_OPEN

        breaker.recordSuccess();

        assertThat(breaker.isOpen()).isFalse();
        assertThat(breaker.permitCall()).isTrue();
    }

    @Test
    void aFailureInHalfOpenReopensImmediately() {
        tripBreaker();
        clock.advance(OPEN_DURATION_MILLIS);
        breaker.permitCall(); // transitions to HALF_OPEN

        breaker.recordFailure();

        assertThat(breaker.isOpen()).as("single failure while half-open reopens, no second chance").isTrue();
        assertThat(breaker.permitCall()).isFalse();
    }

    @Test
    void staysOpenBeforeCooldownElapses() {
        tripBreaker();

        clock.advance(OPEN_DURATION_MILLIS - 1);

        assertThat(breaker.permitCall()).isFalse();
    }

    private void tripBreaker() {
        for (int i = 0; i < FAILURE_THRESHOLD; i++) {
            breaker.recordFailure();
        }
    }
}
