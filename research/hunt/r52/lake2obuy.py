"""Lay the Drive lake's files out the way research/obuy/data.py reads them, so R52 can run the old studies' own obuy
signal functions and engine on the Nov 2024+ data without changing obuy.

    python3 research/hunt/r52/lake2obuy.py <OBUY_DATA dir>      # reads $IRAALGO_LAKE (flat folder of lake Parquet)

Writes (all derived, outside git):
    options/<U>/<WEEK|MONTH>/<CALL|PUT>/<YYYY>.parquet   ts (UTC, ms), offset, strike, spot, open..close, volume, oi, iv
    candles/minute/IDX_I/<U|INDIA_VIX>/<YYYY>.parquet     ts (IST), open, high, low, close
    candles/daily/IDX_I/INDIA_VIX.parquet                 ts (IST midnight), open, high, low, close
Years 2024-2026 only: 2024 sessions are warm-up for indicators; no trade before 20 Nov 2024 is ever kept (r52run.py).
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

import r52cfg as K

YEARS = (2024, 2025, 2026)
COLS = ["ts", "offset", "strike", "spot", "open", "high", "low", "close", "volume", "oi", "iv"]


def convert(out: Path, unds=K.UNDS, years=YEARS) -> None:
    for u in unds:
        for ser in ("WEEK", "MONTH"):
            for y in years:
                p = K.LAKE / f"opt_{u}_{ser}_{y}.parquet"
                if not p.exists():
                    continue
                df = pd.read_parquet(p)
                df["ts"] = df.ts.dt.tz_convert("UTC").astype("datetime64[ms, UTC]")
                side = df.side.astype(str).str[0].str.upper()
                for s, name in (("C", "CALL"), ("P", "PUT")):
                    d = out / "options" / u / ser / name
                    d.mkdir(parents=True, exist_ok=True)
                    df.loc[side == s, COLS].reset_index(drop=True).to_parquet(d / f"{y}.parquet", index=False)
    for u in tuple(unds) + ("INDIAVIX",):
        name = "INDIA_VIX" if u == "INDIAVIX" else u
        d = out / "candles" / "minute" / "IDX_I" / name
        d.mkdir(parents=True, exist_ok=True)
        for y in years:
            p = K.LAKE / f"index_1m_{u}_{y}.parquet"
            if p.exists():
                pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]).to_parquet(d / f"{y}.parquet", index=False)
    dd = pd.read_parquet(K.LAKE / "index_daily.parquet")
    v = dd[dd.symbol == "INDIAVIX"].copy()
    v["ts"] = pd.to_datetime(v.date).dt.tz_localize("Asia/Kolkata")
    d = out / "candles" / "daily" / "IDX_I"
    d.mkdir(parents=True, exist_ok=True)
    v[["ts", "open", "high", "low", "close"]].to_parquet(d / "INDIA_VIX.parquet", index=False)


if __name__ == "__main__":
    convert(Path(sys.argv[1]).expanduser())
