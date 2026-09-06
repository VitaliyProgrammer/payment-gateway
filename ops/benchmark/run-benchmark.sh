#!/usr/bin/env bash
# Stage 7 benchmark: run the SAME closed-loop load against the gateway twice -
# once with platform threads on Tomcat, once with virtual threads - and print
# the two Markdown tables side by side.
#
# Not part of CI. Needs: JDK 21 (via JAVA_HOME, same as ./mvnw uses - a plain
# `java` on PATH may be a different version), Docker (for Postgres only), curl.
# Run from the repo root:
#
#   ops/benchmark/run-benchmark.sh
#
# The gateway and mock-acquirer run as plain `java -jar` on the host (not as
# Docker images) so the run controls their flags directly and does not fight
# other containers for ports.
#
# Knobs (env vars):
#   LEVELS         concurrency levels, comma-separated  (default 50,200,500,1000)
#   DURATION       measured seconds per level           (default 20)
#   WARMUP         warmup seconds per level             (default 5)
#   WORKERS        gateway processing-worker count      (default 400)
#   GW_PORT        gateway HTTP port                    (default 8080)
#   ACQUIRER_PORT  mock-acquirer HTTP port              (default 8090)
#
# WORKERS is raised on purpose: the worker pool is always virtual-thread, so
# leaving it at 8 would cap throughput on the acquirer drain rate and hide the
# thing being compared - the HTTP request path's thread model.
set -euo pipefail

cd "$(dirname "$0")/../.."

LEVELS="${LEVELS:-50,200,500,1000}"
DURATION="${DURATION:-20}"
WARMUP="${WARMUP:-5}"
WORKERS="${WORKERS:-400}"
GW_PORT="${GW_PORT:-8080}"
ACQUIRER_PORT="${ACQUIRER_PORT:-8090}"
BASE_URL="http://localhost:${GW_PORT}"
RESULTS_DIR="ops/benchmark"
BENCH_JAR="benchmark/target/benchmark.jar"
GATEWAY_JAR=""
ACQUIRER_JAR=""

GATEWAY_PID=""
ACQUIRER_PID=""

# Use the same JDK as ./mvnw (JAVA_HOME), not whatever `java` is first on PATH -
# they can differ, and the jars are compiled for 21.
JAVA="${JAVA_HOME:+${JAVA_HOME}/bin/}java"

cleanup() {
  [ -n "$GATEWAY_PID" ]  && kill "$GATEWAY_PID"  2>/dev/null || true
  [ -n "$ACQUIRER_PID" ] && kill "$ACQUIRER_PID" 2>/dev/null || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

# Picks the runnable Spring Boot jar for a module, skipping the plain *.jar.original
# and any sources/javadoc jars. Plain loop, no pipe to `head` - that trips SIGPIPE
# under `set -o pipefail`.
pick_jar() {
  local f
  for f in $1; do
    case "$f" in *.original|*sources*|*javadoc*) continue ;; esac
    [ -f "$f" ] && { printf '%s\n' "$f"; return 0; }
  done
  return 1
}

echo "==> building jars"
./mvnw -q -Pbenchmark -pl gateway,mock-acquirer,benchmark -am package -DskipTests
GATEWAY_JAR="$(pick_jar 'gateway/target/gateway-*.jar')"
ACQUIRER_JAR="$(pick_jar 'mock-acquirer/target/mock-acquirer-*.jar')"
[ -n "$GATEWAY_JAR" ] && [ -n "$ACQUIRER_JAR" ] || { echo "jars not found" >&2; exit 1; }

echo "==> starting Postgres"
docker compose up -d --wait postgres   # --wait blocks until the healthcheck passes

echo "==> starting mock-acquirer on :${ACQUIRER_PORT}"
"$JAVA" -jar "$ACQUIRER_JAR" --server.port="$ACQUIRER_PORT" --logging.level.root=WARN \
    >"$RESULTS_DIR/mock-acquirer.log" 2>&1 &
ACQUIRER_PID=$!
for _ in $(seq 1 60); do
  curl -sf "http://localhost:${ACQUIRER_PORT}/actuator/health" >/dev/null 2>&1 && break
  sleep 1
done

run_profile() {
  local label="$1" virtual="$2"
  echo "==> starting gateway (label=$label, spring.threads.virtual.enabled=$virtual)"
  DB_URL="jdbc:postgresql://localhost:5432/payflow" \
  DB_USER=payflow DB_PASSWORD=payflow \
  ACQUIRER_BASE_URL="http://localhost:${ACQUIRER_PORT}" \
  SERVER_PORT="$GW_PORT" \
  PROCESSING_WORKER_COUNT="$WORKERS" \
  PROCESSING_QUEUE_CAPACITY=20000 \
  "$JAVA" -jar "$GATEWAY_JAR" \
      --spring.threads.virtual.enabled="$virtual" \
      --logging.level.root=WARN >"$RESULTS_DIR/gateway-$label.log" 2>&1 &
  GATEWAY_PID=$!

  for _ in $(seq 1 90); do
    curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"' && break
    sleep 1
  done

  echo "==> load: label=$label"
  "$JAVA" -Dbench.label="$label" \
       -Dbench.baseUrl="$BASE_URL" \
       -Dbench.concurrency="$LEVELS" \
       -Dbench.durationSeconds="$DURATION" \
       -Dbench.warmupSeconds="$WARMUP" \
       -jar "$BENCH_JAR" | tee "$RESULTS_DIR/results-$label.md"

  kill "$GATEWAY_PID" 2>/dev/null || true
  wait "$GATEWAY_PID" 2>/dev/null || true
  GATEWAY_PID=""
}

run_profile platform false
run_profile virtual  true

{
  echo "# Benchmark results"
  echo
  echo "Levels: $LEVELS   measured: ${DURATION}s/level   warmup: ${WARMUP}s   gateway workers: $WORKERS"
  echo "Acquirer adds 50-300ms simulated latency per authorize."
  echo
  # Each per-profile file prints its rows once per level during the run and again
  # in a final table - take the header once and de-duplicate the data rows.
  awk '/^\| profile /{print; exit}'        "$RESULTS_DIR/results-platform.md" 2>/dev/null || true
  awk '/^\|[- :]+\|[- :]+\|/{print; exit}' "$RESULTS_DIR/results-platform.md" 2>/dev/null || true
  awk '/^\| (platform|virtual) / && !seen[$0]++' \
      "$RESULTS_DIR/results-platform.md" "$RESULTS_DIR/results-virtual.md" 2>/dev/null || true
} > "$RESULTS_DIR/results.md"

echo
echo "==> combined table written to $RESULTS_DIR/results.md"
cat "$RESULTS_DIR/results.md"
