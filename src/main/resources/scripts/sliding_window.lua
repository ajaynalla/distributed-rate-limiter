-- Atomic sliding-window-log rate limiter.
--
-- KEYS[1] = zset key holding this client's request timestamps, e.g. "rl:client-123"
-- KEYS[2] = companion counter key used only to disambiguate ZSET members
--           that land on the same millisecond, e.g. "rl:client-123:seq"
-- ARGV[1] = limit (max requests allowed per window)
-- ARGV[2] = window size in milliseconds
--
-- Returns: { allowed (1|0), remaining (integer), retry_after_ms (integer) }
--
-- Atomicity: Redis executes an entire EVAL script as a single, indivisible
-- step on the server (Redis is single-threaded for command execution, and
-- no other client's commands can interleave inside a script). That is what
-- makes this safe across many application instances hitting the same key
-- concurrently -- unlike a GET -> compute in app code -> SET sequence,
-- there is no window between "read current state" and "write new state"
-- for a second instance to race into.
--
-- Clock source: redis.call('TIME') is used instead of a timestamp passed
-- in from the caller. All application instances therefore agree on a
-- single clock (Redis's), instead of each trusting its own possibly-
-- drifted system clock -- important once this script is called
-- concurrently from many hosts.

local zset_key = KEYS[1]
local seq_key = KEYS[2]
local limit = tonumber(ARGV[1])
local window_ms = tonumber(ARGV[2])

local time_parts = redis.call('TIME')
local now_ms = (tonumber(time_parts[1]) * 1000) + math.floor(tonumber(time_parts[2]) / 1000)
local window_start = now_ms - window_ms

-- Evict entries that are exactly window_ms old or older (same boundary
-- rule as the in-memory implementation: a timestamp exactly window_ms
-- old has expired).
redis.call('ZREMRANGEBYSCORE', zset_key, '-inf', window_start)

local count = redis.call('ZCARD', zset_key)
local ttl_ms = window_ms + 1000

if count < limit then
    local seq = redis.call('INCR', seq_key)
    local member = now_ms .. '-' .. seq
    redis.call('ZADD', zset_key, now_ms, member)
    redis.call('PEXPIRE', zset_key, ttl_ms)
    redis.call('PEXPIRE', seq_key, ttl_ms)
    return {1, limit - count - 1, 0}
end

local oldest = redis.call('ZRANGE', zset_key, 0, 0, 'WITHSCORES')
local retry_after_ms = 0
if oldest[2] ~= nil then
    retry_after_ms = (tonumber(oldest[2]) + window_ms) - now_ms
    if retry_after_ms < 0 then
        retry_after_ms = 0
    end
end

-- Still refresh TTL so a key that is being hammered past its limit
-- doesn't expire mid-burst and silently reset the client's history.
redis.call('PEXPIRE', zset_key, ttl_ms)
redis.call('PEXPIRE', seq_key, ttl_ms)

return {0, 0, retry_after_ms}
