#!/usr/bin/env bash
# Determinism proof: run `--no-llm` twice and check that manifest.json and
# index.html are byte-identical. Does not use the API (free). Good CI gate.
#
#   scripts/determinism-check.sh [REPO]
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"

REPO="${1:-fixtures/order-sample}"
[ -d "$REPO" ] || { echo "error: repo is not a directory: $REPO" >&2; exit 2; }
ki_ensure_bin
BIN="$(ki_bin)"

A="$(mktemp -d)"; B="$(mktemp -d)"
trap 'rm -rf "$A" "$B"' EXIT

"$BIN" run "$REPO" --no-llm --out "$A" >/dev/null 2>&1
"$BIN" run "$REPO" --no-llm --out "$B" >/dev/null 2>&1

rc=0
if diff -q "$A/manifest.json" "$B/manifest.json" >/dev/null; then
  echo "OK   manifest.json byte-identical"
else
  echo "FAIL manifest.json differs"; diff "$A/manifest.json" "$B/manifest.json" | head; rc=1
fi
if diff -q "$A/index.html" "$B/index.html" >/dev/null; then
  echo "OK   index.html byte-identical"
else
  echo "FAIL index.html differs"; rc=1
fi
exit $rc
