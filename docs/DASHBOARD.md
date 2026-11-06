# RateLab dashboard: setup and experiments

RateLab is a React + TypeScript + Vite frontend under `frontend/`. The original Java limiter, Lua script, resilience decorators, production endpoint, and existing tests remain in place.

## What is real?

| Mode | Execution | Scope |
| --- | --- | --- |
| Browser demo | Explicit TypeScript simulation, no API or Redis calls | This browser tab only |
| Java API / isolated lab | Original `InMemorySlidingWindowRateLimiter`, `RedisSlidingWindowRateLimiter`, `ResilientRateLimiter`, and `CircuitBreaker` | An expiring experiment with independent A/B limiter objects in one JVM |
| Java API / production | Existing `POST /v1/rate-limit/check` | Existing deployment configuration and production metrics |

The published static frontend starts in **Browser demo**. A public Java/Redis deployment requires a connected Java-capable hosting account; it is not provided by static hosting. Use **Connect Java backend** after starting or deploying the service. A network error never silently substitutes simulated results for real responses.

## Local setup

Requirements: Java 21, Maven 3.9+, Node 22.12+ (Node 24 recommended); Docker for Redis/Testcontainers.

### Full application with Redis in one command

```bash
docker compose -f docker-compose.dashboard.yml up --build
```

1. Open **http://localhost:8080**.
2. Wait for the automatic Java connection to verify.
3. If it fails, use **Connection settings**, leave Backend origin empty, and retry.
4. The badge must read **Java API · isolated lab** before interpreting requests as real Java/Redis execution.

This separate Compose file adds a combined UI/Java image and private Redis container. The original `docker-compose.yml` and `docker/Dockerfile` remain usable for backend-only development. The UI uses the same origin, so CORS is unnecessary in this configuration.

### Separate development servers, no Docker

Terminal 1, repository root:

```bash
mvn spring-boot:run -Dspring-boot.run.arguments=--dashboard.enabled=true
```

Terminal 2:

```bash
cd frontend
npm ci
npm run dev
```

Open the printed Vite URL (normally **http://127.0.0.1:5173**), click **Connect Java backend**, leave the origin empty, and connect. Vite proxies `/v1` and `/actuator` to port 8080. The in-memory lab works without Redis.

For a local Redis lab, also set `DASHBOARD_REDIS_ENABLED=true`, `RATE_LIMITER_REDIS_HOST`, and `RATE_LIMITER_REDIS_PORT` before starting Java. The production mode can remain `IN_MEMORY`; the lab Redis connection is independent and lifecycle-managed.

PowerShell environment variables use `$env:DASHBOARD_ENABLED='true'`; Bash uses `export DASHBOARD_ENABLED=true`.

## Dashboard walkthrough

### Sliding-window rate enforcement

1. Start a **New experiment**. Use storage **In-memory**, limit **10**, window **10 seconds**, route **Instance A**, client `client-001`.
2. Click **Send burst**: it sends limit + 5 requests using up to 16 workers. Expect **10 allowed, 5 rejected** if the burst finishes inside the window.
3. Inspect the request log: rejected responses are HTTP 429, have remaining 0 and a retry hint. HTTP 429 is a valid decision, not a connection failure.
4. Watch timestamps slide out of the window. After 10 seconds, **Send request** should succeed again. Only accepted, non-degraded decisions consume quota.
5. Change Client ID to see an independent quota. Changing limit, window, storage, or failure policy creates a fresh isolated experiment. Production configuration is read-only.

The remaining indicator is the **latest response's snapshot**, not an authoritative quota poll. Concurrent replies may arrive out of decision order. Dots use browser arrival time and are a visualization, not a Redis ZSET dump.

### Concurrency and shared state

1. Set window **60 seconds** in Overview to keep a short run inside one window.
2. Open **Concurrency**. Set **1 distinct client**, **20 requests per client**, route **A + B**.
3. Run a new **In-memory** experiment. Expect **20 allowed**: A and B each have their own limit of 10.
4. Open **Storage & resilience**, choose **Redis** (requires the Java Redis lab), then start the same test in a fresh experiment. Expect **10 allowed, 10 rejected** because both limiter objects use one shared Redis key.
5. Test client isolation with multiple distinct clients. Generated IDs have suffixes such as `client-001-1`. Node routing alternates over all attempts; with an even number of clients, a client can consistently land on one node. Use the one-client test above for the clearest A/B comparison.

These A/B objects run in **one JVM**. Existing `RedisSlidingWindowRateLimiterIT` tests independent limiter instances against real Redis, and `LabRedisIT` additionally verifies concurrent A/B HTTP requests through the dashboard API. The UI does not claim two deployed Java processes.

### Failure handling and recovery

1. Open **Storage & resilience**. Choose **Fail open**, enable **Inject backend failure**.
2. Return to Overview and send at least four sequential requests to A. All are allowed with `degraded=true`, remaining unknown, and the circuit is observed open after three exhausted calls.
3. Select **Fail closed** (a fresh experiment), keep injection on, and repeat. Requests are rejected with `degraded=true`.
4. Switch injection off to create a fresh healthy experiment. This resets that lab circuit and quota.

Java injection uses a throwing delegate under the **existing resilience decorator**: one retry with 10–20 ms backoff, failure threshold 3, cooldown 5 seconds. It is a controlled injected failure, not proof that a real Redis server was stopped. Browser mode models this behavior and remains labeled simulated.

To test an actual outage locally with the combined Compose stack, use a real Redis experiment, then:

```bash
docker compose -f docker-compose.dashboard.yml stop redis
# Send requests and inspect degraded decisions.
docker compose -f docker-compose.dashboard.yml start redis
# After the 5s cooldown, send a new request; success closes the circuit.
```

This is a command for your disposable local stack. The dashboard has no endpoint for stopping Redis or changing production failure policy. Stop/restart of real Redis cannot be performed by the browser injection toggle.

## Observability

- Session totals count all completed decisions since the experiment started; logs retain 500 decisions and display the newest 40 matching the filter.
- The chart retains the last 500 decisions in the last 60 seconds, in 2-second buckets. Throughput is the last 10 seconds of retained decisions divided by 10.
- Admission rate is allowed / completed decisions. Fail-open admission does not prove successful work by a protected application.
- P95 is browser-measured round-trip latency over up to 500 decisions. Browser-demo timings are browser execution, not Java performance. Concurrency wall time includes the complete run; this is not the repository's core microbenchmark.
- The separate **Production instance metrics** strip polls `/v1/dashboard/metrics` every 5 seconds. It excludes isolated lab traffic, shows instance counters since process startup, and marks stale refreshes.
- Existing `/actuator/health`, `/actuator/metrics`, and `/actuator/prometheus` remain available. Use the original benchmark for core algorithm measurements.

## New API surface

| Endpoint | Purpose |
| --- | --- |
| `GET /v1/dashboard/config` | Production configuration and lab capabilities |
| `GET /v1/dashboard/metrics` | Existing Micrometer production counter/timer snapshot |
| `POST /v1/lab/sessions` | Create isolated session (`limit`, `windowMillis`, `mode`, `failurePolicy`, `outage`) |
| `POST /v1/lab/sessions/{id}/check` | Real Java decision (`clientId`, `node`: A or B), HTTP 200 or 429 |
| `DELETE /v1/lab/sessions/{id}` | Dispose experiment |

The lab is **disabled by default**, enabled with `DASHBOARD_ENABLED=true`. Redis lab access additionally requires `DASHBOARD_REDIS_ENABLED=true`. A session expires after 15 minutes; capacity is 100 sessions, 64 distinct clients/session, limit 1–100, window 1–60 seconds, and 32 in-flight Java checks globally. The UI uses at most 16 workers and 400 attempts/run. Expired/full/disabled resources produce visible API errors.

Redis keys use a random session UUID under `rl:lab:` and inherit the existing Lua TTL. Session state resides in the serving JVM, so horizontal deployment of the lab requires sticky routing or a shared session registry. Production Redis rate enforcement retains its original cross-instance behavior.

## Deployment

The static frontend is deployed separately using Sites. Its `.openai/hosting.json` records the hosting identity. **Sites runs the UI, not the Java backend.** The repository remains the source for both frontend and Java code.

`render.yaml` describes a combined Docker web service plus a private Redis-compatible key-value service, both explicitly on `free` plans. Apply it through an authorized Render account after reviewing workspace usage and billing. See [actual-backend hosting, costs, and verification](HOSTING.md). Configuration alone does not create resources or prove deployment.

The combined Docker image serves `/` and `/v1` from Spring Boot and honors Render's `PORT`. Its health check uses `/actuator/health/liveness`. After Render returns the **actual assigned HTTPS URL**:

1. Open that URL; the combined UI automatically verifies the Java API and selects Redis. Run the experiments above.
2. To use the separate published Sites dashboard, click **Connect Java backend** and enter the Render HTTPS origin. The blueprint allows the published Sites origin via `DASHBOARD_ALLOWED_ORIGINS`.
3. For automatic real-backend connection in the separate frontend, build with `VITE_API_BASE_URL=https://<actual-java-origin>` and `VITE_REQUIRE_BACKEND=true`. Requests remain disabled until verification succeeds; this mode has no simulation fallback.
4. Verify health, a burst, cross-instance Redis quota, and an injected failure before claiming the backend deployment is complete.

For another provider, deploy `docker/Dockerfile.dashboard`, provision reachable Redis, and set the equivalent environment variables. No Redis credentials belong in `VITE_*` variables or Git. CORS permits only configured frontend origins and does not provide API authentication. The original public production endpoint remains unchanged.

Official hosting reference: [Render Blueprint specification](https://render.com/docs/blueprint-spec). GitHub Pages can serve a static UI but cannot run this Java/Redis stack.

## Validation

```bash
mvn test                       # existing 49 + 4 dashboard tests
mvn spotbugs:check
mvn verify                     # includes real Redis Testcontainers tests; requires Docker
cd frontend
npm ci
npm test                       # 12 behavior/API/component tests
npm run build                  # strict TypeScript + production Vite bundle
cd ..
node scripts/smoke.mjs          # Java must be running with the lab enabled
python -m unittest discover -s scripts -p 'test_*.py'
python scripts/verify_deployment.py <actual-java-origin> --frontend <actual-ui-origin>
```

CI retains the existing backend checks and adds frontend tests/build, Compose validation, a combined Docker image build, and a container HTTP smoke check. On restricted JDK 21 sandboxes where Mockito's dynamic attach is blocked, start Surefire with the locally resolved Byte Buddy agent in `-DargLine=-javaagent:<path-to-byte-buddy-agent.jar>`; this is an environment workaround, not a backend code change.

Local validation on November 6, 2026: 53 backend tests and 10 frontend tests passed, SpotBugs reported zero findings, and real HTTP/UI checks verified in-memory quota, concurrency, both injected policies, circuit opening, and CORS. Docker/Redis integration verification is delegated to the repository's Linux CI because Docker is unavailable on the development host. Check the current Actions run for its actual outcome.
