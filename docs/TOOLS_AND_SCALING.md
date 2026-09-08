# The stack, tool by tool — and what happens as load grows

A companion to [`BEGINNERS_GUIDE.md`](BEGINNERS_GUIDE.md). That one teaches how
the system works; this one answers two questions for every piece of it:

1. **Why is this here at all?** What job does it do in system-design terms, and
   what would break without it?
2. **What happens to it as load increases?** Where does it stop coping, why, and
   what do you do about it?

### A note on numbers

The only performance figures in this document come from
[`benchmark/RESULTS.md`](../benchmark/RESULTS.md) — real output from running
`./benchmark/run.sh` on a 4-logical-core machine, recorded verbatim, twice.

Everywhere else, this document describes **mechanisms and orderings** rather than
magnitudes: what saturates before what, why, and which metric reveals it. It
does not tell you "this handles X requests/sec," because that was never measured
here and the number would differ by an order of magnitude across hardware,
network, traffic shape and Redis sizing. Any doc that gives you that number
without a load test is guessing. Part C says exactly what you'd have to run to
get real ones.

---

# Part A — Every tool, and why it's here

Each entry: **What it is · Why here · What it does in this repo · Under load ·
Alternative, and when · Cost/ops.**

## Runtime and build

### Java 21 (the JVM)

- **What it is** — The language and runtime everything is written in.
- **Why here** — Rate limiting is CPU-cheap but concurrency-heavy. The JVM has
  the strongest mature toolkit for exactly that (`java.util.concurrent`, a
  well-specified memory model), and it's what backend teams at the target
  companies actually run.
- **In this repo** — Java 21 features used sparingly and on purpose: `record`
  for `RateLimitDecision` (immutable value with no boilerplate), pattern-matching
  `instanceof` in `RateLimiterHealthIndicator`, and `final` classes throughout
  after SpotBugs flagged constructor-throw patterns.
- **Under load** — The JVM's own behavior becomes visible in the tails. In the
  benchmark's no-contention scenario — where the code path is uncontended and
  should be uniformly fast — max latency still reached **2,010µs at 50 threads
  and 8,066µs at 100** while p99 stayed at **0.180µs and 0.654µs**. That gap
  isn't the algorithm; it's the runtime underneath: garbage collection pauses,
  JIT deoptimization, safepoints, and OS scheduling. **Any latency budget on a
  JVM service has to account for the runtime, not just your code.**
- **Alternative** — Go or Rust remove GC pauses from the tail (Rust entirely,
  Go with a much smaller collector). Worth it if you need single-digit-ms p99.9
  guarantees; not worth rewriting a working system for otherwise.
- **Cost/ops** — Heap sizing matters in containers. The in-memory client map
  grows with client cardinality (see Part B §7).

### Maven

- **What it is** — Build tool and dependency manager.
- **Why here** — Declarative, universal in Java shops, and its **lifecycle
  phases** are what let this project cleanly separate fast tests from
  Docker-dependent ones.
- **In this repo** — `pom.xml` inherits `spring-boot-starter-parent`, which
  pins consistent versions across dozens of transitive dependencies so you don't
  hand-resolve conflicts. Plugins bound deliberately: Surefire (unit tests),
  Failsafe (integration tests), SpotBugs (unbound — CI calls it explicitly so it
  never slows local builds), exec (the benchmark).
- **Under load** — Not a runtime concern; it's a *developer throughput* concern.
  Slow builds get skipped, and skipped checks stop catching things.
- **Alternative** — Gradle is faster (incremental builds, build cache) and
  worth it on large multi-module codebases; Maven's declarative simplicity wins
  on a project this size.
- **Cost/ops** — Dependency-tree size drives image size and CVE surface.

---

## The HTTP layer

### Spring Boot Web (embedded Tomcat) — the most important entry here

- **What it is** — The web framework and the servlet container inside the app.
- **Why here** — Production-standard for Java backends, and it supplies
  routing, JSON, validation, health endpoints and metrics wiring without custom
  plumbing.
- **In this repo** — `RateLimitController` exposes `POST /v1/rate-limit/check`;
  Jackson handles JSON; `RateLimiterConfig` wires the beans.
- **Under load — read this one carefully.** Spring MVC is
  **thread-per-request**: one Tomcat worker thread is dedicated to a request
  from arrival until the response is written, and it **blocks** for the whole
  duration — including while waiting on Redis and, critically, while sleeping in
  retry backoff.
  That gives a hard concurrency ceiling by Little's Law:

  > **max in-flight requests ≈ thread pool size**, so
  > **throughput ≈ threads ÷ per-request service time**

  The consequence is non-obvious and important: *slower dependencies reduce your
  request capacity even if your CPU is idle*, because capacity is measured in
  occupied threads, not cycles. When Redis slows down, each request holds its
  thread longer, in-flight requests pile up, the pool exhausts, and requests
  queue before they even reach your code. This project doesn't override
  `server.tomcat.threads.max`, so Spring Boot's default applies — that property
  is the first knob to look at, and Part B §6 covers the failure mode it creates.
- **Alternative** — WebFlux/reactive or Java 21 virtual threads
  (`spring.threads.virtual.enabled`) both break the thread-per-request ceiling by
  not tying an OS thread to a blocked request. Virtual threads are the far
  cheaper migration and the obvious next step for this codebase.
- **Cost/ops** — Each thread costs stack memory; you can't simply raise the pool
  to 10,000.

### Bean Validation (`spring-boot-starter-validation`)

- **What it is** — Declarative constraint checking on request objects.
- **Why here** — Input validation belongs at the edge, before business logic,
  stated declaratively so it can't be forgotten.
- **In this repo** — `RateLimitCheckRequest` carries `@NotBlank` and
  `@Size(max = 256)`; `@Valid` in the controller enforces them *before* the
  method body runs.
- **Under load** — Negligible cost, and it's a **protective** control: `@Size`
  bounds how long a `clientId` can be, and `clientId` becomes part of a Redis
  key. Unbounded input would mean unbounded key length and unbounded memory in
  the in-memory map — validation is quietly a resource-exhaustion defense.
- **Alternative** — Hand-written checks; more code, easier to skip, harder to
  see at a glance.
- **Cost/ops** — Rejected requests should be visible; they often mean a broken
  client, not an attack.

### RFC 7807 problem details

- **What it is** — A standard JSON shape for HTTP errors
  (`{type, title, status, detail, instance}`).
- **Why here** — Errors are part of your API contract. A standard shape means
  clients parse failures the same way across services.
- **In this repo** — `spring.mvc.problemdetails.enabled=true` handles framework
  errors; `ApiExceptionHandler` covers domain `IllegalArgumentException` and
  guarantees unexpected exceptions never leak stack traces or class names.
- **Under load** — Under attack, error handling *is* the hot path. Cheap,
  non-leaking errors matter most when things go wrong.
- **Cost/ops** — Never let internals into error bodies; they're reconnaissance.

---

## The distributed layer

### Redis

- **What it is** — An in-memory data store, used here as **shared state**, not
  as a cache.
- **Why here** — It's the piece that makes the limit apply to the fleet rather
  than to each instance separately ([`BEGINNERS_GUIDE.md`](BEGINNERS_GUIDE.md)
  §2.4). It's fast enough to sit in the request path and — decisively — its
  single-threaded execution model plus scripting gives **atomicity without
  distributed locks**.
- **In this repo** — One ZSET per client, plus a small companion counter key.
- **Under load** — Redis executes commands on **one thread**. That's what makes
  scripts atomic, and it's also the ceiling: one Redis node's throughput is
  bounded by a single core's execution of your commands. More app instances do
  not make Redis faster. See Part B §4.
- **Alternative** — Memcached (no scripting, so no atomic multi-step — wrong
  tool here); a relational DB (durable but far slower in-path); a dedicated
  service mesh / API-gateway rate limiter (worth considering before building
  anything at all, if you already run one).
- **Cost/ops** — Now a hard dependency in the request path with its own
  availability, memory limits, failover and eviction policy. Everything in
  §"When Redis dies" exists because of this line.

### Redis sorted sets (ZSET)

- **What it is** — Members with numeric scores, kept in score order.
- **Why here** — A sliding window is "the set of timestamps within a range,"
  which is exactly a range query over ordered scores.
- **In this repo** — Score = timestamp; `ZREMRANGEBYSCORE` drops expired
  entries; `ZCARD` counts; `ZRANGE ... WITHSCORES` finds the oldest to compute
  `retryAfterMs`.
- **Under load** — Memory is **O(limit) per active client**, not O(1) — this is
  the price of sliding-window precision over a fixed-window counter, and it's
  what makes total memory scale with *client cardinality × limit*.
- **Alternative** — A plain `INCR` counter (fixed window) is dramatically
  cheaper in memory but reintroduces the boundary burst. Token-bucket via a
  hash is a middle ground: O(1) memory, allows controlled bursts.
- **Cost/ops** — Watch `used_memory` and the eviction policy; a limiter whose
  keys get evicted under pressure silently stops limiting.

### Lua scripting (`EVAL` / `EVALSHA`)

- **What it is** — Server-side scripts Redis runs as one indivisible unit.
- **Why here** — **This is the core correctness mechanism of the distributed
  path.** It collapses read-decide-write into one operation, eliminating the
  cross-instance race entirely (see the guide, §2.5).
- **In this repo** — `src/main/resources/scripts/sliding_window.lua`, sent via
  `EVALSHA` (hash, not full text, per call).
- **Under load** — A script **blocks the entire Redis server** while it runs —
  that's the flip side of atomicity. Keep scripts short; this one is a handful
  of O(log N) operations. A slow script isn't slow for one caller, it's slow for
  every client of that Redis.
- **Alternative** — `MULTI/EXEC` transactions (can't branch on intermediate
  values, so they can't express "count, then decide"); Redis functions (Redis 7+,
  same model, better lifecycle); `WATCH`-based optimistic locking (retry loops
  under contention — worse where it hurts most).
- **Cost/ops** — Scripts are infrastructure code with no compiler and no
  tests-by-default. This one is covered by integration tests against real Redis.

### Lettuce (via Spring Data Redis)

- **What it is** — The Redis client library; Spring Boot's default.
- **Why here** — Netty-based, thread-safe, and it **multiplexes commands from
  many application threads over a shared connection** rather than needing a
  connection per thread.
- **In this repo** — `RateLimiterConfig` builds the `LettuceConnectionFactory`
  with explicit `connect-timeout-millis` and `command-timeout-millis` (both
  default 200ms in `application.yml`).
- **Under load** — Those timeouts are the most consequential settings in the
  file. **A missing or generous timeout is how a slow dependency becomes a total
  outage**: threads pile up waiting, the pool exhausts, and the service stops
  answering — even for requests that don't need Redis. A short timeout converts
  "slow" into "fails fast," which the resilience layer can then handle.
- **Alternative** — Jedis (connection-per-thread; needs a tuned pool).
- **Cost/ops** — Timeouts should be set relative to your latency budget, not
  copied from a blog post.

---

## The resilience layer (deliberately hand-written)

### Circuit breaker, retry with jittered backoff, fail-open/fail-closed

- **What it is** — Three standard reliability patterns, implemented directly in
  `resilience/CircuitBreaker.java` and `resilience/ResilientRateLimiter.java`.
- **Why here** — Adding a network dependency to the request path means
  explicitly answering "what happens when it's down." Doing nothing is still an
  answer — just an accidental one.
- **Why not Resilience4j?** A fair challenge. The whole implementation is ~100
  lines with a clear state machine and its own deterministic tests, and this
  project's stated goal is total explainability. The library is the right call
  when you need bulkheads, rate limiters, time limiters and metrics across many
  call sites, or when a team shouldn't be maintaining its own primitives. Here
  there's exactly one call site.
- **Under load** — Reliability patterns matter *more* as load grows, not less.
  At low traffic a retry storm is invisible; at high traffic, retries against a
  failing dependency multiply your outbound request rate by the retry count and
  can prevent recovery. Bounded retries cap one request's cost; the breaker caps
  the **fleet's** cost — measured, a short-circuited call returned in **0.010s
  wall clock** rather than attempting a configured 200ms timeout across up to 3
  attempts plus backoff.
- **Alternative** — Resilience4j; or a service mesh (Envoy/Istio) doing
  retry/outlier-detection at the network layer, out of your code entirely.
- **Cost/ops** — Every knob is a way to be wrong: too-eager breakers trip on
  noise, too-lazy ones don't protect. `rate_limiter.redis.circuit_open_skipped`
  and `rate_limiter.redis.retries` exist so you can tune from data.

---

## The observability layer

### Micrometer + Prometheus

- **What it is** — A vendor-neutral metrics API and a scrapeable exposition
  format.
- **Why here** — Once behavior degrades gracefully, degradation becomes
  *invisible* by design — a fail-open limiter returns 200s while not limiting at
  all. Metrics are the only thing that make that visible.
- **In this repo** — `MetricsRateLimiter` emits `rate_limiter.decisions`
  (tagged allowed/denied), `rate_limiter.decisions.degraded`, and the
  `rate_limiter.decision.duration` histogram; `ResilientRateLimiter` emits
  `rate_limiter.redis.retries` (tagged by failure type),
  `rate_limiter.redis.circuit_open_skipped`, and
  `rate_limiter.redis.failure_policy_applied`.
- **Under load** — Deliberately counters and histograms, not per-request logs:
  aggregation is cheap and constant-cost, while per-request logging scales with
  traffic and becomes its own bottleneck. Cardinality is the trap — tagging
  metrics by `clientId` would create one time series per client and take down
  your metrics backend. **None of these metrics are tagged with `clientId`, on
  purpose.**
- **Alternative** — StatsD/Datadog/OpenTelemetry. Micrometer can export to all
  of them without touching this code — that's the point of the abstraction.
- **Cost/ops** — Histograms cost more than counters. The **single most
  important thing to alert on here is `rate_limiter.decisions.degraded` going
  above zero** — it means the limiter isn't really limiting.

### Spring Boot Actuator (health and probes)

- **What it is** — Ready-made operational endpoints.
- **Why here** — Orchestrators need a machine-readable answer to "is this
  instance usable?"
- **In this repo** — `RateLimiterHealthIndicator` reports mode,
  `redisCircuitOpen` and `failurePolicy`, and **always returns UP** — with
  liveness/readiness exposed as separate probe groups.
- **Under load / during failure** — The subtle design point: readiness must
  reflect *"can this instance serve requests?"*, not *"are all dependencies
  perfect?"*. Coupling them means a Redis blip removes every pod from every load
  balancer simultaneously and turns a degraded dependency into a total outage.
  Redis state is surfaced as a **detail** for humans, not a signal for the
  orchestrator.
- **Cost/ops** — Health endpoints must stay cheap and never call slow
  dependencies inline; probes run constantly.

### Logback + `logstash-logback-encoder` (JSON logs) and MDC

- **What it is** — Structured logging: one JSON object per line, plus per-thread
  context.
- **Why here** — Grep works on one machine. With many instances, logs go to an
  aggregator that needs parseable fields, not prose.
- **In this repo** — `logback-spring.xml` emits JSON;
  `RequestCorrelationFilter` puts `requestId` in MDC so every line from a request
  carries it — including lines written deep inside the retry loop.
- **Under load** — **Logging is a real bottleneck at scale.** This project logs
  *events*, not requests: nothing is logged per allowed request; log lines appear
  for retries, circuit-open skips, and failure-policy fallbacks. That's a
  deliberate split — **metrics for volume, logs for anomalies**. A per-request
  log line at high QPS costs CPU, disk and money, and the flood arrives exactly
  when you're already in trouble.
- **Alternative** — Distributed tracing (OpenTelemetry) when a request spans
  several services; `requestId` is the same idea at single-service scale.
- **Cost/ops** — Ingestion is usually billed per GB; log volume is a budget line
  item.

---

## Testing and quality

### JUnit 5, AssertJ, Mockito

- **Why here** — Standard Java testing stack; AssertJ for readable assertions
  with descriptive failures, Mockito to script a fake backend so failure paths
  are testable without breaking real infrastructure.
- **In this repo** — 49 tests under `mvn test`. `ManualClock`
  (`src/test/java/com/ratelimiter/testsupport/`) is the notable one: injecting a
  controllable clock makes time-dependent window tests **deterministic** instead
  of `Thread.sleep`-based and flaky. Flaky tests get ignored, and ignored tests
  protect nothing.
- **Under load** — Test *suite* runtime is the constraint. Fast tests get run.

### Testcontainers

- **What it is** — Real dependencies in Docker containers, started by the test.
- **Why here** — The Lua script's atomicity is a claim about **real Redis
  semantics**. A mock would happily confirm whatever behavior you programmed
  into it and prove nothing.
- **In this repo** — `*IT` tests start `redis:7-alpine`;
  `RedisSlidingWindowRateLimiterIT` runs two independent limiter instances
  against one Redis to prove the distributed guarantee.
- **Under load** — Slower than unit tests and needs a Docker daemon, which is
  exactly why they're split out (below).
- **Alternative** — A shared CI Redis (state bleeds between runs); embedded
  fakes (don't reproduce real semantics — the thing under test).
- **Cost/ops** — CI needs Docker; container startup dominates these tests.

### Surefire vs Failsafe

- **What it is** — Two Maven plugins: `*Test` in the `test` phase, `*IT` in
  `integration-test`/`verify`.
- **Why here** — It separates "runs anywhere in seconds" from "needs
  infrastructure," so contributors without Docker still get the full fast suite.
- **Under load** — This is CI throughput design: fail fast and cheap first, run
  expensive checks second.

### SpotBugs

- **What it is** — Static analysis over compiled bytecode.
- **Why here** — Catches whole classes of defect no test targets.
- **In this repo** — Run explicitly in CI. It found 10 real issues; the fix was
  making five classes `final` (they were never designed for subclassing). The
  remaining `EI_EXPOSE_REP` findings are the well-known false positive for
  constructor-injected collaborators — defensively copying a `MeterRegistry`
  would be *wrong* — so they're excluded in `spotbugs-exclude.xml` **with the
  reasoning written down**, not silently suppressed.
- **Cost/ops** — Unexplained suppressions rot into ignored warnings. Every
  exclusion needs a comment.

---

## Packaging and deployment

### Docker (multi-stage build)

- **What it is** — Containerization; build tools in one stage, only the artifact
  in the final image.
- **Why here** — "Works on my machine" is a distributed-systems problem too:
  every instance must be byte-identical.
- **In this repo** — `docker/Dockerfile`: a Maven stage builds the jar, a JRE
  stage runs it as a **non-root user**, with `pom.xml` copied before `src/` so
  the dependency layer caches independently of source changes.
- **Under load** — Image size drives cold-start and scale-out speed: pull time
  is on the critical path every time you add an instance during a traffic spike.
- **Alternative** — Buildpacks / jib (no Dockerfile); GraalVM native image for
  much faster startup, at the cost of build complexity.
- **Cost/ops** — Base images carry CVEs; JRE-not-JDK and non-root are baseline
  hygiene.

### Docker Compose

- **Why here** — One command (`docker compose up --build`) starts app + Redis
  wired together, so the *distributed* mode is reproducible locally. A setup
  nobody can run locally is a setup nobody tests.
- **In this repo** — `docker-compose.yml` passes config as `RATE_LIMITER_*`
  environment variables — the same names Terraform passes in ECS, so there's no
  separate "cloud config format" to keep in sync — and gates app startup on
  Redis's healthcheck.
- **Under load** — Not for production; single-host, no orchestration.

### GitHub Actions

- **Why here** — Checks nobody runs don't exist. CI makes them mandatory.
- **In this repo** — `.github/workflows/ci.yml`: compile → unit tests → SpotBugs
  → integration tests (Docker on the runner) → package, with reports and the jar
  uploaded as artifacts.
- **Under load** — "Load" here is team size and change rate. Slow pipelines get
  bypassed.
- **Cost/ops** — CI minutes are real money; caching Maven deps matters.

### Terraform

- **Why here** — Repeatable, reviewable, diffable infrastructure. Clicking in a
  console produces environments nobody can recreate.
- **In this repo** — `terraform/`: ECS Fargate service + ElastiCache Redis +
  security groups + IAM + CloudWatch, with `environments/dev.tfvars` and
  `prod.tfvars`. Those differ **semantically**, not just in size: dev is
  `FAIL_OPEN`, prod is `FAIL_CLOSED`.
- **Under load** — `desired_count` is your scale knob; the config is what makes
  scaling out a one-line change instead of an afternoon.
- **Cost/ops** — State management is the real operational burden;
  `terraform/README.md` names remote state as a deliberate omission.

### AWS: ECS Fargate · ElastiCache · CloudWatch · security groups & IAM

- **ECS Fargate** — Runs containers without managing servers. Chosen over EKS
  because Kubernetes' operational surface isn't justified by one service; over
  EC2 because there's no patching. **Under load:** raise `desired_count`;
  each new task is another instance sharing the *same* Redis limit — which is
  the entire reason for the Redis path. Scale-out cost is container start +
  image pull.
- **ElastiCache** — Managed Redis: patching, backups and failover handled.
  **Under load:** vertical (bigger node) works until one core saturates; then
  you need sharding (cluster mode). **Note the consistency caveat:** replication
  is asynchronous, so a promoted replica may be missing the most recent writes —
  a client can briefly exceed its limit across a failover. That's an honest
  tradeoff of availability against strict correctness, not a bug.
- **CloudWatch Logs** — Where container stdout goes. **Under load:** ingestion
  is billed per GB, which is the concrete reason the app logs anomalies rather
  than requests.
- **Security groups & IAM** — Redis accepts traffic **only from the app's
  security group**, not from the VPC or internet. Least privilege limits blast
  radius: a compromised app instance is bad, a directly-reachable datastore is
  worse.

---

# Part B — What happens as load increases

Ordered by **what saturates first**. Each: the mechanism, the signal that
reveals it, and what to do.

## The measured baseline — and what it does and doesn't prove

From [`benchmark/RESULTS.md`](../benchmark/RESULTS.md), in-memory limiter,
4 logical cores, two runs:

| Scenario | 1 thread | 10 | 50 | 100 |
|---|---|---|---|---|
| **Every thread on its own client** (no contention) | 4.5M / 4.2M ops/s | 20.0M / 14.5M | 21.9M / 20.1M | 17.7M / 18.1M |
| **All threads on one client** (full contention) | 3.2M / 3.5M ops/s | 3.5M / 4.1M | 3.7M / 5.8M | 4.2M / 3.7M |

Two readings, both important:

**Contention costs ~4-6x throughput.** At 50 threads: ~21.9M vs ~3.7M ops/sec.
That gap is the cost of serializing on one lock — and it's what a single global
lock would impose on *every* client, not just hot ones.

**Contention shows up in the tail long before throughput.** In the hot-client
scenario, p50 latency stayed flat (0.129–0.558µs across all thread counts) while
max latency went from **41.7µs at 1 thread to 139,380µs at 100** — over three
orders of magnitude. Aggregate throughput barely moved. **If you watch
throughput and averages, contention is invisible until it's severe. Watch
`rate_limiter.decision.duration` p99.**

What this does **not** prove: anything about the Redis path, the HTTP layer, or
this code on your hardware. It's an in-process microbenchmark on one 4-core box.

## 1. The per-client lock (hot key), in-memory mode

- **Mechanism** — All requests for one `clientId` serialize on that client's
  lock. Inherent: any correct implementation must serialize mutation of one
  client's state somewhere.
- **Signal** — `rate_limiter.decision.duration` p99/max rising while p50 and
  throughput look fine.
- **Do** — Accept it (it's correctness), or reduce time held under the lock.
  Only sharding a single client's quota across sub-keys avoids it, and that
  trades exactness for parallelism.

## 2. CPU cores, in-memory mode with many clients

- **Mechanism** — With no lock contention, throughput scales with cores until
  you run out. Measured: ~4.5M ops/s at 1 thread → ~20M at 10 threads on 4 cores,
  then **flat at 50 and slightly lower at 100** in both runs. Past the core
  count you're not adding parallelism, you're adding context switching.
- **Signal** — CPU at saturation; throughput flat or regressing as concurrency
  climbs.
- **Do** — Scale out (more instances). Note this is where per-instance limits
  stop being acceptable and you need the Redis path.

## 3. The network round trip, Redis mode

- **Mechanism** — Switching to Redis changes the unit cost of a decision from an
  in-process memory access to a **network round trip**. The Lua script removed the
  *race*, not the round trip.
- **Signal** — `rate_limiter.decision.duration` shifts up as a whole
  distribution the moment you switch modes.
- **Do** — Keep app and Redis in the same AZ. Accept it as the price of a
  fleet-wide limit — or, for extremely hot clients, pre-aggregate locally and
  sync periodically, trading exactness for the round trip.

## 4. Redis single-threaded execution

- **Mechanism** — Redis runs commands on one thread. One node's ceiling is one
  core executing your commands, and adding app instances doesn't raise it —
  it raises the load *against* it.
- **Signal** — Redis-side latency climbing while its CPU pegs on one core; app
  latency rising with no app-side cause.
- **Do** — Shard by `clientId` (cluster mode / consistent hashing) so keys
  spread across nodes. **A single hot key is not helped by sharding** — every
  request for that client still lands on one node. That case needs local
  pre-aggregation or a bucket-splitting scheme.

## 5. Client timeouts and connection sharing

- **Mechanism** — Lettuce multiplexes over a shared connection, so connection
  count isn't usually the limit. The timeouts are: with a 200ms command timeout,
  a struggling Redis holds each request's thread up to 200ms per attempt.
- **Signal** — `rate_limiter.redis.retries{failure_type="TIMEOUT"}` rising —
  distinct from `CONNECTION`, which means Redis is down rather than slow.
- **Do** — Tune timeouts against your latency budget. Shorter timeouts fail
  faster and free threads sooner; too short turns normal slowness into false
  failures.

## 6. Servlet threads consumed by blocking retries — the compounding failure

This is the most instructive failure mode in the system, because it's where
several mechanisms multiply.

- **Mechanism** — Redis degrades. Each request now: waits up to the command
  timeout, **sleeps** in backoff, retries, waits again. Meanwhile the thread is
  held the entire time (§"Spring Boot Web"). Threads that used to turn over in
  microseconds now occupy the pool for hundreds of milliseconds. The pool
  exhausts, and requests queue *before reaching your code* — including requests
  that would have been fine. **A degraded dependency becomes a full outage of an
  otherwise-healthy service.** And the retries themselves multiply outbound load
  against the struggling Redis, making recovery harder.
- **Signal** — `rate_limiter.redis.retries` climbing, latency spiking across
  *all* endpoints, thread pool saturated.
- **Do** — This is exactly what the circuit breaker prevents: once open, calls
  return immediately (**measured 0.010s wall clock**) instead of occupying a
  thread through timeouts and sleeps. Beyond that, virtual threads or a
  non-blocking client remove the thread-occupancy coupling entirely — the single
  highest-value change this codebase could make for behavior under dependency
  failure.

## 7. Memory: key cardinality

- **Mechanism** — Memory scales with **number of active clients × limit**
  (ZSETs store up to `limit` timestamps each). Two distinct concerns:
  - **Redis:** bounded by TTL — idle clients' keys expire (`window + 1s`,
    refreshed per call).
  - **In-memory mode:** **not bounded.** `ConcurrentHashMap` entries are never
    evicted, so a process seeing unbounded distinct clients grows without limit.
    A documented limitation, called out in the README.
- **Signal** — Redis `used_memory` climbing; JVM heap growth with client
  cardinality.
- **Do** — For Redis: size for peak *active* cardinality and set a sane
  eviction policy (and know that eviction means silently not limiting). For
  in-memory: add TTL-based eviction if client cardinality is unbounded.

## 8. Single Redis node → sharding → geography

- **Mechanism** — One node is one failure domain and one capacity unit. Sharding
  spreads load but adds routing and resharding complexity. Going multi-region
  adds cross-region latency to *every* decision, or forces per-region limits.
- **Signal** — Node saturated after vertical scaling; or cross-region latency
  dominating decision time.
- **Do** — Shard by client. For multi-region, per-region limits are usually the
  honest answer: a globally exact limit needs cross-region consensus on every
  request, and almost no rate limiter is worth that latency.

## Traffic shape matters as much as traffic volume

The same requests/second behaves completely differently depending on shape:

| Factor | Why it changes behavior |
|---|---|
| **Client cardinality** | Many clients → parallel, memory-bound. One hot client → serialized, latency-bound. Same total QPS, opposite bottleneck |
| **Burstiness** | Averages hide bursts. A 10x spike over one second saturates thread pools that look idle in a per-minute graph |
| **Limit-to-window ratio** | A large `limit` means larger ZSETs (more memory, more eviction work per call). A tiny window means constant TTL churn |
| **Denied-traffic ratio** | An abusive client generates *all denied* traffic, which still costs a full Redis round trip. Rejection is not free |
| **Retry amplification** | During an outage, your own retries multiply outbound load exactly when the dependency can least afford it |
| **Thundering herd** | When Redis recovers, every instance resumes at once. Jittered backoff and the breaker's staged half-open probing exist for this |
| **Clock drift** | Independent server clocks disagree; a client on a slow clock gets extra quota. Why the Lua script uses Redis `TIME` as one shared clock |

## Factors that aren't load at all

- **Cost** — Redis memory, CloudWatch ingestion per GB, CI minutes, Fargate
  vCPU-hours. Design choices here (metrics over per-request logs; TTLs on keys)
  are cost decisions as much as technical ones.
- **Operational complexity** — Every component is something to patch, monitor
  and page someone about at 3am. This project deliberately doesn't use
  Kubernetes or Resilience4j; each would be defensible with more scale or a
  bigger team.
- **Team familiarity** — The best technology your team can't debug under
  pressure is the wrong technology.
- **Consistency under failure** — Async replication means a failover can lose
  recent writes, briefly letting a client exceed its limit. Strict correctness
  through failover needs synchronous replication or consensus, at a latency cost
  most rate limiters shouldn't pay.
- **Security and multi-tenancy** — `clientId` comes from the caller. Whoever
  calls this service must authenticate it; otherwise a client picks a new
  `clientId` and gets a fresh quota. The limiter enforces limits; it does not
  establish identity.
- **Blast radius** — One shared Redis for all limits means one failure affects
  everything. Separate Redis instances per tier cost more and fail smaller.

## Symptom → cause → first thing to try

| Symptom | Likely cause | First thing to try |
|---|---|---|
| p99 latency up, p50 and throughput normal | Lock contention on a hot client (§1) | Confirm with per-client traffic distribution; consider sub-key sharding for that client |
| Throughput flat as you add threads | Past core count (§2) | Scale out, not up |
| All latency up right after a deploy | Switched to Redis mode — round trip added (§3) | Expected; verify same-AZ placement |
| `rate_limiter.decisions.degraded` > 0 | **The limiter isn't limiting** — Redis unreachable | Page. This is the alert that matters most |
| `redis.retries{failure_type=TIMEOUT}` rising | Redis slow, not down (§5) | Check Redis CPU/memory; consider shorter timeouts |
| `redis.retries{failure_type=CONNECTION}` rising | Redis down or unreachable | Check Redis health/security groups |
| `circuit_open_skipped` rising | Breaker doing its job (§6) | Fix Redis; confirm the policy matches intent |
| All endpoints slow, not just this one | Thread pool exhaustion (§6) | Check breaker settings/timeouts; consider virtual threads |
| Redis memory climbing steadily | Client cardinality × limit (§7) | Verify TTLs applying; size for peak active clients |
| JVM heap growing over days, in-memory mode | Unbounded client map (§7) | Add eviction, or move to Redis mode |
| Limits enforced inconsistently after a failover | Async replication lost writes | Expected tradeoff; document it or change topology |

---

# Part C — The limits of this analysis

Stated plainly, because a scaling document that hides its own gaps is worse than
none:

**What was actually measured:**
- The in-memory limiter's throughput and latency percentiles at 1/10/50/100
  threads, two contention scenarios, two runs, on one 4-logical-core machine
  (`benchmark/RESULTS.md`).
- That an unsynchronized version admits 181/117/161 against a limit of 100.
- That 200 concurrent script invocations against one key admit exactly 50 for a
  limit of 50.
- That a circuit-open call returns in 0.010s wall clock.
- That 49 tests pass under `mvn test`.

**What was never measured, and therefore never claimed:**
- Redis-path throughput or latency under load — no load test was run against it.
- The HTTP layer's capacity: no request/sec figure exists for this service.
- Behavior at 10k / 100k / 1M req/sec. Part B describes which mechanism gives
  out first and why; it deliberately attaches no numbers.
- Docker and Terraform were never executed end-to-end here — this project was
  built in a sandbox whose egress policy blocked Docker Hub and
  `registry.terraform.io`. The configs are validated as far as possible
  (`docker compose config`, `terraform fmt`, Dockerfile parsing) and documented
  honestly in the relevant commits.

**To make real capacity claims you would need:** a load generator (k6, Gatling,
wrk) driving the HTTP endpoint at controlled rates; a realistic client-cardinality
distribution including a hot key; Redis on representative hardware and network;
measurement of p50/p99/p99.9 **and** thread-pool utilisation and Redis CPU
together; and a fault-injection run (kill Redis mid-load) to see the breaker and
thread pool behave under real pressure rather than in a smoke test.

That work is worth doing before promising anyone a number. Until then, the
mechanisms above tell you *what to watch and what to fix first* — which is what
actually matters when the graphs move.
