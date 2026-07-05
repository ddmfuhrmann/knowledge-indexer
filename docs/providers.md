# LLM providers & models

The **deterministic** layer never calls an LLM. Only the **enrichment** layer (use cases, state
transitions, domain map) does, behind a pluggable provider. This note covers what works today and the
provider landscape for [roadmap item 3 (multi-vendor / local)](../ROADMAP.md).

Two hosted-capable providers are wired up: `--provider sdk` (Anthropic Messages API) and
`--provider openai` (any OpenAI `/v1/chat/completions` endpoint — hosted **or** local). Both share the
enrichment cache and the evidence validator, so a weaker model degrades to a low keep-rate instead of
breaking generation.

## Today: the Anthropic SDK path — `--provider sdk`

The only hosted provider wired up right now. Key comes from the environment, never a flag or file.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
BIN=build/install/knowledge-indexer/bin/knowledge-indexer
$BIN run <repo> --provider sdk --out out/                 # default model: claude-haiku-4-5
#   large repo → give each call more output room: --max-tokens 32000
#   richer business voice on the use cases (deliverable) → --task-model behaviors=claude-sonnet-5 --effort low
```

The default model is **`claude-haiku-4-5`** (thinking off) — see
[docs/anthropic_benchmark.md](anthropic_benchmark.md): it matches the stronger models on structural
quality at ~1/3 the cost and with zero ungrounded invention. Route just `behaviors` to Sonnet for a
richer voice on a published deliverable.

**Per-task model routing.** `--task-model <task>=<model>` (repeatable) sends a single task to a
different model than `--model`. Useful because the tasks differ in nature: `behaviors` (the BDD use
cases) is interpretive and benefits from a stronger model, while `stateTransitions`/`domains` are
more mechanical. With `thinking` disabled, models — even Opus — tend to restate the HTTP endpoint in
the `when` instead of writing a business action; the fix is **reasoning**, not just a bigger model.
`--thinking on` recovers the business framing (and is model-aware, so a cheap Haiku 4.5 with extended
thinking can beat a larger model that has thinking off). Combine the two knobs, e.g.
`--thinking on --task-model behaviors=claude-haiku-4-5`.

Claude models to pick from (id → use). Full cost × quality data: [anthropic_benchmark.md](anthropic_benchmark.md).
- `claude-haiku-4-5` — **default**: cheapest, and in the benchmark it matched the bigger models on
  structure with zero invention. Caps at a 200K context, so on a very large repo the `behaviors`
  material can overflow it — route that task to Sonnet there (a capacity need, not just quality).
- `claude-sonnet-5` — the **deliverable** pick for `behaviors`: richest *grounded* business voice,
  ~3× Haiku's cost. Use `--task-model behaviors=claude-sonnet-5 --effort low`.
- `claude-opus-4-8` / `claude-fable-5` — **not recommended** for this: more expensive, and the
  benchmark found both invent an actor the evidence gate can't drop.

The default provider is still `agent` (Claude Code is the LLM via a file handoff, **no key**) — use
that when you're already in Claude Code; use `sdk` for headless/CI or another machine.

How the `sdk` call is shaped (in [`HttpAnthropicProvider`](../src/main/java/io/github/ddmfuhrmann/kindexer/enrich/HttpAnthropicProvider.java)):
- **`thinking` is off by default** — the whole `max_tokens` budget goes to the JSON array. (Recent
  models run adaptive thinking by default when `thinking` is omitted, burning the budget before the
  JSON and silently truncating it, so the call sends an explicit `{"type":"disabled"}`.) Turn it on
  with `--thinking on` when interpretation quality matters (see below); the config is model-aware —
  adaptive for modern models, extended `budget_tokens` for Haiku 4.5.
- **`max_tokens` defaults to 16000** (`--max-tokens N` to change). Non-streaming stays within the 120s
  request timeout; a much larger budget would need streaming (roadmap follow-up). A `stop_reason=max_tokens`
  prints a warning so truncation is never masked.
- **Retry with exponential backoff** on 429 / 5xx / 529 / I/O, honouring the `retry-after` header; a
  single rate-limit no longer empties a section.
- **Keep-rate is logged per task** (`<task>: <kept> items (raw <n>) via <model>`) so a weak run is
  visible rather than silently lossy.
- **Tokens + estimated cost are logged per call** and summed into a run-total `estimated spend`
  line. Tokens come straight from the response `usage` (exact); the `$` is an **estimate** from
  indicative list prices (Haiku $1/$5, Sonnet $3/$15, Opus $5/$25, Fable $10/$50 per 1M in/out) and
  may drift. The Anthropic console only shows aggregated usage/cost — use a dedicated API key to
  attribute a run's spend there.
- **The `behaviors` task auto-chunks on large repos** — its per-endpoint material scales with the
  codebase, so on a big app one prompt would blow the token budget / 120s timeout. It's split by
  controller with the call graph scoped per chunk, each chunk prompted / cached / validated on its own
  and merged. Chunks auto-size (~90K chars ≈ ~50s/call); `--behaviors-chunk N` forces a manual
  endpoint cap. Small repos stay a single call. This is also what makes small-context local models
  (Ollama et al., roadmap 3) viable. Chunks run sequentially today (a 122-endpoint app ≈ 7 min).

## OpenAI-compatible path — `--provider openai` (hosted + local / Ollama)

One client covers **any** endpoint speaking the OpenAI `/v1/chat/completions` shape: hosted (OpenAI,
Groq, OpenRouter, DeepInfra/Together/Fireworks) **and** local runners (Ollama, LM Studio, llama.cpp,
vLLM). For which local model to pick and the per-model `reasoning_effort` values, see the
[Ollama/OpenAI model benchmark](ollama_benchmark.md). The `--base-url` (must include `/v1`) and `--model` pick the backend; `--model` is **required**
(there is no sensible default across vendors). The key comes from `OPENAI_API_KEY` and is **optional**
— a local model needs none, so when it is unset no `Authorization` header is sent.

```bash
BIN=build/install/knowledge-indexer/bin/knowledge-indexer

# local Ollama — no key, no cost:
$BIN run <repo> --provider openai --base-url http://localhost:11434/v1 --model qwen2.5-coder:7b --out out/

# hosted OpenAI-compatible (key in the env):
OPENAI_API_KEY=… $BIN run <repo> --provider openai --model gpt-4o-mini --out out/
# Groq / OpenRouter: same, with --base-url https://api.groq.com/openai/v1 (or the aggregator's URL).
```

The request is `{model, max_tokens, temperature:0, messages:[{role:user, content:<prompt>}]}` plus
`reasoning_effort` when `--reasoning-effort LVL` is set. `temperature:0` maximises consistency/keep-rate.
Retries mirror the Anthropic path (429/5xx/I·O, exponential backoff honouring `retry-after`), except a
**timeout fails fast** (no retry — a slow local model won't get faster on retry) after
`DEFAULT_TIMEOUT_S`=300s (override with `KINDEXER_OPENAI_TIMEOUT_S`). `--thinking`/`--effort` are the
Anthropic knobs and are ignored here; use `--reasoning-effort` instead. Token usage is read from the
response `usage` (`prompt_tokens`/`completion_tokens`) and logged per call; cost is `n/a` (unknown
per-model / free for local). The model id recorded in the manifest + footer is `openai:<model>`.

> **Ollama caveat #1 — thinking is ON by default and burns the budget.** A thinking-capable model
> (gemma, qwen3, gpt-oss, deepseek-r1) returns a `reasoning` field over Ollama's OpenAI endpoint by
> default, spending most of `max_tokens` on chain-of-thought *before* the JSON — which then truncates
> mid-array and validates to **zero** items (the same failure the Anthropic path disables `thinking`
> for). Pass **`--reasoning-effort LVL`** to tame it — but **the effective value is model-specific**:
> `gemma4` (12b/26b) wants `none` (disables it); `gpt-oss:20b` reasons *more* on `none` and needs
> `low` (its minimum). `deepseek-r1` ignores every value (always thinks) → unusable here. `think:false`
> and `chat_template_kwargs` are not honoured by the endpoint — only `reasoning_effort` is. Measured on
> `order-sample`/behaviors: gemma4:12b `none` → 12/12 (~3.7K tok, was 0 + truncation); gpt-oss:20b
> `low` → 12/12 in ~53s (the fastest full-coverage model). The `run-ollama.sh` helper defaults
> `REASONING=none` (right for gemma; pass `REASONING=low` for gpt-oss).

> **Ollama caveat #2 — context window.** Ollama defaults to a small context (`num_ctx`, ~2–4K) and
> **silently truncates** a longer prompt, which starves anchoring and tanks the keep-rate (you'll see
> a low `usage in` vs the `~N tok` estimate). Raise it for real runs — e.g. `OLLAMA_CONTEXT_LENGTH`
> when starting `ollama serve`, or a `Modelfile` with `PARAMETER num_ctx 16384`. On large repos the
> `behaviors` auto-chunking (below) already shrinks each prompt, which helps small-context local models.

> **Ollama caveat #3 — small models ramble; cap `--max-tokens`.** Left with a big budget a small model
> keeps generating past the JSON, so each call is slow. Keep `--max-tokens` modest (the helper defaults
> 4096) — with `reasoning_effort:none` the anchored JSON is compact, so a low cap is both faster and
> lossless.

## What actually matters when choosing a model

Enrichment output is validated: **every item must resolve to a real code anchor or it's dropped**
(`EvidenceIndex`). So a cheap-but-weak model doesn't fail loudly — it quietly produces a **low
keep-rate** (thin output, more gaps). Weigh models on *keep-rate*, not just token price. Two levers
soften the cost of a good model:
- The enrichment **cache is content-addressed** by `(materialHash + promptVersion)` — only changed
  material (or a changed task prompt) re-hits the model.
- Roadmap **A** will chunk material so large repos don't send everything in one prompt.

Rule of thumb: a **small frontier** model (Sonnet / Gemini Flash / GPT-mini / Haiku) usually beats a
tiny local model on keep-rate for the same effort.

## The provider landscape (for multi-vendor — roadmap item 3)

Most of these speak the **OpenAI `/v1/chat/completions`** shape, so a *single* OpenAI-compatible
provider (`--provider openai --base-url … --model …`, now shipped) covers hosted **and** local. Only
Anthropic and Google need their own request shape.

| Provider | OpenAI-compatible | Notes |
|---|---|---|
| **Anthropic** | no (own API) | `--provider sdk`; best JSON/anchoring reliability |
| **Groq** | yes | LPU, very fast + cheap, generous free tier — strong CI pick |
| **OpenRouter** | yes | aggregator; swap models via one key, some free tiers |
| **DeepInfra / Together / Fireworks** | yes | cheap hosted open models (Llama, Qwen, …) |
| **Google Gemini** | no (own API) | Flash tier is cheap + reliable JSON |
| **OpenAI** | yes (native) | GPT-mini tiers are a solid value baseline |
| **Ollama (local)** | yes (`localhost:11434/v1`) | no key, no cost, but CPU-only is slow + weak JSON |
| **Ollama Cloud / Turbo** | yes | hosted Ollama models; usually pricier than Groq/OpenRouter |

Local (Ollama/LM Studio/llama.cpp/vLLM) is genuinely free and private, but expect a lower keep-rate on
small models and slow CPU inference — best on a dev box with a GPU, not a CI runner.

## Keys & safety

- Keys come from **environment variables only** (`ANTHROPIC_API_KEY`, `OPENAI_API_KEY`, later
  `GEMINI_API_KEY`, …). Never a CLI flag, never committed. In CI, use repo/organization **Secrets**.
  `OPENAI_API_KEY` is optional for a local endpoint (Ollama) — omit it and no `Authorization` header
  is sent.
- A failed LLM call leaves that enrichment section empty and never breaks generation — output degrades
  gracefully to deterministic-only.
