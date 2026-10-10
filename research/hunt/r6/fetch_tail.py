"""R6 Dhan top-up (1 req/s, token never printed). Everything else was already local (M3/STRAD-CRUDE/R3 caches).
 (a) rollingoption near-month ATM-3..ATM+3 CALL/PUT 1-min with spot, 2026-10-01 -> 2026-10-10 for
     NATURALGAS, SILVERM, NATGASMINI (adds 8-9 Oct to M3's files; GOLDM tail is in R3, crude in STRAD-CRUDE cache)
 (b) live 1-min futures OHLC for SILVERMIC (front contracts) if the security id resolves from the scrip master in scratchpad.
-> scratchpad/hunt/r6/raw/opt_tail.parquet"""
import sys, datetime as dt
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
import pandas as pd, numpy as np
OUT = S / "hunt" / "r6" / "raw"
c = F.Creds(); cl, pc = F.Client(c.headers(), c.client_id), F.Pacer(1.0, 3000, name="r6")
UND = {"NATURALGAS": 401, "SILVERM": 122, "CRUDEOIL": 294}
REQ = ["open", "high", "low", "close", "iv", "volume", "oi", "strike", "spot"]
allp = []
for sym, sid in UND.items():
    for k in [0, -1, 1, -2, 2, -3, 3]:
        strike = "ATM" if k == 0 else f"ATM{k:+d}"
        for side in ("CALL", "PUT"):
            pl = {"securityId": str(sid), "exchangeSegment": "MCX_COMM", "instrument": "OPTFUT", "expiryFlag": "MONTH",
                  "expiryCode": 1, "strike": strike, "drvOptionType": side, "requiredData": REQ,
                  "fromDate": "2026-10-01", "toDate": "2026-10-10", "interval": 1}
            try:
                js = cl.post("/charts/rollingoption", pl, pc)
                x = (js.get("data") or {}).get("ce" if side == "CALL" else "pe")
                df = F.arrays_to_df(x) if x else pd.DataFrame()
            except Exception as e:
                print("ERR", sym, strike, side, safe(str(e))[:150], flush=True); df = pd.DataFrame()
            print(sym, strike, side, len(df), "" if not len(df) else df.ts.max(), flush=True)
            if len(df):
                df["k"] = np.int8(k); df["cp"] = np.int8(0 if side == "CALL" else 1); df["sym"] = sym; allp.append(df)
pd.concat(allp, ignore_index=True).to_parquet(OUT / "opt_tail.parquet", index=False)
print("done", flush=True)
