#!/usr/bin/env bash
# Load ANTHROPIC_API_KEY from the macOS keychain into the current shell.
# Usage:  source scripts/env-macos.sh
# (the other scripts do this themselves — this one is for interactive use.)
# The -macos suffix marks the platform: a future env-linux.sh could read from
# secret-tool / pass / etc.

_ki_key="$(security find-generic-password -a "$USER" -s ANTHROPIC_API_KEY -w 2>/dev/null)"
if [ -z "$_ki_key" ]; then
  echo "error: ANTHROPIC_API_KEY is not in the keychain." >&2
  echo "store it once with:" >&2
  echo "  security add-generic-password -a \"\$USER\" -s ANTHROPIC_API_KEY -w 'sk-ant-...'" >&2
  unset _ki_key
  return 1 2>/dev/null || exit 1
fi
export ANTHROPIC_API_KEY="$_ki_key"
unset _ki_key
echo "ANTHROPIC_API_KEY loaded (len ${#ANTHROPIC_API_KEY})" >&2
