"""h21 analysis. `pre` = choice sample only (< 2025-10-01); `holdout` = run ONCE after pre (reads pre picks).

    flock <scratch>/obuy.lock python3 -I research/hunt/h21/analyse.py pre
    flock <scratch>/obuy.lock python3 -I research/hunt/h21/analyse.py holdout
"""
from __future__ import annotations

import json
import os
import pickle
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import sim  # noqa: E402
import rules  # noqa: E402

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import overfit as OF  # noqa: E402

H = sim.HOLDOUT
OUT = sim.OUT
ARMS = sim.ARM_ORDER
UNITS = ARMS + ("combined",)


def all_days():
    from obuy.data import market
    ix = market().index("BANKNIFTY")
    return [d for d in ix.days if ix.d[d]["real"]]


def outcomes(P, kappa=sim.KAPPA):
    """{(arm, kind, spec): trades} for every spec, plus R0 (app mode)."""
    res = {}
    for arm in ARMS:
        for kind in ("real", "rand"):
            pk = P[(arm, kind)]
            for sn, sp in rules.SPECS.items():
                res[(arm, kind, sn)] = sim.exits(pk, sp, "fixed", kappa)
            res[(arm, kind, "R0")] = sim.exits(pk, rules.SPECS["fixed"], "app", kappa)
    return res


def part(df, which):
    return df[df.day < H] if which == "pre" else df[df.day >= H]


def candidates(O, F, arm, v, which, spec=None):
    t = part(O[(arm, "real", spec or v["spec"])], which)
    if v.get("filt"):
        f = F[arm].loc[t.cand.values]
        t = t[rules.filt_mask(f, arm, v["filt"])]
    return t


def run_variant(O, F, v, which, liq=None):
    """Returns {unit: kept trades} for the 4 arms alone and the combined book (guard on unless v['guard'] is False)."""
    out = {}
    cands = {}
    for arm in ARMS:
        c = candidates(O, F, arm, v, which)
        cands[arm] = c
        out[arm] = rules.arm_positions(c, arm, v)
    comb, lk = rules.combined_positions(cands, v, guard=v.get("guard", True), liq=liq)
    out["combined"] = pd.concat([comb[a] for a in ARMS])
    out["_comb_parts"] = comb
    out["_liq"] = lk
    return out


def daily(tr, days):
    if tr is None or not len(tr):
        return pd.Series(0.0, index=days)
    return tr.groupby("day").net.sum().reindex(days, fill_value=0.0)


def summ(tr, days):
    dly = daily(tr, days)
    eq = dly.cumsum()
    mon = dly.groupby([d.strftime("%Y-%m") for d in dly.index]).sum()
    n = len(tr) if tr is not None else 0
    if not n:
        return dict(n=0, net=0, rs_day=0.0)
    s = tr.net.std(ddof=1)
    return dict(n=n, gross=round(float(tr.gross.sum())), charges=round(float(tr.charges.sum())), net=round(float(tr.net.sum())),
                per_trade=round(float(tr.net.mean()), 1), win=round(float((tr.net > 0).mean()) * 100, 1),
                t=round(float(tr.net.mean() / (s / np.sqrt(n))), 2) if n > 2 and s > 0 else None,
                rs_day=round(float(dly.mean()), 1), gross_day=round(float(tr.gross.sum() / len(days)), 1),
                maxdd=round(float((eq - eq.cummax()).min())), worst_day=round(float(dly.min())),
                worst_month=round(float(mon.min())), months_neg=f"{int((mon < 0).sum())}/{len(mon)}",
                sharpe=round(float(dly.mean() / dly.std(ddof=1) * np.sqrt(248)), 2) if dly.std() > 0 else None,
                years={int(y): round(float(v)) for y, v in tr.groupby("year").net.sum().items()})


def rand_p(O, kept, spec, arm=None):
    """Random entries with identical exits: same day / arm, random 5-min bar, coin-flip side, same strike."""
    if not len(kept):
        return dict(p=1.0)
    reals, pools = [], []
    arms = [arm] if arm else ARMS
    for a in arms:
        k = kept[kept.book == a]
        if not len(k):
            continue
        r = O[(a, "rand", spec)]
        r = r[r.day.isin(set(k.day))]
        reals.append(k.assign(cand=a + ":" + k.cand.astype(str)))
        pools.append(pd.DataFrame(dict(parent=a + ":" + r.tag.astype(str), net=r.net.values)))
    return OF.random_baseline(pd.concat(reals), pd.concat(pools), B=2000)


def boot_losing_month(dly, B=2000, block=10, seed=11):
    """P(a 21-session month is net negative) by stationary block bootstrap of the daily P&L."""
    rng = np.random.default_rng(seed)
    x = dly.values
    T = len(x)
    neg = 0
    for _ in range(B):
        i = rng.integers(T)
        s = 0.0
        for k in range(21):
            if k and rng.random() < 1 / block:
                i = rng.integers(T)
            s += x[i % T]
            i += 1
        neg += s < 0
    return neg / B


# ------------------------------------------------------------------------------------------------ pre
def pre():
    P, F = sim.load()
    O = outcomes(P)
    days = pd.Index([d for d in all_days() if d < H and d >= min(O[("orb", "real", "fixed")].day)])
    rows, series, kept_by = [], {}, {}
    variants = dict(rules.VARIANTS)
    for vn, v in variants.items():
        res = run_variant(O, F, v, "pre")
        for u in UNITS:
            k = res[u]
            s = summ(k, days)
            rp = rand_p(O, k, v["spec"], None if u == "combined" else u)
            s.update(variant=vn, unit=u, p_rand=round(rp["p"], 4), rand_obs=rp.get("obs"), rand_null=rp.get("null_mean"))
            rows.append(s)
            series[(vn, u)] = daily(k, days)
            kept_by[(vn, u)] = k
        print(vn, {u: (rows[-5 + i]["n"], rows[-5 + i]["net"]) for i, u in enumerate(UNITS)}, flush=True)
    # V09: the combined book without the guard (V01 without) - trial in the combined family
    v = dict(rules.VARIANTS["V01_base_fixed"], guard=False)
    res = run_variant(O, F, v, "pre")
    k = res["combined"]
    s = summ(k, days)
    rp = rand_p(O, k, "fixed")
    s.update(variant="V09_no_guard(V01)", unit="combined", p_rand=round(rp["p"], 4), rand_obs=rp.get("obs"), rand_null=rp.get("null_mean"))
    rows.append(s)
    series[("V09_no_guard(V01)", "combined")] = daily(k, days)
    kept_by[("V09_no_guard(V01)", "combined")] = k
    # reference R0 (app today), not a trial
    ref = {}
    for u_guard in (False, True):
        r0 = run_variant(O, F, dict(rules.V("R0"), guard=u_guard), "pre")
        for u in UNITS:
            if u != "combined" and u_guard:
                continue
            ref[f"{u}{'|guard' if (u == 'combined' and u_guard) else ''}"] = summ(r0[u], days)
    T = pd.DataFrame(rows)
    T["p_used"] = np.where(T.net > 0, T.p_rand, 1.0)
    T["q_bh"] = OF.bh(T.p_used.values)
    # SPA per unit and over all trials
    spa = {}
    for u in UNITS:
        cols = [c for c in series if c[1] == u]
        X = np.column_stack([series[c].values for c in cols])
        r = OF.spa(X, B=1000)
        spa[u] = dict(rc_p=r["rc_p"], spa_p=r["spa_p"], best=cols[r["best"]][0], n=len(cols))
    X = np.column_stack([s.values for s in series.values()])
    r = OF.spa(X, B=1000)
    spa["all"] = dict(rc_p=r["rc_p"], spa_p=r["spa_p"], best=list(series)[r["best"]], n=X.shape[1])
    # walk-forward by year (train = all earlier years from 2021; test 2023, 2024, 2025-to-Sep)
    wf = {}
    for u in UNITS:
        cols = [c for c in series if c[1] == u]
        by = pd.DataFrame({c[0]: series[c].groupby([d.year for d in days]).sum() for c in cols}).T
        out = []
        for y in (2023, 2024, 2025):
            past = by[[c for c in by.columns if c < y]].sum(axis=1)
            pick = past.idxmax()
            out.append(dict(year=y, pick=pick, train=round(float(past[pick])), test=round(float(by.loc[pick, y]))))
        wf[u] = dict(rows=out, oos=sum(r["test"] for r in out), pos_years=sum(r["test"] > 0 for r in out))
    picks = {}
    for u in UNITS:
        tu = T[T.unit == u].sort_values("net", ascending=False)
        b = tu.iloc[0]
        promoted = bool(b.net > 0 and b.q_bh < 0.05 and wf[u]["oos"] > 0 and wf[u]["pos_years"] >= 2 and spa[u]["spa_p"] < 0.10)
        picks[u] = dict(variant=b.variant, pre_net=int(b.net), q_bh=float(b.q_bh), p_rand=float(b.p_rand), promoted=promoted)
    # losing-month probability and per-year of the picks (pre)
    for u in UNITS:
        picks[u]["p_losing_month_pre"] = boot_losing_month(series[(picks[u]["variant"], u)])
    T.to_csv(os.path.join(HERE, "pre_variants.csv"), index=False)
    rep = dict(spa=spa, wf=wf, picks=picks, ref_R0=ref, n_trials=len(T), days=len(days))
    with open(os.path.join(HERE, "pre_report.json"), "w") as f:
        json.dump(rep, f, indent=1, default=str)
    with open(os.path.join(OUT, "pre_series.pkl"), "wb") as f:
        pickle.dump(series, f)
    pd.set_option("display.width", 250)
    print(T[["variant", "unit", "n", "gross", "net", "per_trade", "rs_day", "p_rand", "q_bh", "maxdd"]].to_string())
    print(json.dumps(rep, indent=1, default=str))


# ------------------------------------------------------------------------------------------------ holdout (ONCE)
def liq_trades():
    t = pd.read_parquet(os.path.join(sim.SCR, "h19", "trades.parquet"))
    t = t[t.arm.isin(["liq_bn", "liq_fin"])].copy()
    t["day"] = [d.date() for d in pd.to_datetime(t.day)]
    t["year"] = [d.year for d in t.day]
    return t


def holdout():
    rep = json.load(open(os.path.join(HERE, "pre_report.json")))
    P, F = sim.load()
    out = {}
    lq = liq_trades()
    alld = all_days()
    O = outcomes(P)
    first = min(O[("orb", "real", "fixed")].day)
    for which in ("pre", "hold"):
        days = pd.Index([d for d in alld if (d < H if which == "pre" else d >= H) and d >= first])
        lqw = lq[(lq.day < H) if which == "pre" else (lq.day >= H)]
        out[which] = {}
        for u in UNITS:
            vn = rep["picks"][u]["variant"]
            v = dict(rules.VARIANTS[vn]) if vn in rules.VARIANTS else dict(rules.VARIANTS["V01_base_fixed"], guard=False)
            res = run_variant(O, F, v, "pre" if which == "pre" else "hold")
            k = res[u]
            s = summ(k, days)
            if which == "hold":
                s["p_rand"] = rand_p(O, k, v["spec"], None if u == "combined" else u)["p"]
                s["p_losing_month"] = boot_losing_month(daily(k, days))
                s["variant"] = vn
            out[which][u] = s
        # references
        for nm, v in (("R0_app_today", dict(rules.V("R0"), guard=False)), ("V01_fixed_lock_only", dict(rules.VARIANTS["V01_base_fixed"], guard=False)),
                      ("V08_PKG", rules.VARIANTS["V08_PKG"])):
            res = run_variant(O, F, v, "pre" if which == "pre" else "hold")
            out[which]["ref:" + nm] = {u: summ(res[u], days) for u in UNITS}
        # Liquidity additivity: Liquidity alone vs Liquidity + combined pick (guard incl. Liquidity BN)
        lq_d = daily(lqw, days)
        add = dict(liq_alone=summ(lqw, days))
        for nm in ("combined", "orb", "orb_fresh", "orb_sweep", "range_fade"):
            vn = rep["picks"][nm]["variant"]
            v = dict(rules.VARIANTS.get(vn, rules.VARIANTS["V01_base_fixed"]))
            cands = {a: candidates(O, F, a, v, "pre" if which == "pre" else "hold") for a in ARMS}
            if nm != "combined":
                cands = {a: (c if a == nm else c.iloc[0:0]) for a, c in cands.items()}
            bn = lqw[lqw.arm == "liq_bn"]
            comb, lk = rules.combined_positions(cands, v, guard=True, liq=bn)
            tot = pd.concat([comb[a] for a in ARMS] + ([lk] if lk is not None else []) + [lqw[lqw.arm == "liq_fin"]])
            d = daily(tot, days)
            add[nm] = dict(variant=vn, total=summ(tot, days), liq_bn_dropped=int(len(bn) - (len(lk) if lk is not None else 0)),
                           liq_bn_dropped_net=round(float(bn.net.sum() - (lk.net.sum() if lk is not None else 0))),
                           corr_with_liq=round(float(np.corrcoef(daily(pd.concat([comb[a] for a in ARMS]), days), lq_d)[0, 1]), 3))
        out[which]["liquidity_additivity"] = add
    # kappa sensitivity for the picks in the holdout
    sens = {}
    for kap in (0.0, 0.01, 0.04):
        Ok = outcomes(P, kap)
        days = pd.Index([d for d in alld if d >= H])
        for u in UNITS:
            vn = rep["picks"][u]["variant"]
            v = dict(rules.VARIANTS.get(vn, dict(rules.VARIANTS["V01_base_fixed"], guard=False)))
            sens[f"{u}|k={kap}"] = summ(run_variant(Ok, F, v, "hold")[u], days)
    out["kappa_sens_hold"] = sens
    with open(os.path.join(HERE, "holdout_report.json"), "w") as f:
        json.dump(out, f, indent=1, default=str)
    print(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    {"pre": pre, "holdout": holdout}[sys.argv[1]]()
