"""h43 part A diagnostics (why we don't match): indicators reset each session; ORB30 with a 40-min time stop.

    python3 -I research/hunt/h43/partA_diag.py
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import StrikeRule, positions, prepare_many  # noqa: E402
import sigs  # noqa: E402
from partA import EXE_APP, EXE_THEIRS, EXITS, HS, OUT, W0, W1  # noqa: E402

CASES = [("ema", 5, "T40", True), ("ema", 15, "T40", True), ("bb", 1, "T15", True), ("pe", 5, "T30", True),
         ("orb30", 5, "T40", False), ("orb30", 15, "T40", False)]


def main():
    ix = market().index("BANKNIFTY")
    ss = []
    for s, tf, ek, reset in CASES:
        x = sigs.make(ix, s, tf, reset=reset)
        ss.append(x[(x.day >= W0) & (x.day <= W1)].reset_index(drop=True))
    packs = prepare_many([(x, StrikeRule(0), EXE_THEIRS, 0, None, False) for x in ss]
                         + [(x, StrikeRule(0), EXE_APP, 0, None, False) for x in ss])
    rows = []
    for i, (s, tf, ek, reset) in enumerate(CASES):
        for cost, pk, exe in (("theirs", packs[i][0], EXE_THEIRS), ("ours", packs[len(CASES) + i][0], EXE_APP)):
            tr = positions(pk.run(EXITS[ek], exe), max_per_day=sigs.STRATS[s][0])
            if cost == "ours":
                tr["net"] = tr.net - HS * (tr.entry + tr.exit) * tr.qty
            rows.append(dict(strat=s, tf=tf, exit=ek, reset_daily=reset, cost=cost, trades=len(tr),
                             gross=round(tr.gross.sum()), net=round(tr.net.sum()), win=round((tr.net > 0).mean(), 2),
                             why=tr.why.value_counts().to_dict()))
    r = pd.DataFrame(rows)
    print(r.to_string())
    r.to_csv(os.path.join(OUT, "partA_diag.csv"), index=False)


if __name__ == "__main__":
    main()
