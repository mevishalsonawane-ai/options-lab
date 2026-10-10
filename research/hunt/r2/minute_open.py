"""R2: how fast does the MCX open close its gap residual? MCX minute (Dhan spot) vs US-implied fair gap.
Design window only (5 Aug - 30 Sep 2025) unless 'holdout' and the lock exists."""
import sys
import numpy as np, pandas as pd
from lib import *
MODE = sys.argv[1] if len(sys.argv) > 1 else "design"
if MODE == "holdout": assert (R2 / "holdout.lock").exists()
LO, HI = (pd.Timestamp("2025-08-01"), HOLD_START - pd.Timedelta(days=1)) if MODE == "design" else (HOLD_START, HOLD_END)
HR = pd.read_parquet(OUT / f"hourly_{MODE}.parquet")

def mcx_min(com):
    if com == "CRUDE":
        sp = pd.read_parquet(H / "strad_crude/cache/c1_ATM_CALL.parquet", columns=["ts", "spot"])
    else:
        sp = pd.read_parquet(H / f"m3/raw/spot_{MCX_SYM[com]}.parquet")
    sp = sp[sp.spot > 0].copy(); sp["ts"] = sp.ts.dt.tz_localize(None) if sp.ts.dt.tz is not None else sp.ts
    return sp.drop_duplicates("ts").set_index("ts").spot.sort_index()

marks = ["09:00", "09:01", "09:02", "09:05", "09:15", "09:30", "10:00", "11:00", "12:00", "14:00", "17:00", "23:00"]
for com in COMS:
    m = mcx_min(com); m = m[(m.index >= LO) & (m.index <= HI + pd.Timedelta(days=1))]
    hr = HR[HR.com == com]
    days = sorted(set(m.index.normalize()))
    rec = []
    for D in days:
        if D not in hr.index: continue
        h = hr.loc[D]
        md = m[m.index.normalize() == D]
        prev = m[m.index < D]
        if len(prev) == 0 or len(md) < 100: continue
        c_prev = prev.iloc[-1]
        fair = h.us_on + (h.inr_on if np.isfinite(h.inr_on) else 0)
        r = dict(date=D, fair=fair, first=md.index[0].strftime("%H:%M"))
        for t in marks:
            tt = D + pd.Timedelta(hours=int(t[:2]), minutes=int(t[3:]))
            v = md[md.index <= tt]
            r[t] = np.log(v.iloc[-1] / c_prev) - fair if len(v) and (tt - v.index[-1]) < pd.Timedelta(minutes=5) else np.nan
        r["close"] = np.log(md.iloc[-1] / c_prev) - fair
        rec.append(r)
    R = pd.DataFrame(rec)
    print(com, len(R), "first-minute times:", R["first"].value_counts().head(3).to_dict())
    # residual decay: mean |res| and regression slope of res(t) on res(09:01)
    base = R["09:01"]
    out = {}
    for t in marks[1:] + ["close"]:
        ok = base.notna() & R[t].notna()
        b = np.polyfit(base[ok], R[t][ok], 1)[0] if ok.sum() > 5 else np.nan
        out[t] = f"{1e4*R[t].abs().median():.0f}bp/b={b:.2f}"
    print("   ", out)
