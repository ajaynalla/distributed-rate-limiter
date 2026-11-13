"""Compare local HTTP servers with equal warmup, concurrency and input distribution."""
import argparse
import asyncio
import json
from time import perf_counter
from uuid import uuid4

import httpx


async def measure(origin, count, workers, hot):
    async with httpx.AsyncClient(base_url=origin, limits=httpx.Limits(max_connections=workers)) as client:
        async def run(size):
            prefix = str(uuid4())
            timings = []
            codes = {200: 0, 429: 0}
            jobs = iter(range(size))

            async def worker():
                for index in jobs:
                    started = perf_counter()
                    response = await client.post("/v1/rate-limit/check", json={
                        "clientId": prefix if hot else f"{prefix}-{index}",
                    })
                    timings.append((perf_counter() - started) * 1000)
                    response.raise_for_status() if response.status_code not in codes else None
                    if response.json()["degraded"]:
                        raise RuntimeError("Degraded decision invalidates benchmark")
                    codes[response.status_code] += 1

            started = perf_counter()
            await asyncio.gather(*(worker() for _ in range(workers)))
            elapsed = perf_counter() - started
            timings.sort()
            return {
                "requests": size, "rps": round(size / elapsed, 2), "status_counts": codes,
                **{f"p{p}_ms": round(timings[(len(timings) * p - 1) // 100], 3) for p in (50, 95, 99)},
            }

        await run(300)
        return await run(count)


async def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("java")
    parser.add_argument("python")
    parser.add_argument("--requests", type=int, default=2000)
    parser.add_argument("--workers", type=int, default=16)
    args = parser.parse_args()
    for hot in (False, True):
        for label, origin in (("java", args.java), ("python", args.python)):
            result = await measure(origin, args.requests, args.workers, hot)
            print(json.dumps(dict(runtime=label, hot_key=hot, concurrency=args.workers, **result)))


if __name__ == "__main__":
    asyncio.run(main())
