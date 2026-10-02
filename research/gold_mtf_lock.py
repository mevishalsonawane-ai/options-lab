"""The 1h + 30m candidate with profit locks and stops (the owner's ask, 2026-10-02: "add profit lock to this strategy
and check again"). Entry as research/GOLD_30M_1H_PATTERNS.md: two green 1-hour candles, then a red 30-minute candle,
then a green one -> BUY at the next 30-minute open (ask).

    python research/gold_mtf_lock.py <xauusd_m1_bid.csv.gz> [out.md]

A = the 30-minute ATR(14) at the entry (~$8-15 an ounce here). Every exit is checked on 30-minute candles (the bid):
  stop      none / 1 / 1.5 / 2 x A under the entry / under the red pullback candle's low
  lock      none / breakeven once 1 A up / giveback K = 1, 1.5, 2, 3 A from the top once 1 A up / keep half once 2 A up
  time      out after 8 / 16 / 32 candles (4 / 8 / 16 hours), or at the daily break / the weekend, whichever first
A stop or lock is filled at its level, or at the candle's open if it opened through it; the stop is checked before the
top is raised within a candle (the pessimistic order). Costs: ask = bid + 0.30, $7 a lot; no swap (the trades end at
the daily break). Chosen on the fitting years (Oct 2023 - Sep 2025) by profit / drawdown, read on the held-out year.
USD per standard lot.
"""
from __future__ import annotations

import itertools
import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gold_session_strats as s  # noqa: E402
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07


def main():
    mid = s.load(sys.argv[1])
    m30 = s.bars(mid, 30); m30 = m30[m30.index.hour != 21]
    h1 = s.bars(mid, 60); h1 = h1[h1.index.hour != 21]
    o, hi, lo, c = (m30[k].values for k in ("open", "high", "low", "close"))
    idx = m30.index; n = len(c)
    yr = np.array([g.year_of(d) for d in idx.date]); years = sorted(set(yr), key=lambda x: x[4:8])
    fit, hold = years[:-1], years[-1:]
    tr = np.maximum(hi - lo, np.maximum(np.abs(hi - np.r_[c[0], c[:-1]]), np.abs(lo - np.r_[c[0], c[:-1]])))
    atr = pd.Series(tr).ewm(alpha=1 / 14, adjust=False).mean().values
    col = np.sign(c - o)
    hc = np.sign(h1.close.values - h1.open.values)
    hend = (h1.index + pd.Timedelta(hours=1)).values
    lh = np.searchsorted(hend, (idx + pd.Timedelta(minutes=30)).values, side="right") - 1
    gg = (lh >= 1) & (hc[np.maximum(lh, 0)] > 0) & (hc[np.maximum(lh - 1, 0)] > 0)
    sig = gg & (col > 0) & (np.r_[0, col[:-1]] < 0)
    gap_after = np.r_[(idx[1:] - idx[:-1]) > pd.Timedelta(minutes=60), True]   # the last candle before a break

    def run(stop_kind, stop_x, lock, lock_x, maxn):
        out, i = [], 0
        while i < n - 1:
            if not sig[i] or gap_after[i]:
                i += 1; continue
            k = i + 1
            e = o[k] + H; a = atr[i]
            stop = -np.inf
            if stop_kind == "atr":
                stop = e - stop_x * a
            elif stop_kind == "pullback":
                stop = lo[i - 1] - H - 0.10
            peak = -np.inf; end = None
            for j in range(k, min(k + maxn, n)):
                bl, bh, bo = lo[j] - H, hi[j] - H, o[j] - H
                if bl <= stop:
                    end = (j, min(stop, bo), "stop/lock"); break
                peak = max(peak, bh); gain = peak - e
                if lock == "breakeven" and gain >= a:
                    stop = max(stop, e + COMM)
                elif lock == "giveback" and gain >= a:
                    stop = max(stop, peak - lock_x * a)
                elif lock == "half" and gain >= 2 * a:
                    stop = max(stop, e + 0.5 * gain)
                if gap_after[j]:
                    end = (j, c[j] - H, "break"); break
            if end is None:
                j = min(k + maxn, n) - 1; end = (j, c[j] - H, "time")
            out.append((i, 100 * (end[1] - e - COMM)))
            i = end[0] + 1
        return out

    rows = []
    stops = [("none", 0)] + [("atr", x) for x in (1, 1.5, 2)] + [("pullback", 0)]
    locks = [("none", 0), ("breakeven", 0), ("half", 0)] + [("giveback", x) for x in (1, 1.5, 2, 3)]
    for (sk, sx), (lk, lx), maxn in itertools.product(stops, locks, (8, 16, 32)):
        t = run(sk, sx, lk, lx, maxn)
        p = np.array([v for _, v in t]); y = yr[[i for i, _ in t]]
        per = {yy: p[y == yy].sum() for yy in years}
        fm = np.isin(y, fit)
        eqf = np.cumsum(p[fm]); ddf = (eqf - np.maximum.accumulate(eqf)).min()
        eqh = np.cumsum(p[~fm]); ddh = (eqh - np.maximum.accumulate(eqh)).min() if (~fm).any() else 0
        eq = np.cumsum(p); dd = (eq - np.maximum.accumulate(eq)).min()
        tt = lambda x: x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 else 0
        name = ("stop " + ("none" if sk == "none" else ("pullback low" if sk == "pullback" else f"{sx:g} ATR")) + ", lock " +
                ({"none": "none", "breakeven": "breakeven at 1 ATR", "half": "keep half at 2 ATR"}.get(lk) or f"giveback {lx:g} ATR") +
                f", max {maxn // 2} h")
        rows.append(dict(name=name, per=per, total=p.sum(), n=len(p), win=100 * (p > 0).mean(), t=tt(p), tf=tt(p[fm]), th=tt(p[~fm]),
                         fit=p[fm].sum(), hold=p[~fm].sum(), ddf=ddf, ddh=ddh, dd=dd, worst=p.min(),
                         score=p[fm].sum() / -ddf if ddf < 0 else 0, base=(sk == "none" and lk == "none")))
        print(name, round(p[fm].sum()), round(p[~fm].sum()), round(dd), flush=True)
    base = next(r for r in rows if r["base"] and "max 4 h" in r["name"])
    ranked = sorted([r for r in rows if all(r["per"][y] > 0 for y in fit)], key=lambda r: -r["score"])
    hdr = "| version | " + " | ".join(years) + " | 3 years | trades | win | t | worst trade | deepest drawdown | fitting profit / drawdown |"
    sep = "|---|" + "---|" * (len(years) + 7)

    def line(r):
        return (f"| {r['name']} | " + " | ".join(f"{r['per'][y]:+,.0f}" for y in years) + f" | {r['total']:+,.0f} | {r['n']} | "
                f"{r['win']:.0f}% | {r['t']:.2f} | {r['worst']:,.0f} | {r['dd']:,.0f} | {r['score']:.2f} |")
    beat = [r for r in ranked if r["score"] > base["score"]]
    L = ["## The 1h + 30m candidate with profit locks and stops (research/gold_mtf_lock.py)", "",
         f"{len(rows)} versions; {len(ranked)} positive in both fitting years. Baseline (no stop, no lock, out after 4 hours or at the break):", "",
         hdr, sep, line(base), "",
         f"Top 25 by the fitting years' profit / drawdown ({len(beat)} beat the baseline there; of those "
         f"{sum(1 for r in beat if r['hold'] > base['hold'])} also made more in the held-out year and "
         f"{sum(1 for r in beat if r['ddh'] > base['ddh'])} fell less in it):", "", hdr, sep] + [line(r) for r in ranked[:25]]
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
