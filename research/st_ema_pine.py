"""The owner's Pine strategy "Bank Nifty Supertrend + 50 EMA" (Intraday mode), on the year.

    python research/st_ema_pine.py <banknifty_year_wide.parquet> [out.md]

Exactly as the script: Supertrend(10, 3) (Pine's ta.supertrend: hl2, RMA ATR) and EMA(50) on the chart timeframe,
computed continuously across days; long when the direction flips bullish and close > EMA, short when it flips bearish
and close < EMA, only on bars starting 09:15-15:15; strategy.entry reverses an open position; everything closed on
the 15:15 bar. Orders fill at the next bar's open (TradingView default). No stop or target - exactly as written.
Measured two ways: index points (what TradingView shows on the index; 1 lot = 30 x points), and buying the option
(long -> ATM CE, short -> ATM PE, real minute prices, nearest expiry after the day, Rs 40 + 0.5 a side).
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
from sell_levels import load  # noqa: E402

LOT, SLIP, CHG = 30, 0.5, 40.0


def bars(days, tf):
    rows = []
    for di, d in enumerate(days):
        I = d["I"]
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, I["open"][s], I["high"][s:e].max(), I["low"][s:e].min(), I["close"][e - 1]))
    return pd.DataFrame(rows, columns=["di", "s", "o", "h", "l", "c"])


def supertrend(b, period=10, mult=3.0):
    h, l, c = b.h.values, b.l.values, b.c.values
    pc = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    atr = pd.Series(tr).ewm(alpha=1 / period, adjust=False).mean().values
    src = (h + l) / 2
    up, dn = src - mult * atr, src + mult * atr
    lo, hi = up.copy(), dn.copy()
    dirn = np.ones(len(c))
    st = np.zeros(len(c))
    for i in range(1, len(c)):
        lo[i] = up[i] if (up[i] > lo[i - 1] or c[i - 1] < lo[i - 1]) else lo[i - 1]
        hi[i] = dn[i] if (dn[i] < hi[i - 1] or c[i - 1] > hi[i - 1]) else hi[i - 1]
        if st[i - 1] == hi[i - 1]:
            dirn[i] = -1 if c[i] > hi[i] else 1
        else:
            dirn[i] = 1 if c[i] < lo[i] else -1
        st[i] = lo[i] if dirn[i] == -1 else hi[i]
    return dirn


def simulate(days, tf, itm=0):
    b = bars(days, tf)
    b["ema"] = b.c.ewm(span=50, adjust=False).mean()
    b["dir"] = supertrend(b)
    ch = b["dir"].diff()
    b["buy"] = (ch < 0) & (b.c > b.ema)
    b["sell"] = (ch > 0) & (b.c < b.ema)
    sq = 15 * 60 + 15 - (9 * 60 + 15)                         # minute index of 15:15
    trades, pos = [], None                                   # pos = (sign, di, entry minute, index px, strike, option px)

    def close(di, m, why):
        nonlocal pos
        sign, pdi, em, ipx, k, opx, right = pos
        d = days[di]
        ix_exit = d["I"]["open"][min(m, 374)]
        leg = d["chain"].get((k, right)) if di == pdi else None
        onet = (leg["open"][min(m, 374)] - SLIP - opx) * LOT - CHG if leg is not None else np.nan
        trades.append(dict(day=days[pdi]["day"], sign=sign, pts=sign * (ix_exit - ipx), opt=onet, why=why, held=m - em))
        pos = None

    def open_(sign, di, m):
        nonlocal pos
        d = days[di]
        ipx = d["I"]["open"][m]
        right = "CE" if sign > 0 else "PE"
        ks = np.array(sorted({k for k, r in d["chain"] if r == right}))
        k = ks[np.argmin(np.abs(ks - (ipx - sign * itm)))]
        leg = d["chain"][(k, right)]
        pos = (sign, di, m, ipx, k, leg["open"][m] + SLIP, right)

    for i in range(len(b) - 1):
        r = b.iloc[i]
        nxt = b.iloc[i + 1]
        if pos is not None and pos[1] != r.di:              # never carried overnight (square-off below)
            pos = None
        in_sess = r.s <= sq
        is_end = r.s <= sq < r.s + tf
        sig = 1 if (r.buy and in_sess) else (-1 if (r.sell and in_sess) else 0)
        if nxt.di != r.di:
            if pos is not None:
                close(r.di, 374, "day end")
            continue
        if sig != 0 and (pos is None or pos[0] != sig):
            if pos is not None:
                close(r.di, int(nxt.s), "reversed")
            if not is_end:
                open_(sig, r.di, int(nxt.s))
        if is_end and pos is not None:
            close(r.di, int(nxt.s), "15:15 square-off")
    return pd.DataFrame(trades)


def line(tr, alld, label, col):
    half = set(alld[: len(alld) // 2])
    x = tr[col].dropna()
    t = x.mean() / (x.std(ddof=1) / np.sqrt(len(x)))
    unit = "pts" if col == "pts" else "Rs"
    tot = x.sum() * (LOT if col == "pts" else 1)
    h1 = tr[tr.day.isin(half)][col].sum() * (LOT if col == "pts" else 1)
    h2 = tr[~tr.day.isin(half)][col].sum() * (LOT if col == "pts" else 1)
    m = tr.assign(m=[str(d)[:7] for d in tr.day]).groupby("m")[col].sum()
    return (f"| {label} | {len(x)} | {100 * (x > 0).mean():.0f}% | {x.mean():+.1f} {unit} | {t:.2f} | "
            f"Rs {tot:,.0f} | Rs {h1:,.0f} / {h2:,.0f} | {(m > 0).sum()}/{len(m)} |")


def main():
    path = sys.argv[1]
    days = load(path)
    alld = [d["day"] for d in days]
    head = "| timeframe / instrument | trades | win | per trade | t | net for the year (1 lot) | 1st / 2nd half | green months |"
    sep = "|---|---|---|---|---|---|---|---|"
    out = [f"## Supertrend(10,3) + 50 EMA (the owner's Pine script), BANKNIFTY {alld[0]} .. {alld[-1]} ({len(alld)} days)", "",
           "Index points x 30 = what the script earns on one lot of futures BEFORE costs (TradingView's view of the index); "
           "futures costs would take roughly Rs 100-150 a round trip more.", "", head, sep]
    for tf in (3, 5, 15):
        tr = simulate(days, tf)
        out.append(line(tr, alld, f"{tf}-min, index points", "pts"))
        out.append(line(tr, alld, f"{tf}-min, ATM option bought", "opt"))
        tr2 = simulate(days, tf, itm=200)
        out.append(line(tr2, alld, f"{tf}-min, option 2 strikes ITM", "opt"))
        why = tr.why.value_counts(normalize=True)
        out.append(f"| {tf}-min exits: " + ", ".join(f"{k} {100 * v:.0f}%" for k, v in why.items()) +
                   f"; median hold {tr.held.median():.0f} min | | | | | | | |")
        print("\n".join(out[-4:]), flush=True)
    text = "\n".join(out)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
