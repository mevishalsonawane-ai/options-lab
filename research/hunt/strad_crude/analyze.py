"""STRAD-CRUDE: forecasts (walk-forward), signals, straddle/strangle P&L, stats.
  python -I analyze.py predict   # anchored monthly refit of HAR + MLP, OOS signals Oct 2025 -> Oct 2026 (no P&L)
  python -I analyze.py design    # all variants on design test months (Oct 2025 - Mar 2026), WF selection, BH, gap
  python -I analyze.py holdout   # frozen rules once on 1 Apr - 8 Oct 2026 (partly seen)
See PREREG.md."""
import sys
sys.path.append("/root/.local/lib/python3.11/site-packages")
import json
import warnings
from pathlib import Path
import numpy as np
import pandas as pd
from scipy import stats

warnings.filterwarnings("ignore")
S = Path("/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/strad_crude")
W = S / "work"
OUT = Path("/home/user/options-lab/research/hunt/strad_crude/results")
OUT.mkdir(exist_ok=True)
HS = (15, 30, 60, 240)
QS = (0.7, 0.8, 0.9)
EXITS = {"T": (None, None), "T+PT20": (0.20, None), "T+PT40": (0.40, None), "T+SL25": (None, 0.25),
         "T+SL40": (None, 0.40), "T+PT40+SL40": (0.40, 0.40)}
STRUCT = {"straddle": (0, 1), "strangle": (2, 3)}
DESIGN_END = pd.Timestamp("2026-04-01")
TEST0 = pd.Timestamp("2025-10-01")
LOT = 100
RNG = np.random.default_rng(7)


# ------------------------------------------------------------------ costs
def spread_model():
    d = pd.read_csv(S / "chain_snaps.csv")
    d = d[(d.expiry == d.expiry.min()) & (d.bid > 0) & (d.ask > 0)].copy()
    d["mid"] = (d.bid + d.ask) / 2
    d["rel"] = (d.ask - d.bid) / d.mid
    piv = d.pivot_table(index=["ts", "strike"], columns="side", values="mid").reset_index()
    piv["g"] = (piv.ce - piv.pe).abs()
    atm = piv.loc[piv.groupby("ts").g.idxmin(), ["ts", "strike"]].rename(columns={"strike": "atm"})
    d = d.merge(atm, on="ts")
    d["otm"] = np.where(d.side == "ce", 1, -1) * ((d.strike - d.atm) / 50).round()
    rel = d.groupby("otm").rel.median()
    out = {"atm": float(rel.loc[0]), "otm1": float(rel.loc[1:1].mean() if 1 in rel.index else rel.loc[0]),
           "n_snaps": int(d.ts.nunique()), "first": d.ts.min(), "last": d.ts.max(),
           "atm_abs_median_rs": float(d[d.otm == 0].eval("ask-bid").median()),
           "by_otm": {int(k): round(float(v), 5) for k, v in rel.items()}}
    return out


def fees(buy_rs, sell_rs, n_orders=4):
    brk = 20.0 * n_orders
    exch = 0.000418 * (buy_rs + sell_rs)
    sebi = 10e-7 * (buy_rs + sell_rs)
    gst = 0.18 * (brk + exch + sebi)
    return brk + exch + sebi + gst + 0.0005 * sell_rs + 0.00003 * buy_rs


# ------------------------------------------------------------------ data
def load():
    E = pd.read_parquet(W / "entries.parquet")
    P = np.load(W / "paths.npy", mmap_mode="r")
    E["idx"] = np.arange(len(E))
    E["month"] = E.day.dt.to_period("M")
    return E, P


FEATS = ["l_rv15", "l_rv60", "l_rvday", "l_rvyd", "l_rv5d", "seas", "eia_in", "cpi_in", "fomc_next", "opec_mon", "dte_s"]


def design_matrix(E, h, seas):
    X = pd.DataFrame(index=E.index)
    for c in ("rv15", "rv60", "rvday", "rvyd", "rv5d"):
        X["l_" + c] = np.log(E[c].clip(lower=1e-6)).fillna(np.log(E["rv60"].clip(lower=1e-6)))
    X["seas"] = E.tod.map(seas).fillna(seas.mean())
    X["eia_in"] = E[f"eia_in{h}"].astype(float)
    X["cpi_in"] = E[f"cpi_in{h}"].astype(float)
    X["fomc_next"] = E.fomc_next.astype(float)
    X["opec_mon"] = E.opec_mon.astype(float)
    X["dte_s"] = E.dte.clip(0, 30) / 30
    X = X.replace([np.inf, -np.inf], np.nan).fillna(np.log(7e-4))  # typical per-minute rms when history is missing
    return X[FEATS].values


def predict():
    from sklearn.neural_network import MLPRegressor
    from sklearn.preprocessing import StandardScaler
    E, _ = load()
    E = E[~E.is_expday].copy()
    months = sorted(E.month.unique())
    res = []
    for m in months:
        if m.to_timestamp() < TEST0:
            continue
        mstart = m.to_timestamp()
        tr_days = sorted(E.loc[E.day < mstart, "day"].unique())
        tr = E[E.day < tr_days[-1]]  # purge: drop the last trading day before month m
        te = E[E.month == m]
        for h in HS:
            y_col = f"rvf{h}"
            trh = tr[tr[y_col].notna()]
            y = np.log(trh[y_col].clip(lower=1e-6) ** 2)
            seas = y.groupby(trh.tod).mean()
            Xtr, Xte = design_matrix(trh, h, seas), design_matrix(te, h, seas)
            Xall_tr = design_matrix(tr, h, seas)
            # HAR (OLS)
            A = np.c_[np.ones(len(Xtr)), Xtr]
            beta, *_ = np.linalg.lstsq(A, y.values, rcond=None)
            smear = np.mean(np.exp(y.values - A @ beta))
            har_tr = np.exp(np.c_[np.ones(len(Xall_tr)), Xall_tr] @ beta) * smear
            har_te = np.exp(np.c_[np.ones(len(Xte)), Xte] @ beta) * smear
            # MLP
            sc = StandardScaler().fit(Xtr)
            mlp = MLPRegressor(hidden_layer_sizes=(16, 8), alpha=1e-3, early_stopping=True, random_state=0,
                               max_iter=500).fit(sc.transform(Xtr), y.values)
            smm = np.mean(np.exp(y.values - mlp.predict(sc.transform(Xtr))))
            mlp_tr = np.exp(mlp.predict(sc.transform(Xall_tr))) * smm
            mlp_te = np.exp(mlp.predict(sc.transform(Xte))) * smm
            for name, ptr, pte in (("har", har_tr, har_te), ("mlp", mlp_tr, mlp_te)):
                fm_tr = tr.F.values * np.sqrt(h * ptr) * np.sqrt(2 / np.pi)
                fm_te = te.F.values * np.sqrt(h * pte) * np.sqrt(2 / np.pi)
                im_tr = implied_move(tr, h)
                im_te = implied_move(te, h)
                R_tr, R_te = fm_tr / im_tr, fm_te / im_te
                # thresholds from training (in-sample) predictions
                slot_q = {q: pd.Series(fm_tr).groupby(tr.tod.values).quantile(q) for q in QS}
                r_q = {q: float(np.nanquantile(R_tr, q)) for q in QS}
                out = pd.DataFrame({"idx": te.idx.values, "h": h, "model": name, "FM": fm_te, "IM": im_te, "R": R_te})
                for q in QS:
                    out[f"B{q}"] = fm_te >= te.tod.map(slot_q[q]).values
                    out[f"C{q}"] = R_te >= r_q[q]
                res.append(out)
                # in-sample corr check (descriptive)
            print(m, h, "OLS beta", np.round(beta, 2), flush=True)
    R = pd.concat(res, ignore_index=True)
    R.to_parquet(W / "signals.parquet")
    # OOS forecast quality (design months only): corr(log forecast var, log realised var)
    E2 = E.set_index("idx")
    q = []
    for (h, mdl), g in R.groupby(["h", "model"]):
        e = E2.loc[g.idx]
        des = (e.day < DESIGN_END).values
        y = np.log(e[f"rvf{h}"].values ** 2 + 1e-12)
        x = np.log((g.FM.values / e.F.values) ** 2)
        ok = des & np.isfinite(y)
        q.append(dict(h=h, model=mdl, corr_design=np.corrcoef(x[ok], y[ok])[0, 1]))
    pd.DataFrame(q).to_csv(OUT / "forecast_quality_design.csv", index=False)
    print(pd.DataFrame(q))


def implied_move(E, h):
    return E.F.values * E.iv.values * np.sqrt(E.tau.clip(lower=1e-6).values * h / E.N_rem.clip(lower=h).values) \
        * np.sqrt(2 / np.pi)


# ------------------------------------------------------------------ P&L per entry for each (h, struct, exit)
def outcomes(E, P, sp, spread_mult=1.0):
    """dict[(h, struct, exit)] -> DataFrame(idx, hold, gross, net) for every entry with full window."""
    out = {}
    rel_leg = np.array([sp["atm"], sp["atm"], sp["otm1"], sp["otm1"]])
    morning = (E.tod.values < 17 * 60)
    mult = spread_mult * np.where(morning, 2.0, 1.0)
    idx = E.idx.values
    Pe = np.asarray(P[idx])  # n x 4 x 241
    for s, (a, b) in STRUCT.items():
        legs = Pe[:, [a, b], :].astype(float)
        comb = legs.sum(1)
        P0 = comb[:, 0]
        for h in HS:
            ok = E.n_ok.values >= h
            win = comb[:, 1:h + 1]
            for ex, (pt, sl) in EXITS.items():
                hit = np.zeros_like(win, dtype=bool)
                if pt is not None:
                    hit |= win >= (P0 * (1 + pt))[:, None]
                if sl is not None:
                    hit |= win <= (P0 * (1 - sl))[:, None]
                first = np.where(hit.any(1), hit.argmax(1) + 1, h)
                lx = legs[np.arange(len(legs)), :, first]  # n x 2 leg prices at exit
                l0 = legs[:, :, 0]
                hs0 = np.maximum(0.10, rel_leg[[a, b]] * l0) / 2 * mult[:, None]
                hs1 = np.maximum(0.10, rel_leg[[a, b]] * lx) / 2 * mult[:, None]
                buy = (l0 + hs0).sum(1) * LOT
                sell = np.maximum(lx - hs1, 0.05).sum(1) * LOT
                gross = (lx.sum(1) - P0) * LOT
                net = sell - buy - fees(buy, sell)
                df = pd.DataFrame({"idx": idx, "hold": first, "gross": gross, "net": net, "prem": P0 * LOT})
                fits = buy + fees(buy, 0 * buy) <= 100000.0
                out[(h, s, ex)] = df[ok & fits & np.isfinite(gross) & np.isfinite(net)].set_index("idx")
    return out


def run_rule(fired_idx, oc, E):
    """Non-overlapping: walk fired entries in time order; enter if flat."""
    o = oc.loc[oc.index.intersection(fired_idx)].sort_index()
    if o.empty:
        return o
    ts = E.loc[o.index, "ts"].values
    exit_ts = ts + (o.hold.values * 60 * 1e9).astype("timedelta64[ns]")
    keep, free = [], np.datetime64("1970-01-01")
    for i in range(len(o)):
        if ts[i] >= free:
            keep.append(i)
            free = exit_ts[i]
    return o.iloc[keep]


def summarize(t, E, ndays, oc_pool=None, nrand=2000):
    if t is None or len(t) == 0:
        return dict(trades=0)
    e = E.loc[t.index]
    cum = t.net.cumsum().values
    dd = float(np.max(np.maximum.accumulate(np.r_[0, cum]) - np.r_[0, cum]))
    mon = t.net.groupby(e.month.values).sum()
    res = dict(trades=len(t), hit=float((t.net > 0).mean()), gross_tr=float(t.gross.mean()),
               net_tr=float(t.net.mean()), gross_day=float(t.gross.sum() / ndays), net_day=float(t.net.sum() / ndays),
               maxdd=dd, green_m=float((mon > 0).mean()), months=len(mon), prem_avg=float(t.prem.mean()),
               p_t=float(stats.ttest_1samp(t.net, 0, alternative="greater").pvalue) if len(t) > 2 else 1.0)
    if oc_pool is not None and len(oc_pool) > len(t):
        pool = oc_pool.net.values
        draws = RNG.choice(pool, size=(nrand, len(t)), replace=True).mean(1)
        res["p_rand"] = float((draws >= t.net.mean()).mean())
        res["rand_net_tr"] = float(pool.mean())
    return res


def bh(p):
    p = np.asarray(p, float)
    n = len(p)
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1)
    return out


def variants():
    v = []
    for h in HS:
        for s in STRUCT:
            for ex in EXITS:
                v.append(dict(h=h, s=s, ex=ex, fam="A", model="-", q1=None, q2=None))
                for mdl in ("har", "mlp"):
                    for q in QS:
                        v.append(dict(h=h, s=s, ex=ex, fam="B", model=mdl, q1=q, q2=None))
                        v.append(dict(h=h, s=s, ex=ex, fam="C", model=mdl, q1=None, q2=q))
                    for q1 in QS:
                        for q2 in QS:
                            v.append(dict(h=h, s=s, ex=ex, fam="D", model=mdl, q1=q1, q2=q2))
    return v


def fired(v, sig, Esub):
    if v["fam"] == "A":
        return Esub.idx.values
    g = sig[(sig.h == v["h"]) & (sig.model == v["model"])]
    g = g[g.idx.isin(Esub.idx)]
    m = np.ones(len(g), bool)
    if v["q1"] is not None:
        m &= g[f"B{v['q1']}"].values
    if v["q2"] is not None:
        m &= g[f"C{v['q2']}"].values
    return g.idx.values[m]


def vname(v):
    q = "" if v["fam"] == "A" else f" {v['model']} q{v['q1'] or '-'}/{v['q2'] or '-'}"
    return f"{v['fam']}{q} h{v['h']} {v['s']} {v['ex']}"


def eia_variants():
    return [dict(fam="EIA", s=s, N=n) for s in STRUCT for n in (5, 15, 30, 60)]


def eia_trades(E, oc_all, v, eia_set):
    """entries 15 min before the release, time exit at release + N (hold 15+N); uses the T exit with h>=hold."""
    hold = 15 + v["N"]
    h = 15 if hold <= 15 else (30 if hold <= 30 else (60 if hold <= 60 else 240))
    rows = E[E.ts.isin(eia_set)]
    return rows, hold


def eia_pnl(E, P, sp, rows, s, hold, spread_mult=1.0):
    a, b = STRUCT[s]
    rel_leg = np.array([sp["atm"], sp["atm"], sp["otm1"], sp["otm1"]])
    Pe = np.asarray(P[rows.idx.values])[:, [a, b], :].astype(float)
    ok = rows.n_ok.values >= hold
    l0, lx = Pe[:, :, 0], Pe[:, :, hold]
    hs0 = np.maximum(0.10, rel_leg[[a, b]] * l0) / 2 * spread_mult
    hs1 = np.maximum(0.10, rel_leg[[a, b]] * lx) / 2 * spread_mult
    buy = (l0 + hs0).sum(1) * LOT
    sell = np.maximum(lx - hs1, 0.05).sum(1) * LOT
    ok &= (buy + fees(buy, 0 * buy)) <= 100000.0
    t = pd.DataFrame({"idx": rows.idx.values, "hold": hold, "gross": (lx.sum(1) - l0.sum(1)) * LOT,
                      "net": sell - buy - fees(buy, sell), "prem": l0.sum(1) * LOT}).set_index("idx")
    return t[ok & np.isfinite(t.net.values)]


# ------------------------------------------------------------------ design
def evaluate_all(E, oc, sig, Esub, ndays, with_rand=True):
    rows = []
    for v in variants():
        f = fired(v, sig, Esub)
        o = oc[(v["h"], v["s"], v["ex"])]
        t = run_rule(f, o, E)
        pool = o.loc[o.index.intersection(Esub.idx)] if with_rand else None
        r = summarize(t, E, ndays, pool, nrand=1000 if with_rand else 0)
        rows.append(dict(name=vname(v), **v, **r))
    return pd.DataFrame(rows)


def design():
    E, P = load()
    E = E[~E.is_expday].copy()
    E.index = E.idx
    sp = spread_model()
    json.dump(sp, open(OUT / "spread_model.json", "w"), indent=1, default=str)
    print("spread", sp)
    sig = pd.read_parquet(W / "signals.parquet")
    oc1 = outcomes(E, P, sp, 1.0)
    D = E[(E.day >= TEST0) & (E.day < DESIGN_END)]
    nd = D.day.nunique()
    resume = "--resume" in sys.argv and (OUT / "design_walkforward.csv").exists()
    if resume:
        tab = pd.read_csv(OUT / "design_variants.csv")
        tab["q1"] = tab.q1.astype(object).where(tab.q1.notna(), None)
        tab["q2"] = tab.q2.astype(object).where(tab.q2.notna(), None)
    else:
        tab = evaluate_all(E, oc1, sig, D, nd)
        tab["bh_q"] = bh(tab.p_t.fillna(1).values)
        tab.to_csv(OUT / "design_variants.csv", index=False)
    # stress 2x spread for the top variants
    oc2 = outcomes(E, P, sp, 2.0)
    # ---- walk-forward month-by-month selection (choose on in-sample training months, trade test month)
    # in-sample signals for training months are not stored; selection uses the OOS signals of earlier test months
    # plus family A on all earlier months. For Oct 2025 (no earlier OOS months) only family A can be chosen.
    wf = []
    months = sorted(D.month.unique()) if not resume else []
    for i, m in enumerate(months):
        trm = months[:i]
        if trm:
            Dt = D[D.month.isin(trm)]
            tt = evaluate_all(E, oc1, sig, Dt, Dt.day.nunique(), with_rand=False)
        else:
            Dt = E[(E.day < TEST0)]
            tt = pd.DataFrame([dict(name=vname(v), **v, **summarize(run_rule(fired(v, sig, Dt), oc1[(v['h'], v['s'], v['ex'])], E), E, Dt.day.nunique(), None))
                               for v in variants() if v["fam"] == "A"])
        tt = tt[tt.trades >= 30].sort_values("net_day", ascending=False)
        best = tt.iloc[0].to_dict()
        v = {k: best[k] for k in ("h", "s", "ex", "fam", "model", "q1", "q2")}
        v = {k: (None if (isinstance(x, float) and np.isnan(x)) else x) for k, x in v.items()}
        Dm = D[D.month == m]
        t = run_rule(fired(v, sig, Dm), oc1[(v["h"], v["s"], v["ex"])], E)
        wf.append(dict(month=str(m), chosen=vname(v), train_net_day=best["net_day"], trades=len(t),
                       gross=float(t.gross.sum()), net=float(t.net.sum()), days=Dm.day.nunique()))
        print("WF", wf[-1], flush=True)
    if not resume:
        wf = pd.DataFrame(wf)
        wf.to_csv(OUT / "design_walkforward.csv", index=False)
    # ---- EIA family (design)
    eia = pd.to_datetime(pd.read_csv(W / "eia_ts.csv").iloc[:, 0])
    eia_set = list(eia.dt.tz_convert("Asia/Kolkata") - pd.Timedelta(minutes=15))
    erows = []
    Ed = E[(E.day >= TEST0) & (E.day < DESIGN_END)]
    for v in eia_variants():
        rows, hold = eia_trades(Ed, None, v, eia_set)
        t = eia_pnl(E, P, sp, rows, v["s"], hold)
        # baseline: same clock, non-EIA days
        clock = set(rows.tod.unique())
        base = Ed[Ed.tod.isin(clock) & ~Ed.ts.isin(eia_set)]
        tb = eia_pnl(E, P, sp, base, v["s"], hold)
        r = summarize(t, E, Ed.day.nunique(), tb)
        erows.append(dict(name=f"EIA {v['s']} exit release+{v['N']}", **v, **r, base_net_tr=float(tb.net.mean())))
    et = pd.DataFrame(erows)
    et.to_csv(OUT / "design_eia.csv", index=False)
    allp = np.r_[tab.p_t.fillna(1).values, et.p_t.fillna(1).values]
    q_all = bh(allp)
    tab["bh_q_all"] = q_all[:len(tab)]
    et["bh_q_all"] = q_all[len(tab):]
    tab.to_csv(OUT / "design_variants.csv", index=False)
    et.to_csv(OUT / "design_eia.csv", index=False)
    # ---- choose frozen rules for holdout
    ok = tab[tab.trades >= 30]
    final = ok.sort_values("net_day", ascending=False).iloc[0]
    fam_best = ok.sort_values("net_day", ascending=False).groupby("fam").head(1)
    eia_best = et[et.trades >= 10].sort_values("net_day", ascending=False).head(1)
    chosen = {"final": final[["h", "s", "ex", "fam", "model", "q1", "q2"]].to_dict(),
              "family_best": fam_best[["h", "s", "ex", "fam", "model", "q1", "q2"]].to_dict("records"),
              "eia_best": eia_best[["s", "N"]].to_dict("records"),
              "comparison_same_h_s_ex": True}
    json.dump(chosen, open(OUT / "frozen_rules.json", "w"), indent=1, default=str)
    # stress numbers for family bests
    st = []
    for rec in chosen["family_best"]:
        v = {k: (None if (isinstance(x, float) and np.isnan(x)) else x) for k, x in rec.items()}
        t = run_rule(fired(v, sig, D), oc2[(v["h"], v["s"], v["ex"])], E)
        st.append(dict(name=vname(v), **summarize(t, E, nd)))
    pd.DataFrame(st).to_csv(OUT / "design_stress2x.csv", index=False)
    gap(E[E.day < DESIGN_END], sig, "design")
    events(E, oc1, D, "design")
    print("done design")


def gap(E, sig, label="design"):
    """Implied vs realised (descriptive). Uses all months incl. holdout, labelled by period."""
    rows = []
    E = E.copy()
    E["period"] = np.where(E.day < DESIGN_END, "design", "holdout")
    E["slot"] = pd.cut(E.tod, [0, 14 * 60, 17 * 60 + 30, 24 * 60], labels=["09:15-14:00", "14:00-17:30", "17:30-23:25"])
    for h in HS:
        im = implied_move(E, h)
        mv = E[f"mv{h}"].values * E.F.values
        d = pd.DataFrame({"period": E.period, "slot": E.slot, "im": im, "mv": mv}).dropna()
        for (p, s), g in d.groupby(["period", "slot"], observed=True):
            rows.append(dict(h=h, period=p, slot=s, realised_over_implied=g.mv.mean() / g.im.mean(), n=len(g)))
        for p, g in d.groupby("period"):
            rows.append(dict(h=h, period=p, slot="all", realised_over_implied=g.mv.mean() / g.im.mean(), n=len(g)))
    pd.DataFrame(rows).to_csv(OUT / f"gap_intraday_{label}.csv", index=False)
    # to expiry: 15:00 each day, ATM straddle vs |F_expiry - K|
    M = pd.read_parquet(W / "minutes.parquet", columns=["F", "day", "expiry"])
    fexp = M[M.day == M.expiry].groupby("expiry").F.last()
    e = E[E.tod == 15 * 60].copy()
    e["Fexp"] = e.day.map(M.groupby("day").expiry.first()).map(fexp)
    e = e.dropna(subset=["Fexp"])
    e["payoff"] = (e.Fexp - e.K).abs()
    e["ratio"] = e.payoff / e.strad
    g = e.groupby("period").agg(n=("ratio", "size"), prem=("strad", "mean"), payoff=("payoff", "mean"),
                                payoff_over_prem=("ratio", "mean"))
    g["mean_payoff_over_mean_prem"] = g.payoff / g.prem
    g.to_csv(OUT / f"gap_to_expiry_{label}.csv")
    print(g)


def events(E, oc, Dsub, label):
    """Always-buy (T exit) on event days vs other days, by h and structure."""
    rows = []
    for h in HS:
        for s in STRUCT:
            o = oc[(h, s, "T")]
            o = o.loc[o.index.intersection(Dsub.idx)]
            e = E.loc[o.index]
            flags = {"EIA day": e.eia_day, "EIA in window": e[f"eia_in{h}"], "OPEC Monday": e.opec_mon,
                     "CPI day": e.cpi_day, "CPI in window": e[f"cpi_in{h}"], "FOMC day": e.fomc_day,
                     "FOMC next day": e.fomc_next}
            anyev = e.eia_day | e.opec_mon | e.cpi_day | e.fomc_day | e.fomc_next
            flags["no event"] = ~anyev
            for k, f in flags.items():
                f = f.values.astype(bool)
                if f.sum() == 0:
                    continue
                rows.append(dict(h=h, s=s, event=k, entries=int(f.sum()), days=int(e.day[f].nunique()),
                                 gross_tr=float(o.gross[f].mean()), net_tr=float(o.net[f].mean()),
                                 hit=float((o.net[f] > 0).mean()),
                                 realised_over_implied=float((e[f"mv{h}"][f] * e.F[f]).mean() /
                                                             implied_move(e[f], h).mean())))
    pd.DataFrame(rows).to_csv(OUT / f"events_{label}.csv", index=False)


# ------------------------------------------------------------------ holdout
def holdout():
    E, P = load()
    E = E[~E.is_expday].copy()
    E.index = E.idx
    sp = json.load(open(OUT / "spread_model.json"))
    sig = pd.read_parquet(W / "signals.parquet")
    ch = json.load(open(OUT / "frozen_rules.json"))
    H = E[E.day >= DESIGN_END]
    nd = H.day.nunique()
    rows = []
    for mult in (1.0, 2.0):
        oc = outcomes(E, P, sp, mult)
        recs = [("FINAL", ch["final"])] + [("family best " + r["fam"], r) for r in ch["family_best"]]
        for lab, rec in recs:
            v = {k: (None if (isinstance(x, float) and np.isnan(x)) or x in ("None", "nan") else x) for k, x in rec.items()}
            for k in ("h",):
                v[k] = int(float(v[k]))
            for k in ("q1", "q2"):
                v[k] = None if v[k] is None else float(v[k])
            t = run_rule(fired(v, sig, H), oc[(v["h"], v["s"], v["ex"])], E)
            pool = oc[(v["h"], v["s"], v["ex"])]
            pool = pool.loc[pool.index.intersection(H.idx)]
            r = summarize(t, E, nd, pool)
            rows.append(dict(rule=lab, name=vname(v), spread=f"{mult:.0f}x", **r))
            if mult == 1.0 and lab == "FINAL":
                t.join(E[["ts", "K", "F"]]).to_csv(OUT / "holdout_final_trades.csv")
                mon = t.net.groupby(E.loc[t.index, "month"].astype(str).values).agg(["size", "sum"])
                mon.to_csv(OUT / "holdout_final_months.csv")
        if mult == 1.0:
            # same (h, s, ex) as FINAL but always / random for comparison is in pool p; EIA best
            eia = pd.to_datetime(pd.read_csv(W / "eia_ts.csv").iloc[:, 0])
            eia_set = list(eia.dt.tz_convert("Asia/Kolkata") - pd.Timedelta(minutes=15))
            for r_ in ch["eia_best"]:
                v = dict(fam="EIA", s=r_["s"], N=int(r_["N"]))
                rws, hold = eia_trades(H, None, v, eia_set)
                t = eia_pnl(E, P, sp, rws, v["s"], hold)
                base = H[H.tod.isin(set(rws.tod.unique())) & ~H.ts.isin(eia_set)]
                tb = eia_pnl(E, P, sp, base, v["s"], hold)
                rows.append(dict(rule="EIA best", name=f"EIA {v['s']} exit release+{v['N']}", spread="1x",
                                 **summarize(t, E, nd, tb)))
            events(E, oc, H, "holdout")
    gap(E, sig, "all")
    pd.DataFrame(rows).to_csv(OUT / "holdout_results.csv", index=False)
    print(pd.DataFrame(rows).to_string())


if __name__ == "__main__":
    {"predict": predict, "design": design, "holdout": holdout}[sys.argv[1]]()
