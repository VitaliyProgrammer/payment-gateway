#!/usr/bin/env bash
# One command to see the whole project working:
#   ./run.sh                build the images, start the stack, walk a payment
#                           through its lifecycle (create -> authorize -> capture
#                           -> refund), then leave everything running
#   ./run.sh --monitoring   also start Prometheus + Grafana
#   ./run.sh --no-demo      start the stack, skip the walk-through
#   ./run.sh down           stop and remove everything
#
# First run compiles the modules inside the build image (~3-5 min). After that,
# `docker compose -f docker-compose.prod.yml up` pulls pre-built images instead.
set -euo pipefail
cd "$(dirname "$0")"

if [ "${1:-}" = "down" ]; then
  docker compose --profile monitoring down -v
  exit 0
fi

PROFILE=()
DEMO=1
for arg in "$@"; do
  case "$arg" in
    --monitoring) PROFILE=(--profile monitoring) ;;
    --no-demo)    DEMO=0 ;;
    *) echo "unknown option: $arg" >&2; exit 2 ;;
  esac
done

GW="http://localhost:${GATEWAY_PORT:-8080}"

echo "==> building images and starting the stack"
docker compose "${PROFILE[@]}" up -d --build

echo "==> waiting for the gateway at $GW"
for _ in $(seq 1 90); do
  curl -sf "$GW/actuator/health" 2>/dev/null | grep -q '"status":"UP"' && break
  sleep 2
done

if [ "$DEMO" = 1 ]; then
  echo
  BASE_URL="$GW" bash ops/demo.sh
fi

echo
echo "==> up and running:"
echo "     API         $GW           merchant key: demo-merchant-api-key"
echo "     health      $GW/actuator/health"
echo "     metrics     $GW/actuator/prometheus"
if [ "${#PROFILE[@]}" -gt 0 ]; then
  echo "     Prometheus  http://localhost:${PROMETHEUS_PORT:-9091}"
  echo "     Grafana     http://localhost:${GRAFANA_PORT:-3000}   (anonymous; dashboard 'Payflow gateway')"
fi
echo "     stop        ./run.sh down"
