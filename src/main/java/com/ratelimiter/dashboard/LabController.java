package com.ratelimiter.dashboard;

import com.ratelimiter.resilience.FailurePolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/lab/sessions")
@ConditionalOnProperty(name = "dashboard.enabled", havingValue = "true")
public class LabController {
    private final LabService lab;

    public LabController(LabService lab) { this.lab = lab; }

    public record CreateRequest(@Min(1) @Max(100) int limit, @Min(1000) @Max(60000) long windowMillis,
            @NotNull @Pattern(regexp = "IN_MEMORY|REDIS") String mode,
            @NotNull FailurePolicy failurePolicy, boolean outage) { }
    public record CheckRequest(@NotBlank @Size(max = 64) @Pattern(regexp = "[a-zA-Z0-9_.-]+") String clientId,
            @NotNull @Pattern(regexp = "A|B") String node) { }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        String id = lab.create(request.limit(), request.windowMillis(), request.mode(), request.failurePolicy(), request.outage());
        return ResponseEntity.status(201).body(Map.of("id", id, "expiresInSeconds", 900,
                "instanceScope", "two independent Java limiter objects in one JVM", "simulated", false));
    }

    @PostMapping("/{id}/check")
    public ResponseEntity<LabService.Result> check(@PathVariable String id, @Valid @RequestBody CheckRequest request) {
        var result = lab.check(id, request.clientId(), request.node());
        var builder = ResponseEntity.status(result.allowed() ? 200 : 429);
        if (!result.allowed()) builder.header("Retry-After", String.valueOf(Math.max(1, (result.retryAfterMs() + 999) / 1000)));
        return builder.body(result);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        lab.delete(id);
        return ResponseEntity.noContent().build();
    }
}
