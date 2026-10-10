"""h37 stage 2: every signal x params x underlying x timeframe x exit, looked up in the outcome table (h25's design),
with the random baseline MATCHED BY TIME OF DAY (same day, signal minute +-15, both sides averaged, same exit).

    OBUY_CACHE=<scratch>/hunt/h37/cache flock <scratch>/obuy.lock python3 -I research/hunt/h37/evaluate.py

Writes <scratch>/hunt/h37/: pre.parquet (pre-holdout stats per variant), spa.json, hold_sealed.parquet (holdout sums;
read ONLY by final.py).
"""
from __future__ import annotations

import json
import os
import sys
import time
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.insert(0, HERE)
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.overfit import _stationary_counts  # noqa: E402
import sigs  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h37")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
TEST = os.environ.get("H37_TEST")
if TEST:
    UNDS = [TEST]
SFX = "_test" if TEST else ""
PTS = [(t, s) for t in (15, 20, 25, 30) for s in (10, 15, 20)]
EXITS = [f"P{t}_{s}" for t, s in PTS] + ["ARM", "LAD", "T15", "T30", "T60"]
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0020}
STRESS = 1.5
HOLD = date(2025, 10, 1).toordinal()
YEARS = [2020, 2021, 2022, 2023, 2024, 2025, 2026]
SM0 = 9 * 60 + 15
MW = 346
MAXDAY = 5
NWIN = 15
B_SPA = 500


def load_table(u):
    z = np.load(os.path.join(OUT, f"out_{u}.npz"))
    T = dict(days=z["days"], year=z["year"], qty=z["qty"], e=z["e"])
    hs = HS[u]
    for k in EXITS:
        n, s = z[f"n_{k}"], z[f"s_{k}"]
        r = (n - hs * s).astype(np.float32)
        T[f"r_{k}"] = r
        T[f"t_{k}"] = (n - STRESS * hs * s).astype(np.float32)
        T[f"g_{k}"] = z[f"g_{k}"]
        T[f"x_{k}"] = z[f"x_{k}"]
        T[f"tie_{k}"] = z[f"t_{k}"]
        T[f"m0_{k}"] = matched_null(r)
    return T


def matched_null(r):
    """(D, M): mean of r over minutes m-15..m+15 (clipped) and both sides, same day."""
    v = np.nan_to_num(r).sum(axis=2).astype(np.float64)
    c = (~np.isnan(r)).sum(axis=2).astype(np.float64)
    cv = np.concatenate([np.zeros((v.shape[0], 1)), np.cumsum(v, axis=1)], axis=1)
    cc = np.concatenate([np.zeros((v.shape[0], 1)), np.cumsum(c, axis=1)], axis=1)
    m = np.arange(v.shape[1])
    hi = np.minimum(m + NWIN, v.shape[1] - 1) + 1
    lo = np.maximum(m - NWIN, 0)
    sv = cv[:, hi] - cv[:, lo]
    sc = cc[:, hi] - cc[:, lo]
    with np.errstate(all="ignore"):
        return np.where(sc > 0, sv / sc, np.nan).astype(np.float32)


def greedy(di, mi, X):
    """One position at a time, <= MAXDAY a day. di, mi sorted by time; X = exit minute. Returns selected idx."""
    n = len(di)
    if n == 0:
        return np.zeros(0, np.int64)
    key = di.astype(np.int64) * 1000 + mi
    nxt = np.searchsorted(key, di.astype(np.int64) * 1000 + (X.astype(np.int64) - SM0), side="left")
    _, first = np.unique(di, return_index=True)
    cur = first.copy()
    day = di[first]
    alive = np.ones(len(cur), bool)
    take = []
    for _ in range(MAXDAY):
        take.append(cur[alive])
        cur = nxt[cur]
        ok = cur < n
        cur2 = np.where(ok, cur, 0)
        alive &= ok & (di[cur2] == day)
        cur = cur2
        if not alive.any():
            break
    return np.sort(np.concatenate(take))


def vp_weights(u, ix):
    p = os.path.join(OUT, f"vp_{u}.npy")
    if u in ("NIFTY", "BANKNIFTY") and os.path.exists(p):
        W = np.load(p)
        assert W.shape[0] == len(ix.days)
        return W
    return None


def events(cfg, dmap):
    di = np.array([dmap.get(o, -1) for o in cfg["ord"]], np.int64)
    mi = cfg["mi"].astype(np.int64)
    ok = (di >= 0) & (mi <= MW - 1) & (mi >= 0)
    di, mi, sd = di[ok], mi[ok], cfg["side"][ok]
    o = np.lexsort((mi, di))
    extra = {k: cfg[k][ok][o] for k in ("H", "L") if k in cfg}
    return di[o], mi[o], sd[o], extra


def trades(tb, di0, mi0, sd0, k):
    sx0 = (sd0 > 0).astype(np.int64)
    r = tb[f"r_{k}"][di0, mi0, sx0]
    v = ~np.isnan(r)
    di, mi, sx = di0[v], mi0[v], sx0[v]
    sel = greedy(di, mi, tb[f"x_{k}"][di, mi, sx])
    return di[sel], mi[sel], sx[sel], np.nonzero(v)[0][sel]


def key_of(c):
    return (c["sig"], str(c["tf"]), c["par"])


def main():
    t0 = time.time()
    mk = market()
    tabs = {}
    cal = None
    rows, hrows = [], []
    rng = np.random.default_rng(37)
    spa_acc = None
    for u in UNDS:
        t1 = time.time()
        tb = load_table(u)
        ix = mk.index(u)
        cfgs = sigs.all_events(ix, vp_weights(u, ix))
        if cal is None:
            # pre-holdout calendar: union of every index's non-expiry days (read from the tables' day lists)
            ds = [np.load(os.path.join(OUT, f"out_{x}.npz"))["days"] for x in UNDS]
            cal = np.unique(np.concatenate([d[d < HOLD] for d in ds]))
            T = len(cal)
            counts = _stationary_counts(T, B_SPA, 5.0, rng).astype(np.float32)
            spa_acc = dict(rc_obs=-np.inf, rc_b=np.full(B_SPA, -np.inf), spa_obs=0.0, spa_b=np.zeros(B_SPA), n=0)
            thr_c = np.sqrt(2 * np.log(np.log(T)))
        dmap = {o: i for i, o in enumerate(tb["days"])}
        pre_day = tb["days"] < HOLD
        calpos = np.searchsorted(cal, tb["days"])
        yidx = np.searchsorted(YEARS, tb["year"])
        Xcols = []
        for cfg in cfgs:
            di0, mi0, sd0, _ = events(cfg, dmap)
            for k in EXITS:
                di, mi, sx, _ = trades(tb, di0, mi0, sd0, k)
                net = tb[f"r_{k}"][di, mi, sx].astype(np.float64)
                st = tb[f"t_{k}"][di, mi, sx].astype(np.float64)
                gr = tb[f"g_{k}"][di, mi, sx].astype(np.float64)
                tie = tb[f"tie_{k}"][di, mi, sx].astype(np.float64)
                m0 = tb[f"m0_{k}"][di, mi].astype(np.float64)
                m0 = np.where(np.isnan(m0), net, m0)
                dd = net - m0
                pre = pre_day[di]
                row = dict(und=u, sig=cfg["sig"], fam=cfg["fam"], tf=str(cfg["tf"]), par=cfg["par"], exit=k,
                           n=int(pre.sum()), net=net[pre].sum(), stress=st[pre].sum(), gross=gr[pre].sum(),
                           ss=(net[pre] ** 2).sum(), m0=m0[pre].sum(), ties=tie[pre].sum())
                # day-clustered paired differences (real - matched random), overall and per year
                dpre = di[pre]
                ud, inv = np.unique(dpre, return_inverse=True)
                dsum = np.bincount(inv, weights=dd[pre], minlength=len(ud))
                row["nd"], row["dsum"], row["dss"] = len(ud), dsum.sum(), (dsum ** 2).sum()
                yd = yidx[ud]
                yi = yidx[dpre]
                for nm, val in (("n", np.ones(pre.sum())), ("net", net[pre]), ("stress", st[pre]), ("m0", m0[pre])):
                    bc = np.bincount(yi, weights=val, minlength=len(YEARS))
                    for y, x in zip(YEARS, bc):
                        row[f"{nm}_{y}"] = x
                for nm, val in (("nd", np.ones(len(ud))), ("dsum", dsum), ("dss", dsum ** 2)):
                    bc = np.bincount(yd, weights=val, minlength=len(YEARS))
                    for y, x in zip(YEARS, bc):
                        row[f"{nm}_{y}"] = x
                rows.append(row)
                h = ~pre
                hrows.append(dict(und=u, sig=cfg["sig"], tf=str(cfg["tf"]), par=cfg["par"], exit=k, n=int(h.sum()),
                                  net=net[h].sum(), stress=st[h].sum(), gross=gr[h].sum(), m0=m0[h].sum(),
                                  ties=tie[h].sum()))
                Xcols.append(np.bincount(calpos[di[pre]], weights=net[pre], minlength=T))
        Xm = np.stack(Xcols, axis=1).astype(np.float32)
        if u == UNDS[-1] and os.path.exists(os.path.join(OUT, "fx_X.npy")):
            fx = np.load(os.path.join(OUT, "fx_X.npy"))       # Fibonacci extension-exit variants (fibx.py)
            assert fx.shape[0] == T
            Xm = np.concatenate([Xm, fx], axis=1)
            print("SPA includes", fx.shape[1], "FX variants", flush=True)
        for a in range(0, Xm.shape[1], 2000):
            X = Xm[:, a:a + 2000]
            mu = X.mean(axis=0)
            mus = (counts @ X) / T
            omega = np.sqrt(T) * mus.std(axis=0, ddof=1)
            omega = np.where(omega > 0, omega, np.inf)
            spa_acc["rc_obs"] = max(spa_acc["rc_obs"], float(np.sqrt(T) * mu.max()))
            spa_acc["rc_b"] = np.maximum(spa_acc["rc_b"], np.sqrt(T) * (mus - mu).max(axis=1))
            tt = np.sqrt(T) * mu / omega
            spa_acc["spa_obs"] = max(spa_acc["spa_obs"], float(tt.max()))
            g = np.where(mu >= -thr_c * omega / np.sqrt(T), mu, 0.0)
            spa_acc["spa_b"] = np.maximum(spa_acc["spa_b"], (np.sqrt(T) * (mus - g) / omega).max(axis=1))
        spa_acc["n"] += Xm.shape[1]
        print(u, len(cfgs), "configs", Xm.shape[1], "variants", f"{time.time() - t1:.0f}s", flush=True)
        del tb, Xm
        mk.release(u)
    pd.DataFrame(rows).to_parquet(os.path.join(OUT, "pre%s.parquet" % SFX))
    pd.DataFrame(hrows).to_parquet(os.path.join(OUT, "hold_sealed%s.parquet" % SFX))
    res = dict(n_variants=spa_acc["n"], T=int(T), B=B_SPA,
               rc_p=float((spa_acc["rc_b"] >= spa_acc["rc_obs"]).mean()),
               spa_p=float((np.maximum(spa_acc["spa_b"], 0) >= max(spa_acc["spa_obs"], 0)).mean()),
               rc_obs=spa_acc["rc_obs"], spa_obs=spa_acc["spa_obs"])
    json.dump(res, open(os.path.join(OUT, "spa%s.json" % SFX), "w"), indent=1)
    print(res, f"done {time.time() - t0:.0f}s", flush=True)


# ------------------------------------------------------------------------------------------------ one variant's trades
_TAB, _CFG = {}, {}


def trades_for(u, sig, tf, par, k, mk=None):
    """All-period trades of one variant: DataFrame ord, mi, side, net, stress, gross, m0, tie, prem, exit_min, pre."""
    mk = mk or market()
    if u not in _TAB:
        _TAB[u] = load_table(u)
    tb = _TAB[u]
    if u not in _CFG:
        ix = mk.index(u)
        _CFG[u] = {key_of(c): c for c in sigs.all_events(ix, vp_weights(u, ix))}
    cfg = _CFG[u][(sig, str(tf), par)]
    dmap = {o: i for i, o in enumerate(tb["days"])}
    di0, mi0, sd0, _ = events(cfg, dmap)
    di, mi, sx, _ = trades(tb, di0, mi0, sd0, k)
    m0 = tb[f"m0_{k}"][di, mi]
    net = tb[f"r_{k}"][di, mi, sx]
    return pd.DataFrame(dict(
        und=u, ord=tb["days"][di], di=di, mi=mi, side=np.where(sx > 0, 1, -1), net=net,
        stress=tb[f"t_{k}"][di, mi, sx], gross=tb[f"g_{k}"][di, mi, sx], m0=np.where(np.isnan(m0), net, m0),
        tie=tb[f"tie_{k}"][di, mi, sx], prem=tb["e"][di, mi, sx] * tb["qty"][di, mi, sx],
        exit_min=tb[f"x_{k}"][di, mi, sx], pre=tb["days"][di] < HOLD))


if __name__ == "__main__":
    main()
