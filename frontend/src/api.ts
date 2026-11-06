import type { Config, Decision, Metrics, NodeId, Settings } from "./model";

export class ApiError extends Error {
  constructor(
    message: string,
    public status?: number,
  ) {
    super(message);
  }
}
export function normalizeUrl(value: string) {
  if (!value.trim()) return "";
  const url = new URL(value.trim());
  if (
    !["http:", "https:"].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== "/"
  ) {
    throw new Error(
      "Enter an HTTP(S) origin without credentials, path, or query.",
    );
  }
  if (location.protocol === "https:" && url.protocol !== "https:")
    throw new Error("This HTTPS dashboard needs an HTTPS backend URL.");
  return url.origin;
}
export class Api {
  constructor(readonly base: string) {}
  private async request<T>(
    path: string,
    method = "GET",
    body?: unknown,
    signal?: AbortSignal,
  ): Promise<T> {
    let response: Response;
    try {
      response = await fetch(`${this.base}${path}`, {
        method,
        headers: body ? { "Content-Type": "application/json" } : {},
        body: body ? JSON.stringify(body) : undefined,
        signal: signal
          ? AbortSignal.any([signal, AbortSignal.timeout(12000)])
          : AbortSignal.timeout(12000),
      });
    } catch (e) {
      if (signal?.aborted) throw e;
      throw new ApiError(
        "Java API is unreachable. Check its URL, HTTPS, CORS, and service health.",
      );
    }
    const data = await response.json().catch(() => null);
    // HTTP 429 is a successful rate-limit decision only when it matches the API contract.
    if (
      response.ok ||
      (response.status === 429 && typeof data?.allowed === "boolean")
    ) {
      if (!data)
        throw new ApiError(
          "The endpoint did not return JSON. Check the backend URL.",
        );
      return data;
    }
    throw new ApiError(
      data?.detail || `API returned HTTP ${response.status}.`,
      response.status,
    );
  }
  config() {
    return this.request<Config>("/v1/dashboard/config");
  }
  metrics() {
    return this.request<Metrics>("/v1/dashboard/metrics");
  }
  create(settings: Settings, signal?: AbortSignal) {
    return this.request<{ id: string }>(
      "/v1/lab/sessions",
      "POST",
      settings,
      signal,
    );
  }
  check(id: string, clientId: string, node: NodeId, signal: AbortSignal) {
    return this.decision(
      `/v1/lab/sessions/${encodeURIComponent(id)}/check`,
      { clientId, node },
      signal,
    );
  }
  production(clientId: string, signal: AbortSignal) {
    return this.decision("/v1/rate-limit/check", { clientId }, signal);
  }
  private async decision(path: string, body: unknown, signal: AbortSignal) {
    const d = await this.request<Decision>(path, "POST", body, signal);
    if (
      typeof d.allowed !== "boolean" ||
      typeof d.degraded !== "boolean" ||
      !Number.isInteger(d.remaining) ||
      !Number.isFinite(d.retryAfterMs) ||
      d.retryAfterMs < 0
    )
      throw new ApiError("Invalid decision response from Java API.");
    return d;
  }
  async dispose(id: string) {
    await fetch(`${this.base}/v1/lab/sessions/${encodeURIComponent(id)}`, {
      method: "DELETE",
      signal: AbortSignal.timeout(3000),
    }).catch(() => {});
  }
}
