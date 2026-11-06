export type StorageMode = "IN_MEMORY" | "REDIS";
export type FailurePolicy = "FAIL_OPEN" | "FAIL_CLOSED";
export type Source = "demo" | "lab" | "production";
export type NodeId = "A" | "B";
export interface Settings {
  limit: number;
  windowMillis: number;
  mode: StorageMode;
  failurePolicy: FailurePolicy;
  outage: boolean;
}
export interface Decision {
  allowed: boolean;
  remaining: number;
  retryAfterMs: number;
  degraded: boolean;
  circuitOpen?: boolean;
  latencyMs?: number;
  timestamp?: number;
}
export interface Entry extends Decision {
  id: string;
  clientId: string;
  node: NodeId;
  at: number;
  elapsed: number;
  simulated: boolean;
}
export interface Config {
  mode: StorageMode;
  limit: number;
  windowMillis: number;
  failurePolicy: FailurePolicy;
  demoEnabled: boolean;
  redisEnabled: boolean;
}
export interface Metrics {
  allowed: number;
  rejected: number;
  degraded: number;
  meanLatencyMs: number;
  scope: string;
}

/** Educational browser model only. Never presented as Java, Redis, or a performance benchmark. */
export class DemoEngine {
  private windows = new Map<string, number[]>();
  private failures: Record<NodeId, number> = { A: 0, B: 0 };
  private openedAt: Partial<Record<NodeId, number>> = {};
  constructor(
    readonly settings: Settings,
    private clock = () => Date.now(),
  ) {}
  decide(client: string, node: NodeId): Decision {
    const now = this.clock();
    if (this.settings.outage) {
      const opened = this.openedAt[node];
      if (opened === undefined || now - opened >= 5000) {
        this.failures[node]++;
        if (this.failures[node] >= 3) this.openedAt[node] = now;
      }
      return {
        allowed: this.settings.failurePolicy === "FAIL_OPEN",
        remaining: this.settings.failurePolicy === "FAIL_OPEN" ? -1 : 0,
        retryAfterMs: 0,
        degraded: true,
        circuitOpen: this.openedAt[node] !== undefined,
      };
    }
    const key = `${this.settings.mode === "REDIS" ? "shared" : node}:${client}`;
    const recent = (this.windows.get(key) || []).filter(
      (t) => t > now - this.settings.windowMillis,
    );
    this.windows.set(key, recent);
    if (recent.length >= this.settings.limit)
      return {
        allowed: false,
        remaining: 0,
        retryAfterMs: recent[0] + this.settings.windowMillis - now,
        degraded: false,
      };
    recent.push(now);
    return {
      allowed: true,
      remaining: this.settings.limit - recent.length,
      retryAfterMs: 0,
      degraded: false,
    };
  }
}

export function stats(entries: Entry[], now: number) {
  const recent = entries.filter((e) => e.at > now - 10000);
  const latencies = entries.map((e) => e.elapsed).sort((a, b) => a - b);
  return {
    throughput: recent.length / 10,
    p95: latencies[Math.max(0, Math.ceil(latencies.length * 0.95) - 1)] || 0,
  };
}

export async function pool<T>(
  items: T[],
  concurrency: number,
  work: (item: T, index: number) => Promise<void>,
  signal: AbortSignal,
) {
  let next = 0;
  await Promise.all(
    Array.from({ length: Math.min(concurrency, items.length) }, async () => {
      while (!signal.aborted) {
        const i = next++;
        if (i >= items.length) break;
        await work(items[i], i);
      }
    }),
  );
}
