"""R209 data (PREREG.md section 1): the input table from the dhan-data branch's files (index and VIX minutes, the nearest
expiry's rolling option chain). Untrusted data: read with pandas only, run with `python3 -I`.

    python3 -I research/hunt/r209_direction_brain/r209data.py build    # -> ~/.cache/iraalgo/r209/table.parquet
"""
from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pandas as pd

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r209cfg as K  # noqa: E402
import r209lib as L  # noqa: E402

N = K.NROWS
OPEN_MIN = 9 * 60 + 15
YEARS = range(2021, 2027)


def _minutes(df: pd.DataFrame) -> pd.DataFrame:
    ts = pd.to_datetime(df.ts)
    ts = ts.dt.tz_convert("Asia/Kolkata") if ts.dt.tz is not None else ts
    df = df.assign(day=ts.dt.date, r=(ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(int))
    return df[(df.r >= 0) & (df.r < N) & (df.day >= K.FIRST_DAY) & (df.day <= K.LAST_DAY)]


def candles(path_of) -> dict:
    """{day: (4, 375)} O H L C from one symbol's 1-minute candle files (the first print of a minute kept)."""
    parts = [pd.read_parquet(p, columns=["ts", "open", "high", "low", "close"]) for p in (path_of(y) for y in YEARS) if p.exists()]
    df = _minutes(pd.concat(parts, ignore_index=True)).drop_duplicates(["day", "r"], keep="first")
    out = {}
    for d, g in df.groupby("day", sort=True):
        a = np.full((4, N), np.nan)
        for i, c in enumerate(("open", "high", "low", "close")):
            a[i, g.r.to_numpy()] = g[c].to_numpy(float)
        out[d] = a
    return out


def chain_series(und: str, series: str) -> dict:
    """{day: {key: (375,)}} of one series (WEEK or MONTH) of one index."""
    sides = {}
    for side in ("CALL", "PUT"):
        parts = []
        for y in YEARS:
            p = K.SRC / "options" / und / series / side / f"{y}.parquet"
            if p.exists():
                parts.append(pd.read_parquet(p, columns=["ts", "offset", "iv", "oi", "close", "volume", "spot"]))
        if not parts:
            return {}
        df = _minutes(pd.concat(parts, ignore_index=True))
        df = df[(df.offset >= -K.OFFS) & (df.offset <= K.OFFS)].drop_duplicates(["day", "r", "offset"], keep="first")
        df.loc[~(df.iv > 0), "iv"] = np.nan
        sides[side] = df
    out = {}
    c, p = sides["CALL"], sides["PUT"]
    keys = set(c.day.unique()) & set(p.day.unique())
    cg, pg = dict(tuple(c.groupby("day"))), dict(tuple(p.groupby("day")))
    for d in sorted(keys):
        a = {k: np.full(N, np.nan) for k in L.OPT_KEYS}
        x, z = cg[d], pg[d]

        def put(arr, frame, col, mask=None):
            f = frame if mask is None else frame[mask]
            arr[f.r.to_numpy()] = f[col].to_numpy(float)
        put(a["civ"], x, "iv", x.offset == 0)
        put(a["piv"], z, "iv", z.offset == 0)
        put(a["skew_c"], x, "iv", x.offset == K.SKEW_OFF)
        put(a["skew_p"], z, "iv", z.offset == -K.SKEW_OFF)
        for key, frame, col in (("coi", x, "oi"), ("poi", z, "oi"), ("cvol", x, "volume"), ("pvol", z, "volume")):
            s = frame.groupby("r")[col].sum(min_count=1)
            a[key][s.index.to_numpy()] = s.to_numpy(float)
        c0 = np.full(N, np.nan)
        p0 = np.full(N, np.nan)
        put(c0, x, "close", x.offset == 0)
        put(p0, z, "close", z.offset == 0)
        a["strad"] = c0 + p0
        put(a["spot"], x, "spot", x.offset == 0)
        out[d] = a
    return out


def chains(und: str) -> dict:
    """The nearest expiry: WEEK when it has rows that day, else MONTH."""
    w, m = chain_series(und, "WEEK"), chain_series(und, "MONTH")
    return {d: (w[d] if d in w else m[d]) for d in sorted(set(w) | set(m))}


def build():
    K.OUT.mkdir(parents=True, exist_ok=True)
    idx = {u: candles(lambda y, u=u: K.SRC / "candles" / "minute" / "IDX_I" / u / f"{y}.parquet") for u in K.UNDS}
    print("index days", {u: len(v) for u, v in idx.items()}, flush=True)
    vix = candles(lambda y: K.SRC / "candles" / "minute" / "IDX_I" / K.VIX / f"{y}.parquet")
    print("vix days", len(vix), flush=True)
    opt = {}
    for u in K.UNDS:
        opt[u] = chains(u)
        print("chain", u, len(opt[u]), min(opt[u]) if opt[u] else None, flush=True)
    df = L.build(idx, vix, opt)
    df.to_parquet(K.OUT / "table.parquet", index=False)
    print("table", df.shape, df.day.min(), df.day.max(), flush=True)


if __name__ == "__main__":
    {"build": build}.get((sys.argv[1:] or [""])[0], lambda: sys.exit(__doc__))()
