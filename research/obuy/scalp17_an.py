"""SCALP17 analysis: conditions, screening, selection, holdout, walk-forward, report. See scalp17.py for the model."""
from __future__ import annotations

import itertools
import json
import os
import pickle
import time
from datetime import date

from obuy import config as C  # noqa: F401  (pandas deps path)
import numpy as np
import pandas as pd
from scipy import stats as sst

from obuy import costs as K
from obuy import data as D
from obuy import overfit as OV
from obuy.scalp17 import DN, HS, LS, NEVER, OUT, QTY, SS, TS, TYPES, UNDS3, UP  # noqa: F401

PARAMS = [(T, S, L) for T in TS for S in SS for L in LS]
TRAIN_END = 2024
YEARS = list(range(2020, 2027))
DIRF = ["r5", "r15", "r30", "gap", "dvwap", "dayret", "pcrch", "b5ret", "b5ema", "b5eng", "b5brk", "b5stk"]
NST = 11   # N, N2, Sg, Sg2, SgN, Sn, Sn2, SnN, W, Lo, ndays
FILL = K.Fills("app")
COST = K.Costs("app")
TOD_EDGES = [1, 30, 75, 165, 255, 315, 346]
TOD_LAB = ["09:16-09:44", "09:45-10:29", "10:30-11:59", "12:00-13:29", "13:30-14:29", "14:30-15:00"]


# ------------------------------------------------------------------------------------------------ data
def load(und):
    F = pd.read_parquet(os.path.join(OUT, f"feat_{und}.parquet"))
    O = pd.read_parquet(os.path.join(OUT, f"out_{und}.parquet"))
    # liquid, sane entries only: the fill bar and the 5 minutes before it traded, the fill is within 15% (or 3 pts) of
    # the contract's last close, premium >= 5 (removes stale prints of thin contracts, mostly FINNIFTY 2021-22)
    ok = (O.v_e > 0) & (O.vol5 > 0) & (O.E >= 5) & ((O.E - O.cprev).abs() <= np.maximum(3, 0.15 * O.cprev))
    O = O[ok]
    X = O.merge(F, on=["day", "s"], how="inner")
    X = X.sort_values(["typ", "day", "s"]).reset_index(drop=True)
    side = np.where(X.typ.values < 2, 1.0, -1.0)
    for f in DIRF:
        X[f] = X[f].values * side
    ce = X.typ.values < 2
    X["dfav"] = np.where(ce, X.dhi, X.dlo)
    X["dadv"] = np.where(ce, X.dlo, X.dhi)
    X["prem"] = X.E
    X["year"] = np.array([D.ddate(d).year for d in range(X.day.min(), X.day.max() + 1)])[X.day.values - X.day.min()]
    X["is5"] = (X.s.values % 5) == 4
    return X


# ------------------------------------------------------------------------------------------------ outcomes
def outcome(X, T, S, L, h):
    """Per row: points vs the ask fill (before bps/charges), exit-bar offset, exit price before bps, is_stop, win, loss."""
    tu = X[f"u{T + 2 * h:g}"].values.astype(np.int16)
    td = X[f"d{S - h:g}"].values.astype(np.int16)
    g = X[f"g{S - h:g}"].values
    win = (tu < td) & (tu <= L)
    loss = (td <= tu) & (td <= L)
    cl = X[f"c{L}"].values
    pts = np.where(win, T, np.where(loss, -S - h - g, cl - 2 * h))
    off = np.where(win, tu, np.where(loss, td, X[f"n{L}"].values))
    F = X.E.values + h
    return pts, off, F, F + pts, loss, win


def net_rs(F, Xp, is_stop, dn=None):
    """Rs per trade for QTY: app fills (+5 bps buy, -5 bps sell, -10 bps stop) + app charges."""
    Xp = np.maximum(Xp, 0.05)
    fb = FILL.buy(F)
    xs = np.where(is_stop, FILL.sell(Xp, stop=True), FILL.sell(Xp))
    ch = COST.charge(True, fb, np.full(len(fb), QTY)) + COST.charge(False, xs, np.full(len(xs), QTY))
    return (xs - fb) * QTY - ch


# ------------------------------------------------------------------------------------------------ conditions
def cuts(X, f):
    tr = X[(X.year <= TRAIN_END)][f].values
    tr = tr[np.isfinite(tr)]
    return np.quantile(tr, [0.2, 0.4, 0.6, 0.8]) if len(tr) else None


def fcode(X, f, cut):
    """(code int16, ncell, labels) for one feature; -1 = not applicable."""
    v = X[f].values if f != "tod" else X.s.values
    if f == "tod":
        c = np.searchsorted(TOD_EDGES, v, side="right") - 1
        return c.astype(np.int16), 6, TOD_LAB
    if f == "wd":
        return v.astype(np.int16), 5, ["Mon", "Tue", "Wed", "Thu", "Fri"]
    if f == "dte":
        c = np.select([v == 0, v == 1, v == 2, (v >= 3) & (v <= 4), v >= 5], [0, 1, 2, 3, 4], -1)
        return c.astype(np.int16), 5, ["DTE0", "DTE1", "DTE2", "DTE3-4", "DTE5+"]
    if f in ("b5eng", "b5brk"):
        c = np.where(np.isfinite(v), v + 1, -1)
        nm = {"b5eng": ["opposite engulfing", "no engulfing", "engulfing our way"],
              "b5brk": ["5m close beyond prior bar's adverse extreme", "5m close inside prior bar", "5m close beyond prior bar's favourable extreme"]}[f]
        return c.astype(np.int16), 3, nm
    if f == "b5ins":
        return np.where(np.isfinite(v), v, -1).astype(np.int16), 2, ["not inside bar", "inside bar"]
    if f == "b5stk":
        c = np.select([v <= -3, v == -2, v == -1, v == 1, v == 2, v >= 3], [0, 1, 2, 3, 4, 5], -1)
        return c.astype(np.int16), 6, ["streak<=-3", "streak-2", "streak-1", "streak+1", "streak+2", "streak>=3"]
    c = np.where(np.isfinite(v), np.searchsorted(cut, v, side="right"), -1)
    lab = [f"{f} Q{i + 1} " + (f"(<{cut[0]:.3g})" if i == 0 else f"(>={cut[3]:.3g})" if i == 4 else
                               f"[{cut[i - 1]:.3g},{cut[i]:.3g})") for i in range(5)]
    return c.astype(np.int16), 5, lab


Q1 = ["vix", "prem", "r5", "r15", "r30", "rexp", "gap", "dvwap", "dfav", "dadv", "dayret", "pcrch", "oich", "opm5"]
G1 = [("all",), ("tod",), ("wd",), ("dte",)] + [(f,) for f in Q1] + \
     [("tod", f) for f in ("r5", "r15", "rexp", "dvwap", "dfav", "dadv", "oich", "pcrch", "gap", "vix", "dte", "prem", "opm5")] + \
     [("r15", "rexp"), ("r5", "rexp"), ("r15", "dvwap"), ("dte", "r15"), ("vix", "r15"), ("gap", "dayret"),
      ("r15", "dfav"), ("prem", "r15"), ("r5", "r15"), ("opm5", "rexp"), ("oich", "r15"), ("dvwap", "rexp")]
Q5 = ["vix", "prem", "b5ret", "b5rng", "b5ema", "dvwap", "r15", "dfav", "rexp", "opm5"]
G5 = [("all",), ("tod",), ("wd",), ("dte",)] + [(f,) for f in Q5] + [("b5eng",), ("b5ins",), ("b5brk",), ("b5stk",)] + \
     [("tod", f) for f in ("b5ret", "b5rng", "b5brk", "b5eng", "b5stk", "b5ema")] + \
     [("b5ret", "b5rng"), ("b5brk", "b5rng"), ("b5stk", "b5rng"), ("b5eng", "b5rng"), ("b5ema", "b5ret"),
      ("b5brk", "b5ema"), ("b5brk", "b5stk"), ("dte", "b5ret"), ("vix", "b5ret"), ("b5ins", "b5rng")]
GROUPS = [("1m", g) for g in G1] + [("5m", g) for g in G5]


def group_codes(X, cutd):
    """[(tf, group, code, ncell, labels)] over the rows of X."""
    cache = {}

    def one(f):
        if f not in cache:
            cache[f] = fcode(X, f, cutd.get(f))
        return cache[f]

    out = []
    is5 = X.is5.values
    for tf, g in GROUPS:
        if g == ("all",):
            code, n, lab = np.zeros(len(X), np.int16), 1, ["all"]
        elif len(g) == 1:
            code, n, lab = one(g[0])
        else:
            a, na, la = one(g[0])
            b, nb, lb = one(g[1])
            code = np.where((a >= 0) & (b >= 0), a * nb + b, -1).astype(np.int16)
            n, lab = na * nb, [f"{x} & {y}" for x in la for y in lb]
        if tf == "5m":
            code = np.where(is5, code, -1).astype(np.int16)
        out.append((tf, g, code, n, lab))
    return out


def cutpoints(X):
    d = {}
    for f in set(Q1) | set(Q5):
        sub = X[X.is5] if f.startswith("b5") else X
        d[f] = cuts(sub, f)
    return d


# ------------------------------------------------------------------------------------------------ screen
def screen(und):
    t0 = time.time()
    X = load(und)
    cutd = cutpoints(X)
    meta = None
    res = None
    for ti in range(4):
        Xt = X[X.typ == ti].reset_index(drop=True)
        days = np.unique(Xt.day.values)
        di = np.searchsorted(days, Xt.day.values)
        yd = np.array([D.ddate(d).year for d in days]) - YEARS[0]
        gc = group_codes(Xt, cutd)
        if meta is None:
            meta = [(tf, g, n, lab) for tf, g, _, n, lab in gc]
            ncell = sum(n for *_, n, _ in [(a, b, n, lab) for a, b, n, lab in meta])
            res = np.zeros((4, len(PARAMS), ncell, len(YEARS), NST), np.float32)
        keys = []
        for tf, g, code, n, lab in gc:
            m = code >= 0
            keys.append((m, di[m].astype(np.int64) * n + code[m], n))
        for pi, (T, S, L) in enumerate(PARAMS):
            pg, _, F0, X0, st0, win = outcome(Xt, T, S, L, 0.0)
            ph, _, F1, X1, st1, _ = outcome(Xt, T, S, L, 0.5)
            loss = (pg < 0) & st0
            npts = net_rs(F1, X1, st1) / QTY
            base = 0
            for (m, k, n) in keys:
                size = len(days) * n
                N = np.bincount(k, minlength=size).astype(np.float64)
                Sg = np.bincount(k, weights=pg[m], minlength=size)
                Sn = np.bincount(k, weights=npts[m], minlength=size)
                W = np.bincount(k, weights=win[m], minlength=size)
                Lo = np.bincount(k, weights=loss[m], minlength=size)
                cell = np.tile(np.arange(n), len(days))
                yk = np.repeat(yd, n) * n + cell
                nz = N > 0
                yk, N, Sg, Sn, W, Lo = yk[nz], N[nz], Sg[nz], Sn[nz], W[nz], Lo[nz]
                cols = [N, N * N, Sg, Sg * Sg, Sg * N, Sn, Sn * Sn, Sn * N, W, Lo, np.ones_like(N)]
                ny = len(YEARS)
                for si, w in enumerate(cols):
                    v = np.bincount(yk, weights=w, minlength=ny * n).reshape(ny, n).T
                    res[ti, pi, base:base + n, :, si] = v
                base += n
        print(und, "typ", ti, f"{time.time() - t0:.0f}s", flush=True)
    np.save(os.path.join(OUT, f"screen_{und}.npy"), res)
    with open(os.path.join(OUT, f"screen_{und}_meta.pkl"), "wb") as f:
        pickle.dump(dict(meta=meta, cutd=cutd), f)


# ------------------------------------------------------------------------------------------------ statistics
def stat(R, years, metric="g"):
    """R (..., years, NST) -> mean pts per entry, day-clustered t, ndays over the chosen years."""
    yi = [y - YEARS[0] for y in years]
    A = R[..., yi, :].astype(np.float64).sum(axis=-2)
    N, N2 = A[..., 0], A[..., 1]
    S, S2, SN = (A[..., 2], A[..., 3], A[..., 4]) if metric == "g" else (A[..., 5], A[..., 6], A[..., 7])
    nd = A[..., 10]
    with np.errstate(invalid="ignore", divide="ignore"):
        m = S / N
        var = (S2 - 2 * m * SN + m * m * N2) / (N * N) * nd / np.maximum(nd - 1, 1)
        t = m / np.sqrt(np.maximum(var, 1e-12))
    return m, t, nd, A


# ------------------------------------------------------------------------------------------------ sequential sim
def sim(Xt, mask, T, S, L, max_per_day=5):
    """One position at a time, at most max_per_day trades a day, on the rows of Xt (one und, one typ, sorted by day, s)
    where mask holds. Returns the chosen row indices. The trade sequence uses the gross (h 0) exit times."""
    pts, off, *_ = outcome(Xt, T, S, L, 0.0)
    idx = np.nonzero(mask)[0]
    if not len(idx):
        return idx
    day = Xt.day.values[idx]
    s = Xt.s.values[idx].astype(np.int64)
    o = off[idx].astype(np.int64)
    bnd = np.nonzero(np.diff(day))[0] + 1
    starts = np.concatenate([[0], bnd])
    ends = np.concatenate([bnd, [len(idx)]])
    chosen = []
    for a, b in zip(starts, ends):
        free, n, j = -1, 0, a
        while j < b and n < max_per_day:
            if s[j] >= free:
                chosen.append(idx[j])
                free = s[j] + o[j]
                n += 1
            j += 1
    return np.array(chosen, dtype=np.int64)


def trades(Xt, rows, T, S, L, und, typ):
    sub = Xt.iloc[rows]
    out = dict(day=sub.day.values, s=sub.s.values, year=sub.year.values)
    pg, off, F0, X0, st0, win = outcome(sub, T, S, L, 0.0)
    out["pts"] = pg
    out["gross"] = pg * QTY
    out["win"] = win
    out["net_app"] = net_rs(F0, X0, st0)
    for h, nm in ((0.5, "net_sp"), (1.0, "net_st")):
        p, _, F, Xp, st, _ = outcome(sub, T, S, L, h)
        out[nm] = net_rs(F, Xp, st)
    out["E"] = sub.E.values
    t = pd.DataFrame(out)
    t["und"], t["typ"] = und, typ
    return t


def summarize(t, all_days):
    """Stats of a trade list over the trading days all_days (array of day numbers)."""
    r = {}
    r["trades"] = len(t)
    nd = len(all_days)
    r["days"] = nd
    r["tpd"] = len(t) / nd if nd else np.nan
    r["win"] = float(t.win.mean()) if len(t) else np.nan
    for c in ("gross", "net_app", "net_sp", "net_st"):
        daily = t.groupby("day")[c].sum().reindex(all_days, fill_value=0.0)
        dts = pd.to_datetime([D.ddate(d) for d in daily.index])
        mon = daily.groupby(dts.to_period("M")).sum()
        yr = daily.groupby(dts.year).sum()
        eq = daily.cumsum().values
        dd = float((np.maximum.accumulate(np.concatenate([[0], eq])) - np.concatenate([[0], eq])).max())
        r[c] = dict(per_trade=float(t[c].mean()) if len(t) else 0.0, per_day=float(daily.mean()),
                    per_month=float(mon.mean()), per_year=float(daily.mean() * 248), total=float(daily.sum()),
                    worst_day=float(daily.min()), worst_month=float(mon.min()), maxdd=dd,
                    years_pos=f"{int((yr > 0).sum())}/{len(yr)}", p_month_pos=float((mon > 0).mean()),
                    t=float(daily.mean() / (daily.std(ddof=1) / np.sqrt(nd))) if nd > 2 and daily.std() > 0 else np.nan,
                    by_year={int(k): float(v) for k, v in yr.items()})
    return r


def random_p(Xt, t, years, B=2000, seed=11, col="pts"):
    """Same days, same number of trades per day, random decision minutes (09:16-15:00), same contract type and
    identical T/S/L: p = P(random mean >= real mean)."""
    if not len(t):
        return dict(p=1.0)
    rng = np.random.default_rng(seed)
    sub = Xt[Xt.year.isin(years)]
    pts = sub["_pts"].values
    dayv = sub.day.values
    days, st = np.unique(dayv, return_index=True)
    cnt = np.diff(np.append(st, len(dayv)))
    k = np.searchsorted(days, t.day.values)
    ok = (k < len(days)) & (days[np.minimum(k, len(days) - 1)] == t.day.values)
    k = k[ok]
    u = rng.random((B, len(k)))
    pick = st[k][None, :] + (u * cnt[k][None, :]).astype(np.int64)
    null = pts[pick].mean(axis=1)
    obs = float(t[col].values[ok].mean())
    return dict(p=float((1 + (null >= obs).sum()) / (B + 1)), obs=obs, null_mean=float(null.mean()),
                null_lo=float(np.quantile(null, 0.025)), null_hi=float(np.quantile(null, 0.975)))


# ------------------------------------------------------------------------------------------------ select
def _load_screens():
    R, M = {}, {}
    for u in UNDS3:
        R[u] = np.load(os.path.join(OUT, f"screen_{u}.npy"), mmap_mode="r")
        with open(os.path.join(OUT, f"screen_{u}_meta.pkl"), "rb") as f:
            M[u] = pickle.load(f)
    return R, M


def cell_index(meta):
    """flat cell -> (tf, group, label, local cell)."""
    out = []
    for tf, g, n, lab in meta:
        for i in range(n):
            out.append((tf, g, lab[i], i))
    return out


def row_mask(Xt, cutd, tf, g, local):
    for tf2, g2, code, n, lab in group_codes_one(Xt, cutd, tf, g):
        return code == local


def group_codes_one(Xt, cutd, tf, g):
    saved = GROUPS[:]
    try:
        GROUPS.clear()
        GROUPS.append((tf, g))
        return group_codes(Xt, cutd)
    finally:
        GROUPS.clear()
        GROUPS.extend(saved)


MIN_DAYS = 100


def combos_table(R, M, years, metric="g"):
    """All combos: und, typ, param, cell -> mean, t, ndays (flattened DataFrame)."""
    parts = []
    for u in UNDS3:
        m, t, nd, _ = stat(R[u], years, metric)
        ci = cell_index(M[u]["meta"])
        a, b, c = np.meshgrid(np.arange(4), np.arange(len(PARAMS)), np.arange(len(ci)), indexing="ij")
        parts.append(pd.DataFrame(dict(und=u, typ=a.ravel(), param=b.ravel(), cell=c.ravel(), mean=m.ravel(),
                                       t=t.ravel(), nd=nd.ravel())))
    df = pd.concat(parts, ignore_index=True)
    df["p"] = np.where(df.nd >= MIN_DAYS, sst.norm.sf(df.t.fillna(-99)), 1.0)
    return df


def choose(R, M, X, years, top=25, per_tf=True):
    """Shortlist on `years` by day-clustered t (gross), then sequential-sim each; pick the best sequential daily t."""
    df = combos_table(R, M, years, "g")
    df = df[(df.nd >= MIN_DAYS) & (df["mean"] > 0)]
    cis = {u: cell_index(M[u]["meta"]) for u in UNDS3}
    df["tf"] = [cis[u][c][0] for u, c in zip(df.und.values, df.cell.values)]
    short = pd.concat([df[df.tf == tf].nlargest(top, "t") for tf in ("1m", "5m")]) if per_tf else df.nlargest(top, "t")
    rows = []
    daily = {}
    for r in short.itertuples():
        T, S, L = PARAMS[r.param]
        tf, g, lab, loc = cis[r.und][r.cell]
        Xt = X[r.und][r.typ]
        mask = row_mask(Xt, M[r.und]["cutd"], tf, g, loc) & Xt.year.isin(years).values
        ch = sim(Xt, mask, T, S, L)
        tr = trades(Xt, ch, T, S, L, r.und, r.typ)
        alld = np.unique(Xt.day.values[Xt.year.isin(years).values])
        dly = tr.groupby("day").gross.sum().reindex(alld, fill_value=0.0)
        dn = tr.groupby("day").net_sp.sum().reindex(alld, fill_value=0.0)
        tt = dly.mean() / (dly.std(ddof=1) / np.sqrt(len(dly))) if dly.std() > 0 else 0
        rows.append(dict(und=r.und, typ=r.typ, param=r.param, cell=r.cell, tf=tf, label=lab, T=T, S=S, L=L,
                         screen_t=r.t, screen_mean=r.mean, n=len(tr), seq_gross_day=dly.mean(), seq_t=tt,
                         seq_net_sp_day=dn.mean()))
        daily[len(rows) - 1] = (dly, dn)
    return pd.DataFrame(rows), daily


def load_all():
    X = {}
    for u in UNDS3:
        A = load(u)
        X[u] = {ti: A[A.typ == ti].reset_index(drop=True) for ti in range(4)}
        del A
    return X


def select():
    t0 = time.time()
    R, M = _load_screens()
    X = load_all()
    print("loaded", f"{time.time() - t0:.0f}s", flush=True)
    train = [y for y in YEARS if y <= TRAIN_END]
    hold = [2025, 2026]
    res = {}
    # ---- multiple testing over every combo (train)
    for metric in ("g", "n"):
        df = combos_table(R, M, train, metric)
        res[f"n_combos"] = len(df)
        res[f"n_tested_{metric}"] = int((df.nd >= MIN_DAYS).sum())
        q = OV.bh(df.p.values)
        res[f"bh_{metric}"] = dict(q05=int((q < 0.05).sum()), q10=int((q < 0.10).sum()),
                                   best_t=float(df.t[df.nd >= MIN_DAYS].max()), min_q=float(q.min()))
        df["q"] = q
        df.nsmallest(40, "p").to_csv(os.path.join(OUT, f"top_{metric}.csv"), index=False)
        print(metric, res[f"bh_{metric}"], flush=True)
    # ---- shortlist + sequential sim on train, SPA over the shortlist
    sl, daily = choose(R, M, X, train, top=25)
    sl.to_csv(os.path.join(OUT, "shortlist.csv"), index=False)
    # daily vectors may differ in length per und: SPA per und group
    spa = {}
    for u in UNDS3:
        ii = [i for i in range(len(sl)) if sl.und[i] == u]
        if ii:
            G = pd.concat([daily[i][0] for i in ii], axis=1).fillna(0.0).values
            N_ = pd.concat([daily[i][1] for i in ii], axis=1).fillna(0.0).values
            spa[u] = dict(gross=OV.spa(G, B=1000), net_sp=OV.spa(N_, B=1000), n=len(ii))
    res["spa"] = spa
    picks = {}
    for tf in ("1m", "5m", "any"):
        s2 = sl if tf == "any" else sl[sl.tf == tf]
        best = s2.loc[s2.seq_t.idxmax()]
        picks[tf] = best.to_dict()
    res["picks"] = picks
    # ---- holdout, once, for the picks (+ the whole shortlist as a robustness view)
    hold_rows = []
    for i, r in sl.iterrows():
        Xt = X[r.und][r.typ]
        tf, g, lab, loc = cell_index(M[r.und]["meta"])[r.cell]
        mask = row_mask(Xt, M[r.und]["cutd"], tf, g, loc)
        out = {}
        for nm, ys in (("train", train), ("hold", hold)):
            mm = mask & Xt.year.isin(ys).values
            ch = sim(Xt, mm, r["T"], r.S, r.L)
            tr = trades(Xt, ch, r["T"], r.S, r.L, r.und, r.typ)
            alld = np.unique(Xt.day.values[Xt.year.isin(ys).values])
            out[nm] = summarize(tr, alld)
            Xt["_pts"] = outcome(Xt, r["T"], r.S, r.L, 0.0)[0]
            out[nm]["rand"] = random_p(Xt, tr, ys)
            if nm == "hold":
                out["hold_trades"] = tr
        hold_rows.append(out)
    res["shortlist_eval"] = hold_rows
    # ---- anchored walk-forward: test year Y picked on all years < Y
    wf = []
    for Y in range(2022, 2027):
        tr_years = [y for y in YEARS if y < Y]
        s2, _ = choose(R, M, X, tr_years, top=10)
        best = s2.loc[s2.seq_t.idxmax()]
        Xt = X[best.und][best.typ]
        tf, g, lab, loc = cell_index(M[best.und]["meta"])[best.cell]
        mask = row_mask(Xt, M[best.und]["cutd"], tf, g, loc) & (Xt.year.values == Y)
        ch = sim(Xt, mask, best["T"], best.S, best.L)
        tr = trades(Xt, ch, best["T"], best.S, best.L, best.und, best.typ)
        alld = np.unique(Xt.day.values[Xt.year.values == Y])
        wf.append(dict(year=Y, pick=dict(und=best.und, typ=int(best.typ), tf=tf, label=lab, T=int(best["T"]),
                                         S=int(best.S), L=int(best.L)), trades=tr, days=alld))
        print("wf", Y, best.und, best.typ, lab, best["T"], best.S, best.L, f"{tr.gross.sum():.0f}", f"{tr.net_sp.sum():.0f}", flush=True)
    res["wf"] = wf
    with open(os.path.join(OUT, "select.pkl"), "wb") as f:
        pickle.dump(res, f)
    print("select done", f"{time.time() - t0:.0f}s")


# ------------------------------------------------------------------------------------------------ the map (step 1)
def mapping():
    """Base rates (all entries) and by single condition, for every underlying, pooled types; from the screen sums."""
    R, M = _load_screens()
    years = YEARS
    out = {}
    for u in UNDS3:
        A = np.asarray(R[u], dtype=np.float64).sum(axis=3)        # (typ, param, cell, NST) all years
        out[u] = A
    with open(os.path.join(OUT, "map.pkl"), "wb") as f:
        pickle.dump(dict(A=out, meta={u: M[u]["meta"] for u in UNDS3}), f)


# ------------------------------------------------------------------------------------------------ report
TYPN = ["CE ATM", "CE 1-ITM", "PE ATM", "PE 1-ITM"]


def _offsets(meta):
    off, o = {}, 0
    for tf, g, n, lab in meta:
        off[(tf, g)] = (o, n, lab)
        o += n
    return off


def _rs(x):
    return f"{x:,.0f}"


def _rate_rows(A, cells, pi):
    """A: (typ, param, cell, NST) summed; returns dict of rates for the cells (summed over typ)."""
    s = A[:, pi, cells, :].sum(axis=0)
    N = s[..., 0]
    return dict(N=N, win=s[..., 8] / N, loss=s[..., 9] / N, evg=s[..., 2] / N, evn=s[..., 5] / N)


def report(md_path="/home/user/options-lab/research/SCALP17.md"):
    R, M = _load_screens()
    with open(os.path.join(OUT, "select.pkl"), "rb") as f:
        res = pickle.load(f)
    meta = M["NIFTY"]["meta"]
    offs = _offsets(meta)
    yi_all = list(range(len(YEARS)))
    Asum = sum(np.asarray(R[u], dtype=np.float64).sum(axis=3) for u in UNDS3)          # pooled unds
    Aund = {u: np.asarray(R[u], dtype=np.float64).sum(axis=3) for u in UNDS3}
    L = []
    w = L.append
    picks = res["picks"]
    ev = res["shortlist_eval"]
    sl = pd.read_csv(os.path.join(OUT, "shortlist.csv"))

    def find(p):
        for i, r in sl.iterrows():
            if r.und == p["und"] and r.typ == p["typ"] and r.param == p["param"] and r.cell == p["cell"]:
                return i
    # ---------------- verdict
    best = picks["any"]
    bi = find(best)
    hb = ev[bi]["hold"]
    tb = ev[bi]["train"]
    wf = res["wf"]
    wft = pd.concat([x["trades"] for x in wf], ignore_index=True)
    wfd = np.concatenate([x["days"] for x in wf])
    wfs = summarize(wft, wfd)
    pos_hold = sum(1 for e in ev if e["hold"]["gross"]["total"] > 0)
    pos_hold_n = sum(1 for e in ev if e["hold"]["net_sp"]["total"] > 0)
    pi0 = PARAMS.index((17, 15, 30))
    base = _rate_rows(Asum, [offs[("1m", ("all",))][0]], pi0)
    w("# SCALP17: \"+15-20 points, up to 5 trades a day, 1 lot\" on NIFTY / BANKNIFTY / FINNIFTY options")
    w("")
    w("## Verdict")
    w("")
    w(f"- **Base rate.** Bought from a random minute, an ATM or 1-ITM nearest-expiry option reaches +17 before -15 within 30 minutes "
      f"{base['win'][0]:.1%} of the time and -15 first {base['loss'][0]:.1%} (the rest time out). Conditional on resolving, "
      f"P(+17 first) = {base['win'][0] / (base['win'][0] + base['loss'][0]):.1%} against a break-even of 15/32 = 46.9%. The average entry makes "
      f"{base['evg'][0]:+.2f} pts gross and {base['evn'][0]:+.2f} pts net (app costs + a 1-pt spread). A plain \"+15-20, stop 15\" scalp is a "
      f"small-negative coin flip before costs and a clear loser after them.")
    w(f"- **Best rule found on 2020-2024** ({best['tf']} decisions): {best['und']} {TYPN[int(best['typ'])]}, when *{best['label']}*, "
      f"target +{int(best['T'])} / stop -{int(best['S'])} / time stop {int(best['L'])} min, max 5 a day. "
      f"Train: {_rs(tb['gross']['per_trade'])} Rs/trade gross, {_rs(tb['net_sp']['per_trade'])} net. "
      f"**Holdout 2025-Oct 2026: {_rs(hb['gross']['per_trade'])} Rs/trade gross ({_rs(hb['gross']['per_month'])}/month), "
      f"{_rs(hb['net_sp']['per_trade'])} Rs/trade net ({_rs(hb['net_sp']['per_month'])}/month)**, {hb['trades']} trades, "
      f"random-entry p = {hb['rand'].get('p', float('nan')):.3f}.")
    w(f"- **Walk-forward** (anchored, each year picked on all earlier years): {_rs(wfs['gross']['total'])} Rs gross, "
      f"{_rs(wfs['net_sp']['total'])} Rs net over {len(wf)} test years ({wfs['trades']} trades).")
    w(f"- **Multiple testing.** {res['n_combos']:,} condition x T/S/L x contract x underlying x timeframe combos were screened "
      f"({res['n_tested_g']:,} with >= {MIN_DAYS} train days). BH q < 0.05 for a positive gross edge: {res['bh_g']['q05']:,}; "
      f"net: {res['bh_n']['q05']:,}. Of the {len(ev)} shortlisted rules, {pos_hold} were positive gross and {pos_hold_n} net in the holdout.")
    bs = sl[sl["T"].isin([15, 17, 20])]
    bp = bs.loc[bs.seq_t.idxmax()] if len(bs) else None
    if bp is not None:
        e = ev[bp.name]
        w(f"- **Best rule inside Boss's +15-20 range** (picked the same way, 2020-2024 only): {bp.und} {TYPN[int(bp.typ)]} "
          f"({bp.tf}) when *{bp.label}*, +{int(bp['T'])} / -{int(bp.S)} / {int(bp.L)} min. Train {e['train']['tpd']:.1f} trades/day, "
          f"{_rs(e['train']['gross']['per_month'])} Rs/month gross, {_rs(e['train']['net_sp']['per_month'])} net. Holdout: "
          f"{e['hold']['win']:.0%} wins, {_rs(e['hold']['gross']['per_trade'])} Rs/trade gross ({_rs(e['hold']['gross']['per_month'])}/month), "
          f"{_rs(e['hold']['net_app']['per_trade'])} net app, {_rs(e['hold']['net_sp']['per_trade'])} net +spread "
          f"({_rs(e['hold']['net_sp']['per_month'])}/month); random-entry p {e['hold']['rand'].get('p', float('nan')):.3f}.")
    tfw = {tf: [i for i in range(len(sl)) if sl.tf[i] == tf] for tf in ("1m", "5m")}
    w("- **1-minute vs 5-minute decisions:** " + "; ".join(
        f"{tf}: {sum(ev[i]['hold']['gross']['total'] > 0 for i in ii)}/{len(ii)} shortlisted rules positive gross in the holdout, "
        f"{sum(ev[i]['hold']['net_sp']['total'] > 0 for i in ii)}/{len(ii)} net, mean {np.mean([ev[i]['hold']['gross']['per_trade'] for i in ii]):+.0f} Rs/trade gross, "
        f"{np.mean([ev[i]['hold']['net_sp']['per_trade'] for i in ii]):+.0f} net" for tf, ii in tfw.items()) +
      ". Neither timeframe produces a durable edge; 5-minute rules look better in-sample and fall further out of sample.")
    w("")
    w("**Bottom line: no.** A \"+15-20 points, up to 5 trades a day\" scalp is not achievable on this data. Gross, the average "
      "entry is a slightly losing coin flip at every T/S/time stop, and no condition survives the multiple-testing correction. "
      "Net of the app's costs (about Rs 90 a round trip, ~1.5 pts) and a realistic 1-pt spread, every shortlisted rule lost "
      "money in 2025-Oct 2026. Rs 1,000 x 5 a day is not on the table; the realistic expectation is a loss of roughly "
      "Rs 100-250 a trade.")
    w("")
    # ---------------- setup
    w("## Setup")
    w("")
    w("- Data: real Dhan 1-minute bars of the nearest-expiry option series (weekly; BANKNIFTY / FINNIFTY monthly after Nov 2024, "
      "when their weeklies stopped), NIFTY Aug 2020 - Oct 2026, BANKNIFTY / FINNIFTY Aug 2021 - Oct 2026; index minutes from "
      "`jx/ix_<U>.pkl` (the files the validated lab uses). Days whose index minutes are not real bars are skipped.")
    w("- Entries: decision on the close of every minute 09:16-15:00 (1m) or of every 5-minute bar (5m: 09:19, 09:24, ..., 14:59), "
      "fill at the next minute's OPEN. Contracts: CE/PE x ATM/1-ITM, strike from the index close at the decision minute. "
      "Exits checked minute by minute on the option's own high/low: target +T, stop -S, time stop L minutes, never past the 15:10 "
      "square-off. If target and stop fall in the same minute the stop is assumed first (conservative).")
    w("- Rs are for a fixed 60 qty (Boss's \"1 lot ~60\"; today's lots: NIFTY 65, BANKNIFTY 35, FINNIFTY 60), so 17 pts = Rs 1,020.")
    w("- Columns: **Gross** = points x 60, no charges, no slippage (Boss's headline). **Net app** = the app's fills (+-5 bps, "
      "SL -10 bps, stop gaps filled at the bar open) and the app's charges (`costs.Costs('app')`: Rs 20 + 20 brokerage, STT 0.15% "
      "of the sell, exchange, GST, stamp), about Rs 85-95 a round trip at Rs 100-250 premiums. **Net +spread** = also a 1-pt "
      "bid/ask spread (buy at mid + 0.5; the +T limit fills only when the traded high reaches T + 1 above the open; the SL-M "
      "triggers on the LTP and fills 0.5 below). **Stress** = a 2-pt spread.")
    w("- Choices made ONLY on 2020-2024; 2025-Oct 2026 run once at the end. Quintile cut-points of every feature come from 2020-2024.")
    w("")
    # ---------------- map: grid
    w("## 1. First-passage map")
    w("")
    w("### All entry minutes, all three indices, all four contracts (1-minute decisions)")
    w("")
    w("P(+T first) is the share of entries that hit +T before -S within L minutes; P(-S) the share that hit -S first; the rest "
      "time out. \"won/resolved\" = P(+T) / (P(+T) + P(-S)) - compare it with break-even S/(S+T). EV = average points per entry "
      "(time-outs at their exit price).")
    w("")
    w("| T | S | BE S/(S+T) | P(+T) 15m | P(+T) 30m | P(+T) 60m | P(-S) 30m | won/resolved 30m | EV gross 30m (pts) | EV net+spread 30m (pts) |")
    w("|---|---|---|---|---|---|---|---|---|---|")
    c0 = offs[("1m", ("all",))][0]
    for T in TS:
        for S in SS:
            r = {Lm: _rate_rows(Asum, [c0], PARAMS.index((T, S, Lm))) for Lm in LS}
            q = r[30]
            w(f"| {T} | {S} | {S / (S + T):.1%} | {r[15]['win'][0]:.1%} | {q['win'][0]:.1%} | {r[60]['win'][0]:.1%} | {q['loss'][0]:.1%} | "
              f"{q['win'][0] / (q['win'][0] + q['loss'][0]):.1%} | {q['evg'][0]:+.2f} | {q['evn'][0]:+.2f} |")
    w("")
    w("### By index and contract, T 17 / S 15 / 30 min (1-minute decisions, all years)")
    w("")
    w("| index | contract | entries | P(+17) | P(-15) | won/resolved (BE 46.9%) | EV gross pts | EV net+spread pts |")
    w("|---|---|---|---|---|---|---|---|")
    for u in UNDS3:
        for ti in range(4):
            s = Aund[u][ti, pi0, c0]
            N = s[0]
            w(f"| {u} | {TYPN[ti]} | {N:,.0f} | {s[8] / N:.1%} | {s[9] / N:.1%} | {s[8] / (s[8] + s[9]):.1%} | {s[2] / N:+.2f} | {s[5] / N:+.2f} |")
    w("")
    for tf in ("1m", "5m"):
        w(f"### By condition, T 17 / S 15 / 30 min, {tf} decisions (all indices and contracts pooled, all years)")
        w("")
        w("Directional features are aligned with the trade: for a PE, a falling index counts as positive momentum. "
          "dfav = distance (bps) from the day's extreme in the trade's favour (day high for a CE), dadv = from the other extreme; "
          "pcrch = net put-minus-call OI added near the money in 15 min (bps of OI, aligned); oich = the contract's own OI change "
          "% in 15 min; opm5 = the option's premium change over the last 5 minutes (pts); rexp = mean 1-minute range of the last "
          "15 minutes / that of the previous 5 days; b5rng = the 5-minute bar's range / the previous 5 days' average; streak = consecutive 5-minute bars of one colour, "
          "aligned (streak<=-3 = three or more bars AGAINST the trade, e.g. three green bars before buying a PE: a fade); "
          "\"adverse extreme\" = the 5-minute bar closed beyond the previous bar's low for a CE (high for a PE).")
        w("")
        w("Quintiles (Q1 lowest .. Q5 highest) are cut per index on 2020-2024.")
        w("")
        w("| condition | entries | P(+17) | P(-15) | won/resolved (BE 46.9%) | EV gross | EV net+spread |")
        w("|---|---|---|---|---|---|---|")
        for (tf2, g), (o, n, lab) in offs.items():
            if tf2 != tf or len(g) != 1:
                continue
            for i in range(n):
                s = Asum[:, pi0, o + i].sum(axis=0)
                N = s[0]
                if N < 1:
                    continue
                w(f"| {lab[i].split(' (')[0].split(' [')[0]} | {N:,.0f} | {s[8] / N:.1%} | {s[9] / N:.1%} | {s[8] / (s[8] + s[9]):.1%} | {s[2] / N:+.2f} | {s[5] / N:+.2f} |")
        w("")
    # ---------------- strongest cells, train vs holdout
    w("### The strongest cells on 2020-2024 and what they did in 2025-2026")
    w("")
    w("Every (index, contract, condition cell incl. pairs, T/S/L) ranked by the day-clustered t of its average gross points per "
      "entry on 2020-2024 (>= 100 train days). Holdout columns use the same cell and cut-points.")
    w("")
    w("| # | tf | index | contract | condition | T/S/L | train days | train P(+T) / BE | train EV gross | hold P(+T) | hold EV gross | hold EV net+spread |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|")
    top = pd.read_csv(os.path.join(OUT, "top_g.csv")).head(20)
    for k, r in enumerate(top.itertuples(), 1):
        T, S, Lm = PARAMS[r.param]
        tf, g, lab, loc = cell_index(M[r.und]["meta"])[r.cell]
        Ry = np.asarray(R[r.und][r.typ, r.param, r.cell], dtype=np.float64)
        tr_ = Ry[: TRAIN_END - YEARS[0] + 1].sum(0)
        ho = Ry[TRAIN_END - YEARS[0] + 1:].sum(0)
        w(f"| {k} | {tf} | {r.und} | {TYPN[r.typ]} | {lab} | {T}/{S}/{Lm} | {r.nd:.0f} | {tr_[8] / tr_[0]:.1%} / {S / (S + T):.1%} | "
          f"{tr_[2] / tr_[0]:+.2f} | {ho[8] / max(ho[0], 1):.1%} | {ho[2] / max(ho[0], 1):+.2f} | {ho[5] / max(ho[0], 1):+.2f} |")
    w("")
    # ---------------- rules
    w("## 2. Rules: chosen on 2020-2024, tested once on 2025-Oct 2026")
    w("")
    w(f"Shortlist: the top 25 combos per timeframe by train t (positive gross mean, >= {MIN_DAYS} train days), each run as a real "
      "sequence (one position at a time, next entry only after the exit, max 5 a day); the pick is the best sequential daily t "
      "on 2020-2024. Random baseline: the same days, the same number of trades per day, random decision minutes 09:16-15:00, same "
      "contract and identical T/S/L (2,000 draws; p = share of random means >= the rule's mean, gross points).")
    w("")
    for tf in ("1m", "5m"):
        p = picks[tf]
        i = find(p)
        e = ev[i]
        w(f"### Best {tf} rule: {p['und']} {TYPN[int(p['typ'])]} when *{p['label']}*; +{int(p['T'])} / -{int(p['S'])} / {int(p['L'])} min")
        w("")
        _rule_table(w, e)
        w("")
    w("### All shortlisted rules, train vs holdout (Rs per trade)")
    w("")
    w("| tf | index | contract | condition | T/S/L | train trades | train gross | train net+spread | hold trades | hold gross | hold net app | hold net+spread | hold rand p |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for i, r in sl.iterrows():
        e = ev[i]
        w(f"| {r.tf} | {r.und} | {TYPN[int(r.typ)]} | {r.label} | {int(r['T'])}/{int(r.S)}/{int(r.L)} | {e['train']['trades']} | "
          f"{e['train']['gross']['per_trade']:+.0f} | {e['train']['net_sp']['per_trade']:+.0f} | {e['hold']['trades']} | "
          f"{e['hold']['gross']['per_trade']:+.0f} | {e['hold']['net_app']['per_trade']:+.0f} | {e['hold']['net_sp']['per_trade']:+.0f} | "
          f"{e['hold']['rand'].get('p', float('nan')):.3f} |")
    w("")
    tfw = {tf: [i for i in range(len(sl)) if sl.tf[i] == tf] for tf in ("1m", "5m")}
    w("### 1-minute vs 5-minute decisions")
    w("")
    w("| timeframe | shortlisted | holdout gross > 0 | holdout net+spread > 0 | mean holdout gross Rs/trade | mean holdout net+spread Rs/trade |")
    w("|---|---|---|---|---|---|")
    for tf, ii in tfw.items():
        w(f"| {tf} | {len(ii)} | {sum(ev[i]['hold']['gross']['total'] > 0 for i in ii)} | {sum(ev[i]['hold']['net_sp']['total'] > 0 for i in ii)} | "
          f"{np.mean([ev[i]['hold']['gross']['per_trade'] for i in ii]):+.0f} | {np.mean([ev[i]['hold']['net_sp']['per_trade'] for i in ii]):+.0f} |")
    w("")
    # ---------------- WF
    w("### Anchored walk-forward")
    w("")
    w("| test year | picked on all earlier years | trades | gross Rs | net app Rs | net+spread Rs | stress Rs |")
    w("|---|---|---|---|---|---|---|")
    for x in wf:
        p, t = x["pick"], x["trades"]
        w(f"| {x['year']} | {p['und']} {TYPN[p['typ']]} {p['tf']} *{p['label']}* {p['T']}/{p['S']}/{p['L']} | {len(t)} | "
          f"{t.gross.sum():,.0f} | {t.net_app.sum():,.0f} | {t.net_sp.sum():,.0f} | {t.net_st.sum():,.0f} |")
    w(f"| all | | {len(wft)} | {wft.gross.sum():,.0f} | {wft.net_app.sum():,.0f} | {wft.net_sp.sum():,.0f} | {wft.net_st.sum():,.0f} |")
    w("")
    w("(Walk-forward years before 2025 use cut-points fitted on 2020-2024, a mild look-ahead in the bucket edges only.)")
    w("")
    # ---------------- multiple testing
    w("## Multiple testing")
    w("")
    w(f"- Combos screened: **{res['n_combos']:,}** = 3 indices x 4 contracts x 75 (T, S, L) x {sum(n for _, _, n, _ in meta):,} "
      f"condition cells (1m: {sum(n for tf, _, n, _ in meta if tf == '1m')}, 5m: {sum(n for tf, _, n, _ in meta if tf == '5m')}; "
      "singles and pairs of features, both timeframes counted together).")
    w(f"- Day-clustered one-sided p of the mean points per entry > 0 on 2020-2024, BH over all combos: gross q<0.05 = "
      f"{res['bh_g']['q05']:,} (q<0.10 = {res['bh_g']['q10']:,}, min q {res['bh_g']['min_q']:.3g}); net+spread q<0.05 = "
      f"{res['bh_n']['q05']:,} (min q {res['bh_n']['min_q']:.3g}).")
    for u, s in res["spa"].items():
        w(f"- Hansen SPA_c / White RC over the {s['n']} shortlisted {u} rules' train daily P&L vs not trading: gross SPA p = "
          f"{s['gross']['spa_p']:.3f} (RC {s['gross']['rc_p']:.3f}), net+spread SPA p = {s['net_sp']['spa_p']:.3f}. "
          "(The shortlist was itself picked from the screen, so these p-values are optimistic.)")
    w("")
    w("## Slippage realism for 15-20 point scalps")
    w("")
    w("- Charges alone (app model) cost about 1.4-1.6 pts a round trip on 60 qty; a 1-pt spread adds ~1 pt more on entry plus a "
      "stricter target fill and an earlier stop trigger, a 2-pt spread about double that. Against a +17 / -15 bracket that is "
      "10-20% of the win, which is why rules that look flat gross lose net.")
    w("- Stops are SL-M on 1-minute bars: when the option gaps through the stop the fill is the bar's open (included), and a "
      "target and stop in the same minute counts as the stop. Targets only fill when the traded high goes past the limit by the "
      "half-spread.")
    w("- Weekly expiry-day ATM options move 15 points in seconds; real fills there are worse than any 1-minute model.")
    txt = "\n".join(L)
    open(md_path, "w").write(txt + "\n")
    print("wrote", md_path)


def _rule_table(w, e):
    w("| period | trades | trades/day | win rate | | Rs/trade | Rs/day | Rs/month | Rs/year | worst day | worst month | max DD | years + | P(month > 0) |")
    w("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for nm, lab in (("train", "2020-24"), ("hold", "2025-Oct 26")):
        s = e[nm]
        for c, cl in (("gross", "gross"), ("net_app", "net app"), ("net_sp", "net +1pt spread"), ("net_st", "stress 2pt")):
            x = s[c]
            w(f"| {lab} | {s['trades']} | {s['tpd']:.2f} | {s['win']:.0%} | {cl} | {x['per_trade']:+,.0f} | {x['per_day']:+,.0f} | "
              f"{x['per_month']:+,.0f} | {x['per_year']:+,.0f} | {x['worst_day']:,.0f} | {x['worst_month']:,.0f} | {x['maxdd']:,.0f} | "
              f"{x['years_pos']} | {x['p_month_pos']:.0%} |")
    r1, r2 = e["train"]["rand"], e["hold"]["rand"]
    w("")
    w(f"Random entries with identical T/S/L (gross pts per trade): train rule {r1.get('obs', np.nan):+.2f} vs random "
      f"{r1.get('null_mean', np.nan):+.2f} (p {r1.get('p', np.nan):.3f}); holdout rule {r2.get('obs', np.nan):+.2f} vs random "
      f"{r2.get('null_mean', np.nan):+.2f} (p {r2.get('p', np.nan):.3f}).")
