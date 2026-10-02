"""TAS 1h (research/gold_tas.py) with a profit lock - the owner's ask, 2026-10-02.

    python research/gold_tas_lock.py <xauusd_m1_bid.csv.gz> [out.md]

The lock as on the other gold arms: once the best bid since the buy has been START x ATR(14, Wilder, 1-hour, at the
signal) above the entry, sell what is left if it falls GIVEBACK ATRs from that best bid (checked on each candle's low
before its high raises the best). Tried with the targets (1.5 / 2.5 / 3.5 R, breakeven after the first) and without
them (the lock, the tracker stop and the tracker turning down only). Fitting years Oct 2023 - Sep 2025, held out
Oct 2025 - Sep 2026. USD per standard lot after costs.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gold_tas as gt  # noqa: E402
import liquidity_gold as g  # noqa: E402


def main():
    df = pd.read_csv(sys.argv[1])
    df["ts"] = pd.to_datetime(df.timestamp, unit="ms")
    m = df.set_index("ts")[["open", "high", "low", "close"]].sort_index()
    m = m[~m.index.duplicated()]
    m += gt.H
    m = m[m.index.dayofweek < 5]
    years = sorted({g.year_of(dd) for dd in pd.Series(m.index.date).unique()}, key=lambda x: x[4:8])
    b = m.resample("60min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    b = b[b.index.hour != 21]
    trk, d, pct = gt.indicators(b, 19)
    h, l, c = b.high, b.low, b.close
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    atr = gt.rma(tr, 14).values
    L = ["## TAS 1h with a profit lock on XAUUSD (research/gold_tas_lock.py)", "",
         "Buys only, tracker ATR 19. USD per standard lot after costs; the last year is held out.", "",
         "| targets | lock (start / giveback ATR) | " + " | ".join(years) + " | 3 years | trades | win | t | deepest drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 5)]
    for tname, tps in (("1.5/2.5/3.5 R", gt.TPS), ("none", ())):
        for lock in (None, (1.0, 1.0), (1.0, 1.5), (1.0, 2.0), (1.0, 3.0), (1.0, 4.0), (2.0, 2.0), (2.0, 3.0), (0.5, 1.0)):
            t_ = gt.trades(b, trk, d, pct, 1, True, None, lock, atr, tps)
            p = np.array([x for _, x in t_]); y = np.array([g.year_of(t.date()) for t, _ in t_])
            per = {yy: p[y == yy].sum() for yy in years}
            eq = np.cumsum(p); dd = (eq - np.maximum.accumulate(eq)).min()
            t = p.mean() / (p.std(ddof=1) / len(p) ** 0.5)
            lk = "none (as added)" if lock is None else f"{lock[0]:g} / {lock[1]:g}"
            L.append(f"| {tname} | {lk} | " + " | ".join(f"{per[yy]:+,.0f}" for yy in years) + f" | {p.sum():+,.0f} | {len(p)} | "
                     f"{100 * (p > 0).mean():.0f}% | {t:.2f} | {dd:,.0f} | {p.sum() / 100 / 36:+,.1f} |")
            print(L[-1], flush=True)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write("\n".join(L))


if __name__ == "__main__":
    main()
