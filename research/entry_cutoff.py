"""The last-entry time (the owner's ask, 2026-10-01): what each arm would have made with its last new trade at 14:00,
14:30 (the arms today; Range Fade 14:00), 14:55, 15:00, or up to 15:05 ("off": the 15:10 exit still applies to all).
Two BANKNIFTY years, real option prices, 1 lot of 30, after costs, every arm exactly as it trades now (the profit-lock
ladder on ORB / ORB Fresh / ORB Sweep / Range Fade; Liquidity 15+5 with its 15% stop, 30-pt index stop and 20-min time
stop). Only the latest entry time changes.

    python research/entry_cutoff.py <year A wide parquet> <year B wide parquet> [out.md]
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import arms_long as al  # noqa: E402
import liquidity_break as lb  # noqa: E402
from profit_lock import fill_ladder  # noqa: E402
from range_fade_long import CHG, LOT  # noqa: E402
from sell_levels import load as liq_load  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]
CUTOFFS = [("14:00", "14:00"), ("14:30", "14:30"), ("14:55", "14:55"), ("15:00", "15:00"), ("off (to 15:05)", "15:05")]


def hm(t):
    return t.strftime("%H:%M")


def signal(name, b, j, c, last):
    """The arm's signal with its latest decision bar moved: a bar labelled before [last] - 5 min (its entry by [last])."""
    t = hm(b.index[j])
    lim = (pd.Timestamp("2000-01-01 " + last) - pd.Timedelta(minutes=5)).strftime("%H:%M")
    r = b.iloc[j]
    if name in ("ORB", "ORB Fresh"):
        if not ("10:00" < t <= lim):
            return None
        s = "CE" if r.close > c["orh"] else "PE" if r.close < c["orl"] else None
        if name == "ORB Fresh" and s:
            q = b.iloc[j - 1]
            if (s == "CE" and q.close > c["orh"]) or (s == "PE" and q.close < c["orl"]):
                return None
        return s
    if name == "ORB Sweep":
        if not ("10:00" < t <= lim):
            return None
        if r.high > c["orh"] and r.close < c["orh"]:
            return "PE"
        if r.low < c["orl"] and r.close > c["orl"]:
            return "CE"
        return None
    if name == "Range Fade":
        w = c["orh"] - c["orl"]
        if not ("10:30" <= t <= lim) or w <= 0:
            return None
        if r.high >= c["orh"] - 0.1 * w and r.close < c["orh"]:
            return "PE"
        if r.low <= c["orl"] + 0.1 * w and r.close > c["orl"]:
            return "CE"
    return None


def run_arm(days, name, last):
    _, stop, tgt, maxn = al.ARMS[name]
    out = []
    for day, b, legs in days:
        orb_ = b.between_time("09:15", "10:00")
        c = dict(orh=orb_.high.max(), orl=orb_.low.min())
        n, busy = 0, None
        for j in range(1, len(b) - 1):
            if n >= maxn:
                break
            if busy is not None and b.index[j] <= busy.floor("5min"):
                continue
            s = signal(name, b, j, c, last)
            if not s:
                continue
            f = fill_ladder(legs[s], b.index[j + 1], stop, tgt, LADDER)
            if f is None:
                continue
            out.append(dict(day=day, net=f[0] * LOT - CHG, late=hm(b.index[j + 1]) > "14:30"))
            n += 1
            busy = f[1]
    return pd.DataFrame(out)


def run_liq(path, last):
    days = liq_load(path)
    h, m = map(int, last.split(":"))
    lb.LAST_M = h * 60 + m - 555
    rows = []
    for tf in (15, 5):
        b = lb.bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        tr = lb.simulate(days, b, zones, "both", True, prem_stop=0.15, ix_buffer=30, time_stop=(20, 0.05))
        if not tr.empty:
            rows.append(pd.DataFrame(dict(day=tr.day, net=tr.rs, late=tr.m0 > 315)))
    lb.LAST_M = 315
    return pd.concat(rows) if rows else pd.DataFrame(columns=["day", "net", "late"])


def cell(x):
    if len(x) == 0:
        return "0 | Rs +0"
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else float("nan")
    return f"{len(x)} | Rs {x.sum():+,.0f} ({t:+.2f})"


def main():
    pa, pb = sys.argv[1], sys.argv[2]
    A, B = al.load("file:" + pa), al.load("file:" + pb)
    L = ["## The last-entry time (research/entry_cutoff.py)", "",
         f"BANKNIFTY two years: A {A[0][0]} .. {A[-1][0]}, B {B[0][0]} .. {B[-1][0]}. Arms as they trade now, 1 lot, after costs; "
         "only the latest new-trade time changes (the 15:10 exit is unchanged). Cells: trades | net (t). "
         "\"After 14:30\" = the trades that start later than today's 14:30 window.", ""]
    for name in ["ORB", "ORB Fresh", "ORB Sweep", "Range Fade", "Liquidity 15+5"]:
        L += [f"### {name}", "", "| last entry | year A | year B | both | of which after 14:30 |", "|---|---|---|---|---|"]
        for label, last in CUTOFFS:
            if name == "Liquidity 15+5":
                ta, tb = run_liq(pa, last), run_liq(pb, last)
            else:
                ta, tb = run_arm(A, name, last), run_arm(B, name, last)
            both = pd.concat([ta, tb])
            late = both[both.late] if len(both) else both
            L.append(f"| {label} | {cell(ta.net if len(ta) else pd.Series(dtype=float))} | {cell(tb.net if len(tb) else pd.Series(dtype=float))} | "
                     f"Rs {both.net.sum():+,.0f} | {len(late)} trades, Rs {late.net.sum() if len(late) else 0:+,.0f} |")
            print(name, L[-1], flush=True)
        L.append("")
    text = "\n".join(L)
    if len(sys.argv) > 3:
        open(sys.argv[3], "w").write(text)


if __name__ == "__main__":
    main()
