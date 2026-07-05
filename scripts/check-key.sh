#!/usr/bin/env bash
# Quick smoke test of the Anthropic API key: one cheap "hello world" call to Haiku.
# Confirms the key works (auth + billing + reachability) before running a full enrichment.
#
#   scripts/check-key.sh
#
# Optional env:
#   MODEL=claude-haiku-4-5     model to ping (default — cheapest/fastest)
#   ANTHROPIC_BASE_URL=...      override the endpoint (e.g. a local stub)
#
# The key is read from the keychain automatically (or from ANTHROPIC_API_KEY if already exported).
set -euo pipefail
source "$(dirname "${BASH_SOURCE[0]}")/_lib.sh"
cd "$(ki_root)"

MODEL="${MODEL:-claude-haiku-4-5}"
BASE="${ANTHROPIC_BASE_URL:-https://api.anthropic.com}"
BASE="${BASE%/}"
ki_load_key

echo "[ki] pinging $MODEL at $BASE ..." >&2

body="$(cat <<JSON
{"model":"$MODEL","max_tokens":32,"messages":[{"role":"user","content":"Reply with exactly: knowledge-indexer key OK"}]}
JSON
)"

resp="$(curl -sS -w $'\n%{http_code}' "$BASE/v1/messages" \
  -H "x-api-key: $ANTHROPIC_API_KEY" \
  -H "anthropic-version: 2023-06-01" \
  -H "content-type: application/json" \
  -d "$body")"

code="${resp##*$'\n'}"
payload="${resp%$'\n'*}"

if [ "$code" = "200" ]; then
  text="$(printf '%s' "$payload" | python3 -c 'import sys,json; d=json.load(sys.stdin); print("".join(b.get("text","") for b in d.get("content",[]) if b.get("type")=="text"))' 2>/dev/null || true)"
  echo "OK   HTTP 200 — key works"
  [ -n "$text" ] && echo "     model said: $text"
  exit 0
fi

echo "FAIL HTTP $code" >&2
printf '%s\n' "$payload" >&2
case "$code" in
  401) echo "hint: invalid/expired key — check ANTHROPIC_API_KEY or the keychain entry." >&2 ;;
  400) echo "hint: bad request — is the model id '$MODEL' valid?" >&2 ;;
  403) echo "hint: key lacks permission / model access, or billing not set up." >&2 ;;
  429) echo "hint: rate limited — wait and retry." >&2 ;;
  000) echo "hint: could not reach $BASE — network/base-url issue." >&2 ;;
esac
exit 1
