# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). No versions are tagged/released yet —
everything below the foundation is under **Unreleased**.

## [Unreleased]

### Added
- **Auto-chunked the `behaviors` enrichment for large repos** — the per-endpoint use-case task used to
  send the whole deterministic material in one prompt, which on a big app (~140K tokens) blows the
  token budget and times out non-streaming. It is now split by controller with the **call graph
  scoped per chunk** (only the sub-graph reachable from the chunk's endpoints), each chunk prompted /
  cached / validated on its own and merged. Chunks **auto-size** by material (~90K chars ≈ ~50s/call);
  `--behaviors-chunk N` forces a manual endpoint cap; small repos stay a single unchanged call. A
  dedup backstop + a sharpened prompt keep it **1:1** (one use case per endpoint) even when a smaller
  chunk tempts the model to emit a card per branch. (ROADMAP A — chunks still run sequentially.)
- **Hardened `--provider sdk` (Anthropic Messages API) for real runs** — `thinking` disabled so the
  whole `max_tokens` budget goes to the JSON; `max_tokens` raised 4096 → 16000 and tunable via
  `--max-tokens N`; retry-with-exponential-backoff on 429/5xx/529/I·O honouring `retry-after`; a
  `stop_reason=max_tokens` warning; and per-task keep-rate logging so a weak/failed run is visible
  instead of a silently empty section. (ROADMAP 2)
- **Per-call token + estimated-cost logging** — each `--provider sdk` call logs the response `usage`
  (`in N, out N` tokens, plus cache read/write when non-zero) and an **estimated** USD cost from
  indicative list prices; a run-total `estimated spend` line sums across models. Tokens are exact
  (straight from the API); the `$` is marked estimated and may drift from list prices. The Anthropic
  console only shows aggregated usage/cost, so this is the per-call view.
- **Model benchmark + default is now `claude-haiku-4-5`** — a 13-cell cost × quality matrix
  ({haiku,sonnet,opus} × {off,low,medium,high} + fable) documented in
  [docs/anthropic_benchmark.md](docs/anthropic_benchmark.md), reproducible via
  `scripts/benchmark-matrix.sh` + `scripts/benchmark-report.py`. Finding: after the `behaviors`
  prompt tuning, Haiku matches the bigger models on structural quality at ~1/3 the cost and with zero
  ungrounded invention, so it is the shipped default (`Cli.DEFAULT_MODEL`, was `claude-sonnet-5`);
  effort is not a useful lever for this task, and Opus/Fable invent an actor the evidence gate can't
  drop. Route `behaviors` to Sonnet for a richer voice on a deliverable.
- **`behaviors` prompt tuned for business-facing use cases** — count-free feature grouping (semantic
  litmus test, not a magic number), `lifecycle` typing for status transitions, no HTTP verb in
  `when`, and a business VOICE with a hard GROUNDING rule (state consequences the material shows;
  never name an actor/recipient absent from it — the prose fields are not evidence-gated).
- **Reasoning depth (`--effort`)** — `--effort low|medium|high|xhigh|max` (implies `--thinking on`)
  tunes reasoning depth, model-aware: `output_config.effort` on modern models (Sonnet/Opus/Fable),
  mapped to `budget_tokens` on extended-thinking models (Haiku 4.5). Lets a capable model at low
  effort compete with a cheaper model at high effort. Fable is handled as always-on (no `disabled`).
- **Configurable, model-aware thinking** — `--thinking on` enables model reasoning for `--provider sdk`
  (default off). The config adapts to the model: adaptive thinking for modern models (Sonnet 4.6+/5,
  Opus 4.6+, Fable) and extended thinking with a `budget_tokens` below `max_tokens` for older ones
  (Haiku 4.5, Sonnet 4.5, 3.x). Reasoning is what recovers business framing in the BDD use cases —
  with thinking off, even Opus restates the HTTP endpoint in the `when`; with it on, a cheap Haiku 4.5
  can beat a larger thinking-off model.
- **Per-task model routing** — `--task-model <task>=<model>` (repeatable) routes a single enrichment
  task to a different model than `--model`, e.g. `--task-model behaviors=claude-opus-4-8` for the
  interpretive use-case task while keeping the mechanical tasks on the cheaper default. The provider
  is resolved per task (one `HttpAnthropicProvider` cached per distinct model).
- **Progress logging** so a run is no longer dead air: the deterministic phase reports parse time, a
  "building call graph" marker, and a counts summary (entities / entry points / nodes / edges / tests
  / event flows + total ms); the enrichment phase announces each call **before** it blocks
  (`[i/n] <task>: requesting <model> (<chars>, ~<tok>)…`), then reports items kept vs dropped and the
  elapsed ms per task, plus a final `enrichment done: <items> across <n> task(s) (<hits> cache hit(s), <ms>)`.
  All on stderr — artifacts (and the determinism proof) are unaffected.
- **Enrichment cache keyed by `(materialHash + promptVersion)`** — `promptVersion` is an auto-derived
  hash of each task's `instructions()`; editing a task's prompt/schema now invalidates only that
  task's cache entries (no manual bump, no `rm -rf cache`). Cache files: `<materialHex>-<promptVersion>.json`.
  The manifest's `sourceHash` still tracks material identity only, so the determinism proof is unchanged.
  (ROADMAP C)
- **Event/message consumers as entry points** — in-process Spring events, Spring Modulith
  `@ApplicationModuleListener`, `@TransactionalEventListener`, Kafka/Rabbit/JMS/SQS listeners and
  functional `Consumer`/`Function` beans. Each becomes a use-case card with its own sequence diagram
  entered on an async arrow (`Event -) Listener`, note *async, after commit*).
- **Event Choreography** section — a deterministic producer → event → consumer flowchart
  (`registerEvent`/`publishEvent`/`send(new X(...))` matched to listeners by event type), async
  edges dashed.
- **Infra boundary in sequence diagrams** — injected out-of-source collaborators (Spring Data
  repositories, Elasticsearch/JDBC/HTTP clients), incl. `this.field.method(...)`, drawn as a datastore
  hop (`→ OwnerRepository: save`) instead of the call being dropped.
- **Interactive diagrams** — drag-to-pan, `+`/`−` zoom, fit, copy the Mermaid source, and an in-page
  expand modal (lightbox; closes on backdrop/Esc).
- **Use cases grouped by entry-point category** (REST / Events / Scheduled / …) then by feature, with
  a subtle per-feature count; event labels shown without the package
  (`SaleConfirmedListener#on(SaleConfirmed)`).
- **petclinic worked example** regenerated in the per-endpoint model.
- **GitHub Pages publishing** — `.github/workflows/pages.yml` deploys the committed, self-contained
  `examples/order-sample/` to GitHub Pages on push to `main` (or manually via `workflow_dispatch`).
  Live at <https://ddmfuhrmann.github.io/knowledge-indexer/>.
- **order-sample enriched** — a second entity (`Shipment`) with its own `ShipmentStatus` state
  machine and an `OrderPaidEvent` choreography (published by `OrderService.pay`, consumed by a
  `@EventListener` on `ShipmentService`), so the example now exercises event flows and a two-entity ER
  diagram, not just a single state machine.

### Changed
- **Use-case model: one BDD scenario per entry point** (previously capability-level cards spanning
  sibling CRUD via `evidence.covers`). Keeps each card's sequence diagram faithful to its own flow and
  makes reads first-class; `covers` is now reserved for genuinely redundant endpoints.
- **Source discovery prunes any dot-directory** automatically (`.git`, `.idea`, `.gradle`,
  `.knowledge-index`, git worktrees under `.claude`, …); test sources no longer produce entry points.
- ER diagram grouped by module (bounded contexts).

### Fixed
- **Call-graph roots matched by `file:line`** — overloaded listeners (`on(A)`/`on(B)`) and same-named
  classes across modules (a modular monolith's per-module `XListener`) no longer root at the wrong
  declaration / render the wrong diagram.
- **Single-batch Mermaid render** — concurrent per-element `mermaid.run` left stray measuring SVGs
  overlapping neighbouring sections; now one batch.
- **Pan/zoom state stored on the element** — drag survives a theme re-render (which swaps the SVG) and
  a modal re-open.

## [0.1.0] — Initial foundation (unreleased)

- **Deterministic layer** — JavaParser AST of the current commit → ER, entry points, call graph, state
  machines, tests, migrations, and error/validation flows; SHA-256 over canonical (sorted) JSON;
  git-derived `generatedAt`. Same code ⇒ byte-identical output.
- **Enrichment layer** — evidence-anchored, cached LLM interpretation (use cases, state transitions,
  domain map); items that don't resolve to a real code anchor are discarded. Pluggable providers:
  `agent` (Claude Code as the LLM, no key) and `sdk` (Anthropic Messages API).
- **Renderer & CLI** — self-contained, themeable HTML (Mermaid ER / state / sequence / mindmap); the
  `run | extract | assemble` commands with `--no-llm` / `--provider` / `--model` / `--exclude`.
- **Worked examples** — `order-sample` fixture (state machine) and external `spring-petclinic`;
  validated multi-module on `spring-petclinic-microservices`.
