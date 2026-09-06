#!/usr/bin/env bash
# Regenerate docs/grafana-dashboard.png: bring up the monitoring stack, push a
# little load through the gateway so the panels have data, then ask Grafana to
# server-render the dashboard to PNG.
#
#   ops/grafana-screenshot.sh
set -euo pipefail
cd "$(dirname "$0")/.."

GRAFANA_PORT="${GRAFANA_PORT:-3000}"
OUT="docs/grafana-dashboard.png"

echo "==> starting the stack with the monitoring profile"
docker compose --profile monitoring up -d --build

echo "==> waiting for Grafana"
for _ in $(seq 1 60); do
  curl -sf "http://localhost:${GRAFANA_PORT}/api/health" >/dev/null 2>&1 && break
  sleep 2
done

echo "==> generating load (a few lifecycles)"
for _ in $(seq 1 15); do BASE_URL="http://localhost:8080" bash ops/demo.sh >/dev/null 2>&1 || true; done
sleep 10   # let Prometheus scrape

mkdir -p docs
echo "==> rendering dashboard -> $OUT"
curl -sf -u admin:admin \
  "http://localhost:${GRAFANA_PORT}/render/d/payflow-gateway/payflow-gateway?orgId=1&from=now-15m&to=now&width=1500&height=1900&theme=light&kiosk" \
  -o "$OUT"

echo "==> done: $OUT ($(wc -c < "$OUT") bytes)"
echo "    (stop the stack with ./run.sh down)"
