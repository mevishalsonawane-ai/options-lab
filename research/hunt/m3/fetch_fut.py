"""M3: 1-min futures OHLCV+OI for LIVE MCX contracts (Dhan /charts/intraday; expired contracts return nothing).
-> scratchpad/hunt/m3/raw/fut_live.parquet"""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
import pandas as pd, numpy as np, logging
logging.basicConfig(level=logging.WARNING)
IDS = {"CRUDEOIL OCT": 569900, "CRUDEOILM OCT": 569901, "CRUDEOILM NOV": 573423, "NATURALGAS OCT": 570750,
       "NATGASMINI OCT": 570751, "NATGASMINI NOV": 574320, "GOLD DEC": 495213, "GOLDM NOV": 571445, "GOLDM DEC": 575011,
       "SILVER DEC": 495214, "SILVERM NOV": 483080, "COPPER OCT": 574829}
c = F.Creds(); cl = F.Client(c.headers(), c.client_id); pc = F.Pacer(0.5, 5000, name="m3fut")
parts = []
for name, sid in IDS.items():
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
D = pd.concat(parts, ignore_index=True)
for col in D.columns:
    if col not in ("ts", "contract"):
        D[col] = D[col].astype("float32")
D.to_parquet(S / "hunt" / "m3" / "raw" / "fut_live.parquet", index=False, compression="zstd", compression_level=9)
print("saved", len(D), flush=True)
