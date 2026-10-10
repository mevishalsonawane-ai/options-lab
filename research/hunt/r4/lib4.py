"""R4 shared: reuse the R1 engine (1-ITM nearest expiry, 1 lot, app charges + h24 half-spread, option 1-min wick exits),
add FINNIFTY, twins (+-30 min, time-matched), non-overlap filter, summaries. Run with python3 -I.
"""
from __future__ import annotations

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "r1"))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "h37"))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import lib as L  # noqa: E402  (r1)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

L.LOT.update({"NIFTY": 65, "BANKNIFTY": 30, "FINNIFTY": 60})
L.HS.update({"NIFTY": 0.0016, "BANKNIFTY": 0.0016, "FINNIFTY": 0.0042})
OUT = os.path.join(L.C.SCRATCH, "hunt", "r4")
os.makedirs(OUT, exist_ok=True)
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY"]
NRAND = 10
W0, W1 = L.col(9, 20), L.col(14, 45)      # entry window (signal minute)
SQ = L.SQ
NAN = float("nan")
col = L.col


class Req:
    def __init__(self):
        self.rows = []

    def add(self, var, di, s, side, x=-1, sp=NAN, tp=NAN, idx_exit=False, lo=None, hi=None):
        if lo is None:
            lo, hi = max(W0, s - 30), min(W1, s + 30)
        self.rows.append((var, var.split("|")[0], di, int(s), int(side), int(x), sp, tp, False, int(lo), int(hi),
                          bool(idx_exit)))

    def frame(self):
        return pd.DataFrame(self.rows, columns=["var", "fam", "di", "s", "side", "x", "sp", "tp", "liq", "lo", "hi",
                                                "idx_exit"])


def index_exit(P, di, s, side, stop, tgt):
    """first minute j > s where the index touches stop (first) or tgt; exit column j+1, else -1."""
    h, lw = P["h"][di, s + 1:SQ], P["l"][di, s + 1:SQ]
    if side == 0:
        hs = (lw <= stop) if np.isfinite(stop) else np.zeros(len(lw), bool)
        ht = (h >= tgt) if np.isfinite(tgt) else np.zeros(len(h), bool)
    else:
        hs = (h >= stop) if np.isfinite(stop) else np.zeros(len(h), bool)
        ht = (lw <= tgt) if np.isfinite(tgt) else np.zeros(len(lw), bool)
    hit = hs | ht
    if not hit.any():
        return -1
    j = int(np.argmax(hit))
    return s + 1 + j + 1


def add_std(R, base, di, s, side, nat=None, extra_nat=None, times=(30,), eod=False, opt=True):
    """nat: (stop, tgt) index levels -> NAT; extra_nat: dict name -> (stop,tgt) or name -> exit column."""
    if nat is not None:
        R._P_nat(base + "|NAT", di, s, side, nat)
    if extra_nat:
        for nm, v in extra_nat.items():
            R._P_nat(base + "|" + nm, di, s, side, v)
    if opt:
        R.add(base + "|OPT", di, s, side, sp=0.25, tp=0.50)
    for t in times:
        R.add(base + f"|T{t}", di, s, side, x=min(s + 1 + t, SQ), idx_exit=True)
    if eod:
        R.add(base + "|EOD", di, s, side)


class SReq(Req):
    def __init__(self, P):
        super().__init__()
        self.P = P

    def _P_nat(self, var, di, s, side, v):
        if isinstance(v, (int, np.integer)):
            x = int(v)
        else:
            x = index_exit(self.P, di, s, side, v[0], v[1])
        self.add(var, di, s, side, x=x, idx_exit=x >= 0)


def run(P, req):
    key = ["di", "s", "side", "x", "sp", "tp", "liq"]
    u = req[key].drop_duplicates().reset_index(drop=True)
    res = L.run_engine(P, u)
    u = pd.concat([u, res], axis=1)
    return req.merge(u, on=key, how="left")


def nonoverlap(tr, maxday=3):
    """keep, per var and day, signals in time order that start after the previous kept trade's exit; max 3 a day."""
    tr = tr[np.isfinite(tr.net)].sort_values(["var", "di", "s"])
    keep = np.zeros(len(tr), bool)
    v, d, s, ex = tr["var"].values, tr.di.values, tr.s.values, tr.ex.values
    last = None
    busy, cnt = -1, 0
    for i in range(len(tr)):
        k = (v[i], d[i])
        if k != last:
            last, busy, cnt = k, -1, 0
        if s[i] >= busy and cnt < maxday:
            keep[i] = True
            busy = ex[i]
            cnt += 1
    return tr[keep].reset_index(drop=True)


def twins(tr, seed):
    rng = np.random.default_rng(seed)
    r = tr.loc[tr.index.repeat(NRAND)].copy()
    r["rid"] = np.repeat(tr.index.values, NRAND)
    span = (r.hi - r.lo + 1).values
    sr = r.lo.values + (rng.random(len(r)) * span).astype(int)
    r["x"] = np.where(r.x.values >= 0, np.minimum(sr + (r.x.values - r.s.values), SQ), -1)
    r["s"] = sr
    r["side"] = rng.integers(0, 2, len(r))
    r = r[["var", "fam", "di", "s", "side", "x", "sp", "tp", "liq", "rid"]].reset_index(drop=True)
    return r


def summarize(real, rnd, P, mask):
    rows, daily = [], {}
    nses = int(mask.sum())
    rg = rnd[np.isfinite(rnd.net)].groupby("var").net
    rstats = pd.DataFrame(dict(rm=rg.mean(), rv=rg.var(ddof=1), rn=rg.size()))
    for var, g in real.groupby("var"):
        dd = np.zeros(P["nd"])
        np.add.at(dd, g.di.values, g.net.values)
        dser = dd[mask]
        n = len(g)
        m, t = L.cl_t(g.net.values, g.di.values)
        if var in rstats.index and n > 2:
            rm, rv, rn = rstats.loc[var]
            se = math.sqrt(g.net.var(ddof=1) / n + rv / rn)
            zr = (g.net.mean() - rm) / se if se > 0 else np.nan
        else:
            rm, zr = np.nan, np.nan
        yrs = pd.Series(g.net.values).groupby([P["days"][i].year for i in g.di.values]).sum()
        rows.append(dict(var=var, fam=var.split("|")[0], trades=n, rs_day=dser.sum() / max(nses, 1), rs_trade=m,
                         t=t, p=L.p1(t) if n >= 30 else 1.0, win=float((g.net > 0).mean()),
                         gross_trade=float(g.gross.mean()), rand_trade=rm, z_rand=zr,
                         p_rand=L.p1(zr) if n >= 30 else 1.0, maxdd=L.maxdd(dser), total=dser.sum(),
                         yrs_pos=f"{int((yrs > 0).sum())}/{len(yrs)}"))
        daily[var] = dser
    return pd.DataFrame(rows), daily


def reality_check(daily, B=1000, seed=3):
    vv = list(daily)
    M = np.array([daily[k] for k in vv], float).T
    n = M.shape[0]
    mu = M.mean(0)
    sd = M.std(0, ddof=1) + 1e-9
    tst = mu / (sd / np.sqrt(n))
    idx = L.stationary_boot_idx(n, B, 5, seed=seed)
    mx = np.empty(B)
    for b in range(B):
        mb = M[idx[b]].mean(0) - mu
        mx[b] = np.max(mb / (sd / np.sqrt(n)))
    rc = np.array([(mx >= t).mean() for t in tst])
    return dict(zip(vv, rc)), dict(zip(vv, tst))
