"""Scalping XAUUSD: what the 1-minute bars say, and a search for a scalping rule that survives costs (the owner's ask,
2026-10-01: "study the data and the bars and give me the best scalping strategy").

    python research/gold_scalp.py <xauusd_m1_bid.csv.gz> [out.md]

Part 1, the bars: by UTC hour, the typical 1- and 5-minute move against the cost of one trade (0.30 spread + $0.07 an
ounce commission = $0.37), the average drift, and whether moves continue or reverse (lag-1 autocorrelation).

Part 2, the rules (5-minute and 1-minute bars, London + New York hours 07:00-17:00 UTC, one position at a time, entry at
the next bar's open, exits on 1-minute bars: target / stop in multiples of the 14-bar ATR, or a 12-bar time stop):
  breakout      close above the last N bars' high -> long (below the low -> short)        N = 10, 20
  fade          close below the last N bars' low -> long (above the high -> short)        N = 10, 20
  rsi2 revert   RSI(2) < 10 -> long, > 90 -> short
  rsi2 follow   RSI(2) > 90 -> long, < 10 -> short
  big bar       a bar's body > 2 ATR: follow it / fade it
  session ORB   the first 15 minutes after 07:00 or 13:30 UTC; a close beyond -> that way
  exits         target/stop = 1/1, 1.5/1, 1/1.5 ATR
Each rule is judged on the first two years (fitting) and, unchanged, on the third (held out). A rule is worth anything
only if it is positive in both, with enough trades; the table is sorted by the fitting years, so the held-out column
shows what fitting alone would have picked. Costs as IraGoldAlgo's research: ask = bid + 0.30, $7 a lot.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import liquidity_gold as g  # noqa: E402

H, COMM = 0.15, 0.07
COST = 2 * H + COMM
S0, S1, CUT = 7 * 60, 17 * 60, 20 * 60 + 40


def load(path):
    bid = g.read(path)
    mid = bid + H
    return mid[mid.index.dayofweek < 5]


def part1(mid):
    c = mid.close
    r1 = c.diff()
    m5 = mid.resample("5min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    r5 = m5.close - m5.open
    rng5 = m5.high - m5.low
    hours = range(24)
    L = ["### Part 1: the bars, by UTC hour (Oct 2023 - Sep 2026)", "",
         "Cost of one trade: $0.37 an ounce. 'Moves > cost' = the share of 5-minute bars whose high-low range is more than "
         "twice the cost (room for a target past the cost). Autocorrelation > 0: moves tend to continue; < 0: to reverse.", "",
         "| hour (UTC) | IST | median 1-min move | median 5-min range | 5-min bars with range > 2x cost | mean 5-min drift | autocorr 1-min | autocorr 5-min |",
         "|---|---|---|---|---|---|---|---|"]
    h1 = r1.index.hour; h5 = r5.index.hour
    for h in hours:
        a1 = r1[h1 == h].dropna(); a5 = r5[h5 == h]; g5 = rng5[h5 == h]
        if len(a5) < 100:
            continue
        ac1 = a1.autocorr(1) if len(a1) > 10 else np.nan
        ac5 = a5.autocorr(1) if len(a5) > 10 else np.nan
        ist = (h * 60 + 330) % 1440
        L.append(f"| {h:02d}:00 | {ist // 60:02d}:{ist % 60:02d} | ${a1.abs().median():.2f} | ${g5.median():.2f} | "
                 f"{100 * (g5 > 2 * COST).mean():.0f}% | {a5.mean():+.3f} | {ac1:+.3f} | {ac5:+.3f} |")
    return L


class Minutes:
    def __init__(self, mid):
        self.t = mid.index.values
        self.o, self.h, self.l, self.c = (mid[k].values for k in ("open", "high", "low", "close"))
        self.day = mid.index.normalize().values
        self.mod = (mid.index.hour * 60 + mid.index.minute).values


def walk(M, i0, side, stop, target, tmax):
    e = M.o[i0] + side * H
    day = M.day[i0]
    end = min(len(M.t), i0 + tmax)
    i = i0
    while i < end and M.day[i] == day and M.mod[i] < CUT:
        if side > 0:
            if M.l[i] - H <= stop:
                return stop - e - COMM, i
            if M.h[i] - H >= target:
                return target - e - COMM, i
        else:
            if M.h[i] + H >= stop:
                return e - stop - COMM, i
            if M.l[i] + H <= target:
                return e - target - COMM, i
        i += 1
    i = max(i - 1, i0)
    return side * (M.c[i] - side * H - e) - COMM, i


def signals(b, kind, n):
    c, h, l, o = b.close.values, b.high.values, b.low.values, b.open.values
    prev_hi = pd.Series(h).rolling(n).max().shift(1).values
    prev_lo = pd.Series(l).rolling(n).min().shift(1).values
    tr = np.maximum(h - l, np.maximum(abs(h - np.r_[c[0], c[:-1]]), abs(l - np.r_[c[0], c[:-1]])))
    atr = pd.Series(tr).ewm(alpha=1 / 14, adjust=False).mean().values
    d = np.diff(c, prepend=c[0])
    up = pd.Series(np.clip(d, 0, None)).ewm(alpha=1 / 2, adjust=False).mean().values
    dn = pd.Series(np.clip(-d, 0, None)).ewm(alpha=1 / 2, adjust=False).mean().values
    rsi2 = 100 - 100 / (1 + up / np.where(dn == 0, 1e-9, dn))
    body = c - o
    s = np.zeros(len(c), dtype=int)
    if kind == "breakout":
        s[c > prev_hi] = 1; s[c < prev_lo] = -1
    elif kind == "fade":
        s[c < prev_lo] = 1; s[c > prev_hi] = -1
    elif kind == "rsi2 revert":
        s[rsi2 < 10] = 1; s[rsi2 > 90] = -1
    elif kind == "rsi2 follow":
        s[rsi2 > 90] = 1; s[rsi2 < 10] = -1
    elif kind == "big bar follow":
        s[body > 2 * atr] = 1; s[body < -2 * atr] = -1
    elif kind == "big bar fade":
        s[body > 2 * atr] = -1; s[body < -2 * atr] = 1
    return s, atr


def orb_signals(b, tf):
    """The first 15 minutes after 07:00 and 13:30 UTC; a later close beyond them -> that way (once per window)."""
    s = np.zeros(len(b), dtype=int)
    idx = b.index
    mod = idx.hour * 60 + idx.minute
    day = idx.normalize()
    for open_m in (7 * 60, 13 * 60 + 30):
        rng = {}
        for k in np.where((mod >= open_m) & (mod < open_m + 15))[0]:
            hi, lo = rng.get(day[k], (-np.inf, np.inf))
            rng[day[k]] = (max(hi, b.high.iat[k]), min(lo, b.low.iat[k]))
        done = set()
        for k in np.where((mod >= open_m + 15) & (mod < open_m + 120))[0]:
            dk = day[k]
            if dk in done or dk not in rng:
                continue
            hi, lo = rng[dk]
            if b.close.iat[k] > hi:
                s[k] = 1; done.add(dk)
            elif b.close.iat[k] < lo:
                s[k] = -1; done.add(dk)
    return s


def run(M, b, tf, s, atr, tp, sl, tmax_bars):
    out = []
    starts = b.index.values
    mod = (b.index.hour * 60 + b.index.minute).values
    pos = np.searchsorted(M.t, starts)
    busy = -1
    for j in np.nonzero(s)[0]:
        if j + 1 >= len(b) or not (S0 <= mod[j] + tf <= S1):
            continue
        i0 = pos[j + 1]
        if i0 <= busy or i0 >= len(M.t) or M.day[i0] != M.day[pos[j]]:
            continue
        side = s[j]
        a = atr[j]
        if not a > 0:
            continue
        e = M.o[i0] + side * H
        usd, ie = walk(M, i0, side, e - side * sl * a, e + side * tp * a, tmax_bars * tf)
        out.append((pd.Timestamp(M.t[i0]), side, 100 * usd))
        busy = ie
    return pd.DataFrame(out, columns=["at", "side", "usd"])


def summary(tr, fit, hold):
    if len(tr) < 2:
        return None
    tr = tr.assign(year=[g.year_of(t) for t in tr["at"]])
    x = tr.usd
    t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5)
    f, h = tr[tr.year.isin(fit)].usd, tr[tr.year.isin(hold)].usd
    tf_ = f.mean() / (f.std(ddof=1) / len(f) ** 0.5) if len(f) > 1 else 0
    th = h.mean() / (h.std(ddof=1) / len(h) ** 0.5) if len(h) > 1 else 0
    eq = x.cumsum()
    return dict(n=len(x), fit=f.sum(), hold=h.sum(), tf=tf_, th=th, total=x.sum(), t=t, win=100 * (x > 0).mean(),
                per=x.mean(), dd=(eq - eq.cummax()).min())


def main():
    mid = load(sys.argv[1])
    M = Minutes(mid)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.normalize().unique())}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    L = ["## Scalping XAUUSD: the bars and a rule search (research/gold_scalp.py)", "",
         f"Dukascopy XAUUSD 1-minute, {mid.index.min():%Y-%m-%d} .. {mid.index.max():%Y-%m-%d}. Fitting years: "
         + ", ".join(fit) + "; held out: " + ", ".join(hold) + ".", ""]
    L += part1(mid)
    print("\n".join(L), flush=True)
    rows = []
    exits = [(1.0, 1.0), (1.5, 1.0), (1.0, 1.5)]
    for tf in (1, 5):
        b = mid.resample(f"{tf}min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
        b = b[b.index.dayofweek < 5]
        rules = [(k, n) for k in ("breakout", "fade") for n in (10, 20)] + [(k, 0) for k in ("rsi2 revert", "rsi2 follow", "big bar follow", "big bar fade")]
        for kind, n in rules:
            s, atr = signals(b, kind, max(n, 2))
            for tp, sl in exits:
                tr = run(M, b, tf, s, atr, tp, sl, 12)
                for d, sub in (("buys only", tr[tr.side > 0]), ("both ways", tr)):
                    r = summary(sub, fit, hold)
                    if r:
                        name = f"M{tf} {kind}" + (f" {n}" if n else "") + f", target {tp:g} / stop {sl:g} ATR"
                        rows.append((name, d, r))
                        print(name, d, round(r["fit"]), round(r["hold"]), r["n"], flush=True)
        s = orb_signals(b, tf)
        _, atr = signals(b, "breakout", 10)
        for tp, sl in exits:
            tr = run(M, b, tf, s, atr, tp, sl, 24)
            for d, sub in (("buys only", tr[tr.side > 0]), ("both ways", tr)):
                r = summary(sub, fit, hold)
                if r:
                    name = f"M{tf} session ORB (07:00 / 13:30, 15 min), target {tp:g} / stop {sl:g} ATR"
                    rows.append((name, d, r))
    rows.sort(key=lambda x: -x[2]["fit"])
    passed = [x for x in rows if x[2]["fit"] > 0 and x[2]["hold"] > 0 and x[2]["n"] >= 200]
    L += ["", "### Part 2: every rule, best fitting years first", "",
          f"{len(rows)} rule versions; {sum(1 for x in rows if x[2]['total'] > 0)} positive over three years; "
          f"{len(passed)} positive in BOTH the fitting years and the held-out year with 200+ trades.", "",
          "| rule | direction | fitting (t) | held out (t) | 3 years | trades | win | per trade | max drawdown |",
          "|---|---|---|---|---|---|---|---|---|"]
    for name, d, r in rows:
        L.append(f"| {name} | {d} | {r['fit']:+,.0f} ({r['tf']:.2f}) | {r['hold']:+,.0f} ({r['th']:.2f}) | {r['total']:+,.0f} | {r['n']} | "
                 f"{r['win']:.0f}% | {r['per']:+.1f} | {r['dd']:,.0f} |")
    text = "\n".join(L)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)
    print("\n".join(L[-len(rows) - 6:][:30]))


if __name__ == "__main__":
    main()
