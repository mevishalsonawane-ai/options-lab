"""M3: fetch MCX option minutes (Dhan rollingoption, near month, ATM-3..ATM+3, CALL+PUT, with spot)
for NATURALGAS, NATGASMINI, GOLD, GOLDM, SILVER, SILVERM, COPPER, Aug 2025 -> today.
One compact zstd parquet per commodity: scratchpad/hunt/m3/raw/opt_<SYM>.parquet
columns ts, k (strike offset int8), cp (0 call / 1 put), open, high, low, close, iv, oi, volume, strike (float32);
spot kept only in a separate spot_<SYM>.parquet (from the ATM call, fallback ATM put).
Token is never printed (dhan_io/fetch.redact)."""
import sys, datetime as dt, time
sys.path.insert(0, "/home/user/options-lab/research/hunt/strad_crude")
from dhan_io import F, safe, S
import pandas as pd, numpy as np, logging, concurrent.futures as cf
logging.basicConfig(level=logging.WARNING, format="%(asctime)s %(message)s")

OUT = S / "hunt" / "m3" / "raw"
OUT.mkdir(parents=True, exist_ok=True)
UND = {"NATURALGAS": 401, "GOLDM": 117, "SILVERM": 122, "NATGASMINI": 596, "GOLD": 114, "SILVER": 115, "COPPER": 152}
REQ = ["open", "high", "low", "close", "iv", "volume", "oi", "strike", "spot"]
start, end = dt.date(2025, 7, 25), dt.date(2026, 10, 9)
c = F.Creds()
cl, pc = F.Client(c.headers(), c.client_id), F.Pacer(0.3, 20000, name="m3")
syms = sys.argv[1:] or list(UND)
for sym in syms:
    out = OUT / f"opt_{sym}.parquet"
    if out.exists():
        continue
    allp, spots = [], []
    for k in [0, -1, 1, -2, 2, -3, 3]:
        strike = "ATM" if k == 0 else f"ATM{k:+d}"
        for side in ("CALL", "PUT"):
            wins, d0 = [], start
            while d0 < end:
                wins.append((d0, min(d0 + dt.timedelta(days=25), end))); d0 = wins[-1][1]
            def one(w, strike=strike, side=side):
                d0, d1 = w
                t0 = time.time()
                pl = {"securityId": str(UND[sym]), "exchangeSegment": "MCX_COMM", "instrument": "OPTFUT",
                      "expiryFlag": "MONTH", "expiryCode": 1, "strike": strike, "drvOptionType": side,
                      "requiredData": REQ, "fromDate": d0.isoformat(), "toDate": d1.isoformat(), "interval": 1}
                try:
                    js = cl.post("/charts/rollingoption", pl, pc)
                    d = js.get("data", {}) or {}
                    x = d.get("ce" if side == "CALL" else "pe")
                    df = F.arrays_to_df(x) if x else pd.DataFrame()
                except Exception as e:
                    print("ERR", sym, strike, side, d0, safe(str(e))[:200], flush=True)
                    df = pd.DataFrame()
                print(sym, strike, side, d0, len(df), f"{time.time()-t0:.1f}s", flush=True)
                return df
            with cf.ThreadPoolExecutor(4) as ex:
                parts = [x for x in ex.map(one, wins) if len(x)]
            if not parts:
                continue
            df = pd.concat(parts).drop_duplicates("ts").sort_values("ts")
            if k == 0 and "spot" in df:
                spots.append(df[["ts", "spot"]].assign(src=0 if side == "CALL" else 1))
            df = df.drop(columns=[x for x in ["spot"] if x in df])
            for col in df.columns:
                if col != "ts":
                    df[col] = df[col].astype("float32")
            df["k"] = np.int8(k); df["cp"] = np.int8(0 if side == "CALL" else 1)
            allp.append(df)
    if not allp:
        print("NOTHING", sym, flush=True); continue
    big = pd.concat(allp, ignore_index=True)
    big.to_parquet(out, index=False, compression="zstd", compression_level=9)
    sp = pd.concat(spots).sort_values(["ts", "src"]).drop_duplicates("ts")[["ts", "spot"]]
    sp["spot"] = sp["spot"].astype("float32")
    sp.to_parquet(OUT / f"spot_{sym}.parquet", index=False, compression="zstd", compression_level=9)
    print("saved", sym, len(big), "spot", len(sp), flush=True)
