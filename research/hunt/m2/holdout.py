"""M2 LOCKED HOLDOUT 2025-01-01 .. latest. Runs ONCE (lock file). Fresh Rs 1 lakh on 1 Jan 2025.
Primary: PORT|ENS|LS, PORT|ENS|LO, PORT_OPT|ENS|OPT. Secondary: PORT4|ENS|LS/LO and the walk-forward picks
(best development Sharpe 2013-2024). Also prints every variant on the holdout, descriptive only."""
import os, sys, glob, json, numpy as np, pandas as pd
import engine as E, opt as O
from prep import SPEC
OUT = O.OUT
lock = f"{OUT}/holdout.lock"
if os.path.exists(lock): sys.exit("holdout already run once; refusing")
H0 = E.HOLD_START
data = E.load()
end = max(g.index[-1] for g in data.values())
PORT4 = ["CRUDE", "NATGAS", "GOLD", "SILVER"]
RATIO = {"CRUDE": 1.065, "NATGAS": 1.05, "GOLD": 0.961, "SILVER": 1.005}
# real near-month ATM IV (Dhan rolling) where available
real_iv = {}
for n, o in O.OSPEC.items():
    fs = glob.glob(f"{OUT}/data/opts/{o['dsym']}_c1_+0_C.parquet") or ([f"{OUT}/../strad_crude/cache/c1_ATM_CALL.parquet"] if n == "CRUDE" else [])
    if fs:
        d = pd.read_parquet(fs[0]); d["ts"] = pd.to_datetime(d.ts)
        if d.ts.dt.tz is not None: d["ts"] = d.ts.dt.tz_localize(None)
        d = d[d.iv > 1]; real_iv[n] = d.groupby(d.ts.dt.normalize()).iv.last() / 100.0
devF = pd.read_parquet(f"{OUT}/dev_daily_fut.parquet"); devO = pd.read_parquet(f"{OUT}/dev_daily_opt.parquet")
shF = devF.mean() / devF.std(); shO = devO.mean() / devO.std()
wf_all = shF.idxmax(); wf_port = shF[[k for k in shF.index if k.startswith("PORT")]].idxmax(); wf_opt = shO.idxmax()
def fut(key):
    u, sig, mode = key.split("|")
    legs = list(SPEC) if u == "PORT" else (PORT4 if u == "PORT4" else [u])
    return E.run_futures(data, {n: E.signal(data[n], sig) for n in legs}, mode, start=H0, end=end)
def opt(key, riv=True):
    u, sig, _ = key.split("|")
    legs = list(O.OSPEC) if u == "PORT_OPT" else [u]
    return O.run_options(data, {n: E.signal(data[n], sig) for n in legs}, RATIO, H0, end, real_iv=real_iv if riv else None)
rows = []
def add(lab, key, df):
    st = E.stats(df); mon = df.pnl.groupby(df.index.to_period("M")).sum()
    rows.append(dict(role=lab, key=key, **st))
    return mon
mons = {}
for k in ["PORT|ENS|LS", "PORT|ENS|LO"]: mons[k] = add("PRIMARY", k, fut(k))
mons["PORT_OPT|ENS|OPT"] = add("PRIMARY (semi-real IV)", "PORT_OPT|ENS|OPT", opt("PORT_OPT|ENS|OPT"))
add("PRIMARY (modelled IV)", "PORT_OPT|ENS|OPT", opt("PORT_OPT|ENS|OPT", riv=False))
for k in ["PORT4|ENS|LS", "PORT4|ENS|LO"]: add("secondary post-hoc", k, fut(k))
add("WF pick (all)", wf_all, fut(wf_all)); add("WF pick (portfolios)", wf_port, fut(wf_port))
add("WF pick (options)", wf_opt, opt(wf_opt))
P = pd.DataFrame(rows)
desc = []
for k in devF.columns:
    st = E.stats(fut(k)); desc.append(dict(key=k, **st))
for k in devO.columns:
    st = E.stats(opt(k)); desc.append(dict(key=k, **st))
D = pd.DataFrame(desc)
open(lock, "w").write("run\n")  # written once results exist, before anything is shown
P.to_csv(f"{OUT}/holdout_main.csv", index=False); D.to_csv(f"{OUT}/holdout_all.csv", index=False)
pd.DataFrame(mons).to_csv(f"{OUT}/holdout_monthly.csv")
pd.set_option("display.width", 250); pd.set_option("display.max_columns", 20)
cols = ["cagr", "rs_month", "maxdd", "maxdd_pct", "worst_month", "green", "sharpe", "trades_yr", "avg_margin", "final", "worst_day"]
print("holdout", H0.date(), "->", end.date())
print(P[["role", "key"] + cols].round(2).to_string())
print("ALL variants on holdout (descriptive): median sharpe", D.sharpe.median().round(2), "share final>1L", (D.final > 1e5).mean().round(2))
print(D.sort_values("sharpe", ascending=False)[["key"] + cols].round(2).head(15).to_string())
print(pd.DataFrame(mons).round(0).to_string())
