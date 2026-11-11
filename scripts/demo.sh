#!/usr/bin/env bash
# One command: bring up the whole engine in docker compose, open Grafana, replay 4 June 2024, and watch
# the margin alerts and square-offs fire.
#
#   ./scripts/demo.sh
#
# Real data if data/raw/ has it (see data/README.md): the NSE bhavcopy for 3 June 2024 and Upstox
# one-minute candles for Nifty and India VIX. Otherwise SYNTHETIC data (made-up prices with a fall like the
# real one), clearly labelled: the plumbing is the same, the numbers are not 4 June 2024.
#
# It runs as its own compose project ("risk-demo") on its own ports and volumes, so it never touches the
# default stack. It starts from scratch every time (the previous demo's volumes are removed first) and leaves
# the stack running so you can look around. Stop it with:
#   docker compose -p risk-demo --profile app down -v
#
# Settings (environment):
#   DEMO_ACCOUNTS  accounts in the seeded book (default 20000; the crash-day report uses 100000)
#   DEMO_PACE_MS   wall-clock pause between minute ticks (default 150: the day plays in about a minute)
#   BROKER_CHAOS   full (default: timeouts, rejects, duplicate callbacks) or none
#   GRAFANA_PORT   default 43000; the other ports are in the export line below
#   DEMO_NO_OPEN=1 do not open a browser
# Needs Docker with the compose plugin, a JDK 21+ and curl.
set -Eeuo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=risk-demo
export POSTGRES_PORT="${POSTGRES_PORT:-45432}" REDPANDA_PORT="${REDPANDA_PORT:-47092}" REDPANDA_ADMIN_PORT="${REDPANDA_ADMIN_PORT:-47644}" \
       REDIS_PORT="${REDIS_PORT:-46379}" API_PORT="${API_PORT:-48080}" METRICS_PORT="${METRICS_PORT:-49100}" BROKER_PORT="${BROKER_PORT:-49300}" \
       PROMETHEUS_PORT="${PROMETHEUS_PORT:-49090}" GRAFANA_PORT="${GRAFANA_PORT:-43000}"
export BROKER_CHAOS="${BROKER_CHAOS:-full}"
export DATA_DIR="$PWD/build/demo-data"
ACCOUNTS="${DEMO_ACCOUNTS:-20000}"
PACE_MS="${DEMO_PACE_MS:-150}"
R=replayer/build/install/replayer/bin/replayer
RAW=data/raw
GRAFANA="http://localhost:$GRAFANA_PORT"
DASHBOARD="$GRAFANA/d/risk-engine/risk-engine?orgId=1&refresh=2s&from=now-5m&to=now&kiosk"

step() { printf '\n== %s\n' "$*"; }
fail() {
  echo "FAIL  $*" >&2
  { echo "---- containers"; docker compose --profile app ps -a; echo "---- service logs"; docker compose --profile app logs --no-color --tail 40; } >&2 2>&1 || true
  echo "Stop the demo with: docker compose -p risk-demo --profile app down -v" >&2
  exit 1
}
trap 'fail "unexpected failure (exit $?) at line $LINENO: $BASH_COMMAND"' ERR
wait_for() { # wait_for "what" seconds command...
  local what="$1" secs="$2"; shift 2
  for _ in $(seq 1 "$secs"); do if "$@" >/dev/null 2>&1; then return 0; fi; sleep 1; done
  fail "timed out waiting for $what"
}
psql() { docker compose exec -T postgres psql -U risk -d risk -v ON_ERROR_STOP=1 -tA "$@"; }
# Sum of a shard-host metric over its shards, for one label (e.g. alerts risk_margin_alerts_total 'level="WARNING"').
metric_sum() { curl -sf "http://localhost:$METRICS_PORT/metrics" | awk -v m="$1" -v l="$2" 'index($0, m "{") == 1 && index($0, l) {s += $NF} END {printf "%d", s}'; }

for tool in docker curl java; do command -v "$tool" >/dev/null || { echo "demo.sh needs $tool" >&2; exit 1; }; done
docker compose version >/dev/null 2>&1 || { echo "demo.sh needs the docker compose plugin" >&2; exit 1; }

step "market data and the start-of-day book ($ACCOUNTS accounts)"
./gradlew -q :replayer:installDist
rm -rf "$DATA_DIR"
mkdir -p "$DATA_DIR/processed"
if [ -f "$RAW/fo03JUN2024bhav.csv" ] && [ -f "$RAW/nifty_1min_2024-06-03_04.json" ] && [ -f "$RAW/vix_1min_2024-06-03_04.json" ]; then
  KIND=REAL
  $R instruments --bhavcopy "$RAW/fo03JUN2024bhav.csv" --lots data/reference/nifty_lots_2024-06-03.csv --date 2024-06-03 \
                 --out "$DATA_DIR/processed/instruments-2024-06-03.csv" >/dev/null
  $R bars --in "$RAW/nifty_1min_2024-06-03_04.json" --from 2024-06-03 --to 2024-06-04 --out "$DATA_DIR/processed/NIFTY-1m.csv" >/dev/null
  $R bars --in "$RAW/vix_1min_2024-06-03_04.json" --from 2024-06-03 --to 2024-06-04 --out "$DATA_DIR/processed/INDIAVIX.csv" >/dev/null
  VIX=20.94   # India VIX close on 3 June 2024 (decision 116)
  echo "  real data: NSE bhavcopy 3 June 2024, Upstox one-minute Nifty 50 and India VIX"
else
  KIND=SYNTHETIC
  $R demo --out "$DATA_DIR" >/dev/null
  $R bars --in "$DATA_DIR/raw/SYNTHETIC-NIFTY-1m.csv" --from 2024-06-03 --to 2024-06-04 --out "$DATA_DIR/processed/NIFTY-1m.csv" >/dev/null
  $R bars --in "$DATA_DIR/raw/SYNTHETIC-INDIAVIX.csv" --out "$DATA_DIR/processed/INDIAVIX.csv" >/dev/null
  VIX=20.90
  cat <<'EOF'
  ****************************************************************************************
  *  SYNTHETIC DATA: data/raw/ does not have the 4 June 2024 files (see data/README.md).  *
  *  The engine is the same; the prices are made up. Do not quote these numbers.          *
  ****************************************************************************************
EOF
fi
$R seed --instruments "$DATA_DIR/processed/instruments-2024-06-03.csv" --vix "$VIX" --out "$DATA_DIR/seed" --seed 42 --accounts "$ACCOUNTS" | head -1 | sed 's/^/  /'

step "docker compose --profile app up (project risk-demo; the first build takes a few minutes)"
docker compose --profile app down -v --remove-orphans >/dev/null 2>&1 || true
UPLOG="$DATA_DIR/compose-up.log"
docker compose --profile app up -d --build --quiet-pull >"$UPLOG" 2>&1 || { tail -40 "$UPLOG" >&2; fail "docker compose up (full output: $UPLOG)"; }
wait_for "the shard host" 300 curl -sf "http://localhost:$METRICS_PORT/healthz"
wait_for "the API" 120 sh -c "[ \"\$(curl -s -o /dev/null -w '%{http_code}' http://localhost:$API_PORT/orders/x)\" = 401 ]"
wait_for "Grafana" 120 curl -sf "$GRAFANA/api/health"
echo "  up: $(psql -c 'select count(*) from accounts') accounts and $(psql -c 'select count(*) from positions') positions in Postgres; broker chaos: $BROKER_CHAOS"

step "Grafana"
echo "  $DASHBOARD"
if [ -z "${DEMO_NO_OPEN:-}" ]; then
  if command -v open >/dev/null; then open "$DASHBOARD"; elif command -v xdg-open >/dev/null; then xdg-open "$DASHBOARD" >/dev/null 2>&1; fi
  sleep 8   # let the page load before the market opens
fi

step "replaying 4 June 2024 ($KIND data), one minute every ${PACE_MS} ms"
$R ticks --bars "$DATA_DIR/processed/NIFTY-1m.csv" --vix "$DATA_DIR/processed/INDIAVIX.csv" --from 2024-06-04 --to 2024-06-04 \
         --redpanda "localhost:$REDPANDA_PORT" --pace-ms "$PACE_MS" | sed 's/^/  /'
caught_up() { curl -sf "http://localhost:$METRICS_PORT/metrics" | awk '/^risk_shard_lag_events/ {s+=$2} END {exit !(s == 0)}'; }
wait_for "the shards to catch up" 300 caught_up
settled() {
  [ "$(psql -c "select count(*) from square_off_episodes where status = 'OPEN'")" = 0 ] &&
  [ "$(psql -c "select count(*) from outbox where sent_at is null")" = 0 ]
}
wait_for "square-offs to finish at the broker" 300 settled

step "what happened ($KIND data)"
echo "  alerts:       warnings $(metric_sum risk_margin_alerts_total 'level="WARNING"'), margin calls $(metric_sum risk_margin_alerts_total 'level="MARGIN_CALL"'), square-off level $(metric_sum risk_margin_alerts_total 'level="SQUARE_OFF"')"
echo "  square-offs:  $(psql -c "select string_agg(status || ' ' || n, ', ' order by status) from (select status, count(*) n from square_off_episodes group by status) t")"
echo "  legs:         $(psql -c "select string_agg(status || ' ' || n, ', ' order by status) from (select status, count(*) n from square_off_legs group by status) t")"
echo "  broker fills: $(psql -c 'select count(*) from fills'); ledger transactions not summing to zero: $(psql -c 'select count(*) from (select txn_id from ledger_entries group by txn_id having sum(amount) <> 0) t')"
echo
echo "Grafana:  $DASHBOARD"
echo "Postgres: psql postgresql://risk:risk@localhost:$POSTGRES_PORT/risk   (try: select * from margin_events order by log_time limit 20)"
echo "Stop:     docker compose -p risk-demo --profile app down -v"
