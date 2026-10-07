"""h4: combine and scale the best-evidenced option-buying rules to target Rs 5,000/day; daily P&L matrix, correlations,
three portfolio rules chosen BEFORE the holdout, walk-forward, sizing, risk, random-entry portfolio baseline, SPA/RC,
and ONE holdout test (2025-10-01 onward) of the portfolio picked on pre-holdout data.

    python3 -I research/hunt/h4/portfolio.py      (reads <OBUY_CACHE>/h4/*.parquet from run_comps.py)
"""
from __future__ import annotations

import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import obuy  # noqa: E402,F401
from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy.data import market  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

IN = os.path.join(C.CACHE, "h4")
START, HOLD = pd.Timestamp("2021-10-01"), pd.Timestamp("2025-10-01")
TARGET = 5000.0
EXES = ("gross", "app", "real")
GRIDS = ("btst", "camarilla")          # components with a parameter grid: one variant picked on pre-holdout data
SHORT = {"BANKNIFTY": "BN", "FINNIFTY": "FIN", "NIFTY": "NIFTY", "SENSEX": "SENSEX", "MIDCPNIFTY": "MIDCP"}


def comp_key(df):
    k = df.comp.copy()
    liq = df.comp.isin(["liq_bnfin", "liq_ext"])
    k[liq] = "liq_" + df.und[liq].map(SHORT)
    return k


def load():
    T = {}
    for e in EXES:
        t = pd.read_parquet(os.path.join(IN, f"trades_{e}.parquet"))
        t["key"] = comp_key(t)
        T[e] = t
    P = pd.read_parquet(os.path.join(IN, "pool_real.parquet"))
    return T, P


def calendar():
    days = pd.to_datetime(pd.Index(market().index("NIFTY").days))
    return days[(days >= START)]


def daily(t, cal, col="net"):
    s = t.groupby("day")[col].sum()
    return s.reindex(cal, fill_value=0.0)


def maxdd(x):
    eq = np.cumsum(x)
    return float((eq - np.maximum.accumulate(np.concatenate([[0], eq]))[1:]).min())


def stats_row(x, cal):
    x = np.asarray(x, float)
    s = pd.Series(x, index=cal)
    m = s.groupby(s.index.to_period("M")).sum()
    return dict(days=len(x), mean_day=x.mean(), net=x.sum(), worst_day=x.min(), best_day=x.max(),
                worst_month=m.min(), losing_months=f"{(m < 0).sum()}/{len(m)}", maxdd=maxdd(x),
                sharpe=x.mean() / (x.std() + 1e-9) * np.sqrt(248), pos_days=(x > 0).mean())


def p_losing_month(x, B=20000, block=5, seed=11, mlen=21):
    """Stationary-bootstrap probability that a 21-trading-day month loses money."""
    rng = np.random.default_rng(seed)
    x = np.asarray(x, float)
    T = len(x)
    out = np.empty(B)
    for b in range(B):
        idx = []
        i = rng.integers(T)
        while len(idx) < mlen:
            idx.append(i)
            i = rng.integers(T) if rng.random() < 1 / block else (i + 1) % T
        out[b] = x[idx].sum()
    return float((out < 0).mean())


def p_dd(x, level, B=5000, block=5, seed=12, horizon=248):
    rng = np.random.default_rng(seed)
    x = np.asarray(x, float)
    T = len(x)
    hit = 0
    for b in range(B):
        idx = np.empty(horizon, int)
        i = rng.integers(T)
        for k in range(horizon):
            idx[k] = i
            i = rng.integers(T) if rng.random() < 1 / block else (i + 1) % T
        if maxdd(x[idx]) <= -level:
            hit += 1
    return hit / B


def outlay(t, cal):
    """Max premium tied up at once per day (entry x qty, overlapping holds; overnight holds counted on the entry day)."""
    res = {}
    for d, g in t.groupby("day"):
        ev = []
        for r in g.itertuples(index=False):
            v = float(r.entry) * float(r.qty)
            x = r.exit_min if r.exit_min >= r.entry_min else 10_000
            ev += [(r.entry_min, v), (x + 0.5, -v)]
        ev.sort()
        cur = mx = 0.0
        for _, v in ev:
            cur += v
            mx = max(mx, cur)
        res[d] = mx
    return pd.Series(res).reindex(cal, fill_value=0.0)


def main():
    T, P = load()
    cal = calendar()
    pre = cal < HOLD
    years = sorted(set(cal.year))
    lines = []
    out = {}

    # ---------------------------------------------------------------- grids: pick one variant per grid on pre-holdout real net
    picks = {}
    grid_info = {}
    for g in GRIDS:
        t = T["real"][T["real"].comp == g]
        byv = t[t.day < HOLD].groupby("vid").net.sum().sort_values(ascending=False)
        picks[g] = byv.index[0]
        # anchored walk-forward pick per test year (best on all earlier data, from 2020)
        wf = {}
        for y in years:
            past = t[t.day < pd.Timestamp(f"{y}-01-01")].groupby("vid").net.sum()
            wf[y] = past.idxmax() if len(past) else byv.index[0]
        grid_info[g] = dict(n=int(t.vid.nunique()), pick=byv.index[0], pick_pre_net=float(byv.iloc[0]), wf=wf)

    def comp_trades(e, key, wf_year=None):
        t = T[e][T[e].key == key]
        base = key if key in GRIDS else None
        if base:
            if wf_year == "wf":
                parts = [t[(t.vid == grid_info[base]["wf"][y]) & (t.day.dt.year == y)] for y in years]
                t = pd.concat(parts)
            else:
                t = t[t.vid == picks[base]]
        return t[t.day >= START]

    keys = sorted(set(T["real"].key))
    # ---------------------------------------------------------------- daily matrices (fixed picks; and WF picks for grids)
    D = {e: pd.DataFrame({k: daily(comp_trades(e, k), cal) for k in keys}) for e in EXES}
    DWF = {e: pd.DataFrame({k: daily(comp_trades(e, k, "wf"), cal) for k in keys}) for e in EXES}

    # ---------------------------------------------------------------- per component table
    comp_rows = []
    for k in keys:
        r = {"component": k}
        for e in EXES:
            x = D[e][k]
            r[f"{e}_pre"] = x[pre].sum()
            r[f"{e}_hold"] = x[~pre].sum()
        tr = comp_trades("real", k)
        r["trades"] = len(tr)
        r["trades_hold"] = int((tr.day >= HOLD).sum())
        r["first"] = str(tr.day.min().date()) if len(tr) else "-"
        for y in years:
            r[f"y{y}"] = D["real"][k][cal.year == y].sum()
        r["real_pre_per_day"] = D["real"][k][pre].mean()
        r["sd_pre"] = D["real"][k][pre].std()
        comp_rows.append(r)
    CT = pd.DataFrame(comp_rows).set_index("component")

    # random-entry baseline p per component (real execution, pre-holdout and holdout trades)
    def rb(key, which):
        tr = comp_trades("real", key)
        tr = tr[(tr.day < HOLD) if which == "pre" else (tr.day >= HOLD)]
        vids = tr.vid.unique()
        pl = P[P.vid.isin(vids)]
        if not len(tr) or not len(pl):
            return np.nan
        if len(vids) > 1:
            return np.nan
        return OF.random_baseline(tr, pl, B=2000)["p"]
    CT["p_rand_pre"] = [rb(k, "pre") for k in CT.index]
    CT["p_rand_hold"] = [rb(k, "hold") for k in CT.index]
    ok = CT.p_rand_pre.notna()
    CT.loc[ok, "q_bh_pre"] = OF.bh(CT.loc[ok, "p_rand_pre"].values)

    # ---------------------------------------------------------------- correlations (pre-holdout, real)
    corr = D["real"][pre].corr()
    # ---------------------------------------------------------------- portfolio rules (weights per year from EARLIER data only)
    def weights(rule, y, Dx):
        """Lots per component for test period starting Jan 1 of y (or HOLD), from data strictly before it."""
        cut = pd.Timestamp(f"{y}-01-01") if y != "hold" else HOLD
        past = Dx[Dx.index < cut]
        w = pd.Series(0.0, index=keys)
        live = [k for k in keys if (comp_trades("real", k).day < cut).sum() >= 20]
        if rule == "EQ":
            w[live] = 1.0
        elif rule == "RP":
            sd = past[live].std().replace(0, np.nan)
            iv = (1 / sd).fillna(0)
            # scale so the AVERAGE weight is 1 lot (same gross exposure as EQ)
            w[live] = iv / iv[iv > 0].mean()
        elif rule == "WF+":
            pos = [k for k in live if past[k].sum() > 0]
            w[pos] = 1.0
        elif rule == "WF+RP":
            pos = [k for k in live if past[k].sum() > 0]
            sd = past[pos].std().replace(0, np.nan)
            iv = (1 / sd).fillna(0)
            if len(pos):
                w[pos] = iv / iv[iv > 0].mean()
        return w

    RULES = ["EQ", "RP", "WF+", "WF+RP"]
    test_years = [y for y in years if y >= 2023 and pd.Timestamp(f"{y}-01-01") < HOLD]
    wf_series = {}
    wf_w = {}
    for rule in RULES:
        parts = {e: [] for e in EXES}
        for y in test_years:
            w = weights(rule, y, DWF["real"])
            wf_w[(rule, y)] = w
            m = (cal.year == y) & pre
            for e in EXES:
                parts[e].append((DWF[e][m] * w).sum(axis=1))
        wf_series[rule] = {e: pd.concat(parts[e]) for e in EXES}
    wf_rows = []
    for rule in RULES:
        x = wf_series[rule]["real"]
        r = dict(rule=rule, **stats_row(x.values, x.index))
        r["gross"] = wf_series[rule]["gross"].sum()
        r["app"] = wf_series[rule]["app"].sum()
        for y in test_years:
            r[f"y{y}"] = x[x.index.year == y].sum()
        wf_rows.append(r)
    WF = pd.DataFrame(wf_rows).set_index("rule")
    chosen = WF.sharpe.idxmax() if (WF.mean_day > 0).any() else WF.mean_day.idxmax()

    # ---------------------------------------------------------------- the chosen portfolio, frozen at 2025-10-01
    w_hold = weights(chosen, "hold", D["real"])
    unit_wf = wf_series[chosen]["real"]                   # WF unit-size daily series 2023 .. Sep 2025
    mu_unit = unit_wf.mean()
    k_scale = TARGET / mu_unit if mu_unit > 0 else np.nan
    # sized, pre-holdout (WF) and holdout
    hold_unit = {e: (D[e][~pre] * w_hold).sum(axis=1) for e in EXES}
    pre_unit_fixed = {e: (D[e][pre] * w_hold).sum(axis=1) for e in EXES}   # in-sample look at the frozen weights

    def sized(x):
        return x * k_scale

    risk = {}
    if np.isfinite(k_scale):
        xs = sized(unit_wf).values
        risk = dict(k=k_scale, lots=(w_hold * k_scale).round(1).to_dict(), **stats_row(xs, unit_wf.index),
                    p_losing_month=p_losing_month(xs), p_dd_5L=p_dd(xs, 5e5), p_dd_10L=p_dd(xs, 1e6))
        # capital: premium tied up at once with the holdout weights x k
        outs = []
        for kk in keys:
            if w_hold[kk] > 0:
                outs.append(outlay(comp_trades("real", kk), cal) * w_hold[kk] * k_scale)
        O = pd.concat(outs, axis=1).sum(axis=1) if outs else pd.Series(0.0, index=cal)
        risk["outlay_p95"] = float(O[O > 0].quantile(0.95)) if (O > 0).any() else 0.0
        risk["outlay_max"] = float(O.max())
        risk["outlay_max_note"] = "sum of per-component daily maxima (assumes their peaks coincide: conservative)"
    hold_rows = {}
    for e in EXES:
        xh = sized(hold_unit[e]) if np.isfinite(k_scale) else hold_unit[e]
        hold_rows[e] = stats_row(xh.values, xh.index)
        hold_rows[e]["p_losing_month_boot"] = p_losing_month(xh.values) if e == "real" else np.nan
    # random-entry portfolio baseline (same exits, strikes, sizing; one random alternative per real trade)
    rng = np.random.default_rng(21)

    def rand_port(period):
        tr = []
        for kk in keys:
            if w_hold[kk] <= 0 and period == "hold":
                continue
            t = comp_trades("real", kk)
            t = t[(t.day >= HOLD)] if period == "hold" else t[(t.day < HOLD) & (t.day >= pd.Timestamp("2023-01-01"))]
            if len(t):
                tr.append(t.assign(w=w_hold[kk] if period == "hold" else np.nan))
        if not tr:
            return np.nan, np.nan, np.nan
        tr = pd.concat(tr)
        if period != "hold":
            # WF weights by year
            tr["w"] = [wf_w[(chosen, d.year)][k] for d, k in zip(tr.day, tr.key)]
        pl = P[P.vid.isin(tr.vid.unique())]
        g = pl.groupby(["vid", "parent"]).net.apply(np.asarray)
        obs = float((tr.net * tr.w).sum())
        keyidx = [(v, c) for v, c in zip(tr.vid, tr.cand)]
        have = [i for i, kc in enumerate(keyidx) if kc in g.index]
        arrs = [g[keyidx[i]] for i in have]
        ww = tr.w.values[have]
        obs_used = float((tr.net.values[have] * ww).sum())
        B = 2000
        null = np.zeros(B)
        for a, w_ in zip(arrs, ww):
            if w_ == 0:
                continue
            null += a[rng.integers(0, len(a), B)] * w_
        return obs_used, float(np.mean(null)), float((1 + (null >= obs_used).sum()) / (B + 1))
    rp_pre = rand_port("pre")
    rp_hold = rand_port("hold")

    # ---------------------------------------------------------------- SPA / RC over EVERYTHING tried (pre-holdout, real)
    allv = []
    for vid, t in T["real"].groupby("vid"):
        allv.append(daily(t[(t.day >= START)], cal).rename(vid))
    # split liquidity vids by index (each index is its own component)
    X = pd.concat(allv, axis=1)
    for rule in RULES:
        X[f"PORT_{rule}"] = wf_series[rule]["real"].reindex(cal, fill_value=0.0)
    Xp = X[pre & (cal >= pd.Timestamp("2023-01-01"))]
    sp = OF.spa(Xp.values, B=2000)
    n_tried = X.shape[1]

    out = dict(grid_info={g: dict(n=v["n"], pick=v["pick"], pick_pre_net=v["pick_pre_net"],
                                  wf={str(a): b for a, b in v["wf"].items()}) for g, v in grid_info.items()},
               chosen=chosen, w_hold=w_hold.to_dict(), risk=risk, hold=hold_rows, rand_pre=rp_pre, rand_hold=rp_hold,
               spa=dict(rc_p=sp["rc_p"], spa_p=sp["spa_p"], n=n_tried), mu_unit=mu_unit,
               hold_unit_real_per_day=float(hold_unit["real"].mean()), hold_unit_gross_per_day=float(hold_unit["gross"].mean()),
               hold_unit_app_per_day=float(hold_unit["app"].mean()),
               pre_fixed_unit_real_per_day=float(pre_unit_fixed["real"].mean()))
    CT.to_csv(os.path.join(IN, "components.csv"))
    WF.to_csv(os.path.join(IN, "wf_portfolios.csv"))
    corr.to_csv(os.path.join(IN, "corr.csv"))
    D["real"].to_csv(os.path.join(IN, "daily_real.csv"))
    with open(os.path.join(IN, "summary.json"), "w") as f:
        json.dump(out, f, indent=1, default=str)
    pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40)
    print(CT.round(0).to_string())
    print(corr.round(2).to_string())
    print(WF.round(1).to_string())
    print(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    main()
