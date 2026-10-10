"""h10: capacity-aware fills for the Liquidity 15+5 books (rules unchanged), per PREREG.md.

simulate() re-prices every trade of a book at a given size with a volume participation cap + square-root impact,
exits worked over the following minutes when the cap binds, charges per order slice. Entries/exits (timing) are the
arm's own (h4/h7 port, via research/hunt/h7/sim.py).
"""
from __future__ import annotations

import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h7"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h4"))
import sim  # noqa: E402  (imports obuy, packs from OBUY_CACHE/h7)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402
from obuy.engine import Execution  # noqa: E402

CAP, KAPPA, IMP_MAX = 0.15, 0.02, 0.10
EXE = Execution(expiry="skip", fills=sim.EXES["real"][0], costs=sim.EXES["real"][1])
W = C.W


class Book:
    """Arrays for a set of trades (one execution pack) reused across sizes."""

    def __init__(self, pk, tr):
        self.pk, self.tr = pk, tr.reset_index(drop=True)
        r = pk.meta.crow.values[self.tr.row.values]
        A = pk.store.arr
        self.O = np.round(A["O"][r].astype(np.float64), 2)
        self.Cl = np.round(A["Cl"][r].astype(np.float64), 2)
        V = np.nan_to_num(A["V"][r].astype(np.float64))
        self.lot = self.tr.lot.values.astype(float)
        self.Vl = V / self.lot[:, None]                       # traded lots per minute
        cs = np.concatenate([np.zeros((len(self.tr), 1)), np.cumsum(self.Vl, axis=1)], axis=1)
        k = np.arange(W)
        self.V5 = cs[:, k] - cs[:, np.maximum(k - 5, 0)]     # lots traded in the 5 bars before minute k
        Clf = pd.DataFrame(self.Cl).ffill(axis=1).values
        self.Clf = Clf
        self.dn = self.tr.day.values.astype("datetime64[D]").astype(np.int64)
        self._adds = {}

    def adds(self):
        if "p" not in self._adds:
            self._adds["p"] = sim.adds(self.pk, self.tr, EXE, "prem", 0.10, 3)
        return self._adds["p"]


def imp(q, v, kappa):
    return np.minimum(IMP_MAX, kappa * np.sqrt(np.asarray(q, float) / np.maximum(v, 1.0)))


def simulate(b: Book, n0, add_n=0.0, cap=CAP, kappa=KAPPA):
    """n0: desired initial lots per trade (array); add_n: lots per premium add (0 = no adds).
    Returns per-trade DataFrame (gross, charges, net, lots, desired, prem, exit_end_min, slices, clipped)."""
    tr = b.tr
    N = len(tr)
    ri = np.arange(N)
    fl, co = EXE.fills, EXE.costs
    lot, dn = b.lot, b.dn
    c0, xc = tr.c0.values, tr.xcol.values
    e = tr.entry.values
    n0 = np.asarray(n0, float)
    # entry
    v = b.V5[ri, c0]
    q0 = np.minimum(n0, np.maximum(1.0, np.floor(cap * v)))
    pe = e * (1 + imp(q0, v, kappa))
    ge = b.O[ri, c0]
    gross_g = -ge * q0 * lot
    cash = -pe * q0 * lot
    chg = co.charge(True, pe, q0 * lot, dn)
    prem = pe * q0 * lot
    Q = q0.copy()
    desired = n0.copy()
    clipped = q0 < n0
    if add_n:
        for ok, fc, px in b.adds():
            va = b.V5[ri, fc]
            qa = np.where(ok, np.minimum(add_n, np.maximum(1.0, np.floor(cap * va))), 0.0)
            pa = np.where(ok, px * (1 + imp(np.maximum(qa, 1), va, kappa)), 0.0)
            cash -= pa * qa * lot
            gross_g -= np.where(ok, b.O[ri, fc], 0.0) * qa * lot
            chg += np.where(ok, co.charge(True, np.where(ok, pa, 1.0), np.maximum(qa, 1) * lot, dn), 0.0)
            prem += pa * qa * lot
            Q += qa
            desired += np.where(ok, add_n, 0.0)
            clipped |= ok & (qa < add_n)
    # exit: first slice at the arm's exit fill
    vx = b.V5[ri, xc]
    a = np.minimum(Q, np.maximum(1.0, np.floor(cap * vx)))
    px = tr.exit.values * (1 - imp(a, vx, kappa))
    trig = floor_tick(e * 0.85)
    Ox = b.O[ri, xc]
    graw = np.where(tr.why.values == "stop", np.fmin(trig, Ox), np.where(np.isnan(Ox), b.Clf[ri, xc], Ox))
    cash += px * a * lot
    gross_g += graw * a * lot
    chg += co.charge(False, px, a * lot, dn)
    R = Q - a
    end = xc.copy()
    slices = np.ones(N)
    k = int(xc[R > 0].min()) + 1 if (R > 0).any() else W
    while k < W and (R > 0).any():
        act = (R > 0) & (k > xc)
        if act.any():
            vm = b.Vl[:, k]
            allow = np.where((vm > 0) & ~np.isnan(b.Cl[:, k]), np.maximum(1.0, np.floor(cap * vm)), 0.0)
            if k == W - 1:
                allow = R.copy()
            sl = np.where(act, np.minimum(R, allow), 0.0)
            m = sl > 0
            if m.any():
                raw = b.Clf[m, k]
                v5k = b.V5[m, k]
                p = fl.sell(raw, v5k) * (1 - imp(sl[m], v5k, kappa))
                cash[m] += p * sl[m] * lot[m]
                gross_g[m] += raw * sl[m] * lot[m]
                chg[m] += co.charge(False, p, sl[m] * lot[m], dn[m])
                R[m] -= sl[m]
                end[m] = k
                slices[m] += 1
        k += 1
    out = pd.DataFrame(dict(gross=gross_g, charges=chg, net=cash - chg, lots=Q, desired=desired, prem=prem,
                            exit_end_min=end + C.OPEN_M, slices=slices, clipped=clipped))
    out.index = tr.index
    return out


def room_units(tr, edges):
    r = np.where(np.isfinite(tr.room.values), tr.room.values, 1e9)
    return 1.0 + np.searchsorted(np.array(edges), r, side="right")


def gate(t, L_d=None, L_m=None):
    """t: trades with day, entry_min, exit_end_min, net (all books). Keep a trade unless, at its entry minute, the
    day's realised net <= -L_d, or the month-to-date realised net <= -L_m. Returns boolean keep (aligned to t)."""
    if L_d is None and L_m is None:
        return np.ones(len(t), bool)
    o = np.lexsort((t.entry_min.values, t.day.values.astype("datetime64[D]").astype(np.int64)))
    day = t.day.values[o]
    mon = pd.DatetimeIndex(day).to_period("M").astype(str).values
    em, xm, net = t.entry_min.values[o], t.exit_end_min.values[o], t.net.values[o]
    keep = np.zeros(len(t), bool)
    cur_d = cur_m = None
    mtd_prev = 0.0
    open_ = []          # (exit_end, net) of taken trades today
    for i in range(len(o)):
        if mon[i] != cur_m:
            cur_m, mtd_prev = mon[i], 0.0
            cur_d = None
        if day[i] != cur_d:
            if cur_d is not None:
                mtd_prev += sum(n for _, n in open_)
            cur_d, open_ = day[i], []
        real = sum(n for x, n in open_ if x < em[i])
        if L_d is not None and real <= -L_d:
            continue
        if L_m is not None and mtd_prev + real <= -L_m:
            continue
        keep[o[i]] = True
        open_.append((xm[i], net[i]))
    return keep
