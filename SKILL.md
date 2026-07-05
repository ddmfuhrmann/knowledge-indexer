---
name: knowledge-index
description: >
  Build a "knowledge index" for a Spring Boot repo: a deterministic AST extraction (ER, entry
  points, call graph, state machines, tests) enriched by an LLM only where interpretation is
  needed, rendered to a self-contained HTML. Use when the user says "knowledge index", "map this
  codebase", "generate the ER / state machine / domain map", "index this Spring Boot project", or
  invokes /knowledge-index. Two layers: deterministic (from the AST of the current commit,
  reproducible) and enrichment (LLM, evidence-anchored, cached). This project is a standalone Gradle Java 21 tool at
  ~/git/knowledge-indexer.
---

# Knowledge Index

A utility that reads the **working tree** of a Spring Boot repo and produces a `manifest.json`
(single source of truth) plus a derived `index.html`. Two strictly separated layers:

1. **Deterministic** — pure functions over the JavaParser AST of the current commit. Same code ⇒
   same output, byte for byte. This is where all raw material comes from (ER, entry points, call
   graph, state-machine states + assignment sites, tests). Never from an LLM.
2. **Enrichment** — an LLM interprets the deterministic material only. Three tasks: **use cases**
   (the headline business view — a BDD flow per meaningful use case: Given/When/Then in domain
   language, each entering through a real endpoint, classified by capability / type / priority, and
   linked to the test that verifies it or flagged as a coverage gap), **state transitions**, and
   **domain grouping**. Every item is **anchored to real code evidence** and discarded if the anchor
   doesn't resolve. Keyed by the `contentHash` of its input and cached, so unchanged code is never
   re-sent to the LLM.

**The LLM output never feeds back into the deterministic layer** — the call graph and all raw
material always come from the AST, preserving reproducibility.

## How to run it (agent flow — default, no API key)

In this flow **Claude Code is the LLM**. Build once, then extract → fill responses → assemble.

```bash
cd ~/git/knowledge-indexer
./gradlew -q installDist                         # build (first time / after edits)
BIN=build/install/knowledge-indexer/bin/knowledge-indexer

# Phase 1 — deterministic extract + emit enrichment requests for cache-misses:
$BIN extract <repo> --out <outDir>
```

Then, for each file in `<outDir>/enrich/requests/<task>.json`, read its `instructions` +
`material` and **write** `<outDir>/enrich/responses/<task>.json` — a JSON array obeying the task's
schema. Each item MUST carry `evidence` pointing at a real element from the material (class /
method / file / line, an entry-point id, or a node id); items whose evidence doesn't resolve are
dropped on assemble. Do not invent classes, transitions, or endpoints not present in the material.

```bash
# Phase 2 — validate responses (drop unanchored), cache, merge, render:
$BIN assemble <repo> --out <outDir>
```

Cache hits mean `extract` may emit zero requests — then just run `assemble` (or `run`).

## Other modes

```bash
$BIN run <repo> --out <outDir> --no-llm          # deterministic only (CI, determinism proof)
$BIN run <repo> --out <outDir> --provider sdk --model claude-sonnet-5   # headless, needs ANTHROPIC_API_KEY
```

- `--no-llm`: enrichment sections present but empty. Two runs are byte-identical.
- `--provider sdk`: enrichment via the Anthropic Messages API (key from `ANTHROPIC_API_KEY`). A
  failed LLM call leaves that section empty and never breaks generation.

## Output

- `<outDir>/manifest.json` — the single source of truth (`project`, `artifacts`, `enrichment`).
- `<outDir>/index.html` — headline **Use Cases** section (collapsible BDD cards grouped by capability).
  Each card carries, all deterministic: **preconditions** (Bean Validation on the `@RequestBody`
  command plus per-field imperative guards reachable in the flow — `Guard.*`, `Objects.requireNonNull`,
  Spring `Assert.*`), a **sequence diagram** (from the call graph, identical interactions deduped),
  **alternative flows** (exceptions reachable in the flow with their HTTP status, from the global
  handler), and its test coverage. Plus an
  "endpoints without a use case" list, then Mermaid `erDiagram`, `stateDiagram-v2`, and a domain
  `mindmap`. Every LLM item carries an "interpreted" badge with its evidence in a tooltip.

## Notes

- **Agnostic across Java/Spring Boot projects.** No target-specific coupling — extractors key only
  on JPA/Spring standard annotations. Source roots are auto-discovered (every `src/main/java` /
  `src/test/java` in the tree), so **multi-module** repos work; `--exclude a,b` prunes nested or
  vendored projects (e.g. `--exclude .skills` when the tool lives inside the target repo).
- Robust to missing pieces: no migrations, no tests, no status enum, or no entry points — the
  section is simply empty, never an error.
- `<repo>/.knowledge-index/` (cache + out) is the working area; commit only `cache/` when you want
  the recorded enrichment to be reproducible offline.
- Worked examples: `examples/order-sample/` (the `fixtures/order-sample/` toy — full pipeline incl.
  state machine) and `examples/petclinic/` (external Spring Boot repo — use cases + sequence diagrams).
