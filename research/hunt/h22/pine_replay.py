"""h22: Boss's own Pine scripts as the app's Pine auto-trader (PineAuto.kt as of 1 Oct, aeb9226) would trade them.

The scripts on the phone are not in the repo. The two the repo knows Boss wrote are replayed:
  * "Bank Nifty Supertrend + 50 EMA" (research/st_ema_pine.py): Supertrend(10,3) RMA-ATR on hl2, EMA(50); long on a
    bullish flip with close > EMA, short on a bearish flip with close < EMA (entry reverses), bars 09:15-15:15,
    everything closed on the 15:15 bar.
  * "Matrix Premium v2.2" (research/matrix_pine.py, as written): close crosses EMA(20) -> long / short when flat, bars
    09:20-15:15 outside 11:00-13:14, SL = entry -/+ 2.7 x ATR(14) RMA, TP = 2.5 R, 15:15 square-off.
Indicators run continuously over days on BANKNIFTY index bars of the chart timeframe (default 5m; ST+EMA also 15m).

PineAuto (aeb9226) mapping, defaults (Auto(): BANKNIFTY 5m, 1 lot, buy/sell = "strategy", shortWith = "put",
squareOff, no stop/target/day-loss): on each completed candle the strategy's next position sign is the target; when it
changes, the held option is sold and the new side bought: ATM (half-up, step 100) of the candle's close, the nearest
expiry STRICTLY after today, MARKET MIS, 1 lot; 15:15 square-off. Paper fills at the option's open of the minute after
the candle closes (+5 bps buy / -5 bps sell), SandboxCosts charges. 29 Sep (expiry): the next contract is not in the
data, so that day is not priced.

    flock <scratch>/obuy.lock python3 -I research/hunt/h22/pine_replay.py
"""
from __future__ import annotations

import math
import os
import pickle
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h19"))
sys.path.insert(0, HERE)

import obuy  # noqa: E402,F401
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import Costs, adverse_bps  # noqa: E402
from obuy.data import market  # noqa: E402
import sim as H19  # noqa: E402
from solo_learner import dense  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h22")
COST = Costs("app")
SQ = 360            # 15:15 as minute index from 09:15


def bars_of(S, tf):
    rows = []
    for di, (d, a, lot, ex) in enumerate(S):
        for s in range(0, 375, tf):
            e = min(s + tf, 375)
            rows.append((di, s, e, a[s, 0], a[s:e, 1].max(), a[s:e, 2].min(), a[e - 1, 3]))
    return pd.DataFrame(rows, columns=["di", "s", "e", "o", "h", "l", "c"])


def rma(x, n):
    return pd.Series(x).ewm(alpha=1 / n, adjust=False).mean().values


def st_ema_targets(b):
    h, l, c = b.h.values, b.l.values, b.c.values
    pc = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    atr = rma(tr, 10)
    src = (h + l) / 2
    up, dn = src - 3 * atr, src + 3 * atr
    lo, hi = up.copy(), dn.copy()
    dirn = np.ones(len(c)); st = np.zeros(len(c))
    for i in range(1, len(c)):
        lo[i] = up[i] if (up[i] > lo[i - 1] or c[i - 1] < lo[i - 1]) else lo[i - 1]
        hi[i] = dn[i] if (dn[i] < hi[i - 1] or c[i - 1] > hi[i - 1]) else hi[i - 1]
        if st[i - 1] == hi[i - 1]:
            dirn[i] = -1 if c[i] > hi[i] else 1
        else:
            dirn[i] = 1 if c[i] < lo[i] else -1
        st[i] = lo[i] if dirn[i] == -1 else hi[i]
    ema = pd.Series(c).ewm(span=50, adjust=False).mean().values
    chg = np.r_[0, np.diff(dirn)]
    buy = (chg < 0) & (c > ema)
    sell = (chg > 0) & (c < ema)
    tgt = np.zeros(len(c), int)
    pos = 0; prev_di = -1
    for i in range(len(c)):
        if b.di.iat[i] != prev_di:
            pos = 0; prev_di = b.di.iat[i]
        s = b.s.iat[i]
        if s <= SQ:
            if buy[i]:
                pos = 1
            elif sell[i]:
                pos = -1
        if s >= SQ:
            pos = 0
        tgt[i] = pos
    return tgt


def matrix_targets(b):
    h, l, c, o = b.h.values, b.l.values, b.c.values, b.o.values
    pc = np.r_[c[0], c[:-1]]
    tr = np.maximum(h - l, np.maximum(abs(h - pc), abs(l - pc)))
    atr = rma(tr, 14)
    ema = pd.Series(c).ewm(span=20, adjust=False).mean().values
    up = (c > ema) & (np.r_[np.inf, c[:-1]] <= np.r_[np.inf, ema[:-1]])
    dn = (c < ema) & (np.r_[-np.inf, c[:-1]] >= np.r_[-np.inf, ema[:-1]])
    t = b.s.values + 555
    ok = (t >= 560) & (t <= 915) & ~((t >= 660) & (t < 795))
    tgt = np.zeros(len(c), int)
    pos = 0; stop = tp = None; prev_di = -1
    for i in range(len(c)):
        if b.di.iat[i] != prev_di:
            pos = 0; prev_di = b.di.iat[i]
        # exits on this bar's range for a position held into it (entered on an earlier bar's close)
        if pos != 0:
            if (pos > 0 and l[i] <= stop) or (pos < 0 and h[i] >= stop):
                pos = 0
            elif (pos > 0 and h[i] >= tp) or (pos < 0 and l[i] <= tp):
                pos = 0
        if b.s.iat[i] >= SQ:
            pos = 0
        elif pos == 0 and ok[i] and (up[i] or dn[i]):
            sg = 1 if up[i] else -1
            risk = 2.7 * atr[i]
            pos = sg; stop = c[i] - sg * risk; tp = c[i] + sg * 2.5 * risk
        tgt[i] = pos
    return tgt


def replay(mk, S, b, tgt, days, und="BANKNIFTY"):
    """The app's auto-trader on the targets. Returns trade rows on `days`."""
    rows = []
    held = None
    prev = 0
    for i in range(len(b)):
        di = int(b.di.iat[i]); d, a, lot, ex = S[di]
        if i == 0 or int(b.di.iat[i - 1]) != di:
            prev = 0; held = None
        e = int(b.e.iat[i])
        t = int(tgt[i])
        act = e  # the minute after the candle closed: the order goes out then
        if d not in days:
            prev = t
            continue
        ch = mk.options(und).chain(d, "near")
        if held is not None and (t != prev and (held["side"] != t) or act > SQ):
            # sell what is held at the open of the action minute (or 15:15)
            m = min(act, SQ) if act > SQ else act
            r = "C" if held["side"] > 0 else "P"
            oo = ch.o[r][ch.kpos(held["strike"])]
            j = next((q for q in range(m, min(m + 6, 375)) if oo[q] == oo[q]), None)
            px = float(oo[j]) if j is not None else float(ch.c[r][ch.kpos(held["strike"])][m - 1])
            xp = float(adverse_bps(np.array([px]), 5, False)[0])
            g = (xp - held["entry"]) * lot
            chg = COST.charge_exact(True, held["entry"], lot) + COST.charge_exact(False, xp, lot)
            rows.append(dict(day=d, side=held["side"], strike=held["strike"], em=held["em"], xm=j if j is not None else m,
                             entry=held["entry"], exit=xp, lot=lot, gross=round(g, 2), charges=round(chg, 2),
                             net=round(g - chg, 2), why="15:15" if act > SQ else "signal"))
            held = None
        if t != prev and t != 0 and act <= SQ and not ex and ch is not None:
            k = int(math.floor(b.c.iat[i] / 100 + 0.5) * 100)
            r = "C" if t > 0 else "P"
            ki = ch.kpos(k)
            if ki >= 0:
                oo = ch.o[r][ki]
                j = next((q for q in range(act, min(act + 4, 375)) if oo[q] == oo[q]), None)
                if j is not None:
                    held = dict(side=t, strike=k, em=j, entry=float(adverse_bps(np.array([oo[j]]), 5, True)[0]))
        prev = t
    return pd.DataFrame(rows)


def main():
    mk = market()
    H19.supplement(mk, "BANKNIFTY")
    ix = mk.index("BANKNIFTY")
    S = []
    for d in ix.days:
        a, n = dense(ix.d[d])
        if a is None or n < 250:
            continue
        S.append((d, a, ix.lot(d), bool(ix.d[d].get("exp", False))))
    win = {d for d, *_ in S if date(2026, 9, 28) <= d <= date(2026, 10, 6)}
    longd = {d for d, *_ in S if d >= date(2021, 8, 1)}
    res = {}
    for name, tf, fn in (("st_ema_5m", 5, st_ema_targets), ("st_ema_15m", 15, st_ema_targets), ("matrix_5m", 5, matrix_targets)):
        b = bars_of(S, tf)
        tg = fn(b)
        tw = replay(mk, S, b, tg, win)
        tl = replay(mk, S, b, tg, longd)
        res[name] = (tw, tl)
        print(name, "window:", tw.groupby("day").net.agg(["count", "sum"]).to_dict() if len(tw) else {}, flush=True)
        print(name, "long:", len(tl), round(tl.net.sum()), flush=True)
    with open(os.path.join(OUT, "pine.pkl"), "wb") as f:
        pickle.dump(res, f)


if __name__ == "__main__":
    main()
