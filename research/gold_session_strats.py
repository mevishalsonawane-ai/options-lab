"""Two XAUUSD strategies the owner sent (2026-10-01), backtested as written where the text is precise.

    python research/gold_session_strats.py <xauusd_m1_bid.csv.gz> [out.md]

Option 1, EMA crossover with an RSI filter (M5 or M15): long when EMA 9 crosses above EMA 21 with RSI(14) > 50, short
on the mirror. Only in the London / New York hours (entries 07:00-16:00 UTC; the text says to avoid the Asian session).
Stop just past the recent swing (the lowest low of the last 10 bars for a long). Exits tested: the opposite crossover
(trend following), a 1R target, a 2R target. Optional H1 bias (the text's "H4/H1 for direction"): longs only while the
1-hour close is above its 50-hour EMA, shorts only below. Out by 20:40 UTC.

Option 2, session liquidity sweep: the Asian range (00:00-07:00 UTC high and low). In London / New York (07:00-16:00),
a bar that trades below the Asian low and closes back inside is a long (above the high and back inside: a short); stop
just past the sweep's extreme; target the session range expanded by 100% or 200% from the entry. One trade a day.
M5 and M15. Out by 20:40 UTC.

Not testable here: the DXY / US 10-year confirmation and skipping CPI / NFP / FOMC (no such data in this file).
Fills: a buy pays the ask (mid + 0.15), sells at the bid; $7 a lot. Stop and target checked on 1-minute bars, the stop
first when both are touched in one minute. USD per standard lot (100 oz); fitting years Oct 2023 - Sep 2025, held out
Oct 2025 - Sep 2026. "Buys only" is how IraGoldAlgo trades.
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

sys.path.insert(0, os.path.dirname(__file__))
import liquidity_gold as g  # noqa: E402

H = 0.15          # half the spread
COMM = 0.07       # USD an ounce round trip
START, END, CUT = 7 * 60, 16 * 60, 20 * 60 + 40


def load(path):
    bid = g.read(path)
    mid = bid + H
    mid = mid[mid.index.dayofweek < 5]
    return mid


def bars(mid, tf):
    b = mid.resample(f"{tf}min", label="left", closed="left").agg({"open": "first", "high": "max", "low": "min", "close": "last"}).dropna()
    return b


def ema(x, n):
    return x.ewm(span=n, adjust=False).mean()


def rsi(c, n=14):
    d = c.diff()
    up = d.clip(lower=0).ewm(alpha=1 / n, adjust=False).mean()
    dn = (-d.clip(upper=0)).ewm(alpha=1 / n, adjust=False).mean()
    return 100 - 100 / (1 + up / dn)


class Minutes:
    """1-minute mid bars as arrays, with a fast lookup from a timestamp to its position."""
    def __init__(self, mid):
        self.t = mid.index.values
        self.o, self.h, self.l, self.c = (mid[k].values for k in ("open", "high", "low", "close"))
        self.day = mid.index.normalize().values
        self.mod = (mid.index.hour * 60 + mid.index.minute).values

    def at(self, ts):
        return int(np.searchsorted(self.t, np.datetime64(ts)))


def walk(M, i0, side, stop, target, exit_at=None):
    """From minute i0: entry at that minute's open (buy at ask / sell at bid); exits at the stop, the target (either
    may be None), the minute index [exit_at] (a signal exit, at its open), or the 20:40 UTC cut-off / the day's end."""
    e = M.o[i0] + side * H
    day = M.day[i0]
    i = i0
    while i < len(M.t) and M.day[i] == day:
        if exit_at is not None and i >= exit_at:
            px = M.o[i] - side * H
            return side * (px - e) - COMM, "signal", i
        if M.mod[i] >= CUT:
            px = M.o[i] - side * H
            return side * (px - e) - COMM, "cut-off", i
        lo, hi = M.l[i] - H, M.h[i] - H          # bid side, for a long's exits
        if side < 0:
            lo, hi = M.l[i] + H, M.h[i] + H      # ask side, for a short's exits
        if stop is not None and ((side > 0 and lo <= stop) or (side < 0 and hi >= stop)):
            return side * (stop - e) - COMM, "stop", i
        if target is not None and ((side > 0 and hi >= target) or (side < 0 and lo <= target)):
            return side * (target - e) - COMM, "target", i
        i += 1
    i -= 1
    return side * (M.c[i] - side * H - e) - COMM, "cut-off", i


def option1(M, mid, tf, exit_mode, bias):
    b = bars(mid, tf)
    c = b.close
    e9, e21, r = ema(c, 9), ema(c, 21), rsi(c)
    up = (e9 > e21) & (e9.shift() <= e21.shift())
    dn = (e9 < e21) & (e9.shift() >= e21.shift())
    swing_lo, swing_hi = b.low.rolling(10).min(), b.high.rolling(10).max()
    h1 = bars(mid, 60).close
    h1_up = (h1 > ema(h1, 50)).shift(1)         # the last COMPLETED hour
    h1_up = h1_up.reindex(b.index, method="ffill")
    out = []
    busy_until = None
    idx = b.index
    for j in range(25, len(b) - 1):
        t = idx[j]
        mod = t.hour * 60 + t.minute + tf          # the signal is known when the bar closes
        if not (START <= mod < END) or t.dayofweek > 4:
            continue
        if busy_until is not None and t < busy_until:
            continue
        side = 1 if (up.iloc[j] and r.iloc[j] > 50) else -1 if (dn.iloc[j] and r.iloc[j] < 50) else 0
        if side == 0:
            continue
        if bias:
            hb = h1_up.iloc[j]
            if (side > 0 and hb is not True and hb != True) or (side < 0 and not (hb is False or hb == False)) or pd.isna(hb):
                continue
        i0 = M.at(idx[j + 1])
        if i0 >= len(M.t) or M.day[i0] != np.datetime64(t.normalize()):
            continue
        e = M.o[i0] + side * H
        stop = (swing_lo.iloc[j] - 0.10) if side > 0 else (swing_hi.iloc[j] + 0.10)
        risk = side * (e - stop)
        if not risk > 0.2:
            continue
        target, exit_at = None, None
        if exit_mode == "1R":
            target = e + side * risk
        elif exit_mode == "2R":
            target = e + side * 2 * risk
        else:                                      # the opposite crossover
            opp = dn if side > 0 else up
            k = j + 1
            while k < len(b) - 1 and not opp.iloc[k] and idx[k].normalize() == t.normalize():
                k += 1
            exit_at = M.at(idx[min(k + 1, len(b) - 1)])
        usd, why, ie = walk(M, i0, side, stop, target, exit_at)
        out.append(dict(day=t.date(), side=side, usd=100 * usd, why=why))
        busy_until = pd.Timestamp(M.t[ie])
    return pd.DataFrame(out)


def option2(M, mid, tf, mult):
    b = bars(mid, tf)
    out = []
    for day, g_ in b.groupby(b.index.normalize()):
        if day.dayofweek > 4:
            continue
        mod = g_.index.hour * 60 + g_.index.minute
        asia = g_[mod < START]
        if len(asia) < 0.6 * START / tf:
            continue
        ah, al = asia.high.max(), asia.low.min()
        rng = ah - al
        if rng <= 0.5:
            continue
        sess = g_[(mod + tf >= START + tf) & (mod + tf <= END)]
        for k in range(len(sess) - 1):
            r = sess.iloc[k]
            side = 1 if (r.low < al and r.close > al) else -1 if (r.high > ah and r.close < ah) else 0
            if side == 0:
                continue
            i0 = M.at(sess.index[k + 1])
            if i0 >= len(M.t):
                break
            e = M.o[i0] + side * H
            stop = (r.low - 0.10) if side > 0 else (r.high + 0.10)
            if side * (e - stop) <= 0.2:
                break
            target = e + side * mult * rng
            usd, why, _ = walk(M, i0, side, stop, target)
            out.append(dict(day=day.date(), side=side, usd=100 * usd, why=why))
            break                                  # one trade a day
    return pd.DataFrame(out)


def stats(tr, fit, hold, years):
    if tr.empty:
        return None
    tr = tr.copy()
    tr["year"] = [g.year_of(d) for d in tr.day]
    x = tr.usd
    t = x.mean() / (x.std(ddof=1) / len(x) ** 0.5) if len(x) > 1 else 0
    eq = x.cumsum()
    per = {y: tr[tr.year == y].usd.sum() for y in years}
    return dict(per=per, fit=sum(per[y] for y in fit), hold=sum(per[y] for y in hold), total=x.sum(), n=len(x),
                win=100 * (x > 0).mean(), t=t, dd=(eq - eq.cummax()).min())


def main():
    mid = load(sys.argv[1])
    M = Minutes(mid)
    years = sorted({g.year_of(d) for d in pd.Series(mid.index.date).unique()}, key=lambda s: s[4:8])
    fit, hold = years[:-1], years[-1:]
    months = 36
    rows = []
    for tf in (5, 15):
        for exit_mode in ("opposite cross", "1R", "2R"):
            for bias in (False, True):
                tr = option1(M, mid, tf, exit_mode, bias)
                for d, sub in (("buys only", tr[tr.side > 0] if not tr.empty else tr), ("both ways", tr)):
                    rows.append((f"1: EMA 9/21 + RSI, M{tf}, exit {exit_mode}" + (", H1 bias" if bias else ""), d, stats(sub, fit, hold, years)))
                    print(rows[-1][0], d, {k: round(v) for k, v in rows[-1][2].items() if k not in ("per",)} if rows[-1][2] else None, flush=True)
    for tf in (5, 15):
        for mult in (1.0, 2.0):
            tr = option2(M, mid, tf, mult)
            for d, sub in (("buys only", tr[tr.side > 0] if not tr.empty else tr), ("both ways", tr)):
                rows.append((f"2: Asian-range sweep, M{tf}, target {int(mult * 100)}% of the range", d, stats(sub, fit, hold, years)))
                print(rows[-1][0], d, {k: round(v) for k, v in rows[-1][2].items() if k not in ("per",)} if rows[-1][2] else None, flush=True)
    L = ["## The owner's two XAUUSD strategies (research/gold_session_strats.py)", "",
         f"Dukascopy XAUUSD 1-minute, {mid.index.min():%Y-%m-%d} .. {mid.index.max():%Y-%m-%d}; ask = bid + 0.30, $7 a lot. "
         "USD per standard lot after costs. Fitting years " + ", ".join(fit) + "; held out " + ", ".join(hold) + ". "
         "The DXY / yields check and the news-calendar rule could not be tested (no such data).", "",
         "| strategy | direction | " + " | ".join(years) + " | 3 years | trades | win | t | max drawdown | avg month at 0.01 lot |",
         "|---|---|" + "---|" * (len(years) + 6)]
    for name, d, s in rows:
        if s is None:
            L.append(f"| {name} | {d} | " + " | ".join("-" for _ in years) + " | - | 0 | - | - | - | - |")
            continue
        L.append(f"| {name} | {d} | " + " | ".join(f"{s['per'][y]:+,.0f}" for y in years) +
                 f" | {s['total']:+,.0f} | {s['n']} | {s['win']:.0f}% | {s['t']:.2f} | {s['dd']:,.0f} | {s['total'] / 100 / months:+,.2f} |")
    text = "\n".join(L)
    print(text)
    if len(sys.argv) > 2:
        open(sys.argv[2], "w").write(text)


if __name__ == "__main__":
    main()
