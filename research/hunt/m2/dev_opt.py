"""M2 development: option-buying variants (MODELLED Black-76), 2013-2024. 11 signals x {CRUDE,NATGAS,GOLD,SILVER,PORT_OPT}.
Random run-shuffle baseline (40 draws), BH, walk-forward; IV x0.85/x1.25 and spread x2 sensitivity for ENS."""
import json, numpy as np, pandas as pd, multiprocessing as mp
import engine as E, opt as O
OUT = O.OUT
START = pd.Timestamp("2013-01-01")
data = E.load(E.DEV_END)
cal = O.calibrate(E.load())   # pricing parameter only (IV/RV ratio), Aug 2025+; documented in PREREG
RATIO = {"CRUDE": 1.065, "NATGAS": 1.05, "GOLD": 0.961, "SILVER": 1.005}  # frozen (D5)
SIG = {n: {k: E.signal(data[n], k) for k in E.SIGNALS} for n in O.OSPEC}
UNIV = list(O.OSPEC) + ["PORT_OPT"]
def legs(u): return list(O.OSPEC) if u == "PORT_OPT" else [u]
def job(args):
    u, sig, seed = args
    sg = {n: SIG[n][sig] for n in legs(u)}
    if seed is not None:
        rng = np.random.default_rng(seed)
        sg = {n: pd.Series(E.runs_shuffle(s.values, rng, True), index=s.index) for n, s in sg.items()}
    df = O.run_options(data, sg, RATIO, START, E.DEV_END)
    return df
def sh(p): return p.mean() / p.std() * np.sqrt(252) if p.std() > 0 else 0.0
if __name__ == "__main__":
    print("ratios", RATIO, flush=True)
    keys = [(u, s) for s in E.SIGNALS for u in UNIV]
    with mp.Pool(3) as pool:
        real = pool.map(job, [(u, s, None) for u, s in keys])
        rows, daily = [], {}
        for (u, s), df in zip(keys, real):
            st = E.stats(df); yr = df.pnl.groupby(df.index.year).sum()
            daily[f"{u}|{s}|OPT"] = df.pnl
            rows.append(dict(key=f"{u}|{s}|OPT", univ=u, sig=s, mode="OPT", **st, **{f"y{y}": v for y, v in yr.items()}))
        print("real done", flush=True)
        R = pd.DataFrame(rows).set_index("key")
        rnd = pool.map(job, [(u, s, 1000 + i) for u, s in keys for i in range(40)])
        rs = np.array([sh(df.pnl) for df in rnd]).reshape(len(keys), 40)
        R["rand_pct"] = [100 * (rs[i] < R.iloc[i].sharpe).mean() for i in range(len(keys))]
        R["rand_mean_sharpe"] = rs.mean(1)
        sens = {}
        for u in UNIV:
            sg = {n: SIG[n]["ENS"] for n in legs(u)}
            for lab, kw in [("iv0.85", dict(iv_scale=0.85)), ("iv1.25", dict(iv_scale=1.25)), ("spr2x", dict(spr_scale=2.0))]:
                sens[f"{u}|{lab}"] = E.stats(O.run_options(data, sg, RATIO, START, E.DEV_END, **kw))
    P = pd.DataFrame(daily).fillna(0.0)
    from scipy import stats as SS
    pv = []
    for k in R.index:
        x = P[k].values; xm = x.mean(); xc = x - xm; n = len(x); s = np.dot(xc, xc) / n
        for l in range(1, 11): s += 2 * (1 - l / 11) * np.dot(xc[l:], xc[:-l]) / n
        pv.append(1 - SS.norm.cdf(xm / np.sqrt(s / n)) if s > 0 else 1.0)
    R["p"] = pv
    m = len(pv); order = np.argsort(pv); q = np.empty(m); ranked = np.array(pv)[order] * m / (np.arange(m) + 1)
    q[order] = np.minimum.accumulate(ranked[::-1])[::-1]; R["bh_q"] = np.minimum(q, 1)
    wf = []
    for Y in range(2014, 2025):
        tr = P[P.index < pd.Timestamp(f"{Y}-01-01")]; te = P[P.index.year == Y]
        shr = tr.mean() / tr.std() * np.sqrt(252); pk = shr.idxmax()
        wf.append(dict(year=Y, pick=pk, pnl=te[pk].sum()))
    P.to_parquet(f"{OUT}/dev_daily_opt.parquet", compression="zstd")
    R.to_csv(f"{OUT}/dev_results_opt.csv"); pd.DataFrame(wf).to_csv(f"{OUT}/dev_wf_opt.csv", index=False)
    json.dump(dict(ratio=RATIO, cal=cal, sens=sens), open(f"{OUT}/dev_opt_meta.json", "w"), indent=1, default=float)
    pd.set_option("display.width", 250); pd.set_option("display.max_rows", 100)
    print(R[["cagr", "rs_month", "maxdd", "worst_month", "green", "sharpe", "trades_yr", "avg_margin", "rand_pct", "bh_q", "final"]].round(2).sort_values("sharpe", ascending=False))
    print(pd.DataFrame(wf)); print(pd.DataFrame(sens).T[["cagr", "sharpe", "maxdd", "final"]].round(2))
