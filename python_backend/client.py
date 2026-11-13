"""Async client for the versioned production decision endpoint."""
from .limiter import Decision


class RateLabClient:
    """Uses a caller-owned httpx.AsyncClient for pooling and timeout configuration."""

    def __init__(self, http):
        self.http = http

    async def check(self, client_id):
        response = await self.http.post("/v1/rate-limit/check", json={"clientId": client_id})
        if response.status_code != 429:
            response.raise_for_status()
        body = response.json()
        if response.status_code == 429 and body.get("allowed") is not False:
            response.raise_for_status()
        return Decision(**body)
