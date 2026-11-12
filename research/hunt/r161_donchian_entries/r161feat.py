"""R161 entry-quality filters (PREREG.md section 2), all point-in-time. Pure numpy over one index's continuous 5-minute bars.

Input per index: (d, b, o, h, l, c) as R51's fam_r44.bars5 builds them (d = date, b = 5-minute bar of the day, 0 = 09:15).
A break whose entry row is `er` has its signal bar at b = er // 5 - 1 (it closes at the minute er - 1). Every decision
for that break reads bars 0 .. i (the signal bar) only; E2 HOLD1 also reads bar i + 1 and then enters after it (row er + 5).
test_r161.py's look-ahead guard scrambles every bar after the last one read and checks that no decision changes.

A filter that cannot be computed returns None ("unknown"), and the caller takes the break, as today (PREREG section 2).
"""
from __future__ import annotations

import math
from bisect import bisect_right
from collections import defaultdict
from datetime import date

import numpy as np

OPEN_MIN = 9 * 60 + 15
N_CH = 20                   # the arms' Donchian channel: the 20 bars before the signal bar
N_ATR = 14
SQUEEZE_N = 100             # E5: the width's own median over the previous 100 bars
ATRHOT_N, ATRHOT_MIN = 20, 10   # E9: the same bar of the day over the previous 20 sessions (needs 10)
STRONG = 0.70               # E1: the outer 30% of the bar
BIGBAR = 1.0                # E3: range >= 1.0 x ATR
FAR = 0.20                  # E4: close beyond the channel by >= 0.20 x ATR
BREADTH_MIN = 4             # E11: 4 of the 5 indices on the trade's side of their day open
LAST_ROW = 15 * 60 + 10 - OPEN_MIN      # 355: the arms' last entry row (15:10)


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - OPEN_MIN


LUNCH = (row(11, 30), row(13, 29))      # E12: entry minutes 11:30 .. 13:29 are skipped
HOUR_DONE = row(10, 15)                 # E13: from 10:15 the first hour (bars 0 .. 11) is closed
SINGLES = ("E1", "E2", "E3", "E4", "E5", "E6", "E7", "E8", "E9", "E10", "E11", "E12", "E13")
COMBO_POOL = tuple(s for s in SINGLES if s != "E2")    # C1 never uses E2 (it moves the entry)
NAMES = {"E1": "STRONGCLOSE", "E2": "HOLD1", "E3": "BIGBAR", "E4": "FAR", "E5": "SQUEEZE", "E6": "HTF15", "E7": "HTF60",
         "E8": "PDR", "E9": "ATRHOT", "E10": "VIXWAY", "E11": "BREADTH5", "E12": "NOLUNCH", "E13": "PASTHOUR", "C1": "COMBO"}
PLAIN = {
    "E1": "the signal bar closes in its outer 30%",
    "E2": "the next 5-min bar also closes beyond the broken level (enter one bar later)",
    "E3": "the signal bar is at least 1 ATR tall",
    "E4": "the close is at least 0.2 ATR beyond the channel",
    "E5": "the channel was narrow (at or below its 100-bar median) before the break",
    "E6": "the 15-min Donchian channel agrees (close on the trade's side of its midline)",
    "E7": "the 60-min Donchian channel agrees",
    "E8": "the break is beyond yesterday's high (call) / low (put)",
    "E9": "the 5-min ATR is at or above its usual level for that time of day",
    "E10": "India VIX moves the trade's way since 09:15 (down for a call, up for a put)",
    "E11": "4 of the 5 indices are on the trade's side of their day open",
    "E12": "no entries from 11:30 to 13:29",
    "E13": "after 10:15, the break is beyond the first hour's high / low",
    "C1": "the best two of the above, chosen on design only (walk-forward)",
}


# ================================================================================================ indicators
def atr(h, l, c, n: int = N_ATR) -> np.ndarray:
    """Wilder ATR (R71's): the first value is the mean of the first n true ranges; NaN before."""
    T = len(c)
    out = np.full(T, np.nan)
    if T < n:
        return out
    tr = np.empty(T)
    tr[0] = h[0] - l[0]
    for t in range(1, T):
        tr[t] = max(h[t] - l[t], abs(h[t] - c[t - 1]), abs(l[t] - c[t - 1]))
    a = tr[:n].mean()
    out[n - 1] = a
    for t in range(n, T):
        a = (a * (n - 1) + tr[t]) / n
        out[t] = a
    return out


def channel(h, l, n: int = N_CH) -> tuple:
    """(chi, clo): the high / low of the n bars BEFORE each bar (NaN for the first n), as R72's Panel and R44's donchian."""
    T = len(h)
    chi, clo = np.full(T, np.nan), np.full(T, np.nan)
    for i in range(n, T):
        chi[i] = np.max(h[i - n:i])
        clo[i] = np.min(l[i - n:i])
    return chi, clo


def prior_median(x: np.ndarray, n: int) -> np.ndarray:
    """med[i] = median of x[i-n .. i-1] when all n are finite, else NaN."""
    T = len(x)
    out = np.full(T, np.nan)
    for i in range(n, T):
        w = x[i - n:i]
        if np.isfinite(w).all():
            out[i] = float(np.median(w))
    return out


def htf(d, b, h, l, c, per: int) -> tuple:
    """Higher-timeframe bars from 5-minute bars, 09:15-anchored: group = b // per (per = 3 -> 15 min, 12 -> 60 min).
    Returns (keys [(day ordinal, group)], H, L, C) in time order; a group's close is its last 5-minute close."""
    keys, H, L, C = [], [], [], []
    for i in range(len(c)):
        k = (d[i].toordinal(), int(b[i]) // per)
        if keys and keys[-1] == k:
            H[-1] = max(H[-1], h[i])
            L[-1] = min(L[-1], l[i])
            C[-1] = c[i]
        else:
            keys.append(k)
            H.append(h[i])
            L.append(l[i])
            C.append(c[i])
    return keys, np.array(H, float), np.array(L, float), np.array(C, float)


def htf_last_closed(keys: list, day: date, b: int, per: int) -> int:
    """Index of the last higher-timeframe bar fully closed when 5-minute bar b of `day` closes (its last 5-minute bar
    per*g + per - 1 <= b; earlier days' bars are all closed), or -1."""
    g_last = (b + 1) // per - 1
    k = bisect_right(keys, (day.toordinal(), g_last)) - 1
    return k


def htf_side(H, L, C, k: int, n: int = N_CH):
    """+1 if bar k closes above the midline of the n-bar channel ending at k (bars k-n+1 .. k), -1 below, 0 on it;
    None when there are fewer than n bars."""
    if k < n - 1:
        return None
    mid = (np.max(H[k - n + 1:k + 1]) + np.min(L[k - n + 1:k + 1])) / 2.0
    return int(np.sign(C[k] - mid))


# ================================================================================================ one index's context
class IndexCtx:
    """Every array a decision needs for one index, computed once from its continuous 5-minute bars (causal arrays)."""

    def __init__(self, d, b, o, h, l, c):
        self.d = np.asarray(d, dtype=object)
        self.b = np.asarray(b, int)
        self.o, self.h, self.l, self.c = (np.asarray(x, float) for x in (o, h, l, c))
        self.pos = {(self.d[i], int(self.b[i])): i for i in range(len(self.c))}
        self.atr = atr(self.h, self.l, self.c)
        self.chi, self.clo = channel(self.h, self.l)
        self.wmed = prior_median(self.chi - self.clo, SQUEEZE_N)
        self.k15 = htf(self.d, self.b, self.h, self.l, self.c, 3)
        self.k60 = htf(self.d, self.b, self.h, self.l, self.c, 12)
        # per day: its first index, open, high, low, first-hour high / low
        self.days = []
        self.dfirst, self.dopen, self.dhigh, self.dlow, self.fh = {}, {}, {}, {}, {}
        for i in range(len(self.c)):
            dd = self.d[i]
            if dd not in self.dfirst:
                self.days.append(dd)
                self.dfirst[dd] = i
                self.dopen[dd] = self.o[i]
                self.dhigh[dd], self.dlow[dd] = self.h[i], self.l[i]
            else:
                self.dhigh[dd] = max(self.dhigh[dd], self.h[i])
                self.dlow[dd] = min(self.dlow[dd], self.l[i])
            if self.b[i] <= 11:
                hi, lo = self.fh.get(dd, (-math.inf, math.inf))
                self.fh[dd] = (max(hi, self.h[i]), min(lo, self.l[i]))
        self.prev = {self.days[j]: self.days[j - 1] for j in range(1, len(self.days))}
        # E9: the ATR at each bar of the day over the previous sessions
        self.atr_by_bar = defaultdict(list)     # b -> [(day, atr)] in day order
        for i in range(len(self.c)):
            if np.isfinite(self.atr[i]):
                self.atr_by_bar[int(self.b[i])].append((self.d[i], self.atr[i]))

    def idx(self, day: date, er: int) -> int:
        return self.pos.get((day, er // 5 - 1), -1)

    def atr_usual(self, day: date, b: int):
        """Median ATR at bar b over the previous ATRHOT_N sessions that have it (None with fewer than ATRHOT_MIN)."""
        xs = [a for dd, a in self.atr_by_bar.get(b, []) if dd < day][-ATRHOT_N:]
        return float(np.median(xs)) if len(xs) >= ATRHOT_MIN else None


# ================================================================================================ decisions
def _sgn(side: str) -> int:
    return 1 if side == "C" else -1


def decide(ctx: IndexCtx, day: date, er: int, side: str, others: dict | None = None, vixchg: float = math.nan) -> dict:
    """{E1 .. E13 (not E2): True (take) / False (skip) / None (unknown -> take)} for one break. `others` = {und: IndexCtx}
    of all five indices (E11); `vixchg` = VIX at the minute before er vs the day's 09:15 open (R101's f_vixchg)."""
    s = _sgn(side)
    i = ctx.idx(day, er)
    out = {k: None for k in COMBO_POOL}
    lo_, hi_ = LUNCH
    out["E12"] = not (lo_ <= er <= hi_)
    if i < 0:
        return out
    h, l, c, a = ctx.h[i], ctx.l[i], ctx.c[i], ctx.atr[i]
    rng = h - l
    if rng > 0:
        pos = (c - l) / rng
        out["E1"] = bool(pos >= STRONG) if s > 0 else bool(pos <= 1.0 - STRONG)
    else:
        out["E1"] = False                                   # a flat bar has no strong close
    if np.isfinite(a) and a > 0:
        out["E3"] = bool(rng >= BIGBAR * a)
        lvl = ctx.chi[i] if s > 0 else ctx.clo[i]
        if np.isfinite(lvl):
            out["E4"] = bool(s * (c - lvl) >= FAR * a)
        u = ctx.atr_usual(day, int(ctx.b[i]))
        if u is not None:
            out["E9"] = bool(a >= u)
    if np.isfinite(ctx.wmed[i]) and np.isfinite(ctx.chi[i]):
        out["E5"] = bool(ctx.chi[i] - ctx.clo[i] <= ctx.wmed[i])
    for key, per, kk in (("E6", 3, ctx.k15), ("E7", 12, ctx.k60)):
        k = htf_last_closed(kk[0], day, int(ctx.b[i]), per)
        sd = htf_side(kk[1], kk[2], kk[3], k) if k >= 0 else None
        out[key] = None if sd is None else bool(sd == s)
    pd_ = ctx.prev.get(day)
    if pd_ is not None:
        out["E8"] = bool(c > ctx.dhigh[pd_]) if s > 0 else bool(c < ctx.dlow[pd_])
    if vixchg is not None and np.isfinite(vixchg):
        out["E10"] = bool(vixchg < 0) if s > 0 else bool(vixchg > 0)
    if others:
        n_ok = 0
        for oc in others.values():
            j = oc.idx(day, er)
            if j >= 0 and s * (oc.c[j] - oc.dopen[day]) > 0:
                n_ok += 1
        out["E11"] = bool(n_ok >= BREADTH_MIN)
    if er >= HOUR_DONE:
        fh = ctx.fh.get(day)
        if fh is not None:
            out["E13"] = bool(c > fh[0]) if s > 0 else bool(c < fh[1])
    else:
        out["E13"] = True
    return out


def hold1(ctx: IndexCtx, day: date, er: int, side: str):
    """E2: the new entry row (er + 5) when the next 5-minute bar of the same day closes beyond the level the signal bar
    broke, else None (dropped). None also when the next bar is missing or the new row is past 15:10."""
    i = ctx.idx(day, er)
    if i < 0 or i + 1 >= len(ctx.c):
        return None
    if ctx.d[i + 1] != day or int(ctx.b[i + 1]) != int(ctx.b[i]) + 1:
        return None
    lvl = ctx.chi[i] if side == "C" else ctx.clo[i]
    if not np.isfinite(lvl):
        return None
    ok = ctx.c[i + 1] > lvl if side == "C" else ctx.c[i + 1] < lvl
    if not ok or er + 5 > LAST_ROW:
        return None
    return er + 5


def keep(dec: dict, rule) -> bool:
    """Take the break under a rule: a single id or a tuple of ids (AND); unknown (None) takes."""
    ids = (rule,) if isinstance(rule, str) else tuple(rule)
    return all(dec.get(r) is not False for r in ids)
