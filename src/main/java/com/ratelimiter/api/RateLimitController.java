package com.ratelimiter.api;

import com.ratelimiter.core.RateLimitDecision;
import com.ratelimiter.core.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/rate-limit")
public class RateLimitController {

    private final RateLimiter rateLimiter;

    public RateLimitController(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @PostMapping("/check")
    public ResponseEntity<RateLimitCheckResponse> check(@Valid @RequestBody RateLimitCheckRequest request) {
        RateLimitDecision decision = rateLimiter.decide(request.clientId());
        RateLimitCheckResponse body = new RateLimitCheckResponse(
                decision.allowed(), decision.remaining(), decision.retryAfterMs(), decision.degraded());

        if (decision.allowed()) {
            return ResponseEntity.ok(body);
        }

        long retryAfterSeconds = Math.max(1, (decision.retryAfterMs() + 999) / 1000);
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                .body(body);
    }
}
