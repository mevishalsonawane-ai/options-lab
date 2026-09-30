"""The owner's liquidity idea, step 2: when liquidity is found, wait 5 candles, read the direction from those 5
candles, then buy the call (up) or the put (down). BANKNIFTY, buying options, two years.

    python research/liquidity_wait5.py <year.parquet> [out.md]

Liquidity levels: indicator/liquidity.py (swings: pivot 20, full range; pools: 2 contacts, 5 bars apart,
10 confirmation bars), on the chart timeframe, continuous across days.
  event    "found": a new liquidity level becomes known (a swing confirmed / a pool confirmed; "both" = a pool that
           sits on an active swing zone of the same side)
           "break": a close takes a liquidity level
  wait     the 5 candles after the event (same session)
  reader   net     the 5th candle's close vs the event candle's close
           colour  more green than red candles (a tie: no trade)
           hhhl    higher highs + higher lows minus lower highs + lower lows over the 5 candles (0: no trade)
           held    (breaks only) the 5th close still beyond the broken level -> with the break, else against it
  trade    the next candle's first minute: BUY the ATM CE (up) or PE (down), + 0.5; out when price touches the next
           liquidity level in the trade's direction, a new level forms on that side, or 15:10. Entries to 14:30.
  control  the same readers on the 5 candles after an ordinary candle (the event bar + 20): does liquidity add anything?
1 lot of 30, real minute prices, 0.5 slippage a side, Rs 40 a round trip.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from sell_levels import load  # noqa: E402
from liquidity_break import bars  # noqa: E402
from indicator.liquidity import pool_zones, swing_zones  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0
CUT = 355
WAIT = 5


def read(b, i, how, level=None, side=0):
    """Direction from candles i+1 .. i+WAIT: +1 up, -1 down, 0 none."""
    O, H, L, C = b.open.values, b.high.values, b.low.values, b.close.values
    w = range(i + 1, i + WAIT + 1)
    if how == "net":
        return int(np.sign(C[i + WAIT] - C[i]))
    if how == "colour":
        return int(np.sign(sum(np.sign(C[k] - O[k]) for k in w)))
    if how == "hhhl":
        return int(np.sign(sum((H[k] > H[k - 1] and L[k] > L[k - 1]) - (H[k] < H[k - 1] and L[k] < L[k - 1]) for k in w)))
    if how == "held":
        beyond = (C[i + WAIT] > level) if side > 0 else (C[i + WAIT] < level)
        return side if beyond else -side
    raise ValueError(how)


def run(days, b, zones, signals):
    """signals: {bar index whose close decides: sign}. Entry at the next bar's open minute; one trade at a time."""
    DI, S, E, C = b.di.values, b.s.values, b.e.values, b.close.values
    known = {}
    for z in zones:
        known.setdefault(z.known, []).append(z)
    trades, pos = [], None
    for i in range(len(b)):
        d = days[DI[i]]
        I = d["I"]
        if pos is not None:
            sg, why, xm = pos["sign"], None, None
            for m in range(max(S[i], pos["m"]), E[i]):
                if m >= CUT:
                    why, xm = "15:10", m
                    break
                if pos["target"] is not None and ((sg > 0 and I["high"][m] >= pos["target"]) or
                                                  (sg < 0 and I["low"][m] <= pos["target"])):
                    why, xm = "next liquidity", m
                    break
            if why is None and any(z.side == sg for z in known.get(i, [])):
                why, xm = "new liquidity", E[i] - 1
            if why is None and (i + 1 >= len(b) or DI[i + 1] != DI[i]):
                why, xm = "15:10", min(E[i] - 1, 374)
            if why:
                leg = d["chain"][pos["key"]]
                ix = pos["target"] if why == "next liquidity" else I["close"][xm]
                trades.append(dict(day=d["day"], sign=sg, why=why, pts=sg * (ix - pos["ix"]),
                                   rs=(leg["close"][xm] - SLIP - pos["px"]) * LOT - CHG))
                pos = None
        sg = signals.get(i, 0)
        if pos is not None or not sg or i + 1 >= len(b) or DI[i + 1] != DI[i]:
            continue
        m = S[i + 1]
        if not (5 <= m <= 315):
            continue
        ix = I["open"][m]
        ahead = [q.edge for q in zones if q.side == sg and q.known <= i and (q.broken < 0 or q.broken > i)
                 and sg * (q.edge - ix) > 0]
        target = (min(ahead) if sg > 0 else max(ahead)) if ahead else None
        right = "CE" if sg > 0 else "PE"
        ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
        if not len(ks):
            continue
        k = ks[np.argmin(np.abs(ks - ix))]
        pos = dict(sign=sg, m=m, ix=ix, target=target, key=(k, right), px=d["chain"][(k, right)]["open"][m] + SLIP)
    return pd.DataFrame(trades)


def events(b, zones, kind, source):
    """[(bar, zone)] for the event type: the bar the level became known ("found") or was taken ("break")."""
    sw = [z for z in zones if z.kind == "swing"]
    out = []
    for z in zones:
        if source == "swing" and z.kind != "swing":
            continue
        if source in ("pool", "both") and z.kind != "pool":
            continue
        at = z.known if kind == "found" else z.broken
        if at < 0:
            continue
        if source == "both" and not any(s.side == z.side and s.bottom <= z.top and z.bottom <= s.top and s.known <= at
                                        and (s.broken < 0 or s.broken >= at) for s in sw):
            continue
        out.append((at, z))
    return out


def signals_for(b, evs, how, shift=0):
    DI = b.di.values
    sig = {}
    for at, z in evs:
        i = at + shift
        if i + WAIT + 1 >= len(b) or DI[i] != DI[i + WAIT] or DI[i + WAIT] != DI[i + WAIT + 1]:
            continue
        s = read(b, i, how, z.edge, z.side)
        if s:
            sig[i + WAIT] = s
    return sig


def row(label, tr, alld):
    if tr.empty or len(tr) < 3:
        return f"| {label} | {len(tr)} | | | | | | |"
    half = set(alld[: len(alld) // 2])
    x = tr.rs
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    return (f"| {label} | {len(tr)} ({len(tr) / len(alld):.1f}/day) | {100 * (x > 0).mean():.0f}% | "
            f"{tr.pts.mean():+.1f} | Rs {x.mean():+,.0f} | {t:.2f} | Rs {x.sum():+,.0f} | "
            f"{tr[tr.day.isin(half)].rs.sum():+,.0f} / {tr[~tr.day.isin(half)].rs.sum():+,.0f} |")


def main():
    days = load(sys.argv[1])
    alld = [d["day"] for d in days]
    L = [f"### {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
         "| timeframe, event, levels, reader | trades | win (Rs) | index pts/trade | per trade | t | net (1 lot) | 1st / 2nd half |",
         "|---|---|---|---|---|---|---|---|"]
    for tf in (3, 5, 15):
        b = bars(days, tf)
        zones = swing_zones(b, 20, "full") + pool_zones(b, 2, 5, 10)
        for kind in ("found", "break"):
            for source in ("swing", "pool", "either", "both"):
                evs = events(b, zones, kind, source)
                for how in ("net", "colour", "hhhl") + (("held",) if kind == "break" else ()):
                    tr = run(days, b, zones, signals_for(b, evs, how))
                    L.append(row(f"{tf}-min, {kind}, {source}, {how}", tr, alld))
                    print(L[-1], flush=True)
                if source == "either":
                    for how in ("net", "hhhl"):
                        tr = run(days, b, zones, signals_for(b, evs, how, shift=20))
                        L.append(row(f"{tf}-min, CONTROL (ordinary candle), {how}", tr, alld))
                        print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
