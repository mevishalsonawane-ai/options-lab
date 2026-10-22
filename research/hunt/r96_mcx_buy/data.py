"""R96 data (PREREG_ROUND1.md s2-3): one Book per option underlying.

- m      the R53 Market (near-future minute closes F, printed option legs by (day, cp, strike), strikes by offset,
         expiry days); built from the Drive lake, cached by R53 outside git.
- iv     {(cp, offset): IV % per grid minute}, forward-filled within the day (Dhan's own IV per stored strike).
- daily  the signal series: the book's near-month daily continuous future (raw OHLC, causal roll flag, ATR14; R93),
         plus the back-adjusted closes and indicators the rules read (rules.daily_ind, recomputed on every load).
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass, field

import numpy as np
import pandas as pd

from research.hunt.r96_mcx_buy import cfg as K


@dataclass
class Book:
    name: str
    m: object
    iv: dict
    daily: pd.DataFrame
    units: float
    ck: str
    events: tuple
    day_pos: dict = field(default_factory=dict)       # Market day (datetime64) -> position in m.days

    def __post_init__(self):
        self.day_pos = {d: k for k, d in enumerate(self.m.days)}


def iv_grid(m, raw: pd.DataFrame) -> dict:
    """raw: lake rows (ts naive IST, side 'C'/'P', offset, iv). Returns {(cp, offset): float array on m's grid}."""
    idx = pd.Series(np.arange(len(m.ts)), index=pd.DatetimeIndex(m.ts))
    r = raw[raw.ts.isin(idx.index) & (raw.iv > 0)]
    out = {}
    for (side, k), g in r.groupby(["side", "offset"], observed=True):
        a = np.full(len(m.ts), np.nan)
        a[idx.loc[g.ts].values] = g.iv.values.astype(float)
        out[(0 if side == "C" else 1, int(k))] = pd.Series(a).groupby(m.day).ffill().values
    return out


def load_daily(sig: str) -> pd.DataFrame:
    from research.hunt.r93_breadth_donchian_mcx import rules as R93
    d = pd.read_parquet(K.LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol.astype(str) == sig) & (d.expiry_code == 0)]
    d = d[["date", "open", "high", "low", "close", "oi"]].copy()
    # R93 daily_series keeps the RAW open/high/low/close and adds the causal roll flag and ATR14 (true range reset on a
    # roll row); rules.daily_ind adds the back-adjusted closes the indicators read.
    return R93.daily_series(d).reset_index(drop=True)


def share_expiries(m, other_days) -> None:
    """PREREG_ROUND6 s1 (N11): ZINC's thin book misses one expiry (23 Apr 2026) in the straddle-jump detector; COPPER and
    ZINC options expire on the same days in every other month, so ZINC uses the union of both detected sets. Updates the
    Market's expiry days, contract segments and expiry flags in place."""
    m.exp_days = np.unique(np.r_[m.exp_days, np.asarray(other_days, dtype="datetime64[ns]")]).astype("datetime64[ns]")
    m.seg = np.searchsorted(m.exp_days, m.day, side="left").astype(np.int32)
    m.is_exp = np.isin(m.day, m.exp_days)


EXPIRY_FROM = {"ZINC": "COPPER"}


def load_book(name: str, rebuild: bool = False) -> Book:
    from research.hunt.r53.mcx import data as D53
    from research.hunt.r96_mcx_buy import rules as R
    K.CACHE.mkdir(parents=True, exist_ok=True)
    p = K.CACHE / f"book_{name}.pkl"
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            b = pickle.load(f)
        b.daily = R.daily_ind(load_daily(K.BOOKS[name][0]))       # indicators always from the current code
        return b
    sig, ck, units, ev = K.BOOKS[name]
    m = D53.load_market(name)
    if name in EXPIRY_FROM:
        share_expiries(m, D53.load_market(EXPIRY_FROM[name]).exp_days)
    raw = D53.lake_options(name)
    iv = iv_grid(m, raw[["ts", "side", "offset", "iv"]])
    daily = R.daily_ind(load_daily(sig))
    b = Book(name, m, iv, daily, units, ck, ev)
    with open(p, "wb") as f:
        pickle.dump(b, f, protocol=pickle.HIGHEST_PROTOCOL)
    return b
