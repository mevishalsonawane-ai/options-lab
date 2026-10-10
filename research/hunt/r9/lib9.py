"""R9 shared: index panels, option-complex volume (OPTV) and signed flow (FLOW), futures volume (FUTV), volume / TPO
profiles, value areas, HVN/LVN, statistics. Run everything with python3 -I. See PREREG.md."""
from __future__ import annotations

import math
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(os.path.dirname(HERE), "r1"))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import lib as L  # noqa: E402  (r1: obuy index panels, stats)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

C = L.C
SCR = C.SCRATCH
OUT = os.path.join(SCR, "hunt", "r9")
os.makedirs(OUT, exist_ok=True)
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]
HOLD0 = pd.Timestamp("2025-10-01").date()
HOLD1 = pd.Timestamp("2026-10-06").date()
W = C.W
FLAG = os.path.join(OUT, "HOLDOUT_OPENED")


def col(h, m):
    return h * 60 + m - C.OPEN_M


# ------------------------------------------------------------------ panels
_P = {}


def panel(u):
    """index panel + OPTV/FLOW (+FUTV where it exists) aligned on the index days."""
    if u in _P:
        return _P[u]
    P = L.load_index(u)
    z = np.load(os.path.join(OUT, f"vol_{u}.npz"))
    dn = {int(d): i for i, d in enumerate(z["days"])}
    nd = P["nd"]
    optv = np.full((nd, W), np.nan, np.float32)
    flow = np.full((nd, W), np.nan, np.float32)
    futv = np.full((nd, W), np.nan, np.float32)
    has_f = np.zeros(nd, bool)
    for i, d in enumerate(P["days"]):
        j = dn.get(L.D.dnum(d))
        if j is not None:
            optv[i], flow[i] = z["optv"][j], z["flow"][j]
    fz = os.path.join(OUT, f"fut_{u}.npz")
    if os.path.exists(fz):
        f = np.load(fz)
        fd = {int(d): i for i, d in enumerate(f["days"])}
        for i, d in enumerate(P["days"]):
            j = fd.get(L.D.dnum(d))
            if j is not None:
                futv[i] = f["v"][j]
                has_f[i] = np.nansum(f["v"][j]) > 0
    P["optv"], P["flow"], P["futv"], P["has_fut"] = optv, flow, futv, has_f
    P["date"] = np.array(P["days"])
    P["isdes"] = np.array([d < HOLD0 for d in P["days"]])
    P["ishold"] = np.array([(d >= HOLD0) and (d <= HOLD1) for d in P["days"]])
    h = np.where(np.isfinite(P["h"]), P["h"], P["c"])
    lo = np.where(np.isfinite(P["l"]), P["l"], P["c"])
    P["hh"], P["ll"] = h, lo
    _P[u] = P
    return P


# ------------------------------------------------------------------ profiles
def profile(h, l, w, ref, upto=None, min_n=30):
    """volume-at-price: weights w spread evenly over bins covered by [l, h]. Returns (edges0, bw, prof)."""
    if upto is not None:
        h, l, w = h[:upto], l[:upto], w[:upto]
    ok = np.isfinite(h) & np.isfinite(l) & np.isfinite(w) & (w > 0)
    if ok.sum() < min_n:
        return None
    h, l, w = h[ok], l[ok], w[ok]
    bw = max(round(ref * 0.0005 / 0.05) * 0.05, 0.05)
    e0 = math.floor(l.min() / bw) * bw
    nb = int(math.ceil((h.max() - e0) / bw)) + 1
    a = np.clip(np.floor((l - e0) / bw).astype(int), 0, nb - 1)
    b = np.clip(np.floor((h - e0) / bw).astype(int), 0, nb - 1)
    n = b - a + 1
    prof = np.zeros(nb + 1)
    np.add.at(prof, a, w / n)
    np.add.at(prof, b + 1, -w / n)
    prof = np.cumsum(prof)[:nb]
    return e0, bw, prof


def tpo_weights(h, l):
    """30-minute TPO: returns per-minute (h, l, w) arrays where each 30-min period is one bar of weight 1."""
    n = W // 30 + (1 if W % 30 else 0)
    H = np.array([np.nanmax(h[30 * k:30 * k + 30]) if np.isfinite(h[30 * k:30 * k + 30]).any() else np.nan for k in range(n)])
    Lw = np.array([np.nanmin(l[30 * k:30 * k + 30]) if np.isfinite(l[30 * k:30 * k + 30]).any() else np.nan for k in range(n)])
    return H, Lw, np.ones(n)


def value_area(prof, frac=0.70):
    tot = prof.sum()
    p = int(np.argmax(prof))
    lo = hi = p
    acc = prof[p]
    nb = len(prof)
    while acc < frac * tot:
        up = prof[hi + 1] if hi + 1 < nb else -1
        dn = prof[lo - 1] if lo - 1 >= 0 else -1
        if up < 0 and dn < 0:
            break
        if up >= dn:
            hi += 1
            acc += up
        else:
            lo -= 1
            acc += dn
    return p, lo, hi


def nodes(prof):
    k = np.array([1, 2, 3, 2, 1], float)
    s = np.convolve(prof, k / k.sum(), mode="same")
    nz = s[s > 0]
    if len(nz) == 0:
        return np.zeros(0, int), np.zeros(0, int)
    mu = nz.mean()
    nb = len(s)
    hv, lv = [], []
    for i in range(nb):
        a, b = max(0, i - 3), min(nb, i + 4)
        nb_ = np.r_[s[a:i], s[i + 1:b]]
        if len(nb_) == 0:
            continue
        if s[i] > nb_.max() and s[i] >= mu:
            hv.append(i)
        if s[i] < nb_.min() and s[i] <= 0.6 * mu:
            lv.append(i)
    hv = np.array(hv, int)
    lv = np.array([i for i in lv if len(hv) >= 2 and hv.min() < i < hv.max()], int)
    return hv, lv


def levels(P, di, src="optv"):
    """prior-day (di-1) profile levels for session di: dict(POC, VAH, VAL, HVN[], LVN[]) or None."""
    j = di - 1
    if j < 0:
        return None
    h, l = P["hh"][j], P["ll"][j]
    ref = P["c"][j][np.isfinite(P["c"][j])]
    if len(ref) == 0:
        return None
    ref = ref[0]
    if src == "tpo":
        H, Lw, w = tpo_weights(h, l)
        pr = profile(H, Lw, w, ref, min_n=6)
        if pr is None:
            return None
    else:
        w = P[src][j]
        if not np.isfinite(w).any() or np.nansum(w) <= 0:
            return None
        pr = profile(h, l, w, ref)
        if pr is None:
            return None
    e0, bw, prof = pr
    p, lo, hi = value_area(prof)
    hv, lv = nodes(prof)
    ctr = lambda i: e0 + (np.asarray(i) + 0.5) * bw  # noqa: E731
    return dict(POC=float(ctr(p)), VAH=e0 + (hi + 1) * bw, VAL=e0 + lo * bw, HVN=ctr(hv), LVN=ctr(lv), bw=bw)


# ------------------------------------------------------------------ outcomes helpers
def vwap_proxy(P, di, w=None):
    c = P["c"][di]
    w = P["optv"][di] if w is None else w
    w = np.nan_to_num(w)
    tp = (P["hh"][di] + P["ll"][di] + c) / 3
    tp = np.where(np.isfinite(tp), tp, c)
    cw = np.cumsum(w)
    vw = np.cumsum(np.nan_to_num(tp) * w) / np.where(cw > 0, cw, np.nan)
    # before any volume: running mean of price
    tw = np.cumsum(np.nan_to_num(tp)) / np.arange(1, len(tp) + 1)
    return np.where(np.isfinite(vw), vw, tw)


def crosses(c, ref, a, b, eps=0.0002):
    s = 0
    n = 0
    for t in range(a, b + 1):
        if not (np.isfinite(c[t]) and np.isfinite(ref[t])):
            continue
        x = c[t] / ref[t] - 1
        if x > eps:
            if s == -1:
                n += 1
            s = 1
        elif x < -eps:
            if s == 1:
                n += 1
            s = -1
    return n


def zigzag_n(c, a, b, th=0.002):
    x = c[a:b + 1]
    x = x[np.isfinite(x)]
    if len(x) < 2:
        return 0
    n, d, ext = 0, 0, x[0]
    for v in x[1:]:
        if d >= 0:
            if v > ext:
                ext = v
            elif v <= ext * (1 - th):
                if d == 1:
                    n += 1
                d, ext = -1, v
                continue
        if d <= 0:
            if v < ext:
                ext = v
            elif v >= ext * (1 + th):
                if d == -1:
                    n += 1
                d, ext = 1, v
        if d == 0:
            pass
    return n


def rv(c, a, b):
    x = c[a:b + 1]
    x = x[np.isfinite(x)]
    r = np.diff(np.log(x))
    return float(np.sqrt((r ** 2).sum()) * 100)


def clboot_diff(xa, ga, xb, gb, B=1000, seed=1):
    """one-sided p (mean a > mean b) by day-cluster bootstrap. Returns diff, p."""
    rng = np.random.default_rng(seed)
    xa, xb = np.asarray(xa, float), np.asarray(xb, float)
    days = np.unique(np.r_[ga, gb])
    ia = pd.Series(np.arange(len(xa))).groupby(np.asarray(ga)).apply(np.array).to_dict()
    ib = pd.Series(np.arange(len(xb))).groupby(np.asarray(gb)).apply(np.array).to_dict()
    sa = {d: (xa[v].sum(), len(v)) for d, v in ia.items()}
    sb = {d: (xb[v].sum(), len(v)) for d, v in ib.items()}
    A = np.array([sa.get(d, (0, 0)) for d in days])
    Bm = np.array([sb.get(d, (0, 0)) for d in days])
    d0 = xa.mean() - xb.mean()
    k = rng.integers(0, len(days), (B, len(days)))
    na, nb = A[k, 1].sum(1), Bm[k, 1].sum(1)
    db = A[k, 0].sum(1) / np.maximum(na, 1) - Bm[k, 0].sum(1) / np.maximum(nb, 1)
    p = float(((db - d0) >= d0).mean())   # centred bootstrap: P(null >= observed)
    return float(d0), p


bh = L.bh
