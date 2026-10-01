"""IraGoldAlgo's liquidity rule on other chart timeframes (the owner's ask, 2026-10-01, after the 1-hour upgrade).

    python research/gold_timeframes.py <xauusd_m1_bid.csv.gz> [out.md]

The same rule as research/gold_1h_plus.py (24x5, buys only, held overnight, out by Friday 20:40 UTC), on 15-minute,
30-minute, 1-hour, 2-hour and 4-hour candles; for each, the old settings (pool confirmation 10, the nearest level as
the target) and the new ones (confirmation 15, the second level up). Lookback and confirmation are counted in candles,
so they span more time on a longer chart. Judged as before: fitting years Oct 2023 - Sep 2025, held out Oct 2025 -
Sep 2026. USD per standard lot after the 0.30 spread and $7 a lot; the last column is the average month at 0.01 lot.
"""
from __future__ import annotations

import os
import sys

import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import gold_1h_plus as p  # noqa: E402
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402


def main():
    bid = g.read(sys.argv[1])
    days = g.sessions(bid, bid + g.SPREAD, p.START, p.END)
    years = sorted({g.year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    months = len(pd.period_range("2023-10", "2026-09", freq="M"))
    L = ["## IraGoldAlgo's liquidity rule on other timeframes (research/gold_timeframes.py)", "",
         f"XAUUSD, Dukascopy 1-minute bid {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}; ask = bid + 0.30, $7 a lot. "
         "USD per standard lot after costs; fitting years " + ", ".join(fit) + "; held out " + ", ".join(hold) + ".", "",
         "| chart | settings | " + " | ".join(years) + " | fitting | held out | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 8)]
    for tf, name in ((15, "15 min"), (30, "30 min"), (60, "1 hour"), (120, "2 hours"), (240, "4 hours")):
        b = g.bars(days, tf)
        for label, conf, second in (("old: confirm 10, nearest level", 10, False), ("new: confirm 15, second level", 15, True)):
            tr = p.simulate(days, b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, conf), second_target=second)
            tr["year"] = [g.year_of(d) for d in tr.day]
            s = p.summary(tr, fit, hold)
            per = {y: tr[tr.year == y].usd.sum() for y in years}
            L.append(f"| {name} | {label} | " + " | ".join(f"{per.get(y, 0):+,.0f}" for y in years) +
                     f" | {s['fit']:+,.0f} | {s['hold']:+,.0f} | {s['total']:+,.0f} | {s['n']} | {s['win']:.0f}% | {s['t']:.2f} | "
                     f"{s['dd']:,.0f} | {s['total'] / 100 / months:+,.2f} |")
            print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
