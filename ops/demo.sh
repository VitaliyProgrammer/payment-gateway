#!/usr/bin/env bash
# End-to-end walk-through against a running `docker compose up` stack.
# Uses the demo merchant seeded by V3/V5 (works only against the local DB).
#
#   docker compose up --build      # in one terminal
#   ops/demo.sh                    # in another
set -euo pipefail

BASE="${BASE_URL:-http://localhost:8080}"
KEY="${API_KEY:-demo-merchant-api-key}"

bold() { printf '\n\033[1m== %s ==\033[0m\n' "$1"; }
pp()   { if command -v jq >/dev/null 2>&1; then jq .; else cat; echo; fi; }
newkey() { echo "demo-$(date +%s%N)-${RANDOM}"; }
field()  { sed -nE "s/.*\"$1\":\"?([^\",}]+)\"?.*/\1/p"; }

bold "1. create a payment (5000 UAH)"
created=$(curl -sS -X POST "$BASE/v1/payments" \
  -H "Authorization: Bearer $KEY" -H "Idempotency-Key: $(newkey)" \
  -H 'Content-Type: application/json' -d '{"amount":5000,"currency":"UAH"}')
echo "$created" | pp
id=$(echo "$created" | field id)

bold "2. poll until the acquirer call settles"
status=""
for _ in $(seq 1 20); do
  got=$(curl -sS "$BASE/v1/payments/$id" -H "Authorization: Bearer $KEY")
  status=$(echo "$got" | field status)
  echo "   status=$status"
  case "$status" in AUTHORIZED|DECLINED|FAILED) break ;; esac
  sleep 0.5
done
echo "$got" | pp

if [ "$status" = "AUTHORIZED" ]; then
  bold "3. capture the full amount"
  curl -sS -X POST "$BASE/v1/payments/$id/capture" -H "Authorization: Bearer $KEY" | pp

  bold "4. refund 2000 of it (partial refund)"
  curl -sS -X POST "$BASE/v1/payments/$id/refunds" -H "Authorization: Bearer $KEY" \
    -H 'Content-Type: application/json' -d '{"amount":2000}' | pp
fi

bold "5. idempotency: the same key twice returns the same payment id"
k=$(newkey)
for n in 1 2; do
  rid=$(curl -sS -X POST "$BASE/v1/payments" -H "Authorization: Bearer $KEY" \
    -H "Idempotency-Key: $k" -H 'Content-Type: application/json' \
    -d '{"amount":777,"currency":"UAH"}' | field id)
  printf '   call %s -> %s\n' "$n" "$rid"
done

bold "6. a saturated queue answers 503, it does not hang"
echo "   (only visible under load - see ops/benchmark/run-benchmark.sh)"
