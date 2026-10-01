"""Candle patterns on XAUUSD (the owner's ask, 2026-10-01: "find any candle pattern"), to see whether one adds trades
with an edge beside IraGoldAlgo's 1-hour liquidity arm.

    python research/gold_candles.py <xauusd_m1_bid.csv.gz> [out.md]

Patterns, on 1-hour and 15-minute candles (mid price), signal at the pattern's close, entry at the next candle's open:
  bullish engulfing   a red candle, then a green one whose body covers the red body
  hammer / pin bar    lower wick >= 2x the body, upper wick <= the body, close in the top third
  inside-bar break    a candle inside the one before; the next closes above the outer candle's high
  morning star        a big red candle, a small one, then a green close above the red candle's midpoint
  three soldiers      three green candles, each closing higher and near its high
  outside up          a candle whose range covers the previous one's and closes in its top quarter
The mirror patterns give sells (shown "both ways" for reference; IraGoldAlgo only buys). Each with and without a trend
context (close above the 50-candle EMA for buys). Stop just under the pattern's low; exit at a 1R or 2R target, or
after 4 candles; out by 20:40 UTC and at the day's end. Weekdays, any hour but gold's 21:00-22:00 UTC break.
Judged as before: fitting years Oct 2023 - Sep 2025, then unchanged on the held-out Oct 2025 - Sep 2026; costs: ask =
bid + 0.30, $7 a lot. USD per standard lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402


def patterns(b):
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    n = len(c)
    body = np.abs(c - o); rng = h - l
    green, red = c > o, c < o
    up_wick = h - np.maximum(o, c); lo_wick = np.minimum(o, c) - l
    sh = lambda x, k=1: np.r_[np.full(k, np.nan), x[:-k]]
    o1, h1, l1, c1 = sh(o), sh(h), sh(l), sh(c)
    o2, c2, h2, l2 = sh(o, 2), sh(c, 2), sh(h, 2), sh(l, 2)
    green1, red1 = sh(green.astype(float)) == 1, sh(red.astype(float)) == 1
    green2, red2 = sh(green.astype(float), 2) == 1, sh(red.astype(float), 2) == 1
    avg = pd.Series(body).rolling(20).mean().values
    near_hi = (h - c) <= 0.25 * np.where(rng > 0, rng, np.nan)
    near_lo = (c - l) <= 0.25 * np.where(rng > 0, rng, np.nan)
    with np.errstate(invalid="ignore"):
        P = {
            "bullish engulfing": (red1 & green & (c >= o1) & (o <= c1), green1 & red & (c <= o1) & (o >= c1)),
            "hammer / pin bar": ((lo_wick >= 2 * body) & (up_wick <= body) & (c >= l + 2 * rng / 3) & (rng > 0),
                                 (up_wick >= 2 * body) & (lo_wick <= body) & (c <= h - 2 * rng / 3) & (rng > 0)),
            "inside-bar break": ((h1 <= h2) & (l1 >= l2) & (c > h2), (h1 <= h2) & (l1 >= l2) & (c < l2)),
            "morning star": (red2 & (np.abs(o2 - c2) > avg) & (np.abs(c1 - o1) < 0.5 * avg) & green & (c > (o2 + c2) / 2),
                             green2 & (np.abs(o2 - c2) > avg) & (np.abs(c1 - o1) < 0.5 * avg) & red & (c < (o2 + c2) / 2)),
            "three soldiers": (green & green1 & green2 & (c > c1) & (c1 > c2) & near_hi,
                               red & red1 & red2 & (c < c1) & (c1 < c2) & near_lo),
            "outside up": ((h > h1) & (l < l1) & (c >= h - 0.25 * rng), (h > h1) & (l < l1) & (c <= l + 0.25 * rng)),
        }
    # the pattern's low / high for the stop: the lowest low of its candles (3 candles back at most)
    lo3 = np.fmin(l, np.fmin(l1, np.nan_to_num(l2, nan=np.inf)))
    hi3 = np.fmax(h, np.fmax(h1, np.nan_to_num(h2, nan=-np.inf)))
    return P, lo3, hi3


def run(M, b, tf, sig_long, sig_short, lo3, hi3, ema50, trend, exit_mode):
    out = []
    idx = b.index
    starts = np.searchsorted(M.t, idx.values)
    mod = (idx.hour * 60 + idx.minute).values
    c = b.close.values
    busy = -1
    for j in range(55, len(b) - 1):
        side = 1 if sig_long[j] else -1 if sig_short[j] else 0
        if side == 0:
            continue
        m_next = mod[j] + tf
        if (21 * 60 <= m_next < 22 * 60) or m_next >= 20 * 60 + 40 or idx[j].dayofweek > 4:
            continue
        if trend and ((side > 0 and not c[j] > ema50[j]) or (side < 0 and not c[j] < ema50[j])):
            continue
        i0 = starts[j + 1]
        if i0 <= busy or i0 >= len(M.t) or M.day[i0] != M.day[starts[j]]:
            continue
        e = M.o[i0] + side * s.H
        stop = (lo3[j] - 0.10) if side > 0 else (hi3[j] + 0.10)
        risk = side * (e - stop)
        if not risk > 0.3:
            continue
        target, exit_at = None, None
        if exit_mode == "1R":
            target = e + side * risk
        elif exit_mode == "2R":
            target = e + side * 2 * risk
        else:
            exit_at = i0 + 4 * tf
        usd, why, ie = s.walk(M, i0, side, stop, target, exit_at)
        out.append(dict(day=idx[j].date(), side=side, usd=100 * usd))
        busy = ie
    return pd.DataFrame(out)


def main():
    mid = s.load(sys.argv[1])
    M = s.Minutes(mid)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.date).unique()}, key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    rows = []
    for tf in (60, 15):
        b = s.bars(mid, tf)
        P, lo3, hi3 = patterns(b)
        ema50 = b.close.ewm(span=50, adjust=False).mean().values
        for name, (lg, sh_) in P.items():
            for trend in (False, True):
                for ex in ("1R", "2R", "4 candles"):
                    tr = run(M, b, tf, lg, sh_, lo3, hi3, ema50, trend, ex)
                    for d, sub in (("buys only", tr[tr.side > 0] if len(tr) else tr), ("both ways", tr)):
                        r = s.stats(sub, fit, hold, years)
                        if r:
                            label = f"{'1h' if tf == 60 else '15m'} {name}" + (", trend" if trend else "") + f", exit {ex}"
                            rows.append((label, d, r))
                            print(label, d, round(r["fit"]), round(r["hold"]), r["n"], flush=True)
    rows.sort(key=lambda x: -x[2]["fit"])
    ok = [x for x in rows if x[2]["fit"] > 0 and x[2]["hold"] > 0 and x[2]["n"] >= 100]
    L = ["## Candle patterns on XAUUSD (research/gold_candles.py)", "",
         f"Dukascopy XAUUSD 1-minute, {mid.index.min():%Y-%m-%d} .. {mid.index.max():%Y-%m-%d}; costs included. "
         "Fitting years " + ", ".join(fit) + "; held out " + ", ".join(hold) + ".", "",
         f"{len(rows)} versions; {sum(1 for x in rows if x[2]['total'] > 0)} positive over three years; {len(ok)} positive in both the "
         "fitting years and the held-out year with 100+ trades.", "",
         "| pattern | direction | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 6)]
    for name, d, r in rows:
        L.append(f"| {name} | {d} | " + " | ".join(f"{r['per'][y]:+,.0f}" for y in years) +
                 f" | {r['total']:+,.0f} | {r['n']} | {r['win']:.0f}% | {r['t']:.2f} | {r['dd']:,.0f} | {r['total'] / 100 / 36:+,.2f} |")
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)
    print("\n".join(L[:20]))


if __name__ == "__main__":
    main()
