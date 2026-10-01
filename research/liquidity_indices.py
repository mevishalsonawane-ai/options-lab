"""The Liquidity 15+5 rule (both tools agree, swing lookback 20, pool confirmation 10, stop on a failed break; the
15-minute and 5-minute books side by side) on other indices, two years.

    python research/liquidity_indices.py <out.md> NIFTY:75:nifty_prev.parquet:nifty_year.parquet \
        FINNIFTY:65:finnifty_index.parquet SENSEX:20:sensex_index.parquet

UNDERLYING:LOT:files. A file with option chains (dump_year.py layout) is traded on real ATM option prices (0.5 slippage
a side, Rs 40 a round trip); an index-only file (dump_index.py) gives the index points per trade only, since expired
options for that index are not available. Years split at 2025-02-15 like the BANKNIFTY files.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_break as lb  # noqa: E402
from ml_long import grid  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

SPLIT = pd.Timestamp("2025-02-15").date()


def load_any(path):
    df = pd.read_parquet(path, columns=["right"])
    if (df.right.astype(str) != "IX").any():
        from sell_levels import load
        return load(path)
    df = pd.read_parquet(path)
    df["ts"] = pd.to_datetime(df.ts)
    days = []
    for day, g in df.groupby(df.ts.dt.date):
        g = g.sort_values("ts").set_index("ts")
        if len(g) < 300:
            continue
        days.append(dict(day=day, exp=None, I=grid(g, ["open", "high", "low", "close"]), chain={}))
    return days


def run(days, tf):
    b = lb.bars(days, tf)
    zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
    return lb.simulate(days, b, zones, "both", True)


def line(label, tr, ndays, lot, has_opt):
    if tr.empty:
        return f"| {label} | 0 | | | | | |"
    x = tr.pts
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    rs = (f"Rs {tr.rs.sum():+,.0f} ({100 * (tr.rs > 0).mean():.0f}% win)" if has_opt else "no option history")
    return (f"| {label} | {len(tr)} ({len(tr) / ndays:.1f}/day) | {100 * (x > 0).mean():.0f}% | {x.mean():+.1f} | {t:.2f} | "
            f"{x.sum() * lot:+,.0f} | {rs} |")


def main():
    out = sys.argv[1]
    L = ["## Liquidity 15+5 on other indices (research/liquidity_indices.py)", "",
         "Rule: both tools agree (pool broken where a swing zone sits), swing lookback 20, pool confirmation 10, stop on a "
         "failed break, sell at the next liquidity / new liquidity / 15:10; the 15-minute and 5-minute books side by side.",
         "Index points x lot = what the move was worth on the index (a futures-like view, before costs). Option P&L only "
         "where the option history exists.", "",
         "| index, year, chart | trades | index moved our way | index pts / trade | t (pts) | pts x lot (Rs, before costs) | ATM option P&L (1 lot, after costs) |",
         "|---|---|---|---|---|---|---|"]
    for spec in sys.argv[2:]:
        u, lot, *files = spec.split(":")
        lot = int(lot)
        lb.LOT = lot
        days = []
        for f in files:
            days += load_any(f)
        days.sort(key=lambda d: d["day"])
        has_opt = any(d["chain"] for d in days)
        for yname, ys in (("Feb 2024 - Feb 2025", [d for d in days if d["day"] < SPLIT]),
                          ("Feb 2025 - Feb 2026", [d for d in days if d["day"] >= SPLIT])):
            if len(ys) < 50:
                continue
            both = []
            for tf in (15, 5):
                tr = run(ys, tf)
                both.append(tr)
                L.append(line(f"{u}, {yname}, {tf}-min", tr, len(ys), lot, has_opt))
            L.append(line(f"**{u}, {yname}, 15+5 together**", pd.concat(both), len(ys), lot, has_opt))
            print("\n".join(L[-3:]), flush=True)
    open(out, "w").write("\n".join(L))


if __name__ == "__main__":
    main()
