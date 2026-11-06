import { describe, expect, it } from "vitest";
import { DemoEngine, pool, stats } from "./model";
import type { Settings } from "./model";

const settings: Settings = {
  limit: 3,
  windowMillis: 1000,
  mode: "IN_MEMORY",
  failurePolicy: "FAIL_OPEN",
  outage: false,
};
describe("explicit browser simulation", () => {
  it("enforces quota and expires timestamps exactly at the boundary", () => {
    let now = 0;
    const demo = new DemoEngine(settings, () => now);
    expect(
      Array.from({ length: 5 }, () => demo.decide("a", "A").allowed),
    ).toEqual([true, true, true, false, false]);
    now = 999;
    expect(demo.decide("a", "A").retryAfterMs).toBe(1);
    now = 1000;
    expect(demo.decide("a", "A").remaining).toBe(2);
  });
  it("isolates clients and memory instances, but shares modeled Redis quota", () => {
    for (const mode of ["IN_MEMORY", "REDIS"] as const) {
      const demo = new DemoEngine({ ...settings, mode });
      for (let i = 0; i < 3; i++) demo.decide("a", "A");
      expect(demo.decide("a", "B").allowed).toBe(mode === "IN_MEMORY");
      expect(demo.decide("b", "A").allowed).toBe(true);
    }
  });
  it("marks modeled failure decisions degraded and opens its circuit", () => {
    for (const failurePolicy of ["FAIL_OPEN", "FAIL_CLOSED"] as const) {
      const demo = new DemoEngine({ ...settings, failurePolicy, outage: true });
      for (let i = 0; i < 3; i++) demo.decide("a", "A");
      const d = demo.decide("a", "A");
      expect(d.degraded).toBe(true);
      expect(d.circuitOpen).toBe(true);
      expect(d.allowed).toBe(failurePolicy === "FAIL_OPEN");
    }
  });
});
it("bounds concurrency and stops scheduling when aborted", async () => {
  let active = 0,
    peak = 0,
    completed = 0;
  const abort = new AbortController();
  await pool(
    Array.from({ length: 50 }),
    4,
    async () => {
      active++;
      peak = Math.max(peak, active);
      await new Promise((r) => setTimeout(r, 1));
      active--;
      completed++;
      if (completed === 8) abort.abort();
    },
    abort.signal,
  );
  expect(peak).toBe(4);
  expect(completed).toBeLessThan(50);
});
it("empty metrics are finite", () =>
  expect(stats([], Date.now())).toEqual({ throughput: 0, p95: 0 }));
