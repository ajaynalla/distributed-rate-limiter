# Hosting the actual Java application

The existing service stays in Java 21 / Spring Boot. Python is used only for deployment verification and one-shot health monitoring. No Python backend replaces the limiter.

## Live deployment

Deployed and verified November 6, 2026:

- [Public Sites dashboard](https://ratelab-ajaynalla.ajaykumarnalla98.chatgpt.site)
- [Java API origin and same-origin dashboard](https://ratelab-java.onrender.com)
- [Backend health](https://ratelab-java.onrender.com/actuator/health)
- Production decisions: `POST https://ratelab-java.onrender.com/v1/rate-limit/check`

The existing Sites identity was updated to version 2 and automatically connects to the actual Java origin. Render's `ratelab-java` web service and private `ratelab-redis` datastore are both **Free** in Virginia. The datastore is Redis-compatible Valkey 8.1.10. Workspace billing was checked before creation: Hobby plan, no card on file, and no paid resources added. See [recorded CI, API, and browser results](DEPLOYMENT_STATUS.md).

## Architecture and cost

```text
Browser -> HTTPS Render web service (free)
                 |- React UI served by Spring Boot at /
                 |- Existing production API at /v1/rate-limit/check
                 |- Isolated Java experiments at /v1/lab/sessions
                 `- Private Redis-compatible Key Value service (free, same region)

Existing Sites UI -> HTTPS Java origin (CORS allowlist)
```

`render.yaml` explicitly selects `free` for both resources, disables preview environments, and waits for GitHub checks before automatic deploys. The Docker image builds the existing frontend and Java jar and runs as a non-root user. Render supplies HTTPS and `PORT`; the health path is `/actuator/health/liveness`. JSON application logs remain on stdout for Render log collection. Java heap and processor settings fit the 512 MB free web service.

Estimated **compute cost: $0/month** for one eligible free web service and one free Key Value instance, within the workspace's included usage. Free plans do not guarantee a zero total bill when a workspace has a payment method: Render can bill bandwidth or build-minute overages. Before creation, verify workspace billing, included usage, and build spend limit. To guarantee no unapproved charges, use a workspace without a payment method; if the account or resource cannot use free plans, stop and obtain approval before creating anything paid. No paid resource is authorized by this repository.

Free-tier limits relevant to this portfolio demo: the web service sleeps after 15 idle minutes and can take about a minute to wake; free web service hours are shared across the workspace. Initial Redis connection setup can add seconds to the first decision. The UI waits up to 90s for startup configuration and keeps requests disabled until verified. Redis connection and command deadlines are configured as 2s and 5s with one retry to accommodate free CPU startup; latency figures are real round trips, not core algorithm benchmarks. Only one free Key Value instance is allowed per workspace, with 25 MB storage, 50 connections, and no disk persistence. A Java restart clears lab sessions and in-memory quotas; a datastore restart can clear Redis quotas. A/B in the lab are independent Java limiter objects in one JVM; Redis shares their quota. This is not an always-on, highly available multi-region production deployment.

Current official references: [free plans and limits](https://render.com/docs/free), [compute plans](https://render.com/docs/compute-plans), [Blueprint schema](https://render.com/docs/blueprint-spec).

## Deploy from the existing repository

1. Sign in to Render and complete its GitHub authorization for `ajaynalla/distributed-rate-limiter`. Enable the Render integration for automated access, or use the signed-in Render Dashboard.
2. Use the intended workspace and review the billing conditions above. Keep both resources on **Free**; never accept a paid fallback without approval.
3. Open [Create this repository's Blueprint](https://dashboard.render.com/blueprint/new?repo=https://github.com/ajaynalla/distributed-rate-limiter). Select `main` and the checked-in `render.yaml`. Review `ratelab-java` and `ratelab-redis`, both in Virginia. If a free datastore already exists, resolve reuse before creating a duplicate or upgrading it.
4. Apply the Blueprint and monitor the actual deploy until it is **Live**. Provider acceptance or a generated URL alone does not prove the application works.
5. Copy the actual assigned HTTPS Java origin from Render. Do not derive a URL from the service name: Render can add a suffix.
6. Run the verification command below. It must verify non-degraded Redis decisions, not just health UP.

All non-secret wiring is in `render.yaml`: private datastore host/port references, Redis production mode, a 10-second production window, both lab capabilities, and the existing Sites CORS origin. Redis is private (`ipAllowList: []`), with `noeviction` so memory exhaustion becomes a visible backend failure rather than silently discarding quota keys.

## Frontend connection

The combined Docker UI automatically connects to its own Java API, defaults the lab to Redis, and keeps requests disabled until configuration verifies. It waits up to 90 seconds for a cold start. API failures remain errors; this build provides no browser-simulation fallback.

For the existing separately hosted Sites UI, build the existing `frontend/` with:

```text
VITE_API_BASE_URL=<actual HTTPS Java origin>
VITE_REQUIRE_BACKEND=true
```

Then republish the existing Site identity in `frontend/.openai/hosting.json`. These are public browser settings, not secrets. Runtime environment changes alone cannot change an already built Vite bundle. The Blueprint's CORS allowlist already includes the existing Sites origin. An HTTPS UI must use an HTTPS API.

The published Sites version now uses `https://ratelab-java.onrender.com` with required-backend mode. Its real burst, concurrency, storage comparison, and failure controls were verified in a browser. Local development can still use the explicitly labeled educational simulation.

## Verify and monitor

Python 3.10+; standard library only; no pip installation required:

```bash
python scripts/verify_deployment.py https://ratelab-java.onrender.com --frontend https://ratelab-ajaynalla.ajaykumarnalla98.chatgpt.site
python scripts/verify_deployment.py https://ratelab-java.onrender.com --health-only
```

The first command tests real HTTP responses from the Java service: production Redis decisions, in-memory vs Redis quota across A/B, four-client isolation under concurrent load, both injected failure policies, circuit opening, production metrics, and the frontend CORS preflight. It creates bounded isolated sessions and deletes them afterwards. A 429 is valid quota rejection; 503, malformed decisions, degraded healthy-path decisions, or a window crossed during testing fail verification. Injected failures test the existing Java decorator; they do not stop a Redis server.

The optional frontend check confirms HTML reachability only. Verify actual UI interaction separately in a browser: wait for **Java API · isolated lab**, send a burst, run concurrent clients, select both storage modes, and inspect failure-policy responses. The badge must never say Browser demo for the real hosted application.

Run **Verify live Java deployment** from GitHub Actions with the actual backend and frontend origins to save a JSON report. The workflow has no credentials and creates no cloud resources. It does not schedule keep-alive pings. Render's own HTTP health check and the existing `/actuator/prometheus` endpoint provide basic operational monitoring.

For browser checks, use limit 10 and window 10s: 15 requests to A admit 10 and reject 5. For the A/B comparison use a new experiment, 60s window, and 1 client x 20 attempts: memory admits 20 across two local objects; Redis admits 10 total. Enable failure injection to inspect real Java fail-open/fail-closed degraded responses.
