import { afterEach, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import App from "./App";
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});
it("labels demo data and records allowed and rejected decisions after a burst", async () => {
  render(<App />);
  expect(screen.getByText("SIMULATED DATA")).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: /Send burst/ }));
  await waitFor(() =>
    expect(screen.getAllByText("Rejected").length).toBeGreaterThan(1),
  );
  expect(
    screen.getByText("No Java service or Redis is being contacted.", {
      exact: false,
    }),
  ).toBeTruthy();
});

it("automatically verifies the hosted Java API and sends real API requests", async () => {
  const apiFetch = vi.fn(async (input: string, init?: RequestInit) => {
    const data = input.endsWith("/config")
      ? {
          mode: "REDIS",
          limit: 10,
          windowMillis: 10000,
          failurePolicy: "FAIL_CLOSED",
          demoEnabled: true,
          redisEnabled: true,
        }
      : input.endsWith("/metrics")
        ? {
            allowed: 0,
            rejected: 0,
            degraded: 0,
            meanLatencyMs: 0,
            scope: "production",
          }
        : input.endsWith("/sessions")
          ? { id: "test-session" }
          : { allowed: true, remaining: 9, retryAfterMs: 0, degraded: false };
    return new Response(JSON.stringify(data), {
      status:
        input.endsWith("/sessions") && init?.method === "POST" ? 201 : 200,
    });
  });
  vi.stubGlobal("fetch", apiFetch);
  render(<App requireBackend backendUrl="https://java.example.com" />);
  await screen.findByText("Connected to the Java lab.");
  expect(screen.queryByText("SIMULATED DATA")).toBeNull();
  await userEvent.click(screen.getByRole("button", { name: /Send request/ }));
  await waitFor(() =>
    expect(
      apiFetch.mock.calls.some(
        ([url, init]) =>
          url ===
            "https://java.example.com/v1/lab/sessions/test-session/check" &&
          init?.method === "POST",
      ),
    ).toBe(true),
  );
});

it("keeps requests disabled when the hosted API is down and never enables browser fallback", async () => {
  vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));
  render(<App requireBackend backendUrl="https://java.example.com" />);
  await screen.findByText("Java API is not connected.");
  expect(
    screen.getByRole("button", { name: /Send request/ }).matches(":disabled"),
  ).toBe(true);
  expect(screen.queryByText("SIMULATED DATA")).toBeNull();
  await userEvent.click(
    screen.getByRole("button", { name: "Connection settings" }),
  );
  expect(screen.queryByRole("button", { name: "Use browser demo" })).toBeNull();
});
