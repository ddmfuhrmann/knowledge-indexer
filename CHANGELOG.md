# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/spec/v2.0.0.html). No versions are tagged/released yet —
everything below the foundation is under **Unreleased**.

## [Unreleased]

### Added
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
