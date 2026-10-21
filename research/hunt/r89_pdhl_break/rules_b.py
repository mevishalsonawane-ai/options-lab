"""R89B rules (PREREG_B.md): R89's "break and go" setups (rules.gen_triggers) with longer candles, an ATR stop, the RUN
exit (breakeven at +1R, then a 1R trail) and three day / cross-index filters (BREADTH, OR, GAP).

Signal source: the underlying (each index's own spot minutes; BREADTH reads the other indices' spot minutes).
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np
from numba import njit

from . import cfg as K
from . import rules
from .data import Panel

R_STOP, R_TARGET, R_TIME, R_TRAIL = 1, 2, 3, 4
WHY = {R_STOP: "stop", R_TARGET: "target", R_TIME: "time", R_TRAIL: "trail"}
OR_END = 9 * 60 + 45                     # opening range 09:15-09:44, triggers from 09:45
GAP_MAX = 0.005
BREADTH_MIN = 4


@dataclass(frozen=True)
class VariantB:
    name: str
    tf: int = 15
    stop: str = "candle"                 # 'candle' | 'atr'
    exit: str = "run"                    # 'swing' | 'run'
    breadth: bool = False
    orf: bool = False
    gap: bool = False


VARIANTS_B = (VariantB("B0", tf=1, exit="swing"), VariantB("B1", exit="swing"), VariantB("B2"),
              VariantB("B3", breadth=True), VariantB("B4", orf=True), VariantB("B5", gap=True),
              VariantB("B6", tf=5, stop="atr"), VariantB("B7", tf=5, stop="atr", breadth=True),
              VariantB("B8", breadth=True, orf=True, gap=True))
BY_NAME_B = {v.name: v for v in VARIANTS_B}


# ------------------------------------------------------------------------------------------------ pieces
def atr15(P: Panel) -> np.ndarray:
    """ATR14 of the continuous 15m candles: entry i = mean true range of candles i-13 .. i (NaN before 14)."""
    C = P.C15
    pc = np.r_[np.nan, C["c"][:-1]]
    tr = np.nanmax(np.vstack([C["h"] - C["l"], np.abs(C["h"] - pc), np.abs(C["l"] - pc)]), axis=0)
    cs = np.cumsum(np.r_[0.0, tr])
    out = np.full(len(tr), np.nan)
    out[13:] = (cs[14:] - cs[:-14]) / 14.0
    return out


def last_c15(P: Panel, d: int, tod: int) -> int:
    """Index of the last 15m candle that closed before minute `tod` of session d starts (-1 if none)."""
    return int(np.searchsorted(P.c15_key, d * 10000 + tod - 1, side="right")) - 1


def first_breaks(P: Panel) -> dict:
    """{date: (first minute of day with a close > PDH, first with a close < PDL)}; 10**6 when none."""
    out = {}
    for d in range(P.ndays):
        a, b = P.ms[d], P.me[d]
        c, t = P.mc[a:b], P.mt[a:b]
        up = np.flatnonzero(c > P.pdh[d]) if np.isfinite(P.pdh[d]) else np.array([], int)
        dn = np.flatnonzero(c < P.pdl[d]) if np.isfinite(P.pdl[d]) else np.array([], int)
        out[P.days[d]] = (int(t[up[0]]) if len(up) else 10 ** 6, int(t[dn[0]]) if len(dn) else 10 ** 6)
    return out


def breadth_count(fb_all: dict, day, tod: int, side: int) -> int:
    """How many indices had a 1m close beyond their own level before minute `tod` (a close at minute m is known when
    minute m + 1 starts, so m <= tod - 1)."""
    n = 0
    for fb in fb_all.values():
        x = fb.get(day)
        if x is None:
            continue
        if (x[0] if side > 0 else x[1]) <= tod - 1:
            n += 1
    return n


def opening_range(P: Panel, d: int):
    a, b = P.ms[d], P.me[d]
    t = P.mt[a:b]
    m = (t >= K.NSE_OPEN) & (t < OR_END)
    if not m.any():
        return np.nan, np.nan
    return float(P.mh[a:b][m].max()), float(P.ml[a:b][m].min())


def gap_ok(P: Panel, d: int) -> bool:
    pc = P.pclose[d]
    return bool(np.isfinite(pc) and pc > 0 and abs(P.popen[d] / pc - 1.0) <= GAP_MAX)


@njit(cache=True)
def run_exit(mo, mh, ml, mc, t, side, entry, stop0, risk, sq):
    """RUN exit (PREREG_B 3). Returns (exit minute, reason, exit stop level). Reason 1 initial stop, 4 moved stop
    (breakeven / trail), 3 session end."""
    stop = stop0
    moved = False
    c = mc[t]
    if (side > 0 and c <= stop) or (side < 0 and c >= stop):
        return t, 1, stop
    best = mh[t] if side > 0 else ml[t]
    if (side > 0 and best >= entry + risk) or (side < 0 and best <= entry - risk):
        ns = best - risk if side > 0 else best + risk
        if (side > 0 and ns > stop) or (side < 0 and ns < stop):
            stop = ns
            moved = True
    for m in range(t + 1, sq):
        if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
            return m, (4 if moved else 1), stop
        if side > 0:
            if mh[m] > best:
                best = mh[m]
            if best >= entry + risk and best - risk > stop:
                stop = best - risk
                moved = True
        else:
            if ml[m] < best:
                best = ml[m]
            if best <= entry - risk and best + risk < stop:
                stop = best + risk
                moved = True
    return sq, 3, stop


@njit(cache=True)
def run_exits_many(mo, mh, ml, mc, tt, side, risk, sq):
    """Twins: RUN exits from random entries at the close of minute tt[i] with the same initial risk."""
    n = len(tt)
    xm = np.empty(n, np.int64)
    why = np.empty(n, np.int64)
    lv = np.empty(n)
    for i in range(n):
        e = mc[tt[i]]
        a, b, c = run_exit(mo, mh, ml, mc, tt[i], side, e, e - side * risk[i], risk[i], sq)
        xm[i], why[i], lv[i] = a, b, c
    return xm, why, lv


# ------------------------------------------------------------------------------------------------ plan
def window_first(v: VariantB) -> int:
    return OR_END if v.orf else K.NSE_FIRST


def plan_b(P: Panel, v: VariantB, G: dict, SW, ATR: np.ndarray, fb_all: dict, lo_day: int, hi_day: int) -> list:
    """The variant's trades on sessions lo_day .. hi_day (see rules.plan for the common fields; adds xlv, the exit stop
    level, and init_stop_exit for the false-break count)."""
    C = G["C"]
    first, last = window_first(v), K.NSE_LAST
    out = []
    busy_until = -1
    per_day: dict = {}
    orr: dict = {}
    for t, sd, li, sk, tk in G["raw"]:
        d = int(P.mday[t])
        if d < lo_day or d > hi_day:
            continue
        tod = int(P.mt[t])
        if tod < first or tod > last or t >= P.sq[d]:
            continue
        if per_day.get(d, 0) >= K.MAX_PER_DAY or t <= busy_until:
            continue
        sd = int(sd)
        entry = float(C["h"][sk] if sd > 0 else C["l"][sk])
        if v.gap and not gap_ok(P, d):
            continue
        if v.orf:
            if d not in orr:
                orr[d] = opening_range(P, d)
            hi, lo = orr[d]
            if not (np.isfinite(hi) and ((sd > 0 and entry > hi) or (sd < 0 and entry < lo))):
                continue
        if v.breadth and breadth_count(fb_all, P.days[d], tod, sd) < BREADTH_MIN:
            continue
        if v.stop == "atr":
            i = last_c15(P, d, tod)
            a = ATR[i] if i >= 0 else np.nan
            if not (np.isfinite(a) and a > 0):
                continue
            stop = entry - sd * a
        else:
            stop = float(C["l"][sk] if sd > 0 else C["h"][sk])
        risk = (entry - stop) * sd
        if not risk > 0:
            continue
        if v.exit == "swing":
            tgt = SW.target(sd, entry, risk, 1.0, int(tk), d)
            if not np.isfinite(tgt):
                continue
            xm, why = rules.underlying_exit(P.mo, P.mh, P.ml, P.mc, int(t), sd, stop, tgt, int(P.sq[d]))
            xlv = stop if why == 1 else tgt
            reward = (tgt - entry) * sd
        else:
            tgt, reward = np.nan, np.nan
            xm, why, xlv = run_exit(P.mo, P.mh, P.ml, P.mc, int(t), sd, entry, stop, risk, int(P.sq[d]))
        per_day[d] = per_day.get(d, 0) + 1
        busy_until = xm
        out.append(dict(day=d, t=int(t), side=sd, entry=entry, stop=float(stop), target=float(tgt), risk=float(risk),
                        reward=float(reward), xm=int(xm), why=WHY[int(why)], xlv=float(xlv), level=int(li), tf=v.tf,
                        k=int(tk), sig=int(sk)))
    return out
