#!/usr/bin/env bash
# Shared helpers for the test scripts. Do not run directly; this is sourced.

# Repo root (scripts/ sits one level below).
ki_root() { cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd; }

# Path to the installed binary.
ki_bin() { echo "build/install/knowledge-indexer/bin/knowledge-indexer"; }

# Ensure the binary is built (installDist) — only rebuilds if missing.
ki_ensure_bin() {
  if [ ! -x "$(ki_bin)" ]; then
    echo "[ki] build: ./gradlew -q installDist" >&2
    ./gradlew -q installDist
  fi
}

# Load the key from the keychain if not already in the environment. Fails if not found.
ki_load_key() {
  if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    ANTHROPIC_API_KEY="$(security find-generic-password -a "$USER" -s ANTHROPIC_API_KEY -w 2>/dev/null || true)"
    export ANTHROPIC_API_KEY
  fi
  if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
    echo "error: ANTHROPIC_API_KEY not found (neither in the environment nor the keychain)." >&2
    echo "store it with: security add-generic-password -a \"\$USER\" -s ANTHROPIC_API_KEY -w 'sk-ant-...'" >&2
    exit 1
  fi
}
