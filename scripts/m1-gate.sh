#!/usr/bin/env bash
# M1 gate: infrastructure comes up from nothing, schema + topics are correct, and the seeded book is
# reproducible. Run from the repo root. Needs Docker with the compose plugin and a JDK 21+.
set -euo pipefail
cd "$(dirname "$0")/.."

PARTITIONS="${ENGINE_PARTITIONS:-4}"
psql() { docker compose exec -T postgres psql -U risk -d risk -v ON_ERROR_STOP=1 -tA "$@"; }
pass() { echo "  ok  $*"; }
fail() { echo "FAIL  $*" >&2; exit 1; }

echo "== infrastructure"
docker compose up -d --wait postgres redpanda redis
docker compose run --rm flyway >/dev/null
docker compose run --rm topic-init
pass "postgres, redpanda, redis healthy; migrations applied; topics ensured"

echo "== schema"
expected="accounts audit_log engine_snapshots fills instruments ledger_entries margin_events orders outbox positions square_off_episodes square_off_legs"
actual=$(psql -c "select string_agg(table_name, ' ' order by table_name) from information_schema.tables
                  where table_schema = 'public' and table_name <> 'flyway_schema_history'")
[ "$actual" = "$expected" ] || fail "tables: got [$actual]"
pass "all tables present"

if psql -c "begin; insert into ledger_entries (txn_id, ledger_account, amount, reason, log_time)
            values ('gate-unbalanced', 'CASH', 100, 'GATE', now()); commit;" >/dev/null 2>&1; then
  fail "unbalanced ledger txn was accepted"
fi
psql -c "begin;
         insert into ledger_entries (txn_id, ledger_account, amount, reason, log_time) values
           ('gate-balanced', 'CASH', 100, 'GATE', now()), ('gate-balanced', 'BROKER_CLEARING', -100, 'GATE', now());
         commit;
         delete from ledger_entries where txn_id = 'gate-balanced';" >/dev/null
pass "ledger rejects unbalanced txn, accepts balanced one"

psql -c "insert into audit_log (actor, action, log_time, details) values ('gate', 'GATE', now(), '{}');" >/dev/null
if psql -c "update audit_log set action = 'X' where actor = 'gate';" >/dev/null 2>&1; then
  fail "audit_log accepted an UPDATE"
fi
pass "audit_log is append-only"

echo "== topics"
for t in engine-events engine-out; do
  n=$(docker compose exec -T redpanda rpk topic describe "$t" -p | tail -n +2 | grep -c .)
  [ "$n" = "$PARTITIONS" ] || fail "$t has $n partitions, expected $PARTITIONS"
  pass "$t has $n partitions"
done

echo "== seeded book reproducibility"
./gradlew -q :replayer:test --tests 'riskengine.replayer.seed.SeedGeneratorTest'
pass "same seed -> byte-identical accounts.csv / positions.csv / book.meta"
if [ -f data/processed/instruments-2024-06-03.csv ]; then
  ./gradlew -q :replayer:installDist
  R=replayer/build/install/replayer/bin/replayer
  tmp=$(mktemp -d)
  $R seed --instruments data/processed/instruments-2024-06-03.csv --out "$tmp/a" >/dev/null
  $R seed --instruments data/processed/instruments-2024-06-03.csv --out "$tmp/b" >/dev/null
  diff -r "$tmp/a" "$tmp/b" >/dev/null || fail "real-data seed runs differ"
  pass "real 3 June 2024 instruments: two runs byte-identical"
  rm -rf "$tmp"
else
  echo "  --  skipped real-data check (data/processed/instruments-2024-06-03.csv not built yet; see data/README.md)"
fi

echo "M1 gate passed."
