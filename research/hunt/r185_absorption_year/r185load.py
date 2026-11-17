"""R185 loader (PREREG section 1): the lake's index 1-minute bars and rolling WEEK options (2024-2026, to 19 Oct 2026) plus
the gap files fetched by r185fetch.py (20 Oct - 13 Nov 2026) -> one r185lib.Day per trading day (>= 300 index minutes).

    days = load(und)   # {iso date: Day}, cached as a pickle in ~/.cache/iraalgo/r185/ (outside git)

The rolling series are re-keyed by their actual `strike` (any offset), so a bought contract is followed by its strike as
the ATM moves. Activity V = the summed volume of offsets -2..+2, calls and puts. Expiry days: the r25 / r147 rule.
Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import os
import pickle
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

import r185lib as L

LAKE = Path(os.environ.get("IRAALGO_LAKE", os.path.expanduser("~/.cache/iraalgo/lake")))
CACHE = Path(os.path.expanduser("~/.cache/iraalgo/r185"))
START = date(2024, 11, 20)
MIN_MINUTES = 300
TV_MAX, TV_JUMP, TV_BAR = 0.6, 2.0, 360      # 15:15
YEARS = (2024, 2025, 2026)


def _ts(x: pd.Series) -> pd.Series:
    return pd.to_datetime(x, utc=True).dt.tz_convert("Asia/Kolkata")


def read_index(und: str) -> pd.DataFrame:
    fs = [LAKE / f"index_1m_{und}_{y}.parquet" for y in YEARS] + [LAKE / f"index_1m_{und}_2026_gap1113.parquet"]
    parts = []
    for f in fs:
        if f.exists():
            x = pd.read_parquet(f, columns=["open", "high", "low", "close", "ts"])
            x["ts"] = _ts(x.ts)
            parts.append(x)
    df = pd.concat(parts, ignore_index=True).drop_duplicates("ts", keep="last")
    df["d"] = df.ts.dt.date
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute - (9 * 60 + 15)
    return df[(df.d >= START) & (df.m >= 0) & (df.m < L.N)]


def read_options(und: str) -> pd.DataFrame:
    fs = [LAKE / f"opt_{und}_WEEK_{y}.parquet" for y in YEARS] + [LAKE / f"opt_{und}_WEEK_2026_gap1113.parquet"]
    cols = ["offset", "side", "open", "high", "low", "close", "volume", "strike", "spot", "ts"]
    parts = []
    for f in fs:
        if not f.exists():
            continue
        x = pd.read_parquet(f, columns=cols, filters=[("ts", ">=", pd.Timestamp(START, tz="Asia/Kolkata"))])
        x["ts"] = _ts(x.ts)
        x["side"] = x.side.astype(str)
        parts.append(x)
    df = pd.concat(parts, ignore_index=True).drop_duplicates(["offset", "side", "ts"], keep="last")
    df["d"] = df.ts.dt.date
    df["m"] = df.ts.dt.hour * 60 + df.ts.dt.minute - (9 * 60 + 15)
    return df[(df.d >= START) & (df.m >= 0) & (df.m < L.N)]


def step_of(o: pd.DataFrame) -> float:
    """The day's most common strike gap between ATM and ATM+1 at the same minute (same side)."""
    a = o[o.offset == 0][["side", "m", "strike"]]
    b = o[o.offset == 1][["side", "m", "strike"]]
    j = a.merge(b, on=["side", "m"], suffixes=("0", "1"))
    g = (j.strike1 - j.strike0).round(2)
    g = g[g > 0]
    return float(g.mode().iloc[0]) if len(g) else float("nan")


def tv_1515(o: pd.DataFrame, close_1515: float) -> float:
    x = o[(o.offset == 0) & (o.m == TV_BAR)]
    c = x[x.side == "C"].close
    p = x[x.side == "P"].close
    if not len(c) or not len(p):
        return float("nan")
    k = float(x.strike.iloc[0])
    spot = float(x.spot.iloc[0]) if np.isfinite(x.spot.iloc[0]) and x.spot.iloc[0] > 0 else close_1515
    return (float(c.iloc[0]) + float(p.iloc[0]) - abs(spot - k)) / spot * 100


def expiry_days(tv: dict) -> set:
    """r25 / r147: time value < 0.6% at 15:15 and the next session's > 2x it. The last day counts as not an expiry."""
    ds = sorted(tv)
    out = set()
    for a, b in zip(ds, ds[1:]):
        if np.isfinite(tv[a]) and np.isfinite(tv[b]) and tv[a] < TV_MAX and tv[b] > TV_JUMP * max(tv[a], 1e-6):
            out.add(a)
    return out


def build_days(und: str) -> tuple[dict, dict]:
    idx, opt = read_index(und), read_options(und)
    og = dict(tuple(opt.groupby("d")))
    raw, tv, quality = {}, {}, {}
    for d, g in idx.groupby("d"):
        if len(g) < MIN_MINUTES:
            quality[d.isoformat()] = dict(index_minutes=int(len(g)), dropped="short day")
            continue
        o = og.get(d)
        if o is None or not len(o):
            quality[d.isoformat()] = dict(index_minutes=int(len(g)), dropped="no options")
            continue
        arr = {k: np.full(L.N, np.nan) for k in ("o", "h", "l", "c")}
        m = g.m.to_numpy()
        for k, col in (("o", "open"), ("h", "high"), ("l", "low"), ("c", "close")):
            arr[k][m] = g[col].to_numpy(float)
        near = o[o.offset.between(-2, 2)]
        V = np.zeros(L.N)
        np.add.at(V, near.m.to_numpy(), near.volume.to_numpy(float))
        opts = {}
        for side in ("C", "P"):
            s = o[o.side == side].drop_duplicates(["strike", "m"], keep="last")
            book = {}
            for k, gg in s.groupby("strike"):
                a = [np.full(L.N, np.nan, dtype=np.float32) for _ in range(5)]
                mm = gg.m.to_numpy()
                for i, col in enumerate(("open", "high", "low", "close", "volume")):
                    a[i][mm] = gg[col].to_numpy(np.float32)
                a[4] = np.nan_to_num(a[4])
                book[float(k)] = tuple(a)
            opts[side] = book
        c1515 = arr["c"][TV_BAR] if np.isfinite(arr["c"][TV_BAR]) else np.nanmean(arr["c"])
        tv[d] = tv_1515(o, float(c1515))
        raw[d] = (arr, V, opts, step_of(o))
        quality[d.isoformat()] = dict(index_minutes=int(len(g)), option_rows=int(len(o)),
                                      near_atm2_rows=int(len(near)), zero_activity_minutes=int((V <= 0).sum()))
    exp = expiry_days(tv)
    days = {}
    for d, (arr, V, opts, step) in raw.items():
        days[d.isoformat()] = L.Day(day=d, und=und, o=arr["o"], h=arr["h"], l=arr["l"], c=arr["c"], V=V, step=step,
                                    lot=L.lot_of(und, d), expiry=d in exp, opt=opts)
    meta = dict(days=len(days), expiry_days=sorted(x.isoformat() for x in exp), quality=quality,
                steps=sorted({float(x[3]) for x in raw.values()}))
    return days, meta


def load(und: str, cache: Path = CACHE) -> tuple[dict, dict]:
    cache.mkdir(parents=True, exist_ok=True)
    p = cache / f"days_{und}.pkl"
    if p.exists():
        with open(p, "rb") as f:
            return pickle.load(f)
    out = build_days(und)
    with open(p, "wb") as f:
        pickle.dump(out, f, protocol=pickle.HIGHEST_PROTOCOL)
    return out
