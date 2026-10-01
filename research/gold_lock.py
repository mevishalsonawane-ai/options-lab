"""A profit lock for IraGoldAlgo (the owner's ask, 2026-10-01): the XAUUSD 1-hour liquidity rule as the app trades it
(London + New York, buys only - a short signal is ignored, never held) with and without a stop that moves up as the
trade gains. Dukascopy 1-minute bid, ask = bid + 0.30, $7 a lot; USD an ounce (x100 = a standard lot).

    python research/gold_lock.py <xauusd_m1_bid.csv.gz> [out.md]

Locks (a rung reached on a minute's bid high counts from the next minute; the lock sells at its level on the bid):
  ladder to target   25% of the way to the next liquidity level -> breakeven, 50% -> +25%, 75% -> +50% (IraAlgo's ladder)
                     (no lock when there is no level above)
  breakeven at +$X   once the bid has been X above the price paid, the stop is the price paid
  step lock          at +$X lock +$X/2, at +$2X lock +$X, ...
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
import liquidity_gold as g  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LADDER = [(0.25, 0.0), (0.50, 0.25), (0.75, 0.50)]


def lock_level(kind, x, entry, target, peak):
    gain = peak - entry
    if kind is None:
        return None
    if kind == "ladder":
        if target is None or target <= entry:
            return None
        lv = None
        for reached, keep in LADDER:
            if gain >= reached * (target - entry) - 1e-9:
                lv = entry + keep * (target - entry)
        return lv
    if kind == "be":
        return entry if gain >= x else None
    if kind == "step":
        n = int(gain // x)
        return entry + (n * x) / 2 if n >= 1 else None


def simulate(days, b, zones, first, last, cut, kind=None, x=None):
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    swings = [z for z in zones if z.kind == "swing"]
    trades, pos = [], None
    for i in range(len(b)):
        d = days[DI[i]]
        if pos is not None:
            why, px = None, None
            for m in range(max(S[i], pos["m"]) if DI[i] == pos["di"] else S[i], E[i]):
                if m >= cut:
                    why, px = "cut-off", d["bclose"][m]
                    break
                lv = lock_level(kind, x, pos["px"], pos["target"], pos["peak"])
                if lv is not None and m > pos["m"] and d["blow"][m] <= lv:
                    why, px = "lock", min(lv, d["bopen"][m])
                    break
                if pos["target"] is not None and d["mhigh"][m] >= pos["target"]:
                    half = (d["aclose"][m] - d["bclose"][m]) / 2
                    why, px = "next liquidity", pos["target"] - half
                    break
                pos["peak"] = max(pos["peak"], d["bhigh"][m])
            if why is None and C[i] - pos["level"] < 0:
                why, px = "failed break", d["bclose"][E[i] - 1]
            if why is None and any(z.side > 0 for z in known.get(i, [])):
                why, px = "new liquidity", d["bclose"][E[i] - 1]
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, px = "cut-off", d["bclose"][E[i] - 1]
            if why:
                trades.append(dict(day=pos["day"], why=why, usd=px - pos["px"] - g.COMM))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (first <= m <= last):
            continue
        pl = [z for z in breaks.get(i, []) if z.kind == "pool" and z.side > 0]          # buys only
        cand = [p for p in pl if any(s.side > 0 and s.bottom <= p.top and p.bottom <= s.top and s.known <= i
                                     and (s.broken < 0 or s.broken >= i) for s in swings)]
        if not cand:
            continue
        z = cand[0]
        px = d["aopen"][m]
        mid = d["mopen"][m]
        ahead = [q.edge for q in zones if q.side > 0 and q.known <= i and (q.broken < 0 or q.broken > i) and q.edge - mid > 0]
        pos = dict(day=d["day"], di=DI[i], m=m, px=px, level=z.edge, target=min(ahead) if ahead else None, peak=px)
    return pd.DataFrame(trades)


def main():
    bid = g.read(sys.argv[1])
    ask = bid + g.SPREAD
    start, end, first, last, cut = g.SESSIONS["London + New York"]
    days = g.sessions(bid, ask, start, end)
    b = g.bars(days, 60)
    zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
    years = sorted({g.year_of(d["day"]) for d in days}, key=lambda s: s[4:8])
    variants = [("no lock (the app today)", None, None), ("ladder to the next liquidity", "ladder", None)] + \
        [(f"breakeven at +${x:g}", "be", x) for x in (3, 5, 10)] + [(f"step lock every +${x:g}", "step", x) for x in (5, 10)]
    L = ["## A profit lock for IraGoldAlgo (research/gold_lock.py)", "",
         f"XAUUSD 1-hour liquidity, London + New York, buys only as the app trades it. Dukascopy {bid.index.min():%Y-%m-%d} .. "
         f"{bid.index.max():%Y-%m-%d}, ask = bid + 0.30, $7 a lot. USD per standard lot (100 oz) after costs.", "",
         "| exit | " + " | ".join(years) + " | all | trades | win | worst trade | locks |",
         "|---|" + "---|" * (len(years) + 5)]
    for name, kind, x in variants:
        tr = simulate(days, b, zones, first, last, cut, kind, x)
        tr["year"] = [g.year_of(d) for d in tr.day]
        per = [f"{100 * tr[tr.year == y].usd.sum():+,.0f}" for y in years]
        L.append(f"| {name} | " + " | ".join(per) + f" | {100 * tr.usd.sum():+,.0f} | {len(tr)} | {100 * (tr.usd > 0).mean():.0f}% | "
                 f"{100 * tr.usd.min():+,.0f} | {(tr.why == 'lock').sum()} |")
        print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
