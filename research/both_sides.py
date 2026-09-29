"""The owner's idea: buy BOTH the ATM call and put; each side has one stop; the side that survives books profits at
3-4 targets. If the market goes one way, the wrong side stops out and the right side runs to its targets.

    python research/both_sides.py <prev_year_wide.parquet> <year_wide.parquet> [out.md]

Each side = 4 lots of 30 (so it can book 1 lot at each of 4 targets; with 3 targets the last lot rides to 15:10).
Stop: a side is closed completely if its premium falls S% below its entry. Targets: +T1 / +T2 / +T3 / +T4 % on the
premium, one lot each; whatever is left goes out at 15:10. Optional: once one side has stopped, move the other
side's stop to its entry price (break-even). Entry at 09:20 / 10:00 / 11:00, real minute prices, nearest expiry
after the day, 0.5 slippage and Rs 50 a lot-leg round trip.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, COST = 30, 0.5, 50.0
CUT = 355


def side(leg, m0, e, stop, targets, lots, be_from=None):
    """Returns (rupees, minute the side became flat, minute stopped or None)."""
    left = lots
    pnl = 0.0
    hit = [False] * len(targets)
    st = e * (1 - stop)
    for m in range(m0, CUT + 1):
        if be_from is not None and m >= be_from:
            st = max(st, e)
        if leg["low"][m] <= st:
            pnl += left * (st - SLIP - e) * LOT
            return pnl, m, m
        for j, t in enumerate(targets):
            if not hit[j] and leg["high"][m] >= e * (1 + t):
                hit[j] = True
                pnl += (e * (1 + t) - SLIP - e) * LOT
                left -= 1
        if left == 0:
            return pnl, m, None
    pnl += left * (leg["close"][CUT] - SLIP - e) * LOT
    return pnl, CUT, None


def trade(d, m0, stop, targets, be):
    ks = np.array(sorted({k for k, r in d["chain"]}))
    k = ks[np.argmin(np.abs(ks - d["I"]["close"][m0 - 1]))]
    ce, pe = d["chain"].get((k, "CE")), d["chain"].get((k, "PE"))
    if ce is None or pe is None:
        return None
    lots = len(targets)
    ec, ep = ce["open"][m0] + SLIP, pe["open"][m0] + SLIP
    # first pass without break-even to find when a side stops
    c1 = side(ce, m0, ec, stop, targets, lots)
    p1 = side(pe, m0, ep, stop, targets, lots)
    if be:
        if c1[2] is not None and (p1[2] is None or c1[2] < p1[2]):
            p1 = side(pe, m0, ep, stop, targets, lots, be_from=c1[2] + 1)
        elif p1[2] is not None:
            c1 = side(ce, m0, ec, stop, targets, lots, be_from=p1[2] + 1)
    net = c1[0] + p1[0] - COST * lots * 2
    both_stopped = c1[2] is not None and p1[2] is not None
    return net, (ec + ep) * LOT * lots, both_stopped


def run(days, years, m0, stop, targets, be):
    out = []
    for i, d in enumerate(days):
        r = trade(d, m0, stop, targets, be)
        if r:
            out.append(dict(day=d["day"], year=years[i], net=r[0], cap=r[1], both=r[2]))
    return pd.DataFrame(out)


def main():
    paths = [a for a in sys.argv[1:] if a.endswith(".parquet")]
    out_md = next((a for a in sys.argv[1:] if a.endswith(".md")), None)
    days, years = [], []
    for yi, p in enumerate(paths):
        ds = load(p)
        days += ds
        years += [("Feb24-Feb25" if yi == 0 else "Feb25-Feb26")] * len(ds)
    out = ["## Buy both call and put, stop the loser, ride the winner to 3-4 targets (both years)", "",
           "4 lots per side (8 lots in total, ~Rs 1-1.8 lakh of premium), one trade a day, after slippage and charges.", "",
           "| entry | stop per side | targets | break-even after first stop | Feb24-Feb25: days won / net Rs / both sides stopped | Feb25-Feb26: same | t (both) |",
           "|---|---|---|---|---|---|---|"]
    for m0, tl in ((5, "09:20"), (45, "10:00"), (105, "11:00")):
        for stop in (0.15, 0.25, 0.35):
            for targets, tn in (((0.2, 0.4, 0.6, 0.8), "+20/40/60/80%"), ((0.3, 0.6, 1.0, 1.5), "+30/60/100/150%"),
                                ((0.15, 0.3, 0.45), "+15/30/45% (3 targets)")):
                for be in (False, True):
                    tr = run(days, years, m0, stop, targets, be)
                    cells = []
                    for yr in ("Feb24-Feb25", "Feb25-Feb26"):
                        x = tr[tr.year == yr]
                        cells.append(f"{100 * (x.net > 0).mean():.0f}% / {x.net.sum():,.0f} / {100 * x.both.mean():.0f}%")
                    t = tr.net.mean() / (tr.net.std(ddof=1) / np.sqrt(len(tr)))
                    out.append(f"| {tl} | -{int(stop * 100)}% | {tn} | {'yes' if be else 'no'} | {cells[0]} | {cells[1]} | {t:.2f} |")
        print("\n".join(out[-18:]), flush=True)
    text = "\n".join(out)
    if out_md:
        open(out_md, "w").write(text)


if __name__ == "__main__":
    main()
