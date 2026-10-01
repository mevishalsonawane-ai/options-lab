"""IraAlgo's ORB-family arms on XAUUSD (the owner's ask, 2026-10-01: "check if our arms work with gold").

    python research/gold_arms.py <xauusd_m1_bid.csv.gz> [out.md]

The rules are research/arms_long.py's (as the app runs them), moved onto gold's clock: 5-minute mid bars from a session
open, the opening range = the first 45 minutes (10 bars, as 09:15-10:00), signals on bars closing 50-310 minutes after
the open (10:05-14:25; Range Fade 75-280, 10:30-13:55), entry at the next bar's open, out by 355 minutes (15:10).
  ORB          close outside the range -> that way; no daily cap
  ORB Fresh    the same, only when the previous bar closed inside
  ORB Sweep    wick through the range, close back inside -> fade; target twice the stop; max 2 a day
  Range Fade   bar in the outer 10% of the range, close back inside -> fade; max 2 a day
Gold has no option here, so a trade is the metal: a buy pays the ask (bid + 0.30), sells at the bid, $7 a 100-oz lot.
"Buys only" is how IraGoldAlgo trades (a sell signal ignored); "both ways" shows the short sales for reference.
The arms' -40/+40 premium points on an ATM BANKNIFTY option are about 0.16% of the index; two scales are tested:
  0.16%      stop and target 0.16% of the entry price (Sweep target 0.32%)
  half range stop and target half the opening range (Sweep target the whole range)
Sessions (UTC open): India hours 03:45 (09:15 IST, the app's own clock), London 07:00, New York 13:30.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import liquidity_gold as g  # noqa: E402

SPREAD, COMM = 0.30, 0.07
SESSIONS = {"India hours (03:45 UTC)": 3 * 60 + 45, "London (07:00 UTC)": 7 * 60, "New York (13:30 UTC)": 13 * 60 + 30}
LEN = 360


def days(bid, open_min):
    """[(date, minute mid OHLC arrays [LEN])] for weekdays with most of the session present."""
    out = []
    idx = bid.index
    mins = idx.hour * 60 + idx.minute
    off = (mins - open_min) % 1440
    keep = off < LEN
    b = bid[keep]
    o = off[keep]
    start = (b.index - pd.to_timedelta(o, unit="m")).normalize()
    for day, gidx in pd.Series(np.arange(len(b)), index=start).groupby(level=0):
        if day.weekday() >= 5 or len(gidx) < 0.8 * LEN:
            continue
        rows = gidx.values
        k = o[rows]
        grid = np.full((LEN, 4), np.nan)
        grid[k] = b.iloc[rows][["open", "high", "low", "close"]].values + SPREAD / 2   # mid
        grid = pd.DataFrame(grid).ffill().bfill().values
        out.append((day.date(), grid))
    return out


def five(grid):
    n = LEN // 5
    r = grid.reshape(n, 5, 4)
    return np.stack([r[:, 0, 0], r[:, :, 1].max(1), r[:, :, 2].min(1), r[:, -1, 3]], 1)   # O H L C per 5 minutes


def sig_orb(b, j, c, fresh=False):
    if not (10 <= j <= 62):
        return 0
    s = 1 if b[j, 3] > c["h"] else -1 if b[j, 3] < c["l"] else 0
    if fresh and s and ((s > 0 and b[j - 1, 3] > c["h"]) or (s < 0 and b[j - 1, 3] < c["l"])):
        return 0
    return s


def sig_sweep(b, j, c):
    if not (10 <= j <= 62):
        return 0
    if b[j, 1] > c["h"] and b[j, 3] < c["h"]:
        return -1
    if b[j, 2] < c["l"] and b[j, 3] > c["l"]:
        return 1
    return 0


def sig_fade(b, j, c):
    w = c["h"] - c["l"]
    if not (15 <= j <= 56) or w <= 0:
        return 0
    if b[j, 1] >= c["h"] - 0.1 * w and b[j, 3] < c["h"]:
        return -1
    if b[j, 2] <= c["l"] + 0.1 * w and b[j, 3] > c["l"]:
        return 1
    return 0


ARMS = {  # name: (signal, target multiple of the stop, max a day)
    "ORB": (lambda b, j, c: sig_orb(b, j, c), 1, 99),
    "ORB Fresh": (lambda b, j, c: sig_orb(b, j, c, True), 1, 99),
    "ORB Sweep": (sig_sweep, 2, 2),
    "Range Fade": (sig_fade, 1, 2),
}


def fill(grid, m0, side, stop, tgt):
    """Minute walk from minute [m0]: a buy enters at the ask, exits on the bid (a short sale the other way)."""
    e = grid[m0, 0] + side * SPREAD / 2
    for m in range(m0, LEN):
        o, h, l, c = grid[m]
        if side > 0:
            if l - SPREAD / 2 <= e - stop:
                return -stop - COMM, m
            if h - SPREAD / 2 >= e + tgt:
                return tgt - COMM, m
        else:
            if h + SPREAD / 2 >= e + stop:
                return -stop - COMM, m
            if l + SPREAD / 2 <= e - tgt:
                return tgt - COMM, m
        if m >= 355:
            return side * (c - side * SPREAD / 2 - e) - COMM, m
    return side * (grid[-1, 3] - side * SPREAD / 2 - e) - COMM, LEN - 1


def run(ds, sig, mult, maxn, scale, buys_only):
    out = []
    for day, grid in ds:
        b = five(grid)
        c = dict(h=b[:10, 1].max(), l=b[:10, 2].min())
        n, busy = 0, -1
        for j in range(10, len(b) - 1):
            if n >= maxn:
                break
            if j * 5 + 4 <= busy:
                continue
            s = sig(b, j, c)
            if not s or (buys_only and s < 0):
                continue
            m0 = (j + 1) * 5
            if m0 >= 355:
                break
            px = grid[m0, 0]
            stop = 0.0016 * px if scale == "0.16%" else 0.5 * (c["h"] - c["l"])
            if stop <= 0.05:
                continue
            usd, busy = fill(grid, m0, s, stop, mult * stop)
            out.append(dict(day=day, usd=100 * usd))
            n += 1
    return pd.DataFrame(out)


def main():
    bid = g.read(sys.argv[1])
    L = ["## IraAlgo's arms on gold (research/gold_arms.py)", "",
         f"XAUUSD, Dukascopy 1-minute bid {bid.index.min():%Y-%m-%d} .. {bid.index.max():%Y-%m-%d}; ask = bid + 0.30, $7 a lot. "
         "USD per standard lot (100 oz) after costs. Years October to September. The rules are the app's, on gold's clock "
         "(see the script's header).", ""]
    years = None
    rows = []
    for sname, op in SESSIONS.items():
        ds = days(bid, op)
        for scale in ("0.16%", "half range"):
            for name, (sig, mult, maxn) in ARMS.items():
                for buys_only in (True, False):
                    tr = run(ds, sig, mult, maxn, scale, buys_only)
                    if tr.empty:
                        continue
                    tr["year"] = [g.year_of(d) for d in tr.day]
                    years = years or sorted(set(tr.year), key=lambda s: s[4:8])
                    x = tr.usd
                    eq = x.cumsum()
                    t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 else 0
                    rows.append((sname, scale, name, "buys only" if buys_only else "both ways", {y: tr[tr.year == y].usd.sum() for y in years},
                                 x.sum(), len(x), 100 * (x > 0).mean(), t, (eq - eq.cummax()).min()))
                    print(rows[-1][:4], round(x.sum()), len(x), round(t, 2), flush=True)
    L += ["| session | stop | arm | trades | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown |",
          "|---|---|---|---|" + "---|" * (len(years) + 5)]
    for s, sc, a, d, per, tot, n, win, t, dd in rows:
        L.append(f"| {s} | {sc} | {a} | {d} | " + " | ".join(f"{per.get(y, 0):+,.0f}" for y in years) +
                 f" | {tot:+,.0f} | {n} | {win:.0f}% | {t:.2f} | {dd:,.0f} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
