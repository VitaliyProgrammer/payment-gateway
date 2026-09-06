#!/usr/bin/env bash
# Stage 7 benchmark: run the SAME closed-loop load against the gateway twice -
# once with platform threads on Tomcat, once with virtual threads - and print
# the two Markdown tables side by side.
#
# Not part of CI. Needs: a JDK 21 on PATH, Docker (for postgres + mock-acquirer),
# curl. Run from the repo root:
#
#   ops/benchmark/run-benchmark.sh
#
# Knobs (env vars):
#   LEVELS      concurrency levels, comma-separated   (default 50,200,500,1000)
#   DURATION    measured seconds per level            (default 20)
#   WARMUP      warmup seconds per level              (default 5)
#   WORKERS     gateway processing-worker count       (default 400)
#
# The processing-worker pool is raised on purpose: it is always virtual-thread,
# so leaving it at 8 would cap throughput on the acquirer drain rate and hide the
# thing we are actually comparing - the HTTP request path's thread model.
set -euo pipefail

cd "$(dirname "$0")/../.."

LEVELS="${LEVELS:-50,200,500,1000}"
DURATION="${DURATION:-20}"
WARMUP="${WARMUP:-5}"
WORKERS="${WORKERS:-400}"
BASE_URL="http://localhost:8080"
RESULTS_DIR="ops/benchmark"
BENCH_JAR="benchmark/target/benchmark.jar"
GATEWAY_JAR=""

GATEWAY_PID=""

cleanup() {
  [ -n "$GATEWAY_PID" ] && kill "$GATEWAY_PID" 2>/dev/null || true
  docker compose down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

echo "==> building jars"
./mvnw -q -pl gateway,mock-acquirer,benchmark -am package -DskipTests
GATEWAY_JAR="$(ls gateway/target/gateway-*.jar | grep -vE 'sources|javadoc|original' | head -n1)"
[ -n "$GATEWAY_JAR" ] || { echo "gateway jar not found" >&2; exit 1; }

echo "==> starting postgres + mock-acquirer"
docker compose up -d postgres mock-acquirer
# wait for mock-acquirer health
for i in $(seq 1 60); do
  curl -sf http://localhost:9090/actuator/health >/dev/null 2>&1 && break
  sleep 1
done

run_profile() {
  local label="$1" virtual="$2"
  echo "==> starting gateway (label=$label, spring.threads.virtual.enabled=$virtual)"
  DB_URL="jdbc:postgresql://localhost:5432/payflow" \
  DB_USER=payflow DB_PASSWORD=payflow \
  ACQUIRER_BASE_URL="http://localhost:9090" \
  PROCESSING_WORKER_COUNT="$WORKERS" \
  PROCESSING_QUEUE_CAPACITY=20000 \
  java -jar "$GATEWAY_JAR" \
      --spring.threads.virtual.enabled="$virtual" \
      --logging.level.root=WARN >"$RESULTS_DIR/gateway-$label.log" 2>&1 &
  GATEWAY_PID=$!

  for i in $(seq 1 90); do
    curl -sf "$BASE_URL/actuator/health" | grep -q '"status":"UP"' && break
    sleep 1
  done

  echo "==> load: label=$label"
  java -Dbench.label="$label" \
       -Dbench.baseUrl="$BASE_URL" \
       -Dbench.concurrency="$LEVELS" \
       -Dbench.durationSeconds="$DURATION" \
       -Dbench.warmupSeconds="$WARMUP" \
       -jar "$BENCH_JAR" | tee "$RESULTS_DIR/results-$label.md"

  kill "$GATEWAY_PID"; wait "$GATEWAY_PID" 2>/dev/null || true
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
  grep -h '^|' "$RESULTS_DIR/results-platform.md" | sed -n '1,2p'
  grep -h '^| platform ' "$RESULTS_DIR/results-platform.md"
  grep -h '^| virtual '  "$RESULTS_DIR/results-virtual.md"
} > "$RESULTS_DIR/results.md"

echo
echo "==> combined table written to $RESULTS_DIR/results.md"
cat "$RESULTS_DIR/results.md"
