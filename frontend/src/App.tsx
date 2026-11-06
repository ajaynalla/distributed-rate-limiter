import { useEffect, useRef, useState } from "react";
import {
  Activity,
  ArrowDown,
  ArrowRight,
  ArrowUpRight,
  BookOpen,
  Check,
  CheckCircle2,
  ChevronRight,
  CircleHelp,
  Clock3,
  Database,
  ExternalLink,
  Gauge,
  Github,
  Layers,
  Network,
  Play,
  Plus,
  Radio,
  RotateCcw,
  Server,
  Settings2,
  ShieldCheck,
  ShieldOff,
  Square,
  Terminal,
  Waves,
  X,
  XCircle,
  Zap,
} from "lucide-react";
import { Api, ApiError, normalizeUrl } from "./api";
import { DemoEngine, pool, stats } from "./model";
import type { Config, Entry, Metrics, NodeId, Settings, Source } from "./model";
import { TrafficChart, WindowChart } from "./Charts";

const initial: Settings = {
  limit: 10,
  windowMillis: 10000,
  mode: "IN_MEMORY",
  failurePolicy: "FAIL_OPEN",
  outage: false,
};
const repo = "https://github.com/ajaynalla/distributed-rate-limiter";
const tabs = [
  { id: "overview", name: "Overview", icon: Gauge },
  { id: "concurrency", name: "Concurrency", icon: Network },
  { id: "storage", name: "Storage & resilience", icon: Database },
  { id: "guide", name: "How it works", icon: BookOpen },
];
const number = (v: number) =>
  v.toLocaleString(undefined, { maximumFractionDigits: 1 });

export default function App() {
  const [tab, setTab] = useState("overview");
  const [settings, setSettings] = useState<Settings>(initial);
  const [source, setSource] = useState<Source>("demo");
  const [client, setClient] = useState("client-001");
  const [route, setRoute] = useState<"A" | "B" | "both">("A");
  const [entries, setEntries] = useState<Entry[]>([]);
  const [totals, setTotals] = useState({
    allowed: 0,
    rejected: 0,
    degraded: 0,
  });
  const [config, setConfig] = useState<Config | null>(null);
  const [fleet, setFleet] = useState<Metrics | null>(null);
  const [apiUrl, setApiUrl] = useState(import.meta.env.VITE_API_BASE_URL || "");
  const [dialog, setDialog] = useState(false);
  const [connecting, setConnecting] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [metricError, setMetricError] = useState(false);
  const [clients, setClients] = useState(4);
  const [perClient, setPerClient] = useState(15);
  const [progress, setProgress] = useState({ done: 0, total: 0 });
  const [lastRun, setLastRun] = useState<{
    allowed: number;
    rejected: number;
    elapsed: number;
  } | null>(null);
  const [now, setNow] = useState(Date.now());
  const [logFilter, setLogFilter] = useState("all");
  const api = useRef(new Api(apiUrl));
  const engine = useRef(new DemoEngine(initial));
  const session = useRef<string | null>(null);
  const controller = useRef<AbortController | null>(null);
  const runLock = useRef(false);
  const seq = useRef(0);
  const sessionStart = useRef(Date.now());

  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 250);
    return () => clearInterval(timer);
  }, []);
  useEffect(
    () => () => {
      controller.current?.abort();
    },
    [],
  );
  useEffect(() => {
    if (source === "demo") {
      setFleet(null);
      return;
    }
    let active = true;
    const poll = () =>
      api.current
        .metrics()
        .then((m) => {
          if (active) {
            setFleet(m);
            setMetricError(false);
          }
        })
        .catch(() => {
          if (active) setMetricError(true);
        });
    poll();
    const timer = setInterval(poll, 5000);
    return () => {
      active = false;
      clearInterval(timer);
    };
  }, [source, apiUrl]);

  function resetExperiment(next: Settings = settings) {
    if (session.current) void api.current.dispose(session.current);
    session.current = null;
    engine.current = new DemoEngine(next);
    setEntries([]);
    setTotals({ allowed: 0, rejected: 0, degraded: 0 });
    setLastRun(null);
    setProgress({ done: 0, total: 0 });
    setError("");
    sessionStart.current = Date.now();
  }
  function change(patch: Partial<Settings>) {
    const next = { ...settings, ...patch };
    setSettings(next);
    resetExperiment(next);
  }
  async function connect() {
    setConnecting(true);
    setError("");
    try {
      const base = normalizeUrl(apiUrl);
      const next = new Api(base);
      const c = await next.config();
      if (!["IN_MEMORY", "REDIS"].includes(c.mode) || !Number.isFinite(c.limit))
        throw new Error("This is not a compatible Java rate limiter API.");
      resetExperiment();
      api.current = next;
      setApiUrl(base);
      setConfig(c);
      const opts = c.demoEnabled
        ? {
            ...settings,
            mode: c.redisEnabled ? settings.mode : ("IN_MEMORY" as const),
          }
        : {
            limit: c.limit,
            windowMillis: c.windowMillis,
            mode: c.mode,
            failurePolicy: c.failurePolicy,
            outage: false,
          };
      setSettings(opts);
      engine.current = new DemoEngine(opts);
      setSource(c.demoEnabled ? "lab" : "production");
      setDialog(false);
    } catch (e) {
      setError((e as Error).message);
    } finally {
      setConnecting(false);
    }
  }
  async function run(kind: "one" | "burst" | "concurrent") {
    if (runLock.current) return;
    if (!/^[a-zA-Z0-9_.-]{1,48}$/.test(client)) {
      setError(
        "Client ID must be 1–48 letters, numbers, dots, dashes, or underscores.",
      );
      return;
    }
    runLock.current = true;
    setBusy(true);
    setError("");
    setLastRun(null);
    const abort = new AbortController();
    controller.current = abort;
    const items =
      kind === "concurrent"
        ? Array.from(
            { length: clients * perClient },
            (_, i) => `${client}-${(i % clients) + 1}`,
          )
        : Array.from(
            { length: kind === "one" ? 1 : Math.min(settings.limit + 5, 105) },
            () => client,
          );
    setProgress({ done: 0, total: items.length });
    const started = performance.now();
    let allowed = 0,
      rejected = 0,
      done = 0;
    try {
      if (source === "lab" && !session.current)
        session.current = (await api.current.create(settings, abort.signal)).id;
      await pool(
        items,
        kind === "one" ? 1 : 16,
        async (id, i) => {
          const node: NodeId =
            source === "production"
              ? "A"
              : route === "both"
                ? i % 2
                  ? "B"
                  : "A"
                : route;
          const start = performance.now();
          const decision =
            source === "demo"
              ? engine.current.decide(id, node)
              : source === "lab"
                ? await api.current.check(
                    session.current!,
                    id,
                    node,
                    abort.signal,
                  )
                : await api.current.production(id, abort.signal);
          if (abort.signal.aborted) return;
          const entry: Entry = {
            ...decision,
            id: `${++seq.current}`,
            clientId: id,
            node,
            at: Date.now(),
            elapsed: performance.now() - start,
            simulated: source === "demo",
          };
          decision.allowed ? allowed++ : rejected++;
          setEntries((old) => [entry, ...old].slice(0, 500));
          setTotals((old) => ({
            allowed: old.allowed + Number(decision.allowed),
            rejected: old.rejected + Number(!decision.allowed),
            degraded: old.degraded + Number(decision.degraded),
          }));
          setProgress({ done: ++done, total: items.length });
          // Yield to paint during browser demo bursts; this is not a synthetic network latency.
          if (source === "demo")
            await new Promise((resolve) => setTimeout(resolve, 12));
        },
        abort.signal,
      );
    } catch (e) {
      abort.abort();
      if (e instanceof ApiError && e.status === 410) {
        session.current = null;
        setError(
          "Your lab session expired. Start a new experiment before sending more requests.",
        );
      } else if ((e as Error).name !== "AbortError")
        setError((e as Error).message);
    } finally {
      setLastRun({ allowed, rejected, elapsed: performance.now() - started });
      setBusy(false);
      runLock.current = false;
    }
  }

  const total = totals.allowed + totals.rejected;
  const computed = stats(entries, now);
  const effective = source === "production" && config ? config : settings;
  const last = entries.find(
    (e) => e.clientId === client && (route === "both" || e.node === route),
  );
  const remaining = last
    ? last.degraded
      ? "Unknown"
      : now - last.at > effective.windowMillis
        ? "Recheck"
        : last.remaining
    : "—";
  const shownLogs = entries
    .filter(
      (e) =>
        logFilter === "all" ||
        (logFilter === "allowed" && e.allowed) ||
        (logFilter === "rejected" && !e.allowed) ||
        (logFilter === "degraded" && e.degraded),
    )
    .slice(0, 40);
  const label =
    source === "demo"
      ? "Browser demo"
      : source === "lab"
        ? "Java API · isolated lab"
        : "Java API · production";

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <a
          href="#"
          className="brand"
          onClick={(e) => {
            e.preventDefault();
            setTab("overview");
          }}
        >
          <span className="brand-mark">
            <Waves size={24} />
          </span>
          RateLab<span className="brand-dot">.</span>
        </a>
        <div className="workspace">
          <div className="avatar">AN</div>
          <div>
            <strong>Distributed rate limiter</strong>
            <span>Engineering playground</span>
          </div>
          <span className="workspace-chevron">⌄</span>
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav aria-label="Main navigation">
          {tabs.map(({ id, name, icon: Icon }) => (
            <button
              key={id}
              onClick={() => setTab(id)}
              className={tab === id ? "nav-item active" : "nav-item"}
            >
              <Icon size={18} />
              {name}
              {tab === id && <span className="nav-active-dot" />}
            </button>
          ))}
        </nav>
        <div className="sidebar-note">
          <div className="sidebar-note-icon">
            <Layers size={18} />
          </div>
          <strong>Small system. Real concepts.</strong>
          <p>
            Explore atomicity, contention, and the tradeoffs behind every
            request.
          </p>
          <button onClick={() => setTab("guide")}>
            Explore the architecture <ArrowUpRight size={14} />
          </button>
        </div>
        <div className="sidebar-bottom">
          <a href={repo} target="_blank" rel="noreferrer">
            <Github size={17} />
            Source on GitHub
            <ArrowUpRight size={14} />
          </a>
          <div>
            <span className="tiny-dot" />
            Java 21 <span className="dot-sep">·</span> Spring Boot{" "}
            <span className="dot-sep">·</span> Redis
          </div>
        </div>
      </aside>
      <div className="main-shell">
        <header className="topbar">
          <div className="breadcrumb">
            Workspace
            <ChevronRight size={14} />
            <strong>{tabs.find((t) => t.id === tab)?.name}</strong>
          </div>
          <div className="top-actions">
            <span
              className={`source-chip ${source === "demo" ? "demo-chip" : ""}`}
            >
              <span className="tiny-dot" />
              {label}
            </span>
            <button
              className="icon-button"
              title="How it works"
              aria-label="How it works"
              onClick={() => setTab("guide")}
            >
              <CircleHelp size={19} />
            </button>
            <div className="top-avatar">AN</div>
          </div>
        </header>
        <main>
          <div className="page-heading">
            <div>
              <div className="eyebrow">
                DISTRIBUTED SYSTEMS / INTERACTIVE LAB
              </div>
              <h1>
                {tab === "overview"
                  ? "Every request tells a story."
                  : tab === "concurrency"
                    ? "Find the limit. Together."
                    : tab === "storage"
                      ? "One limit. Multiple instances."
                      : "Built to be understood."}
              </h1>
              <p>
                {tab === "overview"
                  ? "Send traffic, watch the window slide, and see rate limiting in action."
                  : tab === "concurrency"
                    ? "Race concurrent requests and see how per-client isolation holds up."
                    : tab === "storage"
                      ? "Explore shared state, local quotas, and what happens when a backend fails."
                      : "A small, explainable Java system with explicit engineering tradeoffs."}
              </p>
            </div>
            <button
              className="button secondary connect-button"
              disabled={busy}
              onClick={() => {
                setError("");
                setDialog(true);
              }}
            >
              <Server size={16} />
              {source === "demo"
                ? "Connect Java backend"
                : "Connection settings"}
              <ArrowUpRight size={15} />
            </button>
          </div>
          <div
            className={`mode-banner ${source !== "demo" ? "live-banner" : ""}`}
          >
            <span className="banner-symbol">
              {source === "demo" ? <Layers size={17} /> : <Radio size={17} />}
            </span>
            <div>
              <strong>
                {source === "demo"
                  ? "Browser simulation is active."
                  : source === "lab"
                    ? "Connected to the Java lab."
                    : "Connected to the production API."}
              </strong>{" "}
              {source === "demo"
                ? "Decisions run locally in your browser. No Java service or Redis is being contacted."
                : source === "lab"
                  ? "Real limiter classes; A and B are independent limiter objects in one JVM. Lab keys are isolated."
                  : "Requests use the original endpoint and deployment configuration."}
            </div>
            {source === "demo" && (
              <span className="outline-tag">SIMULATED DATA</span>
            )}
          </div>
          {error && !dialog && (
            <div role="alert" className="error-banner">
              <XCircle size={17} />
              {error}
              <button
                className="icon-button"
                aria-label="Dismiss error"
                onClick={() => setError("")}
              >
                <X size={15} />
              </button>
            </div>
          )}
          {tab !== "guide" && (
            <>
              <div className="section-intro">
                <span>
                  <span className="tiny-dot" />
                  SESSION TELEMETRY
                </span>
                <span>
                  Since{" "}
                  {new Date(sessionStart.current).toLocaleTimeString([], {
                    hour: "2-digit",
                    minute: "2-digit",
                  })}{" "}
                  · this dashboard only
                  <button
                    disabled={busy}
                    onClick={() => resetExperiment()}
                    className="text-button"
                  >
                    <RotateCcw size={13} />
                    New experiment
                  </button>
                </span>
              </div>
              <section className="metrics-grid" aria-label="Session metrics">
                {[
                  {
                    name: "Total requests",
                    value: number(total),
                    hint: `${totals.degraded} degraded decisions`,
                    icon: Activity,
                    type: "",
                  },
                  {
                    name: "Allowed",
                    value: number(totals.allowed),
                    hint: `${total ? ((totals.allowed / total) * 100).toFixed(1) : "0.0"}% admission rate`,
                    icon: CheckCircle2,
                    type: "green",
                  },
                  {
                    name: "Rejected",
                    value: number(totals.rejected),
                    hint: "HTTP 429 · quota or failure policy",
                    icon: ShieldOff,
                    type: "red",
                  },
                  {
                    name: "Throughput",
                    value: number(computed.throughput),
                    unit: "req/s",
                    hint: "Rolling 10s · last 500 decisions",
                    icon: Zap,
                    type: "",
                  },
                  {
                    name: "P95 round trip",
                    value: computed.p95.toFixed(1),
                    unit: "ms",
                    hint:
                      source === "demo"
                        ? "Browser execution · not a benchmark"
                        : "Browser → API · last 500 decisions",
                    icon: Clock3,
                    type: "",
                  },
                ].map((m) => (
                  <article className={`metric ${m.type}`} key={m.name}>
                    <div className="metric-top">
                      <span>{m.name}</span>
                      <m.icon size={16} />
                    </div>
                    <div className="metric-value">
                      {m.value}
                      <small>{m.unit}</small>
                    </div>
                    <div className="metric-hint">{m.hint}</div>
                  </article>
                ))}
              </section>
            </>
          )}

          {tab === "overview" && (
            <div className="dashboard-grid">
              <section className="card simulator">
                <div className="card-heading">
                  <div>
                    <span className="section-number">01</span>
                    <h2>Request simulator</h2>
                  </div>
                  <Settings2 size={17} />
                </div>
                <p className="card-subtitle">
                  Give a client a quota. See what gets through.
                </p>
                <fieldset disabled={busy}>
                  <label>
                    Client ID
                    <input
                      value={client}
                      onChange={(e) => setClient(e.target.value)}
                      maxLength={48}
                      placeholder="client-001"
                    />
                  </label>
                  <div className="form-row">
                    <label>
                      Request limit
                      <input
                        aria-label="Request limit"
                        type="number"
                        min={1}
                        max={100}
                        value={effective.limit}
                        disabled={source === "production"}
                        onChange={(e) => {
                          const n = Number(e.target.value);
                          if (n >= 1 && n <= 100) change({ limit: n });
                        }}
                      />
                      <span className="input-note">requests / window</span>
                    </label>
                    <label>
                      Window duration
                      <select
                        value={effective.windowMillis}
                        disabled={source === "production"}
                        onChange={(e) =>
                          change({ windowMillis: Number(e.target.value) })
                        }
                      >
                        {[
                          1000,
                          5000,
                          10000,
                          30000,
                          60000,
                          effective.windowMillis,
                        ]
                          .filter((v, i, a) => a.indexOf(v) === i)
                          .sort((a, b) => a - b)
                          .map((n) => (
                            <option key={n} value={n}>
                              {n / 1000} seconds
                            </option>
                          ))}
                      </select>
                    </label>
                  </div>
                  <label>
                    Route requests to
                    <select
                      value={route}
                      disabled={source === "production"}
                      onChange={(e) => setRoute(e.target.value as typeof route)}
                    >
                      <option value="A">Instance A</option>
                      <option value="B">Instance B</option>
                      <option value="both">Round-robin · A + B</option>
                    </select>
                  </label>
                  <div className="config-summary">
                    <span>
                      <Database size={14} />
                      {effective.mode === "REDIS"
                        ? "Redis · shared window"
                        : "In-memory · local window"}
                    </span>
                    <button
                      className="text-button"
                      onClick={() => setTab("storage")}
                    >
                      Configure
                      <ArrowRight size={13} />
                    </button>
                  </div>
                  <button
                    className="button primary full"
                    onClick={() => run("one")}
                  >
                    <Plus size={17} />
                    Send request<kbd>+1</kbd>
                  </button>
                  <button
                    className="button secondary full"
                    onClick={() => run("burst")}
                  >
                    <Zap size={16} />
                    Send burst{" "}
                    <span>{Math.min(effective.limit + 5, 105)} requests</span>
                  </button>
                </fieldset>
                <p className="microcopy">
                  <ShieldCheck size={13} />
                  {source === "production"
                    ? "Uses the existing production quota."
                    : "Changing quota or storage starts a fresh experiment."}
                </p>
              </section>
              <div className="visuals-stack">
                <section className="card traffic-card">
                  <div className="card-heading">
                    <div>
                      <span className="section-number">02</span>
                      <h2>Traffic overview</h2>
                      <span className="live-label">
                        <span className="tiny-dot" />
                        Live
                      </span>
                    </div>
                    <span className="period-tag">Last 60 seconds</span>
                  </div>
                  <div className="chart-legend">
                    <span>
                      <i />
                      Allowed
                    </span>
                    <span>
                      <i className="red-key" />
                      Rejected
                    </span>
                    <span className="legend-right">
                      Requests / 2s · last 500
                    </span>
                  </div>
                  <TrafficChart entries={entries} now={now} />
                </section>
                <section className="card sliding-card">
                  <div className="card-heading">
                    <div>
                      <span className="section-number">03</span>
                      <h2>Sliding window</h2>
                    </div>
                    <span className="mono muted">{client}</span>
                  </div>
                  <div className="window-summary">
                    <div>
                      <strong>{remaining}</strong>
                      <span>
                        remaining in latest response{last && ` · ${last.node}`}
                      </span>
                    </div>
                    <span className="window-badge">
                      <Clock3 size={13} />
                      {effective.windowMillis / 1000}s window
                    </span>
                  </div>
                  <WindowChart
                    entries={entries}
                    now={now}
                    windowMillis={effective.windowMillis}
                    clientId={client}
                  />
                  <p className="microcopy">
                    Browser-observed attempts slide left as they age. Only
                    accepted, non-degraded requests consume quota. Concurrent
                    responses can arrive out of order; quota is a response
                    snapshot.
                  </p>
                </section>
              </div>
            </div>
          )}

          {tab === "concurrency" && (
            <section className="card experiment-card">
              <div className="card-heading">
                <div>
                  <Network size={19} />
                  <h2>Concurrency runner</h2>
                </div>
                <span className="period-tag">16 concurrent HTTP workers</span>
              </div>
              <p className="card-subtitle">
                Requests share a client quota. Distinct clients get independent
                windows. Run against A + B to compare shared and local state.
              </p>
              <fieldset disabled={busy}>
                <div className="runner-form">
                  <label>
                    Client prefix
                    <input
                      value={client}
                      onChange={(e) => setClient(e.target.value)}
                      maxLength={48}
                    />
                  </label>
                  <label>
                    Distinct clients
                    <input
                      type="number"
                      min={1}
                      max={20}
                      value={clients}
                      onChange={(e) =>
                        setClients(Math.min(20, Math.max(1, +e.target.value)))
                      }
                    />
                  </label>
                  <label>
                    Requests per client
                    <input
                      type="number"
                      min={1}
                      max={20}
                      value={perClient}
                      onChange={(e) =>
                        setPerClient(Math.min(20, Math.max(1, +e.target.value)))
                      }
                    />
                  </label>
                  <label>
                    Target instances
                    <select
                      disabled={source === "production"}
                      value={route}
                      onChange={(e) => setRoute(e.target.value as typeof route)}
                    >
                      <option value="A">Instance A</option>
                      <option value="B">Instance B</option>
                      <option value="both">A + B · round-robin</option>
                    </select>
                  </label>
                </div>
              </fieldset>
              <div className="runner-summary">
                <span>
                  <strong>{clients * perClient}</strong> attempts ·{" "}
                  {effective.limit} quota / client /{" "}
                  {effective.windowMillis / 1000}s · {effective.mode}
                </span>
                <div>
                  {busy && (
                    <button
                      className="button secondary"
                      onClick={() => controller.current?.abort()}
                    >
                      <Square size={14} />
                      Stop
                    </button>
                  )}
                  <button
                    disabled={busy}
                    className="button primary"
                    onClick={() => run("concurrent")}
                  >
                    <Play size={15} />
                    Run concurrency test
                  </button>
                </div>
              </div>
              <progress
                max={progress.total || 1}
                value={progress.done}
                aria-label="Test progress"
              />
              <div className="runner-results">
                <span>
                  {progress.done} / {progress.total} completed
                </span>
                {lastRun && (
                  <strong>
                    {lastRun.allowed} allowed · {lastRun.rejected} rejected ·{" "}
                    {lastRun.elapsed.toFixed(0)}ms wall time
                  </strong>
                )}
              </div>
              <div className="info-panel">
                <CircleHelp size={18} />
                <p>
                  For a clear shared-key test, use{" "}
                  <b>1 client, 20 requests, limit 10, window 60s</b> and route
                  to <b>A + B</b>. In-memory admits up to 10 per instance. Redis
                  admits 10 total. Multi-client runs use names like{" "}
                  <code>{client}-1</code>. Use a new experiment between
                  comparisons; slow runs may cross a window boundary.
                </p>
              </div>
            </section>
          )}

          {tab === "storage" && (
            <div className="storage-grid">
              <section className="card">
                <div className="card-heading">
                  <div>
                    <Database size={19} />
                    <h2>Where does quota live?</h2>
                  </div>
                </div>
                <p className="card-subtitle">
                  Two independent limiter objects, one client ID. The storage
                  boundary changes the outcome.
                </p>
                <fieldset disabled={busy || source === "production"}>
                  <div className="storage-options">
                    {(["IN_MEMORY", "REDIS"] as const).map((mode) => (
                      <button
                        key={mode}
                        disabled={
                          mode === "REDIS" &&
                          source === "lab" &&
                          !config?.redisEnabled
                        }
                        className={`storage-option ${settings.mode === mode ? "selected" : ""}`}
                        onClick={() => change({ mode })}
                      >
                        {mode === "REDIS" ? (
                          <Database size={22} />
                        ) : (
                          <Server size={22} />
                        )}
                        <strong>
                          {mode === "REDIS" ? "Redis" : "In-memory"}
                        </strong>
                        <span>
                          {mode === "REDIS"
                            ? "A shared, atomic window"
                            : "A local window per instance"}
                        </span>
                        {settings.mode === mode && (
                          <Check size={17} className="selection-check" />
                        )}
                      </button>
                    ))}
                  </div>
                </fieldset>
                <div className="architecture-mini">
                  <span>Client</span>
                  <ArrowRight size={17} />
                  <div>
                    <span>Instance A</span>
                    <span>Instance B</span>
                  </div>
                  <ArrowRight size={17} />
                  <div className="mini-store">
                    {effective.mode === "REDIS" ? (
                      <>
                        <Database size={22} />
                        Shared ZSET + Lua
                      </>
                    ) : (
                      <>
                        <Server size={22} />
                        Separate local queues
                      </>
                    )}
                  </div>
                </div>
                <p className="explanation">
                  {effective.mode === "REDIS"
                    ? "Redis executes eviction, counting, and recording in one Lua script. Both limiter objects use the same session key and the Redis server clock."
                    : "Each limiter uses ConcurrentHashMap and per-client synchronized windows. A and B have separate maps, so the same client can consume a full quota on each."}
                </p>
                {source === "demo" && (
                  <p className="callout">
                    Browser demo models these boundaries. Select a connected
                    Java lab for real Redis commands.
                  </p>
                )}
                {source === "lab" && !config?.redisEnabled && (
                  <p className="callout">
                    Redis lab is disabled on this service. Enable
                    DASHBOARD_REDIS_ENABLED and configure a reachable Redis
                    host.
                  </p>
                )}
                <button
                  className="button secondary"
                  onClick={() => {
                    setTab("overview");
                    setRoute("both");
                  }}
                >
                  Try across A + B<ArrowRight size={15} />
                </button>
              </section>
              <section className="card">
                <div className="card-heading">
                  <div>
                    <ShieldCheck size={19} />
                    <h2>When a backend fails</h2>
                  </div>
                  <span className="outline-tag">LAB ONLY</span>
                </div>
                <p className="card-subtitle">
                  Choose availability or protection, then observe degraded
                  decisions and the circuit breaker.
                </p>
                <fieldset disabled={busy || source === "production"}>
                  <label>
                    Failure policy
                    <select
                      value={effective.failurePolicy}
                      onChange={(e) =>
                        change({
                          failurePolicy: e.target
                            .value as Settings["failurePolicy"],
                        })
                      }
                    >
                      <option value="FAIL_OPEN">
                        Fail open · allow during an outage
                      </option>
                      <option value="FAIL_CLOSED">
                        Fail closed · reject during an outage
                      </option>
                    </select>
                  </label>
                  <label className="toggle-row">
                    <span>
                      <strong>Inject backend failure</strong>
                      <small>Only this experiment's delegate throws.</small>
                    </span>
                    <input
                      type="checkbox"
                      role="switch"
                      checked={settings.outage}
                      onChange={(e) => change({ outage: e.target.checked })}
                    />
                  </label>
                </fieldset>
                <div
                  className={`fault-status ${settings.outage ? "fault-active" : ""}`}
                >
                  <span className="tiny-dot" />
                  <strong>
                    {settings.outage
                      ? "Injected failure active"
                      : "Normal operation"}
                  </strong>
                  <span>
                    {entries[0]?.circuitOpen
                      ? "Circuit open"
                      : "Circuit not observed open"}
                  </span>
                </div>
                <p className="explanation">
                  The Java lab executes the existing resilience decorator: one
                  retry, a breaker threshold of 3 exhausted calls, and a 5s
                  cooldown. Responses report <code>degraded: true</code>; quota
                  is unknown. Injection never stops a Redis server.
                </p>
                <button
                  disabled={busy}
                  className="button secondary"
                  onClick={() => {
                    setTab("overview");
                  }}
                >
                  Send traffic to observe
                  <ArrowRight size={15} />
                </button>
              </section>
            </div>
          )}

          {tab !== "guide" && (
            <section className="card log-card">
              <div className="card-heading">
                <div>
                  <Terminal size={17} />
                  <h2>Request log</h2>
                  <span className="count-tag">{entries.length}</span>
                </div>
                <div className="log-actions">
                  <select
                    aria-label="Filter request log"
                    value={logFilter}
                    onChange={(e) => setLogFilter(e.target.value)}
                  >
                    <option value="all">All decisions</option>
                    <option value="allowed">Allowed</option>
                    <option value="rejected">Rejected</option>
                    <option value="degraded">Degraded</option>
                  </select>
                  <span className="muted">Newest first · last 40 matches</span>
                </div>
              </div>
              <div className="table-scroll">
                <table>
                  <thead>
                    <tr>
                      <th>TIME</th>
                      <th>CLIENT ID</th>
                      <th>INSTANCE</th>
                      <th>DECISION</th>
                      <th>REMAINING</th>
                      <th>RETRY AFTER</th>
                      <th>ROUND TRIP</th>
                    </tr>
                  </thead>
                  <tbody>
                    {shownLogs.map((e) => (
                      <tr key={e.id}>
                        <td className="mono muted">
                          {new Date(e.at).toLocaleTimeString([], {
                            hour12: false,
                          })}
                          .{String(e.at % 1000).padStart(3, "0")}
                        </td>
                        <td className="mono">{e.clientId}</td>
                        <td>
                          <span className="node-tag">{e.node}</span>
                        </td>
                        <td>
                          <span
                            className={`decision ${e.allowed ? "allowed" : "denied"}`}
                          >
                            {e.allowed ? <Check size={12} /> : <X size={12} />}
                            {e.allowed ? "Allowed" : "Rejected"}
                          </span>
                          {e.degraded && (
                            <span className="degraded-tag">Degraded</span>
                          )}
                        </td>
                        <td className="mono">
                          {e.remaining < 0 ? "Unknown" : e.remaining}
                        </td>
                        <td className="mono muted">
                          {e.retryAfterMs ? `${e.retryAfterMs}ms` : "—"}
                        </td>
                        <td className="mono">
                          {e.elapsed.toFixed(2)}
                          <span className="muted"> ms</span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                {shownLogs.length === 0 && (
                  <div className="log-empty">
                    <Terminal size={24} />
                    <strong>
                      {entries.length
                        ? "No matching decisions"
                        : "Ready when you are."}
                    </strong>
                    <span>
                      {entries.length
                        ? "Choose another filter to inspect requests."
                        : "Send a request or run a concurrency test to see each decision."}
                    </span>
                  </div>
                )}
              </div>
            </section>
          )}

          {tab === "guide" && (
            <div className="guide-grid">
              <section className="card guide-card">
                <div className="card-heading">
                  <div>
                    <Layers size={20} />
                    <h2>The request path</h2>
                  </div>
                  <span className="period-tag">Java 21 / Spring Boot</span>
                </div>
                <div className="request-path">
                  {[
                    "React dashboard",
                    "Spring REST API",
                    "Metrics decorator",
                    "Resilience decorator (Redis)",
                    "Sliding-window limiter",
                  ].map((s, i) => (
                    <div key={s}>
                      <span>{String(i + 1).padStart(2, "0")}</span>
                      <strong>{s}</strong>
                      <ArrowDown size={15} />
                    </div>
                  ))}
                </div>
                <p className="explanation">
                  A timestamp is counted only while it is newer than{" "}
                  <code>now − window</code>. Exactly-window-old timestamps
                  expire. In-memory uses a per-client lock; Redis uses an atomic
                  Lua script with its own server clock.
                </p>
                <a
                  className="button secondary"
                  href={`${repo}/blob/main/docs/ARCHITECTURE.md`}
                  target="_blank"
                  rel="noreferrer"
                >
                  Read the architecture
                  <ExternalLink size={14} />
                </a>
              </section>
              <section className="card guide-card">
                <h2>Try these experiments</h2>
                <ol className="guide-steps">
                  <li>
                    <strong>Fill the window</strong>
                    <p>
                      Use limit 10 and window 10s. Send a burst of 15 to A: 10
                      allowed, then 5 rejected. Watch accepted dots expire, then
                      send again.
                    </p>
                  </li>
                  <li>
                    <strong>Race one client</strong>
                    <p>
                      Set a 60s window. Run 1 client × 20 requests to A + B.
                      Compare a new in-memory experiment with a new Redis
                      experiment.
                    </p>
                  </li>
                  <li>
                    <strong>Trade availability for protection</strong>
                    <p>
                      Inject a backend failure. Fail-open allows degraded
                      requests; fail-closed rejects them. Sequential calls open
                      the circuit after 3 failures per node.
                    </p>
                  </li>
                  <li>
                    <strong>Use the real Java service</strong>
                    <p>
                      Connect its HTTP(S) origin. The isolated lab uses original
                      Java classes. Production mode uses the existing{" "}
                      <code>/v1/rate-limit/check</code> endpoint and fixed
                      deployment settings.
                    </p>
                  </li>
                </ol>
              </section>
              <section className="card full-width">
                <h2>Honest boundaries</h2>
                <p className="explanation">
                  Browser mode is an educational simulation. A + B in the Java
                  lab are two limiter objects in one JVM; the repository's
                  Testcontainers tests prove Redis correctness across
                  independent instances. Session charts describe this
                  dashboard's traffic, not fleet-wide load. Round-trip latency
                  includes the browser and network and is not a microbenchmark.
                  Fail-open admission is not successful protected application
                  work. Redis failures can make quota unknown; no exact quota is
                  inferred from degraded responses.
                </p>
                <div className="tech-tags">
                  <span>Sliding-window log</span>
                  <span>Per-client synchronization</span>
                  <span>Atomic Lua</span>
                  <span>Retry + circuit breaker</span>
                  <span>Micrometer + Prometheus</span>
                </div>
              </section>
            </div>
          )}

          {source !== "demo" && tab !== "guide" && (
            <section className="backend-strip">
              <Server size={18} />
              <div>
                <strong>Production instance metrics</strong>
                <span>
                  {metricError
                    ? "Metrics refresh failed; values may be stale."
                    : fleet?.scope || "Loading metrics…"}
                </span>
              </div>
              <span>
                {fleet
                  ? `${number(fleet.allowed)} allowed · ${number(fleet.rejected)} rejected · ${fleet.meanLatencyMs.toFixed(2)}ms mean decision`
                  : "—"}
              </span>
            </section>
          )}
          <footer>
            <span>
              <span className="footer-mark">≋</span>RateLab
              <span className="dot-sep">/</span>An observable distributed
              systems project.
            </span>
            <a href={repo} target="_blank" rel="noreferrer">
              Built by Ajay Nalla
              <ArrowUpRight size={13} />
            </a>
          </footer>
        </main>
      </div>
      {dialog && (
        <div className="modal-backdrop">
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-labelledby="connection-title"
          >
            <div className="card-heading">
              <h2 id="connection-title">Connect your Java backend</h2>
              <button
                className="icon-button"
                aria-label="Close connection settings"
                onClick={() => {
                  setDialog(false);
                  setError("");
                }}
              >
                <X size={20} />
              </button>
            </div>
            <p>
              Enter the origin of the running Spring Boot service. Leave empty
              for the same origin or the local Vite proxy.
            </p>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                void connect();
              }}
            >
              <label>
                Backend origin
                <input
                  autoFocus
                  value={apiUrl}
                  onChange={(e) => setApiUrl(e.target.value)}
                  placeholder="https://your-java-service.example.com"
                />
              </label>
              <p className="microcopy">
                Allow this dashboard's origin in{" "}
                <code>DASHBOARD_ALLOWED_ORIGINS</code>. Public HTTPS dashboards
                require an HTTPS API.
              </p>
              {error && (
                <div className="error-banner" role="alert">
                  {error}
                </div>
              )}
              <div className="modal-buttons">
                <button
                  type="button"
                  className="button secondary"
                  disabled={connecting}
                  onClick={() => {
                    resetExperiment();
                    setSource("demo");
                    setConfig(null);
                    setDialog(false);
                  }}
                >
                  Use browser demo
                </button>
                <button className="button primary" disabled={connecting}>
                  {connecting ? "Connecting…" : "Connect & verify"}
                  <ArrowRight size={15} />
                </button>
              </div>
            </form>
            {config?.demoEnabled && (
              <button
                className="text-button production-switch"
                onClick={() => {
                  resetExperiment();
                  setSource("production");
                  setSettings({ ...config, outage: false });
                  setRoute("A");
                  setDialog(false);
                }}
              >
                Switch to original production endpoint
                <ArrowRight size={14} />
              </button>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
