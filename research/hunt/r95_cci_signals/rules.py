"""R95 trade rules (PREREG section 4): CCI signal -> entry at the next minute's open -> swing stop -> Z / R / T exit.

All prices are the UNDERLYING's. Minute arrays are global (sessions in order); `sq[d]` is the global minute of session
d's flat time (exit at its open); `day_ok[d]` says whether trades are allowed on session d.

Each trade: day, ts (signal minute = the signal bar's last minute; the option is bought at the next minute's open),
t (entry minute), side (+1 call, -1 put), entry, stop, target (NaN if none), risk, xm (exit minute), why, px, pts,
i (the CCI signal bar), j (the entry bar; DV may be later), and k (the exit bar, -1 for a stop / target / flat exit).
MO (amendment A1): the entry is the CCI crossing OUT through +-level, the stop the signal bar's own extreme, and the
exit the first bar back inside the level.
"""
from __future__ import annotations

import numpy as np
from numba import njit

from . import cfg as K
from .cci import Ind, breakouts, episode_start

R_STOP, R_TARGET, R_BAR, R_FLAT = 1, 2, 3, 4
WHY = {R_STOP: "stop", R_TARGET: "target", R_BAR: "bar", R_FLAT: "flat"}
EPS = 1e-9


@njit(cache=True)
def walk(mo, mh, ml, mc, t0, side, stop, target, xb, sq):
    """From entry minute t0: each minute the stop first, then the target (NaN = none); at minute xb (a bar-close exit,
    -1 = none) exit at its close; at sq exit at its open. Returns (exit minute, reason, underlying exit price)."""
    end = sq
    if xb >= 0 and xb < sq:
        end = xb + 1
    for m in range(t0, end):
        if side > 0:
            if ml[m] <= stop:
                return m, 1, min(stop, mo[m])
            if target == target and mh[m] >= target:
                return m, 2, max(target, mo[m])
        else:
            if mh[m] >= stop:
                return m, 1, max(stop, mo[m])
            if target == target and ml[m] <= target:
                return m, 2, min(target, mo[m])
        if m == xb:
            return m, 3, mc[m]
    return sq, 4, mo[sq]


def _next_cross(x: np.ndarray) -> tuple:
    """For each bar i: the first bar k > i with CCI >= 0 (up) and the first with CCI <= 0 (dn); len(x) when none."""
    n = len(x)
    up = np.full(n, n, np.int64)
    dn = np.full(n, n, np.int64)
    nu = nd = n
    for k in range(n - 1, -1, -1):
        up[k] = nu
        dn[k] = nd
        if np.isfinite(x[k]):
            if x[k] >= 0:
                nu = k
            if x[k] <= 0:
                nd = k
    return up, dn


def _next_inside(x: np.ndarray, level: float) -> tuple:
    """For MO exits: per bar i, the first bar k > i with CCI < +level (a call's exit) and with CCI > -level (a put's);
    len(x) when none. NaN never counts."""
    n = len(x)
    lo = np.full(n, n, np.int64)
    hi = np.full(n, n, np.int64)
    nl = nh = n
    for k in range(n - 1, -1, -1):
        lo[k] = nl
        hi[k] = nh
        if np.isfinite(x[k]):
            if x[k] < level:
                nl = k
            if x[k] > -level:
                nh = k
    return lo, hi


def candidates(I: Ind, v: K.Variant) -> list:
    """(i, j, side, stop_bar_lo) per setup: i the CCI signal bar, j the entry bar (DV: the later of the signal and the
    divergence confirmation), stop_bar_lo the first bar whose extreme sets the stop."""
    B = I.B
    if v.mode == "mo":
        up, dn = breakouts(I.cci, I.level)
        return [(int(i), int(i), 1 if up[i] else -1, int(i)) for i in np.flatnonzero(up | dn)]
    sig = []
    for i in np.flatnonzero(I.buy | I.sell):
        side = 1 if I.buy[i] else -1
        if v.pulse and not I.pulse[i]:
            continue
        sig.append((int(i), side, episode_start(I.cci, int(i), side, I.level)))
    if not v.div:
        return [(i, i, s, a) for i, s, a in sig]
    out = []
    used = set()
    for i, s, a in sig:
        best = None
        for dv in I.divs:
            if dv.side != s or abs(dv.q - i) > K.DV_WINDOW or (dv.side, dv.p) in used:
                continue
            if B["day"][dv.q] != B["day"][i] and dv.q > i:
                continue                       # a later confirmation must come in the signal's own session
            if best is None or abs(dv.q - i) < abs(best.q - i) or (abs(dv.q - i) == abs(best.q - i) and dv.q < best.q):
                best = dv
        if best is None:
            continue
        used.add((best.side, best.p))
        out.append((i, max(i, best.q), s, min(a, best.p)))
    out.sort(key=lambda r: (r[1], r[0]))
    return out


def run(I: Ind, v: K.Variant, M: dict, sq: np.ndarray, first: int, last: int, day_ok: np.ndarray, tick: float) -> tuple:
    """(trades, funnel). M: minute arrays mo, mh, ml, mc, mt, mday."""
    B = I.B
    mo, mh, ml, mc, mt, mday = M["mo"], M["mh"], M["ml"], M["mc"], M["mt"], M["mday"]
    up, dn = _next_cross(I.cci)
    ins_c, ins_p = _next_inside(I.cci, I.level) if v.exit == "M" else (None, None)
    nb = len(B["o"])
    F = dict(signals=0, day_off=0, window=0, busy=0, cap=0, crossed=0, beyond_stop=0, filled=0)
    out, busy, per_day = [], -1, {}
    for i, j, side, a in candidates(I, v):
        d = int(B["day"][j])
        if not day_ok[d]:
            F["day_off"] += 1
            continue
        F["signals"] += 1
        ts = int(B["e"][j])
        t0 = ts + 1
        if t0 >= len(mt) or int(mday[t0]) != d or not (first <= int(mt[t0]) <= last) or t0 >= int(sq[d]):
            F["window"] += 1
            continue
        if ts <= busy:
            F["busy"] += 1
            continue
        if per_day.get(d, 0) >= K.MAX_PER_DAY:
            F["cap"] += 1
            continue
        nxt = up[i] if side > 0 else dn[i]
        if v.div and j > i and nxt <= j:
            F["crossed"] += 1                 # DV: the CCI already crossed 0 before the divergence was known
            continue
        ext = float(B["l"][a:j + 1].min()) if side > 0 else float(B["h"][a:j + 1].max())
        stop = ext - tick if side > 0 else ext + tick
        E = float(mo[t0])
        risk = side * (E - stop)
        if not risk > EPS:
            F["beyond_stop"] += 1
            continue
        target = E + side * K.RR * risk if v.exit == "R" else np.nan
        if v.exit == "Z":
            k = int(up[j] if side > 0 else dn[j])
        elif v.exit == "T":
            k = i + K.TIME_BARS
        elif v.exit == "M":
            k = int(ins_c[j] if side > 0 else ins_p[j])
        else:
            k = nb
        xb = int(B["e"][k]) if k < nb and int(B["day"][k]) == d else -1
        xm, why, px = walk(mo, mh, ml, mc, t0, side, stop, target, xb, int(sq[d]))
        busy = int(xm)
        per_day[d] = per_day.get(d, 0) + 1
        F["filled"] += 1
        out.append(dict(day=d, ts=ts, t=t0, side=side, entry=E, stop=float(stop), target=float(target),
                        risk=float(risk), xm=int(xm), why=WHY[int(why)], px=float(px), pts=float(side * (px - E)),
                        i=int(i), j=int(j), k=int(k) if why == R_BAR else -1))
    return out, F
