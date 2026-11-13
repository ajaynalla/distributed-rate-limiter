"""FastAPI implementation of the RateLab v1 API."""
import json
import logging
import os
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from http import HTTPStatus
from pathlib import Path
from time import monotonic, perf_counter, time
from typing import Annotated, Literal
from uuid import uuid4

from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles
from prometheus_client import CollectorRegistry, Counter, Histogram, PlatformCollector, ProcessCollector, generate_latest, CONTENT_TYPE_LATEST
from pydantic import BaseModel, Field, StringConstraints, field_validator
from redis.asyncio import Redis
from redis.asyncio.retry import Retry
from redis.backoff import NoBackoff
from redis.exceptions import RedisError
from starlette.exceptions import HTTPException as StarletteHTTPException

from .limiter import SCRIPT, MemoryWindow, RedisWindow, Resilient, injected_failure

Mode = Literal["IN_MEMORY", "REDIS"]
Policy = Literal["FAIL_OPEN", "FAIL_CLOSED"]
log = logging.getLogger("uvicorn.error")


class Config(BaseModel):
    mode: Mode = "IN_MEMORY"
    limit: int = Field(default=10, ge=1)
    windowMillis: int = Field(default=1000, ge=1)
    failurePolicy: Policy = "FAIL_OPEN"
    demoEnabled: bool = False
    redisEnabled: bool = False
    algorithm: str = "SLIDING_WINDOW_LOG"

    @classmethod
    def from_env(cls):
        return cls(
            mode=os.getenv("RATE_LIMITER_MODE", "IN_MEMORY"),
            limit=os.getenv("RATE_LIMITER_LIMIT", "10"),
            windowMillis=os.getenv("RATE_LIMITER_WINDOW_MILLIS", "1000"),
            failurePolicy=os.getenv("RATE_LIMITER_REDIS_FAILURE_POLICY", "FAIL_OPEN"),
            demoEnabled=os.getenv("DASHBOARD_ENABLED", "false"),
            redisEnabled=os.getenv("DASHBOARD_REDIS_ENABLED", "false"),
        )


class Client(BaseModel):
    clientId: Annotated[str, StringConstraints(max_length=256)]

    @field_validator("clientId")
    @classmethod
    def validate_client(cls, value):
        if not value.strip() or len(value.encode("utf-16-le")) > 512:
            raise ValueError("Invalid client ID")
        return value


class LabClient(BaseModel):
    clientId: Annotated[str, StringConstraints(max_length=64, pattern=r"^[a-zA-Z0-9_.-]+$")]
    node: Literal["A", "B"]


class Experiment(BaseModel):
    limit: int = Field(ge=1, le=100)
    windowMillis: int = Field(ge=1000, le=60000)
    mode: Mode
    failurePolicy: Policy
    outage: bool = False


@dataclass
class Session:
    expires: float
    nodes: dict
    outage: bool
    clients: set = field(default_factory=set)


def redis_option(name, default):
    return int(os.getenv("RATE_LIMITER_REDIS_" + name, default))


def decision_response(decision, **extra):
    headers = {}
    if not decision.allowed:
        headers["Retry-After"] = str(max(1, (decision.retryAfterMs + 999) // 1000))
    return JSONResponse(decision.body() | extra, status_code=200 if decision.allowed else 429, headers=headers)


def create_app(config=None):
    config = config or Config.from_env()
    config = config.model_copy(update={"redisEnabled": config.demoEnabled and config.redisEnabled})
    sessions = {}
    in_flight = 0
    totals = dict(allowed=0, rejected=0, degraded=0)
    total_ms = 0.0
    registry = CollectorRegistry()
    ProcessCollector(registry=registry)
    PlatformCollector(registry=registry)
    decisions = Counter("rate_limiter_decisions", "Production decisions", ["outcome"], registry=registry)
    degraded = Counter("rate_limiter_decisions_degraded", "Degraded production decisions", registry=registry)
    duration = Histogram("rate_limiter_decision_duration_seconds", "Production decision latency", registry=registry)
    requests = Counter("ratelab_http_requests", "HTTP responses", ["route", "status"], registry=registry)
    latency = Histogram("ratelab_http_duration_seconds", "HTTP latency", ["route"], registry=registry)

    def build(mode, limit, window, prefix):
        if mode == "REDIS":
            return RedisWindow(app.state.script, limit, window, prefix)
        return MemoryWindow(limit, window)

    @asynccontextmanager
    async def lifespan(app):
        redis = None
        if config.mode == "REDIS" or config.redisEnabled:
            redis = Redis(
                host=os.getenv("RATE_LIMITER_REDIS_HOST", "localhost"),
                port=redis_option("PORT", 6379),
                socket_connect_timeout=redis_option("CONNECT_TIMEOUT_MILLIS", 200) / 1000,
                socket_timeout=redis_option("COMMAND_TIMEOUT_MILLIS", 200) / 1000,
                max_connections=64,
                retry=Retry(NoBackoff(), 0),
            )
            app.state.script = redis.register_script(SCRIPT.read_text(encoding="utf-8"))
        app.state.redis = redis
        base = build(config.mode, config.limit, config.windowMillis,
                     os.getenv("RATE_LIMITER_REDIS_KEY_PREFIX", "rl:"))
        app.state.production = base if config.mode == "IN_MEMORY" else Resilient(
            base, config.failurePolicy, redis_option("MAX_RETRIES", 2),
            redis_option("CIRCUIT_BREAKER_FAILURE_THRESHOLD", 5),
            redis_option("CIRCUIT_BREAKER_OPEN_DURATION_MILLIS", 5000),
            redis_option("BASE_BACKOFF_MILLIS", 20), redis_option("MAX_BACKOFF_MILLIS", 200),
        )
        try:
            yield
        finally:
            if redis is not None:
                await redis.aclose()

    app = FastAPI(title="Distributed Rate Limiter", version="1.0.0", lifespan=lifespan)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=[origin.strip() for origin in os.getenv(
            "DASHBOARD_ALLOWED_ORIGINS", "http://localhost:5173,http://127.0.0.1:5173"
        ).split(",")],
        allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
        allow_headers=["Content-Type", "X-Request-Id"],
        expose_headers=["Retry-After", "X-Request-Id"],
    )

    def problem(request, status, detail):
        return JSONResponse(
            dict(type="about:blank", title=HTTPStatus(status).phrase, status=status,
                 detail=detail, instance=request.url.path),
            status_code=status, media_type="application/problem+json",
        )

    @app.exception_handler(RequestValidationError)
    async def invalid(request, exc):
        return problem(request, 400, "Invalid request content.")

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request, exc):
        return problem(request, exc.status_code, str(exc.detail))

    @app.middleware("http")
    async def observe(request: Request, call_next):
        started = perf_counter()
        request_id = request.headers.get("X-Request-Id", "").strip()[:128] or str(uuid4())
        try:
            response = await call_next(request)
        except Exception:
            log.exception("Unhandled request %r", request_id)
            response = problem(request, 500, "An unexpected error occurred.")
        route = getattr(request.scope.get("route"), "path", "") or "other"
        elapsed = perf_counter() - started
        requests.labels(route, response.status_code).inc()
        latency.labels(route).observe(elapsed)
        response.headers["X-Request-Id"] = request_id
        if response.status_code >= 500 or os.getenv("RATELAB_LOG_REQUESTS") == "true":
            log.info(json.dumps(dict(event="http_request", requestId=request_id,
                                     route=route, status=response.status_code, durationMs=elapsed * 1000)))
        return response

    @app.get("/v1/dashboard/config")
    async def dashboard_config():
        return config

    @app.get("/v1/dashboard/metrics")
    async def dashboard_metrics():
        count = totals["allowed"] + totals["rejected"]
        return totals | dict(meanLatencyMs=total_ms / count if count else 0,
                             scope="production instance since startup; excludes isolated lab")

    @app.post("/v1/rate-limit/check")
    async def production(payload: Client):
        nonlocal total_ms
        started = perf_counter()
        decision = await app.state.production(payload.clientId)
        elapsed = perf_counter() - started
        outcome = "allowed" if decision.allowed else "denied"
        totals["allowed" if decision.allowed else "rejected"] += 1
        decisions.labels(outcome).inc()
        duration.observe(elapsed)
        total_ms += elapsed * 1000
        if decision.degraded:
            totals["degraded"] += 1
            degraded.inc()
        return decision_response(decision)

    if config.demoEnabled:
        @app.post("/v1/lab/sessions", status_code=201)
        async def create(payload: Experiment):
            now = monotonic()
            for identifier in list(sessions):
                if sessions[identifier].expires <= now:
                    del sessions[identifier]
            if len(sessions) >= 100:
                raise HTTPException(503, "Lab is full; retry later.")
            if payload.mode == "REDIS" and not config.redisEnabled:
                raise HTTPException(409, "Redis lab is disabled; set DASHBOARD_REDIS_ENABLED=true.")
            identifier = str(uuid4())
            prefix = f"rl:lab:{identifier}:"
            nodes = {
                node: Resilient(
                    injected_failure if payload.outage else build(payload.mode, payload.limit, payload.windowMillis, prefix),
                    payload.failurePolicy, 1, 3, 5000, 10, 20,
                )
                for node in ("A", "B")
            }
            sessions[identifier] = Session(now + 900, nodes, payload.outage)
            return dict(id=identifier, expiresInSeconds=900, simulated=False,
                        instanceScope="two independent limiter objects in one Python process")

        @app.post("/v1/lab/sessions/{identifier}/check")
        async def check(identifier: str, payload: LabClient):
            nonlocal in_flight
            session = sessions.get(identifier)
            if session is None or session.expires <= monotonic():
                sessions.pop(identifier, None)
                raise HTTPException(410, "Lab session expired; start a new experiment.")
            if in_flight >= 32:
                raise HTTPException(503, "Lab concurrency capacity reached.")
            if len(session.clients) >= 64 and payload.clientId not in session.clients:
                raise HTTPException(409, "Maximum 64 clients per experiment.")
            session.clients.add(payload.clientId)
            in_flight += 1
            limiter = session.nodes[payload.node]
            started = perf_counter()
            try:
                decision = await limiter(payload.clientId)
                return decision_response(
                    decision, node=payload.node, latencyMs=(perf_counter() - started) * 1000,
                    timestamp=int(time() * 1000), circuitOpen=limiter.circuit_open,
                    execution="INJECTED_BACKEND_FAILURE" if session.outage else "PYTHON_LIMITER",
                )
            finally:
                in_flight -= 1

        @app.delete("/v1/lab/sessions/{identifier}", status_code=204)
        async def delete(identifier: str):
            sessions.pop(identifier, None)
            return Response(status_code=204)

    @app.get("/actuator/health/liveness")
    async def liveness():
        return {"status": "UP"}

    @app.get("/actuator/health")
    @app.get("/actuator/health/readiness")
    async def health():
        circuit_open = getattr(app.state.production, "circuit_open", False)
        healthy = not circuit_open
        if app.state.redis is not None:
            try:
                await app.state.redis.ping()
            except RedisError:
                healthy = False
        status = "UP" if healthy else "DOWN"
        return JSONResponse(
            dict(status=status, components=dict(rateLimiter=dict(
                status=status, details=dict(mode=config.mode, redisCircuitOpen=circuit_open,
                                            failurePolicy=config.failurePolicy)))),
            status_code=200 if healthy else 503,
        )

    @app.get("/actuator/info")
    async def info():
        return {"runtime": "python"}

    @app.get("/actuator/prometheus")
    async def prometheus():
        return Response(generate_latest(registry), media_type=CONTENT_TYPE_LATEST)

    static = Path(os.getenv("RATELAB_STATIC_DIR", "frontend/dist"))
    if static.is_dir():
        app.mount("/", StaticFiles(directory=static, html=True), name="dashboard")
    return app


app = create_app()

