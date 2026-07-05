# Anthropic model benchmark — choosing the sdk enrichment model

How we pick the model / thinking / effort for the `--provider sdk` enrichment path, why **Haiku 4.5
with thinking off is the default**, and how to re-run the benchmark on your own repo.

The enrichment layer has three tasks; only one is quality-sensitive:

- `stateTransitions`, `domains` — **mechanical** structured extraction. Any model does them well.
- `behaviors` — the **interpretive** task: BDD-style use cases (feature / scenario / given-when-then)
  that a product owner reads. This is where model choice actually shows, so the benchmark scores
  `behaviors`.

The tension is **cost × quality × grounding**. Quality is easy to see; grounding is the trap — the
prose fields (`given`/`when`/`then`, `scenario`) are **not** evidence-gated (only the structural
anchors are — endpoint, nodes, tests). So a model that writes richer prose can also **invent** facts
(actors, motives, downstream effects) that slip through unvalidated. The benchmark measures both.

## The knobs

| Flag | Effect |
|---|---|
| `--model M` | default model for all tasks |
| `--task-model behaviors=M` | route one task to a different model (mechanical tasks stay cheap) |
| `--thinking on` | enable reasoning; model-aware (adaptive for modern models, extended `budget_tokens` for Haiku 4.5) |
| `--effort low..max` | reasoning depth; `output_config.effort` on modern models, mapped to `budget_tokens` on Haiku 4.5 (implies `--thinking on`) |

## Methodology

**Matrix:** `{haiku, sonnet, opus} × {off, low, medium, high}` (12 cells) + `fable (off)` = 13 cells.
Each cell routes `behaviors` to the cell model and keeps the mechanical tasks on Haiku (cheap). The
cache is cleared per cell — the cache key is `(material + promptVersion)` and does **not** include the
model, so without clearing, a later cell would serve an earlier cell's `behaviors` result.

**Reproduce:**

```bash
scripts/benchmark-matrix.sh [REPO]      # runs the 13 cells → /tmp/ki-matrix/<model>-<effort>/
scripts/benchmark-report.py /tmp/ki-matrix   # prints the cost × quality table
```

Real API calls — order-sample costs ~US$1.8 for the full matrix; a larger repo costs proportionally
more. Skip the (expensive) Fable cell with `FABLE= scripts/benchmark-matrix.sh`.

**Metrics** (per cell, from the `behaviors` output + the run log):

| Signal | Meaning | Good |
|---|---|---|
| `cov` | a scenario for every entry point, incl. the event listener | `12/12` |
| `whHTTP` | `when` clauses that leak the HTTP verb/path (anti-pattern) | `0` |
| `life` | scenarios typed `lifecycle` (state transitions should be) | high |
| `ft` | distinct feature (business-capability) headers — grouping, not fragmentation | small, meaningful |
| `invent` | ungrounded actor/recipient words in the prose (customer/operator/carrier/…) | `0` |
| `$total` / `$beh` | estimated cost (run total / behaviors call) — indicative list prices | lower |

## Results (order-sample, 2026-07)

Model IDs: `claude-haiku-4-5`, `claude-sonnet-5`, `claude-opus-4-8`, `claude-fable-5`. Costs are
estimates from indicative list prices; treat them as ratios, not invoices.

| model | effort | $ total | $ behaviors | cov | whHTTP | life | ft | invent |
|---|---|---|---|---|---|---|---|---|
| **haiku** | **off** | **$0.043** | **$0.031** | 12/12 | 0 | 8 | 2 | **0** |
| haiku | low | $0.052 | $0.035 | 12/12 | 0 | 8 | 4 | 0 |
| haiku | medium | $0.059 | $0.039 | 12/12 | 0 | 8 | 3 | 0 |
| haiku | high | $0.073 | $0.047 | 12/12 | 0 | 9 | 3 | 0 |
| sonnet | off | $0.121 | $0.110 | 12/12 | 0 | 9 | 2 | 1 |
| sonnet | low | $0.122 | $0.105 | 12/12 | 0 | 9 | 2 | **0** |
| sonnet | medium | $0.139 | $0.119 | 12/12 | 0 | 9 | 2 | 0 |
| sonnet | high | $0.174 | $0.144 | 12/12 | 0 | 8 | 2 | 0 |
| opus | off–high | ~$0.20 | ~$0.19 | 12/12 | 0 | 9 | 2 | **1 (every cell)** |
| fable | off | $0.449 | $0.438 | 12/12 | 0 | 9 | 2 | 1 |

### Findings

1. **The prompt does the heavy lifting, not the model.** After tuning the `behaviors` prompt
   (business-capability grouping, `lifecycle` typing, no HTTP verb in `when`, business VOICE with a
   hard GROUNDING rule), every cell hits 12/12 coverage, 0 HTTP leak, 8–9 lifecycle, clean grouping.
   Model and effort barely move the structural quality.
2. **Effort is not a useful lever for this task.** Quality is flat from `off` to `high`; only cost
   rises. Do not pay for `high`.
3. **Invention is the real differentiator — and it is counterintuitive.** Haiku invents **nothing**
   (it plays safe). Sonnet at `low`+ also invents nothing (the tightened GROUNDING rule holds).
   **Opus invents one actor (`customer`) in every cell**, despite the same rule, and costs more than
   Sonnet. Fable invents too and costs ~12× Haiku. The richer voice of the bigger models comes with a
   fabrication tax that the evidence gate cannot catch.
4. **Cost:** Haiku ≈ 1/3 of Sonnet, ≈ 1/5 of Opus, ≈ 1/12 of Fable.

Voice, qualitatively: Haiku is correct but plain (*"the order is marked paid and a shipment is
created"*); Sonnet-`low` is the richest **grounded** voice (*"the order's payment is confirmed, and
shipment preparation can begin"*, zero invention); Opus/Fable are rich but each slip an ungrounded
`customer` in.

## Decision — defaults

- **Default: `claude-haiku-4-5`, thinking off.** Cheapest, zero invention, structurally identical to
  the bigger models. This is the shipped default (`Cli.DEFAULT_MODEL`).
- **Deliverable / published docs:** route just `behaviors` to Sonnet for the richer grounded voice —
  `--task-model behaviors=claude-sonnet-5 --effort low` — keeping the mechanical tasks on Haiku. ~$0.12
  on order-sample; still far below Opus.
- **Skip Opus and Fable** for this task: more expensive, and both invent an actor the gate won't drop.
- **Never `--effort high`** here — it buys nothing measurable and costs more. `off` or `low` only.

### Caveats

- **Numbers are repo-specific.** These come from `order-sample` (12 endpoints, 2 entities). Re-run the
  matrix on a representative repo before committing to a model for it.
- **Context window.** Haiku 4.5 has a 200K context; `behaviors` sends the whole deterministic material
  in one prompt. On a **large** repo the material can exceed that and Haiku 400s — route `behaviors`
  to Sonnet (1M context) there, which is a **capacity** need, not just quality. (Chunking is
  ROADMAP A.)
- **Prose is not evidence-gated.** The `invent` metric is a keyword proxy; on a big repo you cannot
  eyeball hundreds of scenarios, which is exactly why the low-invention default (Haiku) matters at
  scale.
- **Estimated cost.** The `$` figures use indicative list prices and ignore promos (e.g. Sonnet's
  intro pricing); the Anthropic console is the source of truth for the actual bill.
