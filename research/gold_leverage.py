"""The gold strategies at 3:1 leverage on a $500 account (the owner's ask, 2026-10-01).

    python research/gold_leverage.py <xauusd_m1_bid.csv.gz> [out.md]

Each trade holds gold worth 3x the account balance at the time (compounding), so a trade's account return is
3 x its return on the price; the price used is that day's gold close (the trade lists keep USD per lot, not the
entry price, and gold moves well under 1% between a day's close and a trade's entry on most days). Costs are
already in each trade's USD (spread + $7 a lot). Also shown: a fixed $1,500 position (3x the starting $500, not
compounding). Strategies: IraGoldAlgo's 1-hour liquidity arm (pool confirmation 15, second-level target) and the
best version of each of the owner's two strategies (research/GOLD_SESSION_STRATS.md).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import gold_1h_plus as p  # noqa: E402
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

START, LEV = 500.0, 3.0


def run(tr, price):
    tr = tr.sort_values("day").reset_index(drop=True)
    px = np.array([price.get(pd.Timestamp(d), np.nan) for d in tr.day])
    r = (tr.usd.values / 100.0) / px                 # return on the price, after costs
    eq, peak, dd, curve = START, START, 0.0, []
    for x in r:
        eq = max(eq * (1 + LEV * x), 0.0)
        peak = max(peak, eq); dd = min(dd, eq / peak - 1)
        curve.append(eq)
    tr["eq"] = curve
    tr["mo"] = [str(d)[:7] for d in tr.day]
    m = tr.groupby("mo").eq.last()
    months = pd.period_range("2023-10", "2026-09", freq="M").astype(str)
    m = m.reindex(months).ffill().fillna(START)
    mret = m.pct_change().fillna(m.iloc[0] / START - 1)
    fixed = (tr.usd.values / 100.0 / px * LEV * START).sum()          # a fixed $1,500 position, no compounding
    fixed_dd = (lambda e: (e - np.maximum.accumulate(np.r_[START, e])[1:]).min())(START + np.cumsum(tr.usd.values / 100.0 / px * LEV * START))
    return dict(final=eq, ret=100 * (eq / START - 1), dd=100 * dd, avg_m=100 * mret.mean(), med_m=100 * mret.median(),
                worst_m=100 * mret.min(), best_m=100 * mret.max(), green=int((mret > 0).sum()), n=len(tr),
                fixed=fixed, fixed_dd=fixed_dd, y=m.iloc[[11, 23, 35]].values)


def main():
    bid = g.read(sys.argv[1])
    mid = bid + 0.15
    price = mid.close.resample("1D").last().dropna()
    price.index = price.index.normalize()
    days = g.sessions(bid, bid + g.SPREAD, p.START, p.END)
    b = g.bars(days, 60)
    liq = p.simulate(days, b, swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 15), second_target=True)
    midw = s.load(sys.argv[1]); M = s.Minutes(midw)
    o1b = s.option1(M, midw, 15, "2R", True); o1b = o1b[o1b.side > 0]
    o1x = s.option1(M, midw, 15, "opposite cross", True)
    o2b = s.option2(M, midw, 5, 1.0); o2b = o2b[o2b.side > 0]
    rows = [("Liquidity 1h (IraGoldAlgo now), buys only", liq),
            ("Option 1: EMA 9/21 + RSI M15, H1 bias, 2R, buys only", o1b),
            ("Option 1: EMA 9/21 + RSI M15, H1 bias, opposite cross, buys + sells", o1x),
            ("Option 2: Asian-range sweep M5, 100% target, buys only", o2b)]
    L = ["## Gold strategies at 3:1 leverage on $500 (research/gold_leverage.py)", "",
         "Each trade holds gold worth 3x the balance (compounding); costs included. Oct 2023 - Sep 2026.", "",
         "| strategy | trades | balance Sep 2024 | Sep 2025 | Sep 2026 | 3 years | deepest drawdown | avg month | median month | best month | worst month | green months | fixed $1,500 position: 3 years (drawdown) |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name, tr in rows:
        r = run(tr, price)
        L.append(f"| {name} | {r['n']} | ${r['y'][0]:,.0f} | ${r['y'][1]:,.0f} | ${r['y'][2]:,.0f} | {r['ret']:+.0f}% | {r['dd']:.0f}% | "
                 f"{r['avg_m']:+.1f}% | {r['med_m']:+.1f}% | {r['best_m']:+.1f}% | {r['worst_m']:+.1f}% | {r['green']}/36 | "
                 f"{r['fixed']:+,.0f} ({r['fixed_dd']:,.0f}) |")
        print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
