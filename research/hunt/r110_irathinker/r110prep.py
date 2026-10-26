"""R110 data prep (26 Oct 2026): the research-data release files -> the bar CSVs IraThinker's Java engine reads.

Writes, per index, <OUT>/<IDX>_1m.csv and <OUT>/<IDX>_5m.csv (ts = epoch seconds of the bar START, IST minute bars as
Yahoo stamps them), and VIX_1m.csv / VIX_5m.csv. Run with python -I; the release files are untrusted data (read only).

    python -I research/hunt/r110_irathinker/r110prep.py <release_dir> <out_dir>
"""
from __future__ import annotations

import os
import sys

import numpy as np
import pandas as pd

IST = "Asia/Kolkata"

# index -> (release file(s) holding its "IX" rows, or the extra_series name)
SOURCES = {
    "NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"],
    "BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"],
    "FINNIFTY": ["finnifty_index.parquet"],
    "SENSEX": ["sensex_index.parquet"],
}


def index_minutes(rel: str, files: list[str]) -> pd.DataFrame:
    """The index's 1-minute bars from release files ('IX' rows), deduplicated, session minutes only (09:15-15:29)."""
    parts = []
    for f in files:
        df = pd.read_parquet(os.path.join(rel, f), columns=["ts", "open", "high", "low", "close", "right"])
        parts.append(df[df["right"] == "IX"].drop(columns="right"))
    df = pd.concat(parts).drop_duplicates("ts").sort_values("ts")
    return session(df)


def session(df: pd.DataFrame) -> pd.DataFrame:
    t = df["ts"].dt.hour * 60 + df["ts"].dt.minute
    df = df[(t >= 9 * 60 + 15) & (t <= 15 * 60 + 29)]
    df = df[(df[["open", "high", "low", "close"]] > 0).all(axis=1)]
    return df.reset_index(drop=True)


def vix_minutes(rel: str) -> pd.DataFrame:
    df = pd.read_parquet(os.path.join(rel, "extra_series.parquet"))
    df = df[df["series"] == "INDIAVIX"].drop(columns="series").drop_duplicates("ts").sort_values("ts")
    return session(df)


def to_5m(df: pd.DataFrame) -> pd.DataFrame:
    """5-minute bars starting 09:15, 09:20, ... (Yahoo's grid): open first, high max, low min, close last."""
    m = (df["ts"].dt.hour * 60 + df["ts"].dt.minute - (9 * 60 + 15)) // 5
    key = df["ts"].dt.normalize() + pd.to_timedelta(9 * 60 + 15 + m * 5, unit="m")
    g = df.groupby(key)
    out = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(),
                        "close": g["close"].last()})
    out.index.name = "ts"
    return out.reset_index()


def write(df: pd.DataFrame, path: str) -> None:
    """ts (naive IST) -> epoch seconds; volume 0 (index bars carry none)."""
    # whole seconds whatever the column's unit (FINNIFTY / SENSEX files are microseconds, the others nanoseconds)
    ep = (df["ts"].dt.tz_localize(IST) - pd.Timestamp("1970-01-01", tz="UTC")) // pd.Timedelta(seconds=1)
    out = pd.DataFrame({"ts": ep, "open": df["open"].round(2), "high": df["high"].round(2),
                        "low": df["low"].round(2), "close": df["close"].round(2), "volume": 0})
    out.to_csv(path, index=False)


def main(rel: str, out: str) -> None:
    os.makedirs(out, exist_ok=True)
    for idx, files in SOURCES.items():
        m1 = index_minutes(rel, files)
        write(m1, os.path.join(out, f"{idx}_1m.csv"))
        write(to_5m(m1), os.path.join(out, f"{idx}_5m.csv"))
        print(idx, len(m1), "minutes,", m1["ts"].dt.date.nunique(), "days", m1["ts"].min(), "..", m1["ts"].max())
    v = vix_minutes(rel)
    write(v, os.path.join(out, "VIX_1m.csv"))
    write(to_5m(v), os.path.join(out, "VIX_5m.csv"))
    print("VIX", len(v), "minutes")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
