"""R104 data: minute series, bars and daily frames from the lake copy (never in git). Parsed as data only, no network.

A `Series` holds the VALID minutes of one instrument in time order (NaN minutes dropped), with each minute's session
index and minute of day (IST; UTC for nothing: crypto is mapped to IST days too). Sessions are contiguous. `bars(s, tf)`
builds tf-minute bars aligned to each session's open (NSE 09:15, MCX 09:00, crypto 00:00 IST); `i0` / `i1` are the first
and last minute of each bar, so a decision on a bar's close acts at minute `i1 + 1`.
"""
from __future__ import annotations

import pickle
from dataclasses import dataclass, field
from datetime import date
from functools import lru_cache

import numpy as np
import pandas as pd

import r104cfg as K


@dataclass
class Series:
    name: str
    market: str
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray
    ses: np.ndarray            # session index per minute
    tod: np.ndarray            # minute of day per minute
    days: list                 # date per session
    s0: np.ndarray             # first minute of each session
    s1: np.ndarray             # last minute of each session (inclusive)
    sq: np.ndarray             # per session: the minute at whose open an intraday position is squared off
    open_tod: int
    expiry: np.ndarray = field(default_factory=lambda: np.zeros(0, bool))
    meta: dict = field(default_factory=dict)

    @property
    def n(self) -> int:
        return len(self.c)


@dataclass
class Bars:
    tf: int
    o: np.ndarray
    h: np.ndarray
    l: np.ndarray
    c: np.ndarray
    v: np.ndarray
    i0: np.ndarray
    i1: np.ndarray
    ses: np.ndarray
    tod1: np.ndarray           # minute of day of the bar's last minute
    first: np.ndarray          # True on a session's first bar


def _sessions(ses: np.ndarray):
    chg = np.r_[True, ses[1:] != ses[:-1]]
    s0 = np.flatnonzero(chg)
    s1 = np.r_[s0[1:] - 1, len(ses) - 1]
    return s0, s1


def make_series(name, market, df: pd.DataFrame, open_tod: int, sq_fn, expiry=None) -> Series:
    """df: columns day (date), tod (int), open, high, low, close, volume; one row per valid minute."""
    df = df.sort_values(["day", "tod"]).drop_duplicates(["day", "tod"])
    df = df[np.isfinite(df.close.values) & (df.close.values > 0)]
    days = sorted(df.day.unique())
    dix = {d: i for i, d in enumerate(days)}
    ses = df.day.map(dix).values.astype(np.int64)
    s0, s1 = _sessions(ses)
    tod = df.tod.values.astype(np.int64)
    sq = np.array([sq_fn(days[i], tod[s0[i]:s1[i] + 1], s0[i]) for i in range(len(days))], dtype=np.int64)
    ex = np.array([bool(expiry and d in expiry) for d in days])
    f = lambda c: df[c].values.astype(np.float64)
    return Series(name, market, f("open"), f("high"), f("low"), f("close"), f("volume"), ses, tod, list(days), s0, s1,
                  sq, open_tod, ex)


def _first_at_or_after(tods: np.ndarray, base: int, t: int) -> int:
    j = int(np.searchsorted(tods, t))
    return base + min(j, len(tods) - 1) if j < len(tods) else base + len(tods)


# ------------------------------------------------------------------------------------------------ NSE index (stores)
@lru_cache(None)
def store(und: str) -> dict:
    """R103's builder output {date: Day} (idx (4,375), con {(side, strike): (5,375)}, expiry)."""
    import r51cfg  # noqa: F401  (Day must be importable for unpickling)
    import r51data  # noqa: F401
    with open(K.STORE / f"day_{und}.pkl", "rb") as f:
        return pickle.load(f)


def _nse_sq(expiry_days):
    def fn(d, tods, base):
        t = (15 * 60 + 5) if d in expiry_days else (15 * 60 + 10)
        return _first_at_or_after(tods, base, t)
    return fn


@lru_cache(None)
def idx_series(und: str) -> Series:
    """Index spot minutes from the option store (2021-07 warm-up .. 19 Oct 2026), same grid as the option prices."""
    S = store(und)
    rows = []
    for d in sorted(S):
        a = S[d].idx
        ok = np.isfinite(a[3])
        r = np.flatnonzero(ok)
        rows.append(pd.DataFrame(dict(day=d, tod=r + K.NSE_OPEN, open=a[0, r], high=a[1, r], low=a[2, r], close=a[3, r],
                                      volume=0.0)))
    df = pd.concat(rows, ignore_index=True)
    ex = {d for d, D in S.items() if D.expiry}
    s = make_series(und, "IDX", df, K.NSE_OPEN, _nse_sq(ex), ex)
    return s


def _read_1m(path: Path, cols=("open", "high", "low", "close", "volume")) -> pd.DataFrame:
    df = pd.read_parquet(path)
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    out = pd.DataFrame({c: df[c].values for c in cols if c in df})
    out["day"] = ts.dt.date.values
    out["tod"] = (ts.dt.hour * 60 + ts.dt.minute).values
    return out


@lru_cache(None)
def idx_series_long(und: str, start_year: int = 2017) -> Series:
    """Index spot minutes straight from index_1m (NIFTY 2017+, others 2021+), for IDXF and breadth."""
    parts = []
    for y in range(start_year, 2027):
        p = K.LAKE / "index_1m" / f"index_1m_{und}_{y}.parquet"
        if p.exists():
            parts.append(_read_1m(p))
    df = pd.concat(parts, ignore_index=True)
    df = df[(df.tod >= K.NSE_OPEN) & (df.tod < K.NSE_OPEN + K.NROWS)]
    return make_series(und, "IDXF", df, K.NSE_OPEN, _nse_sq(set()))


# ------------------------------------------------------------------------------------------------ MCX minutes
MCX_MINI = {"GOLD": "GOLDM", "SILVER": "SILVERM", "CRUDE": "CRUDEOILM", "NATURALGAS": "NATGASMINI"}


def _mcx_sq(d, tods, base):
    last = tods[-1]
    return _first_at_or_after(tods, base, last - 10)


@lru_cache(None)
def mcx_series(sym: str) -> Series:
    """One MCX future's continuous minutes (Oct 2025 - Oct 2026) with real volume; roll days flagged in meta."""
    df = pd.read_parquet(K.LAKE / "fut_1m_mcx_continuous" / "fut_1m_mcx_continuous.parquet")
    df = df[df.symbol == sym].copy()
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["day"] = ts.dt.date.values
    df["tod"] = (ts.dt.hour * 60 + ts.dt.minute).values
    df = df[df.tod >= 9 * 60]
    s = make_series(sym, "MCXI", df[["day", "tod", "open", "high", "low", "close", "volume"]], 9 * 60, _mcx_sq)
    roll = set(df.loc[df["roll"].astype(bool), "day"]) if "roll" in df else set()
    # a session whose contract differs from the previous session's is a roll session (the overnight gap is a roll gap)
    ex = df.groupby("day").expiry.first()
    prev = ex.shift(1)
    roll |= set(ex.index[(prev.notna()) & (ex != prev)])
    s.meta["roll_days"] = roll
    return s


# ------------------------------------------------------------------------------------------------ crypto minutes
@lru_cache(None)
def crypto_series(sym: str) -> Series:
    """Binance USD-M perp minutes (UTC ms) mapped to IST calendar days; square-off 23:50 IST."""
    root = K.LAKE / "crypto" / "futures_um" / "klines_1m" / sym
    parts = [pd.read_parquet(p) for p in sorted(root.rglob("*.parquet"))]
    df = pd.concat(parts, ignore_index=True)
    ts = pd.to_datetime(df.open_time.astype("int64"), unit="ms") + pd.Timedelta(minutes=330)
    out = pd.DataFrame(dict(open=df.open.astype(float).values, high=df.high.astype(float).values,
                            low=df.low.astype(float).values, close=df.close.astype(float).values,
                            volume=df.volume.astype(float).values))
    out["day"] = ts.dt.date.values
    out["tod"] = (ts.dt.hour * 60 + ts.dt.minute).values

    def sq(d, tods, base):
        return _first_at_or_after(tods, base, 23 * 60 + 50)
    return make_series(sym, "CRY", out, 0, sq)


# ------------------------------------------------------------------------------------------------ bars
def bars(s: Series, tf: int, anchor: int | None = None) -> Bars:
    """tf-minute bars aligned to the session open (or to `anchor` minutes of day)."""
    a = s.open_tod if anchor is None else anchor
    bucket = (s.tod - a) // tf
    key = s.ses * 100000 + bucket
    chg = np.r_[True, key[1:] != key[:-1]]
    i0 = np.flatnonzero(chg)
    i1 = np.r_[i0[1:] - 1, s.n - 1]
    o = s.o[i0]
    c = s.c[i1]
    h = np.maximum.reduceat(s.h, i0)
    l = np.minimum.reduceat(s.l, i0)
    v = np.add.reduceat(s.v, i0)
    ses = s.ses[i0]
    first = np.r_[True, ses[1:] != ses[:-1]]
    return Bars(tf, o, h, l, c, v, i0, i1, ses, s.tod[i1], first)


def half_session_bars(s: Series) -> Bars:
    """NSE 09:15-12:15 and 12:15-15:30 (Carli on ~4-hour bars)."""
    key = s.ses * 2 + (s.tod >= 12 * 60 + 15)
    chg = np.r_[True, key[1:] != key[:-1]]
    i0 = np.flatnonzero(chg)
    i1 = np.r_[i0[1:] - 1, s.n - 1]
    ses = s.ses[i0]
    return Bars(180, s.o[i0], np.maximum.reduceat(s.h, i0), np.minimum.reduceat(s.l, i0), s.c[i1],
                np.add.reduceat(s.v, i0), i0, i1, ses, s.tod[i1], np.r_[True, ses[1:] != ses[:-1]])


# ------------------------------------------------------------------------------------------------ daily frames
@lru_cache(None)
def index_daily(sym: str) -> pd.DataFrame:
    d = pd.read_parquet(K.LAKE / "index_daily" / "index_daily.parquet")
    d = d[d.symbol == sym].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    d = d.sort_values("date").drop_duplicates("date").set_index("date")[["open", "high", "low", "close"]]
    return d[(d.high >= d.low) & (d.close > 0) & (d.open > 0)]


@lru_cache(None)
def fut_daily(sym: str) -> pd.DataFrame:
    """Near-month continuous daily future with volume / OI, and a roll flag (a new contract that day)."""
    d = pd.read_parquet(K.LAKE / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol == sym) & (d.expiry_code == 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    d = d.sort_values("date").drop_duplicates("date").set_index("date")
    d = d[(d.high >= d.low) & (d.close > 0) & (d.open > 0)][["open", "high", "low", "close", "volume", "oi"]]
    d["roll"] = roll_flags(d)
    return d


def roll_flags(d: pd.DataFrame) -> np.ndarray:
    """A roll day = the continuous series switched contract: OI at least doubles overnight after OI had fallen to under
    half of its 20-day high (the old near contract running off into its expiry)."""
    oi = d.oi.values.astype(float)
    out = np.zeros(len(d), bool)
    for i in range(1, len(d)):
        prev = oi[i - 1]
        hi = np.nanmax(oi[max(0, i - 20):i]) if i > 0 else prev
        if prev > 0 and oi[i] >= 2.0 * prev and prev <= 0.5 * hi:
            out[i] = True
    return out


def back_adjusted(d: pd.DataFrame) -> pd.DataFrame:
    """Remove each roll day's open gap (open - previous close) from all earlier prices (additive back-adjustment)."""
    x = d.copy()
    adj = np.zeros(len(x))
    gaps = np.where(x.roll.values, x.open.values - np.r_[np.nan, x.close.values[:-1]], 0.0)
    gaps = np.nan_to_num(gaps)
    cum = np.cumsum(gaps[::-1])[::-1]
    adj = np.r_[cum[1:], 0.0]
    for c in ("open", "high", "low", "close"):
        x[c] = x[c].values + adj
    return x


@lru_cache(None)
def equity_daily(sym: str) -> pd.DataFrame:
    d = pd.read_parquet(K.LAKE / "equity_daily" / "equity_daily.parquet")
    d = d[d.symbol == sym].copy()
    d["date"] = pd.to_datetime(d.date).dt.date
    d = d.sort_values("date").drop_duplicates("date").set_index("date")[["open", "high", "low", "close", "volume"]]
    return d[(d.high >= d.low) & (d.close > 0) & (d.open > 0)]


@lru_cache(None)
def events() -> pd.DataFrame:
    e = pd.read_csv(K.ROOT / "research" / "context" / "events.csv")
    e["date"] = pd.to_datetime(e.date).dt.date
    return e
