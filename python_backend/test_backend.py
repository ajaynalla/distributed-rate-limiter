import asyncio
import os
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient
from redis.exceptions import RedisError

from python_backend.app import Config, create_app
from python_backend.limiter import Decision, MemoryWindow, Resilient


def test_window_boundary():
    async def run():
        now = 0
        limiter = MemoryWindow(1, 1000, lambda: now)
        assert (await limiter("a")).allowed
        assert not (await limiter("a")).allowed
        now = 1
        assert (await limiter("a")).allowed
    asyncio.run(run())


def test_circuit_recovery():
    async def run():
        now = 0
        broken = True

        async def delegate(client):
            if broken:
                raise RedisError("unavailable")
            return Decision(True, 0)

        limiter = Resilient(delegate, "FAIL_CLOSED", 0, 1, 1000, clock=lambda: now)
        assert await limiter("a") == Decision(False, 0, degraded=True)
        broken = False
        assert (await limiter("a")).degraded
        now = 1
        assert (await limiter("a")).allowed
        assert not limiter.circuit_open

    asyncio.run(run())


def test_production_contract():
    with TestClient(create_app(Config(limit=1, windowMillis=60000))) as client:
        path = "/v1/rate-limit/check"
        assert client.post(path, json={"clientId": ""}).status_code == 400
        first = client.post(path, json={"clientId": "a"}, headers={"X-Request-Id": "test"})
        assert first.json() == dict(allowed=True, remaining=0, retryAfterMs=0, degraded=False)
        assert first.headers["X-Request-Id"] == "test"
        rejected = client.post(path, json={"clientId": "a"})
        assert rejected.status_code == 429
        assert 1 <= int(rejected.headers["Retry-After"]) <= 60
        assert client.get("/v1/dashboard/metrics").json()["rejected"] == 1


@pytest.mark.parametrize("policy,allowed", [("FAIL_OPEN", True), ("FAIL_CLOSED", False)])
def test_lab_failure(policy, allowed):
    with TestClient(create_app(Config(demoEnabled=True))) as client:
        created = client.post("/v1/lab/sessions", json=dict(
            limit=10, windowMillis=60000, mode="IN_MEMORY", failurePolicy=policy, outage=True,
        ))
        assert created.status_code == 201
        path = "/v1/lab/sessions/" + created.json()["id"]
        for _ in range(4):
            result = client.post(path + "/check", json=dict(clientId="demo", node="A")).json()
            assert result["allowed"] is allowed and result["degraded"]
        assert result["circuitOpen"]
        assert client.delete(path).status_code == 204
        assert client.post(path + "/check", json=dict(clientId="demo", node="A")).status_code == 410


@pytest.mark.skipif(not os.getenv("RATELAB_TEST_REDIS"), reason="Requires a disposable Redis")
def test_redis_shared_across_app_instances():
    from concurrent.futures import ThreadPoolExecutor

    config = Config(mode="REDIS", windowMillis=60000)
    key = str(uuid4())
    with TestClient(create_app(config)) as a, TestClient(create_app(config)) as b:
        with ThreadPoolExecutor(max_workers=16) as pool:
            responses = list(pool.map(
                lambda i: (a if i % 2 else b).post("/v1/rate-limit/check", json={"clientId": key}),
                range(40),
            ))
    assert sum(r.status_code == 200 for r in responses) == 10
    assert sum(r.status_code == 429 for r in responses) == 30
    assert all(not r.json()["degraded"] for r in responses)
