"""R25 data: one record per EXPIRY day of the lake's rolling weekly series, built straight from the lake's Parquet.

    python3 research/hunt/r25/r25data.py build        # writes <OUT>/days_<UND>.pkl (all expiry days, design + holdout)

The lake (Dhan "rollingoption") stores, at each minute, the contracts that were ATM-10 .. ATM+10 at that minute of the
nearest expiry. It has no expiry column. An expiry day is found from the data (`expiry_days`): the ATM time value at
15:15 is tiny on an expiry day and jumps the next session, when the series rolls to the next contract.

Grid: row t = the 1-minute bar that STARTS at 09:15 + t (375 rows); row t is known once that minute is over (t + 1).
Nothing is filled: a minute without a bar is NaN. A strike exists in a minute only while it is within ATM+-10 of that
minute's spot, so a far-OTM strike has no rows before it comes into range (the coverage gap; `edge` records the price
of the outermost stored strike on each side, so the census can count it).

Holdout safety: `load(und, part)` returns design days, holdout days or both; every analysis step asks for "design"
except `study.py holdout`.
"""
from __future__ import annotations

import pickle
import sys
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

import numpy as np
import pandas as pd

import r25cfg as K

N = 375
O, H, L, C, V, OI, IV = range(7)
TV_MAX = 0.6          # % of spot: an expiry day's ATM time value at 15:15 is below this ...
TV_JUMP = 2.0         # ... and the next session's is more than twice it (the roll to the next contract)


def minute(hh: int, mm: int) -> int:
    """Grid row of the bar that STARTS at hh:mm."""
    return hh * 60 + mm - (9 * 60 + 15)


@dataclass
class Day:
    day: date
    und: str
    ix: np.ndarray                              # (375, 4) index O H L C (NaN: no bar)
    opts: dict = field(default_factory=dict)    # (strike, 'C'/'P') -> (375, 7) O H L C V OI IV
    vix: np.ndarray | None = None               # (375,) VIX close
    prev_close: float = np.nan
    vix_prev: float = np.nan
    lot: int = 0
    edge: np.ndarray | None = None              # (375, 2) close of the outermost stored OTM strike: [:,0] CE (+10), [:,1] PE (-10)
    prev_range: float = np.nan                  # previous session's high - low (for ATR-style distances)

    @property
    def strikes(self) -> np.ndarray:
        return np.array(sorted({k for k, _ in self.opts}), dtype=float)


# ------------------------------------------------------------------------------------------------ expiry days
def expiry_days(tv: pd.Series) -> list[date]:
    """tv: ATM time value at 15:15 in % of spot, indexed by session date (sorted). An expiry day has tv < TV_MAX and
    the next session's tv > TV_JUMP x its own. The last session (no next) is never called an expiry."""
    tv = tv.sort_index()
    nxt = tv.shift(-1)
    ok = (tv < TV_MAX) & (nxt > TV_JUMP * tv.clip(lower=1e-6)) & nxt.notna()
    return [d for d, v in ok.items() if v]


def _tv_1515(o: pd.DataFrame) -> pd.Series:
    x = o[(o.offset == 0) & (o.m == minute(15, 15))]
    if x.empty:
        return pd.Series(dtype=float)
    p = x.pivot_table(index="d", columns="side", values="close", aggfunc="first", observed=True)
    s = x.groupby("d").agg(spot=("spot", "first"), k=("strike", "first"))
    j = p.join(s)
    if "C" not in j or "P" not in j:
        return pd.Series(dtype=float)
    return (j.C + j.P - (j.spot - j.k).abs()) / j.spot * 100


def _read(path, cols=None) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=cols)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["ts"] = ts
    df["d"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - (9 * 60 + 15)
    return df[(df.m >= 0) & (df.m < N)]


def _index_frames(und: str) -> pd.DataFrame:
    parts = [_read(p) for p in sorted(K.LAKE.glob(f"index_1m_{und}_*.parquet"))]
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def _grid(g: pd.DataFrame, cols) -> np.ndarray:
    a = np.full((N, len(cols)), np.nan)
    a[g.m.values] = g[cols].values.astype(float)
    return a


def build(und: str) -> list[Day]:
    ix = _index_frames(und)
    vx = _index_frames("INDIAVIX")
    ixd = dict(tuple(ix.groupby("d")))
    vxd = dict(tuple(vx.groupby("d"))) if len(vx) else {}
    sess = sorted(ixd)
    files = sorted(K.LAKE.glob(f"opt_{und}_WEEK_*.parquet"))
    tvs, frames = [], {}
    for p in files:
        o = _read(p)
        o["side"] = o.side.astype(str).str[0].str.upper()
        tvs.append(_tv_1515(o))
        frames[p] = o
    tv = pd.concat(tvs).sort_index()
    tv = tv[~tv.index.duplicated()]
    exp = set(expiry_days(tv))
    if und in K.LAST_WEEKLY:
        exp = {d for d in exp if d <= K.LAST_WEEKLY[und]}
    out = []
    for p, o in frames.items():
        o = o[o.d.isin(exp)]
        for d, g in o.groupby("d", sort=True):
            i = sess.index(d) if d in ixd else -1
            if i < 0:
                continue
            ixa = _grid(ixd[d], ["open", "high", "low", "close"])
            prev = ixd[sess[i - 1]] if i > 0 else None
            pc = float(prev.close.iloc[-1]) if prev is not None and len(prev) else np.nan
            pr = float(prev.high.max() - prev.low.min()) if prev is not None and len(prev) else np.nan
            vprev = np.nan
            if i > 0 and sess[i - 1] in vxd:
                vprev = float(vxd[sess[i - 1]].close.iloc[-1])
            va = _grid(vxd[d], ["close"])[:, 0] if d in vxd else np.full(N, np.nan)
            g = g.drop_duplicates(["m", "strike", "side"], keep="first")
            opts = {}
            for (k, s), h in g.groupby(["strike", "side"]):
                opts[(float(k), s)] = _grid(h, ["open", "high", "low", "close", "volume", "oi", "iv"])
            edge = np.full((N, 2), np.nan)
            ec = g[(g.side == "C") & (g.offset == 10)]
            ep = g[(g.side == "P") & (g.offset == -10)]
            edge[ec.m.values, 0] = ec.close.values
            edge[ep.m.values, 1] = ep.close.values
            out.append(Day(d, und, ixa, opts, va, pc, vprev, K.lot(und, d), edge, pr))
    out.sort(key=lambda x: x.day)
    return out


def path(und: str):
    return K.OUT / f"days_{und}.pkl"


def load(und: str, part: str = "design") -> list[Day]:
    """part: 'design' (after HOLDOUT_END), 'holdout' (on or before it; only study.py holdout may ask), or 'all'."""
    days = pickle.loads(path(und).read_bytes())
    if part == "design":
        return [d for d in days if not K.is_holdout(d.day)]
    if part == "holdout":
        return [d for d in days if K.is_holdout(d.day)]
    if part == "all":
        return days
    raise ValueError(part)


def calendar_only(und: str) -> list[date]:
    """Every expiry day's date (no prices): safe to read for counts in any period."""
    return [d.day for d in pickle.loads(path(und).read_bytes())]


if __name__ == "__main__":
    import r25data as _self          # pickle Day under its module name, not __main__
    if sys.argv[1:2] == ["build"]:
        K.OUT.mkdir(parents=True, exist_ok=True)
        for u in (sys.argv[2:] or K.UNDS):
            ds = _self.build(u)
            path(u).write_bytes(pickle.dumps(ds))
            print(u, len(ds), ds[0].day if ds else "-", ds[-1].day if ds else "-",
                  "holdout", sum(K.is_holdout(d.day) for d in ds))
