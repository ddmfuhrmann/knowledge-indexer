# scripts/ — test helpers

All of these assume they're run from the repo root and build the binary (`installDist`) if it's
missing. The Anthropic key is read from the **macOS keychain** automatically (service
`ANTHROPIC_API_KEY`), or from `$ANTHROPIC_API_KEY` if already exported.

| Script | What it does | Uses the API? |
|---|---|---|
| `env-macos.sh` | `source scripts/env-macos.sh` — exports the key from the macOS keychain into the current shell (`-macos` marks the platform; a future `env-linux.sh` could read from `secret-tool`/`pass`) | no |
| `check-key.sh` | one cheap "hello world" call to Haiku to confirm the key works (auth + billing) | **yes** (tiny) |
| `run-sdk.sh [REPO] [flags…]` | runs `run --provider sdk` against a repo (default `fixtures/order-sample`); extra args are forwarded to the binary | **yes** |
| `run-sdk-taskmodel.sh <task=model>…` | wrapper that turns `task=model` pairs into `--task-model` flags (per-task model routing) | **yes** |
| `determinism-check.sh [REPO]` | runs `--no-llm` twice and checks manifest/HTML are byte-identical | no (free) |
| `test-sdk-offline.sh` | spins up a local stub and validates retry/thinking/keep-rate/cache with no key or cost | no |
| `benchmark-matrix.sh [REPO]` | runs the model × effort cost/quality matrix (see `docs/anthropic_benchmark.md`) | **yes** (~$1.8) |
| `benchmark-report.py [DIR]` | prints the cost × quality table from a matrix run | no |
| `preview.sh [OUT] [PORT]` | serves the output directory with a static server and opens it in the browser | no |

## Examples

```bash
# store the key in the keychain (once):
security add-generic-password -a "$USER" -s ANTHROPIC_API_KEY -w 'sk-ant-...'

# confirm the key works (cheap Haiku ping):
scripts/check-key.sh

# offline test (no cost) — validates the whole sdk provider hardening:
scripts/test-sdk-offline.sh

# real run against the fixture (force a fresh call by clearing the cache):
FRESH=1 scripts/run-sdk.sh

# another repo, bigger model and budget:
MODEL=claude-opus-4-8 MAX_TOKENS=32000 scripts/run-sdk.sh /path/to/spring-petclinic

# per-task model routing — behaviors on Opus, the rest on the default Sonnet:
FRESH=1 OUT=/tmp/ki-opus scripts/run-sdk-taskmodel.sh behaviors=claude-opus-4-8

# reasoning ON (model-aware) — cheap Haiku 4.5 with extended thinking on behaviors:
FRESH=1 THINKING=on OUT=/tmp/ki-haiku scripts/run-sdk-taskmodel.sh behaviors=claude-haiku-4-5

# open the diagrams in the browser:
scripts/preview.sh /tmp/ki-sdk
```
