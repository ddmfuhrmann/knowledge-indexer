# knowledge-indexer — CLAUDE.md

A standalone **Java 21 / Gradle** tool that turns a Spring Boot repo's working tree into a knowledge
index: a **deterministic** AST extraction, an **enrichment** layer (LLM, evidence-anchored), rendered
to a self-contained HTML. Base package `io.github.ddmfuhrmann.kindexer`.

Read [README.md](README.md) for what it does, [ROADMAP.md](ROADMAP.md) for the plan,
[docs/providers.md](docs/providers.md) for LLM providers. This file is the **how to work in here**.

## Non-negotiable invariants (break these and the tool loses its point)

1. **Two layers, one direction.** Deterministic (pure AST of the current commit) → enrichment (LLM
   reads only the deterministic material). **LLM output never feeds back** into the deterministic
   layer — the call graph and all raw material always come from the AST.
2. **Determinism is a hard requirement.** Two `run --no-llm` must produce **byte-identical**
   `manifest.json` + `index.html`. So: sort every extractor's output (TreeMap / `List.sort`), never
   depend on `HashMap`/`HashSet` iteration order for output, and never use wall-clock time or
   randomness (`generatedAt` is the git **commit** time). If you add output, sort it.
3. **Evidence or it's dropped.** Every enrichment item must resolve to a real anchor via
   `EvidenceIndex` (class / entry-point id / node id / `file:line` / test key) or `validate()`
   discards it. When you add an enrichment task, add its validation — no anchor, no item.
4. **`manifest.json` is the single source of truth; the HTML is derived.** `HtmlRenderer` reads
   **only** the manifest artifacts — never re-parses source. Anything the view needs must be an
   artifact.
5. **Agnostic.** Key only on JPA/Spring **standard** annotations and naming heuristics. Nothing may
   reference a target project's domain. Validate against `spring-petclinic` mentally: would it still
   work with zero config?

## Architecture (where things live)

```
ast/ProjectModel        parse the working tree (shared symbol solver, multi-module discovery)
extract/                the deterministic extractors (one concern each)
model/                  record shapes for each artifact (Entities, EntryPoints, CallGraph, EventFlows, …)
enrich/                 Deterministic bundle, EvidenceIndex, EnrichmentCache, Enricher, providers
  task/Tasks.java       the 3 enrichment tasks: behaviors (use cases), stateTransitions, domains
hash/ContentHash        SHA-256 over canonical (sorted) JSON — the enrichment cache key
manifest/Manifest       single source of truth
render/HtmlRenderer     manifest → self-contained HTML (Mermaid, theme, pan/zoom, sections)
pipeline/Pipeline       wires extractors → Deterministic → artifacts → manifest → HTML
```

**Adding a deterministic extractor** (5 touch points): a record in `model/`, the extractor in
`extract/` (sorted output), a field on `enrich/Deterministic`, wire it in `Pipeline.extract()` **and**
`Pipeline.artifacts()`, then render it in `HtmlRenderer` (read the artifact by key, cast to the typed
list). Keep `EvidenceIndex.build` in sync if the artifact introduces new anchors.

## Gotchas (hard-won — don't relearn these)

- **Method identity = `file:line`, not simple name.** Overloaded methods (`on(A)`/`on(B)`) and
  same-named classes across modules (a modular monolith's per-module `XListener`) collide on
  class+method. Call-graph roots and the renderer's node lookup match on `file:line`; event entry-point
  ids use the **FQN** + payload.
- **The enrichment cache is keyed by `(materialHash + promptVersion)`.** `promptVersion` is an
  auto-derived hash of the task's `instructions()` (`EnrichmentTask.promptVersion()`), so editing a
  task's instructions/schema invalidates only that task's entries — no more `rm -rf cache`. Cache
  files are named `<materialHex>-<promptVersion>.json`. (Was Roadmap **C**, now shipped.)
- **Render Mermaid in a single `mermaid.run` batch.** Concurrent per-element runs leave stray
  measuring SVGs that overlap other sections. Use `MermaidSafe.id`/`.label` for every identifier/label.
- **Entry points come from `src/main` only** (test sources are excluded); the call graph/entities may
  include tests, so filter by category/role when it matters.
- **Infra boundary** in sequences comes from injected fields (`repo.x()` and `this.repo.x()`) whose
  call doesn't resolve in-source — surfaced as a synthetic `external` node. Value-type/JDK fields and
  project enums are excluded to avoid noise.

## Build, run, verify

```bash
./gradlew -q installDist
BIN=build/install/knowledge-indexer/bin/knowledge-indexer

$BIN run <repo> --no-llm --out out/                 # deterministic only (determinism proof / CI gate)
$BIN extract <repo> --out out/                       # emit enrich/requests/*.json for cache-misses
#   ...fill out/enrich/responses/<task>.json (evidence required)...
$BIN assemble <repo> --out out/                      # validate + cache + render
$BIN run <repo> --provider sdk --model claude-sonnet-5 --out out/   # headless, needs ANTHROPIC_API_KEY
```

- **Verify diagrams in a real browser**, not by eyeballing text: serve `out/` with a static server and
  open `index.html` (the Preview/MCP flow). Sequence/choreography diagrams only render client-side.
- **Determinism check:** run `--no-llm` twice into two dirs and diff `manifest.json`.
- Output + cache live under `<repo>/.knowledge-index/`. Never commit private example outputs — only
  `examples/<name>/` (order-sample fixture; petclinic needs a shallow clone to regenerate). The
  `order-sample` fixture has two entities (`Order`, `Shipment`), two status enums (two state machines),
  and an `OrderPaidEvent` choreography (publish → `@EventListener`); it's published to GitHub Pages via
  `.github/workflows/pages.yml`.

## Docs to keep current

When behavior changes, update in the same PR: **README** (current state), **ROADMAP** (mark shipped /
add ideas), **CHANGELOG** (`[Unreleased]`), and **docs/providers.md** if the provider surface moves.
