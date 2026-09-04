package com.ratelimiter.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RateLimitCheckRequest(
        @NotBlank(message = "clientId must not be blank")
                @Size(max = 256, message = "clientId must be at most 256 characters")
                String clientId) {}
