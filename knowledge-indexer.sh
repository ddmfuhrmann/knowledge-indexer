#!/usr/bin/env bash
# knowledge-indexer.sh — build (if needed) and run the knowledge-indexer CLI.
#
# Usage:  knowledge-indexer.sh <run|extract|assemble> <repo> [options]
# stdout: path to the generated manifest.json
# stderr: progress
# exit:   passes through the CLI exit code
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BIN="$HERE/build/install/knowledge-indexer/bin/knowledge-indexer"

# Rebuild when the launcher is missing or any source is newer than it.
needs_build=0
if [ ! -x "$BIN" ]; then
  needs_build=1
elif [ -n "$(find "$HERE/src" "$HERE/build.gradle.kts" -newer "$BIN" 2>/dev/null | head -1)" ]; then
  needs_build=1
fi

if [ "$needs_build" -eq 1 ]; then
  echo "[knowledge-indexer] building..." >&2
  ( cd "$HERE" && ./gradlew -q --console=plain installDist ) >&2 || {
    echo "[knowledge-indexer] build failed" >&2
    exit 1
  }
fi

exec "$BIN" "$@"
