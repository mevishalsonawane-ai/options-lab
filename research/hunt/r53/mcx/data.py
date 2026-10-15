"""R53 MCX data (PREREG.md sections 1-2, written 15 Oct 2026).

- The near future's minute price = the per-minute median of the option rows' `spot` (Dhan rollingoption, near month).
  It is a CLOSE-only series: every bar R53 builds from it (5m, 15m) has o/h/l/c taken from minute closes, and a futures
  "fill" is a minute close (the next minute's close after the signal), never a true open.
- Options: one held contract per trade = (day, side, strike). Only printed minutes (volume > 0) price a trade (M3
  amendment 2). Prints flagged by R42's F1 filter (k 5, 15 min, ratio 1.5, run on the printed rows as R42 did for M4)
  are turned into "no print" first.
- Expiry days: M3's rule (m3lib.Market.expiries), made robust (detect_expiries): the median of a day's first 10 ATM
  straddle values over the median of the previous day's last 10 > 2.5, strongest first, one per 15 days; the day before
  the jump is the expiry day. On the lake the rolling series switches contracts at the next session's start (checked:
  every month of CRUDEOIL, NATURALGAS, GOLDM and SILVERM shows one such jump), so a (day, side, strike) key never mixes
  two expiries. Option trades are not taken on expiry days (M3 run_book).
- Session end: 23:30 (US summer time) / 23:55 (US winter time), or the day's last bar + 1 minute if earlier.

Paths: the lake comes from IRAALGO_LAKE (common.lake_dir()); Yahoo and XAUUSD inputs from IRAALGO_CACHE
(<cache>/yahoo/h/*.json, <cache>/xau/xauusd_m1_bid_to20261012.csv.gz). Processed markets are cached under
common.cache_dir()/mcx (outside git).
"""
from __future__ import annotations

import json
import os
import pickle
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C
from research.hunt.r42.spikes import flag_spikes

# lot multipliers and futures ticks (copied from research/hunt/m3/m3lib.py MULT / TICK / FAMILY, 8 Oct 2026)
MULT = {"CRUDEOIL": 100, "CRUDEOILM": 10, "NATURALGAS": 1250, "NATGASMINI": 250, "GOLD": 100, "GOLDM": 10,
        "SILVER": 30, "SILVERM": 5}
TICK = {"CRUDEOIL": 1.0, "CRUDEOILM": 1.0, "NATURALGAS": 0.1, "NATGASMINI": 0.1, "GOLD": 1.0, "GOLDM": 1.0,
        "SILVER": 1.0, "SILVERM": 1.0, "GOLDTEN": 1.0, "SILVERMIC": 1.0, "GOLDPETAL": 1.0}
FAMILY = {"CRUDEOIL": "crude", "CRUDEOILM": "crude", "NATURALGAS": "natgas", "NATGASMINI": "natgas",
          "GOLD": "gold", "GOLDM": "gold", "GOLDTEN": "gold", "GOLDPETAL": "gold",
          "SILVER": "silver", "SILVERM": "silver", "SILVERMIC": "silver"}
# futures proxies (PREREG section 4): product -> (signal / price series, units per point of that series' quote)
FUT_PROXY = {"CRUDEOILM": ("CRUDEOIL", 10.0), "NATGASMINI": ("NATURALGAS", 250.0), "GOLDM": ("GOLDM", 10.0),
             "GOLDTEN": ("GOLDM", 1.0), "SILVERMIC": ("SILVERM", 1.0)}

OPEN_M = 9 * 60


def us_dst(d) -> bool:
    """US Eastern daylight time on this date (2nd Sunday of March to 1st Sunday of November), any year
    (research/hunt/r2/lib.py us_dst)."""
    d = pd.Timestamp(d)
    y = d.year
    mar = pd.Timestamp(y, 3, 1)
    s2 = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(y, 11, 1)
    s1 = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return bool(s2 <= d.normalize() < s1)


def ext_dir() -> Path:
    return Path(os.environ.get("IRAALGO_CACHE", os.path.expanduser("~/.cache/iraalgo")))


def work_dir() -> Path:
    p = C.cache_dir() / "mcx"
    p.mkdir(parents=True, exist_ok=True)
    return p


def session_close(d, last_bar_tod: int) -> int:
    """MCX session end (minute of day IST): 23:30 in US summer time, 23:55 in US winter time (McxSession.kt), or
    earlier when the day's data stop earlier (a short session). A stray bar at or after the close is ignored."""
    std = 23 * 60 + 30 if us_dst(d) else 23 * 60 + 55
    return int(min(std, last_bar_tod + 1))


def detect_expiries(day, strad, thr: float = 2.5, spacing_days: int = 15) -> np.ndarray:
    """day: datetime64 per minute; strad: ATM straddle per minute (NaN where unknown). Returns expiry days."""
    df = pd.DataFrame({"day": day, "s": strad}).dropna()
    if df.empty:
        return np.array([], dtype="datetime64[ns]")
    g = df.groupby("day").s
    first = g.apply(lambda x: x.iloc[:10].median())
    last = g.apply(lambda x: x.iloc[-10:].median())
    jump = (first / last.shift(1)).dropna()
    days = list(first.index)
    acc = []
    for d, j in sorted(((d, j) for d, j in jump.items() if j > thr), key=lambda x: -x[1]):
        if all(abs((d - x).days) >= spacing_days for x in acc):
            acc.append(d)
    return np.array(sorted(days[days.index(d) - 1] for d in acc), dtype="datetime64[ns]")


# ------------------------------------------------------------------------------------------------ market
class Market:
    """One option underlying on its minute grid.

    ts (datetime64 naive IST), F (near-future close), day (datetime64 day), tod (minute of day), seg (contract segment,
    changes the day after an option expiry), is_exp (expiry day), day_first / day_last / day_cut (cut = last minute at
    or before the session end - 10 min), strikes by offset at each minute (k_at[(cp, offset)], forward-filled within
    the day), and the printed option legs by contract (day, cp, strike)."""

    def __init__(self, sym: str, ts, F, opt: pd.DataFrame | None):
        self.sym = sym
        self.ts = np.asarray(ts, dtype="datetime64[ns]")
        self.F = np.asarray(F, dtype=float)
        t = pd.DatetimeIndex(self.ts)
        self.day = t.normalize().values
        self.tod = (t.hour * 60 + t.minute).values.astype(np.int32)
        self.days = np.unique(self.day)
        self._index_days()
        self.legs = {}
        self.k_at = {}
        self.exp_days = np.array([], dtype="datetime64[ns]")
        if opt is not None and len(opt):
            self._options(opt)
        self.seg = np.searchsorted(self.exp_days, self.day, side="left").astype(np.int32)
        self.is_exp = np.isin(self.day, self.exp_days)

    # ---- day index
    def _index_days(self):
        self.day_first, self.day_last, self.day_cut = {}, {}, {}
        if not len(self.ts):
            return
        b = np.r_[0, np.nonzero(self.day[1:] != self.day[:-1])[0] + 1, len(self.ts)]
        for a, z in zip(b[:-1], b[1:]):
            d = self.day[a]
            ix = np.arange(a, z)
            end = session_close(d, int(self.tod[z - 1]))
            ok = ix[self.tod[ix] <= end - 1 - 10]
            self.day_first[d], self.day_last[d] = int(a), int(z - 1)
            self.day_cut[d] = int(ok[-1]) if len(ok) else int(z - 1)

    def day_ix(self, d):
        d = np.datetime64(pd.Timestamp(d).normalize(), "ns")
        if d not in self.day_first:
            return np.array([], dtype=int)
        return np.arange(self.day_first[d], self.day_last[d] + 1)

    def at_or_before(self, d, tmin):
        """Last grid index of day d with tod <= tmin, or -1."""
        ix = self.day_ix(d)
        ok = ix[self.tod[ix] <= tmin]
        return int(ok[-1]) if len(ok) else -1

    def at_or_after(self, d, tmin, until=None):
        ix = self.day_ix(d)
        ok = ix[self.tod[ix] >= tmin]
        if until is not None:
            ok = ok[self.tod[ok] <= until]
        return int(ok[0]) if len(ok) else -1

    def session_end(self, d) -> int:
        d = np.datetime64(pd.Timestamp(d).normalize(), "ns")
        return session_close(d, int(self.tod[self.day_last[d]]))

    # ---- options
    def _options(self, O: pd.DataFrame):
        """O: ts (naive IST), cp (0 call / 1 put), offset, strike, open, high, low, close, volume, f1 (bool)."""
        idx = pd.Series(np.arange(len(self.ts)), index=pd.DatetimeIndex(self.ts))
        O = O[O.ts.isin(idx.index)].copy()
        O["i"] = idx.loc[O.ts].values
        # strikes by offset on the grid, forward-filled within the day
        for (cp, k), g in O.groupby(["cp", "offset"]):
            a = np.full(len(self.ts), np.nan)
            a[g.i.values] = g.strike.values
            s = pd.Series(a).groupby(self.day).ffill().values
            self.k_at[(int(cp), int(k))] = s
        # expiry days: M3's day-boundary straddle jump (m3lib.Market.expiries), made robust to single bad prints and
        # missing first minutes: the median of a day's first 10 ATM-straddle values over the median of the previous
        # day's last 10, > 2.5; strongest first, one per 15 days; the day before the jump is the expiry day.
        a0 = O[O.offset == 0].pivot_table(index="i", columns="cp", values="close")
        strad = np.full(len(self.ts), np.nan)
        if 0 in a0 and 1 in a0:
            strad[a0.index.values] = (a0[0] + a0[1]).values
        self.exp_days = detect_expiries(self.day, strad)
        # printed, unflagged legs per contract (day, cp, strike)
        P = O[(O.volume > 0) & ~O.f1 & (O.close > 0)].copy()
        P["day"] = self.day[P.i.values]
        P = P.sort_values(["day", "cp", "strike", "i"]).drop_duplicates(["i", "cp", "strike"])
        vals = P[["open", "high", "low", "close", "volume"]].to_numpy(float)
        ii = P.i.to_numpy(np.int64)
        keys = list(zip(P.day.values, P.cp.values.astype(int), np.round(P.strike.values, 4)))
        if len(keys):
            brk = np.r_[0, np.nonzero([keys[n] != keys[n - 1] for n in range(1, len(keys))])[0] + 1, len(keys)]
            for a, z in zip(brk[:-1], brk[1:]):
                self.legs[keys[a]] = (ii[a:z], vals[a:z])

    def strike(self, cp: int, offset: int, j: int) -> float:
        a = self.k_at.get((cp, offset))
        return float(a[j]) if a is not None else float("nan")

    def leg(self, d, cp: int, strike: float):
        """Printed minutes of one held contract: (grid indices, [o, h, l, c, v] rows), or None."""
        d = np.datetime64(pd.Timestamp(d).normalize(), "ns")
        return self.legs.get((d, int(cp), round(float(strike), 4)))


# ------------------------------------------------------------------------------------------------ loaders
def lake_options(sym: str, lake: Path | None = None) -> pd.DataFrame:
    lake = Path(lake or C.lake_dir())
    fs = sorted((lake / "options_rolling_1m").glob(f"opt_{sym}_MONTH_20*.parquet"))
    if not fs:
        raise FileNotFoundError(f"no lake option files for {sym} under {lake}")
    d = pd.concat([pd.read_parquet(f) for f in fs], ignore_index=True)
    d = d[d.offset.between(-3, 3)]
    d["ts"] = d.ts.dt.tz_convert("Asia/Kolkata").dt.tz_localize(None) if d.ts.dt.tz is not None else d.ts
    d["side"] = d.side.astype(str)
    return d.reset_index(drop=True)


def build_market(sym: str, raw: pd.DataFrame) -> Market:
    """raw: lake rows (ts naive IST, offset, side, strike, spot, OHLC, volume)."""
    sp = raw[raw.spot > 0].groupby("ts").spot.median().sort_index()
    O = raw[["ts", "offset", "side", "strike", "open", "high", "low", "close", "volume"]].copy()
    O["cp"] = np.where(O.side == "C", 0, 1).astype(np.int8)
    tr = O[O.volume > 0]
    O["f1"] = False
    if len(tr):
        fl = flag_spikes(tr[["ts", "side", "strike", "close", "high", "low"]])
        O.loc[fl.index[fl.f1.values], "f1"] = True
    return Market(sym, sp.index.values, sp.values, O)


def load_market(sym: str, rebuild: bool = False) -> Market:
    p = work_dir() / f"market_{sym}.pkl"
    if p.exists() and not rebuild:
        with open(p, "rb") as f:
            return pickle.load(f)
    m = build_market(sym, lake_options(sym))
    with open(p, "wb") as f:
        pickle.dump(m, f, protocol=pickle.HIGHEST_PROTOCOL)
    return m


def load_daily(sym: str, lake: Path | None = None) -> pd.DataFrame:
    """Near-month continuous daily (fut_daily_continuous), one row per date, indexed by date."""
    lake = Path(lake or C.lake_dir())
    d = pd.read_parquet(lake / "fut_daily_continuous" / "fut_daily_continuous.parquet")
    d = d[(d.symbol == sym) & (d.expiry_code == 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    d = d[(d.close > 0) & (d.open > 0)].drop_duplicates("date", keep="last").set_index("date").sort_index()
    return d[["open", "high", "low", "close", "volume", "oi"]]


def yahoo_hourly(name: str, root: Path | None = None) -> pd.DataFrame:
    """Yahoo chart JSON (raw, untrusted: parsed with json only) -> UTC-naive hourly bars (index = bar start)."""
    root = Path(root or ext_dir() / "yahoo" / "h")
    with open(root / f"{name}.json") as f:
        j = json.load(f)
    r = j["chart"]["result"][0]
    q = r["indicators"]["quote"][0]
    df = pd.DataFrame({"open": q.get("open"), "close": q.get("close")},
                      index=pd.to_datetime(np.asarray(r["timestamp"], dtype="int64"), unit="s"))
    df = df.apply(pd.to_numeric, errors="coerce")
    df = df[(df.open > 0) | (df.close > 0)]
    df = df[~df.index.duplicated(keep="last")].sort_index()
    return df


def px_at(y: pd.DataFrame, t) -> float:
    """Price at UTC time t: the open of the bar starting at t, else the close of the last bar starting before t, at most
    2 hours old (research/hunt/r2/hourly.py px_at; app McxUsSilverRules.priceAt)."""
    t = pd.Timestamp(t)
    i = y.index.searchsorted(t)
    if i < len(y) and y.index[i] == t and np.isfinite(y.open.iloc[i]) and y.open.iloc[i] > 0:
        return float(y.open.iloc[i])
    if i > 0 and t - y.index[i - 1] <= pd.Timedelta(hours=2):
        v = y.close.iloc[i - 1]
        return float(v) if np.isfinite(v) and v > 0 else float("nan")
    return float("nan")


def xau_minutes(path: Path | None = None) -> pd.Series:
    """XAUUSD 1-minute bid closes, index = bar start in IST (naive)."""
    path = Path(path or ext_dir() / "xau" / "xauusd_m1_bid_to20261012.csv.gz")
    p = work_dir() / "xau_close_ist.pkl"
    if p.exists():
        return pd.read_pickle(p)
    x = pd.read_csv(path, usecols=["timestamp", "close"])
    s = pd.Series(x.close.values.astype(float),
                  index=pd.to_datetime(x.timestamp.values, unit="ms") + pd.Timedelta(hours=5, minutes=30))
    s = s[~s.index.duplicated(keep="last")].sort_index()
    s.to_pickle(p)
    return s


# ------------------------------------------------------------------------------------------------ EIA calendar
# EIA WPSR official exceptions 2025-26 (eia.gov schedule, copied from research/hunt/nn_crude/calendar_events.py,
# fetched there 8 Oct 2026): normal Wednesday -> (actual date, ET time).
EIA_EXC = {
    "2025-01-01": ("2025-01-02", "11:00"), "2025-01-22": ("2025-01-23", "12:00"), "2025-02-19": ("2025-02-20", "12:00"),
    "2025-05-28": ("2025-05-29", "12:00"), "2025-09-03": ("2025-09-04", "12:00"), "2025-10-15": ("2025-10-16", "12:00"),
    "2025-11-12": ("2025-11-13", "12:00"), "2025-12-24": ("2025-12-29", "17:00"), "2026-01-21": ("2026-01-22", "12:00"),
    "2026-02-18": ("2026-02-19", "12:00"), "2026-05-27": ("2026-05-28", "12:00"), "2026-09-09": ("2026-09-10", "12:00"),
    "2026-10-14": ("2026-10-15", "12:00"), "2026-11-11": ("2026-11-12", "12:00")}


def eia_releases(start: date, end: date) -> dict:
    """{IST day (Timestamp): release minute of day IST}: Wednesday 10:30 ET, DST-exact, official exceptions applied.
    A release outside the IST day it falls on is still keyed by its IST date."""
    from zoneinfo import ZoneInfo
    ET = ZoneInfo("America/New_York")
    out = {}
    for w in pd.date_range(pd.Timestamp(start) - pd.Timedelta(days=7), pd.Timestamp(end), freq="W-WED"):
        k = w.date().isoformat()
        dd, hm = (EIA_EXC[k][0], EIA_EXC[k][1]) if k in EIA_EXC else (k, "10:30")
        h, mi = map(int, hm.split(":"))
        y, mo, da = map(int, dd.split("-"))
        t = pd.Timestamp(year=y, month=mo, day=da, hour=h, minute=mi, tz=ET).tz_convert("Asia/Kolkata")
        if pd.Timestamp(start) <= t.tz_localize(None).normalize() <= pd.Timestamp(end):
            out[t.tz_localize(None).normalize()] = t.hour * 60 + t.minute
    return out
