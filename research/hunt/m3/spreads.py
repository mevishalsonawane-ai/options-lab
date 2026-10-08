"""M3: build work/spreads.json from live snapshots (pre-registered method).
- morning ('am'): this study's chain snapshots, 9 Oct 2026 09:01-10:22 IST, near expiry, the 4 strikes nearest the
  money (ATM / 1-ITM) per side, median of (ask - bid) / mid.
- evening ('pm'): CRUDEOIL from STRAD-CRUDE's 8 Oct 22:24-23:28 IST snapshots (same buckets); other commodities:
  am x (crude pm / crude am), floored at crude pm.
- futures: median (ask - bid) in Rs per unit of quoted price from /marketfeed/quote snapshots; CRUDEOILM Rs 2 (NN-CRUDE)
  if missing.
Prints the summary table (also written to results/spreads.csv)."""
import json, sys
import numpy as np, pandas as pd
sys.path.insert(0, "/home/user/options-lab/research/hunt/m3")
import m3lib as M

C = pd.read_csv(M.S / "hunt" / "m3" / "chain_snaps.csv")
C["ts"] = pd.to_datetime(C.ts, utc=True)
C = C[(C.ts >= "2026-10-09T03:31:00Z") & (C.ts <= "2026-10-09T05:00:00Z")]
X = pd.read_csv(M.S / "hunt" / "strad_crude" / "chain_snaps.csv")
X["ts"] = pd.to_datetime(X.ts, utc=True); X["sym"] = "CRUDEOIL"
X = X[X.expiry == X.expiry.min()]


def rel(df):
    df = df[(df.bid > 0) & (df.ask > 0) & (df.ask >= df.bid)].copy()
    df["mid"] = (df.bid + df.ask) / 2
    df["rs"] = (df.ask - df.bid) / df.mid
    # distance from the money in strikes: keep the 2 nearest strikes to the futures on each side (ATM and 1-ITM)
    out = []
    for (ts, sym), g in df.groupby(["ts", "sym"]):
        for side, gg in g.groupby("side"):
            itm = gg[(gg.strike <= gg.fut) if side == "ce" else (gg.strike >= gg.fut)]
            near = itm.iloc[(itm.strike - itm.fut).abs().argsort()[:2]]
            atm = gg.iloc[(gg.strike - gg.fut).abs().argsort()[:1]]
            out.append(pd.concat([near, atm]).drop_duplicates("strike"))
    return pd.concat(out) if out else df.iloc[:0]


A = rel(C); P = rel(X)
am = A.groupby("sym").rs.median()
n_am = A.groupby("sym").ts.nunique()
crude_pm = P.rs.median()
ratio = crude_pm / am.get("CRUDEOIL", crude_pm)
rows, opt = [], {}
for s in sorted(set(am.index) | {"CRUDEOIL"}):
    a = am.get(s, np.nan)
    p = crude_pm if s == "CRUDEOIL" else max(a * ratio, crude_pm)
    opt[s] = {"am": float(a), "pm": float(p)}
    rows.append(dict(sym=s, am_rel=a, pm_rel=p, snapshots=int(n_am.get(s, 0)),
                     am_rs_median=float(A[A.sym == s].eval("ask-bid").median()) if s in am.index else np.nan,
                     prem_median=float(A[A.sym == s].mid.median()) if s in am.index else np.nan))
opt["_default"] = {"am": float(am.median()), "pm": float(crude_pm)}
fut = {}
try:
    Q = pd.read_csv(M.S / "hunt" / "m3" / "fut_quotes.csv")
    Q["ts"] = pd.to_datetime(Q.ts, utc=True)
    Q = Q[(Q.ts >= "2026-10-09T03:31:00Z") & (Q.bid > 0) & (Q.ask > Q.bid)]
    fq = Q.assign(sp=Q.ask - Q.bid).groupby("sym").sp.median()
    fut = {k: float(v) for k, v in fq.items()}
    print("futures spreads (Rs per quoted unit):", fut, "snapshots", Q.ts.nunique())
except FileNotFoundError:
    pass
fut.setdefault("CRUDEOILM", 2.0)
json.dump({"opt": opt, "fut": fut, "note": "measured 9 Oct 2026 (am) + 8 Oct crude (pm)"},
          open(M.BASE_WORK / "spreads.json", "w"), indent=1)
R = pd.DataFrame(rows)
R.to_csv("/home/user/options-lab/research/hunt/m3/results/spreads.csv", index=False)
print(R.round(4).to_string(index=False))
print("crude pm/am ratio", round(ratio, 3))
