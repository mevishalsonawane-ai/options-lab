"""The reopen drift (research/gold_scalp.py part 1): XAUUSD's average 5-minute drift is several times larger, and up,
in the two hours after the daily reopen (22:00-24:00 UTC) than in any other hour. Here: buy at a fixed time after the
reopen, sell at a fixed time, every weekday night (Sunday's reopen included), costs included; versus holding the same
two hours at other times of day. Fitting years Oct 2023 - Sep 2025, held out Oct 2025 - Sep 2026.

    python research/gold_reopen.py <xauusd_m1_bid.csv.gz> [out.md]
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07


def main():
    bid = g.read(sys.argv[1])
    mid = bid + H
    o = mid.open
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.normalize().unique())}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    windows = [("22:05", "23:55"), ("22:05", "22:55"), ("23:00", "23:55"), ("22:05", "00:55"), ("22:05", "06:55"),
               ("01:05", "02:55"), ("08:05", "09:55"), ("13:35", "15:25"), ("18:05", "19:55")]
    L = ["## The reopen drift (research/gold_reopen.py)", "",
         "Buy at the first time, sell at the second, every trading night or day; ask = bid + 0.30, $7 a lot. USD per standard lot.",
         "", "| hold (UTC) | IST | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 6)]
    for a, b in windows:
        ah, am = map(int, a.split(":")); bh, bm = map(int, b.split(":"))
        ts = o.index
        ent = o[(ts.hour == ah) & (ts.minute == am)]
        rows = []
        for t, px in ent.items():
            end = t.normalize() + pd.Timedelta(hours=bh, minutes=bm)
            if end <= t:
                end += pd.Timedelta(days=1)
            if t.dayofweek == 4 and ah >= 21:          # no reopen on Friday night
                continue
            ex = o.get(end)
            if ex is None or np.isnan(ex):
                continue
            rows.append((t, 100 * ((ex - H) - (px + H) - COMM)))
        tr = pd.DataFrame(rows, columns=["at", "usd"])
        tr["year"] = [g.year_of(t) for t in tr["at"]]
        x = tr.usd
        t_ = x.mean() / (x.std(ddof=1) / len(x) ** 0.5)
        eq = x.cumsum()
        per = {y: tr[tr.year == y].usd.sum() for y in years}
        ist = lambda s: f"{(int(s[:2]) * 60 + int(s[3:]) + 330) % 1440 // 60:02d}:{(int(s[:2]) * 60 + int(s[3:]) + 330) % 60:02d}"
        L.append(f"| {a}-{b} | {ist(a)}-{ist(b)} | " + " | ".join(f"{per[y]:+,.0f}" for y in years) +
                 f" | {x.sum():+,.0f} | {len(x)} | {100 * (x > 0).mean():.0f}% | {t_:.2f} | {(eq - eq.cummax()).min():,.0f} | {x.sum() / 100 / 36:+,.2f} |")
        print(L[-1], flush=True)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write("\n".join(L))


if __name__ == "__main__":
    main()


def by_reopen(path):
    """The same, measured from each day's actual reopen (the first minute after a gap of 30+ minutes, Sunday's
    included): gold's break is 21:00-22:00 UTC in northern summer and 22:00-23:00 in winter, so fixed clock times mix
    the first and second hour after the open."""
    bid = g.read(path)
    mid = bid + H
    t = mid.index
    gap = np.r_[np.inf, np.diff(t.values).astype("timedelta64[m]").astype(float)]
    reopen = t[gap >= 30]
    o = mid.open
    years = sorted({g.year_of(d) for d in pd.Series(t.normalize().unique())}, key=lambda s: s[4:8])
    L = ["", "### Measured from each day's reopen", "",
         "| buy | sell | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 6)]
    for a, b in ((5, 60), (5, 120), (60, 120), (5, 30), (30, 90)):
        rows = []
        for r in reopen:
            e, x = r + pd.Timedelta(minutes=a), r + pd.Timedelta(minutes=b)
            pe, px = o.get(e), o.get(x)
            if pe is None or px is None:
                continue
            rows.append((r, 100 * ((px - H) - (pe + H) - COMM)))
        tr = pd.DataFrame(rows, columns=["at", "usd"])
        tr["year"] = [g.year_of(v) for v in tr["at"]]
        xs = tr.usd
        tt = xs.mean() / (xs.std(ddof=1) / len(xs) ** 0.5)
        eq = xs.cumsum()
        per = {y: tr[tr.year == y].usd.sum() for y in years}
        L.append(f"| reopen +{a} min | reopen +{b} min | " + " | ".join(f"{per[y]:+,.0f}" for y in years) +
                 f" | {xs.sum():+,.0f} | {len(xs)} | {100 * (xs > 0).mean():.0f}% | {tt:.2f} | {(eq - eq.cummax()).min():,.0f} | {xs.sum() / 100 / 36:+,.2f} |")
        print(L[-1], flush=True)
    return L


if __name__ == "__main__" and len(sys.argv) > 3:
    pass
