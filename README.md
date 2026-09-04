# Distributed Rate Limiter

A rate limiter built in Java, in-memory first and Redis-backed second,
with the concurrency, failure-handling, observability, and deployment
work a production version of it would actually need. Built as a small,
fully-explainable system: every design decision below has a stated
reason and a stated tradeoff, not a claim that one approach is
universally best.

## Problem

Expose `tryAcquire(clientId)` (and a richer `decide(clientId)` that also
reports remaining quota and a retry hint): given a per-client request
limit and a time window, decide whether a request should be allowed.
Do it correctly under concurrent access from many threads in one
process, and correctly across many separate application instances that
share no in-process state.

## Requirements

- Configurable per-client request limit and window duration.
- Thread-safe under real concurrent load, not just single-threaded.
- A distributed backend so multiple app instances enforce one shared
  limit, not one limit per instance.
- A configurable, explicit policy for what happens when that backend is
  unreachable (never an accidental behavior).
- Exposed over HTTP, with health/metrics for operating it.
- Every claim about correctness or performance backed by a test or a
  real measurement in this repo -- not asserted.

## Architecture

Five diagrams (single-instance, thread-safe, distributed, request
sequence, Redis failure path) are in
[`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). In short: a
`RateLimiter` interface with one core implementation
(`InMemorySlidingWindowRateLimiter`) and one distributed implementation
(`RedisSlidingWindowRateLimiter`), composed with two decorators --
`ResilientRateLimiter` (retries/circuit breaker/failure policy) and
`MetricsRateLimiter` (counters/latency) -- wired to whichever backend is
selected by `rate-limiter.mode`, behind a small Spring Boot REST API.

## Algorithms

**Sliding-window log.** Each client's recent request timestamps are
tracked explicitly (an `ArrayDeque` in memory, a Redis `ZSET` for the
distributed version). A request at time `t` is allowed if fewer than
`limit` timestamps remain after evicting everything at or before
`t - window`. This enforces the limit over a continuously moving
window, avoiding the classic fixed-window boundary problem where a
client can burst to `2x` its limit by timing requests around a reset
boundary. The cost is `O(limit)` memory per client instead of a single
counter -- a correctness-for-memory tradeoff made deliberately; see
[`docs/INTERVIEW_GUIDE.md`](docs/INTERVIEW_GUIDE.md) for the full
"why not fixed window" reasoning.

Both implementations share the exact same windowing rule (a timestamp
exactly `window` old has expired), so a client's observed behavior
doesn't change based on which backend is behind it.

## Concurrency model

Two independent hazards, two independent mechanisms:

1. **Registering a new client's state.** `ConcurrentHashMap.computeIfAbsent`
   guarantees the mapping function runs at most once per key even under
   concurrent first-callers, so two threads racing to be the first
   caller for a brand-new client can't create two separate state
   objects (which would silently reset that client's quota).
2. **Mutating one client's window.** The read-check-write inside one
   client's `decide()` call must be atomic, or two threads can both
   observe capacity and both get admitted past the limit. Each client's
   state carries its own lock (`synchronized(this)` in
   `ClientWindow`), so contention is scoped to callers sharing the same
   `clientId` -- unrelated clients never block each other.

A single global lock would also be correct, but serializes *every*
client through one lock. The Phase 3 benchmark quantifies what that
would cost: `per-thread-client` (no shared lock) reaches ~18-22M
ops/sec at 50-100 threads on this hardware; `single-hot-client` (all
serialized on one lock) tops out around 3.7-5.8M ops/sec at the same
thread counts.

The race this closes is real, not hypothetical: a bare unsynchronized
`ArrayDeque` under the exact load shape `InMemoryRateLimiterConcurrencyTest`
uses (64 threads, 50 attempts each, limit 100) let through 181, 117,
and 161 requests across three separate runs -- see that test's Javadoc
and the Phase 2 commit for the reproduction.

## Redis atomicity

The Redis-backed limiter does **not** do `GET count -> compute -> SET
count` -- that has a gap between the read and the write where a second
app instance can read the same stale count and both get admitted past
the limit. Instead, the entire evict/count/decide/record sequence runs
as one Lua script (`src/main/resources/scripts/sliding_window.lua`)
via `EVAL`. Redis executes a script as a single indivisible unit --
command execution is single-threaded, so no other client's commands can
interleave inside it. There is no window for the race to occur in
because there are no separate round trips.

Also notable in the script:
- **Shared clock:** it calls Redis's own `TIME` command rather than
  trusting each caller's system clock, so every app instance agrees on
  one clock instead of each trusting its own (possibly drifted) one.
- **Client isolation:** each client gets its own key
  (`<prefix><clientId>`), so clients never share state.
- **TTL:** both the ZSET and its companion sequence-counter key get a
  sliding TTL of `window + 1s`, refreshed on every call, so an idle
  client's keys are reclaimed by Redis instead of leaking memory
  forever.
- **Serialization:** `StringRedisTemplate` only -- everything is a
  plain string, never Java object serialization, so the data stays
  inspectable with `redis-cli` and avoids JDK serialization's
  versioning/security pitfalls.

Verified three ways, not just asserted: manual `redis-cli --eval` calls
proving exact limit/remaining/retry-after/TTL behavior; 200 concurrent
`redis-cli` evals via `xargs -P 50` against one key landing exactly 50
admitted for a limit of 50; and a Java-level test with two separate
limiter instances (simulating two app pods sharing no in-process state)
hitting a shared client concurrently and landing exactly at the
configured limit.

## Failure handling

Configurable per deployment (`rate-limiter.redis.failure-policy`),
because there's no universally correct choice:

| Policy | Tradeoff |
|---|---|
| `FAIL_OPEN` | Availability wins. The protected service stays up through a Redis outage; the limit isn't enforced while it's down. |
| `FAIL_CLOSED` | Protection wins. The limit is never silently bypassed; a Redis outage becomes a full outage of everything the limiter guards. |

Mechanically: each call gets up to `maxRetries` attempts with
exponential backoff + jitter (`ResilientRateLimiter`). Once exhausted,
a `CircuitBreaker` records the failure; once its failure threshold
trips, it opens and **subsequent requests skip Redis entirely** instead
of each paying retry+backoff against a backend already known to be
down -- that's the actual anti-retry-storm mechanism, since bounded
retries alone only cap the cost of a single request, not a whole fleet
hammering a dead backend simultaneously. Failures are classified
(`TIMEOUT` vs `CONNECTION` vs `UNKNOWN`) for observability. A decision
made by the failure policy instead of real quota state is marked
`degraded: true` in the API response, so it's never silently
indistinguishable from a real decision.

Verified against a real dead connection (not a mock): fail-open let
requests through with `degraded=true`, fail-closed rejected them the
same way, the breaker tripped after its configured threshold, and a
short-circuited call after tripping completed in ~10ms end-to-end over
real HTTP.

## Benchmark methodology

Full methodology, raw output from two independent runs, and analysis in
[`benchmark/RESULTS.md`](benchmark/RESULTS.md). Summary: a hand-rolled
(deliberately not JMH) harness measures throughput and latency
percentiles for the in-memory limiter at 1/10/50/100 threads, in two
scenarios -- all threads on one hot client (full lock contention) vs.
each thread on its own client (no contention) -- to separate
contention cost from raw per-call cost. Headline finding: contention
shows up first in **tail latency**, not aggregate throughput -- median
latency stays flat under the hot-client scenario while max/p99 latency
grows by 3-4 orders of magnitude as thread count rises. Run it yourself
with `./benchmark/run.sh`; the numbers in that file are real output
from this project's dev sandbox (4 logical cores), not invented, and
will differ on your hardware.

## How to run

**Locally (in-memory mode, no Redis needed):**
```bash
mvn spring-boot:run
```

**Full stack (Redis-backed, one command):**
```bash
docker compose up --build
```
This starts Redis and the app together; the app waits on Redis's
healthcheck. Try it:
```bash
curl -X POST http://localhost:8080/v1/rate-limit/check \
  -H "Content-Type: application/json" \
  -d '{"clientId":"client-123"}'
```

**Configuration** (see `src/main/resources/application.yml` and
`RateLimiterProperties` for the full set, bindable via
`RATE_LIMITER_*` environment variables): `rate-limiter.mode`
(`IN_MEMORY`/`REDIS`), `rate-limiter.limit`, `rate-limiter.window-millis`,
and under `rate-limiter.redis.*`: `host`, `port`, `key-prefix`,
`connect-timeout-millis`, `command-timeout-millis`, `failure-policy`,
`max-retries`, `base-backoff-millis`, `max-backoff-millis`,
`circuit-breaker-failure-threshold`, `circuit-breaker-open-duration-millis`.

**Endpoints:** `POST /v1/rate-limit/check`, `GET /actuator/health`
(+ `/liveness`, `/readiness`), `GET /actuator/prometheus`.

## Test strategy

Tests are written to prove specific behavior, not to inflate a coverage
number -- each one states what it's proving in its name/Javadoc.

- **Unit** (`core`, `redis` validation, `resilience`): algorithm
  correctness (limit reached, window expiration, multiple/unknown
  clients, boundary timestamps), input validation, and the
  retry/circuit-breaker/failure-policy logic against a scriptable fake
  backend.
- **Concurrency** (`concurrency`): a 64-thread stress test proving a
  hot client is admitted exactly up to its limit, and a 200-thread/
  20-client test proving full isolation between clients under load.
- **Integration** (`integration`, `*IT`, run via `mvn verify` /
  Failsafe, needs Docker): the Redis-backed limiter and the full REST
  API against a real Redis via Testcontainers, including two separate
  limiter instances sharing one Redis backend to prove distributed
  correctness, not just single-instance correctness.
- **API** (`api`): controller-layer contract tests (`@WebMvcTest`) for
  validation/status codes/headers, plus a full `@SpringBootTest`
  hitting the real embedded server over real HTTP in `IN_MEMORY` mode
  (runs without Docker).
- **Failure scenarios are explicit tests, not incidental ones**: what
  happens on retry exhaustion, what each failure policy does, that the
  breaker actually trips and actually short-circuits.

Run `mvn test` for everything that doesn't need Docker (49 tests, all
passing); `mvn verify` additionally runs the Testcontainers-based
integration tests.

## Scalability limitations

Stated plainly rather than glossed over:

- **In-memory mode is per-instance.** Each app instance enforces its
  own limit; it is not a distributed limit unless you're in `REDIS`
  mode. Fine if per-instance limits are acceptable, wrong otherwise.
- **The in-memory `ConcurrentHashMap` has no eviction.** A client that
  is seen once and never again keeps its (small) `ClientWindow` object
  forever -- a real, known memory-growth concern for a long-running
  process with unbounded client cardinality, out of scope for this
  project's size but worth flagging rather than hiding.
- **A single hot Redis key is a real bottleneck at extreme scale.**
  Redis serializes concurrent `EVAL` calls against one key correctly,
  but that key becomes hot on one Redis node; this project does not
  shard Redis.
- **Consistency weakens under Redis high availability.** A single Redis
  node gives linearizable decisions; adding replication for
  availability (Redis's default is asynchronous) means a promoted
  replica can be missing the last few writes after a failover, so a
  client could briefly exceed its limit across that event. Strict
  consistency through a failover would need synchronous replication or
  a consensus-backed store, at a latency cost most rate limiters
  shouldn't pay.
- **Retries block the calling thread** (`Thread.sleep` in
  `ResilientRateLimiter`). Fine at this project's scale; a non-blocking
  client would matter at very high concurrency so backoff doesn't tie
  up servlet threads.
- **No sharding/batching for extreme single-client hot keys.** At
  very high QPS on one client, a per-instance local pre-aggregation
  layer would trade some precision for removing the per-request Redis
  round trip -- not implemented here, and it's a real precision
  tradeoff if it were.

## Future improvements

- Shard Redis by `clientId` (consistent hashing / Redis Cluster) so no
  single node absorbs the full request rate.
- Bounded eviction/TTL for idle entries in the in-memory client map.
- A non-blocking Redis client so retry backoff doesn't hold a servlet
  thread.
- Local short-term pre-aggregation for extremely hot single clients.
- Terraform: remote state, a dedicated VPC with private subnets, an ALB
  in front of the Fargate service, multi-AZ ElastiCache, autoscaling --
  all named as deliberate omissions in `terraform/README.md`.

None of this is implemented speculatively; it's listed because it's the
honest answer to "what would you change next," not because the project
needs it to be complete at its current, stated scope.

## Repository structure

```
distributed-rate-limiter/
├── src/main/java/com/ratelimiter/
│   ├── core/            in-memory sliding-window limiter (Phase 1-2)
│   ├── redis/            Redis-backed limiter + Lua script wiring (Phase 4)
│   ├── resilience/        retry/circuit-breaker/failure-policy (Phase 5)
│   ├── config/            Spring wiring, RateLimiterProperties (Phase 6)
│   ├── api/               REST controller, DTOs, error handling (Phase 6)
│   ├── observability/     metrics decorator, health indicator (Phase 7)
│   └── benchmark/         RateLimiterBenchmark (Phase 3)
├── src/main/resources/     application.yml, logback-spring.xml, sliding_window.lua
├── src/test/java/com/ratelimiter/
│   ├── core/, concurrency/, redis/, resilience/, observability/   unit + concurrency tests
│   ├── integration/                                                *IT tests (Testcontainers, needs Docker)
│   └── api/                                                        controller + full-stack API tests
├── benchmark/              run.sh, RESULTS.md (real recorded output)
├── docker/                 Dockerfile
├── docker-compose.yml
├── terraform/              minimal ECS Fargate + ElastiCache foundation
├── docs/                   ARCHITECTURE.md (5 diagrams), INTERVIEW_GUIDE.md
├── .github/workflows/      ci.yml
└── pom.xml
```

Adapted from the suggested `tests/{unit,integration,concurrency}/`
layout to standard Maven convention (`src/test/java/...`, packages
named for what they test) -- Maven's own tooling (Surefire/Failsafe
phase separation, IDE integration) expects tests under `src/test/java`,
and package names here already say what each test proves.

## How I would defend this project in a backend interview

Short version: sliding-window-log over fixed-window because it closes
the boundary-burst problem; `ConcurrentHashMap` + per-client locking
over one global lock because contention should scope to a shared key,
not to the whole limiter (quantified with real benchmark numbers, not
asserted); the Redis implementation is atomic because the whole
decision runs as one Lua script, not a `GET`/`SET` pair with a race
window; Redis failures are a configurable fail-open/fail-closed policy
plus a circuit breaker that actually stops calling Redis once it's
confirmed down; a hot client's cost shows up as tail latency, not
average throughput; and I would not promise a number at 100K/1M req/sec
without load-testing that specific bottleneck first.

Full answers, with the actual measurements and test names behind each
claim, in [`docs/INTERVIEW_GUIDE.md`](docs/INTERVIEW_GUIDE.md).
