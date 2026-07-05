#!/usr/bin/env bash
# Run the model x effort benchmark matrix for the sdk enrichment provider.
# See docs/anthropic_benchmark.md for the methodology and how to read the results.
#
#   scripts/benchmark-matrix.sh [REPO]
#
# For each cell it routes the interpretive `behaviors` task to the cell model and keeps the
# mechanical tasks (stateTransitions, domains) on Haiku (cheap). Each cell gets its own OUT dir and
# .log under $ROOT. The cache is cleared per cell because the cache key does NOT include the model —
# otherwise a later cell would serve an earlier cell's behaviors result.
#
# WARNING: this makes real API calls and spends money (order-sample: ~US$1.8 for the full 13 cells;
# a larger repo costs proportionally more). The key is read from the keychain (or ANTHROPIC_API_KEY).
set -u
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"
ki_ensure_bin
ki_load_key
BIN="$(ki_bin)"

REPO="${1:-fixtures/order-sample}"
ROOT="${ROOT:-/tmp/ki-matrix}"
MODELS_MODERN="${MODELS_MODERN:-haiku=claude-haiku-4-5 sonnet=claude-sonnet-5 opus=claude-opus-4-8}"
EFFORTS="${EFFORTS:-off low medium high}"
FABLE="${FABLE:-claude-fable-5}"   # set FABLE= to skip the (expensive) Fable cell

rm -rf "$ROOT"; mkdir -p "$ROOT"

run_cell() {
  local mshort=$1 model=$2 cond=$3
  local out="$ROOT/$mshort-$cond"
  local args=(run "$REPO" --provider sdk --model claude-haiku-4-5 --task-model "behaviors=$model" --out "$out")
  [ "$cond" != off ] && args+=(--effort "$cond")
  rm -rf "$REPO/.knowledge-index/cache"
  echo "=== CELL $mshort-$cond (behaviors=$model effort=$cond) $(date +%H:%M:%S) ==="
  "$BIN" "${args[@]}" >"$out.stdout" 2>"$out.log" || echo "  cell FAILED (see $out.log)"
  grep -E "estimated spend|behaviors: [0-9]+ items|FAILED|truncated" "$out.log" | sed 's/^/  /'
}

for cond in $EFFORTS; do
  for pair in $MODELS_MODERN; do
    run_cell "${pair%%=*}" "${pair#*=}" "$cond"
  done
done
[ -n "$FABLE" ] && run_cell fable "$FABLE" off

rm -rf "$REPO/.knowledge-index"
echo "=== MATRIX DONE $(date +%H:%M:%S) — analyze with: scripts/benchmark-report.py $ROOT ==="
touch "$ROOT/.done"
