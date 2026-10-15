#!/usr/bin/env python3
"""R52 study driver (PREREG.md).

    OBUY_DATA=<lake laid out by lake2obuy.py> OBUY_CACHE=<cache> python3 research/hunt/r52/r52study.py design [KEY ...]
    ... r52study.py final            # ONCE: writes results/FINAL_READ, reads the final test for every chosen setting
    ... r52study.py report           # results/*.json -> results/report.md

design: every rule in r52rules.RULES, its ORIGINAL setting and its re-tune grid (<= 24 settings), on design sessions;
        walk-forward over the four folds; the re-tune pick = the best design net among settings with walk-forward > 0.
        Results merge into results/design.json (so rules can be run in batches).
final:  the original + the pick of every rule in design.json, once, on 1 Apr .. 13 Oct 2026; BH over all these reads.
"""
from __future__ import annotations

import json
import sys
import time

import numpy as np

import r52cfg as K
import r52lab as L
import r52rules as RR

DESIGN = K.RESULTS / "design.json"
FINAL = K.RESULTS / "final.json"


def _load(p):
    return json.loads(p.read_text()) if p.exists() else {}


def _dump(p, d):
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(d, indent=1, default=str, sort_keys=True))


def design(keys=None):
    L.env_check()
    out = _load(DESIGN)
    for key, spec in RR.RULES.items():
        if keys and key not in keys:
            continue
        t0 = time.time()
        variants = RR.variants(key)
        res = L.run(variants, "design")
        sc = {r.vid: L.score(res[r.id]) for r in variants}
        trades = {r.vid: res[r.id]["trades"] for r in variants}
        wf_orig = L.walk_forward({"orig": trades["orig"]})
        wf_all = L.walk_forward(trades) if len(trades) > 1 else wf_orig
        ok = [v for v in sc if v != "orig" and L.walk_forward({v: trades[v]})["net"] > 0]
        pick = max(ok, key=lambda v: sc[v]["net"]) if ok else None
        out[key] = dict(spec=RR.describe(key), scores=sc, wf_orig=wf_orig, wf_retune=wf_all,
                        wf_each={v: L.walk_forward({v: trades[v]})["net"] for v in sc}, pick=pick,
                        n_variants=len(variants), secs=round(time.time() - t0))
        print(f"{key}: orig {sc['orig']['trades']} tr net {sc['orig']['net']:+,.0f} p {sc['orig']['p']:.3f} "
              f"wf {wf_orig['net']:+,.0f} | pick {pick} | {len(variants)} settings, {time.time() - t0:.0f}s", flush=True)
        _dump(DESIGN, out)
    # design q over every design setting run so far (information only)
    ids = [(k, v) for k in out for v in out[k]["scores"]]
    qs = L.bh([out[k]["scores"][v]["p"] for k, v in ids])
    for (k, v), q in zip(ids, qs):
        out[k]["scores"][v]["q_design"] = q
    _dump(DESIGN, out)


def prereg(keys=None):
    """Write results/rules_prereg.json: every rule's original setting and grid, committed BEFORE its design run."""
    p = K.RESULTS / "rules_prereg.json"
    out = _load(p)
    for key in RR.RULES:
        if keys and key not in keys:
            continue
        vs = RR.variants(key)
        out[key] = dict(orig=RR.describe(key), grid=[dict(vid=r.vid, params=r.params, rule=r.rule.label(),
                                                          exits=r.exits.label()) for r in vs[1:]])
    _dump(p, out)
    print(f"{len(out)} rules pre-registered")


def final():
    if K.FINAL_FLAG.exists():
        raise SystemExit("refused: the final test was already read once (results/FINAL_READ)")
    L.env_check()
    d = _load(DESIGN)
    todo = []
    for key in d:
        if key not in RR.RULES:
            continue
        todo.append((key, "orig"))
        if d[key]["pick"]:
            todo.append((key, d[key]["pick"]))
    K.FINAL_FLAG.parent.mkdir(parents=True, exist_ok=True)
    K.FINAL_FLAG.write_text(f"final test read {time.strftime('%Y-%m-%d %H:%M:%S')} for {len(todo)} settings\n")
    out = {}
    for key in sorted({k for k, _ in todo}):
        vids = [v for k, v in todo if k == key]
        variants = [r for r in RR.variants(key) if r.vid in vids]
        res = L.run(variants, "final")
        for r in variants:
            out[f"{key}:{r.vid}"] = L.score(res[r.id])
            s = out[f"{key}:{r.vid}"]
            print(f"FINAL {key}:{r.vid} {s['trades']} tr net {s['net']:+,.0f} p {s['p']:.3f}", flush=True)
    ids = list(out)
    qs = L.bh([out[i]["p"] for i in ids])
    for i, q in zip(ids, qs):
        key, vid = i.split(":")
        wf = d[key]["wf_orig"]["net"] if vid == "orig" else d[key]["wf_each"][vid]
        out[i]["q"] = q
        out[i]["wf_design"] = wf
        out[i]["gates"] = L.gates(out[i], wf, q)
    _dump(FINAL, out)


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        design(sys.argv[2:] or None)
    elif cmd == "final":
        final()
    elif cmd == "prereg":
        prereg(sys.argv[2:] or None)
    elif cmd == "report":
        import r52report
        r52report.main()
    else:
        print(__doc__)
