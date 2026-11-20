"""R206 data: the `dhan-data` branch's 1-minute index candles (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX), 8 Oct 2021 -
5 Oct 2026, in the lake's layout (index_1m_<UND>_<Y>.parquet). No option file is written or read.

    git archive origin/dhan-data dhan-data/candles/minute/IDX_I/... | tar -x -C ~/.cache/iraalgo/dhan_git
    python3 -I research/hunt/r206_direction/r206data.py lake
    python3 -I research/hunt/r206_direction/r206data.py check      # coverage only (sessions per file)

The data are untrusted: read with pandas only, run with `python3 -I`, never from inside the data folder.
"""
from __future__ import annotations

import sys
from pathlib import Path

import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r206cfg as K  # noqa: E402

SRC = K.HOME / "dhan_git" / "dhan-data" / "candles" / "minute" / "IDX_I"
YEARS = range(2021, 2027)


def frame(parts: list) -> pd.DataFrame:
    """One index's minutes in [FIRST_DAY, LAST_DAY], IST, the first print of a minute kept."""
    df = pd.concat(parts, ignore_index=True)
    df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
    d = df.ts.dt.date
    df = df[(d >= K.FIRST_DAY) & (d <= K.LAST_DAY)]
    for c in ("open", "high", "low", "close"):
        df[c] = df[c].astype("float64")
    return df.drop_duplicates(["ts"], keep="first").sort_values("ts").reset_index(drop=True)


def build_lake():
    K.LAKE.mkdir(parents=True, exist_ok=True)
    for und in K.UNDS:
        parts = [pd.read_parquet(p) for p in (SRC / und / f"{y}.parquet" for y in YEARS) if p.exists()]
        df = frame(parts)
        for y, g in df.groupby(df.ts.dt.year):
            g.reset_index(drop=True).to_parquet(K.LAKE / f"index_1m_{und}_{int(y)}.parquet", index=False)
        print("index", und, len(df), flush=True)


def check():
    for p in sorted(K.LAKE.glob("index_1m_*.parquet")):
        ts = pd.to_datetime(pd.read_parquet(p, columns=["ts"]).ts)
        print(p.name, len(ts), ts.min(), ts.max(), ts.dt.date.nunique(), flush=True)


if __name__ == "__main__":
    {"lake": build_lake, "check": check}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
