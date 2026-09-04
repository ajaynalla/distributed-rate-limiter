package com.ratelimiter.redis;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Distributed sliding-window-log rate limiter backed by Redis. Multiple
 * application instances calling {@link #decide(String)} for the same
 * clientId share one source of truth (the Redis ZSET keyed by that
 * client), so the limit is enforced correctly no matter which instance
 * a given request lands on.
 *
 * <h2>Atomicity</h2>
 * <p>The entire "evict expired timestamps, count remaining, decide,
 * record" sequence runs as a single Lua script (see
 * {@code scripts/sliding_window.lua}) via {@code EVAL}/{@code EVALSHA}.
 * Redis executes scripts as one indivisible unit of work with no other
 * client's commands interleaved, which is what closes the classic
 * distributed rate-limiter race: a naive
 * {@code GET count -> compute in app -> SET count} sequence has a gap
 * between the read and the write where a second instance can read the
 * same stale count and both instances admit a request that should have
 * been the last one allowed. Moving the read-check-write into the script
 * removes that gap entirely; it never exists as separate round trips.
 *
 * <h2>Clock source</h2>
 * <p>The script calls Redis's own {@code TIME} command rather than
 * trusting a timestamp supplied by the caller. Every application
 * instance therefore agrees on one clock. If each instance used its own
 * {@code System.currentTimeMillis()}, ordinary NTP drift between hosts
 * would let a client on a "slow" instance's clock get an extra window's
 * worth of requests relative to a "fast" one.
 *
 * <h2>Client isolation</h2>
 * <p>Every client gets its own Redis key ({@code <prefix><clientId>}),
 * so clients never share state and one client's traffic cannot affect
 * another's quota accounting.
 *
 * <h2>Serialization</h2>
 * <p>Uses {@link StringRedisTemplate} exclusively -- all keys, members,
 * and script arguments are plain UTF-8 strings, never Java object
 * serialization. This keeps the data trivially inspectable with
 * {@code redis-cli} and avoids JDK serialization's versioning and
 * security pitfalls; there is no complex object graph to serialize here,
 * just timestamps and counters.
 *
 * <h2>TTL / expiration</h2>
 * <p>Both the ZSET and its companion sequence key are given a TTL of
 * {@code window + 1s} on every write, refreshed on every call (including
 * denied ones). An idle client's keys are therefore reclaimed by Redis
 * shortly after the client stops sending traffic, instead of leaking
 * memory forever.
 *
 * <h2>What this class does not do</h2>
 * <p>It does not decide what to do when Redis itself is unreachable or
 * times out -- it simply throws {@link RateLimiterBackendException}.
 * Fail-open/fail-closed policy, retries, and backoff are a deliberately
 * separate concern (Phase 5) layered on top via
 * {@code ResilientRateLimiter}, so this class stays purely about
 * correctness of the Redis-backed algorithm itself.
 */
public final class RedisSlidingWindowRateLimiter implements RateLimiter {

    private static final String DEFAULT_KEY_PREFIX = "rl:";

    private final StringRedisTemplate redisTemplate;
    private final RedisScript<List> script;
    private final int limit;
    private final long windowMillis;
    private final String keyPrefix;

    public RedisSlidingWindowRateLimiter(StringRedisTemplate redisTemplate, int limit, long windowMillis) {
        this(redisTemplate, limit, windowMillis, DEFAULT_KEY_PREFIX);
    }

    @SuppressWarnings("unchecked")
    public RedisSlidingWindowRateLimiter(
            StringRedisTemplate redisTemplate, int limit, long windowMillis, String keyPrefix) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive, got " + limit);
        }
        if (windowMillis <= 0) {
            throw new IllegalArgumentException("windowMillis must be positive, got " + windowMillis);
        }
        this.redisTemplate = redisTemplate;
        this.limit = limit;
        this.windowMillis = windowMillis;
        this.keyPrefix = keyPrefix;
        this.script = RedisScript.of(new ClassPathResource("scripts/sliding_window.lua"), List.class);
    }

    @Override
    @SuppressWarnings("unchecked")
    public RateLimitDecision decide(String clientId) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be null/blank");
        }

        String zsetKey = keyPrefix + clientId;
        String seqKey = zsetKey + ":seq";

        try {
            List<Long> result = redisTemplate.execute(
                    script, List.of(zsetKey, seqKey), String.valueOf(limit), String.valueOf(windowMillis));

            if (result == null || result.size() != 3) {
                throw new RateLimiterBackendException(
                        "Unexpected response shape from sliding_window.lua: " + result, null);
            }

            boolean allowed = result.get(0) == 1L;
            int remaining = result.get(1).intValue();
            long retryAfterMs = result.get(2);

            return allowed ? RateLimitDecision.allow(remaining) : RateLimitDecision.deny(retryAfterMs);
        } catch (RateLimiterBackendException e) {
            throw e;
        } catch (Exception e) {
            throw new RateLimiterBackendException("Redis rate-limit check failed for client '" + clientId + "'", e);
        }
    }

    public int getLimit() {
        return limit;
    }

    public long getWindowMillis() {
        return windowMillis;
    }
}
