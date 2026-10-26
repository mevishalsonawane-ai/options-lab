"""R113 adapter helper (26 Oct 2026): rebuild M3's raw option/spot files (research/hunt/m3/fetch_mcx.py layout) from the
Drive lake's Dhan rolling-option minutes (options_rolling_1m/opt_<SYM>_MONTH_<year>.parquet), so M3/M4/R2 code can run
unchanged. Same source as M3's fetch (Dhan rollingoption, near month, ATM-3..ATM+3, CALL+PUT, spot on each row); values
cast to float32 as M3 stored them. Spot comes from the ATM call, else the ATM put (as fetch_mcx.py). Nothing here
changes any rule."""
from __future__ import annotations
import os
from pathlib import Path
import numpy as np, pandas as pd

LAKE = Path(os.path.expanduser("~/.cache/iraalgo/lake"))
RAW = Path(os.path.expanduser("~/.cache/iraalgo/r113/mcx_raw"))


def build(sym: str, end: str = "2026-10-09") -> Path:
    """Write RAW/opt_<sym>.parquet and RAW/spot_<sym>.parquet (rows before `end`, IST), once."""
    RAW.mkdir(parents=True, exist_ok=True)
    out, spo = RAW / f"opt_{sym}.parquet", RAW / f"spot_{sym}.parquet"
    if out.exists() and spo.exists():
        return RAW
    parts = [pd.read_parquet(f) for f in sorted(LAKE.glob(f"opt_{sym}_MONTH_20*.parquet"))]
    if not parts:
        raise FileNotFoundError(f"no lake option files for {sym}")
    d = pd.concat(parts, ignore_index=True)
    d = d[d.ts < pd.Timestamp(end, tz="Asia/Kolkata")]
    d["k"] = d.offset.astype(np.int8)
    d["cp"] = np.where(d.side.astype(str) == "C", 0, 1).astype(np.int8)
    d = d.drop_duplicates(["ts", "k", "cp"]).sort_values(["k", "cp", "ts"])
    sp = d[d.k == 0][["ts", "spot", "cp"]].rename(columns={"cp": "src"}).sort_values(["ts", "src"]).drop_duplicates("ts")
    sp = sp[["ts", "spot"]].copy(); sp["spot"] = sp.spot.astype("float32")
    big = d[["ts", "open", "high", "low", "close", "iv", "volume", "oi", "strike", "k", "cp"]].copy()
    for c in ("open", "high", "low", "close", "iv", "volume", "oi", "strike"):
        big[c] = big[c].astype("float32")
    big.to_parquet(out, index=False)
    sp.to_parquet(spo, index=False)
    return RAW
