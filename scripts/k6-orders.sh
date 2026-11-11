#!/usr/bin/env bash
# k6 load test of POST /orders (the API alone), against its own compose stack ("risk-k6") with synthetic data.
# Prints k6's summary: requests/s, latency p50/p99, and the share of 429s. Needs Docker, a JDK 21+.
set -Eeuo pipefail
cd "$(dirname "$0")/.."

export COMPOSE_PROJECT_NAME=risk-k6
export POSTGRES_PORT=56432 REDPANDA_PORT=49092 REDPANDA_ADMIN_PORT=49644 REDIS_PORT=57379 API_PORT=59080 METRICS_PORT=59101 BROKER_PORT=59301 PROMETHEUS_PORT=59091 GRAFANA_PORT=53001
export BROKER_CHAOS=none
export JWT_SECRET="k6-only-secret-0123456789-abcdefghijklmnopq"
export DATA_DIR="$PWD/build/k6-data"
R=replayer/build/install/replayer/bin/replayer
cleanup() { docker compose --profile app down -v --remove-orphans >/dev/null 2>&1 || true; }
trap cleanup EXIT

./gradlew -q :replayer:installDist
rm -rf "$DATA_DIR"
$R demo --out "$DATA_DIR" >/dev/null
$R bars --in "$DATA_DIR/raw/SYNTHETIC-NIFTY-1m.csv" --from 2024-06-03 --to 2024-06-04 --out "$DATA_DIR/processed/NIFTY-1m.csv" >/dev/null
$R bars --in "$DATA_DIR/raw/SYNTHETIC-INDIAVIX.csv" --out "$DATA_DIR/processed/INDIAVIX.csv" >/dev/null
$R seed --instruments "$DATA_DIR/processed/instruments-2024-06-03.csv" --vix 20.90 --out "$DATA_DIR/seed" --accounts 200 >/dev/null
cleanup
docker compose --profile app build -q
docker compose --profile app up -d >/dev/null 2>&1
for _ in $(seq 1 120); do [ "$(curl -s -o /dev/null -w '%{http_code}' "http://localhost:$API_PORT/orders/x" || true)" = 401 ] && break; sleep 1; done
# A market, so the shards can check the orders (the API does not care, but the shards then reject NO_MARKET).
$R ticks --bars "$DATA_DIR/processed/NIFTY-1m.csv" --vix "$DATA_DIR/processed/INDIAVIX.csv" --from 2024-06-04 --to 2024-06-04 --redpanda "localhost:$REDPANDA_PORT" >/dev/null
accounts=$(tail -n +2 "$DATA_DIR/seed/accounts.csv" | cut -d, -f1 | head -100 | paste -sd, -)
docker run --rm -i --network "${COMPOSE_PROJECT_NAME}_default" -e JWT_SECRET -e ACCOUNTS="$accounts" -e API=http://api:8080 \
  -e USERS="${USERS:-100}" -e RATE="${RATE:-1000}" -e DURATION="${DURATION:-60s}" grafana/k6:0.54.0 run --quiet - < loadtest/k6/orders.js
