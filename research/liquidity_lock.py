"""Liquidity 15+5 (BANKNIFTY, 15-min + 5-min books, 15% premium stop) with the profit-lock ladder the other arms got
(25% of the way -> price paid, 50% -> +25%, 75% -> +50%). The arm has no fixed target (it sells at the next
liquidity), so the ladder is measured against a reference target of X% of the premium paid.

    python research/liquidity_lock.py <year A file> <year B file> [out.md]
"""
import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars, row, simulate  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

REFS = [None, 0.15, 0.30, 0.45, 0.60, 1.00]


def main():
    L = ["## Liquidity 15+5 with the profit-lock ladder (research/liquidity_lock.py)", "",
         "BANKNIFTY, 15-min + 5-min books, 15% premium stop (the arm as it is), real option prices, 1 lot of 30, after costs.",
         "Reference target = X% of the premium paid; rungs at 25 / 50 / 75 % of it lock price paid / +25% / +50% of it.", ""]
    for path in sys.argv[1:3]:
        days = load(path)
        alld = [d["day"] for d in days]
        L += [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
              "| ladder | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |",
              "|---|---|---|---|---|---|---|---|---|"]
        books = {}
        for tf in (15, 5):
            b = bars(days, tf)
            zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
            for ref in REFS:
                books[(tf, ref)] = simulate(days, b, zones, "both", True, prem_stop=0.15, lock_ref=ref)
        for ref in REFS:
            tr = pd.concat([books[(15, ref)], books[(5, ref)]])
            label = "none (the arm today)" if ref is None else f"target = {int(ref * 100)}% of premium"
            L.append(row(label, tr, alld) + (f" locks: {100 * (tr.why == 'profit lock').mean():.0f}%" if ref else ""))
            print(L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
