# How I would defend this project in a backend interview

Direct answers to the questions this project should invite. Each one
points at the actual code/commit/measurement behind it, not a
generic textbook answer.

## Why sliding window?

A sliding-window **log** enforces the limit over a continuously moving
window: a request at time `t` is allowed only if fewer than `limit`
requests landed in `(t - window, t]`. That is the actual guarantee most
rate-limit contracts want ("no more than N requests in any rolling
window"), and it's precise -- no approximation, no burst tolerance
beyond the stated limit.

## Why not fixed window?

A fixed window (e.g. "reset the counter every 60s on the minute") has a
well-known boundary problem: a client can send `limit` requests in the
last millisecond of one window and another `limit` in the first
millisecond of the next, getting `2 * limit` requests in a tiny span
that straddles the boundary. A sliding window closes that gap by
definition -- there's no reset point to game. The cost is per-client
memory proportional to `limit` (a deque/ZSET of timestamps, not a
single counter) and a small amount of extra CPU to evict expired
entries -- a tradeoff I made deliberately in favor of correctness, and
I'd say so if asked "why not the cheaper option."

## Why `ConcurrentHashMap`?

Two different problems need solving for the in-memory limiter under
concurrency: (1) registering a client's state exactly once even when
two threads see it for the first time simultaneously, and (2)
serializing mutations to one client's own state. `ConcurrentHashMap`
solves (1): `computeIfAbsent` is guaranteed to invoke the mapping
function at most once per key even under concurrent callers, so two
threads racing to be the first caller for a brand-new client can never
create two separate `ClientWindow` instances (which would silently
reset that client's quota). It does **not** solve (2) by itself --
`ConcurrentHashMap` makes the map safe, not the mutable object stored
in it.

## Why not one global lock?

A single lock around the whole limiter would make (2) correct as well,
trivially. The problem is throughput: it serializes *every* client
through one lock, so client B waits behind client A's request even
though they share zero state. My design puts a separate lock per
`ClientWindow` (`synchronized(this)` on that client's own object), so
contention is scoped to callers sharing the same `clientId` --
unrelated clients run fully in parallel. I proved this isn't just a
claim: the Phase 3 benchmark shows `per-thread-client` (no shared lock)
hitting ~18-22M ops/sec at 50-100 threads on 4 cores, while
`single-hot-client` (everyone serialized on one lock) tops out around
3.7-5.8M ops/sec at the same thread counts -- a 4-6x gap that
quantifies exactly what a global lock would cost every client, not just
the hot one.

## Where is the race condition?

In the naive version, inside `decide()`: `size() < limit` (read) then
`addLast(now)` (write) is a check-then-act sequence with no atomicity.
Two threads can both read "size is 2, limit is 3, I have room," and
both append, leaving the client at 4 outstanding requests against a
limit of 3. I didn't just assert this -- I reproduced it: a bare
unsynchronized `ArrayDeque` under the same 64-thread/50-attempt/limit-
100 load the concurrency test uses let through 181, 117, and 161
requests across three separate runs (see the Phase 2 commit and
`InMemoryRateLimiterConcurrencyTest`'s Javadoc). The fix is the
per-client lock described above: `recordAttempt()` does the whole
evict-check-append sequence inside one `synchronized` block, so the
whole thing is one atomic unit as far as any other thread can observe.

## Why is the Redis implementation atomic?

The whole evict/count/decide/record sequence runs as a single Lua
script via `EVAL`. Redis executes a script as one indivisible unit --
no other client's commands can interleave partway through, because
Redis is single-threaded for command execution. That's what closes the
distributed version of the same race: a naive `GET count` ->
compute-in-app-code -> `SET count` has a gap between the read and the
write where a second instance can read the same stale count and both
get admitted past the limit. Moving the entire decision into the script
removes that gap -- there are no separate round trips for it to exist
between. I verified this three ways because I don't want to just assert
atomicity: manual `redis-cli --eval` calls proving exact
limit/remaining/retryAfter behavior, 200 concurrent `redis-cli` evals
via `xargs -P 50` against one key landing exactly 50 in the ZSET for a
limit of 50, and a Java-level test with two separate limiter instances
(simulating two app pods, no shared in-process state) hitting a shared
client concurrently and landing exactly at the configured limit.

I'd also flag the clock source if asked: the script calls Redis's own
`TIME` command rather than trusting each caller's system clock, so
every app instance agrees on one clock instead of each trusting its own
(possibly drifted) `System.currentTimeMillis()`.

## What happens when Redis fails?

It's a configurable policy (`rate-limiter.redis.failure-policy`), not a
hardcoded choice, because there's no universally correct answer --
`FAIL_OPEN` favors availability (the protected service stays up, but
the limit isn't enforced during the outage), `FAIL_CLOSED` favors
protection (the limit is never silently bypassed, but a Redis outage
becomes a full outage of everything behind the limiter). Mechanically:
each call gets up to `maxRetries` attempts with exponential backoff +
jitter; once those are exhausted, a `CircuitBreaker` records the
failure, and once its failure threshold is hit it opens, so *subsequent
requests skip Redis entirely* instead of each paying the retry+backoff
cost against a backend that's confirmed down -- that's the actual
anti-retry-storm mechanism, not the bounded retries alone (which only
cap the cost of one request, not a whole fleet hammering a dead
backend). I verified the whole chain against a real dead connection:
fail-open let requests through with `degraded=true`, fail-closed
rejected them the same way, the breaker actually tripped after its
threshold, and a short-circuited call after tripping completed in
~10ms end-to-end over real HTTP -- no retry, no network attempt.

## What happens with a hot client?

In-memory: all concurrent requests for that one client serialize on its
`ClientWindow` lock. The benchmark shows what that costs concretely --
median latency stays low (sub-microsecond) even under heavy contention,
but max/p99 latency degrades by 3-4 orders of magnitude (tens of µs at
1 thread to 54-139ms at 50-100 threads), because some unlucky caller
queues behind the lock. Aggregate throughput looks deceptively okay
because work outside the critical section (hashing the clientId, the
map lookup) still parallelizes -- which is exactly why I instrument
decision *latency percentiles*, not just throughput or averages, in
`MetricsRateLimiter`.

Distributed: a hot client means many app instances all calling `EVAL`
against the *same Redis key* concurrently. Redis serializes those the
same way (single-threaded execution), so correctness holds, but that
key becomes a hot key on one Redis node -- at extreme scale that's a
real bottleneck (see "100K/1M req/sec" below).

## How would this scale to 10x traffic?

For the in-memory limiter: mostly for free, as long as traffic is
spread across many distinct clients -- the per-thread-client benchmark
result shows near-linear scaling with core count. Ten times the load
mostly means ten times the app instances (each running its own
in-memory limiter), which is fine if per-instance limits are
acceptable, or wrong if you need one *global* limit across instances --
which is exactly the problem the Redis-backed version solves. For the
Redis path, 10x traffic against evenly-distributed clients is mostly a
Redis capacity question (CPU/network on the Redis node, connection pool
sizing on each app instance) -- I'd load-test the actual Lua script
throughput on real hardware before promising a number, per the "don't
claim unmeasured performance" rule I held to throughout this project.

## What is the consistency model?

Single Redis instance: linearizable for the rate-limit decision itself
-- every `EVAL` is a single atomic operation against one node, so there
is one true history of admits/denies per client, and every app instance
sees it. That guarantee weakens the moment you add Redis replication
for availability: a replica promoted after a primary failure may be
missing the last few writes (Redis's default async replication), so a
client could briefly get more than its limit across a failover. That's
a real, known tradeoff of this design I'd state plainly rather than
paper over -- strict linearizability across a highly-available Redis
deployment would need synchronous replication or a consensus-backed
store, at a latency cost most rate limiters don't need to pay.

## Where are the bottlenecks?

Measured, not guessed, at each layer:
1. **In-memory core, single hot key:** the per-client lock -- inherent,
   any correct design must serialize mutation of one shared counter
   somewhere.
2. **In-memory core, many keys, high thread count:** CPU core count --
   the Phase 3 benchmark plateaus/slightly regresses past the sandbox's
   4 physical cores due to OS scheduling overhead from oversubscription,
   not the algorithm.
3. **Redis path:** one network round trip per decision (the Lua script
   removes the *race*, not the round trip), Redis's own single-threaded
   command execution as key count and QPS grow, and the app-side
   connection pool to Redis.
4. **API layer:** ordinary HTTP/JSON overhead -- not something this
   project benchmarked in isolation, since it's dwarfed by the Redis
   round trip whenever mode=REDIS.

## What would I change at 100K/1M requests/sec?

- **Shard Redis by clientId** (consistent hashing across multiple Redis
  nodes, or Redis Cluster) so no single node has to absorb the full
  request rate -- the current single-node design is the right choice
  for this project's scope but is the first thing to outgrow.
- **Batch or locally pre-aggregate** for extremely hot single clients:
  e.g. a short-lived local token bucket per app instance that only
  syncs to Redis periodically, trading a small amount of precision for
  removing the per-request round trip on the hottest keys -- a real
  precision/latency tradeoff, not free.
- **Move off a hand-rolled Thread.sleep-based retry** on the request
  thread toward a non-blocking client/reactive stack, so retry backoff
  doesn't tie up a servlet thread under load.
- **Reconsider the TTL-refresh-on-every-call behavior** in the Lua
  script at very high QPS on one key -- `PEXPIRE` on every call is
  cheap per-call but adds up; worth profiling before assuming it's
  fine.
- Most importantly: **I would not guess at any of this without load
  testing the actual bottleneck first.** Everything above is a
  hypothesis about where the next bottleneck would appear based on
  what's measured at this project's scale; none of it is a claim about
  verified behavior at 100K-1M req/sec, because I haven't run that test.
