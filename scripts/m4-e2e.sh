#!/usr/bin/env bash
# M4 end to end, through docker compose:
#
#   replayer ticks -> Redpanda -> shards -> margin events in Postgres
#   POST /orders   -> Redpanda -> shard's pre-trade check -> writers -> order ACCEPTED in Postgres and in GET /orders/{id}
#
# It runs as its own compose project ("risk-e2e") on its own ports and volumes, so it never touches the
# default stack or its data, and removes everything it created when it finishes.
# The market data is SYNTHETIC (replayer demo): this checks the plumbing, not 4 June 2024.
# Needs Docker with the compose plugin, a JDK 21+, curl.
set -euo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=risk-e2e
export POSTGRES_PORT=55432 REDPANDA_PORT=39092 REDPANDA_ADMIN_PORT=39644 REDIS_PORT=56379 API_PORT=58080 METRICS_PORT=59100
export JWT_SECRET="e2e-only-secret-0123456789-abcdefghijklmnop"
export DATA_DIR="$PWD/build/e2e-data"
API="http://localhost:$API_PORT"
R=replayer/build/install/replayer/bin/replayer
BODY=$(mktemp)

psql() { docker compose exec -T postgres psql -U risk -d risk -v ON_ERROR_STOP=1 -tA "$@"; }
pass() { echo "  ok  $*"; }
fail() { echo "FAIL  $*" >&2; docker compose --profile app logs --no-color --tail 40 shard-host writers api book-loader >&2 || true; exit 1; }
cleanup() { rm -f "$BODY"; docker compose --profile app down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
wait_for() { # wait_for "what" command...
  local what="$1"; shift
  for _ in $(seq 1 120); do if "$@" >/dev/null 2>&1; then return 0; fi; sleep 1; done
  fail "timed out waiting for $what"
}

echo "== synthetic data (replayer demo -> bars -> seed)"
./gradlew -q :replayer:installDist
rm -rf "$DATA_DIR"
$R demo --out "$DATA_DIR" >/dev/null
$R bars --in "$DATA_DIR/raw/SYNTHETIC-NIFTY-1m.csv" --from 2024-06-03 --to 2024-06-04 --out "$DATA_DIR/processed/NIFTY-1m.csv" >/dev/null
$R bars --in "$DATA_DIR/raw/SYNTHETIC-INDIAVIX.csv" --out "$DATA_DIR/processed/INDIAVIX.csv" >/dev/null
$R seed --instruments "$DATA_DIR/processed/instruments-2024-06-03.csv" --vix 20.90 --out "$DATA_DIR/seed" --accounts 200 >/dev/null
accounts=$(($(wc -l < "$DATA_DIR/seed/accounts.csv") - 1))
pass "$accounts accounts seeded from synthetic instruments"

echo "== docker compose --profile app up (project $COMPOSE_PROJECT_NAME)"
cleanup
docker compose --profile app build -q
docker compose --profile app up -d >/dev/null 2>&1 || fail "compose up"
wait_for "the API" sh -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' $API/orders/x)\" = 401 ]"
wait_for "the shard host" curl -sf "http://localhost:$METRICS_PORT/healthz"
[ "$(psql -c 'select count(*) from accounts')" = "$accounts" ] || fail "book not loaded into Postgres"
pass "API, shard host and writers are up; $accounts accounts and $(psql -c 'select count(*) from positions') positions in Postgres"

echo "== replayer ticks -> Redpanda -> shards -> Postgres"
out=$($R ticks --bars "$DATA_DIR/processed/NIFTY-1m.csv" --vix "$DATA_DIR/processed/INDIAVIX.csv" \
         --from 2024-06-04 --to 2024-06-04 --redpanda "localhost:$REDPANDA_PORT")
pass "$out"
again=$($R ticks --bars "$DATA_DIR/processed/NIFTY-1m.csv" --vix "$DATA_DIR/processed/INDIAVIX.csv" \
         --from 2024-06-04 --to 2024-06-04 --redpanda "localhost:$REDPANDA_PORT" 2>&1 || true)
echo "$again" | grep -q "already holds ticks" || fail "a second tick run was not refused: $again"
pass "writing the ticks a second time is refused"
caught_up() { curl -sf "http://localhost:$METRICS_PORT/metrics" | awk '/^risk_shard_applied_offset/ {s+=$2} END {exit !(s >= 1500)}'; }
wait_for "the shards to apply 375 ticks x 4 partitions" caught_up
lagged() { curl -sf "http://localhost:$METRICS_PORT/metrics" | awk '/^risk_shard_lag_events/ {s+=$2} END {exit !(s == 0)}'; }
wait_for "shard lag to reach 0" lagged
pass "per-shard lag is 0: $(curl -sf "http://localhost:$METRICS_PORT/metrics" | grep '^risk_shard_applied_offset' | tr '\n' ' ')"
margin_rows() { [ "$(psql -c 'select count(*) from margin_events')" -gt 0 ]; }
wait_for "margin events in Postgres" margin_rows
sleep 2
pass "the synthetic crash produced $(psql -c "select string_agg(action || ' ' || n, ', ' order by action) from (select action, count(*) n from margin_events group by action) t") in margin_events"

echo "== a valid order: API -> shard -> Postgres"
# An option buyer never needs margin, only the premium; pick the one with the most cash.
account=$(awk -F, '$2 == "OPTION_BUYER" {print $4, $1}' "$DATA_DIR/seed/accounts.csv" | sort -rn | head -1 | cut -d' ' -f2)
token=$(./gradlew -q :api:devToken --args="trader e2e-user $account" | tail -1)
body='{"accountId":"'$account'","instrumentId":"NIFTY-20240613-25000-CE","side":"BUY","qty":25}'
post() { curl -s -o "$BODY" -w '%{http_code}' -X POST "$API/orders" -H "Authorization: Bearer $token" -H "X-Request-Id: $1" -H 'Content-Type: application/json' -d "$body"; }
code=$(post e2e-request-0001)
[ "$code" = 202 ] || fail "POST /orders answered $code: $(cat "$BODY")"
order=$(sed -E 's/.*"orderId":"([^"]+)".*/\1/' "$BODY")
pass "202 Accepted for $account, order $order"
accepted() { [ "$(psql -c "select status from orders where id = '$order'")" = ACCEPTED ]; }
wait_for "the order to be ACCEPTED in Postgres" accepted
pass "orders row in Postgres: $(psql -c "select id || ' ' || account_id || ' ' || instrument_id || ' ' || side || ' ' || qty || ' ' || status from orders where id = '$order'")"
[ "$(psql -c "select count(*) from audit_log where request_id = '$order' and action = 'ORDER_ACCEPTED'")" = 1 ] || fail "no audit row for the decision"
pass "audit_log: $(psql -c "select actor || ' ' || action || ' ' || details::text from audit_log where request_id = '$order'")"
curl -s "$API/orders/$order" -H "Authorization: Bearer $token" | grep -q '"status":"ACCEPTED"' || fail "GET /orders/$order does not show ACCEPTED"
pass "GET /orders/$order shows ACCEPTED"

echo "== the security checks, over real HTTP"
[ "$(post e2e-request-0001)" = 409 ] || fail "replayed request id was not rejected"
pass "same X-Request-Id again -> 409"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders" -H 'Content-Type: application/json' -d "$body")" = 401 ] || fail "no token was not 401"
pass "no token -> 401"
other='{"accountId":"A0000001x","instrumentId":"NIFTY-20240613-25000-CE","side":"BUY","qty":25}'
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders" -H "Authorization: Bearer $token" -H 'X-Request-Id: e2e-request-0002' -H 'Content-Type: application/json' -d "$other")" = 403 ] || fail "another account was not 403"
pass "someone else's account -> 403"
limited=0
for i in $(seq 1 40); do
  [ "$(curl -s -o /dev/null -w '%{http_code}' "$API/orders/$order" -H "Authorization: Bearer $token")" = 429 ] && limited=$((limited + 1))
done
[ "$limited" -gt 0 ] || fail "40 rapid requests were never rate limited"
pass "40 rapid requests -> $limited answered 429"

echo "M4 end-to-end passed."
