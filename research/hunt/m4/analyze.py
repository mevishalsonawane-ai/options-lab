"""M4 analysis (see PREREG.md). Usage: python -I analyze.py q1|q2|q3|q4design|q4hold SYM [SYM ...]
Reads scratchpad/hunt/m4/work/{ent,sig}_SYM.parquet; writes research/hunt/m4/results/*.csv"""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import json
from pathlib import Path
import numpy as np
import pandas as pd
from scipy.stats import norm, spearmanr

SP = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt")
W = SP / "m4" / "work"
RES = Path("/home/user/options-lab/research/hunt/m4/results")
RES.mkdir(exist_ok=True)
HOLD0 = pd.Timestamp("2026-07-08")
MULT = {"CRUDEOIL": 100, "CRUDEOILM": 10, "NATURALGAS": 1250, "NATGASMINI": 250, "GOLD": 100, "GOLDM": 10,
        "SILVER": 30, "SILVERM": 5, "COPPER": 2500}
MINI = {"CRUDEOIL": "CRUDEOILM", "NATURALGAS": "NATGASMINI", "GOLD": "GOLDM", "SILVER": "SILVERM"}
SPREAD = {"NATURALGAS": 0.006, "NATGASMINI": 0.009, "GOLD": 0.010, "GOLDM": 0.010, "SILVER": 0.010, "SILVERM": 0.010,
          "COPPER": 0.015}
HN = ["h60", "h240", "eod"]
HMIN = {"h15": 15, "h60": 60, "h240": 240, "eod": 10 ** 6}
CAP = 100000.0


def crude_spread():
    sp = json.load(open(SP / "strad_crude" / "chain_snaps_spread.json")) if (
            SP / "strad_crude" / "chain_snaps_spread.json").exists() else None
    if sp is None:
        sp = json.load(open("/home/user/options-lab/research/hunt/strad_crude/results/spread_model.json"))
    return {int(k): v for k, v in sp["by_otm"].items()}


CRUDE_SP = crude_spread()
try:
    EST = {o["sym"]: o["est_atm_spread"] for o in json.load(open(RES / "spread_estimates.json")) if o["sym"] != "CRUDEOIL"}
except Exception:
    EST = {}


def rel_spread(sym, mny, tod):
    if sym in ("CRUDEOIL", "CRUDEOILM"):
        base = np.array([CRUDE_SP.get(int(-m), 0.0035) for m in np.atleast_1d(mny)])
        if sym == "CRUDEOILM":
            base = base * 1.5
    else:
        est = EST.get(sym)
        if est is not None:  # amendment 3: minute-data estimate, crude's per-offset shape
            base = np.array([est * CRUDE_SP.get(int(-m), 0.0035) / CRUDE_SP[0] for m in np.atleast_1d(mny)])
        else:
            base = np.full(len(np.atleast_1d(mny)), SPREAD[sym])
    return base * np.where(np.atleast_1d(tod) < 17 * 60, 2.0, 1.0)


def fees(buy_rs, sell_rs):
    brk = 40.0
    exch = 0.000418 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    gst = 0.18 * (brk + exch + sebi)
    return brk + exch + sebi + gst + 0.0005 * sell_rs + 0.00003 * buy_rs


def load(sym):
    E = pd.read_parquet(W / f"ent_{sym}.parquet")
    E["sym"] = sym
    E = E[E.P0 * MULT[sym] >= 500].reset_index(drop=True)  # amendment 4: no sub-Rs-500 lottery tickets
    E["mny"] = np.where(E.cp == 0, -E.k, E.k)  # + = ITM steps
    E["mb"] = pd.cut(E.mny, [-9, -2, -1, 0, 1, 9], labels=["OTM2-3", "OTM1", "ATM", "ITM1", "ITM2-3"]).astype(str)
    E["side"] = np.where(E.cp == 0, "call", "put")
    E["cell_m"] = E.mb + " " + E.side
    E["db"] = pd.cut(E.dte, [-1, 0, 3, 10, 20, 99], labels=["0", "1-3", "4-10", "11-20", "21+"]).astype(str)
    E["tb"] = pd.cut(E.tod, [0, 14 * 60 - 1, 17 * 60 + 29, 24 * 60], labels=["morning", "afternoon", "evening"]).astype(str)
    E["month"] = E.day.dt.to_period("M")
    E["hold"] = E.day >= HOLD0
    return E


def add_pnl(E, hn, exit_col="P", mult=None, spread_mult=1.0):
    """Rs P&L for 1 lot. mult None -> lot rule (big if premium<=1L else mini else skip)."""
    sym = E.sym.iloc[0]
    if mult is None:
        big = MULT[sym]
        mini = MULT.get(MINI.get(sym, ""), np.nan)
        m = np.where(E.P0 * big <= CAP, big, np.where(E.P0 * mini <= CAP, mini, np.nan))
        ctr = np.where(E.P0 * big <= CAP, sym, MINI.get(sym, ""))
    else:
        m = np.full(len(E), mult)
        ctr = np.full(len(E), sym)
    P1 = E[f"{hn}_{exit_col}"].values.astype(float)
    P0 = E.P0.values.astype(float)
    texit = np.minimum(E.tod.values + HMIN[hn], 23 * 60 + 25)
    rs_in = rel_spread(sym, E.mny.values, E.tod.values) * spread_mult
    rs_out = rel_spread(sym, E.mny.values, texit) * spread_mult
    if mult is None:  # mini spreads are wider
        mini_mask = ctr != sym
        rs_in = np.where(mini_mask, rs_in * 1.5, rs_in)
        rs_out = np.where(mini_mask, rs_out * 1.5, rs_out)
    gross = (P1 - P0) * m
    cost = fees(P0 * m, P1 * m) + 0.5 * rs_in * P0 * m + 0.5 * rs_out * P1 * m
    return gross, gross - cost, m


def clustered_t(x, g):
    x = np.asarray(x, float)
    ok = np.isfinite(x)
    x, g = x[ok], np.asarray(g)[ok]
    if len(x) < 10:
        return np.nan
    dev = pd.Series(x - x.mean()).groupby(g).sum()
    se = np.sqrt((dev ** 2).sum()) / len(x)
    return x.mean() / se if se > 0 else np.nan


def bh(p):
    p = np.asarray(p, float)
    q = np.full(len(p), np.nan)
    ok = np.isfinite(p)
    pv = p[ok]
    o = np.argsort(pv)
    r = pv[o] * len(pv) / (np.arange(len(pv)) + 1)
    r = np.minimum.accumulate(r[::-1])[::-1]
    qq = np.empty(len(pv))
    qq[o] = np.minimum(r, 1)
    q[ok] = qq
    return q


# ------------------------------------------------------------------ Q1
def cell_stats(D, hn, keys):
    D = D[D[f"{hn}_P"].notna() & (D.P0 > 0)].copy()
    D["dh"] = D[f"{hn}_dh"] / D.P0
    D["ug"] = D[f"{hn}_P"] / D.P0 - 1
    g, n, _ = add_pnl(D, hn, mult=MULT[D.sym.iloc[0]])
    D["un_rs"] = n
    D["un"] = n / (D.P0 * MULT[D.sym.iloc[0]])
    D["g_rs"] = g
    out = []
    for key, x in D.groupby(keys):
        mm = x.groupby("month").dh.mean()
        out.append(dict(zip(keys, key if isinstance(key, tuple) else (key,)), H=hn, n=len(x), days=x.day.nunique(),
                        vr=np.sqrt(x[f"{hn}_rv"].sum() / x[f"{hn}_iv"].sum()), dh=x.dh.mean(),
                        t_dh=clustered_t(x.dh, x.day), m_pos=(mm > 0).mean(), ug=x.ug.mean(), un=x.un.mean(),
                        gross_rs=x.g_rs.mean(), net_rs=x.un_rs.mean(), prem_rs=(x.P0 * MULT[x.sym.iloc[0]]).median()))
    return pd.DataFrame(out)


def q1(syms):
    allc, summ, ev, exp = [], [], [], []
    for sym in syms:
        E = load(sym)
        for part, D in (("design", E[~E.hold]), ("holdout", E[E.hold])):
            for hn in ["h15"] + HN:
                for keys in (["tb"], ["mb"], ["cell_m"], ["db"], []):
                    kk = ["sym"] + keys
                    s = cell_stats(D, hn, kk)
                    s["part"], s["by"] = part, "+".join(keys) or "all"
                    summ.append(s)
                if hn in HN:
                    c = cell_stats(D, hn, ["sym", "cell_m", "db", "tb"])
                    c["part"] = part
                    allc.append(c)
                    # events: pooled across dte/tod (expiry day excluded)
                    Dn = D[D.dte > 0]
                    evcols = {"eia_in": Dn[f"{hn}_eia"], "ngs_in": Dn[f"{hn}_ngs"], "cpi_in": Dn[f"{hn}_cpi"],
                              "fomc_day": Dn.fomc_day, "fomc_next": Dn.fomc_next, "opec_mon": Dn.opec_mon,
                              "eia_day": Dn.eia_day, "ngs_day": Dn.ngs_day}
                    anyev = np.zeros(len(Dn), bool)
                    for en, m in evcols.items():
                        m = m.fillna(False).astype(bool).values
                        if en not in ("eia_in", "ngs_in", "cpi_in"):
                            anyev |= m
                        if m.sum() < 20:
                            continue
                        for keys, cm in ((["sym", "cell_m"], None), (["sym"], "all")):
                            s = cell_stats(Dn[m], hn, keys)
                            s["event"], s["part"] = en, part
                            if cm:
                                s["cell_m"] = cm
                            ev.append(s)
                    s = cell_stats(Dn[~anyev & ~Dn.cpi_day.values], hn, ["sym"])
                    s["event"], s["part"], s["cell_m"] = "none", part, "all"
                    ev.append(s)
            # to expiry from the 15:00 grid
            X = D[(D.tod == 15 * 60) & (D.dte > 0) & D.expF.notna()]
            for keys in (["mb", "side"], ["db"], []):
                for key, x in X.groupby(["sym"] + keys):
                    exp.append(dict(zip(["sym"] + keys, key if isinstance(key, tuple) else (key,)), part=part, n=len(x),
                                    days=x.day.nunique(), pay_over_prem=x.exp_pay.sum() / x.P0.sum(),
                                    med_ret=(x.exp_pay / x.P0 - 1).median(), by="+".join(keys) or "all"))
    C = pd.concat(allc, ignore_index=True)
    # gate (design only)
    Dg = C[C.part == "design"].copy()
    Dg["p"] = 2 * norm.sf(Dg.t_dh.abs())
    Dg["p1"] = norm.sf(Dg.t_dh)  # one-sided: underpriced
    Dg["q"] = bh(Dg.p1.values)
    Dg["eligible"] = (Dg.n >= 300) & (Dg.days >= 20)
    Dg["pass"] = Dg.eligible & (Dg.dh > 0) & (Dg.q < 0.10) & (Dg.m_pos >= 2 / 3) & (Dg.un > 0)
    C = C.merge(Dg[["sym", "cell_m", "db", "tb", "H", "p1", "q", "eligible", "pass"]], how="left",
                on=["sym", "cell_m", "db", "tb", "H"])
    C.to_csv(RES / f"q1_cells_{'_'.join(syms)}.csv", index=False)
    pd.concat(summ, ignore_index=True).to_csv(RES / f"q1_summary_{'_'.join(syms)}.csv", index=False)
    pd.concat(ev, ignore_index=True).to_csv(RES / f"q1_events_{'_'.join(syms)}.csv", index=False)
    pd.DataFrame(exp).to_csv(RES / f"q1_expiry_{'_'.join(syms)}.csv", index=False)
    print("cells tested (design):", len(Dg), "eligible:", int(Dg.eligible.sum()), "pass:", int(Dg["pass"].sum()))
    print("min q among eligible:", Dg[Dg.eligible].q.min())
    print("eligible cells with dh>0:", int((Dg.eligible & (Dg.dh > 0)).sum()), "un>0:", int((Dg.eligible & (Dg.un > 0)).sum()))
    print(Dg[Dg.eligible].sort_values("dh", ascending=False).head(8)[
              ["sym", "cell_m", "db", "tb", "H", "n", "days", "vr", "dh", "t_dh", "m_pos", "ug", "un", "net_rs", "q"]].to_string())
    print(Dg[Dg.eligible].sort_values("net_rs", ascending=False).head(5)[
              ["sym", "cell_m", "db", "tb", "H", "n", "days", "vr", "dh", "t_dh", "ug", "un", "net_rs", "q"]].to_string())


# ------------------------------------------------------------------ Q2
def q2(syms):
    out = []
    for sym in syms:
        E = load(sym)
        D = E[(~E.hold) & (E.tod >= 17 * 60 + 30) & (E.dte >= 1) & E.iv0.notna() & E.h60_P.notna()].copy()
        m = MULT[sym]
        F, K, T, s, c = D.F0.values, D.K.values, D.tau0.values.astype(float), D.iv0.values.astype(float), D.cp.values == 0
        from build import b76, b76_delta
        P = b76(F, K, T, s, c)
        dl = b76_delta(F, K, T, s, c)
        up = b76(F * np.where(c, 1.01, 0.99), K, T, s, c)
        th = P - b76(F, K, T * (1 - 60 / np.maximum(D.N_rem.values, 61)), s, c)
        D["delta"] = np.abs(dl)
        D["elast"] = np.abs(dl) * F / P
        D["g1pct_per1k"] = (up - P) / P * 1000
        D["theta_hr_pct"] = th / P
        D["theta_hr_rs"] = th * m
        D["sp"] = rel_spread(sym, D.mny.values, D.tod.values)
        D["rt_cost_pct"] = (fees(D.P0 * m, D.P0 * m) + D.sp * D.P0 * m) / (D.P0 * m)
        # sigma60 per month from the futures' 60-min moves (design entries, ATM call rows to avoid duplication)
        sig = E[(E.k == 0) & (E.cp == 0)].groupby("month").mn_fwd60.std()
        D["sig60"] = D.month.map(sig)
        mv = np.asarray(np.where(c, D.mn_fwd60, -D.mn_fwd60) / D.sig60, float)
        D["ret60"] = D.h60_P / D.P0 - 1
        for key, x in D.groupby(["mb", "side"]):
            fav = x[mv[D.index.get_indexer(x.index)] >= 1]
            flat = x[np.abs(mv[D.index.get_indexer(x.index)]) < 0.25]
            out.append(dict(sym=sym, mb=key[0], side=key[1], n=len(x), prem_rs=(x.P0 * m).median(),
                            delta=x.delta.median(), elast=x.elast.median(), g1pct_per1k=x.g1pct_per1k.median(),
                            theta_hr_pct=x.theta_hr_pct.median(), theta_hr_rs=x.theta_hr_rs.median(),
                            spread_pct=x.sp.median(), rt_cost_pct=x.rt_cost_pct.median(),
                            fav_ret60=fav.ret60.median(), fav_n=len(fav), flat_ret60=flat.ret60.median(),
                            fav_net=fav.ret60.median() - x.rt_cost_pct.median()))
    O = pd.DataFrame(out)
    O.to_csv(RES / f"q2_convexity_{'_'.join(syms)}.csv", index=False)
    print(O.round(4).to_string())


# ------------------------------------------------------------------ Q3
def q3(syms, part_hold=False):
    out = []
    for sym in syms:
        G = pd.read_parquet(W / f"sig_{sym}.parquet")
        G["S1_rr"] = G.rr
        G["S2_drr"] = G.rr - G.rr_l15
        G["S3_div"] = G.atmiv - G.atmiv_l15
        G["S4_dbasis"] = G.basis - G.basis_l5
        if "nextiv" in G:
            G["S5_term"] = G.nextiv - G.atmiv
        tot = G.oip + G.oic
        G["S6_dpcr"] = ((G.oip - G.oic) - (G.oip_l60 - G.oic_l60)) / tot.replace(0, np.nan)
        G["S7_oibuild"] = ((tot - (G.oip_l60 + G.oic_l60)) / tot.replace(0, np.nan)) * np.sign(G.back60)
        G["hold"] = G.day >= HOLD0
        G = G[G.dte > 0]
        for part, D in (("design", G[~G.hold]), ("holdout", G[G.hold])):
            for s in [c for c in G.columns if c[:2] in ("S1", "S2", "S3", "S4", "S5", "S6", "S7")]:
                for h in (15, 60):
                    x = D[[s, f"fwd{h}", "day"]].replace([np.inf, -np.inf], np.nan).dropna()
                    if len(x) < 200:
                        continue
                    ic = spearmanr(x[s], x[f"fwd{h}"])[0]
                    daily = x.groupby("day").apply(
                        lambda z: spearmanr(z.iloc[:, 0], z.iloc[:, 1])[0] if len(z) > 8 else np.nan,
                        include_groups=False).dropna()
                    t = daily.mean() / daily.std() * np.sqrt(len(daily)) if len(daily) > 5 else np.nan
                    out.append(dict(sym=sym, part=part, signal=s, h=h, n=len(x), days=len(daily), ic=ic,
                                    ic_daily=daily.mean(), t=t))
    O = pd.DataFrame(out)
    d = O[O.part == "design"].copy()
    d["p"] = 2 * norm.sf(d.t.abs())
    d["q"] = bh(d.p.values)
    O = O.merge(d[["sym", "signal", "h", "p", "q"]].rename(columns={"p": "design_p", "q": "design_q"}),
                on=["sym", "signal", "h"], how="left")
    # partial check: IC of the signal after removing the past-60-min return (rank-regression residual)
    O["note"] = ""
    O.to_csv(RES / f"q3_signals_{'_'.join(syms)}.csv", index=False)
    print(O.round(4).to_string())


# ------------------------------------------------------------------ Q4
def trades_for(D, hn, ex):
    """one position at a time: walk entries in time order; next entry after the previous exit."""
    D = D.sort_values("ts")
    taken, free_at = [], None
    for i, (ts, tod) in enumerate(zip(D.ts.values, D.tod.values)):
        if free_at is not None and ts < free_at:
            continue
        taken.append(i)
        dur = HMIN[hn] if hn != "eod" else (23 * 60 + 25 - tod)
        free_at = ts + np.timedelta64(int(dur), "m")
    return D.iloc[taken]


def run_rule(E, cell, hn, ex, rng=None, nrand=0):
    sym, cm, db, tb = cell
    D = E[(E.cell_m == cm) & (E.db == db) & (E.tb == tb) & E[f"{hn}_P"].notna()]
    T = trades_for(D, hn, ex)
    g, n, m = add_pnl(T, hn, exit_col="P" if ex == "E1" else "P2")
    T = T.assign(gross=g, net=n, mult=m)
    T = T[np.isfinite(T.mult)]
    pr = np.nan
    if nrand and len(T):
        pool = E[(E.k == T.k.iloc[0]) & (E.cp == T.cp.iloc[0]) & (E.dte > 0) & E[f"{hn}_P"].notna()]
        pg, pn, pm = add_pnl(pool, hn, exit_col="P" if ex == "E1" else "P2")
        pool = pool.assign(net=pn)[np.isfinite(pm)]
        byM = {mo: x.net.values for mo, x in pool.groupby("month")}
        cnt = T.groupby("month").size()
        sims = np.zeros(nrand)
        for mo, c in cnt.items():
            v = byM.get(mo)
            if v is None or not len(v):
                continue
            sims += rng.choice(v, size=(nrand, c)).sum(1)
        pr = (sims >= T.net.sum()).mean()
    return T, pr


def summarize(T, label):
    if not len(T):
        return dict(rule=label, trades=0)
    days = T.day.nunique()
    eq = T.net.cumsum()
    dd = (eq.cummax() - eq).max()
    mo = T.groupby("month").net.sum()
    return dict(rule=label, trades=len(T), hit=(T.net > 0).mean(), gross_tr=T.gross.mean(), net_tr=T.net.mean(),
                gross_tot=T.gross.sum(), net_tot=T.net.sum(), maxdd=dd, worst_day=T.groupby("day").net.sum().min(),
                green_months=f"{(mo > 0).sum()}/{len(mo)}", mini_share=(T.mult < MULT[T.sym.iloc[0]]).mean())


def q4(syms, hold=False):
    rng = np.random.default_rng(11)
    C = pd.read_csv(RES / f"q1_cells_{'_'.join(syms)}.csv")
    Dg = C[(C.part == "design") & C.eligible.astype(bool)]
    passing = Dg[Dg["pass"].astype(bool)]
    if len(passing):
        cand = passing
        label = "passing cell"
    else:
        cand = Dg.sort_values("net_rs", ascending=False).head(1)
        label = "least dear, not cheap"
    print("candidates:", label, len(cand))
    Es = {s: load(s) for s in syms}
    res = []
    if not hold:
        for _, c in cand.iterrows():
            E = Es[c.sym]
            for ex in ("E1", "E2"):
                T, p = run_rule(E[~E.hold], (c.sym, c.cell_m, c.db, c.tb), c.H, ex, rng, 2000)
                r = summarize(T, f"{label}: {c.sym} {c.cell_m} dte{c.db} {c.tb} {c.H} {ex}")
                r["random_p"] = p
                res.append(r)
        # walk-forward by month over all eligible cells x exits (amendment 1)
        wf = []
        allE = pd.concat([Es[s][~Es[s].hold] for s in syms])
        months = sorted(allE.month.unique())
        cellsall = C[(C.part == "design")][["sym", "cell_m", "db", "tb", "H"]].drop_duplicates()
        # per cell x exit monthly trade nets (single pass)
        rows = []
        for _, c in cellsall.iterrows():
            E = Es[c.sym]
            for ex in ("E1", "E2"):
                T, _ = run_rule(E[~E.hold], (c.sym, c.cell_m, c.db, c.tb), c.H, ex)
                if not len(T):
                    continue
                for mo, x in T.groupby("month"):
                    rows.append(dict(sym=c.sym, cell_m=c.cell_m, db=c.db, tb=c.tb, H=c.H, ex=ex, month=mo, n=len(x),
                                     days=x.day.nunique(), net=x.net.sum(), gross=x.gross.sum()))
        R = pd.DataFrame(rows)
        R.to_csv(RES / f"q4_cellmonths_{'_'.join(syms)}.csv", index=False)
        key = ["sym", "cell_m", "db", "tb", "H", "ex"]
        for i, mo in enumerate(months):
            prior = R[R.month < mo]
            if prior.month.nunique() < 2:
                continue
            agg = prior.groupby(key).agg(n=("n", "sum"), days=("days", "sum"), net=("net", "sum"))
            agg = agg[(agg.n >= 100) & (agg.days >= 20)]
            if not len(agg):
                continue
            agg["per"] = agg.net / agg.n
            best = agg.per.idxmax()
            cur = R[(R.month == mo)].set_index(key)
            got = cur.loc[[best]] if best in cur.index else None
            wf.append(dict(month=str(mo), pick=" ".join(map(str, best)), prior_per_trade=agg.per.max(),
                           trades=int(got.n.sum()) if got is not None else 0,
                           gross=float(got.gross.sum()) if got is not None else 0.0,
                           net=float(got.net.sum()) if got is not None else 0.0))
        WF = pd.DataFrame(wf)
        WF.to_csv(RES / f"q4_walkforward_{'_'.join(syms)}.csv", index=False)
        print(WF.to_string())
        print("WF total net", WF.net.sum(), "gross", WF.gross.sum())
        # count
        print("eligible cells x exits:", len(R.groupby(key)))
        # pick for holdout: best design candidate by net per trade
        O = pd.DataFrame(res)
        O.to_csv(RES / f"q4_design_{'_'.join(syms)}.csv", index=False)
        print(O.to_string())
        best = O.sort_values("net_tr", ascending=False).iloc[0]
        json.dump({"rule": best.rule}, open(RES / f"q4_frozen_{'_'.join(syms)}.json", "w"))
    else:
        fz = json.load(open(RES / f"q4_frozen_{'_'.join(syms)}.json"))
        parts = fz["rule"].split(": ")[1].split()
        sym = parts[0]
        cm = parts[1] + " " + parts[2]
        db, tb, H, ex = parts[3][3:], parts[4], parts[5], parts[6]
        E = Es[sym]
        out = []
        for sm in (1.0, 2.0):
            T, p = run_rule(E[E.hold], (sym, cm, db, tb), H, ex, rng, 2000)
            if sm == 2.0:
                g, n, mlt = add_pnl(T, H, exit_col="P" if ex == "E1" else "P2", spread_mult=2.0)
                T = T.assign(gross=g, net=n)
            r = summarize(T, fz["rule"] + f" spread x{sm:g}")
            r["random_p"] = p
            out.append(r)
            if sm == 1.0:
                T[["ts", "K", "cp", "P0", f"{H}_P", "mult", "gross", "net"]].to_csv(RES / "q4_holdout_trades.csv",
                                                                                     index=False)
                print(T.groupby("month").agg(trades=("net", "size"), gross=("gross", "sum"), net=("net", "sum")))
        O = pd.DataFrame(out)
        O.to_csv(RES / "q4_holdout.csv", index=False)
        print(O.to_string())


if __name__ == "__main__":
    cmd, syms = sys.argv[1], sys.argv[2:]
    sys.path.insert(0, str(Path(__file__).parent))
    if cmd not in ("q2next", "q3trade"):
        {"q1": q1, "q2": q2, "q3": q3, "q4design": q4, "q4hold": lambda s: q4(s, hold=True)}[cmd](syms)


def q2next(_syms):
    """crude only: near vs next-month ATM for a directional buyer (evening grid, design)."""
    from build import b76, b76_delta, implied_vol
    C = SP / "strad_crude" / "cache"
    E = load("CRUDEOIL")
    D = E[(~E.hold) & (E.tod >= 17 * 60 + 30) & (E.dte >= 1) & (E.k == 0)].copy()
    sig = E[(E.k == 0) & (E.cp == 0)].groupby("month").mn_fwd60.std()
    out = []
    for cp, side in ((0, "CALL"), (1, "PUT")):
        x = pd.read_parquet(C / f"c2_ATM_{side}.parquet").drop_duplicates("ts").set_index("ts")
        d = D[D.cp == cp].copy()
        t0 = pd.DatetimeIndex(d.ts)
        p0 = x.close.reindex(t0).values
        p1 = x.close.reindex(t0 + pd.Timedelta(minutes=60)).values
        f0 = x.spot.reindex(t0).values
        k0 = x.strike.reindex(t0).values
        k1 = x.strike.reindex(t0 + pd.Timedelta(minutes=60)).values
        ok = np.isfinite(p0) & np.isfinite(p1) & (k0 == k1) & (p0 > 0)  # same strike still in the ATM series
        tn = d.tau0.values + 30 / 365
        iv = implied_vol(p0, f0, k0, tn, np.full(len(d), cp == 0))
        dl = np.abs(b76_delta(f0, k0, tn, np.nan_to_num(iv, nan=0.3), cp == 0))
        mv = (np.where(cp == 0, d.mn_fwd60, -d.mn_fwd60) / d.month.map(sig)).values
        r = p1 / p0 - 1
        rt = (fees(p0 * 100, p0 * 100) + rel_spread("CRUDEOIL", np.zeros(len(d)), d.tod.values) * 1.5 * p0 * 100) / (p0 * 100)
        for nm, pr, rr, dd, rtc, okk in (("near", d.P0.values, d.h60_P.values / d.P0.values - 1,
                                          np.abs(b76_delta(d.F0.values, d.K.values, d.tau0.values,
                                                           np.nan_to_num(d.iv0.values, nan=0.3), cp == 0)),
                                          (fees(d.P0 * 100, d.P0 * 100) + rel_spread("CRUDEOIL", np.zeros(len(d)), d.tod.values) * d.P0 * 100).values / (d.P0.values * 100),
                                          ok),
                                         ("next", p0, r, dl, rt, ok)):
            o = okk & np.isfinite(rr)
            out.append(dict(expiry=nm, side=side.lower(), n=int(o.sum()), prem_rs=np.median(pr[o]) * 100,
                            delta=np.median(dd[o]), elast=np.median(dd[o] * d.F0.values[o] / pr[o]),
                            fav_ret60=np.median(rr[o & (mv >= 1)]), flat_ret60=np.median(rr[o & (np.abs(mv) < 0.25)]),
                            rt_cost_pct=np.median(rtc[o]), next_spread_assumed="1.5x near"))
    O = pd.DataFrame(out)
    O.to_csv(RES / "q2_next_month_crude.csv", index=False)
    print(O.round(4).to_string())


if __name__ == "__main__" and sys.argv[1] == "q2next":
    q2next(None)


def sig_frame(sym):
    G = pd.read_parquet(W / f"sig_{sym}.parquet")
    tot = G.oip + G.oic
    G["S1_rr"] = G.rr
    G["S2_drr"] = G.rr - G.rr_l15
    G["S4_dbasis"] = G.basis - G.basis_l5
    G["S6_dpcr"] = ((G.oip - G.oic) - (G.oip_l60 - G.oic_l60)) / tot.replace(0, np.nan)
    G["S7_oibuild"] = ((tot - (G.oip_l60 + G.oic_l60)) / tot.replace(0, np.nan)) * np.sign(G.back60)
    G["R_back60"] = G.back60
    G["hold"] = G.day >= HOLD0
    return G[G.dte > 0]


def q3trade(sym, signal, icsign):
    rng = np.random.default_rng(5)
    G = sig_frame(sym)
    E = load(sym)
    A = E[(E.k == 0) & (E.dte > 0) & E.h60_P.notna()]
    des = G[~G.hold][signal].dropna()
    lo, hi = des.quantile(0.2), des.quantile(0.8)
    G = G[G[signal].notna()]
    G["dir"] = np.where(G[signal] >= hi, 1, np.where(G[signal] <= lo, -1, 0)) * icsign
    G = G[G.dir != 0]
    G["cp"] = np.where(G.dir > 0, 0, 1)
    X = G[["ts", "cp", "hold"]].merge(A.drop(columns=["hold"]), on=["ts", "cp"])
    out = []
    for part, D in (("design", X[~X.hold]), ("holdout", X[X.hold])):
        T = trades_for(D, "h60", "E1")
        g, n, m = add_pnl(T, "h60")
        T = T.assign(gross=g, net=n, mult=m)
        T = T[np.isfinite(T.mult)]
        pool = A[A.hold == (part == "holdout")]
        pg, pn, pm = add_pnl(pool, "h60")
        pool = pool.assign(net=pn)[np.isfinite(pm)]
        sims = np.zeros(2000)
        for (mo, cp), c in T.groupby(["month", "cp"]).size().items():
            v = pool[(pool.month == mo) & (pool.cp == cp)].net.values
            if len(v):
                sims += rng.choice(v, size=(2000, c)).sum(1)
        r = summarize(T, f"{sym} {signal} q20/q80 dir{icsign:+d} ATM 60m {part}")
        r["random_p"] = (sims >= T.net.sum()).mean()
        r["days"] = T.day.nunique()
        out.append(r)
    O = pd.DataFrame(out)
    print(O.to_string())
    return O


if __name__ == "__main__" and sys.argv[1] == "q3trade":
    res = []
    for spec in sys.argv[2:]:
        s, sig, sg = spec.split(":")
        res.append(q3trade(s, sig, int(sg)))
    pd.concat(res).to_csv(RES / "q3_trades.csv", index=False)
