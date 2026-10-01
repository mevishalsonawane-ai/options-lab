"""Profit-lock ladders for the normal arms (the owner's ask, 2026-10-01): a trade that gets 25 / 50 / 75 % of the way
to its target and then falls back to a full stop. Two BANKNIFTY years, real ATM option prices, arms exactly as
research/arms_long.py runs them (decide on the closed 5-minute bar, option's next minute open + 0.5, 15:10 out,
Rs 40 a trip, 1 lot of 30). Only the exit changes.

    python research/profit_lock.py <year A file> <year B file> [out.md]

A ladder is a list of (reached, lock): once the option has traded `reached` x target above the price paid, the
stop moves up to price paid + `lock` x target (0 = breakeven). The new stop applies from the NEXT minute (no
same-minute luck); a stop fills at its level less 0.5 slippage, as the arms' -40 stop does.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import arms_long as al  # noqa: E402
from range_fade_long import CHG, LOT, SLIP  # noqa: E402

LADDERS = {
    "none (today)": [],
    "75% -> lock 50%": [(0.75, 0.50)],
    "50% -> breakeven": [(0.50, 0.0)],
    "50% -> BE, 75% -> lock 50%": [(0.50, 0.0), (0.75, 0.50)],
    "25% -> BE, 50% -> lock 25%, 75% -> lock 50%": [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)],
    "50% -> lock 25%, 75% -> lock 50%": [(0.50, 0.25), (0.75, 0.50)],
    "25% -> half risk, 50% -> BE, 75% -> lock 50%": [(0.25, -0.5), (0.50, 0.0), (0.75, 0.50)],
}


def fill_ladder(leg, t_entry, stop, tgt, ladder):
    a = leg[leg.index >= t_entry]
    if a.empty or a.index[0].strftime("%H:%M") >= "15:10":
        return None
    e = a.open.iloc[0] + SLIP
    sl = e - stop          # current stop level (premium)
    why = "stop"
    for ts, r in a.iterrows():
        if r.low <= sl:
            return sl - SLIP - e, ts, why
        if r.high >= e + tgt:
            return tgt - SLIP, ts, "target"
        if ts.strftime("%H:%M") >= "15:10":
            return r.close - SLIP - e, ts, "15:10"
        for reached, lock in ladder:                     # ratchet for the next minute
            if r.high >= e + reached * tgt:
                lvl = e + lock * tgt if lock >= 0 else e + lock * stop
                if lvl > sl:
                    sl, why = lvl, f"lock {int(reached * 100)}%"
    return a.close.iloc[-1] - SLIP - e, a.index[-1], "end"


def run(days, name, ladder, tgt_override=None):
    sig, stop, tgt, maxn = al.ARMS[name]
    tgt = tgt_override or tgt
    ind, st15 = al.indicators(days)
    out = []
    for day, b, legs in days:
        orb_ = b.between_time("09:15", "10:00")
        ctx = dict(orh=orb_.high.max(), orl=orb_.low.min(), ind=ind, st15=st15)
        n, busy = 0, None
        for j in range(1, len(b) - 1):
            if n >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = sig(b, j, ctx)
            if not s:
                continue
            f = fill_ladder(legs[s], b.index[j + 1], stop, tgt, ladder)
            if f is None:
                continue
            out.append(dict(day=day, net=f[0] * LOT - CHG, why=f[2]))
            n += 1
            busy = f[1]
    return pd.DataFrame(out)


def cell(tr):
    if tr.empty:
        return "0 | | |"
    x = tr.net
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else float("nan")
    return f"{len(x)} | {100 * (x > 0).mean():.0f}% | Rs {x.sum():+,.0f} | {t:.2f}"


def main():
    yA, yB = al.load("file:" + sys.argv[1]), al.load("file:" + sys.argv[2])
    days = sorted(yA + yB, key=lambda d: d[0])
    A, B = {d for d, _, _ in yA}, {d for d, _, _ in yB}
    L = ["## Profit-lock ladders on the normal arms (research/profit_lock.py)", "",
         f"BANKNIFTY year A {min(A)} .. {max(A)}, year B {min(B)} .. {max(B)}; real ATM option prices, 1 lot of 30, after costs.",
         "Each ladder: once the option has gone X% of the way to the target, the stop moves to the stated level.", ""]
    arms = [("ORB", None), ("ORB Fresh", None), ("ORB Sweep", None), ("Range Fade", None), ("ORB", 50)]
    for name, tov in arms:
        sig, stop, tgt, _ = al.ARMS[name]
        title = f"{name} (stop -{stop}, target +{tov or tgt})"
        L += [f"### {title}", "", "| ladder | year A: trades, win, net, t | year B: trades, win, net, t | both years |",
              "|---|---|---|---|"]
        for lname, ladder in LADDERS.items():
            tr = run(days, name, ladder, tov)
            a, b = tr[tr.day.isin(A)], tr[tr.day.isin(B)]
            L.append(f"| {lname} | {cell(a)} | {cell(b)} | Rs {tr.net.sum():+,.0f} |")
            print(title, L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
