"""Liquidity 15+5 (BANKNIFTY, 15-min + 5-min books, 15% premium stop) with the owner's trailing profit stop: once a
trade is in profit, a stop that keeps a share of the best profit so far (best +Rs 1,000 -> stop at +Rs 500 when the
share is 50%) and rises with it. On its own and with the 30-point index stop + 20-minute time stop.

    python research/liquidity_trail.py <year A file> <year B file> [out.md]
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars, row, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

TURN = dict(ix_buffer=30, time_stop=(20, 0.05))
VARIANTS = [
    ("the arm today", dict()),
    ("trail: keep 50% of the best, from +3%", dict(trail=(0.5, 0.03))),
    ("trail: keep 50% of the best, from +5%", dict(trail=(0.5, 0.05))),
    ("trail: keep 50% of the best, from +10%", dict(trail=(0.5, 0.10))),
    ("trail: keep 70% of the best, from +5%", dict(trail=(0.7, 0.05))),
    ("trail: keep 30% of the best, from +5%", dict(trail=(0.3, 0.05))),
    ("trail: keep 50% of the best, from +25%", dict(trail=(0.5, 0.25))),
    ("trail: keep 50% of the best, from +40%", dict(trail=(0.5, 0.40))),
    ("index stop + time stop (no trail)", dict(TURN)),
    ("index + time stop + trail 50% from +25%", dict(TURN, trail=(0.5, 0.25))),
    ("index + time stop + trail 50% from +5%", dict(TURN, trail=(0.5, 0.05))),
    ("index + time stop + trail 50% from +10%", dict(TURN, trail=(0.5, 0.10))),
]


def main():
    L = ["## Liquidity 15+5 with a trailing profit stop (research/liquidity_trail.py)", "",
         "BANKNIFTY, 15-min + 5-min books, real option prices, 1 lot of 30, after costs. 'From +5%' = the trail starts once "
         "the option has been 5% above the price paid (about Rs 1,000 on a 700 premium x 30).", ""]
    for path in sys.argv[1:3]:
        days = load(path)
        alld = [d["day"] for d in days]
        L += [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
              "| variant | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months | avg win | avg loss |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
        res = {}
        for tf in (15, 5):
            b = bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            for name, kw in VARIANTS:
                res[(tf, name)] = simulate(days, b, zones, "both", True, prem_stop=0.15, **kw)
        for name, _ in VARIANTS:
            tr = pd.concat([res[(15, name)], res[(5, name)]])
            L.append(row(name, tr, alld) + f" Rs {tr.rs[tr.rs > 0].mean():,.0f} | Rs {tr.rs[tr.rs < 0].mean():,.0f} |")
            print(L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
