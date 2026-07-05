#!/usr/bin/env bash
# OFFLINE test of the --provider sdk path: spins up a local stub (ANTHROPIC_BASE_URL)
# and checks retry on 429, thinking disabled, the max_tokens warning, keep-rate, and
# the -<promptVersion> suffix in the cache. Does not hit the real API — no key, no cost.
#
#   scripts/test-sdk-offline.sh
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"
ki_ensure_bin
BIN="$(ki_bin)"

PORT="${PORT:-8799}"
REPO="fixtures/order-sample"
OUT="$(mktemp -d)"
LOG="$(mktemp)"
CACHE="$REPO/.knowledge-index"
rm -rf "$CACHE"
trap 'kill "${STUB:-0}" 2>/dev/null || true; rm -rf "$OUT" "$LOG" "$CACHE"' EXIT

python3 scripts/stub-anthropic.py "$PORT" "$LOG" & STUB=$!
disown "$STUB" 2>/dev/null || true
sleep 1

echo "== 1st run (against the stub) =="
ANTHROPIC_API_KEY=dummy-key ANTHROPIC_BASE_URL="http://127.0.0.1:$PORT" \
  "$BIN" run "$REPO" --provider sdk --model claude-sonnet-5 --out "$OUT" 2>&1 \
  | grep -E "429|truncated|items|via sdk" || true

echo
echo "== requests seen by the stub (note thinking=disabled, max_tokens=16000) =="
cat "$LOG"

echo
echo "== cache files (-<promptVersion> suffix) =="
ls -1 "$CACHE/cache"

echo
echo "== 2nd run: should be all cache (stub receives nothing) =="
: > "$LOG"
ANTHROPIC_API_KEY=dummy-key ANTHROPIC_BASE_URL="http://127.0.0.1:$PORT" \
  "$BIN" run "$REPO" --provider sdk --model claude-sonnet-5 --out "$OUT" 2>&1 \
  | grep -E "cache|items" || true
calls="$(wc -l < "$LOG" | tr -d ' ')"
echo "stub calls on 2nd run: $calls (expected 0)"
[ "$calls" = "0" ] && echo "OK   cache worked" || echo "FAIL cache did not hold"
