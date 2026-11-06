package com.ratelimiter.integration;

import com.ratelimiter.dashboard.LabService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dashboard.enabled=true", "dashboard.redis-enabled=true"})
class LabRedisIT {
    @Container static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
    @DynamicPropertySource static void config(DynamicPropertyRegistry r) {
        r.add("rate-limiter.redis.host", redis::getHost);
        r.add("rate-limiter.redis.port", () -> redis.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;

    @Test void twoLabLimiterObjectsShareExactlyOneRedisQuotaUnderConcurrentHttpLoad() throws Exception {
        var created = http.postForEntity("http://localhost:" + port + "/v1/lab/sessions",
                Map.of("limit", 10, "windowMillis", 60000, "mode", "REDIS", "failurePolicy", "FAIL_CLOSED"), Map.class);
        String id = (String) created.getBody().get("id");
        try (var executor = Executors.newFixedThreadPool(16)) {
            var tasks = IntStream.range(0, 40).mapToObj(i -> (Callable<LabService.Result>) () -> http.postForObject(
                    "http://localhost:" + port + "/v1/lab/sessions/" + id + "/check",
                    Map.of("clientId", "shared-hot-client", "node", i % 2 == 0 ? "A" : "B"), LabService.Result.class)).toList();
            int allowed = 0;
            for (var future : executor.invokeAll(tasks)) {
                var decision = future.get();
                assertThat(decision.degraded()).isFalse();
                if (decision.allowed()) allowed++;
            }
            assertThat(allowed).isEqualTo(10);
        }
    }
}
