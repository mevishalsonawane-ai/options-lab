"""R102 data: the Drive lake copies (never in git), parsed as data only. No network.

Environment: R102_LAKE (the folder that holds index_1m/, index_daily/, options_rolling_1m/, participants/), R102_OUT
(results; default research/hunt/r102_deep/results). Every loader returns tidy pandas frames in IST, naive timestamps.

Holdout (PREREG.md): design <= 14 Jul 2026, holdout 15 Jul - 19 Oct 2026. `cut(df, part)` applies it; the holdout
part is served only when results/HOLDOUT_READ exists (written by r102pred.py holdout, which refuses a second read).
"""
from __future__ import annotations

import csv
import io
import os
import re
import sys
import zipfile
from datetime import date, timedelta
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from research.hunt.of1.load import parse_participant  # noqa: E402

LAKE = Path(os.environ.get("R102_LAKE", "/dev/shm/lake")).expanduser()
OUT = Path(os.environ.get("R102_OUT", HERE / "results")).expanduser()
HOLDOUT_FLAG = OUT / "HOLDOUT_READ"

DESIGN_END = date(2026, 7, 14)
HOLDOUT_START = date(2026, 7, 15)
HOLDOUT_END = date(2026, 10, 19)
UNDS = ("NIFTY", "BANKNIFTY")
OTHERS = ("FINNIFTY", "SENSEX", "MIDCPNIFTY")
STEP = {"NIFTY": 50.0, "BANKNIFTY": 100.0, "FINNIFTY": 50.0, "MIDCPNIFTY": 25.0, "SENSEX": 100.0}
N_MIN = 375                     # 09:15 .. 15:29
OPEN_MIN = 9 * 60 + 15


def minute_index(h: int, m: int) -> int:
    """Minute of the session: 09:15 -> 0, 15:29 -> 374."""
    return h * 60 + m - OPEN_MIN


def period_of(d: date) -> str:
    if d <= DESIGN_END:
        return "design"
    if HOLDOUT_START <= d <= HOLDOUT_END:
        return "holdout"
    return "after"


def cut(df: pd.DataFrame, part: str, col: str = "day") -> pd.DataFrame:
    """The rows of one period. 'holdout' and 'all' need the holdout flag (one read, r102pred.py holdout)."""
    if part not in ("design", "holdout", "all"):
        raise ValueError(part)
    if part != "design" and not HOLDOUT_FLAG.exists():
        raise PermissionError("the holdout is locked: only `r102pred.py holdout` may read it (PREREG.md)")
    days = pd.to_datetime(df[col]).dt.date if not isinstance(df.index, pd.DatetimeIndex) or col in df.columns else pd.Series(df.index.date, index=df.index)
    if part == "all":
        return df[days <= HOLDOUT_END]
    return df[days.map(period_of) == part]


# ---- daily -----------------------------------------------------------------------------------------------------------

@lru_cache(None)
def daily(sym: str) -> pd.DataFrame:
    """Daily OHLC of one index (date index, naive), bad rows dropped (high < low, close <= 0, duplicate dates)."""
    d = pd.read_parquet(LAKE / "index_daily" / "index_daily.parquet")
    d = d[d.symbol == sym].copy()
    d["date"] = pd.to_datetime(d.date)
    d = d.sort_values("date").drop_duplicates("date").set_index("date")[["open", "high", "low", "close"]]
    d = d[(d.high >= d.low) & (d.close > 0) & (d.open > 0)]
    return d


# ---- minutes ---------------------------------------------------------------------------------------------------------

def _read_minutes(path: Path) -> pd.DataFrame:
    df = pd.read_parquet(path, columns=["ts", "open", "high", "low", "close"])
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["day"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - OPEN_MIN
    df = df[(df.m >= 0) & (df.m < N_MIN)].drop(columns=["ts"])
    return df[(df.high >= df.low) & (df.close > 0)]


@lru_cache(None)
def minutes(sym: str) -> pd.DataFrame:
    """Every 1-minute bar of one index: day, m (0..374), open, high, low, close. Sorted, duplicates dropped."""
    parts = [_read_minutes(p) for p in sorted((LAKE / "index_1m").glob(f"index_1m_{sym}_*.parquet"))]
    if not parts:
        return pd.DataFrame(columns=["day", "m", "open", "high", "low", "close"])
    df = pd.concat(parts, ignore_index=True).sort_values(["day", "m"]).drop_duplicates(["day", "m"])
    return df.reset_index(drop=True)


def full_days(df: pd.DataFrame, min_bars: int = 360) -> pd.DataFrame:
    """Only days with at least `min_bars` bars (a short day or a feed gap would distort range-by-hour tables)."""
    n = df.groupby("day").m.transform("size")
    return df[n >= min_bars]


def session_grid(df: pd.DataFrame) -> tuple[np.ndarray, list[date]]:
    """(days x 375 x 4 array of OHLC with NaN gaps, the day list), from a minutes frame."""
    days = sorted(df.day.unique())
    idx = {d: i for i, d in enumerate(days)}
    a = np.full((len(days), N_MIN, 4), np.nan)
    a[df.day.map(idx).values, df.m.values] = df[["open", "high", "low", "close"]].values.astype(float)
    return a, days


# ---- options (rolling ATM +-10, nearest expiry) ----------------------------------------------------------------------

def option_years(und: str) -> list[int]:
    out = []
    for p in sorted((LAKE / "options_rolling_1m").glob(f"opt_{und}_WEEK_*.parquet")):
        m = re.search(r"_(\d{4})\.parquet$", p.name)
        if m:
            out.append(int(m.group(1)))
    return out


def options(und: str, year: int, offsets=None, minutes_=None) -> pd.DataFrame:
    """One year of the rolling option book: day, m, offset, side, close, iv, strike, oi, spot (+ open/high/low)."""
    path = LAKE / "options_rolling_1m" / f"opt_{und}_WEEK_{year}.parquet"
    filters = []
    if offsets is not None:
        filters.append(("offset", "in", [int(x) for x in offsets]))
    df = pd.read_parquet(path, filters=filters or None,
                         columns=["ts", "offset", "side", "open", "high", "low", "close", "iv", "strike", "oi", "volume", "spot"])
    ts = pd.to_datetime(df.ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None)
    df["day"] = ts.dt.date
    df["m"] = ((ts - ts.dt.normalize()).dt.total_seconds() // 60).astype(int) - OPEN_MIN
    df = df.drop(columns=["ts"])
    df = df[(df.m >= 0) & (df.m < N_MIN) & (df.close > 0)]
    if minutes_ is not None:
        df = df[df.m.isin(list(minutes_))]
    df["side"] = df.side.astype(str)
    return df.reset_index(drop=True)


# ---- F&O bhavcopy (index rows): the expiry calendar and the end-of-day chain ------------------------------------------

@lru_cache(None)
def bhav(und: str) -> pd.DataFrame:
    """Every option row of one underlying from the index-only bhavcopy parquets: date, kind, expiry, strike, close,
    settle, contracts, oi_qty, chg_oi_qty, und_px."""
    parts = []
    for p in sorted((LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
        b = pd.read_parquet(p)
        b = b[(b.und == und) & (b.kind.isin(["CE", "PE"]))]
        parts.append(b)
    if not parts:
        return pd.DataFrame()
    b = pd.concat(parts, ignore_index=True)
    b["date"] = pd.to_datetime(b.date)
    b["expiry"] = pd.to_datetime(b.expiry)
    return b


@lru_cache(None)
def expiries(und: str) -> pd.DataFrame:
    """One row per trading day: nearest expiry (date), is_expiry, is_monthly_expiry, days_to_expiry (calendar), from the
    bhavcopy rows with open interest. Weekly vs monthly comes from the data (the last expiry of a month is monthly),
    so the Thursday -> Wednesday -> Tuesday changes need no table."""
    b = bhav(und)
    b = b[(b.oi_qty > 0) | (b.contracts > 0)]
    near = b.groupby("date").expiry.min().rename("expiry").to_frame()
    all_exp = sorted(set(b.expiry.dt.date))
    monthly = set()
    by_month: dict[tuple[int, int], list[date]] = {}
    for e in all_exp:
        by_month.setdefault((e.year, e.month), []).append(e)
    for k, es in by_month.items():
        monthly.add(max(es))
    near["day"] = near.index.date
    near["is_expiry"] = near.expiry.dt.date == near.day
    near["is_monthly_expiry"] = near.is_expiry & near.expiry.dt.date.isin(monthly)
    near["days_to_expiry"] = (near.expiry.dt.date - near.day).map(lambda x: x.days)
    return near.reset_index(drop=True)


def eod_chain(und: str, d: date, expiry: date | None = None) -> pd.DataFrame:
    """The end-of-day chain of one expiry (nearest by default) on day d: strike, kind, close, oi_qty, und_px."""
    b = bhav(und)
    x = b[b.date == pd.Timestamp(d)]
    if expiry is None:
        x2 = x[(x.oi_qty > 0)]
        if x2.empty:
            return x.iloc[0:0]
        expiry = x2.expiry.min()
    return x[x.expiry == pd.Timestamp(expiry)].sort_values(["strike", "kind"])


# ---- participants ----------------------------------------------------------------------------------------------------

@lru_cache(None)
def participants() -> pd.DataFrame:
    """One row per date (index): the 14 NSE participant counts per group as <group>_<col> (FII_fut_idx_long ...)."""
    rows = []
    for zp in sorted((LAKE / "participants" / "oi").glob("oi_*.zip")):
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                m = re.search(r"(\d{2})(\d{2})(\d{4})\.csv$", n)
                if not m:
                    continue
                try:
                    t = parse_participant(z.read(n).decode("utf-8", "replace"))
                except ValueError:
                    continue
                d = pd.Timestamp(int(m.group(3)), int(m.group(2)), int(m.group(1)))
                r = {"date": d}
                for g in ("Client", "DII", "FII", "Pro", "TOTAL"):
                    for col in t.columns:
                        r[f"{g}_{col}"] = float(t.loc[g, col])
                rows.append(r)
    if not rows:
        return pd.DataFrame()
    return pd.DataFrame(rows).drop_duplicates("date").sort_values("date").set_index("date")


@lru_cache(None)
def fpi() -> pd.DataFrame:
    p = LAKE / "participants" / "fpi" / "fpi_daily.csv"
    f = pd.read_csv(p)
    f["report_date"] = pd.to_datetime(f.report_date)
    return f.drop_duplicates("report_date").sort_values("report_date").set_index("report_date")


# ---- events (R98's list) ---------------------------------------------------------------------------------------------

@lru_cache(None)
def events() -> pd.DataFrame:
    """R98's event list: category, event_date, session (the NSE session that first trades on the news)."""
    p = ROOT / "research" / "hunt" / "r98" / "events.csv"
    e = pd.read_csv(p)
    e["session"] = pd.to_datetime(e.session).dt.date
    e["event_date"] = pd.to_datetime(e.event_date).dt.date
    return e


def event_sessions(categories) -> set:
    e = events()
    return set(e[e.category.isin(list(categories))].session)


# ---- helpers ---------------------------------------------------------------------------------------------------------

def weekday_name(d: date) -> str:
    return ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"][d.weekday()]


def bh(pvals: list[float]) -> list[float]:
    """Benjamini-Hochberg q-values, in the input order."""
    p = np.asarray(pvals, dtype=float)
    n = len(p)
    if n == 0:
        return []
    order = np.argsort(p)
    ranked = p[order] * n / (np.arange(n) + 1)
    q = np.minimum.accumulate(ranked[::-1])[::-1]
    out = np.empty(n)
    out[order] = np.clip(q, 0, 1)
    return out.tolist()
