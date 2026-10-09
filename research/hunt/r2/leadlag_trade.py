"""R2 F3b: is the 1-5 min US lead tradable? Conditional next-N-minute MCX move after a big US 5-min move that MCX has
not matched (catch-up = US move - MCX move over the same window). US minute: Dukascopy XAUUSD/XAGUSD/LIGHTCMDUSD/
GASCMDUSD (UTC). MCX minute: Dhan spot (IST). Design = Aug-Sep 2025; holdout = Oct 2025 - Oct 2026."""
import sys
import numpy as np, pandas as pd
from lib import *
MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2025-08-01"), HOLD_START) if MODE == "design" else (HOLD_START, HOLD_END)
SRC = {"GOLD": "xau", "SILVER": "XAGUSD", "CRUDE": "LIGHTCMDUSD", "NATGAS": "GASCMDUSD"}

def us_min(com):
    if com == "GOLD":
        x = pd.read_csv(S / "xauusd_m1_bid.csv.gz"); x.index = pd.to_datetime(x.timestamp, unit="ms"); return x.close
    p = D / f"duka_{SRC[com]}.parquet"
    if not p.exists(): return None
    x = pd.read_parquet(p); return x.set_index("ts").close.sort_index()

def mcx_min(com):
    if com == "CRUDE": sp = pd.read_parquet(H / "strad_crude/cache/c1_ATM_CALL.parquet", columns=["ts", "spot"])
    else: sp = pd.read_parquet(H / f"m3/raw/spot_{MCX_SYM[com]}.parquet")
    sp["ts"] = sp.ts.dt.tz_localize(None) - pd.Timedelta(hours=5, minutes=30)
    return sp[sp.spot > 0].drop_duplicates("ts").set_index("ts").spot.sort_index()

rows = []
for com in COMS:
    u0 = us_min(com)
    if u0 is None: continue
    m = mcx_min(com); m = m[(m.index >= LO) & (m.index < HI)]
    u = u0.reindex(m.index).ffill(limit=2)
    lm, lu = np.log(m), np.log(u)
    px = float(m.median()); _, n1 = fut_pnl(com, 1, px, px); cost_bp = -1e4 * n1 / (px * MINI[com][1])
    for look in (1, 5):
        cu = (lu - lu.shift(look)) - (lm - lm.shift(look))
        ok = (m.index.to_series().diff(look) == pd.Timedelta(minutes=look)).values
        for hz in (1, 5, 15):
            fwd = lm.shift(-hz) - lm
            okf = ok & ((m.index.to_series().shift(-hz) - m.index.to_series()) == pd.Timedelta(minutes=hz)).values
            c = cu[okf]; f = fwd[okf]
            for q in (0.95, 0.99, 0.997):
                thr = c.abs().quantile(q)
                sel = c.abs() > thr
                sig = np.sign(c[sel]) * f[sel]
                rows.append(dict(com=com, look=look, hz=hz, q=q, n=int(sel.sum()), thr_bp=1e4 * thr, mean_bp=1e4 * sig.mean(),
                                 hit=(sig > 0).mean(), t=sig.mean() / sig.std() * np.sqrt(len(sig)), cost_bp=cost_bp,
                                 zero_mcx=float((lm.diff() == 0).mean())))
O = pd.DataFrame(rows); print(O.round(2).to_string())
O.to_csv(OUT / f"leadlag_trade_{MODE}.csv", index=False)
