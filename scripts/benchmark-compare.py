#!/usr/bin/env python3
"""Unified comparison across an Anthropic matrix and an Ollama matrix, on the same behaviors quality
signals, plus a 'techspeak' score for how technical (vs business) the prose reads.

Usage: scripts/benchmark-compare.py [ANTHROPIC_DIR] [OLLAMA_DIR]
       (defaults: /tmp/ki-matrix and /tmp/ki-ollama-matrix)

Only 'useful' cells (behaviors produced) are shown, ranked by coverage → least technical → least
invented. Anthropic cells show $ (behaviors call), Ollama cells show wall-time. See
docs/anthropic_benchmark.md and docs/ollama_benchmark.md for the metrics.
"""
import json, re, glob, os, sys

ANTH = sys.argv[1] if len(sys.argv) > 1 else "/tmp/ki-matrix"
OLL = sys.argv[2] if len(sys.argv) > 2 else "/tmp/ki-ollama-matrix"
EVENT = "event:com.example.orders.service.ShipmentService#onOrderPaid(OrderPaidEvent)"
INVENT = ["customer", "operator", "carrier", "warehouse", "user", "client",
          "staff", "admin", "agent", "buyer", "stock", "inventory"]
TECH = ["endpoint", "api", "http", "https", "url", "uri", "request", "response", "repository",
        "controller", "service class", "dto", "payload", "json", "crud", "persist", "query",
        "database", "rest ", "status code", "http status", "200", "201", "204", "400", "404", "409"]


def quality(manf):
    try:
        d = json.load(open(manf))["enrichment"]["behaviors"]["data"]
    except Exception:
        return None
    if not d:
        return None
    eps = {it.get("evidence", {}).get("entryPoint") for it in d}
    prose = " ".join(it.get("given", "") + " " + it.get("when", "") + " " + it.get("then", "")
                     for it in d).lower()
    return dict(
        n=len(d),
        cov=(EVENT in eps and len(eps) >= 12),
        life=sum(1 for it in d if it.get("type") == "lifecycle"),
        ft=len({it.get("feature") for it in d}),
        wh=sum(1 for it in d if re.search(r"\b(GET|POST|PUT|DELETE|PATCH)\b|/\{?\w", it.get("when", ""))),
        tech=sum(len(re.findall(r"\b" + re.escape(t) + r"\b", prose)) for t in TECH),
        inv=sum(len(re.findall(r"\b" + w + r"\b", prose)) for w in INVENT),
    )


def anth_cost(logf):
    try:
        m = re.search(r"sdk:\S+ usage:.*?≈ \$([0-9.]+)", open(logf).read())
        return ("$%.4f" % float(m.group(1))) if m else "-"
    except OSError:
        return "-"


def oll_time(logf):
    try:
        m = re.search(r"enrichment done:.*?(\d+)ms\)", open(logf).read())
        return (str(round(int(m.group(1)) / 1000)) + "s") if m else "-"
    except OSError:
        return "-"


rows = []
for out in sorted(glob.glob(f"{ANTH}/*-*")):
    if out.endswith((".log", ".stdout")):
        continue
    q = quality(f"{out}/manifest.json")
    if q:
        rows.append(("anthropic", os.path.basename(out), q, anth_cost(f"{out}.log")))
for mf in sorted(glob.glob(f"{OLL}/*.model")):
    safe = os.path.basename(mf)[:-6]
    model = open(mf).read().strip().split("|")[0]
    q = quality(f"{OLL}/{safe}/manifest.json")
    if q:
        rows.append(("cloud" if model.endswith("-cloud") else "local", model, q, oll_time(f"{OLL}/{safe}.log")))

rows.sort(key=lambda r: (-r[2]["n"], not r[2]["cov"], r[2]["tech"], r[2]["inv"]))
print(f"{'src':10}{'model / cfg':22}{'beh':>5}{'cov':>4}{'life':>5}{'ft':>3}{'whHTTP':>7}{'tech':>5}{'invent':>7}{'cost/time':>10}")
print("-" * 78)
for src, name, q, ct in rows:
    print(f"{src:10}{name:22}{('%d/12' % q['n']):>5}{('Y' if q['cov'] else chr(183)):>4}"
          f"{q['life']:>5}{q['ft']:>3}{q['wh']:>7}{q['tech']:>5}{q['inv']:>7}{(ct or '-'):>10}")
