"""R82 runner (PREREG.md sections 2, 5, 6).

    python3 -m research.hunt.r82.study design      # design + walk-forward + design twins -> results/design.json
    python3 -m research.hunt.r82.study final       # ONCE: final window + final twins + gates -> results/final.json

The final read writes results/FINAL_READ first and refuses to run if it exists.
"""
from __future__ import annotations

import json
import sys
import time
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r82 import daily as D
from research.hunt.r82 import intraday as I
from research.hunt.r82 import r82lib as L

DAILY_DESIGN = ("2005-01-01", "2022-12-31")
DAILY_WF = [(f"{y}-01-01", f"{y + 1}-12-31") for y in range(2009, 2023, 2)]
DAILY_FINAL = ("2023-01-01", "2026-10-16")
INTRA_DESIGN = ("2024-10-21", "2026-03-31")
INTRA_WF = [("2025-04-01", "2025-06-30"), ("2025-07-01", "2025-09-30"), ("2025-10-01", "2025-12-31"),
            ("2026-01-01", "2026-03-31")]
INTRA_FINAL = ("2026-04-01", "2026-10-16")

DAILY_TESTS = ["T1", "T2", "T3", "T4", "D0", "D1", "D2", "G1", "U1"]
INTRA_TESTS = ["L1", "L2", "L3", "E1", "O1", "U2", "U3"]
ALL_TESTS = DAILY_TESTS + INTRA_TESTS
BASE = {**{t: "T0" for t in DAILY_TESTS}, "L2": "L1", "L3": "L1", "E1": "L1", "U3": "U2", "L1": None, "O1": None, "U2": None}
SIGNAL = {
    "T0": "the metal's own rupee proxy price (MCX future proxy), daily", "T1": "own proxy price, daily",
    "T2": "own proxy price, daily", "T3": "own and the other metal's proxy price, daily",
    "T4": "own proxy price, daily", "D0": "own proxy price, daily", "D1": "own proxy price, daily",
    "D2": "own and the other metal's proxy price, daily", "G1": "gold/silver price ratio + own proxy price, daily",
    "U1": "own proxy price + DXY (Yahoo DX-Y.NYB), daily", "L1": "COMEX GC=F / SI=F 1h (MCX future proxy)",
    "L2": "COMEX 1h, own and the other metal", "L3": "COMEX 1h", "E1": "COMEX 1h + US data calendar",
    "O1": "COMEX gold and silver overnight move (MCX future proxy)", "U2": "COMEX gold and silver first US hour",
    "U3": "COMEX gold and silver first US hour on CPI/NFP/FOMC days",
}


def load():
    dxy = L.daily_close("DX-Y.NYB")
    mk = D.Market.build({m: L.load_daily(m) for m in D.METALS}, dxy)
    hs = I.build_all({m: L.load_hourly(m) for m in I.METALS})
    return mk, hs


def trades_of(t, mk, hs, lo, hi):
    if t in D.RULES:
        return D.run(t, mk, lo, hi)
    return I.TESTS[t](hs, lo, hi)


def twins_of(t, tr, mk, hs, lo, hi):
    if t in D.RULES:
        return D.twin_books(t, tr, mk, lo, hi)
    return I.twin_books(tr, hs, lo, hi)


def windows(t):
    return (DAILY_DESIGN, DAILY_WF, DAILY_FINAL) if t in D.RULES else (INTRA_DESIGN, INTRA_WF, INTRA_FINAL)


def trade_rows(tr):
    return [dict(metal=x.metal, side=x.side, entry_t=str(x.entry_t), exit_t=str(x.exit_t), entry=round(x.entry, 2),
                 exit=round(x.exit, 2), why=x.why, net=round(x.net, 0)) for x in tr]


def design():
    mk, hs = load()
    out = {"data": {m: dict(daily=[str(mk.d[m].index.min().date()), str(mk.d[m].index.max().date()), len(mk.d[m])],
                            hourly=[str(hs[m].df.index.min()), str(hs[m].df.index.max()), len(hs[m].df)])
                    for m in D.METALS}, "tests": {}}
    for t in ["T0"] + ALL_TESTS:
        t0 = time.time()
        dz, wf, _ = windows(t)
        tr = trades_of(t, mk, hs, *dz)
        blocks = [L.summary(trades_of(t, mk, hs, a, b)) for a, b in wf]
        row = dict(signal=SIGNAL[t], design=L.summary(tr), wf_blocks=blocks,
                   wf_net=float(sum(b["net"] for b in blocks)), wf_green=sum(b["net"] > 0 for b in blocks))
        if t != "T0":
            books = twins_of(t, tr, mk, hs, *dz)
            row["twin_p"] = L.twin_p(row["design"]["net"], books)
            row["twin_mean"] = round(float(books.mean()), 0)
        row["examples"] = trade_rows(tr[:3])
        out["tests"][t] = row
        print(t, row["design"]["trades"], row["design"]["net"], "wf", row["wf_net"], "p", row.get("twin_p"),
              f"{time.time() - t0:.0f}s", flush=True)
    q = L.bh({t: out["tests"][t]["twin_p"] for t in ALL_TESTS})
    for t in ALL_TESTS:
        out["tests"][t]["bh_q"] = q[t]
    L.RESULTS.mkdir(exist_ok=True)
    (L.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))


def final():
    lock = L.RESULTS / "FINAL_READ"
    if lock.exists():
        raise SystemExit("refused: the R82 final window has already been read (" + lock.read_text().strip() + ")")
    des = json.loads((L.RESULTS / "design.json").read_text())
    lock.write_text(f"read once at {pd.Timestamp.now(tz='UTC')}\n")
    mk, hs = load()
    res = {}
    for t in ["T0"] + ALL_TESTS:
        _, _, fz = windows(t)
        tr = trades_of(t, mk, hs, *fz)
        row = dict(final=L.summary(tr), top5_removed=round(L.top5_removed(tr), 0), examples=trade_rows(
            sorted(tr, key=lambda x: -abs(x.net))[:3]))
        if t != "T0":
            books = twins_of(t, tr, mk, hs, *fz)
            row["twin_p"] = L.twin_p(row["final"]["net"], books)
            row["twin_mean"] = round(float(books.mean()), 0)
        res[t] = row
        print(t, row["final"]["trades"], row["final"]["net"], "p", row.get("twin_p"), flush=True)
    gates = {}
    for t in ALL_TESTS:
        d, f = des["tests"][t], res[t]
        b = BASE[t]
        beats_wf = d["wf_net"] > (des["tests"][b]["wf_net"] if b else 0)
        beats_final = f["final"]["net"] > (res[b]["final"]["net"] if b else 0)
        g = dict(
            positive=d["design"]["net"] > 0 and d["wf_net"] > 0 and f["final"]["net"] > 0,
            beats_baseline=beats_wf and beats_final,
            design_twin=d["bh_q"] < 0.10 and d["twin_p"] < 0.10,
            final_twin=f["twin_p"] < 0.10,
            not_top5=f["top5_removed"] > 0,
            enough=d["design"]["trades"] >= 30,
        )
        g["pass"] = all(g.values())
        gates[t] = g
    (L.RESULTS / "final.json").write_text(json.dumps(dict(tests=res, gates=gates), indent=1, default=str))
    print({t: g["pass"] for t, g in gates.items()})


if __name__ == "__main__":
    {"design": design, "final": final}[sys.argv[1]]()
