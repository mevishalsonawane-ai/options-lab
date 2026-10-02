"""The "Trend Analysis Strategy" (research/gold_tas.py, tracker ATR 19) on BANKNIFTY and FINNIFTY index candles - the
owner's question, 2026-10-02: does it help there too?

    python research/tas_indices.py <out.md> BANKNIFTY:35:2:banknifty_prev_year_wide.parquet,banknifty_year.parquet \
        FINNIFTY:65:1:finnifty_index.parquet

UNDERLYING:LOT:COST:files (COST = index points lost a round trip). Index points per trade (an ATM option moves about half
as much, and loses time value; options for these years are not all available), both ways (a buy = a call, a sell =
a put), on 15-minute, 30-minute and 1-hour candles from 09:15; held overnight like the gold arm, and intraday only
(out at the day's last candle). Years split at 2025-02-15 like the other index studies.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gold_tas as gt  # noqa: E402

SPLIT = pd.Timestamp("2025-02-15")


def load(paths):
    parts = []
    for p in paths:
        df = pd.read_parquet(p)
        if "right" in df:
            df = df[df.right.astype(str) == "IX"]
        parts.append(df[["ts", "open", "high", "low", "close"]])
    m = pd.concat(parts).drop_duplicates("ts").set_index("ts").sort_index()
    return m[(m.index.time >= pd.Timestamp("09:15").time()) & (m.index.time < pd.Timestamp("15:30").time())]


def candles(m, tf):
    k = ((m.index.hour * 60 + m.index.minute - 555) // tf).values
    g = m.groupby([m.index.date, k])
    b = g.agg({"open": "first", "high": "max", "low": "min", "close": "last"})
    b.index = [pd.Timestamp(d) + pd.Timedelta(minutes=555 + int(x) * tf) for d, x in b.index]
    return b


def main():
    out = sys.argv[1]
    L = ["## The \"Trend Analysis Strategy\" on BANKNIFTY and FINNIFTY (research/tas_indices.py)", "",
         "Index points per trade after a round-trip cost; its own stop (tracker line), targets 1.5 / 2.5 / 3.5 R, breakeven "
         "after the first, out when the tracker turns. Tracker ATR 19. 1st year to 2025-02-15, 2nd after.", "",
         "| index | chart | holding | direction | trades | win | pts / trade | 1st year pts | 2nd year pts | total pts | t | deepest drawdown pts | Rs a lot (index) |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for spec in sys.argv[2:]:
        name, lot, cost, files = spec.split(":")
        lot, cost = int(lot), float(cost)
        m = load(files.split(","))
        gt.H, gt.COMM = cost / 2, 0.0
        for tf in (15, 30, 60):
            b = candles(m, tf)
            day = pd.Series(b.index.date, index=b.index)
            end = (day != day.shift(-1)).values
            trk, d, pct = gt.indicators(b, 19)
            for hold, de in (("overnight", None), ("intraday", end)):
                for dname, sides in (("buys only", (1,)), ("sells only", (-1,)), ("both ways", (1, -1))):
                    tr = sorted(sum((gt.trades(b, trk, d, pct, sd, True, de) for sd in sides), []))
                    if not tr:
                        continue
                    p = np.array([x for _, x in tr]) / 100   # gold_tas reports x100 (ounces a lot); back to points
                    ts = np.array([t for t, _ in tr])
                    y1 = p[ts < SPLIT].sum(); y2 = p[ts >= SPLIT].sum()
                    eq = np.cumsum(p); dd = (eq - np.maximum.accumulate(eq)).min()
                    t = p.mean() / (p.std(ddof=1) / len(p) ** 0.5) if len(p) > 1 else 0
                    L.append(f"| {name} | {tf if tf < 60 else '1h'}{'m' if tf < 60 else ''} | {hold} | {dname} | {len(p)} | {100 * (p > 0).mean():.0f}% | "
                             f"{p.mean():+.1f} | {y1:+,.0f} | {y2:+,.0f} | {p.sum():+,.0f} | {t:.2f} | {dd:,.0f} | {p.sum() * lot:+,.0f} |")
                    print(L[-1], flush=True)
    open(out, "w").write("\n".join(L))


if __name__ == "__main__":
    main()
