package com.ratelimiter.config;

import com.ratelimiter.resilience.FailurePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds the {@code rate-limiter.*} configuration namespace. Plain getters
 * and setters (no Lombok) so the bound shape is fully visible without
 * relying on annotation processing magic.
 */
@ConfigurationProperties(prefix = "rate-limiter")
public class RateLimiterProperties {

    public enum Mode {
        IN_MEMORY,
        REDIS
    }

    private Mode mode = Mode.IN_MEMORY;
    private int limit = 10;
    private long windowMillis = 1000;
    private final Redis redis = new Redis();

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public int getLimit() {
        return limit;
    }

    public void setLimit(int limit) {
        this.limit = limit;
    }

    public long getWindowMillis() {
        return windowMillis;
    }

    public void setWindowMillis(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public Redis getRedis() {
        return redis;
    }

    public static class Redis {
        private String host = "localhost";
        private int port = 6379;
        private String keyPrefix = "rl:";
        private long connectTimeoutMillis = 200;
        private long commandTimeoutMillis = 200;
        private FailurePolicy failurePolicy = FailurePolicy.FAIL_OPEN;
        private int maxRetries = 2;
        private long baseBackoffMillis = 20;
        private long maxBackoffMillis = 200;
        private int circuitBreakerFailureThreshold = 5;
        private long circuitBreakerOpenDurationMillis = 5000;

        public String getHost() {
            return host;
        }

        public void setHost(String host) {
            this.host = host;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(long connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public long getCommandTimeoutMillis() {
            return commandTimeoutMillis;
        }

        public void setCommandTimeoutMillis(long commandTimeoutMillis) {
            this.commandTimeoutMillis = commandTimeoutMillis;
        }

        public FailurePolicy getFailurePolicy() {
            return failurePolicy;
        }

        public void setFailurePolicy(FailurePolicy failurePolicy) {
            this.failurePolicy = failurePolicy;
        }

        public int getMaxRetries() {
            return maxRetries;
        }

        public void setMaxRetries(int maxRetries) {
            this.maxRetries = maxRetries;
        }

        public long getBaseBackoffMillis() {
            return baseBackoffMillis;
        }

        public void setBaseBackoffMillis(long baseBackoffMillis) {
            this.baseBackoffMillis = baseBackoffMillis;
        }

        public long getMaxBackoffMillis() {
            return maxBackoffMillis;
        }

        public void setMaxBackoffMillis(long maxBackoffMillis) {
            this.maxBackoffMillis = maxBackoffMillis;
        }

        public int getCircuitBreakerFailureThreshold() {
            return circuitBreakerFailureThreshold;
        }

        public void setCircuitBreakerFailureThreshold(int circuitBreakerFailureThreshold) {
            this.circuitBreakerFailureThreshold = circuitBreakerFailureThreshold;
        }

        public long getCircuitBreakerOpenDurationMillis() {
            return circuitBreakerOpenDurationMillis;
        }

        public void setCircuitBreakerOpenDurationMillis(long circuitBreakerOpenDurationMillis) {
            this.circuitBreakerOpenDurationMillis = circuitBreakerOpenDurationMillis;
        }
    }
}
