"""R94 orchestrator (PREREG 5-7): one Benjamini-Hochberg family over every design cell of the five ideas, the design
gates, then the ONE holdout read of every cell, the holdout gates and Boss's 2% risk check.

    python -m research.hunt.r94_mcx_advanced.study design     # needs results/design_idea{1..5}.json
    python -m research.hunt.r94_mcx_advanced.study holdout    # the one-time read (refuses a second)
    python -m research.hunt.r94_mcx_advanced.study tables     # markdown tables for the write-up
"""
from __future__ import annotations

import importlib
import json
import pickle
import sys

import numpy as np

from research.hunt.r94_mcx_advanced import common as C

IDEAS = {"idea1": "calspread", "idea2": "pairs", "idea3": "options", "idea4": "seasonal", "idea5": "usgap"}
TITLE = {"idea1": "Calendar spreads", "idea2": "Sector-index pairing (proxies)", "idea3": "Option selling",
         "idea4": "Bullion seasonals", "idea5": "US overnight -> MCX open"}


def min_trades(cell: str) -> int:
    """PREREG 7: >= 40, except idea-3 cycle cells and idea-4 cells (>= 10; they cannot reach 40)."""
    return 10 if (cell.startswith("s-") or "-CY-" in cell) else C.GATE_MIN_TRADES


def load_design() -> dict:
    out = {}
    for idea in IDEAS:
        p = C.RESULTS / f"design_{idea}.json"
        if not p.exists():
            raise FileNotFoundError(f"{p.name} missing: run that idea's design first")
        for cell, v in json.loads(p.read_text()).items():
            out[cell] = dict(v, idea=idea)
    return out


def design() -> dict:
    d = load_design()
    pv = {c: (v["p"] if v["summary"]["trades"] and v["p"] is not None and np.isfinite(v["p"]) else 1.0)
          for c, v in d.items()}
    q = C.bh(pv)
    passed = []
    for c, v in d.items():
        v["p_used"], v["q"] = pv[c], q[c]
        v["gate"] = C.design_gate(v["summary"], pv[c], q[c], v.get("folds") or [], min_trades(c))
        if v["gate"]["pass"]:
            passed.append(c)
    C.save("design_all.json", {"cells": d, "n_cells": len(d), "passed": passed,
                               "per_idea": {i: sum(1 for v in d.values() if v["idea"] == i) for i in IDEAS}})
    print(f"{len(d)} design cells, BH over all; passed: {passed or 'none'}")
    for i in IDEAS:
        cs = {c: v for c, v in d.items() if v["idea"] == i}
        best = min(cs, key=lambda c: cs[c]["q"])
        print(f"  {i} {len(cs):3d} cells, lowest q {cs[best]['q']:.3f} ({best}, p {cs[best]['p_used']:.3f}, "
              f"net {cs[best]['summary']['net']:.0f} on {cs[best]['summary']['trades']})")
    return d


def holdout() -> dict:
    """The ONE read. Locks every cell of every idea first; a second call raises before any number is computed."""
    d = json.loads((C.RESULTS / "design_all.json").read_text())["cells"]
    by_idea = {i: [c for c, v in d.items() if v["idea"] == i] for i in IDEAS}
    for i, cells in by_idea.items():                 # lock all, then compute
        C.open_holdout(i, cells)
    out, trades = {}, {}
    for i, mod in IDEAS.items():
        m = importlib.import_module(f"research.hunt.r94_mcx_advanced.{mod}")
        res = m.cells("holdout")
        for c, v in res.items():
            s = C.summary(v["trades"])
            p = v["p"] if v["trades"] else 1.0
            g = {"net": s["net"] > 0, "p": p is not None and np.isfinite(p) and p <= 0.10,
                 "month_stop": s["worst_month"] is not None and s["worst_month"] >= C.MONTH_STOP}
            g["pass"] = all(g.values())
            dg = d.get(c, {}).get("gate", {}).get("pass", False)
            tradable = (s["fit_2pct_1L"] or 0) >= 0.8
            out[c] = {"idea": i, "summary": s, "p": p, "gate": g, "design_pass": dg,
                      "both_pass": bool(dg and g["pass"]), "tradable_1L": tradable,
                      "extra": {k: x for k, x in v.get("extra", {}).items() if not isinstance(x, (list, dict))
                                or k in ("exits", "per_event")}}
            trades[c] = v["trades"]
    with open(C.cache_dir() / "holdout_trades.pkl", "wb") as f:
        pickle.dump(trades, f)
    C.save("holdout_all.json", out)
    both = [c for c, v in out.items() if v["both_pass"]]
    print(f"holdout read once for {len(out)} cells; design + holdout passers: {both or 'none'}")
    return out


def _f(x, nd=0):
    if x is None:
        return "-"
    if isinstance(x, float) and not np.isfinite(x):
        return "-"
    return f"{x:,.{nd}f}"


def _pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def tables() -> str:
    d = json.loads((C.RESULTS / "design_all.json").read_text())["cells"]
    hp = C.RESULTS / "holdout_all.json"
    h = json.loads(hp.read_text()) if hp.exists() else {}
    lines = []
    for i in IDEAS:
        lines += [f"### {TITLE[i]} ({i})", "",
                  "| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | "
                  "design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | "
                  "capital for median trade at 2% |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for c, v in sorted(d.items()):
            if v["idea"] != i:
                continue
            s = v["summary"]
            g = v["gate"]
            fails = "PASS" if g["pass"] else ", ".join(k for k, ok in g.items() if k != "pass" and not ok)
            hh = h.get(c, {})
            hs = hh.get("summary", {})
            lines.append(
                f"| {c} | {s['trades']} | {_pct(s['win_rate'])} | {_f(s['net'])} | "
                f"{_f(s['worst_trade'])} | {_f(s['worst_day'])} | {_f(s['worst_month'])} | {_f(s['max_dd'])} | "
                f"{_f(v['p_used'], 3)} | {_f(v['q'], 2)} | {fails} | {hs.get('trades', '-')} | {_f(hs.get('net'))} | "
                f"{_f(hs.get('worst_month'))} | {_f(hh.get('p'), 3)} | "
                f"{_pct(s['fit_2pct_1L'])} | {_f(s['cap_2pct_median'])} |")
        lines.append("")
    txt = "\n".join(lines)
    (C.RESULTS / "TABLES.md").write_text(txt)
    return txt


def main(argv):
    cmd = argv[:1]
    if cmd == ["design"]:
        design()
    elif cmd == ["holdout"]:
        holdout()
    elif cmd == ["tables"]:
        print(tables())
    else:
        print(__doc__)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
