"""R90 structure engine (PREREG section 3): bars, swings, trend, CHoCH and BOS on one timeframe.

aggregate()   epoch-aligned bars of `minutes` from 1-minute bars (0 = one bar per session); a bar never spans sessions
path()        the open -> nearer extreme -> far extreme -> close path of one bar (a tie goes to the high first)
reach()       when, along a path, price first gets beyond (strict) / to (non-strict) a level
structure()   the per-bar state after each bar's close: trend, active / protected high and low, their swing ids, BOS and
              CHoCH events

Everything is computed bar by bar at the bar's close from that bar and earlier ones only; a swing at bar p is confirmed at
the close of bar p + D and changes the levels only from bar p + D + 1 (look-ahead guard in tests/test_r90.py).
"""
from __future__ import annotations

import math
from dataclasses import dataclass

import numpy as np

INF = math.inf


# ------------------------------------------------------------------------------------------------ bars
def aggregate(mday: np.ndarray, ep_min: np.ndarray, minutes: int, mo, mh, ml, mc) -> dict:
    """Bars of `minutes` (epoch-aligned: floor(unix minute / minutes)); minutes == 0 -> one bar per session.
    mday: session index of each 1-minute bar; ep_min: its unix minute (UTC). Returns o, h, l, c, s, e (first / last
    1-minute index), day."""
    mday = np.asarray(mday, np.int64)
    if minutes > 0:
        b = np.asarray(ep_min, np.int64) // minutes
        new = np.r_[True, (b[1:] != b[:-1]) | (mday[1:] != mday[:-1])]
    else:
        new = np.r_[True, mday[1:] != mday[:-1]]
    brk = np.flatnonzero(new)
    end = np.r_[brk[1:], len(mday)] - 1
    return dict(o=np.asarray(mo, float)[brk], h=np.maximum.reduceat(np.asarray(mh, float), brk),
                l=np.minimum.reduceat(np.asarray(ml, float), brk), c=np.asarray(mc, float)[end],
                s=brk.astype(np.int64), e=end.astype(np.int64), day=mday[brk])


# ------------------------------------------------------------------------------------------------ the bar path
def path(o: float, h: float, l: float, c: float) -> tuple:
    """open -> the extreme nearer the open -> the other extreme -> close (equal distance: the high first)."""
    return (o, h, l, c) if (h - o) <= (o - l) else (o, l, h, c)


def reach(seq, level: float, up: bool, strict: bool) -> float:
    """Path distance at which price first gets above (up) / below (down) `level` (strict) or to it (non-strict).
    0 if the path starts there; INF if never."""
    a = seq[0]
    if up and (a > level if strict else a >= level):
        return 0.0
    if not up and (a < level if strict else a <= level):
        return 0.0
    cum = 0.0
    for b in seq[1:]:
        if up and b > a and (b > level if strict else b >= level):
            return cum + (level - a)
        if not up and b < a and (b < level if strict else b <= level):
            return cum + (a - level)
        cum += abs(b - a)
        a = b
    return INF


def subpath(seq, t0: float) -> tuple:
    """The rest of the path from distance t0 (its first point is the price at t0)."""
    if t0 <= 0:
        return tuple(seq)
    cum = 0.0
    for i in range(1, len(seq)):
        a, b = seq[i - 1], seq[i]
        d = abs(b - a)
        if cum + d >= t0:
            frac = (t0 - cum)
            p = a + frac if b >= a else a - frac
            return (p,) + tuple(seq[i:])
        cum += d
    return (seq[-1],)


# ------------------------------------------------------------------------------------------------ structure
@dataclass
class Struct:
    trend: np.ndarray       # +1 up, -1 down, 0 unknown (after the bar's close)
    H: np.ndarray           # uptrend: active swing high (BOS level); downtrend: protected high
    Hid: np.ndarray         # bar index of the swing (or extreme) that set H; -1 none
    Hok: np.ndarray         # H is an ACTIVE level, unbroken (uptrend only)
    L: np.ndarray           # downtrend: active swing low; uptrend: protected low
    Lid: np.ndarray
    Lok: np.ndarray         # L is an ACTIVE level, unbroken (downtrend only)
    bos: np.ndarray         # +1 BOS up / -1 BOS down on this bar
    choch: np.ndarray       # +1 CHoCH up / -1 CHoCH down on this bar


def structure(o, h, l, c, D: int = 2, reset=None) -> Struct:
    """PREREG 3 'Structure', bar by bar. reset[i] True restarts the engine at bar i (MCX contract change)."""
    o, h, l, c = (np.asarray(x, float) for x in (o, h, l, c))
    n = len(o)
    out = Struct(np.zeros(n, np.int8), np.full(n, np.nan), np.full(n, -1, np.int64), np.zeros(n, bool),
                 np.full(n, np.nan), np.full(n, -1, np.int64), np.zeros(n, bool), np.zeros(n, np.int8),
                 np.zeros(n, np.int8))

    def fresh():
        return dict(tr=0, H=np.nan, Hb=-1, Hok=False, Hsw=False, L=np.nan, Lb=-1, Lok=False, Lsw=False,
                    sh=[], sl=[], fh=None, fl=None, seg=0)

    s = fresh()

    def init(i):
        (bh, ph), (bl, pl) = s["fh"], s["fl"]
        up = bl < bh or (bl == bh and c[bl] >= o[bl])
        s.update(H=ph, Hb=bh, L=pl, Lb=bl, Hsw=up, Lsw=not up, Hok=up, Lok=not up, tr=1 if up else -1)

    def new_high(p, price):
        s["sh"].append((p, price))
        tr = s["tr"]
        if tr == 0:
            if s["fh"] is None:
                s["fh"] = (p, price)
                if s["fl"] is not None:
                    init(p)
            return
        if tr == 1 and (not (s["H"] == s["H"]) or price > s["H"]):
            if s["Hsw"]:
                prev = s["Hb"]
                cand = [(q, b) for (b, q) in s["sl"] if prev < b < p]
                if cand:
                    q, b = min(cand)
                    s["L"], s["Lb"] = q, b
            s.update(H=price, Hb=p, Hok=True, Hsw=True)

    def new_low(p, price):
        s["sl"].append((p, price))
        tr = s["tr"]
        if tr == 0:
            if s["fl"] is None:
                s["fl"] = (p, price)
                if s["fh"] is not None:
                    init(p)
            return
        if tr == -1 and (not (s["L"] == s["L"]) or price < s["L"]):
            if s["Lsw"]:
                prev = s["Lb"]
                cand = [(q, b) for (b, q) in s["sh"] if prev < b < p]
                if cand:
                    q, b = max(cand)
                    s["H"], s["Hb"] = q, b
            s.update(L=price, Lb=p, Lok=True, Lsw=True)

    for i in range(n):
        if reset is not None and reset[i] and i > 0:
            s = fresh()
            s["seg"] = i
        # 1. events on this bar against the levels known before it
        if s["tr"] != 0:
            seq = path(o[i], h[i], l[i], c[i])
            tu = td = INF
            if s["tr"] == 1:
                if s["Hok"]:
                    tu = reach(seq, s["H"], True, True)
                if s["L"] == s["L"]:
                    td = reach(seq, s["L"], False, True)
            else:
                if s["H"] == s["H"]:
                    tu = reach(seq, s["H"], True, True)
                if s["Lok"]:
                    td = reach(seq, s["L"], False, True)
            evs = sorted([(tu, 0), (td, 1)])
            for t, kind in evs:
                if t == INF:
                    break
                if kind == 0:                          # price above H
                    if s["tr"] == 1:                   # BOS up
                        s["Hok"] = False
                        out.bos[i] = 1
                    else:                              # CHoCH up: the bottom of the failed down-leg is protected
                        a = max(s["Hb"], s["seg"])
                        j = a + int(np.argmin(l[a:i + 1]))
                        s.update(tr=1, L=float(l[j]), Lb=j, Lok=False, Lsw=False, H=np.nan, Hb=-1, Hok=False,
                                 Hsw=False)
                        out.choch[i] = 1
                        break
                else:                                  # price below L
                    if s["tr"] == -1:                  # BOS down
                        s["Lok"] = False
                        out.bos[i] = -1
                    else:                              # CHoCH down: the top of the failed up-leg is protected
                        a = max(s["Lb"], s["seg"])
                        j = a + int(np.argmax(h[a:i + 1]))
                        s.update(tr=-1, H=float(h[j]), Hb=j, Hok=False, Hsw=False, L=np.nan, Lb=-1, Lok=False,
                                 Lsw=False)
                        out.choch[i] = -1
                        break
        # 2. swings confirmed by this bar's close (pivot p = i - D)
        p = i - D
        if p - D >= s["seg"]:
            isl = l[p] <= l[p - D:p].min() and l[p] <= l[p + 1:i + 1].min()
            ish = h[p] >= h[p - D:p].max() and h[p] >= h[p + 1:i + 1].max()
            if isl:
                new_low(p, float(l[p]))
            if ish:
                new_high(p, float(h[p]))
        out.trend[i] = s["tr"]
        out.H[i], out.Hid[i], out.Hok[i] = s["H"], s["Hb"], s["Hok"]
        out.L[i], out.Lid[i], out.Lok[i] = s["L"], s["Lb"], s["Lok"]
    return out


def swings(h, l, D: int):
    """All swing highs / lows (bool arrays by pivot bar), for tests and descriptions; confirmed at p + D."""
    h, l = np.asarray(h, float), np.asarray(l, float)
    n = len(h)
    hs, ls = np.zeros(n, bool), np.zeros(n, bool)
    for p in range(D, n - D):
        hs[p] = h[p] >= h[p - D:p].max() and h[p] >= h[p + 1:p + D + 1].max()
        ls[p] = l[p] <= l[p - D:p].min() and l[p] <= l[p + 1:p + D + 1].min()
    return hs, ls
