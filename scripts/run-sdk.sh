#!/usr/bin/env bash
# Run the enrichment via the Anthropic API (--provider sdk) against a repo.
#
#   scripts/run-sdk.sh [REPO] [-- extra kindexer flags...]
#
# REPO   path to the Spring repo to index (default: fixtures/order-sample)
# Any args after REPO are forwarded verbatim to the binary, e.g.:
#   scripts/run-sdk.sh fixtures/order-sample --task-model behaviors=claude-opus-4-8
# Optional env:
#   MODEL=claude-sonnet-5      model (default)
#   MAX_TOKENS=16000           output budget per call
#   OUT=/tmp/ki-sdk            output directory
#   THINKING=on                enable model reasoning (default off); adaptive for modern models,
#                              extended (budget) for Haiku 4.5
#   EFFORT=low|medium|high|xhigh|max   reasoning depth (implies thinking on)
#   FRESH=1                    clear the repo cache first (forces a real call for every task)
#
# The key is read from the keychain automatically (or from ANTHROPIC_API_KEY if already exported).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"

REPO="${1:-fixtures/order-sample}"
shift || true            # remaining args are forwarded to the binary
[ "${1:-}" = "--" ] && shift || true
MODEL="${MODEL:-claude-sonnet-5}"
MAX_TOKENS="${MAX_TOKENS:-16000}"
OUT="${OUT:-/tmp/ki-sdk}"

[ -d "$REPO" ] || { echo "error: repo is not a directory: $REPO" >&2; exit 2; }
ki_load_key
ki_ensure_bin

if [ "${FRESH:-0}" = "1" ]; then
  echo "[ki] FRESH=1 — clearing $REPO/.knowledge-index/cache" >&2
  rm -rf "$REPO/.knowledge-index/cache"
fi

reason_flag=()
[ "${THINKING:-off}" = "on" ] && reason_flag=(--thinking on)
[ -n "${EFFORT:-}" ] && reason_flag+=(--effort "$EFFORT")

model_label="model"
case "$*" in *"--task-model"*) model_label="default-model" ;; esac
echo "[ki] run --provider sdk ${model_label}=$MODEL max-tokens=$MAX_TOKENS thinking=${THINKING:-off}${EFFORT:+ effort=$EFFORT} repo=$REPO out=$OUT ${*:+extra: $*}" >&2
# ${arr[@]+…} guard: on macOS bash 3.2, expanding an empty array under `set -u` errors.
"$(ki_bin)" run "$REPO" --provider sdk --model "$MODEL" --max-tokens "$MAX_TOKENS" \
  ${reason_flag[@]+"${reason_flag[@]}"} --out "$OUT" "$@"

echo >&2
echo "[ki] done. Open the diagrams with:  scripts/preview.sh $OUT" >&2
