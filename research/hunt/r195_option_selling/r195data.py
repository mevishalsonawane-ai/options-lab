"""R195 data (PREREG.md section 2): a flat lake folder and R51's Amendment A clean held-contract stores for the five
indices, 2 Aug 2021 - 13 Nov 2026. No network; the raw files were copied with rclone beforehand.

    python3 -I research/hunt/r195_option_selling/r195data.py flat           # join the 2026 files with their fill parts
    python3 -I research/hunt/r195_option_selling/r195data.py build [UND..]  # R51 clean stores -> <OUT>/clean/day_<UND>.pkl

The data are untrusted: read with pandas only, run with `python3 -I`, never from inside the data folder.
"""
from __future__ import annotations

import os
import pickle
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r195cfg as K  # noqa: E402

import pandas as pd  # noqa: E402

KEYS = {"index": ["ts"], "opt": ["ts", "side", "offset"]}


def join(parts: list, keys: list) -> pd.DataFrame:
    """Concatenate parts in order and keep the first row of each key (the main file wins over a fill part)."""
    df = pd.concat([pd.read_parquet(p) for p in parts], ignore_index=True)
    for c in df.columns:
        if str(df[c].dtype) == "category":
            df[c] = df[c].astype(str)
    df["ts"] = pd.to_datetime(df.ts).dt.tz_convert("Asia/Kolkata")
    return df.drop_duplicates(keys, keep="first").sort_values(keys).reset_index(drop=True)


def _link(src: Path, dst: Path):
    if dst.exists() or dst.is_symlink():
        dst.unlink()
    dst.symlink_to(src.resolve())


def build_flat():
    F, R = K.FLAT, K.RAW
    F.mkdir(parents=True, exist_ok=True)
    for p in sorted((R / "index_1m").glob("index_1m_*_20??.parquet")):
        _link(p, F / p.name)
    for u in K.UNDS + ("INDIAVIX",):
        parts = [R / "index_1m" / f"index_1m_{u}_2026.parquet"] + sorted((R / "index_1m").glob(f"index_1m_{u}_2026_*.parquet"))
        dst = F / f"index_1m_{u}_2026.parquet"
        if dst.is_symlink():
            dst.unlink()
        join(parts, KEYS["index"]).to_parquet(dst, index=False)
    for p in sorted((R / "options_rolling_1m").glob("opt_*_WEEK_20??.parquet")):
        _link(p, F / p.name)
    for u in K.UNDS:
        parts = [R / "options_rolling_1m" / f"opt_{u}_WEEK_2026.parquet"] + sorted(
            (R / "options_rolling_1m").glob(f"opt_{u}_WEEK_2026_*.parquet"))
        dst = F / f"opt_{u}_WEEK_2026.parquet"
        if dst.is_symlink():
            dst.unlink()
        join(parts, KEYS["opt"]).to_parquet(dst, index=False)
    print("flat built", F, flush=True)


def r51():
    """R51's config and builder pointed at R195's window and folders (Amendment A cleaning on, stale step off)."""
    os.environ["R51_CLEAN"] = "1"
    import r51cfg as R
    R.LAKE, R.OUT = K.FLAT, K.OUT / "clean"
    R.WARM_START, R.START, R.END = K.WARM_START, K.START, K.END
    R.CLEAN, R.STALE = True, False
    for u, s in K.STEP.items():
        R.STEP[u] = s
    import r51data
    return R, r51data


_CACHE: dict = {}


def load(und: str) -> dict:
    """{day: Day} (R51's Day: idx (4, 375), atm (375,), con {(side, strike): (5, 375) O H L C V}, expiry)."""
    if und not in _CACHE:
        r51()
        with open(K.OUT / "clean" / f"day_{und}.pkl", "rb") as f:
            _CACHE[und] = pickle.load(f)
    return _CACHE[und]


def vix_days() -> dict:
    """{day: (4, 375) India VIX O H L C} on the minute grid."""
    import numpy as np
    parts = [pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"])
             for p in sorted(K.FLAT.glob("index_1m_INDIAVIX_20??.parquet"))]
    x = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(x.ts).dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    x["day"], x["r"] = ts.dt.date, (ts.dt.hour * 60 + ts.dt.minute - K.OPEN_MIN)
    x = x[(x.r >= 0) & (x.r < K.NROWS) & (x.close > 0)].drop_duplicates(["day", "r"])
    out = {}
    for d, g in x.groupby("day", sort=True):
        a = np.full((4, K.NROWS), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.values] = g[c].values
        out[d] = a
    return out


if __name__ == "__main__":
    a = sys.argv[1:] or [""]
    if a[0] == "flat":
        build_flat()
    elif a[0] == "build":
        R, D = r51()
        (K.OUT / "clean").mkdir(parents=True, exist_ok=True)
        for u in a[1:] or K.UNDS:
            print(D.build(u), flush=True)
    else:
        raise SystemExit(__doc__)
