"""Verify real HTTP behavior of a deployed Java rate limiter; Python standard library only.

This does not create cloud resources or use credentials. It creates and deletes
isolated lab sessions. Failure injection exercises Java, never a browser model.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
import json
import math
import sys
import time
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from uuid import uuid4


class VerificationError(RuntimeError):
    pass


def origin(value: str) -> str:
    url = urlsplit(value.strip())
    if (url.scheme not in {"http", "https"} or not url.hostname or url.username or url.password
            or url.query or url.fragment or url.path not in {"", "/"}):
        raise VerificationError("Use an HTTP(S) origin without credentials, path, query, or fragment.")
    if url.scheme == "http" and url.hostname not in {"localhost", "127.0.0.1", "::1"}:
        raise VerificationError("Public deployment verification requires HTTPS.")
    return f"{url.scheme}://{url.netloc}"


class Client:
    def __init__(self, base: str, timeout: float = 20):
        self.base = origin(base)
        self.timeout = timeout

    def request(self, path: str, body=None, method: str | None = None, headers=None):
        encoded = None if body is None else json.dumps(body).encode()
        request_headers = {"User-Agent": "RateLab-DeploymentVerifier/1.0", **(headers or {})}
        if encoded is not None:
            request_headers["Content-Type"] = "application/json"
        request = Request(self.base + path, data=encoded,
                          method=method or ("GET" if body is None else "POST"), headers=request_headers)
        start = time.perf_counter()
        try:
            response = urlopen(request, timeout=self.timeout)
        except HTTPError as error:
            response = error  # 429 is a normal limiter decision; all other statuses are checked by the caller.
        except (URLError, TimeoutError, OSError):
            raise VerificationError("HTTP service is unreachable or timed out.") from None
        with response:
            raw = response.read(1_000_001)
            if len(raw) > 1_000_000:
                raise VerificationError("HTTP response exceeded the verification size limit.")
            try:
                data = json.loads(raw)
            except (ValueError, UnicodeDecodeError):
                data = raw.decode("utf-8", errors="replace")
            return response.status, data, response.headers, (time.perf_counter() - start) * 1000

    def json(self, path: str, body=None, expected=(200,)):
        status, data, headers, latency = self.request(path, body)
        if status not in expected or not isinstance(data, dict):
            raise VerificationError(f"{path}: expected JSON with status {expected}, received {status}.")
        return data, headers, latency

    def decision(self, path: str, body):
        status, data, headers, latency = self.request(path, body)
        if (status not in {200, 429} or not isinstance(data, dict)
                or type(data.get("allowed")) is not bool or type(data.get("degraded")) is not bool
                or type(data.get("remaining")) is not int
                or type(data.get("retryAfterMs")) not in {int, float}
                or not math.isfinite(data["retryAfterMs"]) or data["retryAfterMs"] < 0):
            raise VerificationError(f"{path}: invalid decision or unexpected HTTP status {status}.")
        if (status == 200) != data["allowed"]:
            raise VerificationError("HTTP status contradicts the rate-limit decision.")
        if status == 429 and not str(headers.get("Retry-After", "")).isdigit():
            raise VerificationError("Rejected decision is missing Retry-After.")
        return data, latency


def wait_healthy(client: Client, attempts: int, delay: float):
    for attempt in range(attempts):
        try:
            health, _, _ = client.json("/actuator/health/liveness")
            if health.get("status") == "UP":
                return
        except VerificationError:
            pass
        if attempt + 1 < attempts:
            time.sleep(delay)
    raise VerificationError("Liveness did not become UP within the startup retry budget.")


def experiment(client: Client, mode: str, outage=False, policy="FAIL_CLOSED"):
    created, _, _ = client.json("/v1/lab/sessions", {
        "limit": 10, "windowMillis": 60000, "mode": mode,
        "failurePolicy": policy, "outage": outage,
    }, expected=(201,))
    session_id = created.get("id")
    if not isinstance(session_id, str) or not session_id or any(ch not in "0123456789abcdef-" for ch in session_id):
        raise VerificationError("Invalid isolated lab session ID.")
    return f"/v1/lab/sessions/{session_id}"


def verify(client: Client, frontend: str | None = None, cors_origin: str | None = None):
    config, _, _ = client.json("/v1/dashboard/config")
    if config.get("mode") != "REDIS" or config.get("demoEnabled") is not True or config.get("redisEnabled") is not True:
        raise VerificationError("Full deployment needs production REDIS mode and both Java lab capabilities enabled.")
    checks = []
    # The existing production endpoint must execute a genuine, non-degraded Redis decision.
    production, latency = client.decision("/v1/rate-limit/check", {"clientId": f"verify-{uuid4()}"})
    if not production["allowed"] or production["degraded"]:
        raise VerificationError("Production API did not return a healthy real Redis decision.")
    checks.append({"check": "production_redis_decision", "passed": True, "roundTripMs": round(latency, 2)})
    for mode, expected_allowed in (("IN_MEMORY", 20), ("REDIS", 10)):
        path = experiment(client, mode)
        try:
            started = time.monotonic()
            with ThreadPoolExecutor(max_workers=16) as executor:
                results = list(executor.map(lambda i: client.decision(path + "/check", {
                    "clientId": "shared-client", "node": "A" if i % 2 == 0 else "B",
                }), range(20)))
            if time.monotonic() - started >= 60:
                raise VerificationError("Concurrency test crossed its 60-second window; result is inconclusive.")
            allowed = sum(d["allowed"] for d, _ in results)
            if allowed != expected_allowed or any(d["degraded"] for d, _ in results):
                raise VerificationError(f"{mode}: expected {expected_allowed} non-degraded admissions, received {allowed}.")
            checks.append({"check": f"{mode.lower()}_a_b_concurrency", "passed": True,
                           "allowed": allowed, "rejected": 20 - allowed})
        finally:
            client.request(path, method="DELETE")
    path = experiment(client, "REDIS")
    try:
        with ThreadPoolExecutor(max_workers=16) as executor:
            results = list(executor.map(lambda i: client.decision(path + "/check", {
                "clientId": f"client-{i % 4}", "node": "A" if i % 2 == 0 else "B",
            }), range(60)))
        if sum(d["allowed"] for d, _ in results) != 40 or any(d["degraded"] for d, _ in results):
            raise VerificationError("Distinct Redis clients did not receive independent quotas.")
        checks.append({"check": "redis_multi_client_isolation", "passed": True, "clients": 4, "allowed": 40, "rejected": 20})
    finally:
        client.request(path, method="DELETE")
    for policy in ("FAIL_OPEN", "FAIL_CLOSED"):
        path = experiment(client, "IN_MEMORY", outage=True, policy=policy)
        try:
            for _ in range(4):
                decision, _ = client.decision(path + "/check", {"clientId": "injected-fault", "node": "A"})
                if not decision["degraded"] or decision["allowed"] != (policy == "FAIL_OPEN"):
                    raise VerificationError(f"{policy}: unexpected degraded policy decision.")
            if decision.get("circuitOpen") is not True:
                raise VerificationError("Java circuit breaker did not open after exhausted calls.")
            checks.append({"check": f"{policy.lower()}_injected_failure", "passed": True})
        finally:
            client.request(path, method="DELETE")
    metrics, _, _ = client.json("/v1/dashboard/metrics")
    for field in ("allowed", "rejected", "degraded", "meanLatencyMs"):
        if not isinstance(metrics.get(field), (int, float)) or not math.isfinite(metrics[field]) or metrics[field] < 0:
            raise VerificationError("Invalid production monitoring metrics.")
    checks.append({"check": "production_metrics", "passed": True})
    if cors_origin and origin(cors_origin) != client.base:
        expected = origin(cors_origin)
        status, _, headers, _ = client.request("/v1/lab/sessions", method="OPTIONS", headers={
            "Origin": expected, "Access-Control-Request-Method": "POST",
            "Access-Control-Request-Headers": "content-type",
        })
        if status != 200 or headers.get("Access-Control-Allow-Origin") != expected:
            raise VerificationError("CORS preflight did not allow the deployed frontend origin.")
        checks.append({"check": "frontend_cors_preflight", "passed": True})
    if frontend:
        status, html, _, _ = Client(frontend).request("/")
        if status != 200 or not isinstance(html, str) or "RateLab" not in html or "<script" not in html:
            raise VerificationError("Frontend URL did not serve the expected application HTML.")
        checks.append({"check": "frontend_html_reachable", "passed": True,
                       "scope": "HTML only; browser interaction must be verified separately"})
    return checks


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("backend", help="Actual Java origin returned by your host")
    parser.add_argument("--frontend", help="Actual public frontend origin")
    parser.add_argument("--origin", help="Frontend origin to verify in a CORS preflight")
    parser.add_argument("--health-only", action="store_true", help="Read-only liveness check; no lab traffic")
    parser.add_argument("--attempts", type=int, default=12)
    parser.add_argument("--delay", type=float, default=5)
    args = parser.parse_args(argv)
    try:
        if not 1 <= args.attempts <= 30 or not 0 <= args.delay <= 10:
            raise VerificationError("Startup retry budget must be 1–30 attempts with 0–10 seconds between attempts.")
        client = Client(args.backend)
        wait_healthy(client, args.attempts, args.delay)
        checks = [{"check": "liveness", "passed": True}]
        if not args.health_only:
            checks.extend(verify(client, args.frontend, args.origin or args.frontend))
        print(json.dumps({"passed": True, "backend": client.base, "checks": checks}, indent=2))
        return 0
    except VerificationError as error:
        print(json.dumps({"passed": False, "error": str(error)}, indent=2))
        return 1


if __name__ == "__main__":
    sys.exit(main())
