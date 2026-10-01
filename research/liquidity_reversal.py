"""Liquidity 15+5 (BANKNIFTY, 15-min + 5-min books, 15% premium stop): ways to get out sooner when the direction
turns, on two years of real option prices.

    python research/liquidity_reversal.py <year A file> <year B file> [out.md]
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars, row, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

VARIANTS = [
    ("the arm today", dict()),
    ("failed break read on every 5-min close", dict(fb_every=5)),
    ("failed break read on every 1-min close", dict(fb_every=1)),
    ("index stop 30 pts back through the level", dict(ix_buffer=30)),
    ("index stop 60 pts back through the level", dict(ix_buffer=60)),
    ("premium stop 10%", dict(prem_stop=0.10)),
    ("time stop: not +5% after 20 min -> out", dict(time_stop=(20, 0.05))),
    ("time stop: not +0% after 30 min -> out", dict(time_stop=(30, 0.0))),
    ("5-min failed break + 30-pt index stop", dict(fb_every=5, ix_buffer=30)),
    ("30-pt index stop + time stop +5% / 20 min", dict(ix_buffer=30, time_stop=(20, 0.05))),
    ("30-pt index stop + time stop 0% / 30 min", dict(ix_buffer=30, time_stop=(30, 0.0))),
]


def main():
    L = ["## Liquidity 15+5: getting out sooner when the direction turns (research/liquidity_reversal.py)", "",
         "BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. Each variant adds one exit to the arm.", ""]
    for path in sys.argv[1:3]:
        days = load(path)
        alld = [d["day"] for d in days]
        L += [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
              "| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg loss | worst trade |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
        res = {}
        for tf in (15, 5):
            b = bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            for name, kw in VARIANTS:
                kw = dict(kw)
                ps = kw.pop("prem_stop", 0.15)
                res[(tf, name)] = simulate(days, b, zones, "both", True, prem_stop=ps, **kw)
        for name, _ in VARIANTS:
            tr = pd.concat([res[(15, name)], res[(5, name)]])
            loss = tr.rs[tr.rs < 0]
            L.append(row(name, tr, alld) + f" Rs {loss.mean():,.0f} | Rs {tr.rs.min():,.0f} |")
            print(L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
