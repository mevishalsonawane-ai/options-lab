"""R1 shared: index panels, the option-trade engine (1-ITM nearest expiry, 1 lot), costs, statistics.

Run everything with `python3 -I` (the data tree is untrusted). See PREREG.md for the rules.
"""
from __future__ import annotations

import math
import os
import sys
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))           # research/
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402
from obuy.costs import Costs  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "r1")
os.makedirs(OUT, exist_ok=True)
HOLD0 = date(2025, 10, 1)
HOLD1 = date(2026, 10, 6)
LOT = {"NIFTY": 65, "BANKNIFTY": 30}
HS = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016}
SQ = 355            # 15:10 column
W = C.W
COST = Costs("app")


def col(h, m):
    return h * 60 + m - C.OPEN_M


# ------------------------------------------------------------------------------------------------ index panel
def load_index(u):
    mk = D.market()
    ix = mk.index(u)
    M = ix.mat()
    days = list(ix.days)
    nd = len(days)
    dl = ix.daily()
    ok = np.array([bool(ix.d[d]["real"]) for d in days]) & (np.isnan(M["c"][:, :345]).mean(1) < 0.2)
    c = pd.DataFrame(M["c"]).T.ffill().T.values          # forward-filled closes
    P = dict(u=u, days=days, nd=nd, o=M["o"], h=M["h"], l=M["l"], c=c, ok=ok,
             exp=np.array([bool(ix.d[d]["exp"]) for d in days]),
             dopen=dl["open"].values.astype(float), dhigh=dl["high"].values.astype(float),
             dlow=dl["low"].values.astype(float), dclose=dl["close"].values.astype(float))
    P["pclose"] = np.r_[np.nan, P["dclose"][:-1]]
    P["phigh"] = np.r_[np.nan, P["dhigh"][:-1]]
    P["plow"] = np.r_[np.nan, P["dlow"][:-1]]
    # previous day must be the previous trading row (always true in this list)
    P["hold"] = np.array([d >= HOLD0 for d in days])
    P["inrange"] = np.array([d <= HOLD1 for d in days])
    return P


def vix_minutes(days):
    v = D.market().vix
    mins = v.minutes()
    A = np.full((len(days), W), np.nan)
    for i, d in enumerate(days):
        if d in mins:
            A[i] = mins[d]
    A = pd.DataFrame(A).T.ffill().T.values
    dc = v.daily.close
    vclose = np.array([float(dc.get(d, np.nan)) for d in days])
    return A, vclose


def bars5(P):
    """5-minute OHLC (n_days, 75) from 09:15."""
    o, h, l, c = P["o"], P["h"], P["l"], P["c"]
    n = W // 5
    sh = (P["nd"], n, 5)
    O = o[:, :n * 5].reshape(sh)[:, :, 0]
    H = np.nanmax(h[:, :n * 5].reshape(sh), 2)
    L = np.nanmin(l[:, :n * 5].reshape(sh), 2)
    Cc = c[:, :n * 5].reshape(sh)[:, :, 4]
    return O, H, L, Cc


# ------------------------------------------------------------------------------------------------ engine
def _ff(a):
    out = a.copy()
    m = np.isnan(out)
    if m.all():
        return out
    idx = np.where(~m, np.arange(len(out)), 0)
    np.maximum.accumulate(idx, out=idx)
    out = out[idx]
    out[: np.argmax(~m)] = np.nan
    return out


def run_engine(P, req):
    """req: DataFrame with di, s, side (0 CE, 1 PE), x (index exit column, -1 = none -> 15:10), sp, tp (premium stop /
    target fractions, NaN = none), liq (bool). Returns DataFrame (same index) with E, X, stop, ent, ex, gross, net."""
    u = P["u"]
    step = C.STEP[u]
    q = LOT[u]
    hs = HS[u]
    op = D.market().options(u)
    n = len(req)
    E = np.full(n, np.nan)
    X = np.full(n, np.nan)
    ST = np.zeros(n, bool)
    ENT = np.full(n, -1)
    EX = np.full(n, -1)
    req = req.reset_index(drop=True)
    cur_year = None
    for di, g in req.groupby("di", sort=True):
        d = P["days"][di]
        if d.year != cur_year:
            D.market().release(u)
            cur_year = d.year
        ch = op.chain(d, "near")
        if ch is None or len(ch.K) < 5:
            continue
        cache = {}
        cidx = P["c"][di]
        for r in g.itertuples():
            s = int(r.s)
            if s < 0 or s >= SQ - 1 or not np.isfinite(cidx[s]):
                continue
            atm = round(cidx[s] / step) * step
            side = int(r.side)
            strike = atm - step if side == 0 else atm + step
            key = (strike, side)
            if key not in cache:
                ki = ch.kpos(strike)
                if ki < 0:
                    cache[key] = None
                else:
                    rt = "C" if side == 0 else "P"
                    O, H, L, Cl, V = (getattr(ch, f)[rt][ki] for f in ("o", "h", "l", "c", "v"))
                    pr = np.isfinite(O) & (np.nan_to_num(V) > 0)
                    cache[key] = (O, H, L, _ff(np.where(pr, Cl, np.nan)), pr)
            cc = cache[key]
            if cc is None:
                continue
            O, H, L, Cf, pr = cc
            e = -1
            for j in range(s + 1, min(s + 4, SQ)):
                if pr[j] and O[j] > 1.0:
                    e = j
                    break
            if e < 0:
                continue
            en = float(O[e])
            xe = int(r.x) if r.x >= 0 else SQ
            xe = min(max(xe, e + 1), SQ)
            stop_lvl = en * (1 - r.sp) if np.isfinite(r.sp) else -1.0
            tgt_lvl = en * (1 + r.tp) if np.isfinite(r.tp) else 1e12
            if r.liq:
                stop_lvl = math.floor(en * 0.85 / C.TICK + 1e-9) * C.TICK
                tc = e + 19
                if tc < SQ and np.isfinite(Cf[tc]) and Cf[tc] < en * 1.05:
                    xe = min(xe, tc + 1)
            out_px, out_c, stopped = None, xe, False
            seg = np.arange(e, xe)
            m = pr[seg]
            if m.any():
                ss = seg[m]
                hit_s = ss[L[ss] <= stop_lvl]
                hit_t = ss[H[ss] >= tgt_lvl]
                js = hit_s[0] if len(hit_s) else 10 ** 6
                jt = hit_t[0] if len(hit_t) else 10 ** 6
                if js < 10 ** 6 and js <= jt:
                    out_px = min(stop_lvl, O[js]) if (js > e and np.isfinite(O[js])) else stop_lvl
                    out_c, stopped = js, True
                elif jt < 10 ** 6:
                    out_px = max(tgt_lvl, O[jt]) if (jt > e and np.isfinite(O[jt])) else tgt_lvl
                    out_c = jt
            if out_px is None:
                if xe < W and pr[xe] and np.isfinite(O[xe]):
                    out_px = float(O[xe])
                else:
                    v = Cf[xe - 1]
                    out_px = float(v) if np.isfinite(v) else en
            E[r.Index], X[r.Index], ST[r.Index], ENT[r.Index], EX[r.Index] = en, out_px, stopped, e, out_c
    buy = E * (1 + max(hs, 0.0005))
    sell = X * (1 - max(hs, 0.0005) - np.where(ST, 0.0005, 0.0))
    qq = np.full(n, q, float)
    ok = np.isfinite(E)
    ch_b = np.where(ok, COST.charge(True, np.nan_to_num(buy), qq), 0)
    ch_s = np.where(ok, COST.charge(False, np.nan_to_num(sell), qq), 0)
    net = (sell - buy) * q - ch_b - ch_s
    gross = (X - E) * q
    return pd.DataFrame(dict(E=E, X=X, stop=ST, ent=ENT, ex=EX, gross=gross, net=net))


# ------------------------------------------------------------------------------------------------ statistics
def cl_t(y, g):
    y = np.asarray(y, float)
    n = len(y)
    if n < 3:
        return (float(y.mean()) if n else np.nan), np.nan
    _, gi = np.unique(g, return_inverse=True)
    G = gi.max() + 1
    m = y.mean()
    r = np.bincount(gi, weights=y - m, minlength=G)
    se = math.sqrt(G / max(G - 1, 1) * (r ** 2).sum()) / n
    return float(m), (m / se if se > 0 else np.nan)


def p1(z):
    return 0.5 * math.erfc(z / math.sqrt(2)) if np.isfinite(z) else np.nan


def bh(p):
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pv = p[ok]
    nn = len(pv)
    if nn == 0:
        return q
    o = np.argsort(pv)
    r = pv[o] * nn / np.arange(1, nn + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(nn)
    qq[o] = np.minimum(r, 1)
    q[ok] = qq
    return q


def maxdd(daily):
    eq = np.cumsum(daily)
    return float((np.maximum.accumulate(np.r_[0, eq])[1:] - eq).max()) if len(eq) else 0.0


def stationary_boot_idx(n, B, mean_block=5, seed=7):
    rng = np.random.default_rng(seed)
    p = 1.0 / mean_block
    out = np.empty((B, n), np.int64)
    for b in range(B):
        idx = np.empty(n, np.int64)
        idx[0] = rng.integers(n)
        newb = rng.random(n) < p
        starts = rng.integers(n, size=n)
        for i in range(1, n):
            idx[i] = starts[i] if newb[i] else (idx[i - 1] + 1) % n
        out[b] = idx
    return out
