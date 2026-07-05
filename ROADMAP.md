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

### 2. Real Anthropic SDK integration — [planned]
Exercise the `--provider sdk` path live (built but never run against the API).
- Set `ANTHROPIC_API_KEY`, run `run <repo> --provider sdk --model <model>`.
- **Validate:** the model returns parseable JSON (fenced output handled by `JsonExtract`),
  `max_tokens` is sufficient, rate-limit/retry behaviour, and that evidence validation keeps a
  reasonable share of items.
- Depends on **A (scale enrichment)** to hold up on large repos.

### A. Scale the enrichment for large repos — [planned]
Today each task sends the **entire** deterministic material in a single prompt. A large repo blows
the context / token budget.
- **Chunk** enrichment by slice/feature, run in parallel, cap material per request.
- Applies to both the `agent` and `sdk` providers.
- **This is the first thing that will hurt on a big repo.**

### B. Performance & symbol resolution — [planned]
Symbol resolution is per-call; thousands of files can get slow.
- Parallel parsing.
- Add a `JarTypeSolver` fed by the target's dependency jars — resolves more edges *and* is faster.
- Incremental mode: only re-parse files changed since the last recorded commit.

### C. Cache keyed by prompt version — [planned] ⚠️ correctness fix
The enrichment cache is currently keyed only by `hash(material)`. Changing a task's instructions or
schema still serves **stale** cached results. Key the cache by `(materialHash + promptVersion)` so a
prompt/schema change invalidates the relevant entries.

### D. Packaging & distribution — [in progress]
Run against any repo without cloning another.
- ✅ Extracted to its own repo (`~/git/knowledge-indexer`) with a neutral package
  (`io.github.ddmfuhrmann.kindexer`).
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

---

## Suggested sequencing

For the larger project specifically: **A → B → C** first (otherwise the first large run stalls or
gets expensive), then **2 (live SDK)** validated at that scale, then **1 (history/diff)** and
**D (packaging)** — the diff is the "sellable" feature. Run the larger repo in **`--no-llm`** early to
measure size/perf and surface **E** gaps before committing effort.
