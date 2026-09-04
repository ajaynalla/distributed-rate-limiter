package com.ratelimiter.integration;

import com.ratelimiter.api.RateLimitCheckResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring context + real embedded server + real Redis (via
 * Testcontainers), proving the whole stack -- controller, config wiring,
 * Redis Lua script, resilience wrapper, metrics -- works together in
 * REDIS mode, not just IN_MEMORY mode (covered by
 * {@code api.RateLimitApiTest}, which runs without Docker).
 */
@Testcontainers
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rate-limiter.mode=REDIS", "rate-limiter.limit=3", "rate-limiter.window-millis=60000"})
class RateLimitApiRedisIT {

    @Container
    static GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("rate-limiter.redis.host", redis::getHost);
        registry.add("rate-limiter.redis.port", () -> redis.getMappedPort(6379));
    }

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void enforcesTheConfiguredLimitViaRealRedis() {
        String clientId = "redis-api-test-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<RateLimitCheckResponse> response = check(clientId);
            assertThat(response.getStatusCode()).as("request #%d", i + 1).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<RateLimitCheckResponse> fourth = check(clientId);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody().degraded()).as("Redis is up, this must be a real decision").isFalse();
    }

    @Test
    void healthReportsRedisModeAndClosedCircuit() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getBody()).contains("\"mode\":\"REDIS\"").contains("\"redisCircuitOpen\":false");
    }

    private ResponseEntity<RateLimitCheckResponse> check(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"clientId\":\"" + clientId + "\"}", headers);
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/rate-limit/check", request, RateLimitCheckResponse.class);
    }
}
