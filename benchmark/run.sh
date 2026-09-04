#!/usr/bin/env bash
# Runs the Phase 3 in-memory rate limiter benchmark and prints CSV to stdout.
# Usage: ./benchmark/run.sh [> results.csv]
set -euo pipefail

cd "$(dirname "$0")/.."

mvn -q compile
java -cp target/classes com.ratelimiter.benchmark.RateLimiterBenchmark
