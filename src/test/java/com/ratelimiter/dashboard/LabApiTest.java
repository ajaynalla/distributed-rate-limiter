package com.ratelimiter.dashboard;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.*;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"dashboard.enabled=true", "dashboard.redis-enabled=false"})
@AutoConfigureMockMvc
class LabApiTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Autowired MockMvc mvc;

    private String url(String path) { return "http://localhost:" + port + path; }

    private String session(String mode, String policy, boolean outage) {
        var response = http.postForEntity(url("/v1/lab/sessions"),
                Map.of("limit", 5, "windowMillis", 60000, "mode", mode, "failurePolicy", policy, "outage", outage), Map.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return (String) response.getBody().get("id");
    }

    private ResponseEntity<LabService.Result> check(String id, String client, String node) {
        return http.postForEntity(url("/v1/lab/sessions/" + id + "/check"), Map.of("clientId", client, "node", node), LabService.Result.class);
    }

    @Test void concurrentHttpRequestsCannotOversubscribeOneWindow() throws Exception {
        String id = session("IN_MEMORY", "FAIL_OPEN", false);
        try (var pool = Executors.newFixedThreadPool(12)) {
            var tasks = IntStream.range(0, 40).mapToObj(i -> (Callable<Boolean>) () -> check(id, "hot", "A").getBody().allowed()).toList();
            long allowed = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) allowed++;
            assertThat(allowed).isEqualTo(5);
        }
        assertThat(check(id, "hot", "B").getBody().allowed()).as("independent memory instance").isTrue();
        assertThat(check(id, "other", "A").getBody().allowed()).as("independent client").isTrue();
        var denied = check(id, "hot", "A");
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(denied.getHeaders().getFirst("Retry-After")).isNotBlank();
        http.delete(url("/v1/lab/sessions/" + id));
    }

    @Test void failureInjectionUsesRealPolicyAndOpensCircuitWithoutTouchingProduction() {
        for (String policy : new String[]{"FAIL_OPEN", "FAIL_CLOSED"}) {
            String id = session("IN_MEMORY", policy, true);
            for (int i = 0; i < 3; i++) {
                var d = check(id, "fault", "A").getBody();
                assertThat(d.degraded()).isTrue();
                assertThat(d.allowed()).isEqualTo(policy.equals("FAIL_OPEN"));
            }
            assertThat(check(id, "fault", "A").getBody().circuitOpen()).isTrue();
            http.delete(url("/v1/lab/sessions/" + id));
        }
        var production = http.postForEntity(url("/v1/rate-limit/check"), Map.of("clientId", "production-safe"), Map.class);
        assertThat(production.getBody().get("degraded")).isEqualTo(false);
    }

    @Test void inputBoundsAndMissingSessionsReturnUsefulStatusCodes() {
        var invalid = http.postForEntity(url("/v1/lab/sessions"), Map.of("limit", 100000, "windowMillis", 60000, "mode", "IN_MEMORY", "failurePolicy", "FAIL_OPEN"), String.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(check("missing", "client", "A").getStatusCode()).isEqualTo(HttpStatus.GONE);
        String id = session("IN_MEMORY", "FAIL_OPEN", false);
        assertThat(check(id, "a:seq", "A").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        http.delete(url("/v1/lab/sessions/" + id));
        var redis = http.postForEntity(url("/v1/lab/sessions"), Map.of("limit", 5, "windowMillis", 1000, "mode", "REDIS", "failurePolicy", "FAIL_OPEN"), String.class);
        assertThat(redis.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test void corsAllowsConfiguredLocalDashboardAndRejectsOtherOrigins() throws Exception {
        // HttpURLConnection strips Origin as a restricted header; MockMvc preserves the actual preflight.
        mvc.perform(options("/v1/lab/sessions").header("Origin", "http://localhost:5173")
                .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));
        mvc.perform(options("/v1/lab/sessions").header("Origin", "https://untrusted.example")
                .header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden());
    }
}
