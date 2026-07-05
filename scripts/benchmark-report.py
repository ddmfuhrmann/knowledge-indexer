#!/usr/bin/env python3
"""Analyze a benchmark-matrix run: cost vs quality per cell.

Usage: scripts/benchmark-report.py [/tmp/ki-matrix]

Reads each cell's manifest.json (quality) and .log (cost), prints a table. Quality signals for the
`behaviors` task — the interpretive, human-facing one:
  cov     coverage: does the run keep a scenario for every entry point, incl. the event listener?
  whHTTP  scenarios whose `when` leaks the HTTP verb/path (should be 0 — an anti-pattern)
  life    scenarios typed `lifecycle` (state transitions should be, not `happy-path`)
  ft      distinct feature (business-capability) headers — grouping, not fragmentation
  invent  ungrounded actor/recipient words in the prose (customer/operator/carrier/…). The prose
          fields are NOT evidence-gated, so this is the key fabrication risk — lower is better.
"""
import json, re, glob, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ki-matrix"
EVENT = "event:com.example.orders.service.ShipmentService#onOrderPaid(OrderPaidEvent)"
# Common actor/recipient nouns a model may invent when the material doesn't model them.
INVENT = ["customer", "operator", "carrier", "warehouse", "user", "client",
          "staff", "admin", "agent", "buyer", "stock", "inventory"]
ORDER = {"off": 0, "low": 1, "medium": 2, "high": 3}


def cost(logf):
    try:
        t = open(logf).read()
    except OSError:
        return None
    m = re.search(r"estimated spend:.*?≈ \$([0-9.]+)", t)
    # first per-call usage line == behaviors (task 1/3)
    b = re.search(r"sdk:\S+ usage: in \d+, out \d+.*?≈ \$([0-9.]+)", t)
    return (float(m.group(1)) if m else None, float(b.group(1)) if b else None)


def quality(manf):
    d = json.load(open(manf))["enrichment"]["behaviors"]["data"]
    if not d:
        return None
    eps = {it["evidence"]["entryPoint"] for it in d}
    txt = " ".join(it.get("given", "") + " " + it.get("when", "") + " " + it.get("then", "")
                   for it in d).lower()
    return dict(
        n=len(d),
        cov=EVENT in eps and len(eps) >= 12,
        wh=sum(1 for it in d if re.search(r"\b(GET|POST|PUT|DELETE)\b|/\{?\w", it.get("when", ""))),
        life=sum(1 for it in d if it.get("type") == "lifecycle"),
        feats=len({it.get("feature") for it in d}),
        inv=sum(len(re.findall(r"\b" + w + r"\b", txt)) for w in INVENT),
    )


rows = []
for out in sorted(glob.glob(f"{ROOT}/*-*")):
    if out.endswith((".log", ".stdout")) or "-" not in os.path.basename(out):
        continue
    model, cond = os.path.basename(out).rsplit("-", 1)
    manf = f"{out}/manifest.json"
    if not os.path.isfile(manf):
        continue
    rows.append((model, cond, cost(f"{out}.log"), quality(manf)))

rows.sort(key=lambda r: (r[0], ORDER.get(r[1], 9)))
print(f"{'model':7}{'effort':7}{'$total':>8}{'$beh':>8}  {'cov':4}{'whHTTP':>7}{'life':>5}{'ft':>3}{'invent':>7}")
for model, cond, c, q in rows:
    if not q:
        print(f"{model:7}{cond:7}  (no behaviors data — cell failed)")
        continue
    tot, beh = (c or (None, None))
    print(f"{model:7}{cond:7}{('$%.4f' % tot) if tot else '-':>8}{('$%.4f' % beh) if beh else '-':>8}  "
          f"{('%d/12' % q['n']):4}{q['wh']:>7}{q['life']:>5}{q['feats']:>3}{q['inv']:>7}"
          f"{'   ← event MISSING' if not q['cov'] else ''}")
