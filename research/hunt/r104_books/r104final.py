"""R104 final verdicts: design gates (results/gates_design.json) + the twin gate for design passers
(results/twins.json) + the one holdout read (results/holdout_entries.json, results/holdout_arms.json).

    python research/hunt/r104_books/r104final.py      # results/final.json, results/final.md
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import r104cfg as K  # noqa: E402


def _j(name, default=None):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else default


def finalize():
    rows = _j("gates_design.json", [])
    twins = _j("twins.json", {})
    he = _j("holdout_entries.json", {})
    ha = _j("holdout_arms.json", {})
    out = []
    for x in rows:
        key = x["test"]
        mk = x["market"]
        h = None
        hold_ok = False
        if mk == "ARM":
            hv = ha.get(key, {}).get("variants", {}).get(x.get("variant"))
            if hv:
                h = dict(n=hv["var_n"], net=hv["var_net"], win=hv["var_win"], b0_net=hv["b0_net"], b0_win=hv["b0_win"],
                         delta=hv["delta"], changed=hv.get("changed", 0) + hv.get("removed", 0))
                hold_ok = h["delta"] > 0
        elif mk == "MM":
            r = ha.get(key)
            if r:
                v = r["variants"].get(x.get("variant"), {})
                b = r["base"]
                dd_cut = 1 - v["maxdd"] / b["maxdd"] if b["maxdd"] < 0 else 0.0
                net_cut = 1 - v["net"] / b["net"] if b["net"] > 0 else 0.0
                h = dict(net=v["net"], b0_net=b["net"], maxdd=v["maxdd"], b0_maxdd=b["maxdd"], dd_cut=dd_cut,
                         net_cut=net_cut, worst_month=v["worst_month"], b0_worst_month=b["worst_month"])
                hold_ok = dd_cut >= 0.20 and net_cut <= 0.10 and v["worst_month"] >= b["worst_month"]
        else:
            r = he.get(key)
            if r:
                mn = K.MIN_TRADES[mk][1]
                h = dict(n=r["n"], net=r["net"], win=r["win"])
                hold_ok = r["net"] > 0 and r["n"] >= mn
        tw = twins.get(key)
        twin_ok = True
        if x.get("design_pass") and mk not in ("ARM", "MM"):
            twin_ok = tw is not None and tw["p"] < K.GATE_TWIN_P
        final = bool(x.get("design_pass")) and twin_ok and hold_ok
        out.append(dict(x, holdout=h, twin=tw, holdout_ok=hold_ok, final_pass=final))
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    return out


if __name__ == "__main__":
    rows = finalize()
    print(len(rows), "tests;", sum(r["final_pass"] for r in rows), "pass everything;",
          sum(bool(r.get("design_pass")) for r in rows), "passed design")
    for r in rows:
        if r.get("design_pass"):
            print(r["test"], "twin", r.get("twin"), "holdout", r.get("holdout"), "FINAL", r["final_pass"])
