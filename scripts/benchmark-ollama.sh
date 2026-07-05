#!/usr/bin/env bash
# Run a model matrix for the OpenAI-compatible provider against a local/remote Ollama (or any
# OpenAI-compatible endpoint). Mirrors benchmark-matrix.sh, but the axis is just the MODEL (cost is
# free/n·a for local, so the interesting signals are keep-rate, quality and wall-time).
#
#   scripts/benchmark-ollama.sh [REPO]
#
# For each model it runs the FULL enrichment (behaviors + stateTransitions + domains) on that model,
# into its own OUT dir + .log under $ROOT. Cache is cleared per cell (the cache key does NOT include
# the model). Unlike the Anthropic matrix this spends no money — but a big model on CPU is slow.
#
# Env:
#   BASE_URL=http://localhost:11434/v1   endpoint (set to your GPU box, e.g. http://192.168.1.100:11434/v1)
#   MODELS='a b c'                       models to test; default = auto-discovered from BASE_URL/models
#   MAX_TOKENS=4096                      output budget per call
#   REASONING=none                       reasoning_effort (none disables a thinking model's CoT); '' sends nothing
#   ROOT=/tmp/ki-ollama-matrix           where cells are written
#   OPENAI_API_KEY=...                   only for a hosted endpoint (unset for local)
set -u
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"
ki_ensure_bin
BIN="$(ki_bin)"

REPO="${1:-fixtures/order-sample}"
BASE_URL="${BASE_URL:-http://localhost:11434/v1}"
MAX_TOKENS="${MAX_TOKENS:-4096}"
REASONING="${REASONING-none}"
ROOT="${ROOT:-/tmp/ki-ollama-matrix}"

[ -d "$REPO" ] || { echo "error: repo is not a directory: $REPO" >&2; exit 2; }

# Auto-discover installed chat models when MODELS is unset (skip obvious embed/vision-only tags).
if [ -z "${MODELS:-}" ]; then
  MODELS="$(curl -s --connect-timeout 5 "$BASE_URL/models" \
    | python3 -c "import sys,json; print(' '.join(m['id'] for m in json.load(sys.stdin).get('data',[]) if 'embed' not in m['id']))" 2>/dev/null)"
fi
[ -n "$MODELS" ] || { echo "error: no MODELS (set MODELS or check BASE_URL=$BASE_URL)" >&2; exit 2; }

reason_flag=()
[ -n "$REASONING" ] && reason_flag=(--reasoning-effort "$REASONING")

rm -rf "$ROOT"; mkdir -p "$ROOT"
echo "[ki] ollama matrix: base=$BASE_URL max-tokens=$MAX_TOKENS reasoning=${REASONING:-<none>} models=[$MODELS]" >&2

run_cell() {
  local model=$1
  local safe; safe="$(echo "$model" | tr ':/' '--')"
  local out="$ROOT/$safe"
  echo "$model" > "$ROOT/$safe.model"
  rm -rf "$REPO/.knowledge-index/cache"
  echo "=== CELL $model $(date +%H:%M:%S) ===" >&2
  "$BIN" run "$REPO" --provider openai --base-url "$BASE_URL" --model "$model" \
    --max-tokens "$MAX_TOKENS" ${reason_flag[@]+"${reason_flag[@]}"} --out "$out" \
    >"$out.stdout" 2>"$out.log" || echo "  cell FAILED (see $out.log)" >&2
  grep -E "items kept|items merged|FAILED|truncated|enrichment done" "$out.log" | sed 's/^/  /' >&2
}

for model in $MODELS; do
  run_cell "$model"
done

rm -rf "$REPO/.knowledge-index"
echo "=== OLLAMA MATRIX DONE $(date +%H:%M:%S) — analyze with: scripts/benchmark-ollama-report.py $ROOT ===" >&2
touch "$ROOT/.done"
