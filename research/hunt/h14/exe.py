"""h14: execution policies for the Liquidity 15+5 trade list (h10 Book), per PREREG.md.

A Leg = one option contract's minute paths for every trade of a book (the arm's 1-ITM, or the ATM for the K1 split).
simulate(book, legs, n, policy, model, kappa) re-prices every trade: entry policy E0..E5, exit policy X0..X3, split K1.
Exit DECISIONS (timing, reasons) are the arm's own; only how the orders are worked changes.
"""
from __future__ import annotations

import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h10"))
import cap  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from cap import sim  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.costs import floor_tick  # noqa: E402

W, TICK, CAP, IMP_MAX = C.W, C.TICK, cap.CAP, cap.IMP_MAX
FL, CO = cap.EXE.fills, cap.EXE.costs
NONURG = ("index_target", "time_stop", "square_off")


def ceil_tick(x):
    return np.ceil(np.round(np.asarray(x, float) / TICK, 6)) * TICK


class Leg:
    def __init__(self, O, H, L, Cl, V, lot, dn, c0, xc, why, exit_raw=None, exit_base=None):
        self.O, self.H, self.L = [np.round(a.astype(np.float64), 2) for a in (O, H, L)]
        self.Cl = np.round(Cl.astype(np.float64), 2)
        self.Clf = pd.DataFrame(self.Cl).ffill(axis=1).values
        self.N = len(lot)
        self.lot, self.dn = lot, dn
        self.Vl = np.nan_to_num(V.astype(np.float64)) / lot[:, None]
        self.cum = np.concatenate([np.zeros((self.N, 1)), np.cumsum(self.Vl, axis=1)], axis=1)
        k = np.arange(W)
        self.V5 = self.cum[:, k] - self.cum[:, np.maximum(k - 5, 0)]
        self.c0, self.xc = c0.astype(np.int64), xc.astype(np.int64)
        self.why = why
        self.stop = why == "stop"
        self.nonurg = np.isin(why, NONURG)
        self.r = np.arange(self.N)
        if exit_raw is None:            # ATM leg: market at the arm's exit bar (stop bar exits: the next bar)
            xa = np.minimum(self.xc + self.stop.astype(np.int64), W - 1)
            raw = self.O[self.r, xa]
            raw = np.where(np.isnan(raw), self.Clf[self.r, xa], raw)
            self.xa = xa
            self.exit_raw = raw
            self.exit_base = FL.sell(np.nan_to_num(raw), self.V5[self.r, xa])
        else:
            self.xa = self.xc.copy()
            self.exit_raw, self.exit_base = exit_raw, exit_base

    def vacc(self, k, s0):
        """lots available to a parent order started at column s0, for a child at column k (v5 at s0 + traded since)."""
        k = np.asarray(k)
        return self.cum[self.r, np.minimum(k, W)] - self.cum[self.r, np.maximum(s0 - 5, 0)]


def leg_itm(b: cap.Book):
    A = b.pk.store.arr
    r = b.pk.meta.crow.values[b.tr.row.values]
    tr = b.tr
    ri = np.arange(len(tr))
    xc = tr.xcol.values
    trig = floor_tick(tr.entry.values * 0.85)
    Ox = b.O[ri, xc]
    graw = np.where(tr.why.values == "stop", np.fmin(trig, Ox), np.where(np.isnan(Ox), b.Clf[ri, xc], Ox))
    return Leg(A["O"][r], A["H"][r], A["L"][r], A["Cl"][r], A["V"][r], b.lot, b.dn, tr.c0.values, xc, tr.why.values,
               graw, tr.exit.values.astype(float))


ATM = None


def leg_atm(b: cap.Book):
    global ATM
    if ATM is None:
        with open(os.path.join(C.CACHE, "h14", "atm.pkl"), "rb") as f:
            ATM = pickle.load(f)
    tr = b.tr
    N = len(tr)
    arr = np.full((5, N, W), np.nan, np.float32)
    have = np.zeros(N, bool)
    for i, rr in enumerate(tr[["und", "day", "side", "strike"]].itertuples(index=False)):
        k = (rr.und, rr.day.date(), "C" if rr.side > 0 else "P", int(rr.strike) + int(rr.side) * C.STEP[rr.und])
        a = ATM.get(k)
        if a is not None:
            arr[:, i] = a
            have[i] = True
    lg = Leg(arr[0], arr[1], arr[2], arr[3], arr[4], b.lot, b.dn, tr.c0.values, tr.xcol.values, tr.why.values)
    lg.have = have
    return lg


def delta_hat(b: cap.Book, la: Leg):
    tr = b.tr
    ri = np.arange(len(tr))
    cm = np.maximum(tr.c0.values - 1, 0)
    pi = b.Clf[ri, cm]
    pa = la.Clf[ri, cm]
    step = tr.und.map(C.STEP).values.astype(float)
    d = (pi - pa) / step
    d = np.where(np.isfinite(d), np.clip(d, 0.2, 1.0), 0.6)
    return d


# ------------------------------------------------------------------------------------------------ order mechanics
class Acc:
    """per-trade accumulators for one leg."""

    def __init__(self, N):
        z = lambda: np.zeros(N)  # noqa: E731
        self.Q, self.buy_cash, self.buy_raw, self.chg, self.sell_cash, self.sell_raw = z(), z(), z(), z(), z(), z()
        self.slices, self.last_buy = z(), np.full(N, -1)
        self.end = np.full(N, -1)
        self.pas_buy, self.pas_sell = z(), z()


def _imp(q, v, kappa):
    return np.minimum(IMP_MAX, kappa * np.sqrt(np.asarray(q, float) / np.maximum(v, 1.0)))


def _buy(lg, ac, m, k, q, model, kappa, qcum, s0, raw=None):
    """aggressive buy child: rows m, column k (array), q lots. qcum: aggressive lots of this parent BEFORE this child."""
    m = m & (q > 0)
    if not m.any():
        return
    i = np.nonzero(m)[0]
    kk = k[i]
    rw = lg.O[i, kk] if raw is None else raw[i]
    base = FL.buy(rw, lg.V5[i, kk])
    if model == "M1":
        im = _imp(q[i], lg.V5[i, kk], kappa)
    else:
        im = _imp(qcum[i] + q[i], lg.cum[i, kk] - lg.cum[i, np.maximum(s0[i] - 5, 0)], kappa)
    p = base * (1 + im)
    u = q[i] * lg.lot[i]
    ac.buy_cash[i] += p * u
    ac.buy_raw[i] += rw * u
    ac.chg[i] += CO.charge(True, p, u, lg.dn[i])
    ac.Q[i] += q[i]
    ac.slices[i] += 1
    ac.last_buy[i] = np.maximum(ac.last_buy[i], kk)
    qcum[i] += q[i]


def _pas(lg, ac, m, k, q, L, buy):
    m = m & (q > 0)
    if not m.any():
        return
    i = np.nonzero(m)[0]
    u = q[i] * lg.lot[i]
    p = L[i]
    k = np.broadcast_to(np.asarray(k), (lg.N,))[i]
    if buy:
        ac.buy_cash[i] += p * u
        ac.buy_raw[i] += p * u
        ac.Q[i] += q[i]
        ac.last_buy[i] = np.maximum(ac.last_buy[i], k)
        ac.pas_buy[i] += q[i]
    else:
        ac.sell_cash[i] += p * u
        ac.sell_raw[i] += p * u
        ac.end[i] = np.maximum(ac.end[i], k)
        ac.pas_sell[i] += q[i]
    ac.chg[i] += CO.charge(buy, p, u, lg.dn[i])
    ac.slices[i] += 1


def _sell(lg, ac, m, k, q, model, kappa, qcum, s0, raw, base):
    m = m & (q > 0)
    if not m.any():
        return
    i = np.nonzero(m)[0]
    kk = k if np.ndim(k) == 0 else k[i]
    kk = np.broadcast_to(kk, i.shape)
    if model == "M1":
        im = _imp(q[i], lg.V5[i, kk], kappa)
    else:
        im = _imp(qcum[i] + q[i], lg.cum[i, kk] - lg.cum[i, np.maximum(s0[i] - 5, 0)], kappa)
    p = base[i] * (1 - im)
    u = q[i] * lg.lot[i]
    ac.sell_cash[i] += p * u
    ac.sell_raw[i] += raw[i] * u
    ac.chg[i] += CO.charge(False, p, u, lg.dn[i])
    ac.end[i] = np.maximum(ac.end[i], kk)
    ac.slices[i] += 1
    qcum[i] += q[i]


def capq(v):
    return np.maximum(1.0, np.floor(CAP * v))


# ------------------------------------------------------------------------------------------------ entry policies
def entry(lg, ac, n, pol, model, kappa):
    N, r, c0, xc = lg.N, lg.r, lg.c0, lg.xc
    n = np.asarray(n, float).copy()
    valid0 = ~np.isnan(lg.O[r, c0])
    n = np.where(valid0, n, 0.0)
    qcum = np.zeros(N)
    if pol == "E0":
        q = np.minimum(n, capq(lg.V5[r, c0]))
        _buy(lg, ac, n > 0, c0, q, model, kappa, qcum, c0)
        return
    if pol in ("E4", "E5"):
        K = 3 if pol == "E4" else 5
        carry = np.zeros(N)
        for j in range(K):
            k = np.minimum(c0 + j, W - 1)
            d = np.ceil(n * (j + 1) / K) - np.ceil(n * j / K) + carry
            ok = (j == 0) | ((k < xc) & ~np.isnan(lg.O[r, k]))
            q = np.where(ok, np.minimum(d, capq(lg.V5[r, k])), 0.0)
            carry = np.where(ok, 0.0, d)
            _buy(lg, ac, ok & (n > 0), k, q, model, kappa, qcum, c0)
        return
    # limit orders E1 / E2 / E3
    P0 = lg.O[r, c0]
    if pol == "E1":
        L = P0.copy()
        qi = np.zeros(N)
    else:
        L = floor_tick(np.nan_to_num(P0) * 1.005)
        base = FL.buy(np.nan_to_num(P0), lg.V5[r, c0])
        v = lg.V5[r, c0]
        kk = kappa * (1.5 if pol == "E2m" else 1.0)     # E2m (post-hoc, info only): MARGINAL price <= L
        qmax = np.where(L > base, np.floor(np.maximum(v, 1.0) * ((L / base - 1) / kk) ** 2 + 1e-9), 0.0)
        qi = np.minimum(np.minimum(n, capq(v)), qmax)
        _buy(lg, ac, n > 0, c0, qi, model, kappa, qcum, c0)
    R = n - qi
    for j in (1, 2, 3):
        k = np.minimum(c0 + j, W - 1)
        lo = lg.L[r, k]
        hit = (k < xc) & (R > 0) & (lo <= L - TICK + 1e-9) & (lg.Vl[r, k] > 0)
        q = np.where(hit, np.minimum(R, capq(lg.Vl[r, k])), 0.0)
        _pas(lg, ac, hit, k, q, L, True)
        R = R - q
    if pol == "E3":
        k = np.minimum(c0 + 4, W - 1)
        ok = (R > 0) & (k < xc) & ~np.isnan(lg.O[r, k])
        q = np.where(ok, np.minimum(R, capq(lg.V5[r, k])), 0.0)
        _buy(lg, ac, ok, k, q, model, kappa, qcum, c0)


# ------------------------------------------------------------------------------------------------ exit policies
def work(lg, ac, R, start, model, kappa, qcum, s0, first_raw=None, first_base=None):
    """h10 exit working: first child at `start` (cap on v5) at first_raw/first_base (default: the minute's forward-filled
    close), then each later minute up to 15% of its traded lots at its close; all that is left at the last column."""
    r = lg.r
    start = np.minimum(start, W - 1)
    R = R.copy()
    if first_raw is None:
        first_raw = lg.Clf[r, start]
        first_base = FL.sell(np.nan_to_num(first_raw), lg.V5[r, start])
    m = R > 0
    q = np.where(m, np.minimum(R, capq(lg.V5[r, start])), 0.0)
    q = np.where(start >= W - 1, R, q)
    _sell(lg, ac, m, start, q, model, kappa, qcum, s0, first_raw, first_base)
    R -= q
    if not (R > 0).any():
        return
    k = int(start[R > 0].min()) + 1
    while k < W and (R > 0).any():
        act = (R > 0) & (k > start)
        if act.any():
            vm = lg.Vl[:, k]
            allow = np.where((vm > 0) & ~np.isnan(lg.Cl[:, k]), capq(vm), 0.0)
            if k == W - 1:
                allow = R.copy()
            sl = np.where(act, np.minimum(R, allow), 0.0)
            raw = lg.Clf[:, k]
            base = FL.sell(np.nan_to_num(raw), lg.V5[:, k])
            _sell(lg, ac, sl > 0, k, sl, model, kappa, qcum, s0, raw, base)
            R -= sl
        k += 1


def exit_(lg, ac, pol, model, kappa, pstar=None):
    N, r, xc = lg.N, lg.r, lg.xa
    R = ac.Q.copy()
    qcum = np.zeros(N)
    if pol == "X2" and pstar is not None:
        st = np.maximum(lg.c0, ac.last_buy) + 1
        for k in range(int(st.min()) if N else W, int(lg.xc.max()) if N else W):
            act = (R > 0) & (k >= st) & (k < lg.xc)
            if not act.any():
                continue
            hit = act & (lg.H[:, k] >= pstar + TICK - 1e-9) & (lg.Vl[:, k] > 0)
            q = np.where(hit, np.minimum(R, capq(lg.Vl[:, k])), 0.0)
            _pas(lg, ac, hit, k, q, pstar, False)
            R -= q
    if pol == "X1":
        nu = lg.nonurg & (R > 0)
        L = lg.Clf[r, np.maximum(lg.xc - 1, 0)]
        for j in range(3):
            k = np.minimum(lg.xc + j, W - 1)
            hit = nu & (R > 0) & (lg.H[r, k] >= L + TICK - 1e-9) & (lg.Vl[r, k] > 0) & (lg.xc + j <= W - 1)
            q = np.where(hit, np.minimum(R, capq(lg.Vl[r, k])), 0.0)
            _pas(lg, ac, hit, k, q, L, False)
            R -= q
        s = np.minimum(lg.xc + 3, W - 1)
        Ru = np.where(nu, R, 0.0)
        work(lg, ac, Ru, s, model, kappa, qcum, s)
        R = np.where(nu, 0.0, R)
    if pol == "X3":
        nu = lg.nonurg & (R > 0)
        Rn = np.where(nu, R, 0.0)
        tot = Rn.copy()
        carry = np.zeros(N)
        for j in range(3):
            k = np.minimum(xc + j, W - 1)
            d = np.ceil(tot * (j + 1) / 3) - np.ceil(tot * j / 3) + carry
            if j == 0:
                raw, base = lg.exit_raw, lg.exit_base
                ok = nu
            else:
                raw = lg.O[r, k]
                ok = nu & ~np.isnan(raw) & (xc + j <= W - 1)
                base = FL.sell(np.nan_to_num(raw), lg.V5[r, k])
            q = np.where(ok, np.minimum(np.minimum(d, Rn), capq(lg.V5[r, k])), 0.0)
            carry = d - q
            _sell(lg, ac, ok, k, q, model, kappa, qcum, xc, raw, base)
            Rn -= q
        s = np.minimum(xc + 3, W - 1)
        work(lg, ac, Rn, s, model, kappa, qcum, xc)
        R = np.where(nu, 0.0, R)
    # everything else (and X0): h10 working from the arm's exit
    work(lg, ac, R, xc, model, kappa, qcum, xc, lg.exit_raw, lg.exit_base)


def simulate(b: cap.Book, legs, n0, pol, model="M2", kappa=0.02):
    """pol = dict(entry, exit, split). legs = dict(itm=Leg, atm=Leg or None, dhat=array). Returns per-trade frame."""
    li = legs["itm"]
    N = li.N
    n0 = np.asarray(n0, float) * np.ones(N)
    parts = []
    if pol.get("split"):
        la = legs["atm"]
        r = li.r
        vI = li.V5[r, li.c0]
        okA = la.have & ~np.isnan(la.O[r, la.c0])
        vA = np.where(okA, la.V5[r, la.c0], 0.0)
        nA = np.where(okA & (vI + vA > 0), np.round(n0 * vA / np.maximum(vI + vA, 1e-9)), 0.0)
        parts = [(li, n0 - nA, "itm"), (la, nA, "atm")]
    else:
        parts = [(li, n0, "itm")]
    tot = None
    for lg, n, nm in parts:
        ac = Acc(N)
        entry(lg, ac, n, pol["entry"], model, kappa)
        ps = None
        if pol["exit"] == "X2" and nm == "itm":
            tr = b.tr
            ps = np.where(np.isfinite(tr.tgt.values),
                          ceil_tick(tr.entry.values + legs["dhat"] * np.abs(tr.tgt.values - tr.ref.values)), np.inf)
        exit_(lg, ac, pol["exit"] if not (pol["exit"] == "X2" and nm == "atm") else "X0", model, kappa, ps)
        o = dict(gross=ac.sell_raw - ac.buy_raw, charges=ac.chg, net=ac.sell_cash - ac.buy_cash - ac.chg, lots=ac.Q,
                 desired=np.asarray(n, float), prem=ac.buy_cash, end=ac.end, slices=ac.slices, pas_buy=ac.pas_buy,
                 pas_sell=ac.pas_sell, impact_free_cost=0.0)
        if tot is None:
            tot = o
        else:
            for k in ("gross", "charges", "net", "lots", "desired", "prem", "slices", "pas_buy", "pas_sell"):
                tot[k] = tot[k] + o[k]
            tot["end"] = np.maximum(tot["end"], o["end"])
    out = pd.DataFrame(dict(gross=tot["gross"], charges=tot["charges"], net=tot["net"], lots=tot["lots"],
                            desired=n0, prem=tot["prem"], exit_end_min=np.maximum(tot["end"], li.xc) + C.OPEN_M,
                            slices=tot["slices"], clipped=tot["lots"] < n0, missed=tot["lots"] == 0,
                            pas_buy=tot["pas_buy"], pas_sell=tot["pas_sell"]))
    out.index = b.tr.index
    return out
