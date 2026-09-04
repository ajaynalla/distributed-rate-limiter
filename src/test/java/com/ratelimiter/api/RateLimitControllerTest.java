package com.ratelimiter.api;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Controller-layer contract tests: the {@link RateLimiter} is mocked so
 * these exercise only request/response mapping, validation, and status
 * codes -- not the rate-limiting algorithm itself (that is covered by
 * the core/Redis/resilience test suites).
 */
@WebMvcTest(RateLimitController.class)
class RateLimitControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private RateLimiter rateLimiter;

    @Test
    void allowedRequestReturns200WithDecisionBody() throws Exception {
        when(rateLimiter.decide("alice")).thenReturn(RateLimitDecision.allow(9));

        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allowed").value(true))
                .andExpect(jsonPath("$.remaining").value(9))
                .andExpect(jsonPath("$.retryAfterMs").value(0))
                .andExpect(jsonPath("$.degraded").value(false));
    }

    @Test
    void deniedRequestReturns429WithRetryAfterHeader() throws Exception {
        when(rateLimiter.decide("bob")).thenReturn(RateLimitDecision.deny(1500));

        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"bob\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", "2")) // ceil(1500ms) = 2s
                .andExpect(jsonPath("$.allowed").value(false))
                .andExpect(jsonPath("$.retryAfterMs").value(1500));
    }

    @Test
    void degradedDecisionIsSurfacedInResponseBody() throws Exception {
        when(rateLimiter.decide("carol")).thenReturn(RateLimitDecision.degradedAllow());

        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"carol\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.remaining").value(-1));
    }

    @Test
    void blankClientIdIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingClientIdIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void oversizedClientIdIsRejectedWith400() throws Exception {
        String tooLong = "x".repeat(257);

        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void malformedJsonIsRejectedWith400() throws Exception {
        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{not-json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingContentTypeIsRejected() throws Exception {
        mockMvc.perform(post("/v1/rate-limit/check").content("{\"clientId\":\"dave\"}"))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void responseContentTypeIsJson() throws Exception {
        when(rateLimiter.decide("erin")).thenReturn(RateLimitDecision.allow(5));

        mockMvc.perform(post("/v1/rate-limit/check")
                        .contentType("application/json")
                        .content("{\"clientId\":\"erin\"}"))
                .andExpect(content().contentType("application/json"));
    }
}
