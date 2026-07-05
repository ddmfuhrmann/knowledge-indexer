#!/usr/bin/env bash
# Serve an output directory with a static server to check the diagrams in a browser
# (sequence/choreography diagrams only render client-side).
#
#   scripts/preview.sh [OUT_DIR] [PORT]
set -euo pipefail
OUT="${1:-/tmp/ki-sdk}"
PORT="${2:-8000}"
[ -f "$OUT/index.html" ] || { echo "error: $OUT/index.html not found — run run-sdk.sh first" >&2; exit 2; }
echo "[ki] serving $OUT at http://127.0.0.1:$PORT  (Ctrl-C to stop)" >&2
command -v open >/dev/null 2>&1 && (sleep 1; open "http://127.0.0.1:$PORT/index.html") &
cd "$OUT"
exec python3 -m http.server "$PORT"
