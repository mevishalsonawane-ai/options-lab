"""R190 data (PREREG.md section 1): one flat folder in the Drive lake's own layout, so R147's Night builder and R51's
stores read it unchanged.

    python3 -I research/hunt/r190_best_hunt/r190data.py lake     # build ~/.cache/iraalgo/r190/lake from raw + dhan
    python3 -I research/hunt/r190_best_hunt/r190data.py check    # coverage only (days per file; no prices summarised)

- Year files are linked as they are.
- A 2026 file is joined with its fill parts (`*_fill...`, `*_gap1113`), keeping the first print of each minute
  (instrument keys: index -> ts; options -> ts, side, offset; stocks -> ts, symbol).
- BANKNIFTY has only monthly options from 2025, so its MONTH 2026 file is joined with the WEEK gap part too.
- `opt_NIFTY_WEEK_2020.parquet` is written from dhan-data's NIFTY WEEK CALL / PUT files: every row before the lake's
  first option day (2 Aug 2021), in the lake's columns (side 'C' / 'P').
The data are untrusted: read with pandas only, run with `python3 -I`, never from inside the data folder.
"""
from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r190cfg as K  # noqa: E402

import pandas as pd  # noqa: E402

KEYS = {"index": ["ts"], "opt": ["ts", "side", "offset"], "equity": ["ts", "symbol"]}


def _link(src: Path, dst: Path):
    if dst.exists() or dst.is_symlink():
        dst.unlink()
    dst.symlink_to(src)


def join(parts: list, keys: list) -> pd.DataFrame:
    """Concatenate parts in order and keep the first row of each key (the main file wins over a fill part)."""
    df = pd.concat([pd.read_parquet(p) for p in parts], ignore_index=True)
    for c in df.columns:
        if str(df[c].dtype) == "category":
            df[c] = df[c].astype(str)
    df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
    return df.drop_duplicates(keys, keep="first").sort_values(keys).reset_index(drop=True)


def dhan_nifty_week() -> pd.DataFrame:
    """dhan-data NIFTY WEEK rows before the lake's first option day, in the lake's columns."""
    out = []
    for side, s in (("CALL", "C"), ("PUT", "P")):
        for y in (2020, 2021):
            df = pd.read_parquet(K.DHAN / "options" / "NIFTY" / "WEEK" / side / f"{y}.parquet")
            df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
            df = df[df.ts.dt.date < K.LAKE_OPT_START]
            df["side"] = s
            out.append(df)
    df = pd.concat(out, ignore_index=True)
    for c in ("open", "high", "low", "close", "strike", "spot"):
        df[c] = df[c].astype("float64")
    df = df[["offset", "side", "open", "high", "low", "close", "iv", "volume", "strike", "oi", "spot", "ts"]]
    return df.drop_duplicates(["ts", "side", "offset"], keep="first").sort_values(["ts", "side", "offset"]).reset_index(drop=True)


def build_lake():
    L = K.LAKE
    (L / "equity_1m").mkdir(parents=True, exist_ok=True)
    raw = K.RAW
    # index minutes
    for p in sorted((raw / "index_1m").glob("index_1m_*_20??.parquet")):
        _link(p, L / p.name)
    for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX", "INDIAVIX"):
        parts = [raw / "index_1m" / f"index_1m_{u}_2026.parquet"] + sorted((raw / "index_1m").glob(f"index_1m_{u}_2026_*.parquet"))
        dst = L / f"index_1m_{u}_2026.parquet"
        if dst.is_symlink():
            dst.unlink()
        join(parts, KEYS["index"]).to_parquet(dst, index=False)
    # options
    for p in sorted((raw / "options_rolling_1m").glob("opt_*_20??.parquet")):
        _link(p, L / p.name)
    for u in ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"):
        gap = raw / "options_rolling_1m" / f"opt_{u}_WEEK_2026_gap1113.parquet"
        series = ("WEEK", "MONTH") if u == "BANKNIFTY" else ("WEEK",)
        for s in series:
            dst = L / f"opt_{u}_{s}_2026.parquet"
            if dst.is_symlink():
                dst.unlink()
            join([raw / "options_rolling_1m" / f"opt_{u}_{s}_2026.parquet", gap], KEYS["opt"]).to_parquet(dst, index=False)
    dhan_nifty_week().to_parquet(L / "opt_NIFTY_WEEK_2020.parquet", index=False)
    # stocks (Night's breadth)
    for p in sorted((raw / "equity_1m").glob("equity_1m_20??.parquet")):
        _link(p, L / "equity_1m" / p.name)
    dst = L / "equity_1m" / "equity_1m_2026.parquet"
    if dst.is_symlink():
        dst.unlink()
    join([raw / "equity_1m" / "equity_1m_2026.parquet", raw / "equity_1m" / "equity_1m_2026_fill1013-1113.parquet"],
         KEYS["equity"]).to_parquet(dst, index=False)
    # daily closes (the overlay's 50-day average and VIX percentile)
    d = pd.concat([pd.read_parquet(p) for p in sorted((raw / "index_daily").glob("*.parquet"))], ignore_index=True)
    d = d.drop_duplicates(["symbol", "date"], keep="first").sort_values(["symbol", "date"])
    d.to_parquet(L / "index_daily.parquet", index=False)
    print("lake built", L, flush=True)


def check():
    """Sessions per file (coverage only)."""
    for p in sorted(K.LAKE.glob("*.parquet")):
        df = pd.read_parquet(p, columns=["ts"] if "daily" not in p.name else ["date"])
        c = df.columns[0]
        ts = pd.to_datetime(df[c])
        print(p.name, len(df), ts.min(), ts.max(), ts.dt.date.nunique(), flush=True)


if __name__ == "__main__":
    {"lake": build_lake, "check": check}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
