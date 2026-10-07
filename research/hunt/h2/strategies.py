"""Long-only cross-sectional intraday signal families on the F&O stocks. Each builder returns dict of arrays
(s, d, te, stop, tgt, tx) for the days in `dmask`. All decisions use bars up to the signal bar only."""
from __future__ import annotations

import itertools

import numpy as np
import pandas as pd

from lib import Mkt, SQ

NAN = np.nan


def topk(score, valid, K):
    """score (S, D); per day the K highest valid scores. Returns (s, d)."""
    sc = np.where(valid & np.isfinite(score), score, -np.inf)
    order = np.argsort(-sc, axis=0)[:K]                      # (K, D)
    D = sc.shape[1]
    dd = np.broadcast_to(np.arange(D), order.shape)
    ok = np.isfinite(sc[order, dd])
    return order[ok], dd[ok]


def first_true(mask, lo, hi):
    """mask (n, 375): first column in [lo, hi] that is True, else -1."""
    cols = np.arange(mask.shape[1])[None, :]
    mm = mask & (cols >= lo) & (cols <= hi)
    f = mm.argmax(1)
    return np.where(mm.any(1), f, -1)


def _out(s, d, te, stop=None, tgt=None, tx=None):
    n = len(s)
    return dict(s=np.asarray(s), d=np.asarray(d), te=np.asarray(te),
                stop=np.full(n, NAN) if stop is None else np.asarray(stop, float),
                tgt=np.full(n, NAN) if tgt is None else np.asarray(tgt, float),
                tx=np.full(n, SQ) if tx is None else np.asarray(tx))


# --------------------------------------------------------------------------------------------- F1 ORB on hot stocks
def orb(m: Mkt, dmask, N, gap, K, stopmode, rr, cutoff=105):
    rv = m.relvol(N - 1)
    valid = m.valid & dmask[None, :] & (m.gap >= gap)
    s, d = topk(rv, valid, K)
    H, L, C = m.h[s, d], m.l[s, d], m.cf[s, d]
    orh = np.nanmax(H[:, :N], 1)
    orl = np.nanmin(L[:, :N], 1)
    j = first_true(C > orh[:, None], N, cutoff)
    k = j >= 0
    s, d, j, orh, orl = s[k], d[k], j[k], orh[k], orl[k]
    stop = orl if stopmode == "low" else (orh + orl) / 2
    tgt = orh + rr * (orh - stop) if rr else None
    return _out(s, d, j + 1, stop, tgt)


# --------------------------------------------------------------------------------------------- F2 gap-and-go / gap-fill
def gapplay(m: Mkt, dmask, mode, g, N, tgtmode, K=None):
    green = m.cf[:, :, N - 1] > m.op
    if mode == "go":
        valid = m.valid & dmask[None, :] & (m.gap >= g) & green
    else:  # long gap-fill: gap down, first window green
        valid = m.valid & dmask[None, :] & (m.gap <= -g) & green
    if K:
        s, d = topk(-m.gap if mode == "fill" else m.gap, valid, K)   # the K largest gaps of the day
    else:
        s, d = np.nonzero(valid)
    low = np.nanmin(m.l[s, d, :N], 1)
    tgt = None
    if tgtmode == "pc":
        tgt = m.pc[s, d]
    return _out(s, d, np.full(len(s), N), low, tgt)


# --------------------------------------------------------------------------------------------- F3/F6 relative strength
def rs(m: Mkt, dmask, k, K, stop_pct, side, nifty_up, base="open"):
    r = m.ret_open(k) if base == "open" else m.cf[:, :, k] / m.pc - 1
    rel = r - r[0][None, :]
    valid = m.valid & dmask[None, :]
    if nifty_up == 1:
        valid &= (r[0] > 0)[None, :]
    elif nifty_up == -1:
        valid &= (r[0] < 0)[None, :]
    s, d = topk(rel if side == "top" else -rel, valid, K)
    px = m.cf[s, d, k]
    if stop_pct == "low":
        stop = np.nanmin(m.l[s, d, :k + 1], 1)
    elif stop_pct:
        stop = px * (1 - stop_pct)
    else:
        stop = None
    return _out(s, d, np.full(len(s), k + 1), stop)


# --------------------------------------------------------------------------------------------- F4 VWAP pullback
def vwap_pb(m: Mkt, dmask, K, stop_pct, rr, k0=59):
    r = m.ret_open(k0)
    rel = r - r[0][None, :]
    valid = m.valid & dmask[None, :] & (r > 0) & (m.cf[:, :, k0] > m.vwap[:, :, k0])
    s, d = topk(rel, valid, K)
    L, C, V = m.l[s, d], m.cf[s, d], m.vwap[s, d]
    j = first_true((L <= V) & (C > V), k0 + 1, 285)
    k = j >= 0
    s, d, j = s[k], d[k], j[k]
    vw = m.vwap[s, d, j]
    stop = vw * (1 - stop_pct)
    px = m.cf[s, d, j]
    tgt = px + rr * (px - stop) if rr else None
    return _out(s, d, j + 1, stop, tgt)


# --------------------------------------------------------------------------------------------- F5 5-minute crash fade
_SIG5 = {}


def sig5(m: Mkt):
    if "x" not in _SIG5:
        C = m.cf
        r = C[:, :, 5::5] / C[:, :, :-5:5][:, :, :C[:, :, 5::5].shape[2]] - 1
        sd = np.nanstd(r, axis=2)
        _SIG5["x"] = pd.DataFrame(sd.T).rolling(10, min_periods=5).mean().shift(1).to_numpy().T
    return _SIG5["x"]


def crashfade(m: Mkt, dmask, z0, hold, top_n=100):
    sd = sig5(m)
    rank = np.argsort(np.argsort(-np.nan_to_num(m.adv, nan=-1), axis=0), axis=0)
    valid = m.valid & dmask[None, :] & (rank < top_n) & np.isfinite(sd)
    s, d = np.nonzero(valid)
    C = m.cf[s, d]
    r5 = np.full(C.shape, np.nan, np.float32)
    r5[:, 5:] = C[:, 5:] / C[:, :-5] - 1
    z = r5 / sd[s, d][:, None]
    j = first_true(z < -z0, 15, 330)
    k = j >= 0
    s, d, j = s[k], d[k], j[k]
    mv = -r5[k, j]
    px = m.cf[s, d, j]
    stop = px * (1 - mv)
    return _out(s, d, j + 1, stop, None, j + 1 + hold)


# --------------------------------------------------------------------------------------------- F7 peer-cluster rotation
def clusters(m: Mkt, n_cl=20):
    ism = m.ism
    r = (m.cf[:, ism, -1] / m.op[:, ism] - 1)[1:]            # in-sample open->close returns, stocks only
    r = np.nan_to_num(r - np.nanmean(r, 0))
    x = r / np.maximum(np.linalg.norm(r, axis=1, keepdims=True), 1e-9)
    rng = np.random.default_rng(0)
    cen = x[rng.choice(len(x), n_cl, replace=False)]
    for _ in range(50):
        lab = np.argmax(x @ cen.T, 1)
        cen = np.stack([x[lab == i].mean(0) if (lab == i).any() else cen[i] for i in range(n_cl)])
        cen /= np.maximum(np.linalg.norm(cen, axis=1, keepdims=True), 1e-9)
    return np.concatenate([[-1], lab])


def sector(m: Mkt, dmask, k, K, stop_pct, lab):
    r = m.ret_open(k)
    rel = r - r[0][None, :]
    valid = m.valid & dmask[None, :]
    D = m.D
    ss, dd = [], []
    for di in np.nonzero(dmask)[0]:
        v = valid[:, di]
        best, bv = None, -np.inf
        for c in np.unique(lab[lab >= 0]):
            mem = np.nonzero((lab == c) & v)[0]
            if len(mem) >= 3:
                mv = np.nanmean(rel[mem, di])
                if mv > bv:
                    bv, best = mv, mem
        if best is None:
            continue
        top = best[np.argsort(-np.nan_to_num(rel[best, di], nan=-9))][:K]
        ss += list(top)
        dd += [di] * len(top)
    s, d = np.array(ss, int), np.array(dd, int)
    px = m.cf[s, d, k]
    stop = px * (1 - stop_pct) if stop_pct else None
    return _out(s, d, np.full(len(s), k + 1), stop)


# --------------------------------------------------------------------------------------------- the grid
def grid():
    V = []
    for N, gap, K, sm, rr in itertools.product([5, 15, 30], [-1.0, 0.01, 0.02], [3, 5, 10], ["low", "mid"], [0, 2]):
        V.append(("orb", dict(N=N, gap=gap, K=K, stopmode=sm, rr=rr)))
    for mode, g, N in itertools.product(["go", "fill"], [0.01, 0.02, 0.03, 0.04], [5, 15]):
        for tm in (["eod"] if mode == "go" else ["eod", "pc"]):
            V.append(("gap", dict(mode=mode, g=g, N=N, tgtmode=tm)))
    for k, K, sp, side, nu in itertools.product([14, 29, 59], [1, 3, 5, 10], [0, 0.01, "low"], ["top", "bottom"],
                                                [0, 1]):
        V.append(("rs", dict(k=k, K=K, stop_pct=sp, side=side, nifty_up=nu)))
    for K, sp, rr in itertools.product([5, 10, 20], [0.003, 0.006, 0.01], [0, 2]):
        V.append(("vwap", dict(K=K, stop_pct=sp, rr=rr)))
    # round 2 (added after round 1 showed uncapped gap-fill P&L sits on a few crash days): per-day caps, and
    # 'biggest losers since the previous close'
    for g, N, K, tm in itertools.product([0.01, 0.02, 0.03], [5, 15], [3, 5, 10], ["eod", "pc"]):
        V.append(("gap", dict(mode="fill", g=g, N=N, tgtmode=tm, K=K)))
    for k, K, sp in itertools.product([4, 14, 29], [3, 5, 10], [0, "low"]):
        V.append(("rs", dict(k=k, K=K, stop_pct=sp, side="bottom", nifty_up=0, base="pc")))
    for z0, hold in itertools.product([3, 4, 5, 6], [15, 30, 60, 120]):
        V.append(("crash", dict(z0=z0, hold=hold)))
    for k, K, sp in itertools.product([29, 59], [2, 4], [0, 0.01]):
        V.append(("sector", dict(k=k, K=K, stop_pct=sp)))
    return V


def build(m: Mkt, fam, p, dmask, lab=None):
    if fam == "orb":
        return orb(m, dmask, **p)
    if fam == "gap":
        return gapplay(m, dmask, **p)
    if fam == "rs":
        return rs(m, dmask, **p)
    if fam == "vwap":
        return vwap_pb(m, dmask, **p)
    if fam == "crash":
        return crashfade(m, dmask, **p)
    if fam == "sector":
        return sector(m, dmask, lab=lab, **p)
    raise KeyError(fam)
