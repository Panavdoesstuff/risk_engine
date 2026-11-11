#!/usr/bin/env bash
# End to end, through docker compose (M4, extended in M5):
#
#   replayer ticks -> Redpanda -> shards -> margin events in Postgres
#   POST /orders   -> Redpanda -> shard's pre-trade check -> writers -> order ACCEPTED in Postgres and in GET /orders/{id}
#   M5: accepted orders and square-off legs -> outbox -> relay -> mock broker (full chaos) -> fills -> ledger;
#       the synthetic fall squares accounts off; risk-ops blocks and unblocks an account over HTTP
#   M6: synthetic live ticks at 50/s -> tick-to-margin histogram scraped by Prometheus; Grafana serves the dashboard;
#       kill -9 the shard host, start it again: it restores from its snapshots and catches up
#
# It runs as its own compose project ("risk-e2e") on its own ports and volumes, so it never touches the
# default stack or its data, and removes everything it created when it finishes.
# The market data is SYNTHETIC (replayer demo): this checks the plumbing, not 4 June 2024.
# Needs Docker with the compose plugin, a JDK 21+, curl.
set -Eeuo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=risk-e2e
export POSTGRES_PORT=55432 REDPANDA_PORT=39092 REDPANDA_ADMIN_PORT=39644 REDIS_PORT=56379 API_PORT=58080 METRICS_PORT=59100 BROKER_PORT=59300 PROMETHEUS_PORT=59090 GRAFANA_PORT=53000
export BROKER_CHAOS=full
export JWT_SECRET="e2e-only-secret-0123456789-abcdefghijklmnop"
export DATA_DIR="$PWD/build/e2e-data"
API="http://localhost:$API_PORT"
R=replayer/build/install/replayer/bin/replayer
BODY=$(mktemp)
LOG=$(mktemp)

psql() { docker compose exec -T postgres psql -U risk -d risk -v ON_ERROR_STOP=1 -tA "$@"; }
pass() { echo "  ok  $*"; }
# On failure, show everything needed to see why without re-running: what compose said, the state of every
# container, and the tail of every service's log.
fail() {
  echo "FAIL  $*" >&2
  { echo "---- docker compose output"; cat "$LOG"
    echo "---- containers"; docker compose --profile app ps -a
    echo "---- service logs"; docker compose --profile app logs --no-color --tail 60; } >&2 2>&1 || true
  exit 1
}
cleanup() { rm -f "$BODY" "$LOG"; docker compose --profile app down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT
# Any command that fails outside a check (set -e would otherwise end the script without a word, which is
# how a Grafana that was not up yet once made a run stop silently) reports where it happened, with the
# same diagnostics as a failed check.
trap 'fail "unexpected failure (exit $?) at line $LINENO: $BASH_COMMAND"' ERR
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
docker compose --profile app down -v --remove-orphans >/dev/null 2>&1 || true
docker compose --profile app build >"$LOG" 2>&1 || fail "docker compose build"
docker compose --profile app up -d >"$LOG" 2>&1 || fail "docker compose up"
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
# Once accepted the order goes on to the broker, so by the time we look it may be SENT or FILLED already.
decided() { psql -c "select status from orders where id = '$order'" | grep -Eq '^(ACCEPTED|SENT|PARTIALLY_FILLED|FILLED|BROKER_REJECTED)$'; }
wait_for "the shard to accept the order" decided
[ "$(psql -c "select count(*) from audit_log where request_id = '$order' and action = 'ORDER_ACCEPTED'")" = 1 ] || fail "no audit row for the decision"
pass "accepted by the shard: $(psql -c "select actor || ' ' || action || ' ' || details::text from audit_log where request_id = '$order'")"
done_at_broker() { psql -c "select status from orders where id = '$order'" | grep -Eq '^(FILLED|BROKER_REJECTED)$'; }
wait_for "the broker to fill (or reject) the order" done_at_broker
final=$(psql -c "select status from orders where id = '$order'")
if [ "$final" = FILLED ]; then
  [ "$(psql -c "select coalesce(sum(qty), 0) from fills where order_id = '$order'")" = 25 ] || fail "fills for $order do not add up to 25"
  [ "$(psql -c "select count(*) from ledger_entries where ref_id in (select broker_fill_id from fills where order_id = '$order')")" -ge 2 ] || fail "no ledger entries for the premium paid"
fi
pass "orders row: $(psql -c "select id || ' ' || account_id || ' ' || instrument_id || ' ' || side || ' ' || qty || ' ' || status || ' required_after ' || required_after from orders where id = '$order'"); $(psql -c "select count(*) from fills where order_id = '$order'") fill(s)"
view=$(curl -s "$API/orders/$order" -H "Authorization: Bearer $token" || true)
echo "$view" | grep -q "\"status\":\"$final\"" && echo "$view" | grep -q '"requiredAfter"' || fail "GET /orders/$order: $view"
pass "GET /orders/$order: $view"

echo "== square-off under full broker chaos"
drained() {
  [ "$(psql -c "select count(*) from square_off_episodes where status = 'OPEN'")" = 0 ] &&
  [ "$(psql -c "select count(*) from outbox where sent_at is null")" = 0 ] &&
  [ "$(psql -c "select count(*) from square_off_legs where status in ('PLANNED', 'SENT')")" = 0 ]
}
# "Finished" must hold for a while, not just at one instant: a leg rejected at the last moment is re-planned,
# and Postgres sees the new leg a batch later.
drained_steadily() { drained && sleep 2 && drained && sleep 2 && drained; }
wait_for "every square-off episode to finish (and stay finished)" drained_steadily
episodes=$(psql -c "select count(*) from square_off_episodes")
[ "$episodes" -gt 0 ] || fail "the synthetic fall squared nobody off"
pass "$episodes episodes: $(psql -c "select string_agg(status || ' ' || n, ', ') from (select status, count(*) n from square_off_episodes group by status) t"); $(psql -c "select string_agg(status || ' ' || n, ', ') from (select status, count(*) n from square_off_legs group by status) t") legs"
overfilled=$(psql -c "select count(*) from square_off_legs l left join (select order_id, sum(qty) q from fills group by order_id) f on f.order_id = l.leg_key where coalesce(f.q, 0) <> case l.status when 'FILLED' then l.qty else 0 end")
[ "$overfilled" = 0 ] || fail "$overfilled legs whose fills do not add up to the leg"
pass "every leg filled exactly once or rejected: 0 legs closed twice"
unbalanced=$(psql -c "select count(*) from (select txn_id from ledger_entries group by txn_id having sum(amount) <> 0) t")
[ "$unbalanced" = 0 ] || fail "$unbalanced unbalanced ledger transactions"
pass "$(psql -c "select count(distinct txn_id) from ledger_entries") ledger transactions, all summing to zero; $(psql -c "select count(*) from fills") fills"
pass "broker: $(curl -s "http://localhost:$BROKER_PORT/stats" || echo unavailable)"

echo "== monitoring: synthetic live ticks -> Prometheus -> Grafana"
synth=$($R synth --redpanda "localhost:$REDPANDA_PORT" --rate 50 --seconds 10)
pass "$synth"
promq() { curl -s --get "http://localhost:$PROMETHEUS_PORT/api/v1/query" --data-urlencode "query=$1" | sed -E 's/.*"value":\[[^,]+,"([^"]+)".*/\1/' || true; }
scraped() { promq 'sum(risk_tick_to_margin_seconds_count)' | awk '{exit !($1 >= 2000)}'; }
scraped_or_explain() {
  for _ in $(seq 1 120); do scraped && return 0; sleep 1; done
  fail "Prometheus did not reach 2000 tick-latency samples: count=$(promq 'sum(risk_tick_to_margin_seconds_count)') per shard=$(promq 'risk_tick_to_margin_seconds_count' ) target=$(curl -s "http://localhost:$PROMETHEUS_PORT/api/v1/targets" | grep -oE '"(health|lastError)":"[^"]*"' | tr '\n' ' ') shard host says: $(curl -sf "http://localhost:$METRICS_PORT/metrics" | grep -E '^risk_(tick_to_margin_seconds_count|ticks_applied_total|shard_applied_offset)' | tr '\n' ' ')"
}
scraped_or_explain
P99='histogram_quantile(0.99, sum by (le) (rate(risk_tick_to_margin_seconds_bucket[2m])))'
has_p99() { promq "$P99" | grep -Eq '^[0-9.]+(e[+-]?[0-9]+)?$'; }
wait_for "Prometheus to compute a p99" has_p99
p50=$(promq 'histogram_quantile(0.5, sum by (le) (rate(risk_tick_to_margin_seconds_bucket[2m])))')
p99=$(promq "$P99")
tps=$(promq 'min(rate(risk_ticks_applied_total[30s]))')
skew=$(promq 'sum(risk_tick_clock_skew_total)')
pass "Prometheus (the dashboard's own queries): p50 $p50 s, p99 $p99 s, slowest shard $tps ticks/s; $skew ticks measured as 0 because the host's clock was ahead of the VM's"
# Grafana is the slowest service to start (it migrates its own database on first start, and on a busy
# machine that takes a while): wait for it rather than ask once.
dashboard() { curl -sf "http://localhost:$GRAFANA_PORT/api/dashboards/uid/risk-engine" | grep -q '"title":"Risk engine"'; }
wait_for "Grafana to serve the Risk engine dashboard" dashboard
datasource() { curl -sf "http://localhost:$GRAFANA_PORT/api/datasources/proxy/uid/prometheus/api/v1/query?query=risk_shard_up" | grep -q '"status":"success"'; }
wait_for "Grafana to query Prometheus through its data source" datasource
charts=$(curl -sf "http://localhost:$GRAFANA_PORT/api/dashboards/uid/risk-engine" | grep -o '"type":"timeseries"' | wc -l | tr -d ' ')
pass "Grafana serves the Risk engine dashboard ($charts charts) and queries Prometheus through its data source"

echo "== recovery: kill -9 the shard host and start it again"
before=$(curl -sf "http://localhost:$METRICS_PORT/metrics" | awk '/^risk_shard_applied_offset/ {s+=$2} END {print s}')
docker compose --profile app kill -s KILL shard-host >/dev/null 2>&1
docker compose --profile app start shard-host >/dev/null 2>&1
wait_for "the shard host to come back" curl -sf "http://localhost:$METRICS_PORT/healthz"
restored() { [ "$(docker compose --profile app logs --no-color shard-host | grep -c 'restored from')" -ge 4 ]; }
wait_for "all 4 shards to restore from a snapshot" restored
caught_up_again() { curl -sf "http://localhost:$METRICS_PORT/metrics" | awk -v want="$before" '/^risk_shard_applied_offset/ {s+=$2} /^risk_shard_lag_events/ {l+=$2} END {exit !(s >= want && l == 0)}'; }
wait_for "the restarted shards to catch up" caught_up_again
pass "$(docker compose --profile app logs --no-color shard-host | grep 'restored from' | tail -4 | sed -E 's/.*\| //' | tr '\n' ';' | sed 's/;$//')"
pass "caught up again: lag 0, applied offset >= $before"

echo "== risk-ops over real HTTP"
ops=$(./gradlew -q :api:devToken --args="risk-ops e2e-ops" | tail -1)
control() { curl -s -o /dev/null -w '%{http_code}' -X POST "$API/accounts/$account/$1" -H "Authorization: Bearer $ops" -H "X-Request-Id: $2"; }
[ "$(control block e2e-control-0001)" = 202 ] || fail "block was not accepted"
blocked() { [ "$(psql -c "select status from accounts where id = '$account'")" = BLOCKED ]; }
wait_for "the account to be BLOCKED" blocked
[ "$(control block e2e-control-0001)" = 409 ] || fail "a replayed block was not rejected"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/accounts/$account/block" -H "Authorization: Bearer $token" -H 'X-Request-Id: e2e-control-0002')" = 403 ] || fail "a trader could block"
[ "$(control unblock e2e-control-0003)" = 202 ] || fail "unblock was not accepted"
unblocked() { [ "$(psql -c "select status from accounts where id = '$account'")" = ACTIVE ]; }
wait_for "the account to be ACTIVE again" unblocked
pass "block -> BLOCKED, same request again -> 409, a trader -> 403, unblock -> ACTIVE ($(psql -c "select count(*) from audit_log where account_id = '$account' and actor = 'risk-ops:e2e-ops'") audit rows by risk-ops:e2e-ops)"

echo "== the security checks, over real HTTP"
[ "$(post e2e-request-0001)" = 409 ] || fail "replayed request id was not rejected"
pass "same X-Request-Id again -> 409"
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders" -H 'Content-Type: application/json' -d "$body")" = 401 ] || fail "no token was not 401"
pass "no token -> 401"
other='{"accountId":"A0000001x","instrumentId":"NIFTY-20240613-25000-CE","side":"BUY","qty":25}'
[ "$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API/orders" -H "Authorization: Bearer $token" -H 'X-Request-Id: e2e-request-0002' -H 'Content-Type: application/json' -d "$other")" = 403 ] || fail "another account was not 403"
pass "someone else's account -> 403"
# 60 requests, 30 at a time: faster than the bucket can refill however slow the machine is. (One at a time,
# on a busy machine, 40 requests can take long enough for the bucket to refill and nothing is refused.)
codes=$(seq 1 60 | xargs -P 30 -I{} curl -s -o /dev/null -w '%{http_code}\n' "$API/orders/$order" -H "Authorization: Bearer $token" || true)
limited=$(echo "$codes" | grep -c '^429$' || true)
[ "$limited" -gt 0 ] || fail "60 requests at once were never rate limited: $(echo "$codes" | sort | uniq -c | tr '\n' ' ')"
pass "60 requests at once -> $limited answered 429"

echo "End-to-end passed."
