"""h25 stage 2: every signal x orientation x underlying x timeframe x exit, looked up in the outcome table.

    OBUY_CACHE=<scratch>/hunt/h25/cache flock <scratch>/obuy.lock python3 -I research/hunt/h25/evaluate.py

Writes <scratch>/hunt/h25/: pre.parquet (pre-holdout stats per variant, incl. per-year and random-null moments),
spa.json (White RC / Hansen SPA_c over ALL variants, chunked), hold_sealed.parquet (holdout sums; read ONLY by
final.py, never by selection code).
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

OUT = os.path.join(C.SCRATCH, "hunt/h25")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
TEST = os.environ.get("H25_TEST")
if TEST:
    UNDS = [TEST]
TFS = [1, 3, 5, 15, 30, 60]
EXITS7 = ["ARM", "P20", "PCT", "LAD", "T15", "T30", "T60"]
# AMENDMENT 1: premium-point exits (outcomes.py H25_SET=points -> out2_<U>.npz)
EXITS2 = [f"P{t}_{s}" for t in (15, 20, 25, 30) for s in (10, 15, 20) if (t, s) != (20, 20)]
EXITS = EXITS7 + EXITS2
HS = {"BANKNIFTY": 0.0016, "NIFTY": 0.0016, "MIDCPNIFTY": 0.0021, "FINNIFTY": 0.0042, "SENSEX": 0.0016}
STRESS = 1.5
HOLD = date(2025, 10, 1).toordinal()
YEARS = [2020, 2021, 2022, 2023, 2024, 2025]
SM0 = 9 * 60 + 15
MW = 346
MAXDAY = 5
B_SPA = 500


def load_table(u):
    z0 = np.load(os.path.join(OUT, f"out_{u}{'_test' if TEST else ''}.npz"))
    z2 = np.load(os.path.join(OUT, f"out2_{u}{'_test' if TEST else ''}.npz"))
    T = dict(days=z0["days"], year=z0["year"], qty=z0["qty"])
    hs = HS[u]
    for k in EXITS:
        z = z0 if k in EXITS7 else z2
        n, s = z[f"n_{k}"], z[f"s_{k}"]
        T[f"r_{k}"] = (n - hs * s).astype(np.float32)               # net at the real spread
        T[f"t_{k}"] = (n - STRESS * hs * s).astype(np.float32)      # stress
        T[f"g_{k}"] = z[f"g_{k}"]
        T[f"x_{k}"] = z[f"x_{k}"]
        r = T[f"r_{k}"]
        with np.errstate(all="ignore"):
            T[f"m0_{k}"] = np.nanmean(r, axis=1)                    # (D, 2): random-minute null mean per day, side
            T[f"v0_{k}"] = np.nanvar(r, axis=1)
    return T


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


def main():
    t0 = time.time()
    mk = market()
    tabs = {u: load_table(u) for u in UNDS}
    # pre-holdout calendar (union of all indices' non-expiry trading days)
    cal = np.unique(np.concatenate([t["days"][t["days"] < HOLD] for t in tabs.values()]))
    T = len(cal)
    rng = np.random.default_rng(4)
    counts = _stationary_counts(T, B_SPA, 5.0, rng).astype(np.float32)
    spa_acc = dict(rc_obs=-np.inf, rc_b=np.full(B_SPA, -np.inf), spa_obs=0.0, spa_b=np.zeros(B_SPA), n=0)
    thr_c = np.sqrt(2 * np.log(np.log(T)))
    rows, hrows = [], []
    for u in UNDS:
        tb = tabs[u]
        ix = mk.index(u)
        Mx = ix.mat()
        dmap = {o: i for i, o in enumerate(tb["days"])}
        allord = np.array([d.toordinal() for d in ix.days])
        pre_day = tb["days"] < HOLD
        calpos = np.searchsorted(cal, tb["days"])
        yidx = np.searchsorted(YEARS, tb["year"])
        for tf in TFS:
            t1 = time.time()
            Bb = sigs.bars(Mx, tf)
            S = sigs.compute(Bb)
            bord = allord[Bb["dpos"]]
            bdi = np.array([dmap.get(o, -1) for o in bord])
            bmi = Bb["col_end"]
            okb = (bdi >= 0) & (bmi <= MW - 1)
            Xcols = []
            for name in sigs.names():
                s = S[name]
                ev = np.nonzero(okb & (s != 0))[0]
                di0, mi0, sd0 = bdi[ev], bmi[ev], s[ev].astype(np.int64)
                for orient in ("follow", "fade"):
                    sd = sd0 if orient == "follow" else -sd0
                    sx = (sd > 0).astype(np.int64)
                    for k in EXITS:
                        r = tb[f"r_{k}"][di0, mi0, sx]
                        v = ~np.isnan(r)
                        di, mi, sxx = di0[v], mi0[v], sx[v]
                        X = tb[f"x_{k}"][di, mi, sxx]
                        sel = greedy(di, mi, X)
                        di, mi, sxx = di[sel], mi[sel], sxx[sel]
                        net = tb[f"r_{k}"][di, mi, sxx].astype(np.float64)
                        st = tb[f"t_{k}"][di, mi, sxx].astype(np.float64)
                        gr = tb[f"g_{k}"][di, mi, sxx].astype(np.float64)
                        m0 = tb[f"m0_{k}"][di, sxx].astype(np.float64)
                        v0 = tb[f"v0_{k}"][di, sxx].astype(np.float64)
                        pre = pre_day[di]
                        row = dict(und=u, tf=tf, sig=name, fam=sigs.family_of(name), orient=orient, exit=k,
                                   n=int(pre.sum()), net=net[pre].sum(), stress=st[pre].sum(), gross=gr[pre].sum(),
                                   ss=(net[pre] ** 2).sum(), m0=m0[pre].sum(), v0=v0[pre].sum(),
                                   ndays=len(np.unique(di[pre])))
                        yi = yidx[di[pre]]
                        for nm, val in (("n", np.ones(pre.sum())), ("net", net[pre]), ("stress", st[pre]),
                                        ("m0", m0[pre]), ("v0", v0[pre])):
                            bc = np.bincount(yi, weights=val, minlength=len(YEARS))
                            for y, x in zip(YEARS, bc):
                                row[f"{nm}_{y}"] = x
                        rows.append(row)
                        h = ~pre
                        hrows.append(dict(und=u, tf=tf, sig=name, orient=orient, exit=k, n=int(h.sum()),
                                          net=net[h].sum(), stress=st[h].sum(), gross=gr[h].sum(),
                                          m0=m0[h].sum(), v0=v0[h].sum()))
                        Xcols.append(np.bincount(calpos[di[pre]], weights=net[pre], minlength=T))
            # SPA / RC chunk update (daily net vs not trading)
            Xm = np.stack(Xcols, axis=1).astype(np.float32)          # (T, V)
            mu = Xm.mean(axis=0)
            mus = (counts @ Xm) / T                                  # (B, V)
            omega = np.sqrt(T) * mus.std(axis=0, ddof=1)
            omega = np.where(omega > 0, omega, np.inf)
            spa_acc["rc_obs"] = max(spa_acc["rc_obs"], float(np.sqrt(T) * mu.max()))
            spa_acc["rc_b"] = np.maximum(spa_acc["rc_b"], np.sqrt(T) * (mus - mu).max(axis=1))
            tt = np.sqrt(T) * mu / omega
            spa_acc["spa_obs"] = max(spa_acc["spa_obs"], float(tt.max()))
            g = np.where(mu >= -thr_c * omega / np.sqrt(T), mu, 0.0)
            spa_acc["spa_b"] = np.maximum(spa_acc["spa_b"], (np.sqrt(T) * (mus - g) / omega).max(axis=1))
            spa_acc["n"] += Xm.shape[1]
            print(u, tf, len(Bb["c"]), "bars", Xm.shape[1], "variants", f"{time.time() - t1:.0f}s",
                  f"total {time.time() - t0:.0f}s", flush=True)
        mk.release(u)
    pre = pd.DataFrame(rows)
    pre.to_parquet(os.path.join(OUT, "pre%s.parquet" % ("_test" if TEST else "")))
    pd.DataFrame(hrows).to_parquet(os.path.join(OUT, "hold_sealed%s.parquet" % ("_test" if TEST else "")))
    res = dict(n_variants=spa_acc["n"], T=T, B=B_SPA,
               rc_p=float((spa_acc["rc_b"] >= spa_acc["rc_obs"]).mean()),
               spa_p=float((np.maximum(spa_acc["spa_b"], 0) >= max(spa_acc["spa_obs"], 0)).mean()),
               rc_obs=spa_acc["rc_obs"], spa_obs=spa_acc["spa_obs"])
    json.dump(res, open(os.path.join(OUT, "spa%s.json" % ("_test" if TEST else "")), "w"), indent=1)
    print(res, f"done {time.time() - t0:.0f}s", flush=True)


if __name__ == "__main__":
    main()


# ------------------------------------------------------------------------------------------------ one variant's trades
_TAB, _SIG = {}, {}


def trades_for(u, tf, name, orient, k, mk=None):
    """The trades of one variant (all periods): DataFrame day ord, mi, side, net (real spread), stress, gross, m0, v0,
    entry premium x qty, pre flag. Same lookup + greedy as main()."""
    mk = mk or market()
    if u not in _TAB:
        _TAB[u] = load_table(u)
    tb = _TAB[u]
    if (u, tf) not in _SIG:
        ix = mk.index(u)
        Bb = sigs.bars(ix.mat(), tf)
        allord = np.array([d.toordinal() for d in ix.days])
        dmap = {o: i for i, o in enumerate(tb["days"])}
        bdi = np.array([dmap.get(o, -1) for o in allord[Bb["dpos"]]])
        _SIG[(u, tf)] = (Bb, sigs.compute(Bb), bdi)
    Bb, S, bdi = _SIG[(u, tf)]
    s = S[name]
    bmi = Bb["col_end"]
    ev = np.nonzero((bdi >= 0) & (bmi <= MW - 1) & (s != 0))[0]
    di0, mi0, sd = bdi[ev], bmi[ev], s[ev].astype(np.int64)
    if orient == "fade":
        sd = -sd
    sx = (sd > 0).astype(np.int64)
    r = tb[f"r_{k}"][di0, mi0, sx]
    v = ~np.isnan(r)
    di, mi, sxx = di0[v], mi0[v], sx[v]
    sel = greedy(di, mi, tb[f"x_{k}"][di, mi, sxx])
    di, mi, sxx = di[sel], mi[sel], sxx[sel]
    z = np.load(os.path.join(OUT, f"out_{u}{'_test' if TEST else ''}.npz"))
    e = z["e"][di, mi, sxx]
    return pd.DataFrame(dict(
        und=u, ord=tb["days"][di], di=di, mi=mi, side=np.where(sxx > 0, 1, -1), net=tb[f"r_{k}"][di, mi, sxx],
        stress=tb[f"t_{k}"][di, mi, sxx], gross=tb[f"g_{k}"][di, mi, sxx], m0=tb[f"m0_{k}"][di, sxx],
        v0=tb[f"v0_{k}"][di, sxx], prem=e * tb["qty"][di, mi, sxx], exit_min=tb[f"x_{k}"][di, mi, sxx],
        pre=tb["days"][di] < HOLD))
