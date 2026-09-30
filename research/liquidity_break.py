"""The owner's liquidity strategy: Liquidity Swings (pivot lookback 20, full-range areas) + Liquidity Pools (2
contacts, 5 bars apart, 10 confirmation bars), on BANKNIFTY, buying options.

    python research/liquidity_break.py <year.parquet> [out.md]

Levels come from indicator/liquidity.py on the chart timeframe (continuous across days, like a TradingView chart).
  entry   a bar CLOSES through an active liquidity level: above a high's top -> BUY the ATM CE, below a low's
          bottom -> BUY the ATM PE (next minute's open + 0.5). Entries 09:20-14:30, one trade at a time.
  exit    "sell when liquidity is found again": the first of
            target    price touches the next active liquidity level in the trade's direction
            new       a new liquidity level forms on the trade's side (a new swing high / pool above for a long:
                      the move made its own liquidity) - out at that bar's close
            [stop]    optional: a close back through the broken level (the break failed)
            15:10     square-off
  sources "swing" (swing levels only), "pool" (pools only), "either", "both" (a pool break that coincides with a
          swing zone on the same side, the two indicators agreeing)
Option P&L: 1 lot of 30, real minute prices, 0.5 slippage a side, Rs 40 a round trip. Also the index points (was the
break's direction right?).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
CUT = 355


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "open", "high", "low", "close"])


def simulate(days, b, zones, source, stop):
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    # events by bar: breaks (zone, bar) and new zones known at a bar
    breaks, known = {}, {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
        if z.broken >= 0:
            breaks.setdefault(z.broken, []).append(z)
    trades = []
    pos = None
    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None:
            sg = pos["sign"]
            why, xm = None, None
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= CUT:
                    why, xm = "15:10", m
                    break
                if pos["target"] is not None and ((sg > 0 and I["high"][m] >= pos["target"]) or
                                                  (sg < 0 and I["low"][m] <= pos["target"])):
                    why, xm = "next liquidity", m
                    break
            if why is None and stop and sg * (C[i] - pos["level"]) < 0:
                why, xm = "failed break", E[i] - 1
            if why is None and any(z.side == sg and z.known == i for z in known.get(i, [])):
                why, xm = "new liquidity", E[i] - 1
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, xm = "15:10", min(E[i] - 1, 374)
            if why:
                leg = d["chain"][pos["key"]]
                ix = I["close"][xm] if why != "next liquidity" else pos["target"]
                trades.append(dict(day=d["day"], sign=sg, why=why, pts=sg * (ix - pos["ix"]),
                                   rs=(leg["close"][xm] - SLIP - pos["px"]) * LOT - CHG, held=xm - pos["m"]))
                pos = None
        if pos is not None or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (5 <= m <= 315):
            continue
        brk = breaks.get(i, [])
        sw = [z for z in brk if z.kind == "swing"]
        pl = [z for z in brk if z.kind == "pool"]
        if source == "swing":
            cand = sw
        elif source == "pool":
            cand = pl
        elif source == "either":
            cand = brk
        else:  # both: a pool break overlapping an active-until-now swing zone on the same side
            cand = [p for p in pl if any(s.side == p.side and s.bottom <= p.top and p.bottom <= s.top and
                                         s.known <= i and (s.broken < 0 or s.broken >= i) for s in
                                         [z for z in zones if z.kind == "swing"])]
        if not cand:
            continue
        z = cand[0]
        sg = z.side
        ix = I["open"][m]
        # the next liquidity in the trade's direction: the nearest active level beyond the entry
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                 and sg * (q.edge - ix) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        right = "CE" if sg > 0 else "PE"
        ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
        if not len(ks):
            continue
        k = ks[np.argmin(np.abs(ks - ix))]
        pos = dict(sign=sg, m=m, ix=ix, level=z.edge, target=target, key=(k, right),
                   px=d["chain"][(k, right)]["open"][m] + SLIP)
    return pd.DataFrame(trades)


def row(label, tr, alld):
    if tr.empty:
        return f"| {label} | 0 | | | | | | | |"
    half = set(alld[: len(alld) // 2])
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x))) if len(x) > 2 else np.nan
    m = tr.assign(mo=[str(d)[:7] for d in tr.day]).groupby("mo").rs.sum()
    return (f"| {label} | {len(tr)} ({len(tr) / len(alld):.1f}/day) | {100 * (x > 0).mean():.0f}% | "
            f"{tr.pts.mean():+.1f} | Rs {x.mean():+,.0f} | {t:.2f} | Rs {x.sum():+,.0f} | "
            f"{tr[tr.day.isin(half)].rs.sum():+,.0f} / {tr[~tr.day.isin(half)].rs.sum():+,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    L = [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
         "| timeframe, levels, stop | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half | green months |",
         "|---|---|---|---|---|---|---|---|---|"]
    notes = []
    for tf in (1, 3, 5, 15):
        b = bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        nsw = sum(z.kind == "swing" for z in zones)
        npl = len(zones) - nsw
        notes.append(f"{tf}-min: {nsw} swing levels, {npl} pools; median bars from a level forming to its break "
                     f"{np.median([z.broken - z.known for z in zones if z.broken >= 0]):.0f}")
        for source in ("swing", "pool", "either", "both"):
            for stop in (False, True):
                tr = simulate(days, b, zones, source, stop)
                L.append(row(f"{tf}-min, {source}, {'stop on failed break' if stop else 'no stop'}", tr, alld))
                print(L[-1], flush=True)
                if tf == 5 and source == "either" and not stop and not tr.empty:
                    notes.append("5-min, either, no stop: exits " + ", ".join(
                        f"{k} {100 * v:.0f}%" for k, v in tr.why.value_counts(normalize=True).items()) +
                        f"; median hold {tr.held.median():.0f} min")
    L += [""] + [f"- {n}" for n in notes]
    text = "\n".join(L)
    print("\n".join(notes))
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
