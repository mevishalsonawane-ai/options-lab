"""R29 data: index minutes and option tables from the lake's Parquet, with the holdout guard.

    python3 research/hunt/r29/r29data.py build [UND ...]      # writes <OUT>/idx_<UND>.parquet, opt_<UND>.npz, days_<UND>.json

Index: every minute of the lake for the underlying, as a cube-like frame (ts naive IST, day, minute, flag, OHLC) that
the Data Brain's feature code reads (`ribbon.ribbon_bars`).

Options: one long table per underlying sorted by (day, side, strike, minute) with O H L C V (float32), and a lookup
(day, side, strike) -> rows. Only what the trade simulator needs. ATM per minute = the strike at offset 0.

Holdout safety: `option_days(und, part)` and `days(und, part)` refuse part="holdout" unless study.py has set
`ALLOW_HOLDOUT` for its one read, and refuse the walk-forward parts until PREREG.md has a filled Part B.
"""
from __future__ import annotations

import json
import sys
from dataclasses import dataclass
from datetime import date

import numpy as np
import pandas as pd

import r29cfg as K

ALLOW_HOLDOUT = False


def _read(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["ts"] = ts
    m = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    df["m"] = m
    return df[(m >= 0) & (m < K.N)].reset_index(drop=True)


def index_minutes(und: str) -> pd.DataFrame:
    parts = [_read(p, ["ts", "open", "high", "low", "close"]) for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet"))]
    if not parts:
        return pd.DataFrame(columns=["ts", "day", "minute", "flag", "open", "high", "low", "close"])
    x = pd.concat(parts, ignore_index=True).drop_duplicates("ts").sort_values("ts").reset_index(drop=True)
    x = x[x.ts.dt.date <= K.LAST_DAY]
    bad = ~np.isfinite(x[["open", "high", "low", "close"]]).all(axis=1) | (x.close <= 0)
    return pd.DataFrame(dict(ts=x.ts.values, day=x.ts.dt.normalize().values, minute=x.m.values,
                             flag=np.where(bad, "gap", "ok"), open=x.open.values, high=x.high.values,
                             low=x.low.values, close=x.close.values))


# ------------------------------------------------------------------------------------------------ expiry days
TV_MAX, TV_JUMP = 0.6, 2.0


def expiry_days(tv: pd.Series) -> set:
    """R25's rule: ATM time value at 15:15 (% of spot) < TV_MAX and the next session's > TV_JUMP x it."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return {d for d, v in ok.items() if v}


# ------------------------------------------------------------------------------------------------ option table
@dataclass
class Opt:
    """Option minutes of one underlying. key[(day_ordinal, side, strike)] = (start, end) rows into the arrays."""
    m: np.ndarray
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray
    key: dict
    atm: dict            # day_ordinal -> (375,) ATM strike per minute (NaN where none)
    expiry: set          # day ordinals that are expiry days

    def path(self, d: int, side: str, k: float):
        """(375,) O H L C V of one strike on one day; NaN where the minute has no row. None if never stored."""
        se = self.key.get((d, side, float(k)))
        if se is None:
            return None
        s, e = se
        out = np.full((5, K.N), np.nan)
        mm = self.m[s:e]
        for j, a in enumerate((self.o, self.h, self.l, self.c, self.v)):
            out[j, mm] = a[s:e]
        return out


def build_options(und: str) -> Opt | None:
    files = sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet"))
    if not files:
        return None
    frames, tvs = [], []
    step = K.STEP[und]
    for p in files:
        o = _read(p, ["ts", "offset", "side", "strike", "spot", "open", "high", "low", "close", "volume"])
        o = o[o.ts.dt.date <= K.LAST_DAY]
        o["side"] = o.side.astype(str).str[0].str.upper()
        o = o[(o.strike - o.spot).abs() <= 12 * step]                       # mislabelled rows (R25)
        o["d"] = o.ts.dt.date.map(date.toordinal).astype(np.int32)
        a = o[(o.offset == 0) & (o.m == 360)]
        if len(a):
            pv = a.pivot_table(index="d", columns="side", values="close", aggfunc="first")
            s = a.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
            j = pv.join(s)
            if "C" in j and "P" in j:
                tvs.append((j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100)
        frames.append(o[["d", "m", "side", "strike", "offset", "open", "high", "low", "close", "volume"]])
    x = pd.concat(frames, ignore_index=True)
    x = x.drop_duplicates(["d", "m", "side", "strike"], keep="first")
    atm_rows = x[x.offset == 0].drop_duplicates(["d", "m"])
    atm = {}
    for d, g in atm_rows.groupby("d"):
        a = np.full(K.N, np.nan)
        a[g.m.values] = g.strike.values
        atm[int(d)] = a
    x = x.sort_values(["d", "side", "strike", "m"]).reset_index(drop=True)
    g = x.groupby(["d", "side", "strike"], sort=False).size()
    ends = np.cumsum(g.values)
    starts = ends - g.values
    key = {(int(d), s, float(k)): (int(a), int(b)) for (d, s, k), a, b in zip(g.index, starts, ends)}
    tv = pd.concat(tvs).sort_index() if tvs else pd.Series(dtype=float)
    tv = tv[~tv.index.duplicated()]
    f32 = lambda c: x[c].values.astype(np.float32)
    return Opt(x.m.values.astype(np.int16), f32("open"), f32("high"), f32("low"), f32("close"), f32("volume"),
               key, atm, {int(d) for d in expiry_days(tv)})


def save_options(und: str, op: Opt):
    K.OUT.mkdir(parents=True, exist_ok=True)
    ks = np.array([(d, 0 if s == "C" else 1, k, a, b) for (d, s, k), (a, b) in op.key.items()], dtype=np.float64)
    ad = np.array(sorted(op.atm), dtype=np.int64)
    np.savez(K.OUT / f"opt_{und}.npz", m=op.m, o=op.o, h=op.h, l=op.l, c=op.c, v=op.v, keys=ks,
             atm_days=ad, atm=np.stack([op.atm[d] for d in ad]) if len(ad) else np.zeros((0, K.N)),
             expiry=np.array(sorted(op.expiry), dtype=np.int64))


def load_options(und: str) -> Opt | None:
    p = K.OUT / f"opt_{und}.npz"
    if not p.exists():
        return None
    z = np.load(p)
    key = {(int(d), "C" if s == 0 else "P", float(k)): (int(a), int(b)) for d, s, k, a, b in z["keys"]}
    atm = {int(d): a for d, a in zip(z["atm_days"], z["atm"])}
    return Opt(z["m"], z["o"], z["h"], z["l"], z["c"], z["v"], key, atm, set(int(d) for d in z["expiry"]))


# ------------------------------------------------------------------------------------------------ guard
def prereg_b_filled() -> bool:
    t = (K.HERE / "PREREG.md").read_text()
    return "## Part B" in t and "(Added after Steps 1-2, before Step 3. Empty at the Part A commit.)" not in t


def check_part(part: str):
    if part not in K.PARTS:
        raise ValueError(part)
    if part == "holdout" and not ALLOW_HOLDOUT:
        raise PermissionError("the R29 holdout (2023) is read only by `study.py holdout`")
    if part.startswith("wf") and not prereg_b_filled():
        raise PermissionError("walk-forward years are read only after PREREG.md Part B is committed")


def days_in(part: str, days) -> list:
    """The session dates of `days` that belong to `part` (after the guard)."""
    check_part(part)
    ys = K.PARTS[part]
    return [d for d in days if d.year in ys]


def build(und: str):
    K.OUT.mkdir(parents=True, exist_ok=True)
    M = index_minutes(und)
    M.to_parquet(K.OUT / f"idx_{und}.parquet")
    op = build_options(und)
    info = dict(und=und, index_rows=len(M), index_days=int(M.day.nunique()),
                index_first=str(M.ts.min()), index_last=str(M.ts.max()))
    if op is not None:
        save_options(und, op)
        od = sorted(op.atm)
        info.update(option_days=len(od), option_first=str(date.fromordinal(od[0])),
                    option_last=str(date.fromordinal(od[-1])), expiry_days=len(op.expiry),
                    option_rows=int(len(op.m)))
    (K.OUT / f"days_{und}.json").write_text(json.dumps(info, indent=1))
    return info


def load_index(und: str) -> pd.DataFrame:
    return pd.read_parquet(K.OUT / f"idx_{und}.parquet")


if __name__ == "__main__":
    if len(sys.argv) >= 2 and sys.argv[1] == "build":
        for u in sys.argv[2:] or K.UNDS:
            print(build(u), flush=True)
