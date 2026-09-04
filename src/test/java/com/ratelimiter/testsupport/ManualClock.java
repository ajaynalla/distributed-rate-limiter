package com.ratelimiter.testsupport;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A mutable {@link Clock} for deterministic tests. Avoids Thread.sleep-based
 * timing in window-expiration and boundary tests.
 */
public final class ManualClock extends Clock {

    private final AtomicLong millis;
    private final ZoneId zone;

    public ManualClock(long startMillis) {
        this(startMillis, ZoneId.of("UTC"));
    }

    private ManualClock(long startMillis, ZoneId zone) {
        this.millis = new AtomicLong(startMillis);
        this.zone = zone;
    }

    public void advance(long deltaMillis) {
        millis.addAndGet(deltaMillis);
    }

    public void set(long newMillis) {
        millis.set(newMillis);
    }

    @Override
    public ZoneId getZone() {
        return zone;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return new ManualClock(millis.get(), zone);
    }

    @Override
    public Instant instant() {
        return Instant.ofEpochMilli(millis.get());
    }

    @Override
    public long millis() {
        return millis.get();
    }
}
