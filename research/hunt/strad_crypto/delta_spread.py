"""Measure Delta Exchange India live bid/ask on near-ATM options (daily/next-day expiries) from polled chains."""
import sys; sys.path.append("/root/.local/lib/python3.11/site-packages")
import json, pandas as pd, numpy as np
rows = []
for line in open(sys.argv[1]):
    s = json.loads(line)
    for r in s["rows"]:
        try:
            K = float(r["K"]); spot = float(r["spot"]); bid = float(r["bid"] or 0); ask = float(r["ask"] or 0)
        except (TypeError, ValueError):
            continue
        exp = pd.Timestamp("20" + r["sym"][-2:] + "-" + r["sym"][-4:-2] + "-" + r["sym"][-6:-4] + " 12:00", tz="UTC")
        rows.append(dict(t=s["t"], u=s["u"], sym=r["sym"], typ=r["sym"][0], K=K, spot=spot, bid=bid, ask=ask, mark=float(r["mark"] or 0),
                         bs=float(r["bs"] or 0), as_=float(r["as_"] or 0), iv=float(r["iv"] or 0),
                         hrs=(exp - pd.Timestamp(s["t"], unit="s", tz="UTC")).total_seconds() / 3600))
d = pd.DataFrame(rows)
d["mny"] = (d.K / d.spot - 1).abs()
d = d[(d.bid > 0) & (d.ask > 0)]
d["mid"] = (d.bid + d.ask) / 2; d["spr"] = (d.ask - d.bid) / d.mid
# ATM = the strike nearest spot per (snapshot, underlying, expiry, type)
d["exp"] = d.sym.str[-6:]
atm = d.loc[d.groupby(["t", "u", "exp", "typ"]).mny.idxmin()]
atm["bucket"] = pd.cut(atm.hrs, [0, 6, 12, 24, 36, 48, 96, 400])
print("snapshots:", d.t.nunique(), "from", pd.Timestamp(d.t.min(), unit="s"), "to", pd.Timestamp(d.t.max(), unit="s"))
g = atm.groupby(["u", "bucket"], observed=True).agg(n=("spr", "size"), spr_med=("spr", "median"), spr_p75=("spr", lambda x: x.quantile(.75)),
                                                   mid_usd=("mid", "median"), bid_sz=("bs", "median"), ask_sz=("as_", "median"))
print(g.to_string())
atm.to_csv(sys.argv[2], index=False)

# linear spread model per leg: full spread / spot = a + b * mid / spot  (near-ATM +-5%, <= 7 days)
d["sprf"] = (d.ask - d.bid) / d.spot; d["midf"] = d.mid / d.spot
n = d[(d.mny < 0.05) & (d.hrs < 170)]
model = {}
for u, g in n.groupby("u"):
    A = np.c_[np.ones(len(g)), g.midf]
    model[u] = [float(x) for x in np.linalg.lstsq(A, g.sprf, rcond=None)[0]]
    print(u, "quotes", len(g), "snapshots", g.t.nunique(), "spread/S = %.6f + %.4f * mid/S" % tuple(model[u]))
json.dump(model, open(sys.argv[3], "w"))
