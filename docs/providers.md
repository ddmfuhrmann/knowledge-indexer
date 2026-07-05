# LLM providers & models

The **deterministic** layer never calls an LLM. Only the **enrichment** layer (use cases, state
transitions, domain map) does, behind a pluggable provider. This note covers what works today and the
provider landscape for [roadmap item 3 (multi-vendor / local)](../ROADMAP.md).

## Today: the Anthropic SDK path — `--provider sdk`

The only hosted provider wired up right now. Key comes from the environment, never a flag or file.

```bash
export ANTHROPIC_API_KEY=sk-ant-...
BIN=build/install/knowledge-indexer/bin/knowledge-indexer
$BIN run <repo> --provider sdk --model claude-sonnet-5 --out out/
```

Claude models to pick from (id → use):
- `claude-sonnet-5` — **good default**: strong structured-JSON + anchoring at a sane price.
- `claude-haiku-4-5-20251001` — cheapest, fastest; fine for small/simple repos, weaker on nuance.
- `claude-opus-4-8` — most capable; reach for it only when interpretation quality clearly matters.

The default provider is still `agent` (Claude Code is the LLM via a file handoff, **no key**) — use
that when you're already in Claude Code; use `sdk` for headless/CI or another machine.

## What actually matters when choosing a model

Enrichment output is validated: **every item must resolve to a real code anchor or it's dropped**
(`EvidenceIndex`). So a cheap-but-weak model doesn't fail loudly — it quietly produces a **low
keep-rate** (thin output, more gaps). Weigh models on *keep-rate*, not just token price. Two levers
soften the cost of a good model:
- The enrichment **cache is content-addressed** — only changed material re-hits the model.
- Roadmap **A** will chunk material so large repos don't send everything in one prompt.

Rule of thumb: a **small frontier** model (Sonnet / Gemini Flash / GPT-mini / Haiku) usually beats a
tiny local model on keep-rate for the same effort.

## The provider landscape (for multi-vendor — roadmap item 3)

Most of these speak the **OpenAI `/v1/chat/completions`** shape, so a *single* OpenAI-compatible
provider (`--base-url` + `--model`) covers hosted **and** local. Only Anthropic and Google need their
own request shape.

| Provider | OpenAI-compatible | Notes |
|---|---|---|
| **Anthropic** | no (own API) | wired up today; best JSON/anchoring reliability |
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

- Keys come from **environment variables only** (`ANTHROPIC_API_KEY`, later `OPENAI_API_KEY`,
  `GEMINI_API_KEY`, …). Never a CLI flag, never committed. In CI, use repo/organization **Secrets**.
- A failed LLM call leaves that enrichment section empty and never breaks generation — output degrades
  gracefully to deterministic-only.
