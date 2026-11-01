"""R144c data access: index minutes and option chains from the flat lake ($IRAALGO_LAKE), cached as pickles in R144C_OUT.

Grid: row r = the 1-minute bar STARTING 09:15 + r (375 rows), NaN where there is no bar. Nothing is forward-filled here.
Option chains come from the lake's rolling ATM-10..+10 files (no expiry column): WEEK = the nearest expiry (after Nov 2024
the BANKNIFTY / FINNIFTY / MIDCPNIFTY WEEK files follow the nearest, monthly, expiry), MONTH = the monthly series.
The lake is read-only data; it is never written to and nothing from it goes into git.
"""
from __future__ import annotations

import os
import pickle
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

CACHE = Path(os.environ.get("IRAALGO_CACHE", "~/.cache/iraalgo")).expanduser()
LAKE = Path(os.environ.get("IRAALGO_LAKE", CACHE / "lake")).expanduser()
OUT = Path(os.environ.get("R144C_OUT", CACHE / "r144c")).expanduser()
N = 375
O, H, L, C, V, OI, IV = range(7)
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}


def row(hh: int, mm: int) -> int:
    return hh * 60 + mm - (9 * 60 + 15)


def _frame(path, cols) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < N)].drop(columns=["ts"])


def index_minutes(und: str) -> dict:
    """{day: (375, 4) O H L C} of the index (INDIAVIX too), every lake year; cached."""
    p = OUT / f"ix_{und}.pkl"
    if p.exists():
        return pickle.loads(p.read_bytes())
    out = {}
    for f in sorted(LAKE.glob(f"index_1m_{und}_*.parquet")):
        df = _frame(f, ["open", "high", "low", "close", "ts"])
        for d, g in df.groupby("d"):
            a = np.full((N, 4), np.nan)
            a[g.m.values] = g[["open", "high", "low", "close"]].values
            out[d] = a
    OUT.mkdir(parents=True, exist_ok=True)
    p.write_bytes(pickle.dumps(out))
    return out


def daily(und: str) -> pd.DataFrame:
    """Daily O H L C from the minutes (the session's own bars), indexed by date, sorted."""
    ix = index_minutes(und)
    rows = []
    for d in sorted(ix):
        a = ix[d]
        ok = ~np.isnan(a[:, 3])
        if ok.sum() < 30:
            continue
        rows.append((d, a[ok, 0][0], np.nanmax(a[:, 1]), np.nanmin(a[:, 2]), a[ok, 3][-1]))
    return pd.DataFrame(rows, columns=["d", "open", "high", "low", "close"]).set_index("d")


def chains(und: str, series: str, days: set) -> dict:
    """{day: {"spot": (375,), (strike, 'C'/'P'): (375, 7) O H L C V OI IV}} for the requested days; cached per call set."""
    days = set(days)
    key = OUT / f"ch_{und}_{series}.pkl"
    have = pickle.loads(key.read_bytes()) if key.exists() else {}
    need = sorted(d for d in days if d not in have)
    if need:
        years = sorted({d.year for d in need})
        for y in years:
            f = LAKE / f"opt_{und}_{series}_{y}.parquet"
            if not f.exists():
                continue
            df = _frame(f, ["offset", "side", "open", "high", "low", "close", "volume", "oi", "iv", "strike", "spot", "ts"])
            df = df[df.d.isin({d for d in need if d.year == y})]
            df["side"] = df.side.astype(str).str[0].str.upper()
            df = df[(df.strike - df.spot).abs() <= 12 * STEP[und]]
            df = df.drop_duplicates(["d", "m", "strike", "side"], keep="first")
            for d, g in df.groupby("d"):
                rec = {}
                sp = np.full(N, np.nan)
                s1 = g.drop_duplicates("m")
                sp[s1.m.values] = s1.spot.values
                rec["spot"] = sp
                for (k, s), h in g.groupby(["strike", "side"]):
                    a = np.full((N, 7), np.nan, dtype=np.float32)
                    a[h.m.values] = h[["open", "high", "low", "close", "volume", "oi", "iv"]].values
                    rec[(float(k), s)] = a
                have[d] = rec
        for d in need:
            have.setdefault(d, None)
        OUT.mkdir(parents=True, exist_ok=True)
        key.write_bytes(pickle.dumps(have))
    return {d: have.get(d) for d in days}


def expiry_days(und: str) -> list:
    """The index's expiry days from R98's calendar (R98_OUT day pickles), else []."""
    p = Path(os.environ.get("R98_OUT", CACHE / "r98")) / f"days_{und}.pkl"
    if not p.exists():
        return []
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "r98"))
    return sorted(d.day for d in pickle.loads(p.read_bytes()))


def period(d: date) -> str:
    """PREREG section 2."""
    if d < date(2022, 12, 1):
        return "early"
    if d <= date(2024, 11, 19):
        return "unseen"
    if d <= date(2026, 3, 31):
        return "design"
    return "final"
