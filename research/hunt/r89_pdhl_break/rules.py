"""R89 rules (PREREG.md section 3): setups on TF candles, the trigger at 1-minute resolution, stop, swing target, filters
and the underlying exit.

gen_triggers()   every raw trigger of one (reading, TF, levels, break type): the trigger minute, side, level, signal
                 candle (numba state machine, one pass over the session's candles per level and side)
plan()           a variant's trades: target from confirmed fractal swings, HTF / window / expiry / first-only filters,
                 one open position at a time, at most 3 a day, and the underlying exit (stop / target / session end)

Implementation clarifications (NOTES.md N1, before any result): a trigger consumes its setup whether or not it becomes
a trade (no target, filtered, a position already open), so a variant's filters never change which triggers exist.
"""
from __future__ import annotations

import numpy as np
from numba import njit

from . import cfg as K
from .data import Panel, candles, fractals

R_STOP, R_TARGET, R_TIME = 1, 2, 3
WHY = {R_STOP: "stop", R_TARGET: "target", R_TIME: "time"}


@njit(cache=True)
def _gen(cday, co, ch, cl, cc, cs, ce, mh, ml, ndays, levels, pclose, skip, close_break, reclaim, W):
    """Returns (n, 5) int64 rows: trigger global minute, side (+1 / -1), level index, signal candle, trigger candle."""
    nc = len(cday)
    cap = 4 * nc + 16
    out = np.empty((cap, 5), np.int64)
    n = 0
    # session candle ranges
    first = np.full(ndays, -1, np.int64)
    last = np.full(ndays, -1, np.int64)
    for k in range(nc):
        d = cday[k]
        if first[d] < 0:
            first[d] = k
        last[d] = k
    for d in range(ndays):
        if skip[d] or first[d] < 0:
            continue
        for li in range(levels.shape[1]):
            L = levels[d, li]
            if not (L == L):
                continue
            for sd in (1, -1):
                armed = False
                arm_k = -1
                sig_k = -1
                prevc = pclose[d]
                for k in range(first[d], last[d] + 1):
                    # 1. trigger: the candle right after the signal candle breaks its extreme
                    if sig_k >= 0 and sig_k == k - 1:
                        X = ch[sig_k] if sd > 0 else cl[sig_k]
                        hit = -1
                        for mi in range(cs[k], ce[k] + 1):
                            if (sd > 0 and mh[mi] > X) or (sd < 0 and ml[mi] < X):
                                hit = mi
                                break
                        if hit >= 0:
                            if n >= cap:
                                return out[:n]
                            out[n, 0] = hit
                            out[n, 1] = sd
                            out[n, 2] = li
                            out[n, 3] = sig_k
                            out[n, 4] = k
                            n += 1
                            armed = False
                        sig_k = -1
                    # 2. the candle updates the setup
                    c = cc[k]
                    green = c > co[k]
                    red = c < co[k]
                    if reclaim == 0:
                        # continuation: side +1 after an UP-break, side -1 after a DOWN-break
                        if armed and k > arm_k:
                            beyond = (c > L) if sd > 0 else (c < L)
                            if not beyond:
                                armed = False
                                sig_k = -1
                            elif k - arm_k > W:
                                armed = False
                                sig_k = -1
                            elif (sd > 0 and green) or (sd < 0 and red):
                                sig_k = k
                        if not armed:
                            if close_break:
                                brk = (c > L and prevc <= L) if sd > 0 else (c < L and prevc >= L)
                            else:
                                brk = (ch[k] > L and prevc <= L) if sd > 0 else (cl[k] < L and prevc >= L)
                            if brk:
                                armed = True
                                arm_k = k
                                sig_k = -1
                    else:
                        # reclaim: side +1 after a DOWN-break closed back above, side -1 after an UP-break back below
                        if close_break:
                            brk = (c < L and prevc >= L) if sd > 0 else (c > L and prevc <= L)
                        else:
                            brk = (cl[k] < L and prevc >= L) if sd > 0 else (ch[k] > L and prevc <= L)
                        if brk:
                            armed = True
                            arm_k = k
                            sig_k = -1
                        elif armed and k > arm_k:
                            if k - arm_k > W:
                                armed = False
                                sig_k = -1
                            elif sd > 0 and green and c > L:
                                sig_k = k
                            elif sd < 0 and red and c < L:
                                sig_k = k
                    prevc = c
    return out[:n]


def gen_triggers(P: Panel, reading: str, tf: int, sr: bool, wick: bool, C: dict | None = None) -> dict:
    C = C if C is not None else candles(P, tf)
    nl = 4 if sr else 2
    lv = np.full((P.ndays, nl), np.nan)
    for d in range(P.ndays):
        lv[d] = P.levels(d, sr)
    raw = _gen(C["day"], C["o"], C["h"], C["l"], C["c"], C["s"], C["e"], P.mh, P.ml, P.ndays, lv,
               P.pclose.astype(np.float64), P.skip, not wick, 1 if reading == "R" else 0, K.WINDOW_CANDLES)
    raw = raw[np.argsort(raw[:, 0], kind="stable")] if len(raw) else raw
    return dict(raw=raw, C=C, levels=lv)


# ------------------------------------------------------------------------------------------------ target and exit
class Swings:
    """Fractal swings of one candle series with the candle index that confirms each."""

    def __init__(self, C: dict, n: int):
        hs, ls = fractals(C["h"], C["l"], n)
        w = (n - 1) // 2
        self.hk = np.flatnonzero(hs)
        self.lk = np.flatnonzero(ls)
        self.hv, self.lv = C["h"][self.hk], C["l"][self.lk]
        self.hconf, self.lconf = self.hk + w, self.lk + w
        self.hday, self.lday = C["day"][self.hk], C["day"][self.lk]

    def target(self, side: int, entry: float, risk: float, minrr: float, trig_k: int, day: int) -> float:
        """Nearest confirmed swing beyond entry by >= minrr x risk: confirmed by a candle that closed before the
        trigger candle starts, in today's or the previous 5 sessions. NaN when none."""
        lo_d = day - K.SWING_SESSIONS
        if side > 0:
            ok = (self.hconf < trig_k) & (self.hday >= lo_d) & (self.hday <= day) & (self.hv >= entry + minrr * risk)
            v = self.hv[ok]
            return float(v.min()) if len(v) else np.nan
        ok = (self.lconf < trig_k) & (self.lday >= lo_d) & (self.lday <= day) & (self.lv <= entry - minrr * risk)
        v = self.lv[ok]
        return float(v.max()) if len(v) else np.nan


@njit(cache=True)
def underlying_exit(mo, mh, ml, mc, t, side, stop, target, sq):
    """First exit of a trade triggered at global minute t: on the trigger minute only its close counts, later the
    minute's low / high (stop first); else the session end. Returns (exit minute, reason)."""
    c = mc[t]
    if (side > 0 and c <= stop) or (side < 0 and c >= stop):
        return t, 1
    if (side > 0 and c >= target) or (side < 0 and c <= target):
        return t, 2
    for m in range(t + 1, sq):
        if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
            return m, 1
        if (side > 0 and mh[m] >= target) or (side < 0 and ml[m] <= target):
            return m, 2
    return sq, 3


def window(P: Panel, v: K.Variant) -> tuple:
    if P.kind == "nse":
        return (K.NSE_WIN_FIRST if v.win else K.NSE_FIRST), K.NSE_LAST
    return (K.MCX_WIN_FIRST if v.win else 0), K.MCX_LAST


def plan(P: Panel, v: K.Variant, G: dict, SW: Swings, lo_day: int = 0, hi_day: int | None = None) -> list:
    """The variant's trades on sessions lo_day .. hi_day (panel indices). Each trade: day (index), t (trigger minute),
    side, entry (signal candle extreme), stop, target, risk, reward, xm / why (underlying exit), level, tf, k (trigger
    candle), sig (signal candle)."""
    hi_day = P.ndays - 1 if hi_day is None else hi_day
    C = G["C"]
    first, last = window(P, v)
    out = []
    busy_until = -1
    per_day: dict = {}
    for t, sd, li, sk, tk in G["raw"]:
        d = int(P.mday[t])
        if d < lo_day or d > hi_day:
            continue
        tod = int(P.mt[t])
        if tod < first or tod > last or t >= P.sq[d]:
            continue
        if P.kind == "mcx" and P.expiry[d]:
            continue
        if v.win and P.kind == "nse" and P.expiry[d]:
            continue
        if per_day.get(d, 0) >= (1 if v.first_only else K.MAX_PER_DAY):
            continue
        if v.first_only and d in per_day:
            continue
        if t <= busy_until:
            continue
        if v.htf and not P.htf_ok(d, tod, int(sd)):
            continue
        entry = float(C["h"][sk] if sd > 0 else C["l"][sk])
        stop = float(C["l"][sk] if sd > 0 else C["h"][sk])
        risk = (entry - stop) * sd
        if not risk > 0:
            continue
        tgt = SW.target(int(sd), entry, risk, v.minrr, int(tk), d)
        if not np.isfinite(tgt):
            continue
        xm, why = underlying_exit(P.mo, P.mh, P.ml, P.mc, int(t), int(sd), stop, tgt, int(P.sq[d]))
        per_day[d] = per_day.get(d, 0) + 1
        busy_until = xm
        out.append(dict(day=d, t=int(t), side=int(sd), entry=entry, stop=stop, target=tgt, risk=risk,
                        reward=(tgt - entry) * sd, xm=int(xm), why=WHY[int(why)], level=int(li), tf=v.tf, k=int(tk),
                        sig=int(sk)))
    return out


def no_target_count(P: Panel, v: K.Variant, G: dict, SW: Swings, lo_day: int, hi_day: int) -> int:
    """Triggers inside the window that found no qualifying swing (PREREG 6 'no target')."""
    C = G["C"]
    first, last = window(P, v)
    n = 0
    for t, sd, li, sk, tk in G["raw"]:
        d = int(P.mday[t])
        if d < lo_day or d > hi_day or not (first <= P.mt[t] <= last):
            continue
        entry = float(C["h"][sk] if sd > 0 else C["l"][sk])
        stop = float(C["l"][sk] if sd > 0 else C["h"][sk])
        risk = (entry - stop) * sd
        if risk > 0 and not np.isfinite(SW.target(int(sd), entry, risk, v.minrr, int(tk), d)):
            n += 1
    return n
