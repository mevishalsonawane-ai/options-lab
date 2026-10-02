"""ChalkBoardAnalytics' "AI's Opinion Trading System V5" (TradingView, MPL-2.0) on XAUUSD - the owner's ask, 2026-10-02.

    python research/gold_ai_opinion.py <xauusd_m1_bid.csv.gz> [out.md]

The indicator, as written (defaults; note its presets set variables that are never used, so the inputs rule:
confirmation 2 bars, ADX 20, ATR 14, volume lookback 20, RSI flat band 45-55, view mode Majority):
  raw consensus  majority of five votes on the bar: SMA5/20 cross, EMA9/21 cross, RSI(14) crossing 50, MACD(3,6,5)
                 cross (each +1 up / -1 down / 0), and "ADX > 20" (+1 only - it never votes down)
  view           majority of: raw consensus, volume delta (the bar's tick volume, + if the close rose, - if it fell),
                 sentiment (close minus the middle of the last 50 bars' average high and average low)
  confirmed      the view, if the last 2 bars' views agree
  Robust signal  confirmed AND ADX > 20 AND ATR(14) > its 20-bar average AND RSI outside 45-55 AND volume > its
                 20-bar average   (Standard: confirmed alone; Freedom: confirmed AND any one of those four)
  trade          its own levels: stop 1 ATR below the close, target 2 ATRs above (mirrored for sells)
Its "Buy at $..." line needs a confirmed buy whose previous bar's view was not a buy - impossible with 2-bar
confirmation - so the entry tested is the signal turning to buy (the bar before was not a buy signal). The part of the
script that plots arrows was not in the paste.
Trades: BUY at the next bar's open (ask = mid + 0.15), out at the stop or the target checked on that timeframe's bars
(stop first when both are touched in one bar), or after 100 bars; $7 a lot. Dukascopy tick volume as the volume.
Fitting years Oct 2023 - Sep 2025, held out Oct 2025 - Sep 2026. USD per standard lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import liquidity_gold as g  # noqa: E402

H, COMM, MAXH = 0.15, 0.07, 100


def rma(x, n):
    return x.ewm(alpha=1 / n, adjust=False).mean()


def cross(a, b):
    up = (a > b) & (a.shift() <= b.shift())
    dn = (a < b) & (a.shift() >= b.shift())
    return up.astype(int) - dn.astype(int)


def signals(b):
    c, h, l, v = b.close, b.high, b.low, b.volume
    sma = cross(c.rolling(5).mean(), c.rolling(20).mean())
    ema = cross(c.ewm(span=9, adjust=False).mean(), c.ewm(span=21, adjust=False).mean())
    d = c.diff()
    rsi = 100 - 100 / (1 + rma(d.clip(lower=0), 14) / rma(-d.clip(upper=0), 14))
    rsix = cross(rsi, pd.Series(50.0, index=c.index))
    fast, slow = c.ewm(span=3, adjust=False).mean(), c.ewm(span=6, adjust=False).mean()
    macd = fast - slow
    macdx = cross(macd, macd.ewm(span=5, adjust=False).mean())
    upm, dnm = h.diff(), -l.diff()
    plus = np.where((upm > dnm) & (upm > 0), upm, 0.0)
    minus = np.where((dnm > upm) & (dnm > 0), dnm, 0.0)
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    pdi = 100 * rma(pd.Series(plus, index=c.index), 14) / rma(tr, 14)
    mdi = 100 * rma(pd.Series(minus, index=c.index), 14) / rma(tr, 14)
    adx = rma(100 * (pdi - mdi).abs() / (pdi + mdi), 14)
    adxs = (adx > 20).astype(int)
    votes = pd.concat([sma, ema, rsix, macdx, adxs], axis=1)
    ai = np.sign((votes == 1).sum(axis=1) - (votes == -1).sum(axis=1))
    delta = np.sign(d.fillna(0)) * v
    senti = c - (h.rolling(50).mean() + l.rolling(50).mean()) / 2
    view = np.sign((ai == 1).astype(int) + (delta > 0).astype(int) + (senti > 0).astype(int)
                   - (ai == -1).astype(int) - (delta < 0).astype(int) - (senti < 0).astype(int))
    conf = view.where(view == view.shift(), 0)
    atr = rma(tr, 14)
    f1, f2, f3, f4 = adx > 20, atr > atr.rolling(20).mean(), (rsi < 45) | (rsi > 55), v > v.rolling(20).mean()
    robust = conf.where(f1 & f2 & f3 & f4, 0)
    freedom = conf.where(f1 | f2 | f3 | f4, 0)
    return {"Robust": robust, "Standard": conf, "Freedom": freedom}, atr


def trade(b, sig, atr, side):
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    s, a = sig.values, atr.values
    n, out, i = len(c), [], 1
    while i < n - 1:
        if not (s[i] == side and s[i - 1] != side) or not np.isfinite(a[i]):
            i += 1; continue
        e = o[i + 1] + side * H
        stop, tgt = c[i] - side * a[i], c[i] + 2 * side * a[i]
        j, px = i + 1, None
        while j < min(n, i + 1 + MAXH):
            lo, hi = (l[j] - H, h[j] - H) if side > 0 else (l[j] + H, h[j] + H)
            if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                px = stop; break
            if (side > 0 and hi >= tgt) or (side < 0 and lo <= tgt):
                px = tgt; break
            j += 1
        if px is None:
            j = min(n, i + 1 + MAXH) - 1; px = c[j] - side * H
        out.append((b.index[i], 100 * (side * (px - e) - COMM)))
        i = j + 1
    return out


def main():
    df = pd.read_csv(sys.argv[1])
    df["ts"] = pd.to_datetime(df.timestamp, unit="ms")
    m = df.set_index("ts")[["open", "high", "low", "close", "volume"]].sort_index()
    m = m[~m.index.duplicated()]
    m[["open", "high", "low", "close"]] += H
    m = m[m.index.dayofweek < 5]
    years = sorted({g.year_of(d) for d in pd.Series(m.index.date).unique()}, key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    L = ["## \"AI's Opinion Trading System V5\" on XAUUSD (research/gold_ai_opinion.py)", "",
         f"{m.index[0]:%Y-%m-%d} .. {m.index[-1]:%Y-%m-%d}. Stop 1 ATR, target 2 ATRs (the indicator's own), costs included. USD per standard lot.", "",
         "| timeframe | preset | direction | " + " | ".join(years) + " | 3 years | trades | win | t | deepest drawdown | avg month at 0.01 lot |",
         "|---|---|---|" + "---|" * (len(years) + 5)]
    for tf in (5, 15, 30, 60, 240):
        b = m.resample(f"{tf}min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last", "volume": "sum"}).dropna()
        b = b[b.index.hour != 21]
        sigs, atr = signals(b)
        for name, sig in sigs.items():
            for dname, sides in (("buys only", (1,)), ("both ways", (1, -1))):
                tr = sorted(sum((trade(b, sig, atr, sd) for sd in sides), []))
                if not tr:
                    continue
                t_idx = [t for t, _ in tr]; p = np.array([x for _, x in tr])
                y = np.array([g.year_of(t.date()) for t in t_idx])
                per = {yy: p[y == yy].sum() for yy in years}
                eq = np.cumsum(p); dd = (eq - np.maximum.accumulate(eq)).min()
                t = p.mean() / (p.std(ddof=1) / len(p) ** 0.5) if len(p) > 1 else 0
                L.append(f"| {tf if tf < 60 else str(tf // 60) + 'h'}{'m' if tf < 60 else ''} | {name} | {dname} | " +
                         " | ".join(f"{per[yy]:+,.0f}" for yy in years) + f" | {p.sum():+,.0f} | {len(p)} | {100 * (p > 0).mean():.0f}% | "
                         f"{t:.2f} | {dd:,.0f} | {p.sum() / 100 / 36:+,.1f} |")
                print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
