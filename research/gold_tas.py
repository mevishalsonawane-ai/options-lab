"""The "Trend Analysis Strategy" (TAS, Pine v6) on XAUUSD - the owner's ask, 2026-10-02: 15-minute chart, ATR period 19.

    python research/gold_tas.py <xauusd_m1_bid.csv.gz> [out.md]

The strategy, as pasted (defaults unless said; the paste ends before the entry / exit block, so that part follows the
inputs' own descriptions):
  tracker   ATR = WMA(true range, N) (N = 19, the owner's; 14 the default), up = high + 2.2 ATR, down = low - 2.2 ATR;
            in an up-trend the line is max(line, down) and a close under it turns the trend down (the line jumps to up),
            and the mirror in a down-trend
  score     six votes of +1 / -1: tracker direction, close vs equilibrium (HMA(9) of the 55-bar linear regression),
            equilibrium slope, RSI(14) vs 50, RSI vs its 14-bar average, +DI vs -DI (14); as a % of 6
  entry     the tracker turning up with the score >= +50% (the only filter on by default); "delayed entry": if the
            score blocks it, a later bar of the same up-trend within 10 bars once the score passes. BUY at the next
            bar's open (process_orders_on_close off)
  stop      the tracker line at the signal; R = entry - stop
  targets   1.5 R (33%), 2.5 R (33%), 3.5 R (the rest); after the first target the stop moves to the entry
  exits     also the tracker turning down (out at the next bar's open)
Costs as in the other gold studies: ask = mid + 0.15, bid = mid - 0.15, $7 a lot. Stop first when a bar touches both.
Fitting years Oct 2023 - Sep 2025, held out Oct 2025 - Sep 2026. USD per standard lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07
MULT, EQ_LEN, EQ_SMOOTH, RSI_LEN, DMI_LEN, MIN_SCORE, LATE = 2.2, 55, 9, 14, 14, 50.0, 10
TPS = ((1.5, 0.33), (2.5, 0.33), (3.5, 0.34))


def rma(x, n):
    return x.ewm(alpha=1 / n, adjust=False).mean()


def wma(x, n):
    w = np.arange(1, n + 1, dtype=float)
    return x.rolling(n).apply(lambda v: np.dot(v, w) / w.sum(), raw=True)


def indicators(b, n):
    o, h, l, c = b.open, b.high, b.low, b.close
    tr = pd.concat([h - l, (h - c.shift()).abs(), (l - c.shift()).abs()], axis=1).max(axis=1)
    tr.iloc[0] = h.iloc[0] - l.iloc[0]
    a = wma(tr, n).values
    hv, lv, cv = h.values, l.values, c.values
    trk = np.full(len(c), np.nan); d = np.ones(len(c), dtype=int)
    t, di = np.nan, 1
    for i in range(len(c)):
        if not np.isfinite(a[i]):
            d[i] = di; continue
        up, dn = hv[i] + a[i] * MULT, lv[i] - a[i] * MULT
        if not np.isfinite(t):
            t = dn
        elif di == 1:
            t = max(t, dn)
            if cv[i] < t:
                di, t = -1, up
        else:
            t = min(t, up)
            if cv[i] > t:
                di, t = 1, dn
        trk[i], d[i] = t, di
    # equilibrium: HMA(9) of the 55-bar regression line's last value
    x = np.arange(EQ_LEN, dtype=float)
    def lr(v):
        s, i0 = np.polyfit(x, v, 1)
        return i0 + s * (EQ_LEN - 1)
    reg = c.rolling(EQ_LEN).apply(lr, raw=True)
    eq = wma(2 * wma(reg, EQ_SMOOTH // 2) - wma(reg, EQ_SMOOTH), int(round(EQ_SMOOTH ** 0.5)))
    dd = c.diff()
    rsi = 100 - 100 / (1 + rma(dd.clip(lower=0), RSI_LEN) / rma(-dd.clip(upper=0), RSI_LEN))
    rsim = rsi.rolling(14).mean()
    upm, dnm = h.diff(), -l.diff()
    plus = pd.Series(np.where((upm > dnm) & (upm > 0), upm, 0.0), index=c.index)
    minus = pd.Series(np.where((dnm > upm) & (dnm > 0), dnm, 0.0), index=c.index)
    atrd = rma(tr, DMI_LEN)
    pdi, mdi = rma(plus, DMI_LEN) / atrd, rma(minus, DMI_LEN) / atrd
    sgn = lambda cond: np.where(cond, 1, -1)
    score = d + sgn(c > eq) + sgn(eq > eq.shift()) + sgn(rsi > 50) + sgn(rsi > rsim) + sgn(pdi > mdi)
    ok = np.isfinite(eq.values) & np.isfinite(rsim.values) & np.isfinite(trk)
    return trk, d, np.where(ok, score / 6 * 100, np.nan)


def trades(b, trk, d, pct, side=1, late=True, day_end=None):
    """Buys (side 1) or sells (-1): a list of (signal time, USD a standard lot). [day_end]: True on each day's last
    candle - out at its close, and no buy on it (intraday use, research/tas_indices.py)."""
    o, h, l, c = (b[k].values for k in ("open", "high", "low", "close"))
    n, out, i, flip_at = len(c), [], 1, None
    while i < n - 1:
        if d[i] == side and d[i - 1] != side:
            flip_at = i
        if d[i] != side:
            flip_at = None
        go = flip_at is not None and (i - flip_at <= (LATE if late else 0)) and np.isfinite(pct[i]) and side * pct[i] >= MIN_SCORE
        if not go or (day_end is not None and day_end[i]):
            i += 1; continue
        e = o[i + 1] + side * H
        stop = trk[i]
        r = side * (e - stop)
        if r <= 0:
            i += 1; continue
        left, pnl, be, j, done = 1.0, 0.0, False, i + 1, False
        hit = [False] * len(TPS)
        while j < n:
            lo, hi = (l[j] - H, h[j] - H) if side > 0 else (l[j] + H, h[j] + H)
            ox = o[j] - side * H
            if (side > 0 and lo <= stop) or (side < 0 and hi >= stop):
                px = min(stop, ox) if side > 0 else max(stop, ox)
                pnl += left * side * (px - e); left = 0; done = True; break
            moved = False
            for k, (m, q) in enumerate(TPS):
                tgt = e + side * m * r
                if not hit[k] and ((side > 0 and hi >= tgt) or (side < 0 and lo <= tgt)):
                    q = left if k == len(TPS) - 1 else q
                    pnl += q * side * (tgt - e); left -= q; hit[k] = True; moved = True
            if left <= 1e-9:
                done = True; break
            if moved and not be:
                stop, be = e, True          # breakeven from the next bar
            if day_end is not None and day_end[j]:
                pnl += left * side * (c[j] - side * H - e); left = 0; done = True; break
            if d[j] != side and j + 1 < n:  # the tracker turned: out at the next open
                pnl += left * side * (o[j + 1] - side * H - e); left = 0; j += 1; done = True; break
            j += 1
        if not done:
            pnl += left * side * (c[n - 1] - side * H - e)
        out.append((b.index[i], 100 * (pnl - COMM)))
        flip_at = None
        i = j + 1 if d[min(j, n - 1)] == side else j  # one trade per up-trend
        # stay out until the next flip
        while i < n and d[i] == side and d[i - 1] == side:
            i += 1
    return out


def main():
    df = pd.read_csv(sys.argv[1])
    df["ts"] = pd.to_datetime(df.timestamp, unit="ms")
    m = df.set_index("ts")[["open", "high", "low", "close"]].sort_index()
    m = m[~m.index.duplicated()]
    m += H
    m = m[m.index.dayofweek < 5]
    years = sorted({g.year_of(dd) for dd in pd.Series(m.index.date).unique()}, key=lambda x: x[4:8])
    L = ["## \"Trend Analysis Strategy\" on XAUUSD (research/gold_tas.py)", "",
         f"{m.index[0]:%Y-%m-%d} .. {m.index[-1]:%Y-%m-%d}. Its own stop (tracker line), targets 1.5 / 2.5 / 3.5 R, breakeven after "
         "the first, out when the tracker turns. Costs included. USD per standard lot; the last year is held out.", "",
         "| chart | tracker ATR | direction | entries | " + " | ".join(years) + " | 3 years | trades | win | t | deepest drawdown | avg month at 0.01 lot |",
         "|---|---|---|---|" + "---|" * (len(years) + 5)]
    for tf in (15, 30, 60, 240):
        b = m.resample(f"{tf}min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
        b = b[b.index.hour != 21]
        for n_atr in ((19, 14) if tf == 15 else (19,)):
            trk, d, pct = indicators(b, n_atr)
            for dname, sides in (("buys only", (1,)), ("both ways", (1, -1))):
                for late in ((True, False) if tf == 15 else (True,)):
                    tr = sorted(sum((trades(b, trk, d, pct, sd, late) for sd in sides), []))
                    if not tr:
                        continue
                    p = np.array([x for _, x in tr]); y = np.array([g.year_of(t.date()) for t, _ in tr])
                    per = {yy: p[y == yy].sum() for yy in years}
                    eqc = np.cumsum(p); dd = (eqc - np.maximum.accumulate(eqc)).min()
                    t = p.mean() / (p.std(ddof=1) / len(p) ** 0.5) if len(p) > 1 else 0
                    lab = f"{tf}m" if tf < 60 else f"{tf // 60}h"
                    L.append(f"| {lab} | {n_atr} | {dname} | {'flip + delayed' if late else 'flip only'} | " +
                             " | ".join(f"{per[yy]:+,.0f}" for yy in years) + f" | {p.sum():+,.0f} | {len(p)} | {100 * (p > 0).mean():.0f}% | "
                             f"{t:.2f} | {dd:,.0f} | {p.sum() / 100 / 36:+,.1f} |")
                    print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
