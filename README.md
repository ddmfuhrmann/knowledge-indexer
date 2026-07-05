# knowledge-indexer

A utility that turns a **Spring Boot** repo's working tree into a knowledge index: a deterministic
AST extraction, enriched by an LLM only where interpretation is required, rendered to a
self-contained, themeable HTML.

> Forward plan lives in [ROADMAP.md](ROADMAP.md).

## Two layers, strictly separated

| Layer | Source | Property |
|---|---|---|
| **Deterministic** | JavaParser AST of the current commit | Same code ⇒ byte-identical output |
| **Enrichment** | An LLM interpreting *only* the deterministic material | Every item anchored to real evidence; cached by input hash |

The headline enrichment is **use cases** (flows): one BDD scenario per meaningful use case
(Given/When/Then in domain language), each entering through a real endpoint, classified by
capability / type / priority, and linked to the real test that verifies it — or flagged as a gap.
A use case can span sibling CRUD endpoints via `evidence.covers`, so every endpoint is accounted for;
anything left is listed under "endpoints without a use case". The other two enrichment tasks (state
transitions, domain map) are structural views that don't fit the use-case card.

Non-negotiables enforced in code:

1. The LLM output **never** feeds back into the deterministic layer (call graph & raw material
   always come from the AST — reproducibility/replay preserved).
2. `manifest.json` is the **single source of truth**; the HTML is a derived view.
3. Every deterministic artifact carries a `contentHash` of its input. Enrichment is keyed by that
   hash — unchanged material reuses the cached interpretation, no LLM call.
4. Every enrichment item must resolve to a real code anchor (`EvidenceIndex`) or it is **discarded**.
5. Determinism: stable sort everywhere; `generatedAt` is the git **commit** time, not the wall clock.

## What a use-case card shows

Everything below the LLM narrative is **deterministic**, scoped to the endpoint's reachable call graph:

- **Preconditions** — Bean Validation on the `@RequestBody` command (`@NotNull`, `@NotEmpty`, …) plus
  per-field imperative guards reachable in the flow (`Guard.*`, `Objects.requireNonNull`, Spring
  `Assert.*`), read from the call site so they name the actual field.
- **Sequence diagram** — Mermaid, built from the call graph rooted at the endpoint: `Client →
  Controller → Service → domain/repo` with typed dashed **returns**, identical interactions deduped.
  The outcome is an `alt` block — each reachable exception is a guarded error branch
  (`Controller --x Client: 409 CONFLICT (ConflictException)`), with **success as the default
  `else`** — so the diagram reads as the whole request, happy path and error branches, all returning
  to the Client at the HTTP boundary.
- **Alternative flows** — the reachable `throw new *Exception(...)` sites (covers `throw` and
  `orElseThrow`), each with its guard condition and its **HTTP status** resolved from the global
  `@ControllerAdvice` (`NotFoundException → 404`, `ConflictException → 409`, …).
- **Coverage** — the deterministic `verifiedBy` test link, plus an LLM **coverage estimate**
  (`full` / `partial` / `thin` + which branches look untested), grounded in the alternative flows.

The page also has: an **ER** `erDiagram`, a **state machine** `stateDiagram-v2` (from enriched
transitions over the enum's assignment sites), a **domain** `mindmap`, a collapsed **test catalog**
table, a **light/dark theme** toggle (system-detected, saved to `localStorage`, re-themes Mermaid),
and a collapsible **commit-message** box. Deterministic vs LLM items are badged; evidence is in a
tooltip.

## Design decisions

- **Java 21 standalone Gradle tool.** JavaParser + its symbol solver (mandated for the call graph)
  are Java-only, and the target stack is Java — so the tool matches the repo's own toolchain and
  runs against any Spring Boot repo path. Built as an `installDist` launcher; no fat-jar plugin.
- **Call graph via `javaparser-symbol-solver-core`.** `CombinedTypeSolver` =
  `ReflectionTypeSolver` + a `JavaParserTypeSolver` per source root. Only **resolved intra-project**
  edges are kept; when precise resolution fails on a complex call, a receiver-type fallback recovers
  the edge (still validated against the real declaration index — never fabricated). No regex for code
  structure.
- **Project-agnostic, multi-module aware.** Nothing keys on the target's domain — only JPA/Spring
  standard annotations and graceful naming heuristics. Source roots are discovered by walking the
  tree for **every** `src/main/java` / `src/test/java` (so a multi-module repo is handled), pruning
  build/VCS/IDE dirs; `--exclude` skips nested or vendored projects. Validated on `spring-petclinic`
  (single-module) and `spring-petclinic-microservices` (8 modules) with zero configuration.
- **Enrichment provider is pluggable.** Default `agent` = a file handoff where **Claude Code is the
  LLM** (no API key). `sdk` = a thin `java.net.http` client for the Anthropic Messages API
  (`ANTHROPIC_API_KEY`) — same prompts, for headless/CI. Both share the cache + evidence validator.
- **Canonical JSON** (Jackson, alphabetically sorted keys) for both hashing and on-disk files.

## Layout

```
src/main/java/.../kindex/
  Cli.java                 commands: run | extract | assemble  (--out --no-llm --provider --model --exclude)
  ast/ProjectModel.java    parse working tree with shared symbol solver (multi-module discovery)
  git/GitInfo.java         HEAD hash + commit time + commit message (deterministic generatedAt)
  extract/                 DETERMINISTIC extractors:
                             Entity, EntryPoint, CallGraph, StateMachine, TestScenario, Migration,
                             Error (throws + exception→status), InputConstraint, Guard
  model/                   record data shapes (Entities, EntryPoints, CallGraph, StateMachines,
                             TestScenarios, Migrations, Flows)
  hash/ContentHash.java    SHA-256 over canonical JSON
  enrich/                  Deterministic bundle, EvidenceIndex, EnrichmentCache, Enricher, providers
    task/Tasks.java        the 3 tasks: behaviors (use cases), stateTransitions, domains
  manifest/Manifest.java   single source of truth
  render/HtmlRenderer.java manifest → self-contained HTML (Mermaid, theme, sequence/alt blocks)
fixtures/order-sample/     tiny Spring Boot with an OrderStatus enum (exercises the state machine)
examples/                  committed manifest.json + index.html per example run (order-sample, petclinic, …)
ROADMAP.md                 forward plan
```

## Run

```bash
./gradlew -q installDist
BIN=build/install/knowledge-indexer/bin/knowledge-indexer

# deterministic only (reproducible; determinism proof):
$BIN run <repo> --no-llm --out out/

# agent enrichment (Claude Code is the LLM): extract → fill enrich/responses/*.json → assemble
$BIN extract <repo> --out out/
#   ...write out/enrich/responses/<task>.json per each request's schema (evidence required)...
$BIN assemble <repo> --out out/

# headless enrichment via the Anthropic API:
ANTHROPIC_API_KEY=… $BIN run <repo> --provider sdk --model claude-sonnet-5 --out out/

# prune a nested/vendored project (e.g. when the tool lives inside the target repo):
$BIN run <repo> --no-llm --exclude .skills --out out/
```

`knowledge-indexer.sh <cmd> <repo> …` builds if stale and runs in one step. Output + cache live under
`<repo>/.knowledge-index/`; committed `examples/<name>/enrich/responses/*.json` are the recorded
agent interpretations that make an example reproducible.

## Validation performed

- **Determinism:** two `run --no-llm` produce byte-identical `manifest.json` + `index.html`
  (the sequence diagrams and all deterministic material included).
- **Anchoring:** on `fixtures/order-sample`, deliberately fabricated enrichment items (a transition
  at a non-existent line, a non-existent domain member, coverage for a non-existent endpoint) are
  dropped by `EvidenceIndex`; only evidence-backed items survive.
- **Full endpoint coverage:** a capability-level use case spans its sibling CRUD endpoints via
  `covers`, so every HTTP endpoint is accounted for; only non-HTTP entry points (e.g. a
  `CommandLineRunner`) are left unclassified.
- **Agnosticism:** validated end-to-end on external `spring-petclinic` (single-module) and
  `spring-petclinic-microservices` (8 Maven modules) with zero configuration.
- **Robustness / honesty:** on codebases without a given surface (no status enum, no migrations, no
  `@RequestBody` / explicit exceptions — e.g. petclinic's form-MVC style) the corresponding sections
  are simply empty; nothing is invented.
