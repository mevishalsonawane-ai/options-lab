"""R3 Dhan fetch (gentle pacing, 1 req/s, token never printed):
 (a) GOLDPETAL live futures (OCT 571306, NOV 574841) 1-min, 2025-08-01 -> 2026-10-09 (Dhan serves live contracts only)
 (b) GOLDM rollingoption near-month ATM-3..ATM+3 CALL/PUT 1-min with spot, 2026-09-20 -> 2026-10-09 (adds 8-9 Oct to M3's file)
Outputs scratchpad/hunt/r3/raw/{petal_live.parquet, goldm_opt_tail.parquet}"""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
import pandas as pd, numpy as np
OUT = S / "hunt" / "r3" / "raw"
c = F.Creds(); cl, pc = F.Client(c.headers(), c.client_id), F.Pacer(1.0, 3000, name="r3")
parts = []
for name, sid in {"GOLDPETAL OCT": 571306, "GOLDPETAL NOV": 574841}.items():
    d0 = dt.date(2025, 8, 1)
    while d0 < dt.date(2026, 10, 9):
        d1 = min(d0 + dt.timedelta(days=88), dt.date(2026, 10, 9))
        pl = {"securityId": str(sid), "exchangeSegment": "MCX_COMM", "instrument": "FUTCOM", "interval": 1, "oi": True,
              "fromDate": d0.isoformat(), "toDate": d1.isoformat()}
        try:
            df = F.arrays_to_df(cl.post("/charts/intraday", pl, pc))
        except Exception as e:
            print("ERR", name, d0, safe(str(e))[:150], flush=True); df = pd.DataFrame()
        if len(df):
            df["contract"] = name; parts.append(df)
        print(name, d0, len(df), flush=True)
        d0 = d1
if parts:
    pd.concat(parts, ignore_index=True).to_parquet(OUT / "petal_live.parquet", index=False)
REQ = ["open", "high", "low", "close", "iv", "volume", "oi", "strike", "spot"]
allp = []
for k in [0, -1, 1, -2, 2, -3, 3]:
    strike = "ATM" if k == 0 else f"ATM{k:+d}"
    for side in ("CALL", "PUT"):
        pl = {"securityId": "117", "exchangeSegment": "MCX_COMM", "instrument": "OPTFUT", "expiryFlag": "MONTH", "expiryCode": 1,
              "strike": strike, "drvOptionType": side, "requiredData": REQ, "fromDate": "2026-09-20", "toDate": "2026-10-10", "interval": 1}
        try:
            js = cl.post("/charts/rollingoption", pl, pc)
            x = (js.get("data") or {}).get("ce" if side == "CALL" else "pe")
            df = F.arrays_to_df(x) if x else pd.DataFrame()
        except Exception as e:
            print("ERR", strike, side, safe(str(e))[:150], flush=True); df = pd.DataFrame()
        print(strike, side, len(df), "" if not len(df) else df.ts.max(), flush=True)
        if len(df):
            df["k"] = np.int8(k); df["cp"] = np.int8(0 if side == "CALL" else 1); allp.append(df)
pd.concat(allp, ignore_index=True).to_parquet(OUT / "goldm_opt_tail.parquet", index=False)
print("done", flush=True)
