"""R88 data (PREREG section 1): index minutes, VIX, option-chain state per minute, MCX underlying minutes, expiries.

All timestamps are naive IST minute starts. Everything is read from the lake (cfg.LAKE) and cached per instrument
under cfg.CACHE (never committed).
"""
from __future__ import annotations

import glob
import os
import sys
from datetime import date, timedelta

import numpy as np
import pandas as pd

from . import cfg

sys.path.insert(0, str(cfg.ROOT / "research" / "data"))
import mcx as MCXLIB  # noqa: E402  (research/data/mcx.py: the MCX session rule)

WARMUP_DAYS = 120  # calendar days loaded before the period start, for baselines and z-scores


def lake_files(pattern: str, lake=None) -> list[str]:
    lake = str(lake or cfg.LAKE)
    return sorted(set(glob.glob(os.path.join(lake, pattern)) + glob.glob(os.path.join(lake, "*", pattern))))


def _naive(ts: pd.Series) -> pd.Series:
    return ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None) if ts.dt.tz is not None else ts


def _years(first: date, last: date) -> list[int]:
    return list(range(first.year, last.year + 1))


def load_span(sym: str) -> tuple[date, date]:
    return cfg.start(sym) - timedelta(days=WARMUP_DAYS), cfg.END


def clean_sessions(d: pd.DataFrame, close_only: bool = False) -> pd.DataFrame:
    """Weekdays with >= 120 minutes and fewer than half of them flat (R87 A2). For close-only series (MCX) a flat
    minute is one whose close equals the previous minute's close."""
    if not len(d):
        return d
    day = d.ts.dt.normalize()
    flat = (d.c == d.groupby(day).c.shift()) if close_only else (d.h == d.l)
    g = pd.DataFrame(dict(n=d.groupby(day).size(), flat=flat.groupby(day).mean()))
    ok = g[(g.index.dayofweek < 5) & (g.n >= 120) & (g.flat < 0.5)].index
    return d[day.isin(ok)].reset_index(drop=True)


def index_minutes(sym: str, lake=None) -> pd.DataFrame:
    """ts, o, h, l, c of an index (or INDIAVIX), 09:15-15:29, cleaned sessions."""
    a, b = load_span(sym if sym != "INDIAVIX" else "NIFTY")
    fs = [f for f in lake_files(f"index_1m_{sym}_*.parquet", lake) if int(f[-12:-8]) in _years(a, b)]
    if not fs:
        raise FileNotFoundError(f"no index_1m files for {sym} under {lake or cfg.LAKE}")
    d = pd.concat([pd.read_parquet(f, columns=["ts", "open", "high", "low", "close"]) for f in fs], ignore_index=True)
    d = d.rename(columns=dict(open="o", high="h", low="l", close="c"))
    d["ts"] = _naive(d.ts).dt.floor("min")
    m = d.ts.dt.hour * 60 + d.ts.dt.minute
    d = d[(m >= cfg.NSE_OPEN) & (m < cfg.NSE_OPEN + cfg.NSE_ROWS)]
    d = d[(d.ts.dt.date >= a) & (d.ts.dt.date <= b) & (d[["o", "h", "l", "c"]] > 0).all(axis=1)]
    d = d.drop_duplicates("ts", keep="last").sort_values("ts").reset_index(drop=True)
    return clean_sessions(d)


def option_book(sym: str, lake=None) -> pd.DataFrame:
    """Rows of the instrument's rolling option book in the load span: ts, offset, side, close, iv (NaN if <= 0),
    volume, oi, spot."""
    a, b = load_span(sym)
    flag = "MONTH" if cfg.is_mcx(sym) else cfg.BOOK_FLAG[sym]
    fs = [f for f in lake_files(f"opt_{sym}_{flag}_*.parquet", lake) if int(f[-12:-8]) in _years(a, b)]
    if not fs:
        raise FileNotFoundError(f"no option book for {sym}")
    cols = ["ts", "offset", "side", "close", "iv", "volume", "oi", "spot"]
    d = pd.concat([pd.read_parquet(f, columns=cols) for f in fs], ignore_index=True)
    d["ts"] = _naive(d.ts).dt.floor("min")
    d["side"] = d.side.astype(str)
    d = d[(d.ts.dt.date >= a) & (d.ts.dt.date <= b)]
    d["iv"] = d.iv.astype("float64").where(d.iv > 0)
    d["volume"] = d.volume.astype("float64").where(d.volume >= 0)  # A1: Dhan's volume wraps past 2^32 (rare, < 0)
    return d.reset_index(drop=True)


def drop_foreign(book: pd.DataFrame, idx: pd.DataFrame, tol: float = cfg.FOREIGN_TOL) -> pd.DataFrame:
    """Rows whose spot is more than tol from the index close at that minute (the last earlier close of the same
    session if that minute has none) belong to another underlying (R47); rows with no index close are dropped too."""
    ref = idx[["ts", "c"]].sort_values("ts")
    b = book.sort_values("ts").reset_index(drop=True)
    m = pd.merge_asof(b, ref, on="ts", direction="backward")
    last_ts = pd.merge_asof(b[["ts"]], ref.assign(rt=ref.ts)[["ts", "rt"]], on="ts", direction="backward").rt
    ok = m.c.notna() & (last_ts.dt.normalize() == b.ts.dt.normalize()) & ((m.spot / m.c - 1).abs() <= tol)
    return b[ok.to_numpy()].reset_index(drop=True)


def chain_minute(book: pd.DataFrame) -> pd.DataFrame:
    """Option-chain state per minute: spot (median), pcr (put/call OI, all offsets), oi_c, oi_p, atm_iv (offset 0,
    both sides), skew (OTM put IV -5..-1 minus OTM call IV +1..+5), straddle (offset-0 call + put close), vol_c / vol_p
    (volume of offsets -3..+3) and vol (their sum)."""
    b = book
    g = b.groupby("ts")
    out = pd.DataFrame(dict(spot=g.spot.median()))
    oi = b.pivot_table(index="ts", columns="side", values="oi", aggfunc="sum")
    out["oi_c"], out["oi_p"] = oi.get("C"), oi.get("P")
    out["pcr"] = out.oi_p / out.oi_c.where(out.oi_c > 0)
    atm = b[b.offset == 0]
    out["atm_iv"] = atm.groupby("ts").iv.mean()
    st = atm.pivot_table(index="ts", columns="side", values="close", aggfunc="last")
    out["straddle"] = st.get("C") + st.get("P")
    otm_p = b[(b.side == "P") & b.offset.between(-5, -1)].groupby("ts").iv.mean()
    otm_c = b[(b.side == "C") & b.offset.between(1, 5)].groupby("ts").iv.mean()
    out["skew"] = otm_p - otm_c
    near = b[b.offset.abs() <= 3]
    v = near.pivot_table(index="ts", columns="side", values="volume", aggfunc="sum")
    out["vol_c"], out["vol_p"] = v.get("C"), v.get("P")
    out["vol"] = out.vol_c.fillna(0) + out.vol_p.fillna(0)
    return out.reset_index()


def mcx_minutes(chain: pd.DataFrame) -> pd.DataFrame:
    """MCX underlying minutes from the chain's median spot: close-only (o = h = l = c), MCX session only."""
    d = chain[["ts", "spot"]].dropna().rename(columns={"spot": "c"})
    d = d[MCXLIB.in_session(d.ts).to_numpy()]
    d = d.assign(o=d.c, h=d.c, l=d.c)[["ts", "o", "h", "l", "c"]].sort_values("ts").reset_index(drop=True)
    return clean_sessions(d, close_only=True)


def detect_expiries(chain: pd.DataFrame, jump: float = cfg.EXPIRY_JUMP, gap: int = cfg.EXPIRY_GAP) -> set:
    """Expiry days: the median straddle of the next session's first 10 minutes is > jump x that of the day's last
    10 minutes (the rolling book switched to the next contract), strongest first, at most one per `gap` sessions."""
    s = chain[["ts", "straddle"]].dropna()
    if not len(s):
        return set()
    s = s.assign(day=s.ts.dt.normalize())
    first = s.groupby("day").straddle.apply(lambda x: x.iloc[:10].median())
    last = s.groupby("day").straddle.apply(lambda x: x.iloc[-10:].median())
    days = list(first.index)
    ratio = pd.Series([first.iloc[i + 1] / last.iloc[i] if last.iloc[i] > 0 else np.nan for i in range(len(days) - 1)],
                      index=days[:-1])
    cand = ratio[ratio > jump].sort_values(ascending=False)
    pos = {d: i for i, d in enumerate(days)}
    chosen = []
    for d in cand.index:
        if all(abs(pos[d] - pos[c]) >= gap for c in chosen):
            chosen.append(d)
    return {d.date() for d in chosen}


def instrument(sym: str, lake=None, refresh: bool = False) -> dict:
    """{'min': underlying minutes, 'chain': chain state per minute, 'vix': VIX minutes or None, 'expiries': set}.
    Cached per instrument."""
    cfg.CACHE.mkdir(parents=True, exist_ok=True)
    pm, pc = cfg.CACHE / f"{sym}_min.parquet", cfg.CACHE / f"{sym}_chain.parquet"
    if pm.exists() and pc.exists() and not refresh and lake is None:
        mins, chain = pd.read_parquet(pm), pd.read_parquet(pc)
    else:
        book = option_book(sym, lake)
        if cfg.is_mcx(sym):
            chain = chain_minute(book)
            mins = mcx_minutes(chain)
        else:
            mins = index_minutes(sym, lake)
            chain = chain_minute(drop_foreign(book, mins))
        if lake is None:
            mins.to_parquet(pm), chain.to_parquet(pc)
    vix = None
    if not cfg.is_mcx(sym):
        pv = cfg.CACHE / "INDIAVIX_min.parquet"
        if pv.exists() and not refresh and lake is None:
            vix = pd.read_parquet(pv)
        else:
            vix = index_minutes("INDIAVIX", lake)
            if lake is None:
                vix.to_parquet(pv)
    return dict(min=mins, chain=chain, vix=vix, expiries=detect_expiries(chain))
