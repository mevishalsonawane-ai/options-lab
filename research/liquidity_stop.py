"""Liquidity 15+5 with a stop on the option's premium (sell when it falls X% below the price paid), BANKNIFTY.

    python research/liquidity_stop.py <year.parquet> [out.md]

The arm's rule (both tools agree, swing lookback 20, pool confirmation 10, failed-break stop, 15-minute and 5-minute
books side by side) plus a premium stop checked on the option's minute lows; filled at the stop level (or the minute's
close if it gapped below), less 0.5 slippage. 1 lot of 30, Rs 40 a round trip.
"""
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
         "| premium stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |",
         "|---|---|---|---|---|---|---|---|---|"]
    books = {}
    for tf in (15, 5):
        b = bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        for ps in (None, 0.10, 0.15, 0.20, 0.25):
            books[(tf, ps)] = simulate(days, b, zones, "both", True, prem_stop=ps)
    for ps in (None, 0.10, 0.15, 0.20, 0.25):
        tr = pd.concat([books[(15, ps)], books[(5, ps)]])
        label = "none (as tested before)" if ps is None else f"{int(ps * 100)}% below the price paid"
        L.append(row(label, tr, alld))
        if ps:
            L[-1] += f" stops hit: {100 * (tr.why == 'premium stop').mean():.0f}%"
        print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
