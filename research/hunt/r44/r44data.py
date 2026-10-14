"""R44 data: 5-minute underlying bars and the ATM option paths, from the lake's Parquet.

    python3 research/hunt/r44/r44data.py build [UND ...]     # writes <OUT>/r44_<UND>.npz and info_<UND>.json

Underlying: NSE/BSE index minutes (index_1m), or for CRUDEOIL the `spot` column of its option file (the near future;
the lake has no continuous futures minutes). 5-minute bars on the fixed grid of r44cfg.GRID.

Options: the nearest-expiry rolling series only (opt_<UND>_WEEK; CRUDEOIL opt_CRUDEOIL_MONTH). For every day and
entry bar the ATM strike at the bar's first minute (or the last ATM within 3 minutes before) is looked up, and the
5-minute O H L C V path of each such contract (side x strike x day) is stored. Expiry days: the R25/R29 straddle rule.

The cache holds every day (like the brain's cubes); periods and the holdout guard are applied in run.py.
"""
from __future__ import annotations

import json
import os
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

import r44cfg as K
from ind import bars5


def _read(path, cols) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["ts"] = ts
    df["minute"] = (ts.dt.hour * 60 + ts.dt.minute).astype(np.int32)
    df["d"] = ts.dt.date.map(date.toordinal).astype(np.int32)
    return df[ts.dt.date <= K.LAST_DAY]


def option_files(und: str) -> list:
    if K.is_mcx(und):
        pat = f"opt_{und}_MONTH_*.parquet"
    else:   # the nearest expiry, read once (never the MONTH twin); R44_SERIES=MONTH only for diag_series.py
        pat = f"opt_{und}_{os.environ.get('R44_SERIES', 'WEEK')}_*.parquet"
    return [p for p in sorted(K.LAKE.glob(pat)) if int(p.stem[-4:]) >= K.FIRST_YEAR]


def underlying_minutes(und: str) -> pd.DataFrame:
    """1-minute O H L C of the signal series: columns d, minute, open, high, low, close (time order, no duplicates)."""
    if K.is_mcx(und):
        parts = [_read(p, ["ts", "spot"]) for p in option_files(und)]
        if not parts:
            return pd.DataFrame(columns=["d", "minute", "open", "high", "low", "close"])
        x = pd.concat(parts, ignore_index=True)
        x = x[np.isfinite(x.spot) & (x.spot > 0)]
        x = x.groupby(["d", "minute"], sort=True).spot.first().reset_index()
        return pd.DataFrame(dict(d=x.d, minute=x.minute, open=x.spot, high=x.spot, low=x.spot, close=x.spot))
    files = [p for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet")) if int(p.stem[-4:]) >= K.FIRST_YEAR]
    parts = [_read(p, ["ts", "open", "high", "low", "close"]) for p in files]
    if not parts:
        return pd.DataFrame(columns=["d", "minute", "open", "high", "low", "close"])
    x = pd.concat(parts, ignore_index=True).drop_duplicates("ts").sort_values("ts")
    ok = np.isfinite(x[["open", "high", "low", "close"]]).all(axis=1) & (x.close > 0)
    x = x[ok]
    return x[["d", "minute", "open", "high", "low", "close"]].reset_index(drop=True)


def expiry_days(tv: pd.Series, tv_max: float = K.EXPIRY_TV_MAX, jump: float = K.EXPIRY_TV_JUMP) -> set:
    """ATM time value (% of spot) < tv_max at the read minute and the next session's > jump x it."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < tv_max) & (nxt > jump * tv.clip(lower=1e-6)) & nxt.notna()
    return {int(d) for d, v in ok.items() if v}


@dataclass
class Data:
    und: str
    # underlying 5-minute bars (time order)
    bd: np.ndarray        # day ordinal
    bb: np.ndarray        # bar index in the day
    bo: np.ndarray
    bh: np.ndarray
    bl: np.ndarray
    bc: np.ndarray
    # option paths
    P: np.ndarray         # (npaths, 5, nbars) float32 O H L C V on the 5-minute grid, NaN = no trade in the bar
    days: np.ndarray      # option day ordinals (sorted)
    pidx: np.ndarray      # (ndays, nbars, 2) path index of the ATM contract entered at that bar (CE, PE); -1 = none
    expiry: set           # expiry day ordinals

    def day_row(self, d: int) -> int:
        i = int(np.searchsorted(self.days, d))
        return i if i < len(self.days) and self.days[i] == d else -1


def build_options(und: str, nbars: int, start: int):
    files = option_files(und)
    if not files:
        return None
    step = K.STEP[und]
    frames, tvs = [], []
    tvm = K.TV_MINUTE["mcx" if K.is_mcx(und) else "nse"]
    for p in files:
        o = _read(p, ["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume"])
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= 12 * step]                       # mislabelled rows (R25)
        o = o[(o.minute >= start) & (o.minute < start + 5 * nbars)]
        a = o[(o.offset == 0) & (o.minute <= tvm) & (o.minute >= tvm - 30)].sort_values("minute")
        a = a.groupby(["d", "side"], sort=False).tail(1)                    # the last ATM minute at or before tvm
        if len(a):
            pv = a.pivot_table(index="d", columns="side", values="close", aggfunc="first")
            s = a.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
            j = pv.join(s)
            if "C" in j and "P" in j:
                tvs.append((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100)
        frames.append(o[["d", "minute", "side", "strike", "offset", "open", "high", "low", "close", "volume"]])
    x = pd.concat(frames, ignore_index=True)
    x = x.drop_duplicates(["d", "minute", "side", "strike"], keep="first")
    # ATM per minute, then per entry bar (first minute of the bar, else the last ATM within the 3 minutes before)
    at = x[x.offset == 0].drop_duplicates(["d", "minute"])[["d", "minute", "strike"]].sort_values(["d", "minute"])
    days = np.array(sorted(at.d.unique()), dtype=np.int64)
    rows = []
    for d, g in at.groupby("d", sort=True):
        mins, ks = g.minute.values, g.strike.values
        for b in range(nbars):
            m0 = start + 5 * b
            i = np.searchsorted(mins, m0, side="right") - 1
            if i >= 0 and mins[i] >= m0 - 3:
                rows.append((d, b, ks[i]))
    eb = pd.DataFrame(rows, columns=["d", "bar", "strike"])
    need = pd.concat([eb.assign(side="C"), eb.assign(side="P")])[["d", "side", "strike"]].drop_duplicates()
    y = x.merge(need, on=["d", "side", "strike"], how="inner")
    y["bar"] = (y.minute - start) // 5
    y = y.sort_values(["d", "side", "strike", "minute"])
    agg = y.groupby(["d", "side", "strike", "bar"], sort=True).agg(
        open=("open", "first"), high=("high", "max"), low=("low", "min"), close=("close", "last"),
        volume=("volume", "sum")).reset_index()
    keys = agg[["d", "side", "strike"]].drop_duplicates().reset_index(drop=True)
    keys["pi"] = np.arange(len(keys))
    agg = agg.merge(keys, on=["d", "side", "strike"])
    P = np.full((len(keys), 5, nbars), np.nan, dtype=np.float32)
    for j, col in enumerate(("open", "high", "low", "close", "volume")):
        P[agg.pi.values, j, agg.bar.values] = agg[col].values
    pidx = np.full((len(days), nbars, 2), -1, dtype=np.int32)
    kmap = {(int(d), s, float(k)): int(i) for d, s, k, i in keys.itertuples(index=False)}
    drow = {int(d): i for i, d in enumerate(days)}
    for d, b, k in eb.itertuples(index=False):
        for si, s in enumerate("CP"):
            pi = kmap.get((int(d), s, float(k)))
            if pi is not None:
                pidx[drow[int(d)], b, si] = pi
    tv = pd.concat(tvs).sort_index() if tvs else pd.Series(dtype=float)
    tv = tv[~tv.index.duplicated()]
    mx = K.is_mcx(und)
    return P, days, pidx, expiry_days(tv, K.EXPIRY_TV_MAX_MCX if mx else K.EXPIRY_TV_MAX,
                                      K.EXPIRY_TV_JUMP_MCX if mx else K.EXPIRY_TV_JUMP)


def build(und: str) -> dict:
    g = K.grid(und)
    M = underlying_minutes(und)
    B = bars5(M.minute.values, M.d.values, M.open.values, M.high.values, M.low.values, M.close.values,
              g["start"], g["nbars"])
    op = build_options(und, g["nbars"], g["start"])
    if op is None:
        P, days, pidx, exp = np.zeros((0, 5, g["nbars"]), np.float32), np.zeros(0, np.int64), \
            np.zeros((0, g["nbars"], 2), np.int32), set()
    else:
        P, days, pidx, exp = op
    K.OUT.mkdir(parents=True, exist_ok=True)
    np.savez(K.OUT / f"r44_{und}.npz", bd=B["day"], bb=B["bar"], bo=B["open"], bh=B["high"], bl=B["low"],
             bc=B["close"], P=P, days=days, pidx=pidx, expiry=np.array(sorted(exp), dtype=np.int64))
    info = dict(und=und, bars=int(len(B["day"])), bar_days=int(len(np.unique(B["day"]))),
                first=str(date.fromordinal(int(B["day"][0]))) if len(B["day"]) else None,
                last=str(date.fromordinal(int(B["day"][-1]))) if len(B["day"]) else None,
                option_days=int(len(days)), paths=int(len(P)), expiry_days=len(exp))
    (K.OUT / f"info_{und}.json").write_text(json.dumps(info, indent=1))
    return info


def load(und: str) -> Data:
    z = np.load(K.OUT / f"r44_{und}.npz")
    return Data(und, z["bd"], z["bb"], z["bo"], z["bh"], z["bl"], z["bc"], z["P"], z["days"], z["pidx"],
                set(int(d) for d in z["expiry"]))


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
