# Benchmark Results — In-Memory Sliding-Window Limiter

These are real numbers captured by running `./benchmark/run.sh` (which runs
`com.ratelimiter.benchmark.RateLimiterBenchmark`) in the development
sandbox this project was built in. **No numbers here are invented.** Run
the script yourself — results will differ by hardware, and you should
expect them to.

## Environment

| | |
|---|---|
| CPU | Intel(R) Xeon(R) @ 2.10GHz, **4 logical cores** (`nproc` = 4) |
| RAM | 15 GiB |
| JVM | OpenJDK 21.0.10 (Ubuntu build), default G1GC, default heap sizing |
| OS | Linux 6.18 (containerized sandbox) |
| Isolation | Shared/virtualized host — expect run-to-run noise, not a dedicated bare-metal benchmark box |

## Methodology

- Two scenarios per thread count:
  - **single-hot-client** — every thread hammers the *same* clientId, so
    every call serializes on that client's lock (worst case for
    contention).
  - **per-thread-client** — every thread uses its *own* clientId, so
    calls never contend on the same lock (isolates raw per-call cost
    from lock contention).
- Thread counts: 1, 10, 50, 100.
- Each scenario: 5,000 warmup ops/thread (untimed, lets the JIT compile
  the hot path) followed by 20,000 measured ops/thread, released
  simultaneously via a `CountDownLatch` to maximize interleaving.
- Per-call latency measured with `System.nanoTime()` around each
  `tryAcquire` call, aggregated across all threads, sorted, and reported
  as percentiles. Throughput = total ops / wall-clock time of the
  measured burst.
- Limiter configured with `limit=1000`, `window=1000ms` — small enough
  that clients actually hit their limit and exercise both the allow and
  deny branches (not just an artificially unlimited allow-path), large
  enough that per-client memory stays bounded (old timestamps evict
  continuously instead of growing unboundedly).

## Raw results (two independent runs)

### Run 1

```
scenario,threads,totalOps,wallTimeMs,throughputOpsPerSec,avgLatencyUs,p50LatencyUs,p95LatencyUs,p99LatencyUs,maxLatencyUs
single-hot-client,1,20000,6.34,3153753.86,0.216,0.166,0.265,1.729,41.679
single-hot-client,10,200000,57.77,3461801.96,2.107,0.437,1.527,3.158,16742.595
single-hot-client,50,1000000,270.97,3690482.10,8.153,0.427,1.617,2.909,64122.772
single-hot-client,100,2000000,472.40,4233719.12,13.321,0.553,1.272,2.354,139380.374
per-thread-client,1,20000,4.48,4462085.55,0.148,0.104,0.219,0.424,41.279
per-thread-client,10,200000,10.00,20008817.89,0.158,0.083,0.087,0.171,6156.917
per-thread-client,50,1000000,45.63,21916222.09,0.083,0.071,0.086,0.180,2010.151
per-thread-client,100,2000000,112.76,17737301.41,0.120,0.082,0.095,0.654,8066.068
```

### Run 2

```
scenario,threads,totalOps,wallTimeMs,throughputOpsPerSec,avgLatencyUs,p50LatencyUs,p95LatencyUs,p99LatencyUs,maxLatencyUs
single-hot-client,1,20000,5.74,3486924.82,0.173,0.129,0.167,1.483,42.456
single-hot-client,10,200000,48.28,4142783.78,1.638,0.436,0.998,2.714,16521.686
single-hot-client,50,1000000,173.80,5753785.52,4.701,0.167,1.081,2.108,54042.456
single-hot-client,100,2000000,536.88,3725244.23,14.750,0.558,1.537,2.735,138273.610
per-thread-client,1,20000,4.76,4197355.46,0.175,0.103,0.217,0.462,272.680
per-thread-client,10,200000,13.79,14507488.19,0.148,0.083,0.085,0.179,6403.084
per-thread-client,50,1000000,49.69,20126443.98,0.091,0.082,0.085,0.181,2361.140
per-thread-client,100,2000000,110.73,18061631.81,0.099,0.083,0.089,0.195,8528.579
```

Latency columns are microseconds; throughput is ops/sec.

## Reading the results

**`per-thread-client` (no contention) scales with cores, then flattens/degrades:**
throughput jumps roughly **4-5x from 1→10 threads** (~4.2-4.5M → ~14.5-20M
ops/sec), matching the 4 physical cores available — this is real parallel
speedup, not noise. It does **not** keep climbing at 50 or 100 threads
(~18-22M ops/sec, no further gain, slight *drop* at 100 vs 50 in both
runs). With only 4 cores, 50-100 runnable threads are massively
oversubscribed; the OS is now context-switching between far more
runnable threads than it has cores for, and that scheduling overhead
starts eating into the gains. **The bottleneck at high thread counts in
this scenario is core count and OS scheduling, not the rate limiter.**

**`single-hot-client` (full contention) shows the cost hiding in tail
latency, not aggregate throughput:** median (p50) latency stays low and
roughly flat (0.13-0.56µs) across all thread counts — most calls still
acquire the per-client lock quickly. But **max latency explodes**: ~42µs
at 1 thread → **16,500-16,700µs at 10 threads** → **54,000-139,000µs
(54-139ms) at 50-100 threads**. That is the signature of lock
contention: as more threads queue behind the single `synchronized` block
guarding `hot-client`'s deque, most acquisitions are still fast, but an
unlucky thread at the back of the queue can wait milliseconds. Aggregate
throughput looks deceptively fine (it even mildly increases with thread
count, since work done *outside* the critical section — hashing the
clientId, the `ConcurrentHashMap` lookup — still parallelizes) but that
metric hides the real story: **p99/max latency is where contention
actually shows up first**, well before throughput visibly drops. This is
exactly why a production dashboard for this system should alert on
`rate_limiter_decision_latency` p99, not just throughput or average
latency.

**Where performance degrades and why, concretely:**
1. Beyond the core count (4 here), `per-thread-client` throughput stops
   improving — bottleneck is **CPU parallelism**, not the algorithm.
2. Under a single hot key, tail latency degrades by 3-4 orders of
   magnitude as thread count rises — bottleneck is **serialization on
   the per-client lock**, which is inherent: any correct implementation
   must serialize mutations to one client's shared state somewhere.
3. `single-hot-client` throughput never approaches `per-thread-client`
   throughput at the same thread count (e.g. at 50 threads: ~3.7-5.8M
   vs ~20-22M ops/sec) — the ~4-6x gap quantifies the real cost of
   contention on this hardware for this workload.

## What this does and doesn't prove

This benchmark measures the **in-memory limiter's raw call path** on a
4-core sandbox VM — it is a statement about lock contention behavior and
JIT-warmed call overhead, not a capacity-planning number for a specific
production deployment. It deliberately does not benchmark the Redis path
(Phase 4) or the HTTP layer (Phase 6); those add network round-trips and
serialization that dominate over the in-process lock cost measured here.
