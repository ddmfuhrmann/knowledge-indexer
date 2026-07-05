# Ollama / OpenAI-compatible model benchmark — choosing a local (or cloud) model

How we pick a model for the `--provider openai` enrichment path against **Ollama** (local GPU or
Ollama Cloud) or any OpenAI-compatible endpoint, and the two hard-won knobs that decide whether a
model works at all: **`reasoning_effort` (per-model!)** and **`--max-tokens`**. Companion to the
[Anthropic benchmark](anthropic_benchmark.md); same methodology, same quality signals.

As there, only one of the three enrichment tasks is quality-sensitive — `behaviors` (the BDD-style
use cases a product owner reads) — so the benchmark scores it. `stateTransitions`/`domains` are
mechanical. The prose fields (`given`/`when`/`then`, `scenario`) are **not** evidence-gated, so a
model can *invent* actors/motives that slip through — measured as `invent`.

Measured on **`order-sample`** against a local **Radeon RX 9070 XT (16 GB, ROCm)** box plus Ollama
Cloud. Numbers are single runs — see [Variance](#variance-read-this-before-trusting-a-single-cell).

## The knobs

| Flag / env | Effect |
|---|---|
| `--base-url URL` | endpoint (must include `/v1`); e.g. `http://<box>:11434/v1` for Ollama |
| `--model M` | required — no cross-vendor default |
| `--reasoning-effort LVL` | `none\|low\|medium\|high` → OpenAI `reasoning_effort`. **The value is model-specific** (see below); unset by default |
| `--max-tokens N` | output budget per call (default 16000; the helper uses 4096) |
| `KINDEXER_OPENAI_TIMEOUT_S` | per-request timeout (default 300s; a timeout **fails fast**, no retry) |

## Finding #1 — `reasoning_effort` is per-model, and it's the difference between 12/12 and 0

A thinking-capable model reasons by default over Ollama's OpenAI endpoint, spending the output budget
on chain-of-thought *before* the JSON, which truncates mid-array → **0 kept items**. The switch that
minimises it **differs by model** — there is no universal value:

| Model | disable/minimise thinking with | note |
|---|---|---|
| `gemma4:12b` / `gemma4:26b` | `none` | binary: `none`=off, `low`=`medium`=`high`=on (identical) |
| `gpt-oss:20b` / `gpt-oss:120b-cloud` | `low` | `none` reasons *more*; real gradient `low < medium < high=max` |
| `nemotron-cascade-2` | `none` (disables) | still truncates — pathologically verbose output |
| `deepseek-r1:8b` | *nothing works* | always thinks; also mangles JSON format → unusable |
| `devstral-small-2` | n/a (not a thinking model) | `low` errors "does not support thinking" |
| `minimax-m3:cloud` | n/a | ignores "JSON only" — asks for clarification instead |

`think:false` and `chat_template_kwargs:{enable_thinking:false}` are **not** honoured by the endpoint —
only `reasoning_effort` is. Probe a new model before trusting it:

```bash
curl -s $BASE_URL/chat/completions -H 'content-type: application/json' \
  -d '{"model":"<m>","max_tokens":300,"reasoning_effort":"low","messages":[{"role":"user","content":"Return ONLY [{\"x\":1}]."}]}' \
  | python3 -c 'import sys,json;m=json.load(sys.stdin)["choices"][0]["message"];print("reasoning:",len(m.get("reasoning","")),"| content:",m["content"][:40])'
```

## Methodology

**One cell per model** (unlike the Anthropic effort matrix), running the full enrichment on that
model. Cache cleared per cell (the cache key is `(material + promptVersion)` — **not** the model or
reasoning, so an un-cleared cell serves a previous model's result).

```bash
BASE_URL=http://<box>:11434/v1 scripts/benchmark-ollama.sh [REPO]   # MODELS auto-discovered from /v1/models
scripts/benchmark-ollama-report.py /tmp/ki-ollama-matrix            # keep-rate + quality + wall-time
```

Local runs cost nothing (cost column dropped); the signals are **keep-rate**, the same quality
metrics as the Anthropic report, and **wall-time**. Set `MODELS='a b c'` and `REASONING=<lvl>` to
target specific models / efforts.

## Results (order-sample, 9070 XT + Ollama Cloud)

```
model                 beh   life ft whHTTP tech invent  time   reasoning   verdict
gpt-oss:120b-cloud   12/12   9   3     0    3     0      44s    low         cloud, fastest + clean
gpt-oss:20b          12/12  10   2     0    1     0      49s    low         best local speed/quality
gemma4:26b           12/12   9   2     0    1     0     112s    none        cleanest local (invent 0)
gemma4:12b           12/12   9   2     0    0     1     121s    none        best value; least technical
qwen3-coder:30b      12/12   0   2     0    0     1     105s    none        anchors, but misses lifecycle
gemma3:4b             6/12   4   4     0    0     7      33s    none        fast but partial + invents
minimax-m3:cloud      0/12   —   —     —    —     —      69s    none        won't emit JSON (clarifies)
nemotron-cascade-2    0/12   —   —     —    —     —     132s    none        too verbose, truncates
devstral-small-2      0/12   —   —     —    —     —     372s    none        24B > 16 GB → CPU offload, slow
deepseek-r1:8b        0/12   —   —     —    —     —     180s    —           always thinks; unusable
gemma3:1b / lfm2.5    0/12   —   —     —    —     —     ~35s    —           too weak to anchor
deepseek-v4-flash:cloud   (blocked — requires a paid Ollama Cloud subscription)
```

`beh` = use cases kept /12 endpoints · `life` = scenarios typed `lifecycle` (state changes) · `ft` =
feature groups · `whHTTP` = `when` clauses leaking the HTTP verb (0 = good) · `tech` = transport/infra
words in the prose (`request`/`json`/status codes…; lower = more business-like) · `invent` =
ungrounded actor nouns (lower = better).

## Finding #2 — more reasoning does **not** improve quality here (unlike Anthropic)

On the Anthropic path, thinking sharpened the business framing (`sonnet-off` invent 1 → `sonnet-low`
invent 0). Over Ollama it doesn't:

- **gemma4:26b** `none`→`on`: `life`/`invent` unchanged, `tech` slightly *worse*, and **3× slower**
  (319s vs 113s). Pure cost.
- **gemma4:12b** `on`: **truncates** behaviors (even at 12000 tokens) and runs ~10× slower. Actively harmful.
- **gpt-oss:20b** `low`→`medium`→`high`: `medium` traded one flaw for another at 2.5× the time;
  `high` truncated. `low` is already the sweet spot.

**Keep `reasoning_effort` at the minimum that disables thinking** (`none` for gemma, `low` for gpt-oss).
It's a "don't break the JSON" knob, not a quality dial.

## Variance — read this before trusting a single cell

Model output is **not deterministic** even at `temperature:0`, and the run-to-run spread is large: the
same `gpt-oss:20b low` config scored `life 5 / tech 3 / invent 1` in one run and `life 10 / tech 1 /
invent 0` in another. So **±2 on any quality signal is noise** — a single cell ranks models only
coarsely (does it hit 12/12? is it clean-ish?). For a trustworthy prose-quality ranking, run each cell
2–3× and average. The reliable, low-variance signals are **keep-rate**, **truncation**, and **wall-time**.

## Recommendations

- **Local, best all-round:** `gpt-oss:20b --reasoning-effort low` — 12/12, clean, ~50s (fastest local).
- **Local, cleanest deliverable prose:** `gemma4:26b --reasoning-effort none` (invent 0) or
  `gemma4:12b --reasoning-effort none` (least technical, cheapest to run).
- **Cloud (no local VRAM):** `gpt-oss:120b-cloud --reasoning-effort low` — 12/12 in 44s.
- **Avoid** for this task: reasoning-only models that can't be tamed (`deepseek-r1`), models that
  exceed your VRAM (`devstral-small-2` on 16 GB → CPU offload), and models weaker than ~4B.
- **Hardware:** a model must fit VRAM or half of it spills to CPU and crawls (`ollama ps` shows
  `100% GPU` when it fits). Context size doesn't change decode t/s — only the output token count and
  model size do; keep `--max-tokens` modest and reasoning minimal.
- **Still want perfectly clean prose?** A hosted `haiku-low` ($0.035/run) hits `life 9 / tech 0 /
  invent 0` — cleaner than any local model here (see [anthropic_benchmark.md](anthropic_benchmark.md)).
