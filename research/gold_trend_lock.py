"""Profit locks on the 4-hour Supertrend(10, 3) buy-only rule (the owner's ask, 2026-10-01: "what if we add profit
lock"). The trend rule itself is unchanged (research/gold_trend_tune.py's baseline); a lock adds an earlier exit.

    python research/gold_trend_lock.py <xauusd_m1_bid.csv.gz> [out.md]

A = the 4-hour ATR(14) of the last closed 4-hour bar at the entry (gold's own volatility, ~$25-60 an ounce here).
  breakeven X      once the high has gone X*A above the entry, the stop moves to the entry (+ costs)
  lock half X      once the open profit has reached X*A, keep at least half of the best open profit seen
  giveback K       once in profit by 1*A, leave when the price falls K*A from the highest high since the entry
Stops checked on each hour's low (bid), filled at the stop or the hour's open if it opened below. After a lock exit:
  "wait"   no new buy until the trend flips down and up again
  "rejoin" buy again at the next 4-hour close that is still up and above the lock's exit price
Chosen on the fitting years only (Oct 2023 - Sep 2025), then the held-out year read unchanged. Costs and swap as
research/gold_trend.py. USD per standard lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import gold_trend as gt  # noqa: E402
import gold_trend_tune as tu  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM, SWAP = gt.H, gt.COMM, gt.SWAP


def run(h1, up, newbar, A, lock, x, rejoin):
    o, hi, lo, c, idx = h1.open.values, h1.high.values, h1.low.values, h1.close.values, h1.index
    u, nb, a = up.values, newbar.values, A.values
    trades, pos, armed, last_exit = [], None, True, None
    for i in range(1, len(h1)):
        if pos is None:
            if not u[i]:
                armed, last_exit = True, None
                continue
            ok = armed or (rejoin and nb[i] and last_exit is not None and c[i - 1] - H > last_exit)
            if ok:
                e = o[i] + H
                pos = dict(i=i, e=e, A=a[i], peak=hi[i] - H, stop=-np.inf, on=False)
                armed = False
            else:
                continue
        p = pos
        if not u[i]:
            trades.append((idx[p["i"]], p["e"], idx[i], o[i] - H)); pos = None; armed = True; last_exit = None
            continue
        if lo[i] - H <= p["stop"]:
            px = min(p["stop"], o[i] - H)
            trades.append((idx[p["i"]], p["e"], idx[i], px)); pos = None; last_exit = px
            continue
        p["peak"] = max(p["peak"], hi[i] - H)
        gain = p["peak"] - p["e"]
        if lock == "breakeven" and gain >= x * p["A"]:
            p["stop"] = max(p["stop"], p["e"] + COMM)
        elif lock == "lock half" and gain >= x * p["A"]:
            p["stop"] = max(p["stop"], p["e"] + 0.5 * gain)
        elif lock == "giveback" and gain >= p["A"]:
            p["stop"] = max(p["stop"], p["peak"] - x * p["A"])
    if pos is not None:
        trades.append((idx[pos["i"]], pos["e"], idx[-1], c[-1] - H))
    return trades


def main():
    mid = s.load(sys.argv[1])
    h1, h4 = s.bars(mid, 60), s.bars(mid, 240)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.date).unique()}, key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    yr = np.array([g.year_of(t) for t in h1.index.date])
    d, _ = tu.supertrend_line(h4, 10, 3.0)
    up = gt.to_hours(d, h1)
    A = gt.to_hours(gt.atr(h4, 14), h1).bfill()
    stamp = pd.Series(np.arange(len(h4)), index=h4.index)
    k4 = gt.to_hours(stamp, h1)
    newbar = (k4 != k4.shift()).fillna(False)
    variants = [("none", 0, False)]
    for x in (1, 2, 3, 4):
        for rj in (False, True):
            variants.append(("breakeven", x, rj))
    for x in (2, 3, 4, 6):
        for rj in (False, True):
            variants.append(("lock half", x, rj))
    for x in (2, 3, 4, 5):
        for rj in (False, True):
            variants.append(("giveback", x, rj))
    rows = []
    for lock, x, rj in variants:
        tr = run(h1, up, newbar, A, lock, x, rj)
        eq = tu.equity(tr, h1)
        step = eq.diff().fillna(eq.iloc[0])
        per = {y: float(step[yr == y].sum()) for y in years}
        pnl = np.array([100 * (xx - e - COMM - SWAP * gt.nights(t0, t1)) for t0, e, t1, xx in tr])
        fdd, hdd = tu.dd(eq[np.isin(yr, fit)]), tu.dd(eq[np.isin(yr, hold)])
        fitp = sum(per[y] for y in fit)
        name = "no lock (baseline)" if lock == "none" else f"{lock} {x}" + ("" if lock == "giveback" else " ATR") + (", rejoin" if rj else ", wait")
        if lock == "giveback":
            name = f"giveback {x} ATR" + (", rejoin" if rj else ", wait")
        rows.append(dict(name=name, per=per, fit=fitp, hold=sum(per[y] for y in hold), total=sum(per.values()), n=len(tr),
                         win=100 * float((pnl > 0).mean()), t=pnl.mean() / (pnl.std(ddof=1) / len(pnl) ** 0.5),
                         fdd=fdd, hdd=hdd, dd=tu.dd(eq), score=fitp / -fdd if fdd < 0 else 0))
        print(name, round(fitp), round(rows[-1]["hold"]), round(rows[-1]["dd"]), flush=True)
    base = rows[0]
    L = ["## Profit locks on the 4-hour Supertrend (research/gold_trend_lock.py)", "",
         "Sorted by the fitting years' profit / drawdown (the held-out year read afterwards). USD per standard lot; "
         "drawdowns marked to market every hour, swap included.", "",
         "| version | " + " | ".join(years) + " | 3 years | trades | win | t | fitting drawdown | held-out drawdown | 3-year drawdown | fitting profit / drawdown |",
         "|---|" + "---|" * (len(years) + 9)]
    for r in sorted(rows, key=lambda r: -r["score"]):
        L.append(f"| {r['name']} | " + " | ".join(f"{r['per'][y]:+,.0f}" for y in years) +
                 f" | {r['total']:+,.0f} | {r['n']} | {r['win']:.0f}% | {r['t']:.2f} | {r['fdd']:,.0f} | {r['hdd']:,.0f} | {r['dd']:,.0f} | {r['score']:.2f} |")
    better = [r for r in rows[1:] if r["score"] > base["score"]]
    L += ["", f"{len(better)} of {len(rows) - 1} locks beat the baseline in the fitting years; of those "
          f"{sum(1 for r in better if r['hold'] >= base['hold'] and r['hdd'] >= base['hdd'])} also matched or beat it in the held-out year "
          f"on both profit and drawdown, {sum(1 for r in better if r['hdd'] > base['hdd'])} on drawdown alone."]
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
