"""M2: Dhan /charts/rollingoption (expired MCX monthly options, from Aug 2025), 60-min bars, ATM and ATM±1,
near (code 1) and next (code 2) month, for GOLDM, SILVERM, NATGASMINI, NATURALGAS, CRUDEOIL, CRUDEOILM.
One parquet per unit under scratchpad/hunt/m2/data/opts/. Token never printed."""
import sys, os, time, datetime as dt, concurrent.futures as cf
sys.path.insert(0, "/home/user/options-lab/research/hunt/nn_crude")
import pandas as pd
from dhan import Dhan, redact
OUT = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/m2/data/opts"
os.makedirs(OUT, exist_ok=True)
UND = {"GOLDM": 117, "SILVERM": 122, "NATGASMINI": 596, "NATURALGAS": 401, "CRUDEOIL": 294, "CRUDEOILM": 556, "GOLD": 114, "SILVER": 115}
RD = ["open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot"]

def unit(sym, code, off, typ):
    strike = "ATM" if off == 0 else f"ATM{off:+d}"
    fn = f"{OUT}/{sym}_c{code}_{off:+d}_{typ[0]}.parquet"
    if os.path.exists(fn): return
    d = Dhan(); parts = []; start = dt.date(2025, 7, 1)
    while start <= dt.date.today():
        end = start + dt.timedelta(days=30)
        st, js, err = d.post("/charts/rollingoption", dict(securityId=UND[sym], exchangeSegment="MCX_COMM", instrument="OPTFUT",
                             expiryFlag="MONTH", expiryCode=code, strike=strike, drvOptionType=typ, requiredData=RD,
                             fromDate=start.isoformat(), toDate=end.isoformat(), interval="60"), gap=1.0)
        v = ((js or {}).get("data") or {}).get("ce" if typ == "CALL" else "pe") or {}
        n = len(v.get("timestamp") or [])
        if err: print(sym, code, strike, typ, start, redact(err)[:120], flush=True)
        if n:
            df = pd.DataFrame({k: v[k] for k in RD if v.get(k)})
            df["ts"] = pd.to_datetime(v["timestamp"], unit="s", utc=True).tz_convert("Asia/Kolkata").tz_localize(None)
            parts.append(df)
        start = end
    if parts:
        df = pd.concat(parts, ignore_index=True).drop_duplicates("ts").sort_values("ts")
        df["off"] = off; df["type"] = typ[0]; df["code"] = code; df["sym"] = sym
        df.to_parquet(fn, compression="zstd")
        print("saved", sym, code, strike, typ, len(df), df.ts.iloc[0], flush=True)
    else:
        print("EMPTY", sym, code, strike, typ, flush=True)

units = [(s, c, o, t) for s in UND for c in (1, 2) for o in (0, -1, 1) for t in ("CALL", "PUT")]
with cf.ThreadPoolExecutor(4) as ex:
    for f in [ex.submit(unit, *u) for u in units]:
        try: f.result()
        except Exception as e: print("ERR", type(e).__name__, redact(e), flush=True)
print("done")
