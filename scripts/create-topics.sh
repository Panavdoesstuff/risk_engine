#!/usr/bin/env bash
# Idempotent topic setup. engine-events and engine-out share a partition count so
# a shard's input partition and output partition line up (hash(accountId) % N).
set -euo pipefail
N="${ENGINE_PARTITIONS:-4}"

ensure_topic() {
  local name="$1"
  if rpk topic describe "$name" >/dev/null 2>&1; then
    local have
    have=$(rpk topic describe "$name" -p | tail -n +2 | wc -l | tr -d ' ')
    if [ "$have" != "$N" ]; then
      echo "ERROR: topic $name has $have partitions, expected $N (delete it or set ENGINE_PARTITIONS)" >&2
      exit 1
    fi
    echo "topic $name exists ($N partitions)"
  else
    rpk topic create "$name" -p "$N" -r 1 -c cleanup.policy=delete -c retention.ms=-1
  fi
}

ensure_topic engine-events
ensure_topic engine-out
