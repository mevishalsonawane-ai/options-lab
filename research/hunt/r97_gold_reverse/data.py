"""R97 data: the GOLD / GOLDM option books (R96 Book: R53 Market + IV per stored strike + daily series), SILVER's near
future (context), XAUUSD spot minutes (context, Dukascopy BID, fetched by fetch_xau.py into the cache), the total
option volume per minute of each gold book (activity proxy: the lake has no full-period gold futures volume), and the
US release calendar (CPI / NFP / FOMC, research/brain/mcx/events.py).

Nothing here is committed: everything is rebuilt from the lake / cache.
"""
from __future__ import annotations

import pickle
from datetime import date

import numpy as np
import pandas as pd

from research.hunt.r97_gold_reverse import cfg as K


def _register_books():
    from research.hunt.r96_mcx_buy import cfg as K96
    for n, v in K.BOOKS.items():
        K96.BOOKS.setdefault(n, v)


def load_book(name: str):
    """R96 Book for GOLD or GOLDM (R96 already handles GOLDM; GOLD is registered with its x100 lot)."""
    _register_books()
    from research.hunt.r96_mcx_buy import data as D96
    return D96.load_book(name)


def load_silver_F() -> pd.Series:
    """SILVER near-future minute closes (R53 Market of the SILVER option book), index naive IST."""
    from research.hunt.r53.mcx import data as D53
    p = K.CACHE / "silver_F.pkl"
    if p.exists():
        return pd.read_pickle(p)
    K.CACHE.mkdir(parents=True, exist_ok=True)
    m = D53.load_market("SILVER")
    s = pd.Series(m.F, index=pd.DatetimeIndex(m.ts))
    s.to_pickle(p)
    return s


def load_xau() -> pd.Series | None:
    """XAUUSD BID minute closes, index = bar END in IST (naive): a value is usable from that minute on.

    Source: Dukascopy XAUUSD 1-minute BID (research only). (1) the options-lab `research-data` release asset
    `xauusd_m1_bid.csv.gz` (2 Oct 2023 - 30 Sep 2026; sha256 c4195fa6..., the release digest), cached as
    CACHE/rel/xauusd_m1_bid.csv.gz; (2) the tail fetched by fetch_xau.py (CACHE/xau_tail_1m.parquet), used only after
    the release's last minute. None when neither exists (step 2 then reports the XAUUSD rows as missing)."""
    parts = []
    rel = K.CACHE / "rel" / "xauusd_m1_bid.csv.gz"
    if rel.exists():
        x = pd.read_csv(rel, usecols=["timestamp", "close"])
        parts.append(pd.Series(x.close.values.astype(float), index=pd.to_datetime(x.timestamp.values, unit="ms")))
    for f in (K.CACHE / "xau_tail_1m.parquet", K.XAU_FILE):
        if f.exists():
            y = pd.read_parquet(f)
            s = pd.Series(y.close.values.astype(float), index=pd.DatetimeIndex(y.ts_utc))
            if parts:
                s = s[s.index > parts[0].index.max()]
            parts.append(s)
    if not parts:
        return None
    s = pd.concat(parts)
    s.index = s.index + pd.Timedelta(hours=5, minutes=30) + pd.Timedelta(minutes=1)     # bar start UTC -> end IST
    return s[~s.index.duplicated(keep="first")].sort_index()


def option_volume(name: str) -> pd.Series:
    """Total traded option volume (contracts, ATM-3..+3, both sides) per minute of the book, index naive IST."""
    p = K.CACHE / f"optvol_{name}.pkl"
    if p.exists():
        return pd.read_pickle(p)
    from research.hunt.r53.mcx import data as D53
    raw = D53.lake_options(name)
    s = raw.groupby("ts").volume.sum().astype(float).sort_index()
    K.CACHE.mkdir(parents=True, exist_ok=True)
    s.to_pickle(p)
    return s


def events(start: date = date(2025, 8, 1), end: date = date(2026, 10, 31)) -> dict:
    """{kind: {IST date: release minute of the IST day}} for cpi / nfp / fomc."""
    from research.brain.mcx import events as EV
    cal = EV.calendar(start, end)
    return {k: cal[k] for k in ("cpi", "nfp", "fomc")}


def save(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    with open(K.CACHE / name, "wb") as f:
        pickle.dump(obj, f, protocol=pickle.HIGHEST_PROTOCOL)


def day_arrays(m, d):
    """(grid indices, F, tod) of one session of a Market."""
    ix = m.day_ix(d)
    return ix, m.F[ix], m.tod[ix]


def in_period(d, p) -> bool:
    d = pd.Timestamp(d).date()
    return p[0] <= d <= p[1]


def design_days(m) -> list:
    return [d for d in m.days if in_period(d, K.DESIGN)]


def to_np_day(d):
    return np.datetime64(pd.Timestamp(d).normalize(), "ns")
