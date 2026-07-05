#!/usr/bin/env bash
# Run the enrichment via a local (or any) OpenAI-compatible endpoint (--provider openai).
# Defaults target a local Ollama server; no API key is needed for local models.
#
#   scripts/run-ollama.sh [REPO] [-- extra kindexer flags...]
#
# REPO   path to the Spring repo to index (default: fixtures/order-sample)
# Any args after REPO are forwarded verbatim to the binary.
# Optional env:
#   MODEL=qwen2.5-coder:7b            model tag (must be pulled: `ollama pull <model>`)
#   BASE_URL=http://localhost:11434/v1  OpenAI-compatible endpoint (default: local Ollama)
#   OPENAI_API_KEY=...               only needed for a hosted endpoint (unset for local Ollama)
#   REASONING=none                   reasoning_effort (none|low|medium|high); default 'none' so a
#                                    thinking model's chain-of-thought doesn't burn the budget. Set
#                                    REASONING='' to send nothing (a non-thinking model / picky server)
#   MAX_TOKENS=4096                  output budget per call (kept low on purpose — small local models
#                                    ramble to fill a big budget, so a high cap makes each call slow)
#   OUT=/tmp/ki-ollama               output directory
#   FRESH=1                          clear the repo cache first (forces a real call for every task)
#
# Tip: Ollama defaults to a small context and silently truncates long prompts, tanking the keep-rate.
# Raise it, e.g. start the server with OLLAMA_CONTEXT_LENGTH=16384, or a Modelfile PARAMETER num_ctx.
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"

REPO="${1:-fixtures/order-sample}"
shift || true            # remaining args are forwarded to the binary
[ "${1:-}" = "--" ] && shift || true
MODEL="${MODEL:-qwen2.5-coder:7b}"
BASE_URL="${BASE_URL:-http://localhost:11434/v1}"
MAX_TOKENS="${MAX_TOKENS:-4096}"
REASONING="${REASONING-none}"   # default 'none'; REASONING='' sends nothing
OUT="${OUT:-/tmp/ki-ollama}"

[ -d "$REPO" ] || { echo "error: repo is not a directory: $REPO" >&2; exit 2; }
ki_ensure_bin

if [ "${FRESH:-0}" = "1" ]; then
  echo "[ki] FRESH=1 — clearing $REPO/.knowledge-index/cache" >&2
  rm -rf "$REPO/.knowledge-index/cache"
fi

reason_flag=()
[ -n "$REASONING" ] && reason_flag=(--reasoning-effort "$REASONING")

echo "[ki] run --provider openai base-url=$BASE_URL model=$MODEL max-tokens=$MAX_TOKENS reasoning=${REASONING:-<none sent>} repo=$REPO out=$OUT ${*:+extra: $*}" >&2
"$(ki_bin)" run "$REPO" --provider openai --base-url "$BASE_URL" --model "$MODEL" \
  --max-tokens "$MAX_TOKENS" ${reason_flag[@]+"${reason_flag[@]}"} --out "$OUT" "$@"

echo >&2
echo "[ki] done. Open the diagrams with:  scripts/preview.sh $OUT" >&2
