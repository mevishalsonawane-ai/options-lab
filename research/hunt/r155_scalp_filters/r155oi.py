"""R155 OI store: per index and session, the option chain's OI around ATM (for F11 OIB, F12 PCR, F13 FT3).

    python research/hunt/r155_scalp_filters/r155oi.py build [UND ...]     # writes $R155_OUT/oi_<UND>.pkl

Per session:
  pcr   (375,) put OI / call OI summed over the offsets -10..+10 printed at that minute (NaN when a side has none)
  con   {(side, strike): (2, 375) float32 close, OI} for the strikes within 4 steps of the session's ATM range
Rows are the lake's nearest-expiry (WEEK) rolling files from Aug 2021 to 19 Oct 2026. Kept as R51 keeps them: rows
whose strike is more than 12 steps from their spot are dropped, and (foreign rows, lakeclean step 1) rows whose spot is
more than 3% from the median spot of that minute. A zero or missing OI is NaN (never filled).
"""
from __future__ import annotations

import pickle
import sys
from datetime import date

import numpy as np
import pandas as pd

import r155cfg as K

NROWS, OPEN_MIN = 375, 9 * 60 + 15
KEEP_STEPS = 4


def _lake():
    import r51cfg
    return r51cfg.LAKE, r51cfg.STEP


def read_year(path, lo: date, hi: date) -> pd.DataFrame:
    o = pd.read_parquet(path, columns=["ts", "offset", "side", "strike", "spot", "close", "oi"])
    ts = pd.to_datetime(o.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    o["day"] = ts.dt.date
    o["r"] = (ts.dt.hour * 60 + ts.dt.minute - OPEN_MIN).astype(np.int32)
    o = o[(o.r >= 0) & (o.r < NROWS) & (o.day >= lo) & (o.day <= hi)].drop(columns=["ts"])
    o["side"] = o.side.astype(str).str[0].str.upper()
    return o


def clean(o: pd.DataFrame, step: float) -> pd.DataFrame:
    o = o[(o.strike - o.spot).abs() <= 12 * step]
    med = o.groupby(["day", "r"]).spot.transform("median")
    o = o[(o.spot / med - 1).abs() <= 0.03]
    o = o.copy()
    o.loc[~(o.oi > 0), "oi"] = np.nan
    return o.drop_duplicates(["day", "r", "side", "strike"], keep="first")


def pcr_of(g: pd.DataFrame) -> np.ndarray:
    """PCR per minute: put OI / call OI over the rows printed at that minute."""
    s = g.groupby(["r", "side"]).oi.sum(min_count=1).unstack("side")
    out = np.full(NROWS, np.nan)
    if "C" in s and "P" in s:
        v = (s["P"] / s["C"]).replace([np.inf, -np.inf], np.nan)
        out[v.index.values] = v.values
    return out


def day_store(g: pd.DataFrame, step: float) -> dict:
    atm = g[g.offset == 0].strike
    lo = (atm.min() if len(atm) else g.strike.min()) - KEEP_STEPS * step - 1e-6
    hi = (atm.max() if len(atm) else g.strike.max()) + KEEP_STEPS * step + 1e-6
    con = {}
    for (s, k), h in g[(g.strike >= lo) & (g.strike <= hi)].groupby(["side", "strike"], sort=False):
        a = np.full((2, NROWS), np.nan, np.float32)
        a[0, h.r.values] = h.close.values
        a[1, h.r.values] = h.oi.values
        con[(s, float(k))] = a
    return dict(pcr=pcr_of(g).astype(np.float32), con=con)


def build(und: str) -> dict:
    lake, STEP = _lake()
    out = {}
    lo, hi = K.K4.EARLY_START, K.K4.POST_END
    for y in range(lo.year, hi.year + 1):
        p = lake / f"opt_{und}_WEEK_{y}.parquet"
        if not p.exists():
            continue
        o = clean(read_year(p, lo, hi), STEP[und])
        for d, g in o.groupby("day", sort=True):
            out[d] = day_store(g, STEP[und])
        print(und, y, len(out), flush=True)
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / f"oi_{und}.pkl").write_bytes(pickle.dumps(out, protocol=pickle.HIGHEST_PROTOCOL))
    return dict(und=und, days=len(out))


_C: dict = {}


def load(und: str) -> dict:
    if und not in _C:
        _C[und] = pickle.loads((K.OUT / f"oi_{und}.pkl").read_bytes())
    return _C[und]


if __name__ == "__main__":
    import r154data
    r154data.mods()
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
    else:
        raise SystemExit(__doc__)
