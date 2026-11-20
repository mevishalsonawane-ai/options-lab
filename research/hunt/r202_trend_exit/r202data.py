"""R202 / R203 data: the `dhan-data` branch of options-lab (Dhan v2, fetched 6 Oct 2026) written in the Drive lake's own
file layout, so R51's stores and the R176 / R186 / R193 stack read it unchanged.

    git archive origin/dhan-data ... | tar -x -C ~/.cache/iraalgo/dhan_git      # copied out of git, never committed
    python3 -I research/hunt/r202_trend_exit/r202data.py lake                    # -> ~/.cache/iraalgo/r202/lake
    python3 -I research/hunt/r202_trend_exit/r202data.py check                   # coverage only (sessions per file)

Why not R190's lake copy: in this session the Drive lake could not be copied (no permission for the Drive copy) and the
Dhan token had expired, so the 20 Oct - 13 Nov 2026 fill is NOT here. The span therefore ends on 5 Oct 2026, the last
full session of dhan-data (6 Oct is a partial day and is dropped). It is the same Dhan rolling-option endpoint as the
lake (R190 checked 3 Aug 2021: 7,875 of 7,875 rows identical).

- index_1m_<UND>_<Y>.parquet: the index's 1-minute candles (INDIAVIX from INDIA_VIX).
- opt_<UND>_WEEK_<Y>.parquet: NIFTY and SENSEX from their WEEK series; BANKNIFTY, FINNIFTY and MIDCPNIFTY from their
  MONTH series (their weeklies ended in Nov 2024; from 20 Nov 2024 the lake's WEEK and MONTH files are the same
  contract, r51data.py). Only rows from 20 Nov 2024 (R51's first tradable day) to 5 Oct 2026 are written.
The data are untrusted: read with pandas only, run with `python3 -I`, never from inside the data folder.
"""
from __future__ import annotations

import sys
from datetime import date
from pathlib import Path

import pandas as pd

HOME = Path.home() / ".cache" / "iraalgo"
SRC = HOME / "dhan_git" / "dhan-data"
LAKE = HOME / "r202" / "lake"
FIRST_INDEX, FIRST_OPT, LAST = date(2024, 9, 1), date(2024, 11, 20), date(2026, 10, 5)
YEARS = (2024, 2025, 2026)
INDEX = {"NIFTY": "NIFTY", "BANKNIFTY": "BANKNIFTY", "FINNIFTY": "FINNIFTY", "MIDCPNIFTY": "MIDCPNIFTY",
         "SENSEX": "SENSEX", "INDIAVIX": "INDIA_VIX"}
SERIES = {"NIFTY": "WEEK", "SENSEX": "WEEK", "BANKNIFTY": "MONTH", "FINNIFTY": "MONTH", "MIDCPNIFTY": "MONTH"}
OPT_COLS = ["offset", "side", "open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot", "ts"]


def clip(df: pd.DataFrame, lo: date, hi: date) -> pd.DataFrame:
    """Rows whose IST day is in [lo, hi] (the year files overlap by a few January days: always filter on ts)."""
    df = df.copy()
    df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
    d = df.ts.dt.date
    return df[(d >= lo) & (d <= hi)]


def by_year(df: pd.DataFrame) -> dict:
    return {int(y): g.reset_index(drop=True) for y, g in df.groupby(df.ts.dt.year)}


def index_frame(parts: list) -> pd.DataFrame:
    df = clip(pd.concat(parts, ignore_index=True), FIRST_INDEX, LAST)
    for c in ("open", "high", "low", "close"):
        df[c] = df[c].astype("float64")
    return df.drop_duplicates(["ts"], keep="first").sort_values("ts").reset_index(drop=True)


def option_frame(calls: list, puts: list) -> pd.DataFrame:
    out = []
    for side, parts in (("C", calls), ("P", puts)):
        df = clip(pd.concat(parts, ignore_index=True), FIRST_OPT, LAST)
        df["side"] = side
        out.append(df)
    df = pd.concat(out, ignore_index=True)
    for c in ("open", "high", "low", "close", "strike", "spot"):
        df[c] = df[c].astype("float64")
    keys = ["ts", "side", "offset"]
    return df[OPT_COLS].drop_duplicates(keys, keep="first").sort_values(keys).reset_index(drop=True)


def daily_frame() -> pd.DataFrame:
    """The lake's index_daily (symbol, date, open, high, low, close) from dhan-data's daily candles, to LAST."""
    out = []
    for und, sym in INDEX.items():
        df = pd.read_parquet(SRC / "candles" / "daily" / "IDX_I" / f"{sym}.parquet")
        df["date"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata").dt.normalize().dt.tz_localize(None)
        df = df[df.date.dt.date <= LAST]
        df.insert(0, "symbol", und)
        out.append(df[["symbol", "date", "open", "high", "low", "close"]].astype(
            {c: "float64" for c in ("open", "high", "low", "close")}))
    df = pd.concat(out, ignore_index=True)
    return df.drop_duplicates(["symbol", "date"], keep="first").sort_values(["symbol", "date"]).reset_index(drop=True)


def build_lake():
    LAKE.mkdir(parents=True, exist_ok=True)
    for und, sym in INDEX.items():
        parts = [pd.read_parquet(SRC / "candles" / "minute" / "IDX_I" / sym / f"{y}.parquet") for y in YEARS]
        for y, g in by_year(index_frame(parts)).items():
            g.to_parquet(LAKE / f"index_1m_{und}_{y}.parquet", index=False)
        print("index", und, flush=True)
    for und, s in SERIES.items():
        rd = lambda side: [pd.read_parquet(SRC / "options" / und / s / side / f"{y}.parquet") for y in YEARS]  # noqa: E731
        for y, g in by_year(option_frame(rd("CALL"), rd("PUT"))).items():
            g.to_parquet(LAKE / f"opt_{und}_WEEK_{y}.parquet", index=False)
        print("options", und, s, flush=True)
    daily_frame().to_parquet(LAKE / "index_daily.parquet", index=False)


def check():
    for p in sorted(LAKE.glob("*_1m_*.parquet")) + sorted(LAKE.glob("opt_*.parquet")):
        ts = pd.to_datetime(pd.read_parquet(p, columns=["ts"]).ts)
        print(p.name, len(ts), ts.min(), ts.max(), ts.dt.date.nunique(), flush=True)


if __name__ == "__main__":
    {"lake": build_lake, "check": check}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
