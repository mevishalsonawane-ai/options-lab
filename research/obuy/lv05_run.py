"""Runner for the multi-day LV-05 test (strategies/lv05_multiday.py).

    python3 -I research/obuy/lv05_run.py run [--k 20] [--only N]   # parity + every variant + random pools + futures
    python3 -I research/obuy/lv05_run.py report                    # statistics, walk-forward, SPA/DSR/PBO, MC -> REPORT.md

Untrusted market data: always python -I. Outputs: <OBUY_CACHE>/runs/lv05_multiday/ (res_<UND>.pkl, REPORT.md,
summary.json, variants.csv). Heavy: hold the shared obuy lock (flock) while running 'run'.
"""
from __future__ import annotations

import argparse
import json
import os
import pickle
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

import obuy  # noqa: E402,F401  (puts the pandas deps on the path)
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from obuy import config as C  # noqa: E402
from obuy import overfit as OF  # noqa: E402
from obuy import stats as ST  # noqa: E402
from obuy.data import market  # noqa: E402
from obuy.engine import Execution, Exits, StrikeRule, positions, prepare_many  # noqa: E402
from obuy.strategies import lv05_multiday as LV  # noqa: E402
from obuy.strategies.ga_common import SQ  # noqa: E402

OUT = os.path.join(C.CACHE, "runs", "lv05_multiday")
UNDS = ("NIFTY", "BANKNIFTY")
TCOLS = ["und", "day", "i0", "side", "xday", "xi", "xcol", "sessions", "why", "n_legs", "strike", "ser", "lot", "qty",
         "entry", "gross", "charges", "net", "overnight", "intraday", "risk_rs", "model_days", "days_held", "fut_net",
         "fut_gross", "fut_overnight", "fut_intraday", "year"]


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


# ------------------------------------------------------------------------------------------------ parity (same day)
PARITY = [(dict(n=7, vix=True), StrikeRule(1, "month"), Exits(sq_off=SQ), None),
          (dict(n=20, vix=False), StrikeRule(0, "month"), Exits(stop_pct=0.3, sq_off=SQ), 0.3)]


def parity_engine(und):
    """The unchanged same-day engine on two GA LV-05 variants (next-day mode) for one underlying."""
    mk = market()
    jobs, sigs = [], []
    for sp, rule, ex, _ in PARITY:
        s = LV.lv05_signals(mk, mode="nextday", unds=(und,), **sp)
        sigs.append(s)
        jobs.append((s, rule, Execution(expiry="skip"), 0, None, False))
    packs = prepare_many(jobs)
    out = []
    for (pk, _), (sp, rule, ex, _) in zip(packs, PARITY):
        tr = pk.run(ex, Execution(expiry="skip"))
        out.append(positions(tr, one_at_a_time=True))
    return sigs, out


def parity_check(sim, sigs, eng):
    rows = []
    for (sp, rule, ex, ps), s, e in zip(PARITY, sigs, eng):
        v = LV.Var(sp["n"], sp["vix"], rule.money, "M0", ps)
        mine = pd.DataFrame(LV.run_variant(sim, s, v, mode="M0", skip_exp_entry=True, sameday=True))
        a = e[["day", "entry", "exit_min", "net"]].rename(columns={"net": "net_engine", "entry": "e_engine"})
        b = mine[["day", "entry", "xcol", "net"]] if len(mine) else pd.DataFrame(columns=["day", "entry", "xcol", "net"])
        m = a.merge(b, on="day", how="outer")
        both = m.dropna()
        rows.append(dict(und=sim.U.und, variant=f"n={sp['n']} vix={sp['vix']} {rule.label()} {ex.label()}",
                         engine_trades=len(a), sim_trades=len(b), matched=len(both),
                         max_abs_diff=float((both.net_engine - both.net).abs().max()) if len(both) else np.nan,
                         same_exit_min=int(((both.xcol + C.OPEN_M) == both.exit_min).sum()),
                         engine_net=float(a.net_engine.sum()), sim_net=float(b.net.sum())))
    return rows


# ------------------------------------------------------------------------------------------------ model check
def model_check(U, n=3000, seed=1):
    """How good is the Black-Scholes fill-in? Price REAL contracts near the window edge (1-3 strikes inside) at 15:15
    as if they were missing (edge IV on that side) and compare with the real print."""
    rng = np.random.default_rng(seed)
    keys = list(U.ch)
    out = []
    col = LV.SQ_COL
    for _ in range(n * 3):
        if len(out) >= n:
            break
        ser, i = keys[rng.integers(len(keys))]
        ch = U.ch[(ser, i)]
        K = ch["K"]
        if len(K) < 8 or not np.isfinite(ch["kmin"][col]):
            continue
        side = rng.choice(("lo", "hi"))
        S = U.icf[i, col]
        if side == "lo":
            cand = K[(K > ch["kmin"][col]) & (K <= ch["kmin"][col] + 3 * U.step)]
            right = 0                       # deep ITM call below the money (what a winning long call becomes)
        else:
            cand = K[(K < ch["kmax"][col]) & (K >= ch["kmax"][col] - 3 * U.step)]
            right = 1                       # deep ITM put above the money
        if not len(cand):
            continue
        k = int(rng.choice(cand))
        j = int(np.searchsorted(K, k))
        real = float(ch["C"][right, j, col])
        if not np.isfinite(real) or real < 5:
            continue
        ep = U.wexp[i] if ser == "WEEK" else U.mexp[i]
        iv = ch["ivlo"][col] if side == "lo" else ch["ivhi"][col]
        if not np.isfinite(iv):
            continue
        p = float(LV.bs(S, k, U.tyears(i, ep, [col])[0], iv / 100.0, right))
        out.append(dict(und=U.und, ser=ser, real=real, model=p, err=(p - real) / real, intrinsic_share=max(
            (S - k) if right == 0 else (k - S), 0) / real))
    return pd.DataFrame(out)


# ------------------------------------------------------------------------------------------------ run
def strip(p, key):
    d = {k: p[k] for k in TCOLS}
    d["key"] = key
    return d


def run(k=20, only=None):
    os.makedirs(OUT, exist_ok=True)
    G = LV.grid()
    if only:
        G = G[:only]
    fut_specs = {}
    for v in G:
        fs = LV.Var(v.n, v.vix, 0, "FUT", None, v.xlen, v.trigger, v.max_hold)
        fut_specs[fs.vid()] = fs
    mk = market()
    for und in UNDS:
        t0 = time.time()
        log(f"{und}: engine parity pass")
        psigs, peng = parity_engine(und)
        log(f"{und}: building the option cache")
        U = LV.Und(mk, und, log=log)
        log(f"{und}: cache ready ({time.time() - t0:.0f}s, {len(U.ch)} chains); monthly expiries "
            f"{len(U.mexp_days)}, chain-confirmed {sum(ok for _, ok in U.mexp_check)}")
        sim = LV.Sim(U)
        par = parity_check(sim, psigs, peng)
        for r in par:
            log("parity", r)
        mc = model_check(U)
        res = dict(und=und, days=U.days, parity=par, model_check=mc, mexp_check=U.mexp_check, variants={}, fut={})
        sigc = {}
        for vi, v in enumerate(G):
            key = (v.n, v.vix)
            if key not in sigc:
                sigc[key] = LV.signals(mk, v, und)
            sig = sigc[key]
            tr = LV.run_variant(sim, sig, v)
            for p in tr:
                p["key"] = f"{und}|{p['i0']}"
            poolB, poolA = LV.random_pool(sim, tr, v, k=k)
            res["variants"][v.vid()] = dict(
                trades=pd.DataFrame([strip(p, p["key"]) for p in tr]),
                mtm=[(p["key"], p["mtm"]) for p in tr], fmtm=[(p["key"], p["fmtm"]) for p in tr],
                poolB=poolB, poolA=poolA, n_signals=len(sig))
            log(f"{und} [{vi + 1}/{len(G)}] {v.vid()}: {len(tr)} positions, net {sum(p['net'] for p in tr):+,.0f}, "
                f"pool {len(poolB)}; memo {len(sim.memo)}")
        for fid, fs in fut_specs.items():
            sig = sigc.get((fs.n, fs.vix)) if (fs.n, fs.vix) in sigc else LV.signals(mk, fs, und)
            tr = LV.run_variant(sim, sig, fs, mode="FUT")
            for p in tr:
                p["key"] = f"{und}|{p['i0']}"
            poolB, poolA = LV.random_pool(sim, tr, fs, k=k, mode="FUT")
            res["fut"][fid] = dict(trades=pd.DataFrame([strip(p, p["key"]) for p in tr]),
                                   mtm=[(p["key"], p["mtm"]) for p in tr], poolB=poolB, poolA=poolA)
            log(f"{und} FUT {fid}: {len(tr)} positions, net {sum(p['net'] for p in tr):+,.0f}")
        with open(os.path.join(OUT, f"res_{und}.pkl"), "wb") as f:
            pickle.dump(res, f, protocol=4)
        log(f"{und} done in {time.time() - t0:.0f}s")
        del U, sim, res
        mk.release()


# ------------------------------------------------------------------------------------------------ analysis
def load():
    R = {}
    for und in UNDS:
        with open(os.path.join(OUT, f"res_{und}.pkl"), "rb") as f:
            R[und] = pickle.load(f)
    return R


def combine(R, part, vid, field="mtm"):
    trs, mt, pB, pA = [], {}, [], []
    for und, r in R.items():
        x = r[part].get(vid)
        if x is None:
            continue
        if len(x["trades"]):
            trs.append(x["trades"])
        for kk, m in x[field]:
            days = r["days"]
            mt[kk] = {days[i]: v for i, v in m.items()}
        pB.append(x["poolB"])
        pA.append(x["poolA"])
    tr = pd.concat(trs, ignore_index=True) if trs else pd.DataFrame(columns=TCOLS + ["key"])
    pB = pd.concat([p for p in pB if len(p)], ignore_index=True) if any(len(p) for p in pB) else pd.DataFrame(columns=["parent", "net"])
    pA = pd.concat([p for p in pA if len(p)], ignore_index=True) if any(len(p) for p in pA) else pd.DataFrame(columns=["parent", "net"])
    return tr, mt, pB, pA


def daily(mt, keys, days):
    s = pd.Series(0.0, index=days)
    for kk in keys:
        for d, v in mt[kk].items():
            if d in s.index:
                s[d] += v
    return s


def vsummary(tr, ds, days):
    """Headline numbers; drawdown / worst month / Sharpe on the daily mark-to-market P&L."""
    n = len(tr)
    yrs = len(days) / ST.DPY
    if n == 0:
        return dict(trades=0, net=0.0, per_year=0.0)
    net = tr.net.values.astype(float)
    gp, gl = net[net > 0].sum(), -net[net < 0].sum()
    r = ds.values / ST.CAPITAL
    sd = r.std(ddof=1)
    mon = ds.groupby(pd.to_datetime(pd.Series(ds.index)).dt.to_period("M").values).sum()
    yr = tr.groupby("year").net.sum()
    return dict(trades=n, net=float(net.sum()), per_year=float(net.sum() / yrs), pf=float(gp / gl) if gl > 0 else np.inf,
                win=float((net > 0).mean()), avg=float(net.mean()), max_dd=ST.max_dd(ds.cumsum().values),
                worst_month=float(mon.min()), worst_month_at=str(mon.idxmin()),
                sharpe=float(r.mean() / sd * np.sqrt(ST.DPY)) if sd > 0 else np.nan,
                years_pos=int((yr > 0).sum()), years=int(len(yr)), charges=float(tr.charges.sum()),
                sessions=float(tr.sessions.mean()), overnight=float(tr.overnight.sum()), intraday=float(tr.intraday.sum()),
                model_share=float(tr.model_days.sum() / max(tr.days_held.sum(), 1)),
                fut_net=float(tr.fut_net.sum()), fut_overnight=float(tr.fut_overnight.sum()),
                fut_intraday=float(tr.fut_intraday.sum()), legs=float(tr.n_legs.mean()))


def wf(V, by_tr, years, train_years=2, min_train=20):
    rows, parts = [], []
    for y in years[train_years:]:
        cut = pd.Timestamp(y, 1, 1).date()
        best, bv = None, -np.inf
        for vid, tr in by_tr.items():
            t = tr[tr.xday < cut]
            if len(t) < min_train:
                continue
            s = t.net.sum()
            if s > bv:
                best, bv = vid, s
        if best is None:
            continue
        t = by_tr[best]
        t = t[t.year == y]
        parts.append(t.assign(picked=best))
        rows.append(dict(year=y, picked=best, train_net=float(bv), test_net=float(t.net.sum()), n_test=len(t),
                         fut_test=float(t.fut_net.sum())))
    oos = pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()
    return rows, oos


def report():
    R = load()
    G = LV.grid()
    vids = [v.vid() for v in G]
    days = sorted({d for r in R.values() for d in r["days"]})
    years = sorted({d.year for d in days})
    V, by_tr, mts, pools = {}, {}, {}, {}
    X = np.zeros((len(days), len(vids)))
    XF = np.zeros((len(days), len(vids)))
    for j, vid in enumerate(vids):
        tr, mt, pB, pA = combine(R, "variants", vid)
        _, fmt, _, _ = combine(R, "variants", vid, field="fmtm")
        ds = daily(mt, tr.key, days) if len(tr) else pd.Series(0.0, index=days)
        fds = daily(fmt, tr.key, days) if len(tr) else pd.Series(0.0, index=days)
        X[:, j] = ds.values
        XF[:, j] = fds.values
        s = vsummary(tr, ds, days)
        s["chk_mtm"] = float(ds.sum() - tr.net.sum()) if len(tr) else 0.0
        s["chk_fut"] = float((tr.fut_overnight + tr.fut_intraday - tr.fut_gross).abs().max()) if len(tr) else 0.0
        s["chk_fmtm"] = float(fds.sum() - tr.fut_net.sum()) if len(tr) else 0.0
        s["fut_max_dd"] = ST.max_dd(fds.cumsum().values)
        rb = OF.random_baseline(tr.assign(cand=tr.key), pB, B=2000) if len(tr) >= 5 else dict(p=1.0)
        ra = OF.random_baseline(tr.assign(cand=tr.key), pA, B=2000) if len(tr) >= 5 else dict(p=1.0)
        s.update(p_rand=rb["p"], rand_obs=rb.get("obs"), rand_null=rb.get("null_mean"), p_side=ra["p"],
                 side_null=ra.get("null_mean"))
        for y in years:
            s[f"y{y}"] = float(tr[tr.year == y].net.sum()) if len(tr) else 0.0
        V[vid] = s
        by_tr[vid] = tr
        mts[vid] = mt
        pools[vid] = pB
    Vd = pd.DataFrame(V).T
    Vd["p_bh"] = OF.bh(Vd.p_rand.astype(float).values)
    Vd["p_holm"] = OF.holm(Vd.p_rand.astype(float).values)
    Vd["p_side_bh"] = OF.bh(Vd.p_side.astype(float).values)
    os.makedirs(OUT, exist_ok=True)
    Vd.to_csv(os.path.join(OUT, "variants.csv"))
    spa = OF.spa(X, B=1000)
    spaF = OF.spa(XF, B=1000)
    sd = X.std(axis=0, ddof=1)
    srs = np.where(sd > 0, X.mean(axis=0) / np.where(sd > 0, sd, 1), np.nan)
    best = Vd.net.astype(float).idxmax()
    var_sr = max(np.nanvar(srs, ddof=1), 1.0 / len(X))
    d = OF.dsr(X[:, vids.index(best)], srs, n_trials=len(vids), var_sr=var_sr)
    pb = OF.pbo(X, S=16, max_combos=4000)
    # walk-forward
    wf_rows, oos = wf(Vd, by_tr, years)
    wf_years = [r["year"] for r in wf_rows]
    days_oos = [x for x in days if x.year >= min(wf_years)] if wf_rows else []
    oos_mt = {}
    for r in wf_rows:
        for kk in oos[oos.picked == r["picked"]].key:
            oos_mt[f"{kk}|{r['picked']}"] = mts[r["picked"]][kk]
    oos = oos.assign(ukey=oos.key + "|" + oos.picked)
    ds_oos = daily(oos_mt, oos.ukey, days_oos)
    s_oos = vsummary(oos, ds_oos, days_oos)
    pp = []
    for r in wf_rows:
        p = pools[r["picked"]]
        pp.append(p[p.year == r["year"]].assign(parent=lambda x: x.parent + "|" + r["picked"]))
    rb_oos = OF.random_baseline(oos.assign(cand=oos.ukey), pd.concat(pp), B=2000)
    mc = ST.monte_carlo(oos.assign(lot=oos.qty), days_oos)
    fut_oos_dd = None
    # futures (pure signal)
    F = {}
    for fid in R["NIFTY"]["fut"]:
        tr, mt, pB, pA = combine(R, "fut", fid)
        ds = daily(mt, tr.key, days)
        s = vsummary(tr, ds, days)
        rb = OF.random_baseline(tr.assign(cand=tr.key), pB, B=2000) if len(tr) >= 5 else dict(p=1.0)
        ra = OF.random_baseline(tr.assign(cand=tr.key), pA, B=2000) if len(tr) >= 5 else dict(p=1.0)
        s.update(p_rand=rb["p"], p_side=ra["p"])
        for y in years:
            s[f"y{y}"] = float(tr[tr.year == y].net.sum())
        F[fid] = s
    Fd = pd.DataFrame(F).T
    Fd.to_csv(os.path.join(OUT, "futures.csv"))
    # checks
    par = [r for x in R.values() for r in x["parity"]]
    mcheck = pd.concat([x["model_check"] for x in R.values()])
    mexp = {u: (sum(ok for _, ok in x["mexp_check"]), len(x["mexp_check"]), [str(d) for d, ok in x["mexp_check"] if not ok])
            for u, x in R.items()}
    oos_years = {r["year"]: r for r in wf_rows}
    summ = dict(n_variants=len(vids), days=[str(days[0]), str(days[-1]), len(days)], spa=spa, spa_fut_mirror=spaF,
                dsr=d, pbo=pb, best=best, wf=wf_rows, wf_summary=s_oos, wf_p_rand=rb_oos, monte_carlo=mc,
                parity=par, model_check=dict(n=len(mcheck), median_err=float(mcheck.err.median()),
                                             median_abs_err=float(mcheck.err.abs().median()),
                                             p90_abs_err=float(mcheck.err.abs().quantile(0.9)),
                                             by_und_ser=mcheck.groupby(["und", "ser"]).err.median().to_dict().__repr__()),
                mexp_check=mexp, wf_oos_by_und_side=oos.groupby(["und", "side"]).net.sum().to_dict().__repr__(),
                wf_why=oos.why.value_counts().to_dict(), wf_oos_years=oos_years)
    gates = dict(G1=s_oos["net"] > 0, G2=rb_oos["p"] < 0.05, G3=sum(r["test_net"] > 0 for r in wf_rows) > len(wf_rows) / 2,
                 G4=(s_oos["max_dd"] >= -0.2 * ST.CAPITAL) and (mc.get("1 lot", {}).get("p_dd50", 1) <= 0.05))
    summ["gates"] = gates
    with open(os.path.join(OUT, "summary.json"), "w") as f:
        json.dump(summ, f, indent=1, default=str)
    oos.drop(columns=[]).to_csv(os.path.join(OUT, "wf_trades.csv"), index=False)
    print(json.dumps(summ, indent=1, default=str)[:20000])
    pd.set_option("display.width", 250)
    pd.set_option("display.max_rows", 200)
    cols = ["trades", "net", "per_year", "pf", "win", "max_dd", "worst_month", "sharpe", "years_pos", "sessions",
            "overnight", "intraday", "model_share", "fut_net", "p_rand", "p_side", "p_bh"]
    print(Vd[cols].astype(float).round(3).sort_values("net", ascending=False).to_string())
    print(Fd[["trades", "net", "per_year", "pf", "win", "max_dd", "years_pos", "sessions", "overnight", "intraday",
              "p_rand", "p_side"] + [f"y{y}" for y in years]].astype(float).round(3).to_string())


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("cmd", choices=["run", "report"])
    ap.add_argument("--k", type=int, default=20)
    ap.add_argument("--only", type=int, default=None)
    a = ap.parse_args()
    if a.cmd == "run":
        run(k=a.k, only=a.only)
    else:
        report()
