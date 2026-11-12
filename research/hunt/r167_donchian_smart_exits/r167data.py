"""R167 data (PREREG section 8): the day measurements (r167feat.DayCtx) per index, the held contracts' minute OI and India
VIX, read from the lake and the R51 clean stores. Cached in $R167_OUT (never in git).

    python research/hunt/r167_donchian_smart_exits/r167data.py build     # after `R51_CLEAN=1 r51data.py build`

12 Nov 2026.
"""
from __future__ import annotations

import pickle
import sys

import numpy as np
import pandas as pd

import r167cfg as K
import r167feat as F
import r51cfg as K51

_CTX: dict = {}
_OI: dict = {}


def _minute_rows(ts: pd.Series) -> tuple:
    t = pd.to_datetime(ts)
    if t.dt.tz is not None:
        t = t.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    r = (t.dt.hour * 60 + t.dt.minute - K51.OPEN_MIN).astype(int)
    return t.dt.date, r


def vix_days() -> dict:
    """{day: (375,) India VIX closes} from the lake's index_1m_INDIAVIX files."""
    out = {}
    for y in range(K51.WARM_START.year, K51.END.year + 1):
        p = K.LAKE / f"index_1m_INDIAVIX_{y}.parquet"
        if not p.exists():
            continue
        df = pd.read_parquet(p, columns=["ts", "close"])
        d, r = _minute_rows(df["ts"])
        df = df.assign(day=d.values, r=r.values)
        df = df[(df.r >= 0) & (df.r < K51.NROWS)]
        for day, g in df.groupby("day"):
            a = np.full(K51.NROWS, np.nan)
            a[g.r.values] = g.close.values
            out[day] = a
    return out


def build_ctx(und: str, vix: dict) -> dict:
    """{day: DayCtx} for every store day from START (earlier days only warm the measurements up)."""
    import r51data
    days = r51data.load(und)
    keys = sorted(days)
    arrs = [days[d].idx for d in keys]
    first = next((i for i, d in enumerate(keys) if d >= K.START), len(keys))
    ctxs = F.build(arrs, [vix.get(d) for d in keys], first_ctx=first)
    return {d: c for d, c in zip(keys, ctxs) if c is not None}


def build_oi(und: str) -> dict:
    """{(day, side, strike): (375,) OI} from the lake's WEEK option files (the series the stores hold)."""
    out = {}
    for y in range(K.START.year, K51.END.year + 1):
        p = K.LAKE / f"opt_{und}_WEEK_{y}.parquet"
        if not p.exists():
            continue
        o = pd.read_parquet(p, columns=["ts", "side", "strike", "oi"])
        d, r = _minute_rows(o["ts"])
        o = o.assign(day=d.values, r=r.values)
        o = o[(o.r >= 0) & (o.r < K51.NROWS) & (o.day >= K.START) & (o.day <= K51.END)]
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o.drop_duplicates(["day", "side", "strike", "r"])
        for (day, s, k), g in o.groupby(["day", "side", "strike"], sort=False):
            a = np.full(K51.NROWS, np.nan, dtype=np.float32)
            a[g.r.values] = g.oi.values
            out[(day, s, float(k))] = a
    return out


def ctx(und: str) -> dict:
    if und not in _CTX:
        _CTX[und] = pickle.loads((K.OUT / f"ctx_{und}.pkl").read_bytes())
    return _CTX[und]


def oi(und: str) -> dict:
    if und not in _OI:
        p = K.OUT / f"oi_{und}.pkl"
        _OI[und] = pickle.loads(p.read_bytes()) if p.exists() else {}
    return _OI[und]


def oi_of(und: str, day):
    m = oi(und)
    return lambda side, k: m.get((day, side, float(k)))


def build():
    K.OUT.mkdir(parents=True, exist_ok=True)
    vix = vix_days()
    print("VIX days", len(vix), flush=True)
    for und in K51.UNDS:
        c = build_ctx(und, vix)
        (K.OUT / f"ctx_{und}.pkl").write_bytes(pickle.dumps(c, protocol=pickle.HIGHEST_PROTOCOL))
        o = build_oi(und)
        (K.OUT / f"oi_{und}.pkl").write_bytes(pickle.dumps(o, protocol=pickle.HIGHEST_PROTOCOL))
        print(und, "days", len(c), "OI series", len(o), flush=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["build"]:
        build()
    else:
        raise SystemExit(__doc__)
