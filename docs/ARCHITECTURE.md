# Architecture

Five diagrams tracing the system from the simplest version (Phase 1) to
the full distributed, failure-aware version (Phase 5+).

## 1. Single-instance in-memory architecture (Phase 1)

The starting point: one JVM, one `HashMap`, correctness proven before
concurrency is even a concern.

```mermaid
flowchart TD
    Client[Client] --> Limiter["InMemorySlidingWindowRateLimiter.decide(clientId)"]
    Limiter --> Map["HashMap&lt;clientId, Deque&lt;timestamp&gt;&gt;<br/>(NOT thread-safe)"]
    Map --> Decision{"timestamps.size() &lt; limit?"}
    Decision -->|yes| Allow["record timestamp, allow"]
    Decision -->|no| Deny["deny, retryAfterMs = oldest + window - now"]
```

## 2. Thread-safe architecture (Phase 2)

Same algorithm, safe under concurrent callers. Two separate hazards,
two separate mechanisms: `ConcurrentHashMap.computeIfAbsent` for
race-free client registration, a per-client lock for the read-check-
write inside one client's window.

```mermaid
flowchart TD
    T1[Thread 1] --> CHM
    T2[Thread 2] --> CHM
    T3[Thread N] --> CHM
    CHM["ConcurrentHashMap&lt;clientId, ClientWindow&gt;<br/>computeIfAbsent: race-free registration"]
    CHM --> CWA["ClientWindow (client A)<br/>synchronized(this)"]
    CHM --> CWB["ClientWindow (client B)<br/>synchronized(this)"]
    CWA --> DequeA["ArrayDeque&lt;timestamp&gt;"]
    CWB --> DequeB["ArrayDeque&lt;timestamp&gt;"]

    note1["Threads on DIFFERENT clients never contend --<br/>each client's lock is independent.<br/>Threads on the SAME client serialize --<br/>inherent to a shared mutable counter."]
    CWA -.-> note1
```

## 3. Distributed Redis architecture (Phase 4-6)

Multiple application instances, no shared in-process state -- they
agree only through Redis. Each instance wraps the raw Redis limiter in
the resilience layer before the metrics layer, then exposes it over
HTTP.

```mermaid
flowchart TD
    subgraph Instance A
        APIA[REST API] --> MetA[MetricsRateLimiter]
        MetA --> ResA[ResilientRateLimiter<br/>retry + circuit breaker + fail-open/closed]
        ResA --> RedA[RedisSlidingWindowRateLimiter]
    end

    subgraph Instance B
        APIB[REST API] --> MetB[MetricsRateLimiter]
        MetB --> ResB[ResilientRateLimiter<br/>retry + circuit breaker + fail-open/closed]
        ResB --> RedB[RedisSlidingWindowRateLimiter]
    end

    subgraph Instance N
        APIN[REST API] --> MetN[MetricsRateLimiter]
        MetN --> ResN[ResilientRateLimiter<br/>retry + circuit breaker + fail-open/closed]
        ResN --> RedN[RedisSlidingWindowRateLimiter]
    end

    RedA --> Redis[(Redis<br/>ZSET per clientId<br/>EVAL sliding_window.lua)]
    RedB --> Redis
    RedN --> Redis
```

## 4. Request sequence (happy path)

```mermaid
sequenceDiagram
    participant C as Client
    participant API as RateLimitController
    participant M as MetricsRateLimiter
    participant R as ResilientRateLimiter
    participant L as RedisSlidingWindowRateLimiter
    participant Redis as Redis

    C->>API: POST /v1/rate-limit/check {clientId}
    API->>M: decide(clientId)
    M->>R: decide(clientId)
    R->>R: circuitBreaker.permitCall() -> true
    R->>L: decide(clientId)
    L->>Redis: EVAL sliding_window.lua (ZREMRANGEBYSCORE, ZCARD, ZADD, PEXPIRE)
    Redis-->>L: {allowed, remaining, retryAfterMs}
    L-->>R: RateLimitDecision
    R->>R: circuitBreaker.recordSuccess()
    R-->>M: RateLimitDecision
    M->>M: record counters + latency timer
    M-->>API: RateLimitDecision
    API-->>C: 200 OK {allowed:true, remaining, retryAfterMs:0} or<br/>429 + Retry-After {allowed:false, retryAfterMs}
```

## 5. Redis failure path

```mermaid
sequenceDiagram
    participant C as Client
    participant R as ResilientRateLimiter
    participant CB as CircuitBreaker
    participant L as RedisSlidingWindowRateLimiter
    participant Redis as Redis (down)

    C->>R: decide(clientId)
    R->>CB: permitCall()
    CB-->>R: true (CLOSED)
    R->>L: decide(clientId)  [attempt 1]
    L->>Redis: EVAL ...
    Redis--xL: connection refused / timeout
    L-->>R: RateLimiterBackendException
    R->>R: sleep(backoff, attempt 1)
    R->>L: decide(clientId)  [attempt 2 of maxRetries]
    L->>Redis: EVAL ...
    Redis--xL: still failing
    L-->>R: RateLimiterBackendException
    R->>CB: recordFailure()
    Note over CB: consecutive failures >= threshold -> OPEN
    R->>R: applyFailurePolicy() -> degraded decision
    R-->>C: FAIL_OPEN: allow (degraded=true)<br/>FAIL_CLOSED: deny (degraded=true)

    Note over C,Redis: --- subsequent request, breaker still OPEN ---
    C->>R: decide(clientId)
    R->>CB: permitCall()
    CB-->>R: false (OPEN, cooldown not elapsed)
    Note over R,L: Redis is never called -- no retry, no network attempt.<br/>This is what actually prevents a retry storm.
    R-->>C: degraded decision (fast, ~0ms)
```

## Notes on design choices visible in these diagrams

- **The decorator chain (Metrics -> Resilient -> Redis) is the same
  shape in every environment.** In `IN_MEMORY` mode, `Resilient`/`Redis`
  are simply absent -- `Metrics` wraps `InMemorySlidingWindowRateLimiter`
  directly. One `RateLimiter` interface, composed differently per mode,
  wired in one place (`RateLimiterConfig`).
- **The circuit breaker is what makes diagram 5 different from "just
  retries."** Retries alone still touch Redis (and pay backoff) on every
  request during an outage. The breaker remembers the outage across
  requests and skips Redis entirely once it's open.
- **Readiness/liveness deliberately don't appear as failing in diagram
  5.** The whole point of fail-open/fail-closed is that the service
  keeps answering `/v1/rate-limit/check` through a Redis outage --
  `RateLimiterHealthIndicator` always reports UP and surfaces
  `redisCircuitOpen` as a detail instead, so a Kubernetes readiness
  probe doesn't pull a perfectly-functional pod out of rotation over a
  dependency the app is explicitly designed to tolerate losing.
