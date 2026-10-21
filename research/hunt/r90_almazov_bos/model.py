"""R90 trade model "BOS to BOS" (PREREG section 3): setups, the indicator's own bar-path run, the session-rules run.

htf_view()       the HTF state each chart bar may use: that of the last HTF bar that CLOSED before it (an HTF bar is
                 known closed when the next bucket's first chart bar arrives)
setup()          the order for the next chart bar, from the state at this bar's close
run_as_written() the indicator's own model: chart-bar OHLC path fills / exits, continuous, no fees, no slippage
run_session()    the tested version: the same setups, the stop order filled at 1-minute resolution inside the next chart
                 bar, the underlying exit at 1-minute resolution (R89 underlying_exit), session window and flat times
"""
from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .structure import INF, Struct, path, reach, subpath

R_STOP, R_TARGET, R_TIME = 1, 2, 3
WHY = {R_STOP: "stop", R_TARGET: "target", R_TIME: "time"}


@dataclass
class View:
    trend: np.ndarray
    H: np.ndarray
    Hid: np.ndarray
    Hok: np.ndarray
    L: np.ndarray
    Lid: np.ndarray
    Lok: np.ndarray


def htf_view(ltf_s: np.ndarray, htf_s: np.ndarray, S: Struct) -> View:
    """ltf_s / htf_s: first 1-minute index of each chart / HTF bar (each chart bar lies inside one HTF bar)."""
    hidx = np.searchsorted(htf_s, ltf_s, side="right") - 1        # the HTF bar each chart bar belongs to
    j = hidx - 1                                                    # the last CLOSED HTF bar
    ok = j >= 0
    jj = np.where(ok, j, 0)

    def pick(a, fill):
        x = a[jj].copy()
        x[~ok] = fill
        return x
    return View(pick(S.trend, 0), pick(S.H, np.nan), pick(S.Hid, -1), pick(S.Hok, False), pick(S.L, np.nan),
                pick(S.Lid, -1), pick(S.Lok, False))


def setup(k: int, c: float, S: Struct, V: View, minrr: float, tick: float, used_l: set, used_h: set,
          daily: np.ndarray | None = None):
    """(side, entry, stop, target, ltf id, htf id) for the bar after k, or None."""
    tr = int(S.trend[k])
    if tr == 0 or int(V.trend[k]) != tr:
        return None
    if daily is not None and int(daily[k]) != tr:
        return None
    if tr == 1:
        if not S.Hok[k] or int(S.Hid[k]) in used_l or not V.Hok[k] or int(V.Hid[k]) in used_h:
            return None
        E, P, T = float(S.H[k]), float(S.L[k]), float(V.H[k])
        if not (c < E and P == P and T > E):
            return None
        stop = P - tick
        risk = E - stop
        reward = T - E
        lid, hid = int(S.Hid[k]), int(V.Hid[k])
    else:
        if not S.Lok[k] or int(S.Lid[k]) in used_l or not V.Lok[k] or int(V.Lid[k]) in used_h:
            return None
        E, P, T = float(S.L[k]), float(S.H[k]), float(V.L[k])
        if not (c > E and P == P and T < E):
            return None
        stop = P + tick
        risk = stop - E
        reward = E - T
        lid, hid = int(S.Lid[k]), int(V.Lid[k])
    if not risk > 0 or reward / risk < minrr - 1e-9:
        return None
    return (tr, E, stop, T, lid, hid)


# ------------------------------------------------------------------------------------------------ as written
def _exit_on_path(seq, side: int, stop: float, tgt: float):
    """(reason, price) of the first stop / target touch along `seq` (its first point may already be beyond: a gap), or
    None. Stop first on a tie."""
    ts = reach(seq, stop, side < 0, False)
    tt = reach(seq, tgt, side > 0, False)
    if ts == INF and tt == INF:
        return None
    if ts <= tt:
        return R_STOP, (seq[0] if ts == 0 else stop)
    return R_TARGET, (seq[0] if tt == 0 else tgt)


def run_as_written(B: dict, S: Struct, V: View, minrr: float, tick: float, daily=None, k0: int = 0,
                   k1: int | None = None) -> list:
    """Chart bars B (o, h, l, c), bars k0 .. k1 - 1. Each trade: k_in, k_out, side, entry, fill, stop, target, exit, why,
    pts (underlying points, no costs)."""
    o, h, l, c = B["o"], B["h"], B["l"], B["c"]
    k1 = len(o) if k1 is None else k1
    used_l, used_h = set(), set()
    out, pos, pend = [], None, None
    for k in range(k0, k1):
        seq = path(o[k], h[k], l[k], c[k])
        if pos is None and pend is not None:
            side, E, stp, T, lid, hid = pend
            tf = reach(seq, E, side > 0, False)
            if tf < INF:
                fill = seq[0] if tf == 0 else E
                used_l.add(lid)
                pos = dict(k_in=k, side=side, entry=E, fill=float(fill), stop=stp, target=T, hid=hid)
                ex = _exit_on_path(subpath(seq, tf), side, stp, T)
            else:
                ex = None
        elif pos is not None:
            ex = _exit_on_path(seq, pos["side"], pos["stop"], pos["target"])
        else:
            ex = None
        if pos is not None and ex is not None:
            why, px = ex
            out.append(dict(pos, k_out=k, exit=float(px), why=WHY[why], pts=float(pos["side"] * (px - pos["fill"]))))
            if why == R_TARGET:
                used_h.add(pos["hid"])
            pos = None
        pend = None
        if pos is None:
            pend = setup(k, c[k], S, V, minrr, tick, used_l, used_h, daily)
    return out


# ------------------------------------------------------------------------------------------------ session rules
def underlying_exit(mo, mh, ml, mc, t: int, side: int, stop: float, target: float, sq: int):
    """R89 rules.underlying_exit (same rule, plain Python): on the fill minute only its close counts, later each minute's
    low / high, the stop first; else the session end (exit at that minute's open)."""
    x = mc[t]
    if (side > 0 and x <= stop) or (side < 0 and x >= stop):
        return t, R_STOP
    if (side > 0 and x >= target) or (side < 0 and x <= target):
        return t, R_TARGET
    for m in range(t + 1, sq):
        if (side > 0 and ml[m] <= stop) or (side < 0 and mh[m] >= stop):
            return m, R_STOP
        if (side > 0 and mh[m] >= target) or (side < 0 and ml[m] <= target):
            return m, R_TARGET
    return sq, R_TIME


def run_session(M: dict, B: dict, S: Struct, V: View, minrr: float, tick: float, sq: np.ndarray, first: int,
                last: int, day_ok: np.ndarray, daily=None, k0: int = 0, k1: int | None = None) -> list:
    """M: 1-minute arrays mo, mh, ml, mc, mt (minute of day). B: chart bars with s / e / day. sq[d]: the global minute
    index of day d's flat time (exit at its open). day_ok[d]: trades allowed on session d. Each trade: day, k (chart bar
    of the fill), t (fill minute), side, entry, fill, stop, target, risk, reward, xm, why, pts."""
    mo, mh, ml, mc, mt = M["mo"], M["mh"], M["ml"], M["mc"], M["mt"]
    o, c, s, e, bday = B["o"], B["c"], B["s"], B["e"], B["day"]
    k1 = len(o) if k1 is None else k1
    used_l, used_h = set(), set()
    out, pend, busy = [], None, -1
    for k in range(k0, k1):
        d = int(bday[k])
        if pend is not None and busy < s[k] and k > 0 and int(bday[k - 1]) == d and day_ok[d]:
            side, E, stp, T, lid, hid = pend
            t = -1
            for m in range(s[k], e[k] + 1):
                if (side > 0 and mh[m] >= E) or (side < 0 and ml[m] <= E):
                    t = m
                    break
            if t >= 0 and first <= mt[t] <= last and t < sq[d]:
                fill = max(E, mo[t]) if side > 0 else min(E, mo[t])
                xm, why = underlying_exit(mo, mh, ml, mc, t, side, stp, T, int(sq[d]))
                if why == R_STOP:
                    px = mc[t] if xm == t else (min(stp, mo[xm]) if side > 0 else max(stp, mo[xm]))
                elif why == R_TARGET:
                    px = mc[t] if xm == t else T
                else:
                    px = mo[xm]
                used_l.add(lid)
                if why == R_TARGET:
                    used_h.add(hid)
                busy = xm
                out.append(dict(day=d, k=k, t=int(t), side=side, entry=E, fill=float(fill), stop=stp, target=T,
                                risk=float(side * (E - stp)), reward=float(side * (T - E)), xm=int(xm), why=WHY[why],
                                pts=float(side * (px - fill))))
        pend = None
        if busy <= e[k]:
            pend = setup(k, c[k], S, V, minrr, tick, used_l, used_h, daily)
    return out
