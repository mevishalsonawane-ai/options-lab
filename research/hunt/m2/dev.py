"""M2 development run (2013-2024 only; data truncated at 2024-12-31 before signals are computed).
Futures variants: 11 signals x {LS, LO} x 8 universes, constrained engine (margin, Rs 1 lakh, fixed lots).
Random baseline (unconstrained, run-shuffled), BH, SPA/White RC, walk-forward by year."""
import sys, json, numpy as np, pandas as pd
import engine as E
from prep import SPEC
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2"
START = pd.Timestamp("2013-01-01")
data = E.load(E.DEV_END)
for n, g in data.items(): g.attrs["n"] = n
SIG = {n: {k: E.signal(data[n], k) for k in E.SIGNALS} for n in data}
PORT4 = ["CRUDE", "NATGAS", "GOLD", "SILVER"]
UNIV = list(SPEC) + ["PORT", "PORT4"]
rows, daily = [], {}
for sig in E.SIGNALS:
    for mode in ("LS", "LO"):
        for u in UNIV:
            legs = list(SPEC) if u == "PORT" else (PORT4 if u == "PORT4" else [u])
            df = E.run_futures(data, {n: SIG[n][sig] for n in legs}, mode, start=START, end=E.DEV_END)
            st = E.stats(df); key = f"{u}|{sig}|{mode}"
            daily[key] = df.pnl
            yr = df.pnl.groupby(df.index.year).sum()
            rows.append(dict(key=key, univ=u, sig=sig, mode=mode, **st, **{f"y{y}": v for y, v in yr.items()}))
    print(sig, "done", flush=True)
R = pd.DataFrame(rows).set_index("key")
P = pd.DataFrame(daily).fillna(0.0)
P.to_parquet(f"{OUT}/dev_daily_fut.parquet", compression="zstd")

# ---------- random baseline (unconstrained, same run lengths) ----------
rng = np.random.default_rng(7)
idx = P.index
arrs = {}
for n in SPEC:
    g = data[n]; g = g[g.index >= START]
    arrs[n] = (g.index, E.leg_arrays(g, n))
def unc(u, sig, mode, shuffle=None):
    legs = list(SPEC) if u == "PORT" else (PORT4 if u == "PORT4" else [u])
    tot = pd.Series(0.0, index=idx)
    for n in legs:
        ix, a = arrs[n]
        s = SIG[n][sig].reindex(ix).values
        if mode == "LO": s = np.maximum(s, 0)
        if shuffle is not None: s = E.runs_shuffle(s, shuffle, flip=(mode == "LS"))
        tot = tot.add(pd.Series(E.vec_pnl(a, s), index=ix), fill_value=0.0)
    return tot
def sh(p): return p.mean() / p.std() * np.sqrt(252) if p.std() > 0 else 0.0
pct, unc_sh, rnd_mean = [], [], []
for key in R.index:
    u, sig, mode = key.split("|")
    real = sh(unc(u, sig, mode))
    rs = np.array([sh(unc(u, sig, mode, rng)) for _ in range(100)])
    unc_sh.append(real); pct.append(100 * (rs < real).mean()); rnd_mean.append(rs.mean())
R["unc_sharpe"] = unc_sh; R["rand_pct"] = pct; R["rand_mean_sharpe"] = rnd_mean
print("random done", flush=True)

# ---------- BH on NW t-stat p-values ----------
from scipy import stats as SS
def nw_t(x, L=10):
    x = np.asarray(x) - np.mean(x); n = len(x)
    g0 = np.dot(x, x) / n; s = g0
    for l in range(1, L + 1):
        s += 2 * (1 - l / (L + 1)) * np.dot(x[l:], x[:-l]) / n
    return np.mean(np.asarray(x) + 0) , s
pv = []
for key in R.index:
    x = P[key].values
    xm = x.mean(); xc = x - xm; n = len(x); s = np.dot(xc, xc) / n
    for l in range(1, 11): s += 2 * (1 - l / 11) * np.dot(xc[l:], xc[:-l]) / n
    t = xm / np.sqrt(s / n) if s > 0 else 0
    pv.append(1 - SS.norm.cdf(t))
R["p"] = pv
m = len(pv); order = np.argsort(pv); q = np.empty(m)
ranked = np.array(pv)[order] * m / (np.arange(m) + 1)
q[order] = np.minimum.accumulate(ranked[::-1])[::-1]
R["bh_q"] = np.minimum(q, 1)

# ---------- SPA / White RC (stationary bootstrap) ----------
def stat_boot_idx(n, B, mean_block, rng):
    p = 1 / mean_block
    out = np.empty((B, n), dtype=np.int64)
    for b in range(B):
        i = rng.integers(n); 
        starts = rng.random(n) < p; newi = rng.integers(n, size=n)
        idxs = np.empty(n, dtype=np.int64)
        for t in range(n):
            if t == 0 or starts[t]: i = newi[t]
            else: i = (i + 1) % n
            idxs[t] = i
        out[b] = idxs
    return out
X = P.values; n = X.shape[0]
mu = X.mean(0); sd = X.std(0) + 1e-9
B = 500
bi = stat_boot_idx(n, B, 20, np.random.default_rng(11))
Tobs = np.max(np.sqrt(n) * mu / sd)
thr = np.sqrt(2 * np.log(np.log(n)))
mu_c = np.where(np.sqrt(n) * mu / sd > -thr, mu, 0.0)   # SPA consistent recentring
rc, spa = [], []
for b in range(B):
    mb = X[bi[b]].mean(0)
    rc.append(np.max(np.sqrt(n) * (mb - mu) / sd))
    spa.append(np.max(np.sqrt(n) * (mb - mu + mu_c - mu_c) / sd) if False else np.max(np.sqrt(n) * (mb - mu_c) / sd - np.sqrt(n) * (mu - mu_c) / sd * 0))
rc = np.array(rc)
p_rc = (rc >= Tobs).mean()
spa_stats = np.array([np.max(np.sqrt(n) * (X[bi[b]].mean(0) - mu + mu_c) / sd) for b in range(B)])
p_spa = (np.maximum(spa_stats, 0) >= max(Tobs, 0)).mean()
best = R.index[np.argmax(np.sqrt(n) * mu / sd)]
print(f"White RC p={p_rc:.3f}  SPA(c) p={p_spa:.3f}  best={best}  n_variants={X.shape[1]}", flush=True)

# ---------- walk-forward by year ----------
wf = []
for Y in range(2014, 2025):
    tr = P[(P.index < pd.Timestamp(f"{Y}-01-01"))]
    te = P[(P.index.year == Y)]
    shr = tr.mean() / tr.std() * np.sqrt(252)
    pick_all = shr.idxmax()
    port = [k for k in P.columns if k.startswith("PORT")]
    pick_p = shr[port].idxmax()
    wf.append(dict(year=Y, pick_all=pick_all, pnl_all=te[pick_all].sum(), pick_port=pick_p, pnl_port=te[pick_p].sum()))
WF = pd.DataFrame(wf)
R.to_csv(f"{OUT}/dev_results_fut.csv"); WF.to_csv(f"{OUT}/dev_wf_fut.csv", index=False)
json.dump(dict(p_rc=p_rc, p_spa=p_spa, best=best, n=int(X.shape[1])), open(f"{OUT}/dev_spa_fut.json", "w"))
pd.set_option("display.width", 250); pd.set_option("display.max_rows", 300)
cols = ["cagr", "rs_month", "maxdd", "worst_month", "green", "sharpe", "trades_yr", "unc_sharpe", "rand_pct", "bh_q"]
print(R[cols].sort_values("sharpe", ascending=False).round(2).head(40))
print(R[R.univ.str.startswith("PORT")][cols + ["final"]].round(2))
print(WF)
