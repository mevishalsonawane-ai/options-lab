"""R63 trend-turn rules (PREREG.md section 2), read from the UNDERLYING's 1-minute bars only.

Grid: row r = the 1-minute bar that STARTS at 09:15 + r (375 rows a session, NaN where the lake has no minute). A k-minute
bar b of a session covers rows kb .. kb+k-1; it is COMPLETE at the end of row kb+k-1 and a decision on it acts at the
open of row kb+k (its "act row"). A bar with no minute at all does not exist (the indicators skip it); a bar with some
minutes missing is built from the minutes it has. Nothing ever reads a row after the bar it decides on (test_r63.py:
the look-ahead guard changes or deletes every later minute and checks the decisions do not move).

For every completed bar the module gives two flags: "turned against a CALL (long underlying)" and "turned against a PUT
(short underlying)":
  T1  15-minute close crosses EMA20 of the 15-minute closes: down-cross (close < EMA, previous close >= its EMA) is
      against a call; up-cross against a put. EMA seeded with the first close, alpha 2/21, continuous across sessions.
  T2  15-minute Supertrend(10, 3) flips: up -> down against a call, down -> up against a put. ATR = Wilder's RMA of the
      true range over 10 bars (seeded with the mean of the first 10), bands on (high + low) / 2, TradingView's rule.
  T3  two consecutive 5-minute closes of the same session on the far side of the session VWAP. The index has no volume,
      so the session VWAP is the equal-weight mean of the 1-minute typical prices (H + L + C) / 3 from 09:15 to the
      bar's last minute (a TWAP; PREREG says so). Both bars must close while the trade is open.
  T4  5-minute Donchian-10 opposite break: a 5-minute close below the lowest low of the previous 10 completed 5-minute
      bars is against a call; above the highest high against a put. Continuous across sessions.
"""
from __future__ import annotations

import numpy as np

NROWS = 375
RULES = ("T1", "T2", "T3", "T4")
TF = {"T1": 15, "T2": 15, "T3": 5, "T4": 5}
NAMES = {"T1": "15-min close crosses EMA20 against the trade",
         "T2": "15-min Supertrend(10, 3) flips against the trade",
         "T3": "two 5-min closes in a row beyond the session VWAP (TWAP) against the trade",
         "T4": "5-min Donchian-10 opposite break (close beyond the last 10 bars' extreme)"}
MIN_OPEN = 5            # the trade must have been open >= 5 minutes when the deciding bar completes
EMA_N = 20
ST_N, ST_MULT = 10, 3.0
DON_N = 10


def bars(days: list, idx: list, k: int):
    """Continuous k-minute bars over sessions in order. days: session keys; idx: matching (4, 375) O H L C arrays.
    Returns dict of arrays: day position, bar number, act row, o, h, l, c, twap (session TWAP at the bar's last minute)."""
    dp, bn, act, o_, h_, l_, c_, tw = [], [], [], [], [], [], [], []
    nb = NROWS // k + (1 if NROWS % k else 0)
    for i, a in enumerate(idx):
        ok = np.isfinite(a[3]) & np.isfinite(a[1]) & np.isfinite(a[2])
        typ = np.where(ok, (a[1] + a[2] + a[3]) / 3.0, 0.0)
        cs, cn = np.cumsum(typ), np.cumsum(ok)
        for b in range(nb):
            r0, r1 = k * b, min(k * b + k, NROWS)
            seg = ok[r0:r1]
            if not seg.any():
                continue
            j = np.flatnonzero(seg) + r0
            op = a[0, j[0]] if np.isfinite(a[0, j[0]]) else a[3, j[0]]
            dp.append(i)
            bn.append(b)
            act.append(r1)
            o_.append(op)
            h_.append(np.max(a[1, j]))
            l_.append(np.min(a[2, j]))
            c_.append(a[3, j[-1]])
            tw.append(cs[r1 - 1] / cn[r1 - 1])
    f = np.array
    return dict(dp=f(dp, np.int64), b=f(bn, np.int64), act=f(act, np.int64), o=f(o_, float), h=f(h_, float),
                l=f(l_, float), c=f(c_, float), twap=f(tw, float))


def ema(x: np.ndarray, n: int) -> np.ndarray:
    out = np.empty_like(x)
    a = 2.0 / (n + 1)
    for i in range(len(x)):
        out[i] = x[i] if i == 0 else a * x[i] + (1 - a) * out[i - 1]
    return out


def supertrend_dir(h, l, c, n: int = ST_N, mult: float = ST_MULT) -> np.ndarray:
    """+1 up / -1 down per bar (0 until the ATR exists)."""
    m = len(c)
    d = np.zeros(m, np.int64)
    if m == 0:
        return d
    tr = np.empty(m)
    tr[0] = h[0] - l[0]
    for i in range(1, m):
        tr[i] = max(h[i] - l[i], abs(h[i] - c[i - 1]), abs(l[i] - c[i - 1]))
    atr = np.full(m, np.nan)
    if m >= n:
        atr[n - 1] = tr[:n].mean()
        for i in range(n, m):
            atr[i] = (atr[i - 1] * (n - 1) + tr[i]) / n
    up_b = dn_b = np.nan
    cur = 0
    for i in range(m):
        if not np.isfinite(atr[i]):
            continue
        mid = (h[i] + l[i]) / 2.0
        bu, bd = mid - mult * atr[i], mid + mult * atr[i]
        if cur == 0:
            up_b, dn_b, cur = bu, bd, 1 if c[i] >= mid else -1
        else:
            up_b = max(bu, up_b) if c[i - 1] > up_b else bu
            dn_b = min(bd, dn_b) if c[i - 1] < dn_b else bd
            if cur == -1 and c[i] > dn_b:
                cur = 1
            elif cur == 1 and c[i] < up_b:
                cur = -1
        d[i] = cur
    return d


def flags(rule: str, B: dict):
    """(against_call, against_put) booleans per bar of B (bars(..., TF[rule])). For T3 the flag marks the SECOND bar
    of the pair; `pair_first_act` (act row of the first bar) is returned in B['prev_act'] for the both-open check."""
    c, h, l, dp = B["c"], B["h"], B["l"], B["dp"]
    m = len(c)
    ac, ap = np.zeros(m, bool), np.zeros(m, bool)
    if m < 2:
        return ac, ap
    if rule == "T1":
        e = ema(c, EMA_N)
        above = c >= e
        ac[1:] = above[:-1] & ~above[1:]
        ap[1:] = ~above[:-1] & (c[1:] > e[1:])
    elif rule == "T2":
        d = supertrend_dir(h, l, c)
        ac[1:] = (d[:-1] == 1) & (d[1:] == -1)
        ap[1:] = (d[:-1] == -1) & (d[1:] == 1)
    elif rule == "T3":
        same = dp[1:] == dp[:-1]
        below, above = c < B["twap"], c > B["twap"]
        ac[1:] = same & below[1:] & below[:-1]
        ap[1:] = same & above[1:] & above[:-1]
    elif rule == "T4":
        for i in range(DON_N, m):
            lo, hi = l[i - DON_N:i].min(), h[i - DON_N:i].max()
            ac[i] = c[i] < lo
            ap[i] = c[i] > hi
    else:
        raise ValueError(rule)
    return ac, ap


def turn_rows(rule: str, days: list, idx: list) -> dict:
    """{day: {'C': act rows, 'P': act rows, 'Cfirst': act rows of the pair's first bar (T3), ...}} for every session.
    An act row is where a position would be sold (the open of the minute after the deciding bar)."""
    B = bars(days, idx, TF[rule])
    ac, ap = flags(rule, B)
    prev_act = np.concatenate([[-1], B["act"][:-1]])
    out = {d: {"C": [], "P": [], "Cf": [], "Pf": []} for d in days}
    for i in np.flatnonzero(ac | ap):
        d = days[B["dp"][i]]
        for s, f in (("C", ac), ("P", ap)):
            if f[i]:
                out[d][s].append(int(B["act"][i]))
                out[d][s + "f"].append(int(prev_act[i]) if rule == "T3" else -1)
    return out


def exit_row(turns: dict, side: str, ef: int, tf: int, rule: str = "") -> int:
    """First act row at which the rule sells a position entered (filled) at row ef: the deciding bar completed while
    the trade was open and >= MIN_OPEN minutes after the fill (act row >= ef + MIN_OPEN); for T3 the pair's first bar
    must also have completed while the trade was open (its act row >= ef + 1). -1 if none that session."""
    if not turns:
        return -1
    rows, firsts = turns[side], turns[side + "f"]
    for x, f in zip(rows, firsts):
        if x < ef + MIN_OPEN:
            continue
        if rule == "T3" and f < ef + 1:
            continue
        return x
    return -1
