package com.ratelimiter.dashboard;

import com.ratelimiter.config.RateLimiterProperties;
import com.ratelimiter.core.InMemorySlidingWindowRateLimiter;
import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import com.ratelimiter.redis.RateLimiterBackendException;
import com.ratelimiter.redis.RedisSlidingWindowRateLimiter;
import com.ratelimiter.resilience.CircuitBreaker;
import com.ratelimiter.resilience.FailurePolicy;
import com.ratelimiter.resilience.ResilientRateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Bounded, expiring experiments using the original limiter implementations, never production keys. */
@Service
@ConditionalOnProperty(name = "dashboard.enabled", havingValue = "true")
public class LabService {
    private static final long TTL_MS = 15 * 60_000;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Semaphore inFlight = new Semaphore(32);
    private final SimpleMeterRegistry labRegistry = new SimpleMeterRegistry();
    private final LettuceConnectionFactory redisFactory;
    private final StringRedisTemplate redis;

    public LabService(RateLimiterProperties properties,
            @Value("${dashboard.redis-enabled:false}") boolean redisEnabled) {
        if (redisEnabled) {
            var p = properties.getRedis();
            var standalone = new RedisStandaloneConfiguration(p.getHost(), p.getPort());
            var options = ClientOptions.builder().socketOptions(SocketOptions.builder()
                    .connectTimeout(Duration.ofMillis(p.getConnectTimeoutMillis())).build()).build();
            var client = LettuceClientConfiguration.builder().clientOptions(options)
                    .commandTimeout(Duration.ofMillis(p.getCommandTimeoutMillis())).build();
            redisFactory = new LettuceConnectionFactory(standalone, client);
            redisFactory.afterPropertiesSet();
            redis = new StringRedisTemplate(redisFactory);
            redis.afterPropertiesSet();
        } else {
            redisFactory = null;
            redis = null;
        }
    }

    public synchronized String create(int limit, long windowMillis, String mode, FailurePolicy policy, boolean outage) {
        sessions.entrySet().removeIf(e -> e.getValue().expiresAt < System.currentTimeMillis());
        if (sessions.size() >= 100) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lab is full; retry later.");
        }
        if ("REDIS".equals(mode) && redis == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Redis lab is disabled; set DASHBOARD_REDIS_ENABLED=true.");
        }
        String id = UUID.randomUUID().toString();
        sessions.put(id, new Session(id, limit, windowMillis, mode, policy, outage));
        return id;
    }

    public Result check(String id, String client, String node) {
        Session session = sessions.get(id);
        if (session == null || session.expiresAt < System.currentTimeMillis()) {
            if (session != null) sessions.remove(id, session);
            throw new ResponseStatusException(HttpStatus.GONE, "Lab session expired; start a new experiment.");
        }
        if (!inFlight.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Lab concurrency capacity reached.");
        }
        try {
            session.registerClient(client);
            long start = System.nanoTime();
            ResilientRateLimiter limiter = "B".equals(node) ? session.b : session.a;
            RateLimitDecision d = limiter.decide(client);
            return new Result(d.allowed(), d.remaining(), d.retryAfterMs(), d.degraded(), node,
                    (System.nanoTime() - start) / 1_000_000.0, System.currentTimeMillis(), limiter.isCircuitOpen(),
                    session.outage ? "INJECTED_BACKEND_FAILURE" : "JAVA_LIMITER");
        } finally {
            inFlight.release();
        }
    }

    public void delete(String id) { sessions.remove(id); }

    @PreDestroy
    public void close() {
        if (redisFactory != null) redisFactory.destroy();
        labRegistry.close();
    }

    public record Result(boolean allowed, int remaining, long retryAfterMs, boolean degraded, String node,
            double latencyMs, long timestamp, boolean circuitOpen, String execution) { }

    private final class Session {
        private final long expiresAt = System.currentTimeMillis() + TTL_MS;
        private final Map<String, Boolean> clients = new ConcurrentHashMap<>();
        private final ResilientRateLimiter a;
        private final ResilientRateLimiter b;
        private final boolean outage;

        private Session(String id, int limit, long windowMillis, String mode, FailurePolicy policy, boolean outage) {
            this.outage = outage;
            // Both Redis objects use this experiment's prefix; both memory objects have independent maps.
            String prefix = "rl:lab:" + id + ":";
            a = wrap(base(limit, windowMillis, mode, prefix), policy);
            b = wrap(base(limit, windowMillis, mode, prefix), policy);
        }

        private RateLimiter base(int limit, long window, String mode, String prefix) {
            if (outage) {
                return client -> { throw new RateLimiterBackendException("Injected lab-only backend outage", null); };
            }
            return "REDIS".equals(mode)
                    ? new RedisSlidingWindowRateLimiter(redis, limit, window, prefix)
                    : new InMemorySlidingWindowRateLimiter(limit, window);
        }

        private ResilientRateLimiter wrap(RateLimiter base, FailurePolicy policy) {
            return new ResilientRateLimiter(base, policy, new CircuitBreaker(3, 5000, Clock.systemUTC()),
                    1, 10, 20, labRegistry);
        }

        private synchronized void registerClient(String client) {
            if (!clients.containsKey(client) && clients.size() >= 64) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Maximum 64 clients per experiment.");
            }
            clients.put(client, Boolean.TRUE);
        }
    }
}
