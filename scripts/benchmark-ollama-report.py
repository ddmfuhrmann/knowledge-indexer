#!/usr/bin/env python3
"""Analyze a benchmark-ollama run: keep-rate, quality and wall-time per model.

Usage: scripts/benchmark-ollama-report.py [/tmp/ki-ollama-matrix]

Local models are free, so cost is dropped; the signals are keep-rate (did the model produce anchored
output at all), the same behaviors quality signals as the Anthropic report, and wall-time. Columns:
  beh    behaviors use cases kept /12 entry points (order-sample); '← event MISSING' if the listener flow is absent
  st,dm  stateTransitions / domains items kept
  whHTTP scenarios whose `when` leaks the HTTP verb/path (should be 0)
  life   scenarios typed `lifecycle` (state changes should be)
  ft     distinct feature headers (grouping, not fragmentation)
  invent ungrounded actor nouns in the (non-evidence-gated) prose — lower is better
  time   total enrichment wall-time (s); FAIL marks a task that errored/timed out
"""
import json, re, glob, os, sys

ROOT = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ki-ollama-matrix"
EVENT = "event:com.example.orders.service.ShipmentService#onOrderPaid(OrderPaidEvent)"
INVENT = ["customer", "operator", "carrier", "warehouse", "user", "client",
          "staff", "admin", "agent", "buyer", "stock", "inventory"]


def logstats(logf):
    try:
        t = open(logf).read()
    except OSError:
        return (None, False)
    m = re.search(r"enrichment done:.*?(\d+)ms\)", t)
    secs = round(int(m.group(1)) / 1000) if m else None
    failed = ("FAILED" in t) or ("truncated" in t)
    return (secs, failed)


def counts(manf):
    enr = json.load(open(manf))["enrichment"]
    return {k: len(enr.get(k, {}).get("data", []) or []) for k in ("behaviors", "stateTransitions", "domains")}


def quality(manf):
    d = json.load(open(manf))["enrichment"]["behaviors"]["data"]
    if not d:
        return None
    eps = {it.get("evidence", {}).get("entryPoint") for it in d}
    txt = " ".join(it.get("given", "") + " " + it.get("when", "") + " " + it.get("then", "")
                   for it in d).lower()
    return dict(
        cov=EVENT in eps and len(eps) >= 12,
        wh=sum(1 for it in d if re.search(r"\b(GET|POST|PUT|DELETE)\b|/\{?\w", it.get("when", ""))),
        life=sum(1 for it in d if it.get("type") == "lifecycle"),
        feats=len({it.get("feature") for it in d}),
        inv=sum(len(re.findall(r"\b" + w + r"\b", txt)) for w in INVENT),
    )


rows = []
for mf in sorted(glob.glob(f"{ROOT}/*.model")):
    safe = os.path.basename(mf)[:-len(".model")]
    model = open(mf).read().strip()
    out = f"{ROOT}/{safe}"
    manf = f"{out}/manifest.json"
    secs, failed = logstats(f"{out}.log")
    if not os.path.isfile(manf):
        rows.append((model, None, None, None, secs, True))
        continue
    rows.append((model, counts(manf), quality(manf), None, secs, failed))

rows.sort(key=lambda r: (-(r[1]["behaviors"] if r[1] else -1), r[0]))
print(f"{'model':26}{'beh':>6}{'st':>4}{'dm':>4}{'whHTTP':>7}{'life':>5}{'ft':>3}{'invent':>7}{'time':>6}")
for model, c, q, _, secs, failed in rows:
    if not c:
        print(f"{model:26}  (no manifest — cell failed)")
        continue
    tail = ""
    if q and not q["cov"]:
        tail += "  ← event MISSING"
    if failed:
        tail += "  ⚠ task FAILED/truncated"
    beh = f"{c['behaviors']}/12"
    wh = q["wh"] if q else "-"
    life = q["life"] if q else "-"
    ft = q["feats"] if q else "-"
    inv = q["inv"] if q else "-"
    print(f"{model:26}{beh:>6}{c['stateTransitions']:>4}{c['domains']:>4}"
          f"{wh:>7}{life:>5}{ft:>3}{inv:>7}{(str(secs) + 's') if secs is not None else '-':>6}{tail}")
