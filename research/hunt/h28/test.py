"""h28 Part B analysis: calendar-flag option-buying variants vs random-direction and random-day baselines, BH, SPA,
walk-forward by year; then (once) the holdout for survivors. See PREREG.md.

    python3 -I research/hunt/h28/test.py            # PRE only (< 2025-10-01)
    python3 -I research/hunt/h28/test.py --holdout  # survivors from choice.json, once

Inputs (<scratch>/hunt/h28/): opt_trades.parquet, day_mom.parquet (build.py), flags.parquet (cal.py), priors.csv
(index_study.py). Outputs: variants_pre.csv, wf.csv, uncond.csv, choice.json, test_pre.log / holdout.csv.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.overfit import bh, spa  # noqa: E402
from obuy.stats import max_dd  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt/h28")
HOLD = date(2025, 10, 1)
HS = {"NIFTY": 0.0016, "BANKNIFTY": 0.0016, "FINNIFTY": 0.0042, "MIDCPNIFTY": 0.0021, "SENSEX": 0.0020}
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
TIMES = ("0920", "1015", "1200", "1400")
EXITS = ("E1_liq", "E2_s15t30", "E3_s30t60", "E4_lock", "E5_t60", "E6_close")
BASE = ("und", "day", "open", "high", "low", "close", "opt_era")
B = 2000
MIN_TR = 20
WF_YEARS = (2022, 2023, 2024, 2025)
pd.set_option("display.width", 250)
pd.set_option("display.max_columns", 40)


def load(holdout):
    tr = pd.read_parquet(os.path.join(OUT, "opt_trades.parquet"))
    tr["day"] = pd.to_datetime(tr.day).dt.date
    tr = tr[(tr.day >= HOLD) if holdout else (tr.day < HOLD)].copy()
    hs = tr.und.map(HS).values
    sp = (tr.entry.values.astype(float) + tr.exit.values.astype(float)) * tr.qty.values
    tr["net1"] = tr.net.values - hs * sp
    tr["net15"] = tr.net.values - 1.5 * hs * sp
    tr["prem"] = tr.entry.values.astype(float) * tr.qty.values
    piv = tr.pivot_table(index=["und", "day", "T", "exit_set"], columns="side",
                         values=["net1", "net15", "gross", "prem"], aggfunc="first")
    piv.columns = [f"{a}_{'C' if b > 0 else 'P'}" for a, b in piv.columns]
    piv = piv.reset_index()
    piv = piv.dropna(subset=["net1_C", "net1_P"])          # both sides tradable (needed by the direction baseline)
    mom = pd.read_parquet(os.path.join(OUT, "day_mom.parquet"))
    mom["day"] = pd.to_datetime(mom.day).dt.date
    piv = piv.merge(mom[["und", "day", "T", "mom"]], on=["und", "day", "T"], how="left")
    piv["year"] = [d.year for d in piv.day]
    fl = pd.read_parquet(os.path.join(OUT, "flags.parquet"))
    fl["day"] = pd.to_datetime(fl.day).dt.date
    fcols = [c for c in fl.columns if c not in BASE]
    piv = piv.merge(fl[["und", "day"] + fcols], on=["und", "day"], how="left")
    for f in fcols:
        piv[f] = piv[f].fillna(False).astype(bool)
    pr = pd.read_csv(os.path.join(OUT, "priors.csv"))
    prior = {(r.flag, r.und, r.year): r.side for r in pr.itertuples()}
    sessions = {u: sorted(set(fl[(fl.und == u) & fl.opt_era & ((fl.day >= HOLD) if holdout else (fl.day < HOLD))].day))
                for u in UNDS}
    return piv, fcols, prior, sessions


def variant_list(fcols):
    out = []
    for f in fcols:
        for u in UNDS:
            for T in TIMES:
                for X in EXITS:
                    out.append((f, u, T, X, "prior"))
                    if T != "0920":
                        out.append((f, u, T, X, "mom"))
    return out


def sides(g, f, u, mode, prior):
    if mode == "prior":
        return np.array([prior.get((f, u, y), 0) for y in g.year.values])
    return g["mom"].fillna(0).values.astype(int)


def pick(g, s, col="net1"):
    return np.where(s > 0, g[f"{col}_C"].values, g[f"{col}_P"].values)


def evaluate(piv, fcols, prior, sessions, variants, rng, baselines=True):
    groups = {k: g.reset_index(drop=True) for k, g in piv.groupby(["und", "T", "exit_set"])}
    rows, daily = [], {}
    for vi, (f, u, T, X, mode) in enumerate(variants):
        g = groups.get((u, T, X))
        r = dict(vid=vi, flag=f, und=u, T=T, exit=X, mode=mode)
        if g is None:
            rows.append(r)
            continue
        s_all = sides(g, f, u, mode, prior)
        m = g[f].values & (s_all != 0)
        n = int(m.sum())
        r["trades"] = n
        if n == 0:
            rows.append(r)
            continue
        gf, s = g[m], s_all[m]
        v = pick(gf, s)
        v15 = pick(gf, s, "net15")
        gr = pick(gf, s, "gross")
        r.update(net=v.sum(), net15=v15.sum(), gross=gr.sum(), avg=v.mean(), win=(v > 0).mean() * 100,
                 pf=v[v > 0].sum() / max(-v[v < 0].sum(), 1e-9), ce_share=(s > 0).mean() * 100,
                 rs_day=v.sum() / max(len(sessions[u]), 1), prem_med=float(np.median(np.where(s > 0, gf.prem_C, gf.prem_P))))
        yv = pd.Series(v, index=gf.year.values).groupby(level=0).sum()
        for y in range(2020, 2027):
            r[f"y{y}"] = yv.get(y, np.nan)
        r["wf_years_pos"] = int(sum(yv.get(y, 0) > 0 for y in WF_YEARS))
        dser = pd.Series(v, index=gf.day.values).groupby(level=0).sum()
        r["max_dd"] = max_dd(dser.sort_index().cumsum().values)
        mon = pd.Series(v, index=pd.to_datetime(pd.Series(gf.day.values)).dt.to_period("M").values).groupby(level=0).sum()
        r["worst_month"] = mon.min()
        daily[vi] = dser
        if baselines and n >= MIN_TR and v.sum() > 0:
            # (i) random direction: coin flip between the CE and PE trade of the same session/time/exit
            C_, P_ = gf.net1_C.values, gf.net1_P.values
            bits = rng.random((B, n)) < 0.5
            rd = np.where(bits, C_[None, :], P_[None, :]).mean(axis=1)
            r["p_dir"] = (1 + (rd >= v.mean()).sum()) / (B + 1)
            # POST-HOC sensitivity (not pre-registered): normal-approximation p with unlimited resolution, because
            # B = 2,000 caps the smallest p at 1/2,001 and BH over 8,820 variants then cannot reach q < 0.10.
            a_, d_ = (C_ + P_) / 2, (C_ - P_) / 2
            r["pz_dir"] = _pz(v.mean(), a_.mean(), np.sqrt((d_ ** 2).sum()) / n)
            # (ii) random day: a random NON-flag session of the same index and year, same T/exit, the mode's side there
            nf = ~g[f].values & (s_all != 0)
            pool_v = pick(g, s_all)
            yrs_t = gf.year.values
            draws = np.zeros((B, n))
            ok = True
            for y in np.unique(yrs_t):
                idx = np.nonzero(nf & (g.year.values == y))[0]
                cols = np.nonzero(yrs_t == y)[0]
                if len(idx) == 0:
                    ok = False
                    break
                draws[:, cols] = pool_v[idx[rng.integers(0, len(idx), (B, len(cols)))]]
            r["p_day"] = (1 + (draws.mean(axis=1) >= v.mean()).sum()) / (B + 1) if ok else 1.0
            if ok:
                mu_, var_ = 0.0, 0.0
                for y in np.unique(yrs_t):
                    idx = np.nonzero(nf & (g.year.values == y))[0]
                    k_ = (yrs_t == y).sum()
                    mu_ += k_ * pool_v[idx].mean()
                    var_ += k_ * pool_v[idx].var()
                r["pz_day"] = _pz(v.mean(), mu_ / n, np.sqrt(var_) / n)
            else:
                r["pz_day"] = 1.0
        else:
            r["p_dir"] = r["p_day"] = 1.0
        rows.append(r)
    return pd.DataFrame(rows), daily


def _pz(x, mu, se):
    from math import erf, sqrt
    if not se > 0:
        return 1.0
    z = (x - mu) / se
    return float(0.5 * (1 - erf(z / sqrt(2))))


def uncond(piv, sessions):
    """What buying blindly costs: every session, CE / PE / coin flip, per index x time x exit (PRE)."""
    rows = []
    for (u, T, X), g in piv.groupby(["und", "T", "exit_set"]):
        rows.append(dict(und=u, T=T, exit=X, n=len(g), ce_avg=g.net1_C.mean(), pe_avg=g.net1_P.mean(),
                         coin_avg=(g.net1_C.mean() + g.net1_P.mean()) / 2, ce_gross=g.gross_C.mean(),
                         pe_gross=g.gross_P.mean(), cost_per_trade=((g.gross_C - g.net1_C).mean() + (g.gross_P - g.net1_P).mean()) / 2,
                         prem_med=g.prem_C.median()))
    return pd.DataFrame(rows)


def main_pre():
    rng = np.random.default_rng(28)
    piv, fcols, prior, sessions = load(False)
    variants = variant_list(fcols)
    print("variants:", len(variants), "| pivot rows", len(piv))
    un = uncond(piv, sessions)
    un.to_csv(os.path.join(OUT, "uncond.csv"), index=False)
    print("Blind buying, avg net/trade at real spread (PRE), by index x time (mean over exits):")
    print(un.groupby(["und", "T"])[["ce_avg", "pe_avg", "coin_avg", "cost_per_trade", "prem_med"]].mean().round(0).to_string())
    df, daily = evaluate(piv, fcols, prior, sessions, variants, rng)
    tested = df.trades.fillna(0) >= MIN_TR
    df["tested"] = tested
    for c in ("p_dir", "p_day", "pz_dir", "pz_day"):
        if c not in df:
            df[c] = 1.0
        df[c] = df[c].fillna(1.0)
    df.loc[~tested, ["p_dir", "p_day", "pz_dir", "pz_day"]] = 1.0
    df["qz_dir"] = bh(df.pz_dir.values)
    df["qz_day"] = bh(df.pz_day.values)
    zs = df[(df.qz_dir < 0.10) & (df.qz_day < 0.10)]
    print("POST-HOC sensitivity, normal-approx p: min qz_dir", round(df.qz_dir.min(), 4), "| min qz_day",
          round(df.qz_day.min(), 4), "| both < 0.10:", len(zs))
    if len(zs):
        print(zs[["flag", "und", "T", "exit", "mode", "trades", "net", "net15", "wf_years_pos", "pz_dir", "pz_day", "qz_dir",
                  "qz_day"]].round(4).to_string())
    df["q_dir"] = bh(df.p_dir.fillna(1).values)
    df["q_day"] = bh(df.p_day.fillna(1).values)
    # SPA over tested variants: (session x variant) daily net, 0 when not trading
    allsess = sorted(set(d for u in UNDS for d in sessions[u]))
    tv = df[tested].vid.values
    X = np.zeros((len(allsess), len(tv)), np.float32)
    pos = {d: i for i, d in enumerate(allsess)}
    for j, vi in enumerate(tv):
        s = daily[vi]
        X[[pos[d] for d in s.index], j] = s.values
    sp = spa(X.astype(float), B=1000)
    print("SPA over", len(tv), "tested variants x", len(allsess), "sessions:", sp)
    # walk-forward (anchored by year): training = option-era years < Y
    wf_rows = []
    for Y in WF_YEARS:
        cand = []
        for vi in df.vid.values:
            s = daily.get(vi)
            if s is None:
                continue
            yrs = np.array([d.year for d in s.index])
            tm = yrs < Y
            # trades count = flagged sessions traded (1 trade per session per variant)
            if tm.sum() >= MIN_TR:
                cand.append((s.values[tm].sum(), vi, s.values[yrs == Y].sum(), int((yrs == Y).sum())))
        cand.sort(reverse=True)
        best = cand[0]
        top10 = cand[:10]
        v = df.loc[df.vid == best[1]].iloc[0]
        wf_rows.append(dict(year=Y, pick=f"{v.flag}|{v.und}|{v['T']}|{v['exit']}|{v['mode']}", train_net=best[0],
                            test_net=best[2], test_trades=best[3], top10_test_net_avg=np.mean([c[2] for c in top10]),
                            top10_picks=";".join(f"{df.loc[df.vid == c[1]].iloc[0].flag}|{df.loc[df.vid == c[1]].iloc[0].und}"
                                                 for c in top10)))
    wf = pd.DataFrame(wf_rows)
    wf.to_csv(os.path.join(OUT, "wf.csv"), index=False)
    print("Walk-forward (best-by-training variant each year):")
    print(wf.round(0).to_string())
    print("WF total best:", wf.test_net.sum().round(0), "| top-10 avg total:", wf.top10_test_net_avg.sum().round(0))
    df["spa_p"] = sp["spa_p"]
    df["rc_p"] = sp["rc_p"]
    df["surv"] = (df.net15 > 0) & (df.q_dir < 0.10) & (df.q_day < 0.10) & (df.wf_years_pos >= 3) & (sp["spa_p"] < 0.10)
    df.to_csv(os.path.join(OUT, "variants_pre.csv"), index=False)
    print("tested variants:", int(tested.sum()), "of", len(df), "| net>0:", int((df[tested].net > 0).sum()),
          "| net15>0:", int((df[tested].net15 > 0).sum()), "| raw p_dir<0.05:", int((df.p_dir < 0.05).sum()),
          "| raw p_day<0.05:", int((df.p_day < 0.05).sum()), "| both<0.05:", int(((df.p_dir < 0.05) & (df.p_day < 0.05)).sum()),
          "| min q_dir", round(df.q_dir.min(), 3), "| min q_day", round(df.q_day.min(), 3))
    cols = ["flag", "und", "T", "exit", "mode", "trades", "net", "net15", "gross", "avg", "win", "pf", "rs_day", "ce_share",
            "wf_years_pos", "p_dir", "p_day", "q_dir", "q_day", "max_dd"] + [f"y{y}" for y in range(2020, 2026)]
    print("Top 25 tested variants by PRE net (1x spread):")
    print(df[tested].sort_values("net", ascending=False)[cols].head(25).round(0).to_string())
    print("Top 15 by p_dir+p_day:")
    print(df[tested].assign(pp=df.p_dir + df.p_day).sort_values("pp")[cols].head(15).round(3).to_string())
    # per flag family: best tested variant and share of positive variants
    fam = df[tested].groupby("flag").agg(n_var=("vid", "size"), pos_share=("net", lambda x: (x > 0).mean() * 100),
                                         best_net=("net", "max"), med_avg=("avg", "median"), min_pdir=("p_dir", "min"),
                                         min_pday=("p_day", "min"))
    fam.to_csv(os.path.join(OUT, "family_pre.csv"))
    print(fam.sort_values("best_net", ascending=False).round(2).to_string())
    surv = df[df.surv].sort_values("net", ascending=False).head(3)
    print("SURVIVORS:", len(df[df.surv]), surv[cols].round(3).to_string() if len(surv) else "none")
    with open(os.path.join(OUT, "choice.json"), "w") as f:
        json.dump(dict(survivors=surv[["flag", "und", "T", "exit", "mode"]].to_dict("records"), spa=sp,
                       wf_picks=wf.pick.tolist()), f, default=str)


def main_holdout():
    ch = json.load(open(os.path.join(OUT, "choice.json")))
    piv, fcols, prior, sessions = load(True)
    rng = np.random.default_rng(29)
    vs = [(c["flag"], c["und"], c["T"], c["exit"], c["mode"]) for c in ch["survivors"]]
    # info only: the walk-forward's last pick (2025) traded in the holdout
    wf_last = tuple(ch["wf_picks"][-1].split("|"))
    pre = pd.read_csv(os.path.join(OUT, "variants_pre.csv"))
    top = pre[pre.tested].sort_values("net", ascending=False).head(5)
    tops = [(r.flag, r.und, f"{int(r.T):04d}" if not isinstance(r.T, str) else r.T, r.exit, r.mode) for r in top.itertuples()]
    vs_all = vs + [wf_last] + tops
    df, daily = evaluate(piv, fcols, prior, sessions, vs_all, rng, baselines=False)
    df["role"] = ["survivor"] * len(vs) + ["info: WF 2025 pick"] + ["info: top-5 PRE net (not survivors)"] * len(tops)
    print("holdout sessions:", {u: len(sessions[u]) for u in UNDS})
    for vi, s in daily.items():
        mon = s.groupby(pd.to_datetime(pd.Series(s.index)).dt.to_period("M").values).sum()
        print(vs_all[vi], "monthly:", mon.round(0).to_dict())
    df.to_csv(os.path.join(OUT, "holdout.csv"), index=False)
    print(df.round(3).to_string())


if __name__ == "__main__":
    if "--holdout" in sys.argv:
        main_holdout()
    else:
        main_pre()
