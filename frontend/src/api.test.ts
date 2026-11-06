import { afterEach, expect, it, vi } from "vitest";
import { Api, normalizeUrl } from "./api";
afterEach(() => vi.unstubAllGlobals());
it("treats HTTP 429 with a decision as a normal rejected request", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(
          JSON.stringify({
            allowed: false,
            remaining: 0,
            retryAfterMs: 900,
            degraded: false,
          }),
          { status: 429 },
        ),
      ),
  );
  expect(
    (await new Api("").production("client", new AbortController().signal))
      .allowed,
  ).toBe(false);
});
it("reports unavailable APIs instead of silently switching to fake decisions", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("network")));
  await expect(new Api("").config()).rejects.toThrow("unreachable");
});
it("does not treat a capacity response as a quota decision", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValue(
        new Response(JSON.stringify({ detail: "Lab full" }), { status: 503 }),
      ),
  );
  await expect(new Api("").config()).rejects.toThrow("Lab full");
});
it("rejects credentials and non-HTTP URLs", () => {
  expect(() => normalizeUrl("https://user:secret@example.com")).toThrow();
  expect(() => normalizeUrl("javascript:alert(1)")).toThrow();
  expect(normalizeUrl("https://example.com/")).toBe("https://example.com");
});
