# knowledge-index — Roadmap

Forward-looking plan for the tool. Scope for now is **Spring Boot (Java) only**; broader
framework/language support is tracked as a **risk**, not a commitment (see Risks).

Guiding principles are unchanged: a **deterministic** layer (pure AST of the current commit,
reproducible, diffable) with an **enrichment** layer (LLM, evidence-anchored, cached) layered on
top — the LLM never feeds back into the deterministic material.

Legend: **[planned]** committed near-term · **[risk]** watch/scope boundary · **[idea]** backlog.

---

## Planned

### 1. Commit-hash history index + diff / CI gate — [planned]
The payoff of the deterministic layer: a browsable, diffable history of runs.
- **Store:** `.knowledge-index/history/<commit>/manifest.json` (+ `index.html`), plus an
  `index.json` mapping commit → { timestamp, summary counts, coverage rollup }.
- **Index page:** lists all recorded runs and links to each.
- **`diff <commitA> <commitB>`:** added/removed endpoints, new/removed use cases, **coverage
  regressions**, new critical gaps.
- **CI gate:** fail a build when a PR adds an endpoint with no use case, drops coverage, or
  introduces a new critical gap.
- The enrichment cache is already content-addressed, so re-running old commits reuses
  interpretations where the deterministic material is unchanged.

### 2. Real Anthropic SDK integration — [shipped]
The `--provider sdk` path is hardened for real runs, not just built:
- ✅ `thinking` explicitly **disabled** so the whole `max_tokens` budget goes to the JSON array
  (recent models otherwise run adaptive thinking by default and silently truncate the output).
- ✅ `max_tokens` raised to 16000 (was 4096) and made tunable via `--max-tokens N`; a
  `stop_reason=max_tokens` is surfaced as a warning so truncation is never masked.
- ✅ Transient failures (429/5xx/529, I/O/timeout) retried with exponential backoff honouring
  `retry-after`; non-retryable statuses throw.
- ✅ Per-task **keep-rate** logged (`<task>: <kept> itens (raw <n>) via <model>`) so a weak or failed
  run is visible instead of a silently empty section.
- Still open: **A (scale enrichment)** for large repos, and a live run against `spring-petclinic`
  with a real `ANTHROPIC_API_KEY` (hardening verified offline via an `ANTHROPIC_BASE_URL` stub).

### 3. Multi-vendor & local LLM providers — [planned]
The enrichment provider is already pluggable (`agent` + `sdk` = Anthropic Messages API). Add more
backends behind the same interface + evidence validator. Provider landscape and model notes:
[docs/providers.md](docs/providers.md).
- **OpenAI-compatible provider** — one `/v1/chat/completions` client covers OpenAI **and** most local
  runners (Ollama, LM Studio, llama.cpp, vLLM) via `--base-url` + `--model`; key from an env var.
- **Google Gemini** provider (its own request shape).
- Config precedence: `--provider {anthropic|openai|gemini|local}` + `--base-url`/`--model`, keys from
  env only (never committed).
- **Caveat:** small/local models return messier JSON and weaker anchoring — lean on `JsonExtract`
  (fenced-output tolerant) and the evidence validator (unanchored items already dropped), and report
  the keep-rate so a weak model is visible rather than silently lossy.
- **Provenance:** the model is already recorded per enrichment section; add a **footer line**
  (provider + model + generated-at) so a run says which model interpreted it — matters once several
  are in play.
- **CI note:** GitHub-hosted runners are CPU-only, so a local model on the runner is slow and weak;
  prefer a cheap hosted OpenAI-compatible endpoint (key in Secrets) for the enrichment, and run the
  deterministic gate with `--no-llm` (free). See the cost options in the CI discussion.

### A. Scale the enrichment for large repos — [partly shipped]
A large repo used to send the **entire** deterministic material in one prompt, blowing the token
budget and the request timeout (order-sample 12 endpoints ≈ 9K tokens; a 122-endpoint app ≈ 140K,
which times out non-streaming).
- ✅ **Chunked the `behaviors` task** (its material scales with the codebase): split by controller (a
  controller is never split), and **scope the call graph per chunk** to the sub-graph reachable from
  the chunk's endpoints — the graph is the main inflator. Each chunk is prompted, cached and
  validated on its own, then merged; the evidence gate still validates against the full index.
  `EnrichmentTask.chunks()`; only `behaviors` overrides it.
- ✅ **Auto-sizes chunks** by material size (~90K chars/chunk ≈ ~50s/call); `--behaviors-chunk N`
  overrides with a manual endpoint cap. Small repos stay a single unchanged call.
- ✅ **1:1 kept under chunking** — a smaller prompt tempts the model to emit a card per branch; the
  prompt + a dedup backstop keep exactly one use case per endpoint.
- Still open: **run chunks in parallel** (today sequential — outfit ≈ 7 min) and add **streaming** so
  a single oversized chunk can't hit the timeout. `domains` isn't chunked (it groups across the whole
  system); it fits so far. The `agent` provider path isn't chunked yet.

### B. Performance & symbol resolution — [planned]
Symbol resolution is per-call; thousands of files can get slow.
- Parallel parsing.
- Add a `JarTypeSolver` fed by the target's dependency jars — resolves more edges *and* is faster.
- Incremental mode: only re-parse files changed since the last recorded commit.

### C. Cache keyed by prompt version — [shipped] ⚠️ correctness fix
The enrichment cache is now keyed by `(materialHash + promptVersion)`, where `promptVersion` is a
short auto-derived hash of the task's `instructions()` (`EnrichmentTask.promptVersion()`). Editing a
task's instructions/schema invalidates only that task's entries — no manual bump, no `rm -rf cache`.
Cache files are named `<materialHex>-<promptVersion>.json`. The manifest's `sourceHash` still tracks
material identity only, so the determinism proof is unchanged.

### D. Packaging & distribution — [in progress]
Run against any repo without cloning another.
- ✅ Extracted to its own repo (`~/git/knowledge-indexer`) with a neutral package
  (`io.github.ddmfuhrmann.kindexer`).
- ✅ **Hosted example** — `order-sample` published to GitHub Pages via `.github/workflows/pages.yml`
  (the self-contained HTML needs no build to serve): <https://ddmfuhrmann.github.io/knowledge-indexer/>.
- Remaining: distributable fat-jar / Docker image / release so `knowledge-indexer run <repo>` works
  anywhere without a local Gradle build.

---

## Risks

### E. Framework & language coverage — [risk]
Current support: **Spring MVC / REST + JPA**, plus **event/message consumers** — in-process Spring
events, Spring Modulith `@ApplicationModuleListener`, Kafka/Rabbit/JMS/SQS listeners, and functional
`Consumer`/`Function` beans (with a producer→event→consumer choreography graph). Out of scope until
re-prioritized, tracked here so it isn't a surprise on a new codebase:
- **Kotlin sources are invisible** (JavaParser is Java-only) — the biggest gap for mixed codebases.
- Spring **WebFlux** (reactive), **JAX-RS**, **Micronaut**, **Quarkus**, **gRPC / GraphQL** entry
  points are not detected (the use-case view already groups by entry-point category, so these slot in
  once their extractors exist).
- Non-standard validation/guard styles beyond what the guard extractor recognizes.
- **Mitigation:** run new targets in `--no-llm` first to measure size/perf and surface unmapped
  surface area before investing.

---

## Ideas (backlog)

### F. Security / authorization matrix — [idea]
Extract `@PreAuthorize` / `@Secured` / `@RolesAllowed` per endpoint → a "who can do what" matrix.

### G. Cross-service / microservices map — [idea]
The call graph is per-module today. Detect inter-service calls (Feign / `WebClient` / `RestTemplate`,
Kafka topics) to build a service-level map for distributed systems.

### H. JaCoCo coverage (optional input) — [idea]
Now that alternative flows are deterministic, consume an existing `jacoco.xml` (`--coverage`) to
attribute **measured** line/branch coverage per flow, complementing the LLM coverage estimate. Stays
static — parses a report the CI already produces; does not run tests.

### I. Business decision points — [idea]
`if` / `switch` branches that change business outcome without throwing (e.g. "commission nulled for a
non-salesperson") → a decision/rule map beyond the error paths. Noisier; needs filtering heuristics.

### J. Export formats — [idea]
Generate/validate an **OpenAPI** spec (we have endpoints + commands + responses), Markdown docs, and
standalone `.mmd` diagram files, in addition to the HTML.

### K. Tool testing & real Mermaid validation — [idea]
- Golden-fixture tests for the extractors; a determinism check in CI.
- **Headless Mermaid validation** — today diagram syntax is only sanitized statically, never rendered
  to confirm it parses (this was in the original spec and is still only partially done).

### L. Deep links to source — [idea]
Make the index a navigable entry point into the code, not just a description. Every node, entry point,
listener, test, throw site and assignment already carries a deterministic `file:line`, so:
- Link class/method names (use-case endpoints, sequence participants, `verifiedBy` tests, choreography
  producers/consumers, ER entities) to the source **at the run's commit**.
- Resolve the target from git: a GitHub/GitLab **blob URL** (`.../blob/<commit>/<file>#L<line>`) when
  an `origin` remote is known; otherwise local `file://` or editor deep-links (`vscode://file/…:line`).
- Keep it deterministic and optional — a `--repo-url <base>` override for hosts that can't be inferred,
  and graceful omission (plain text) when no location resolves.
- **In Mermaid diagrams:** don't rely on Mermaid's uneven `click`/`link` support (flowchart has
  `click`, sequence only participant `link` popups needing `securityLevel: loose`, erDiagram none).
  We already own the rendered SVG in the pan/zoom layer — post-`mermaid.run`, walk the SVG, match
  `<text>` nodes to known class names, and wrap them in `<a href>` to the source. One mechanism, all
  three diagram types, no securityLevel change.

### M. Steerable analysis — extra prompt context / args — [idea]
Let the caller inject guidance into the **enrichment** prompt without touching the deterministic layer:
`--prompt-extra "<text>"` and/or `--context-file <path>` (a domain glossary, naming conventions,
"focus on security / fiscal flows", priority hints). Appended to the task instructions, never to the
material — the AST-derived facts stay pure and reproducible.
- **Must** fold the extra text into the enrichment **cache key** (ties into **C** — cache by
  `materialHash + promptVersion`), so changing the guidance re-runs instead of serving stale results.
- Deterministic output is unaffected; only interpretation is steered.

### N. MCP server — serve the index to agents — [idea] ⭐ high-leverage
Expose the index over **MCP** so a coding agent (Claude Code, Cursor, …) queries the map instead of
re-deriving structure by reading files. Closes the loop: the index feeds back to the AIs.
- **Tools over `manifest.json`:** `list_endpoints` / `get_use_case(endpoint)` /
  `sequence_for(endpoint)` / `callers_of` & `callees_of(class#method)` / `who_publishes` &
  `who_consumes(event)` / `coverage_gaps` / `tests_for(endpoint)` / `entities` / `state_machine(enum)`.
- **Serve the *derived* views** — reachability, choreography, coverage, use-case-by-endpoint — the
  queries that are expensive for an agent to compute ad hoc; plain "read a file" adds nothing over the
  agent's own tools.
- **Staleness is the one real risk:** the index is a snapshot at a commit. Return the indexed commit
  with every answer, and offer an on-demand `reindex` (the deterministic pass is fast) so an agent
  editing code isn't grounded on stale structure.
- **Worth it:** the manifest is already the single source of truth and the deterministic layer is
  reproducible — that makes it *trustworthy grounding*, which is exactly what agents lack. Arguably the
  highest-leverage consumer of the whole tool. Start read-only over a pre-built manifest; add `reindex`
  once the query surface proves useful.

---

## Suggested sequencing

For the larger project specifically: **A → B → C** first (otherwise the first large run stalls or
gets expensive), then **2 (live SDK)** validated at that scale, then **1 (history/diff)** and
**D (packaging)** — the diff is the "sellable" feature. Run the larger repo in **`--no-llm`** early to
measure size/perf and surface **E** gaps before committing effort.
