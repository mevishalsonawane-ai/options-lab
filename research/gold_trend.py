"""Buying gold with the trend (the owner's ask, 2026-10-01: "find the trend of the bars and buy; gold is too volatile").

    python research/gold_trend.py <xauusd_m1_bid.csv.gz> [out.md]

Buys only, positions held across days (so XM's overnight swap matters: an assumed $40 a lot a night, x3 on
Wednesday nights, as research/gold_24x5.py), every stop set by the market's own volatility (ATR) rather than a fixed
amount. Trades run on 1-hour bars (stops checked on each hour's low); signals from the chart named.

  buy and hold         the baseline: buy on the first day, sell on the last
  donchian 20/10       daily: buy a close above the last 20 days' high; sell a close below the last 10 days' low
  donchian 55/20       the same, 55 and 20 days
  supertrend 4h        Supertrend(10, 3) on 4-hour bars: in while it points up
  supertrend 1h        the same on 1-hour bars
  ema 20/50 4h         in while the 4-hour EMA 20 is above the EMA 50 (enter on the cross up, leave on the cross down)
  pullback 1h          trend = 1-hour close above EMA 200 and EMA 50 above EMA 200; buy when the close crosses back above
                       EMA 20 after a dip below it; trailing stop 3 ATR(14) under the highest close since entry
  chandelier 4h        buy a 4-hour close above the last 20 bars' high; trailing stop 3 ATR(22) under the highest high
Costs: ask = bid + 0.30, $7 a lot, plus the swap. USD per standard lot; fitting years Oct 2023 - Sep 2025, held out
Oct 2025 - Sep 2026. Gold rose about 140% over these three years, so any buy-only rule is helped; the comparison
with buying and holding is the point.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM, SWAP = 0.15, 0.07, 0.40     # USD an ounce


def ema(x, n):
    return x.ewm(span=n, adjust=False).mean()


def atr(b, n):
    pc = b.close.shift()
    tr = pd.concat([b.high - b.low, (b.high - pc).abs(), (b.low - pc).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False).mean()


def supertrend(b, n=10, k=3.0):
    a = atr(b, n).values
    h, l, c = b.high.values, b.low.values, b.close.values
    hl2 = (h + l) / 2
    ub, lb = hl2 + k * a, hl2 - k * a
    fu, fl, d = ub.copy(), lb.copy(), np.ones(len(c))
    for i in range(1, len(c)):
        fu[i] = ub[i] if (ub[i] < fu[i - 1] or c[i - 1] > fu[i - 1]) else fu[i - 1]
        fl[i] = lb[i] if (lb[i] > fl[i - 1] or c[i - 1] < fl[i - 1]) else fl[i - 1]
        d[i] = 1 if c[i] > fu[i - 1] else (-1 if c[i] < fl[i - 1] else d[i - 1])
    return pd.Series(d, index=b.index)


def nights(t0, t1):
    """Swap nights between two times: each 21:00 UTC rollover passed, Wednesday's counted three times."""
    n = 0
    d = (t0 - pd.Timedelta(hours=21)).normalize() + pd.Timedelta(hours=21)
    if d <= t0:
        d += pd.Timedelta(days=1)
    while d <= t1:
        if d.dayofweek < 5:
            n += 3 if d.dayofweek == 2 else 1
        d += pd.Timedelta(days=1)
    return n


def to_hours(sig, h1):
    """A signal on a slower chart, known only once its bar has closed, laid onto the 1-hour bars."""
    out = sig.astype(float).shift(1).reindex(h1.index, method="ffill")
    return out if sig.dtype != bool else out.fillna(0).astype(bool)


def run_state(h1, want):
    """In while [want] (on 1-hour bars, known at each bar's close) is true: enter at the next bar's open, leave at the
    next bar's open after it turns false."""
    trades, entry = [], None
    o, c, idx = h1.open.values, h1.close.values, h1.index
    w = want.fillna(False).values.astype(bool)
    for i in range(len(h1) - 1):
        if entry is None and w[i]:
            entry = (i + 1, o[i + 1] + H)
        elif entry is not None and not w[i]:
            trades.append((idx[entry[0]], entry[1], idx[i + 1], o[i + 1] - H))
            entry = None
    if entry is not None:
        trades.append((idx[entry[0]], entry[1], idx[-1], c[-1] - H))
    return trades


def run_trail(h1, enter, trail_mult, atr_s, use_high):
    trades, i, n = [], 0, len(h1)
    o, hi, lo, c, idx = h1.open.values, h1.high.values, h1.low.values, h1.close.values, h1.index
    en = enter.fillna(False).values.astype(bool)
    a = atr_s.values
    while i < n - 1:
        if not en[i]:
            i += 1
            continue
        k = i + 1
        e = o[k] + H
        best = c[k] if not use_high else hi[k]
        stop = best - trail_mult * a[i]
        exit_t, px = idx[-1], c[-1] - H
        while k < n:
            if lo[k] - H <= stop:
                exit_t, px = idx[k], min(stop, o[k] - H)
                break
            best = max(best, hi[k] if use_high else c[k])
            stop = max(stop, best - trail_mult * a[k])
            k += 1
        trades.append((idx[i + 1], e, exit_t, px))
        i = idx.get_indexer([exit_t])[0] + 1 if exit_t in idx else n
    return trades


def mtm_drawdown(trades, h1):
    """The deepest fall of the account marked to market every hour (open trades counted at each hour's bid close)."""
    c = h1.close.values - H
    pos = np.zeros(len(h1)); cost = np.zeros(len(h1))
    ix = h1.index
    for t0, e, t1, x in trades:
        i0, i1 = ix.get_indexer([t0])[0], ix.get_indexer([t1])[0]
        pos[i0:i1] = 1
        cost[i0] += (e - (c[i0 - 1] if i0 > 0 else e)) + COMM            # paid on entry against the bid mark
        cost[i1] += 0
    ret = np.r_[0, np.diff(c)] * np.r_[0, pos[:-1]] - cost
    eq = 100 * np.cumsum(ret)
    return (eq - np.maximum.accumulate(eq)).min()


def summarise(trades, fit, hold, years, start, end):
    rows = []
    for t0, e, t1, x in trades:
        sw = SWAP * nights(t0, t1)
        rows.append(dict(day=t0.date(), usd=100 * (x - e - COMM - sw), hours=(t1 - t0).total_seconds() / 3600, swap=100 * sw))
    tr = pd.DataFrame(rows)
    if tr.empty:
        return None
    r = s.stats(tr, fit, hold, years)
    r["in_market"] = 100 * min(1.0, tr.hours.sum() / ((end - start).total_seconds() / 3600))
    r["swap"] = tr.swap.sum()
    return r


def main():
    mid = s.load(sys.argv[1])
    h1 = s.bars(mid, 60)
    h4 = s.bars(mid, 240)
    d1 = s.bars(mid, 1440)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.date).unique()}, key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    start, end = h1.index[0], h1.index[-1]
    A1 = atr(h1, 14)
    variants = {}
    variants["buy and hold"] = [(h1.index[0], h1.open.iloc[0] + H, h1.index[-1], h1.close.iloc[-1] - H)]
    for n_in, n_out in ((20, 10), (55, 20)):
        hi_in = d1.high.rolling(n_in).max().shift(1)
        lo_out = d1.low.rolling(n_out).min().shift(1)
        state, inpos = [], False
        for dd in d1.index:
            if not inpos and d1.close[dd] > hi_in[dd]:
                inpos = True
            elif inpos and d1.close[dd] < lo_out[dd]:
                inpos = False
            state.append(inpos)
        variants[f"donchian {n_in}/{n_out} (daily)"] = run_state(h1, to_hours(pd.Series(state, index=d1.index), h1))
    variants["supertrend 4h"] = run_state(h1, to_hours(supertrend(h4) > 0, h1))
    variants["supertrend 1h"] = run_state(h1, supertrend(h1) > 0)
    # Is the 4-hour Supertrend a lucky setting? Its neighbours: other ATR lengths and multipliers, 2- and 8-hour bars.
    for n_, k_ in ((7, 3.0), (14, 3.0), (10, 2.0), (10, 2.5), (10, 3.5), (10, 4.0)):
        variants[f"supertrend 4h ({n_}, {k_:g})"] = run_state(h1, to_hours(supertrend(h4, n_, k_) > 0, h1))
    for tf_ in (120, 480):
        variants[f"supertrend {tf_ // 60}h"] = run_state(h1, to_hours(supertrend(s.bars(mid, tf_)) > 0, h1))
    variants["ema 20/50 4h"] = run_state(h1, to_hours(ema(h4.close, 20) > ema(h4.close, 50), h1))
    c = h1.close
    e20, e50, e200 = ema(c, 20), ema(c, 50), ema(c, 200)
    trend = (c > e200) & (e50 > e200)
    cross_back = (c > e20) & (c.shift() <= e20.shift())
    variants["pullback 1h (EMA 20 in an EMA 50/200 uptrend), 3 ATR trail"] = run_trail(h1, trend & cross_back, 3.0, A1, False)
    hi20 = h4.high.rolling(20).max().shift(1)
    above = to_hours((h4.close > hi20).astype(float), h1).fillna(0).astype(bool)
    brk = above & ~above.shift(1, fill_value=False)
    variants["chandelier 4h (20-bar high break), 3 ATR trail"] = run_trail(h1, brk, 3.0, to_hours(atr(h4, 22), h1), True)
    L = ["## Buying gold with the trend (research/gold_trend.py)", "",
         f"XAUUSD {h1.index[0]:%Y-%m-%d} .. {h1.index[-1]:%Y-%m-%d}: gold {h1.open.iloc[0]:,.0f} -> {h1.close.iloc[-1]:,.0f}. Buys only; "
         "costs and an assumed $40 a lot a night swap included. USD per standard lot.", "",
         "| rule | " + " | ".join(years) + " | 3 years | of which swap | trades | win | t | max drawdown | time in market | profit / drawdown | avg month at 0.01 lot |",
         "|---|" + "---|" * (len(years) + 10)]
    for name, trades in variants.items():
        r = summarise(trades, fit, hold, years, start, end)
        if r is None:
            continue
        r["dd"] = min(r["dd"], mtm_drawdown(trades, h1))
        ratio = r["total"] / -r["dd"] if r["dd"] < 0 else float("inf")
        L.append(f"| {name} | " + " | ".join(f"{r['per'][y]:+,.0f}" for y in years) +
                 f" | {r['total']:+,.0f} | -{r['swap']:,.0f} | {r['n']} | {r['win']:.0f}% | {r['t']:.2f} | {r['dd']:,.0f} | {r['in_market']:.0f}% | "
                 f"{ratio:.1f} | {r['total'] / 100 / 36:+,.2f} |")
        print(L[-1], flush=True)
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
