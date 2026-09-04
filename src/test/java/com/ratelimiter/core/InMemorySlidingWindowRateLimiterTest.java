package com.ratelimiter.core;

import com.ratelimiter.testsupport.ManualClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1: correctness of the sliding-window-log algorithm, single-threaded.
 * All timing is driven by {@link ManualClock} so tests are deterministic and
 * fast (no Thread.sleep).
 */
class InMemorySlidingWindowRateLimiterTest {

    private static final int LIMIT = 3;
    private static final long WINDOW_MILLIS = 1000;

    private ManualClock clock;
    private InMemorySlidingWindowRateLimiter limiter;

    @BeforeEach
    void setUp() {
        clock = new ManualClock(0);
        limiter = new InMemorySlidingWindowRateLimiter(LIMIT, WINDOW_MILLIS, clock);
    }

    @Test
    void allowsRequestsUpToTheLimit() {
        for (int i = 0; i < LIMIT; i++) {
            RateLimitDecision decision = limiter.decide("client-A");
            assertThat(decision.allowed()).as("request #%d", i + 1).isTrue();
            assertThat(decision.remaining()).isEqualTo(LIMIT - 1 - i);
        }
    }

    @Test
    void deniesOnceLimitIsReachedWithinTheWindow() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(limiter.tryAcquire("client-A")).isTrue();
        }

        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.remaining()).isZero();
        assertThat(decision.retryAfterMs()).isGreaterThan(0);
    }

    @Test
    void deniedDecisionReportsAnAccurateRetryAfter() {
        // 3 requests at t=0 fill the window of 1000ms.
        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire("client-A");
        }

        clock.set(400);
        RateLimitDecision decision = limiter.decide("client-A");

        assertThat(decision.allowed()).isFalse();
        // oldest request (t=0) expires at t=1000, we're at t=400 -> 600ms left.
        assertThat(decision.retryAfterMs()).isEqualTo(600);
    }

    @Test
    void allowsAgainAfterTheWindowFullyExpires() {
        for (int i = 0; i < LIMIT; i++) {
            limiter.tryAcquire("client-A");
        }
        assertThat(limiter.tryAcquire("client-A")).isFalse();

        clock.set(1000); // oldest timestamp (0) is now exactly WINDOW_MILLIS old.

        assertThat(limiter.tryAcquire("client-A")).isTrue();
    }

    @Test
    void slidingWindowAdmitsRequestsAsOldOnesExpireOneAtATime() {
        // t=0,100,200 fill the limit of 3.
        clock.set(0);
        limiter.tryAcquire("client-A");
        clock.set(100);
        limiter.tryAcquire("client-A");
        clock.set(200);
        limiter.tryAcquire("client-A");

        clock.set(999);
        assertThat(limiter.tryAcquire("client-A"))
                .as("still within 1000ms of the t=0 request")
                .isFalse();

        clock.set(1000);
        assertThat(limiter.tryAcquire("client-A"))
                .as("t=0 request has just expired, freeing exactly one slot")
                .isTrue();

        assertThat(limiter.tryAcquire("client-A"))
                .as("t=100 request has not expired yet, so the slot is used up again")
                .isFalse();
    }

    @Test
    void tracksMultipleClientsIndependently() {
        for (int i = 0; i < LIMIT; i++) {
            assertThat(limiter.tryAcquire("client-A")).isTrue();
        }
        assertThat(limiter.tryAcquire("client-A")).isFalse();

        // client-B has never been seen and must have its own full quota.
        for (int i = 0; i < LIMIT; i++) {
            assertThat(limiter.tryAcquire("client-B")).as("client-B request #%d", i + 1).isTrue();
        }
        assertThat(limiter.tryAcquire("client-B")).isFalse();
    }

    @Test
    void unknownClientStartsWithAFullQuota() {
        RateLimitDecision decision = limiter.decide("brand-new-client");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.remaining()).isEqualTo(LIMIT - 1);
    }

    @Test
    void rejectsNullOrBlankClientId() {
        assertThatThrownBy(() -> limiter.decide(null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.decide("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> limiter.decide("   ")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidConfiguration() {
        assertThatThrownBy(() -> new InMemorySlidingWindowRateLimiter(0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemorySlidingWindowRateLimiter(-1, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new InMemorySlidingWindowRateLimiter(10, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void boundaryTimestampExactlyAtWindowEdgeIsTreatedAsExpired() {
        InMemorySlidingWindowRateLimiter single =
                new InMemorySlidingWindowRateLimiter(1, WINDOW_MILLIS, clock);

        clock.set(0);
        assertThat(single.tryAcquire("client-A")).isTrue();

        clock.set(WINDOW_MILLIS - 1);
        assertThat(single.tryAcquire("client-A")).as("1ms before expiry, still denied").isFalse();

        clock.set(WINDOW_MILLIS);
        assertThat(single.tryAcquire("client-A")).as("exactly at expiry, now allowed").isTrue();
    }
}
