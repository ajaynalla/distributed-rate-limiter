import type { Entry } from "./model";

export function TrafficChart({
  entries,
  now,
}: {
  entries: Entry[];
  now: number;
}) {
  const buckets = Array.from({ length: 30 }, (_, i) => {
    const end = now - (29 - i) * 2000;
    const slice = entries.filter((e) => e.at <= end && e.at > end - 2000);
    return {
      allowed: slice.filter((e) => e.allowed).length,
      denied: slice.filter((e) => !e.allowed).length,
    };
  });
  const max = Math.max(5, ...buckets.map((b) => b.allowed + b.denied));
  return (
    <div className="traffic-chart">
      <div className="y-labels">
        <span>{max}</span>
        <span>{Math.ceil(max / 2)}</span>
        <span>0</span>
      </div>
      <svg
        viewBox="0 0 700 170"
        role="img"
        aria-label="Allowed and rejected requests per two-second bucket over the last minute"
        preserveAspectRatio="none"
      >
        {[15, 85, 155].map((y) => (
          <line
            key={y}
            x1="0"
            x2="700"
            y1={y}
            y2={y}
            stroke="#e8ece7"
            strokeDasharray="4 4"
          />
        ))}
        {buckets.map((b, i) => (
          <g key={i}>
            <rect
              x={i * 23.3 + 4}
              y={155 - (b.allowed / max) * 140}
              width="14"
              height={(b.allowed / max) * 140}
              rx="2"
              fill="#659f6d"
            />
            <rect
              x={i * 23.3 + 4}
              y={155 - ((b.allowed + b.denied) / max) * 140}
              width="14"
              height={(b.denied / max) * 140}
              rx="2"
              fill="#d9826d"
            />
          </g>
        ))}
      </svg>
      {entries.length === 0 && (
        <div className="chart-empty">
          Your traffic will appear here
          <span>Send a request to start observing.</span>
        </div>
      )}
      <div className="x-labels">
        <span>60s ago</span>
        <span>40s</span>
        <span>20s</span>
        <span>Now</span>
      </div>
    </div>
  );
}

export function WindowChart({
  entries,
  now,
  windowMillis,
  clientId,
}: {
  entries: Entry[];
  now: number;
  windowMillis: number;
  clientId: string;
}) {
  const visible = entries.filter(
    (e) => e.clientId === clientId && e.at > now - windowMillis,
  );
  return (
    <div className="window-chart">
      <div
        className="window-line"
        role="img"
        aria-label={`Sliding window for ${clientId}; ${visible.length} observed attempts still within the window`}
      >
        {[0, 25, 50, 75, 100].map((x) => (
          <div className="window-tick" key={x} style={{ left: `${x}%` }} />
        ))}
        {visible.slice(-100).map((e, i) => (
          <div
            key={e.id}
            title={`${e.clientId} · ${e.node} · ${e.allowed ? "allowed" : "rejected"}`}
            className={`request-dot ${e.degraded ? "degraded-dot" : e.allowed ? "" : "rejected-dot"}`}
            style={{
              left: `${Math.max(0, 100 - ((now - e.at) / windowMillis) * 100)}%`,
              top: `${6 + (i % 6) * 10}px`,
            }}
          />
        ))}
      </div>
      <div className="x-labels">
        <span>−{windowMillis / 1000}s · expires here</span>
        <span>Sliding window</span>
        <span>Now</span>
      </div>
    </div>
  );
}
