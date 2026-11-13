"""Sliding windows and per-limiter circuit state for the asyncio service."""
import asyncio
from collections import deque
from dataclasses import asdict, dataclass
from pathlib import Path
from time import monotonic

from redis.exceptions import RedisError

SCRIPT = Path(__file__).resolve().parents[1] / "src/main/resources/scripts/sliding_window.lua"


@dataclass(frozen=True)
class Decision:
    allowed: bool
    remaining: int
    retryAfterMs: int = 0
    degraded: bool = False

    def body(self):
        return asdict(self)


class MemoryWindow:
    def __init__(self, limit, window_ms, clock=monotonic):
        self.limit, self.window_ms, self.clock = limit, window_ms, clock
        self.clients = {}
        self.sweep_at = 0

    async def __call__(self, client):
        # No await in the mutation. A single event loop owns these deques.
        now = int(self.clock() * 1000)
        cutoff = now - self.window_ms
        if now >= self.sweep_at:
            self.clients = {key: queue for key, queue in self.clients.items()
                            if queue[-1] > cutoff}
            self.sweep_at = now + max(1000, self.window_ms)
        queue = self.clients.setdefault(client, deque())
        while queue and queue[0] <= cutoff:
            queue.popleft()
        if len(queue) >= self.limit:
            return Decision(False, 0, queue[0] + self.window_ms - now)
        queue.append(now)
        return Decision(True, self.limit - len(queue))


class RedisWindow:
    def __init__(self, script, limit, window_ms, prefix):
        self.script, self.limit, self.window_ms, self.prefix = script, limit, window_ms, prefix

    async def __call__(self, client):
        key = self.prefix + client
        allowed, remaining, retry = await self.script(
            keys=[key, key + ":seq"], args=[self.limit, self.window_ms]
        )
        return Decision(bool(allowed), remaining, retry)


async def injected_failure(client):
    raise RedisError("Injected lab failure")


class Resilient:
    def __init__(self, delegate, policy, retries, threshold, cooldown_ms,
                 base_backoff_ms=20, max_backoff_ms=200, clock=monotonic):
        self.delegate, self.policy, self.retries = delegate, policy, retries
        self.threshold, self.cooldown = threshold, cooldown_ms / 1000
        self.base_backoff, self.max_backoff = base_backoff_ms, max_backoff_ms
        self.clock = clock
        self.failures = 0
        self.open_until = None
        self.probe = False
        self.generation = 0

    @property
    def circuit_open(self):
        return self.open_until is not None

    def fallback(self):
        allowed = self.policy == "FAIL_OPEN"
        return Decision(allowed, -1 if allowed else 0, degraded=True)

    async def __call__(self, client):
        probing = self.circuit_open
        if probing:
            if self.clock() < self.open_until or self.probe:
                return self.fallback()
            self.probe = True
        generation = self.generation
        try:
            for attempt in range(self.retries + 1):
                try:
                    decision = await self.delegate(client)
                except RedisError:
                    if attempt < self.retries:
                        delay = min(self.max_backoff, self.base_backoff * 2**attempt)
                        await asyncio.sleep(delay / 1000)
                        continue
                    if generation == self.generation:
                        self.failures += 1
                        if probing or self.failures >= self.threshold:
                            self.open_until = self.clock() + self.cooldown
                            self.generation += 1
                    return self.fallback()
                if generation == self.generation:
                    self.failures = 0
                    self.open_until = None
                return decision
        finally:
            if probing:
                self.probe = False

