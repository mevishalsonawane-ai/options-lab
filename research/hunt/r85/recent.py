"""R85 recent window: TRUE 1-minute futures OHLC, volume and OI (fut_1m_live_mcx), descriptive only, 19 Oct 2026.

    python3 -m research.hunt.r85.recent [LAKE_FILE]

Dhan serves only listed contracts, so the ACTIVE contract's minutes exist only for the last weeks before the download
(all inside R85's final period; run after the final read, chosen nothing from it). The active contract per day:
- for the 6 option underlyings, the contract whose 1-minute close equals the lake option rows' `spot` on >= 75% of
  that day's shared minutes (the option's underlying, checked);
- for the other commodities, the contract with the most volume on the PREVIOUS day (data/mcx.py rule), kept only on
  days its own volume is >= 50% of the commodity's highest daily volume in the file (a thin far contract is not
  "the market").
Per commodity: 1m and 5m true candles; green / red share, wick shares, how much the close-only candles of Part 1 miss
(true range / close-only range); volume vs its usual for that minute (median of the same minute over the previous
10 active days); OI change classes (price up + OI up = long build-up, up + OI down = short covering, down + OI up =
short build-up, down + OI down = long unwinding); next 3 candles' signed move (ATR units) by class, with n and the
random-time baseline. No BH: descriptive, small samples, final-period data.
"""
from __future__ import annotations

import json
import os
import sys

import numpy as np
import pandas as pd

from research.hunt.r85 import anatomy as A
from research.hunt.r85 import candles as K

OPT_UNDERLYINGS = ("GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "NATURALGAS")


def load(path: str) -> pd.DataFrame:
    f = pd.read_parquet(path)
    f["symbol"] = f.symbol.astype(str)
    f["ts"] = f.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None) if f.ts.dt.tz is not None else f.ts
    f["day"] = f.ts.dt.date
    f["tod"] = f.ts.dt.hour * 60 + f.ts.dt.minute
    f["expiry"] = f.expiry.astype(str)
    return f


def active_days(f: pd.DataFrame, sym: str) -> pd.DataFrame:
    """Minutes of the active contract only (see module doc), sorted by time."""
    g = f[f.symbol == sym]
    if sym in OPT_UNDERLYINGS:
        from research.hunt.r53.mcx import data as D
        m = D.load_market(sym)
        sp = pd.Series(m.F, index=pd.DatetimeIndex(m.ts))
        g = g.assign(spot=sp.reindex(g.ts.values).values)
        ok = (g.close - g.spot).abs() <= 0.0002 * g.spot
        share = ok.groupby([g.day, g.expiry]).mean()
        keep = share[share >= 0.75].index
    else:
        vol = g.groupby(["day", "expiry"]).volume.sum().unstack()
        best_prev = vol.idxmax(axis=1).shift(1)
        top = vol.max(axis=1).max()
        keep = [(d, e) for d, e in best_prev.dropna().items() if vol.loc[d, e] >= 0.5 * top]
    idx = pd.MultiIndex.from_frame(g[["day", "expiry"]])
    g = g[idx.isin(keep)]
    return g.sort_values("ts").reset_index(drop=True)


def true_candles(g: pd.DataFrame, tf: int) -> pd.DataFrame:
    """True OHLCV candles (anchored at 09:00) with the close-only range of the same candle for comparison."""
    g = g[(g.tod >= K.OPEN_M)].copy()
    g["b"] = (g.tod - K.OPEN_M) // tf
    pc = g.groupby("day").close.shift(1)
    g["co_o"] = pc.fillna(g.close)
    agg = g.groupby(["day", "b"]).agg(o=("open", "first"), h=("high", "max"), l=("low", "min"), c=("close", "last"),
                                      v=("volume", "sum"), oi=("oi", "last"), n=("close", "size"),
                                      co_hi=("close", "max"), co_lo=("close", "min"), co_o=("co_o", "first"))
    agg = agg[agg.n >= (tf + 1) // 2].reset_index()
    agg["start"] = K.OPEN_M + agg.b * tf
    agg["co_rng"] = np.maximum(agg.co_hi, agg.co_o) - np.minimum(agg.co_lo, agg.co_o)
    return agg


def describe(sym: str, cd: pd.DataFrame, tf: int) -> dict:
    cd = cd.copy()
    rng = cd.h - cd.l
    body = cd.c - cd.o
    col = np.sign(body).astype(int)
    col[(rng <= 0) | (body.abs() <= 0.1 * rng)] = 0
    cd["colour"] = col
    same = cd.day.values[1:] == cd.day.values[:-1]
    pc = np.r_[np.nan, cd.c.values[:-1]]
    tr = np.where(np.r_[False, same], np.maximum.reduce([rng, (cd.h - pc).abs(), (cd.l - pc).abs()]), rng)
    atr = pd.Series(tr).rolling(14, min_periods=5).mean().values
    atr = np.where(atr > 0, atr, np.nan)
    cd["atr"] = atr
    # volume vs the same slot's median over the previous 10 days
    med = cd.groupby("start").v.transform(lambda x: x.shift(1).rolling(10, min_periods=5).median())
    cd["v_ratio"] = cd.v / med
    doi = cd.groupby("day").oi.diff()
    up, dn = col > 0, col < 0
    cls = np.full(len(cd), "", dtype=object)
    cls[up & (doi > 0)] = "long build-up (green, OI up)"
    cls[up & (doi < 0)] = "short covering (green, OI down)"
    cls[dn & (doi > 0)] = "short build-up (red, OI up)"
    cls[dn & (doi < 0)] = "long unwinding (red, OI down)"
    cd["oi_class"] = cls
    cd["day0"] = cd.day
    cd["bucket"] = [K.bucket_of(int(t), d) for t, d in zip(cd.start, cd.day)]
    c = cd.c.values
    n = len(cd)
    j = np.arange(n) + 3
    okf = j < n
    okf[okf] = cd.day.values[j[okf]] == cd.day.values[np.arange(n)[okf]]
    u3 = np.full(n, np.nan)
    u3[okf] = (c[j[okf]] - c[okf]) / atr[okf]
    s3 = np.where(col != 0, col * u3, np.nan)
    base = float(np.nanmean(s3))
    base_c = float(np.nanmean(s3[np.isfinite(s3)] > 0))
    out = {"symbol": sym, "tf": f"{tf}m", "candles": int(n), "days": int(cd.day.nunique()),
           "first_day": str(cd.day.min()), "last_day": str(cd.day.max()),
           "green": float((col > 0).mean()), "red": float((col < 0).mean()), "doji": float((col == 0).mean()),
           "median_upper_wick": float(np.nanmedian((cd.h - np.maximum(cd.o, cd.c)) / rng.replace(0, np.nan))),
           "median_lower_wick": float(np.nanmedian((np.minimum(cd.o, cd.c) - cd.l) / rng.replace(0, np.nan))),
           "close_only_range_share": float(np.nanmedian(cd.co_rng / rng.replace(0, np.nan))),
           "baseline_cont3": base_c, "baseline_s3": base, "groups": {}}

    def grp(name, mask):
        x = s3[mask & np.isfinite(s3)]
        if len(x):
            out["groups"][name] = {"n": int(len(x)), "cont3": float((x > 0).mean()), "mean_s3_atr": float(x.mean())}
    for k in sorted(set(cls) - {""}):
        grp(k, cls == k)
    big = (rng / atr >= 1.5) & (col != 0)
    hv = cd.v_ratio.values >= 2
    grp("big candle (range >= 1.5 ATR)", big)
    grp("big candle on volume >= 2x usual", big & hv)
    grp("any candle on volume >= 2x usual", hv & (col != 0))
    grp("big green candle on volume >= 2x after 19:00", big & hv & (col > 0) & (cd.start.values >= 19 * 60))
    grp("big red candle on volume >= 2x after 19:00", big & hv & (col < 0) & (cd.start.values >= 19 * 60))
    for b in K.BUCKETS:
        grp(f"session {b}", cd.bucket.values == b)
    return out


def run(path: str | None = None):
    A.lake_ready()
    path = path or os.path.join(os.environ["IRAALGO_LAKE"], "fut_1m_live_mcx", "fut_1m_live_mcx.parquet")
    f = load(path)
    res = []
    for sym in sorted(f.symbol.unique()):
        g = active_days(f, sym)
        if g.day.nunique() < 5:
            res.append({"symbol": sym, "note": f"only {g.day.nunique()} active days"})
            continue
        for tf in (1, 5):
            res.append(describe(sym, true_candles(g, tf), tf))
        print(sym, g.day.nunique(), flush=True)
    (A.RESULTS / "recent_true_candles.json").write_text(json.dumps(res, indent=1, default=str))
    return res


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else None)
