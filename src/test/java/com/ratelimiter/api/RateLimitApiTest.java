package com.ratelimiter.api;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full Spring context, real embedded server, real HTTP calls -- proves
 * RateLimiterConfig/RateLimiterProperties/RateLimitController are wired
 * together correctly end to end, not just that the controller's request
 * mapping works in isolation (that's {@link RateLimitControllerTest}).
 * Uses IN_MEMORY mode specifically so this runs under plain `mvn test`
 * with no external Redis dependency.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"rate-limiter.mode=IN_MEMORY", "rate-limiter.limit=3", "rate-limiter.window-millis=60000"})
class RateLimitApiTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    void enforcesTheConfiguredLimitAcrossRealHttpCalls() {
        String clientId = "api-test-client-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            ResponseEntity<RateLimitCheckResponse> response = check(clientId);
            assertThat(response.getStatusCode()).as("request #%d", i + 1).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().allowed()).isTrue();
        }

        ResponseEntity<RateLimitCheckResponse> fourth = check(clientId);
        assertThat(fourth.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(fourth.getBody().allowed()).isFalse();
        assertThat(fourth.getHeaders().getFirst("Retry-After")).isNotNull();
    }

    @Test
    void distinctClientsHaveIndependentQuotas() {
        String clientA = "api-test-a-" + System.nanoTime();
        String clientB = "api-test-b-" + System.nanoTime();

        for (int i = 0; i < 3; i++) {
            assertThat(check(clientA).getBody().allowed()).isTrue();
        }
        assertThat(check(clientA).getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        assertThat(check(clientB).getBody().allowed()).as("client B has its own quota").isTrue();
    }

    @Test
    void healthEndpointReportsUp() {
        ResponseEntity<String> response =
                restTemplate.getForEntity("http://localhost:" + port + "/actuator/health", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    private ResponseEntity<RateLimitCheckResponse> check(String clientId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<String> request = new HttpEntity<>("{\"clientId\":\"" + clientId + "\"}", headers);
        return restTemplate.postForEntity(
                "http://localhost:" + port + "/v1/rate-limit/check", request, RateLimitCheckResponse.class);
    }
}
