"""R47 rule runner: events x instrument x exit -> priced trades (shared by the design search and the holdout read).

A rule = (family, params, instrument, exit, filter). Legs are chosen at the decision minute from the lake's rows
(the strike of offset k on that side), then each leg is ONE contract to the exit (r47price).
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

import r47cfg as K
import r47price as P
import r47sig as S

N = K.N


def exits_for(fam: str, params: dict):
    mode = K.FAMILIES[fam]["mode"]
    if mode in ("intraday", "intraday_expiry"):
        return K.XI
    if mode == "positional":
        return K.XP
    if mode == "vol":
        return K.XP if params.get("when") == "pos" else K.XI
    if mode == "event":
        return K.XE1
    if mode == "expiry":
        return K.XE2
    raise ValueError(mode)


def instruments_for(fam: str, und: str):
    mode = K.FAMILIES[fam]["mode"]
    if fam.startswith("O"):
        return ("own",)
    if mode in ("vol", "event", "expiry"):
        names = K.VOL_INSTR
    elif mode == "intraday_expiry":
        names = tuple(x for x in K.DIR_INSTR if K.INSTR[x][0] == "WEEK")
    else:
        names = K.DIR_INSTR
    return tuple(x for x in names if K.INSTR[x][0] in K.SERIES[und])


def kind_of(instr: str) -> str:
    return "dir" if instr == "own" else K.INSTR[instr][2]


def series_of(instr: str) -> str:
    return "WEEK" if instr == "own" else K.INSTR[instr][0]


def _lookup(st, di, m, side, off):
    """Contract carried at offset `off` on `side` (0 C / 1 P) at minute m (or up to 2 minutes before); -1 if none."""
    if off < -10 or off > 10:
        return -1
    for mm in (m, m - 1, m - 2):
        if mm < 0:
            break
        cc = st.offcid[di, mm, side, off + 10]
        if cc >= 0:
            return int(cc)
    return -1


def legs_for(u, ev: S.Events, instr: str):
    """Per event: (cid0, cid1, sg0, sg1) arrays; events with a missing leg get cid0 = -1."""
    n = len(ev)
    c0 = np.full(n, -1, dtype=np.int64)
    c1 = np.full(n, -1, dtype=np.int64)
    s0 = np.ones(n, dtype=np.int8)
    s1 = np.zeros(n, dtype=np.int8)
    if instr == "own":
        c0[:] = ev.cid
        return c0, c1, s0, s1
    st = u.stores[K.INSTR[instr][0]]
    legs, kind = K.INSTR[instr][1], K.INSTR[instr][2]
    for i in range(n):
        di, m, d = int(ev.di[i]), int(ev.m[i]), int(ev.d[i])
        if kind == "vol":
            k = legs[0][0]
            a, b = _lookup(st, di, m, 0, k), _lookup(st, di, m, 1, -k)
            if a >= 0 and b >= 0:
                c0[i], c1[i], s1[i] = a, b, 1
            continue
        if d == 0:
            continue
        side = 0 if d > 0 else 1
        sgn = 1 if d > 0 else -1
        a = _lookup(st, di, m, side, sgn * legs[0][0])
        if len(legs) == 1:
            c0[i] = a
            continue
        b = _lookup(st, di, m, side, sgn * legs[1][0])
        if a >= 0 and b >= 0:
            c0[i], c1[i], s1[i] = a, b, -1
    return c0, c1, s0, s1


def exit_minutes(ev: S.Events, ex) -> tuple[np.ndarray, np.ndarray]:
    """(exit_g, positional flag) per event for an exit spec (stop, target, hold, lock)."""
    h = ex[2]
    di = ev.di.astype(np.int64)
    gdec = di * N + ev.m
    if h == "sq":
        return di * N + K.SQ, np.zeros(len(di), dtype=np.bool_)
    if h == "exp":
        return di * N + K.SQ, np.zeros(len(di), dtype=np.bool_)       # the 15:05 expiry square-off caps it
    if isinstance(h, int) and h >= 10:                                # minutes (intraday)
        return np.minimum(gdec + 1 + h, di * N + K.SQ), np.zeros(len(di), dtype=np.bool_)
    if h == "am":
        return (di + 1) * N + K.MORNING_EXIT, np.ones(len(di), dtype=np.bool_)
    if h == "sq1":                                                    # E1: 15:15 of the event day
        same = ev.m < K.SQ
        return np.where(same, di, di + 1) * N + K.SQ, ~same
    return (di + int(h)) * N + K.SQ, np.ones(len(di), dtype=np.bool_)


def lots_of(u):
    return np.array([K.lot(u.und, date.fromordinal(int(x))) for x in u.days], dtype=np.int64)


def price(u, ev: S.Events, instr: str, ex, legs=None, lots=None, sequential=True) -> pd.DataFrame:
    """Trades of one rule (before any filter). Columns: di, day, exit_day, d, net, lots, outlay, charges, reason, fb."""
    st = u.stores[series_of(instr)]
    if legs is None:
        legs = legs_for(u, ev, instr)
    c0, c1, s0, s1 = legs
    ok = c0 >= 0
    if lots is None:
        lots = lots_of(u)
    exit_g, pos = exit_minutes(ev, ex)
    gdec = ev.di.astype(np.int64) * N + ev.m
    idx = np.flatnonzero(ok)
    if len(idx) == 0:
        return pd.DataFrame(columns=["di", "day", "exit_day", "d", "net", "lots", "outlay", "charges", "reason", "fb", "m", "ev"])
    lock = ex[3] or (0.0, 0.0)
    spotO = u.O.ravel()
    spotC = u.C.ravel()
    res = P.sim_batch(gdec[idx], c0[idx], c1[idx], s0[idx], s1[idx], exit_g[idx], float(ex[0]), float(ex[1]),
                      float(lock[0]), float(lock[1]), lots[ev.di[idx]], pos[idx], np.int64(u.last_di * N + N - 1),
                      st.g, st.o, st.h, st.l, st.c, st.v, st.off, st.cstart, st.cend, st.cstrike, st.cside, st.cexp,
                      st.offcid, spotO, spotC, K.EDGE, K.FILL_WAIT, K.EXPIRY_SQ, sequential)
    stt, gi, go, e0, e1, x0, x1, a0, a1, rs, fb = res
    take = stt == P.OK
    j = idx[take]
    two = c1[j] >= 0
    di = ev.di[j]
    xdi = go[take] // N
    net, nl, outlay, ch = P.settle(u.days[di], lots[di], s0[j], s1[j], e0[take], e1[take], x0[take], x1[take],
                                   a0[take], a1[take], two, u.und in K.BSE, u.days[np.minimum(xdi, len(u.days) - 1)])
    return pd.DataFrame(dict(ev=j, di=di, day=u.days[di], exit_day=u.days[np.minimum(xdi, len(u.days) - 1)], d=ev.d[j],
                             m=ev.m[j], net=net, lots=nl, outlay=outlay, charges=ch, reason=rs[take], fb=fb[take]))


def block_of(day_ordinals) -> np.ndarray:
    """Design block index 0..3 per day (-1 outside design)."""
    d = np.asarray(day_ordinals, dtype=np.int64)
    out = np.full(len(d), -1, dtype=np.int64)
    for i, (_, a, b) in enumerate(K.BLOCKS):
        out[(d >= a.toordinal()) & (d <= b.toordinal())] = i
    return out
