"""M2 long proxy: same trend signals on Yahoo front futures (CL, BZ, NG, GC, SI, HG) in INR, 2001-2024 (dev).
Vol-scaled (10%/yr per leg, 60d vol) and unscaled (fixed notional), cost 0.06% of notional per side, roll days'
returns removed (volume-jump detection). Portfolio = equal risk average of legs. Random run-shuffle baseline.
Usage: python3 proxy.py            (dev, data cut 2024-12-31)
       python3 proxy.py --holdout  (2025-01-01..; once, lock file)"""
import sys, os, numpy as np, pandas as pd
import engine as E
from prep import roll_days
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2"
HOLD = "--holdout" in sys.argv
if HOLD:
    lock = f"{OUT}/proxy_holdout.lock"
    if os.path.exists(lock): sys.exit("proxy holdout already run once; refusing")
    open(lock, "w").write("run\n")
y = pd.read_parquet(f"{OUT}/data/yahoo.parquet")
y = y.drop_duplicates(["sym", "date"]).set_index(["sym", "date"]).sort_index()
inr = y.loc["INR=X"].close
inr_r = np.log(inr).diff().reindex(pd.DatetimeIndex(sorted(set(y.index.get_level_values(1))))).fillna(0.0)
SYMS = ["CL=F", "BZ=F", "NG=F", "GC=F", "SI=F", "HG=F"]
END = None if HOLD else E.DEV_END
legs = {}
for s in SYMS:
    g = y.loc[s].copy()
    g = g[g.close > 0]
    if END is not None: g = g[g.index <= END]
    rd = roll_days(g.volume.replace(0, np.nan).ffill())
    r = np.log(g.close).diff().where(~rd, 0.0).fillna(0.0) + inr_r.reindex(g.index).fillna(0.0)
    f = pd.DataFrame(index=g.index); f["adj"] = np.exp(r.cumsum()); f["carry"] = np.nan; f["r"] = r
    legs[s] = f
SIGS = [k for k in E.SIGNALS if k != "CARRY"]
def leg_ret(f, s, scaled, mode):
    if mode == "LO": s = s.clip(lower=0)
    vol = f.r.rolling(60).std() * np.sqrt(252)
    w = (0.10 / vol).clip(upper=3.0) if scaled else pd.Series(1.0, index=f.index)
    pos = (s * w).shift(1).fillna(0.0)
    return pos * f.r - 0.0006 * pos.diff().abs().fillna(0.0)
def sh(x): x = x[x.index >= START]; return x.mean() / x.std() * np.sqrt(252) if x.std() > 0 else 0.0
START = pd.Timestamp("2025-01-01") if HOLD else pd.Timestamp("2001-06-01")
rng = np.random.default_rng(3)
rows = []
for sig in SIGS:
    S = {s: E.signal(legs[s], sig) for s in SYMS}
    for mode in ("LS", "LO"):
        for scaled in (True, False):
            rets = {s: leg_ret(legs[s], S[s], scaled, mode) for s in SYMS}
            port = pd.DataFrame(rets).fillna(0.0).mean(1)
            row = dict(sig=sig, mode=mode, scaled=scaled, port_sh=sh(port), port_ann=100 * port[port.index >= START].mean() * 252)
            for s in SYMS: row[s] = sh(rets[s])
            if not HOLD:
                rs = []
                for _ in range(100):
                    rr = {}
                    for s in SYMS:
                        sh_s = pd.Series(E.runs_shuffle(S[s].values, rng, flip=(mode == "LS")), index=S[s].index)
                        rr[s] = leg_ret(legs[s], sh_s, scaled, mode)
                    rs.append(sh(pd.DataFrame(rr).fillna(0.0).mean(1)))
                row["rand_pct"] = 100 * (np.array(rs) < row["port_sh"]).mean(); row["rand_mean"] = np.mean(rs)
            # per-year for scaled LS port
            if scaled:
                py = port[port.index >= START].groupby(port[port.index >= START].index.year).sum() * 100
                for k, v in py.items(): row[f"y{k}"] = v
            rows.append(row)
R = pd.DataFrame(rows)
R.to_csv(f"{OUT}/proxy_{'holdout' if HOLD else 'dev'}.csv", index=False)
pd.set_option("display.width", 250); pd.set_option("display.max_columns", 40)
print(R[["sig", "mode", "scaled", "port_sh", "port_ann"] + SYMS + (["rand_pct", "rand_mean"] if not HOLD else [])].round(2))
