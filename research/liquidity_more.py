"""More trades from the liquidity-break strategy: shorter swing lookback, faster pool confirmation, 3/5/15-minute
charts, and two charts traded side by side. BANKNIFTY, buying options, same rules and costs as liquidity_break.py.

    python research/liquidity_more.py <year.parquet> [out.md]
"""
from __future__ import annotations

import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars, row, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    L = [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
         "| chart, swing lookback, pool confirm, levels, stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |",
         "|---|---|---|---|---|---|---|---|---|"]
    books = {}
    for tf in (3, 5, 15):
        b = bars(days, tf)
        for length in (5, 10, 20):
            sw = swing_zones(b, length, "full")
            for confirm in (5, 10):
                zones = sw + pool_zones(b, 2, 5, confirm)
                for source in ("both", "either"):
                    for stop in (True, False):
                        tr = simulate(days, b, zones, source, stop)
                        label = f"{tf}-min, {length}, {confirm}, {source}, {'stop' if stop else 'no stop'}"
                        books[label] = tr
                        L.append(row(label, tr, alld))
                        print(L[-1], flush=True)
    L += ["", "Two charts side by side (separate books, each one trade at a time):", ""]
    for a, b_ in (("15-min, 20, 10, both, stop", "5-min, 20, 10, both, stop"),
                  ("15-min, 20, 10, both, stop", "5-min, 10, 10, both, stop"),
                  ("15-min, 10, 10, both, stop", "5-min, 10, 10, both, stop"),
                  ("15-min, 20, 10, both, stop", "3-min, 20, 10, both, stop")):
        tr = pd.concat([books[a], books[b_]])
        L.append(row(f"{a} + {b_}", tr, alld))
    text = "\n".join(L)
    print("\n".join(L[-6:]))
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
