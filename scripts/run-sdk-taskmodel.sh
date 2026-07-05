#!/usr/bin/env bash
# Convenience wrapper around run-sdk.sh for per-task model routing (--task-model).
# Pass one or more `task=model` pairs; each becomes a --task-model flag.
#
#   scripts/run-sdk-taskmodel.sh <task=model> [<task=model> ...]
#
# Tasks: behaviors | stateTransitions | domains
# Env (same as run-sdk.sh):
#   REPO=fixtures/order-sample   repo to index
#   MODEL=claude-sonnet-5        default model for tasks WITHOUT an override
#   MAX_TOKENS=16000             output budget per call
#   OUT=/tmp/ki-sdk              output directory
#   FRESH=1                      clear the repo cache first (forces real calls)
#
# Examples:
#   # behaviors on Opus, the rest on the default Sonnet:
#   FRESH=1 OUT=/tmp/ki-opus scripts/run-sdk-taskmodel.sh behaviors=claude-opus-4-8
#
#   # different model per task:
#   scripts/run-sdk-taskmodel.sh behaviors=claude-opus-4-8 domains=claude-haiku-4-5
set -euo pipefail
here="$(dirname "${BASH_SOURCE[0]}")"
REPO="${REPO:-fixtures/order-sample}"

if [ "$#" -eq 0 ]; then
  echo "usage: scripts/run-sdk-taskmodel.sh <task=model> [<task=model> ...]" >&2
  echo "  tasks: behaviors | stateTransitions | domains" >&2
  exit 2
fi

flags=()
for pair in "$@"; do
  case "$pair" in
    *=*) flags+=(--task-model "$pair") ;;
    *) echo "error: expected <task>=<model>, got: $pair" >&2; exit 2 ;;
  esac
done

echo "[ki] task-model routing: ${flags[*]}" >&2
exec "$here/run-sdk.sh" "$REPO" "${flags[@]}"
