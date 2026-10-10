"""R2 F3: minute lead-lag. GOLDM (Dhan spot minute, IST) vs XAUUSD (Dukascopy 1m, UTC). Design 5 Aug-30 Sep 2025.
Holdout mode: Oct 2025-Sep 2026 for gold, plus Yahoo 1m (2-7 Oct 2026) for CL/NG/SI vs MCX (descriptive)."""
import sys
import numpy as np, pandas as pd
from lib import *
MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2025-08-01"), HOLD_START) if MODE == "design" else (HOLD_START, HOLD_END)

def mcx_min(com):
    if com == "CRUDE": sp = pd.read_parquet(H / "strad_crude/cache/c1_ATM_CALL.parquet", columns=["ts", "spot"])
    else: sp = pd.read_parquet(H / f"m3/raw/spot_{MCX_SYM[com]}.parquet")
    sp["ts"] = sp.ts.dt.tz_localize(None) - pd.Timedelta(hours=5, minutes=30)  # -> UTC
    return sp[sp.spot > 0].drop_duplicates("ts").set_index("ts").spot.sort_index()

def xcorr(a, b, lags=range(-5, 6)):
    """corr(a_t, b_{t+k}); k>0: a leads b."""
    j = pd.concat([a.rename("a"), b.rename("b")], axis=1).dropna()
    return {k: j.a.corr(j.b.shift(-k)) for k in lags}, len(j)

def run(com, us):
    m = mcx_min(com); m = m[(m.index >= LO) & (m.index < HI)]
    us = us[(us.index >= LO) & (us.index < HI)]
    out = {}
    for nm, (h0, h1) in {"all": (0, 24), "IST09-17": (3.5, 11.5), "IST17-23:30": (11.5, 18.0)}.items():
        hh = m.index.hour + m.index.minute / 60
        mm = m[(hh >= h0) & (hh < h1)]
        rm = np.log(mm).diff(); ru = np.log(us.reindex(mm.index).ffill(limit=2)).diff()
        ok = (rm.index.to_series().diff() == pd.Timedelta(minutes=1)).values
        rm, ru = rm[ok], ru[ok]
        c, n = xcorr(ru, rm)  # k>0: US leads MCX
        # stale share: MCX minutes with zero change
        out[nm] = dict(n=n, zero_mcx=float((rm == 0).mean()), **{f"L{k}": round(v, 3) for k, v in c.items()})
        # 5-min returns, lag 1 bar
        m5 = mm.resample("5min").last(); u5 = us.resample("5min").last().reindex(m5.index)
        c5, n5 = xcorr(np.log(u5).diff(), np.log(m5).diff(), range(-2, 3))
        out[nm].update({f"5m_L{k}": round(v, 3) for k, v in c5.items()})
    return out

SRC = {"GOLD": None, "SILVER": "XAGUSD", "CRUDE": "LIGHTCMDUSD", "NATGAS": "GASCMDUSD"}
res = {}
for com in COMS:
    if com == "GOLD":
        x = pd.read_csv(S / "xauusd_m1_bid.csv.gz"); x.index = pd.to_datetime(x.timestamp, unit="ms"); u = x.close
    else:
        p = D / f"duka_{SRC[com]}.parquet"
        if not p.exists(): continue
        u = pd.read_parquet(p).set_index("ts").close.sort_index()
    res[f"{com}_vs_dukascopy"] = run(com, u)
if MODE == "holdout":
    y1 = yahoo("m1")
    for com in COMS:
        u = y1[y1.sym == US_SYM[com]].set_index("ts").close.sort_index()
        res[f"{com}_vs_{US_SYM[com]}_1m_yahoo"] = run(com, u)
for k, v in res.items():
    print(k); print(pd.DataFrame(v).T.to_string())
pd.concat({k: pd.DataFrame(v).T for k, v in res.items()}).to_csv(OUT / f"leadlag_{MODE}.csv")
