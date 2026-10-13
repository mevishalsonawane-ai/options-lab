"""Adapter for the Dhan data lake (research/data/README.md, CATALOG.json; built by the data-lake session).

Planned files (Parquet, ts tz-aware IST, bar starts 09:15..15:29):
  index_1m_<SYM>_<YEAR>.parquet       ts, open, high, low, close, volume          (SYM incl. INDIAVIX)
  index_daily.parquet                 symbol, date, open, high, low, close, volume
  opt_<UND>_WEEK_<YEAR>.parquet       ts, offset (-10..+10), side C/P, open, high, low, close, iv, volume, strike, oi,
                                      spot -- Dhan "rollingoption": the contract that was ATM+offset at that minute,
                                      nearest expiry. No expiry column: the expiry comes from research.brain.calendar.
  fut_1m_live_index.parquet           symbol, expiry, security_id, ts, OHLC, volume, oi (contracts live on download day)

Get the files with `python research/data/fetch_release.py --dest $IRAALGO_LAKE` (default ~/.cache/iraalgo/lake), or
from Google Drive with `python -m research.brain.refresh --source lake` (rclone; the Drive keeps one sub-folder per
dataset, `index_1m/`, `options_rolling_1m/`, ...). Files are found by name anywhere under the lake folder, so the flat
release layout and the Drive layout both work. The catalog is research/data/CATALOG.json, or CATALOG.json in the lake
folder (the Drive copy). If no catalog or no files are present, available() is False and the build skips this source.

14 Oct 2026: reads are month by month (Parquet row filters on ts), so a full NIFTY options year is never in memory at
once. `full_sessions()` are the days the lake has WITH options; in `--source auto` the release keeps the days it has
when the lake has only the index for them (BANKNIFTY options were not on Drive yet), so no chain is lost.
"""
from __future__ import annotations

import json
import os
import re
from datetime import date, timedelta
from pathlib import Path
from typing import Iterator

import pandas as pd
import pyarrow.parquet as pq

from .. import calendar, config
from .base import FUT_COLS, OPTION_COLS, VIX_COLS, DayData, empty

REPO_ROOT = Path(__file__).resolve().parents[3]
CATALOG = REPO_ROOT / "research" / "data" / "CATALOG.json"


def _naive_ist(ts: pd.Series) -> pd.Series:
    ts = pd.to_datetime(ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert(config.TZ).dt.tz_localize(None)
    return ts


def pick_future(expiries, day: date) -> tuple[date | None, bool]:
    """The futures contract whose volume stands for the index on `day`: the near month, except on its expiry day (the
    roll day), when the next month is used. Expiry dates are scheduled, so this reads nothing after the day's start.
    Returns (expiry, rolled)."""
    ex = sorted({e for e in expiries if e is not None and e == e and e >= day})
    if not ex:
        return None, False
    if ex[0] == day and len(ex) > 1:
        return ex[1], True
    return ex[0], False


class LakeAdapter:
    name = "lake"

    def __init__(self, lake_dir: str | os.PathLike, catalog: str | os.PathLike | None = None):
        self.dir = Path(lake_dir)
        if catalog:
            self.catalog = Path(catalog)
        elif CATALOG.exists():
            self.catalog = CATALOG
        else:
            self.catalog = self.dir / "CATALOG.json"         # the Drive copy, next to the data
        self._sessions: dict[str, list[date]] = {}
        self._full: dict[str, list[date]] = {}
        self._index: dict[str, Path] | None = None

    def _all(self) -> dict[str, Path]:
        """file name -> path, anywhere under the lake folder (flat release layout or the Drive's sub-folders)."""
        if self._index is None:
            self._index = {}
            if self.dir.exists():
                for p in sorted(self.dir.rglob("*.parquet")):
                    self._index.setdefault(p.name, p)
        return self._index

    def _files(self, pattern: str) -> list[Path]:
        rx = re.compile(pattern)
        return [p for n, p in sorted(self._all().items()) if rx.fullmatch(n)]

    def _path(self, name: str) -> Path | None:
        return self._all().get(name)

    def available(self, und: str) -> bool:
        return self.catalog.exists() and bool(self._files(rf"index_1m_{und}_\d{{4}}\.parquet"))

    def fingerprint(self, und: str) -> str:
        tag = ""
        if self.catalog.exists():
            tag = json.loads(self.catalog.read_text()).get("release_tag", "")
        files = self._files(rf"(index_1m_({und}|INDIAVIX)_\d{{4}}|opt_{und}_WEEK_\d{{4}}|fut_1m_live_index)\.parquet")
        return "lake|" + tag + "|" + "|".join(f"{p.name}:{p.stat().st_size}" for p in files)

    def sessions(self, und: str) -> list[date]:
        if und not in self._sessions:
            s: set[date] = set()
            for p in self._files(rf"index_1m_{und}_\d{{4}}\.parquet"):
                ts = _naive_ist(pd.read_parquet(p, columns=["ts"]).ts)
                s |= set(ts.dt.date.unique())
            self._sessions[und] = sorted(s)
        return self._sessions[und]

    def full_sessions(self, und: str) -> list[date]:
        """Sessions with at least one option minute in the lake's option files. Only these outrank another source in
        `--source auto`."""
        if und not in self._full:
            have: set[date] = set()
            for p in self._files(rf"opt_{und}_WEEK_\d{{4}}\.parquet"):
                have |= set(_naive_ist(pd.read_parquet(p, columns=["ts"]).ts).dt.date.unique())
            self._full[und] = [d for d in self.sessions(und) if d in have]
        return self._full[und]

    def _read(self, name: str, frm: date, to: date) -> pd.DataFrame:
        """Rows of one file with frm <= ts.date <= to, filtered inside Parquet (only those rows are loaded)."""
        p = self._path(name)
        if p is None:
            return pd.DataFrame()
        tz = pq.read_schema(p).field("ts").type
        lo, hi = pd.Timestamp(frm), pd.Timestamp(to) + pd.Timedelta(days=1)
        if getattr(tz, "tz", None):
            lo, hi = lo.tz_localize(config.TZ), hi.tz_localize(config.TZ)
        return pd.read_parquet(p, filters=[("ts", ">=", lo), ("ts", "<", hi)])

    def _year_frame(self, stem: str, years: range, frm: date, to: date) -> pd.DataFrame:
        parts = [f for y in years if len(f := self._read(f"{stem}_{y}.parquet", frm, to))]
        if not parts:
            return pd.DataFrame()
        df = pd.concat(parts, ignore_index=True)
        df["ts"] = _naive_ist(df.ts)
        df = df[(df.ts.dt.date >= frm) & (df.ts.dt.date <= to)]
        df["d"] = df.ts.dt.date
        return df

    def load(self, und: str, frm: date, to: date) -> Iterator[DayData]:
        """One DayData per session, read one calendar month at a time."""
        if not self.available(und):
            return
        m = date(frm.year, frm.month, 1)
        while m <= to:
            nxt = date(m.year + (m.month == 12), m.month % 12 + 1, 1)
            yield from self._load(und, max(frm, m), min(to, nxt - timedelta(days=1)))
            m = nxt

    def _load(self, und: str, frm: date, to: date) -> Iterator[DayData]:
        years = range(frm.year, to.year + 1)
        ix = self._year_frame(f"index_1m_{und}", years, frm, to)
        if ix.empty:
            return
        opt = self._year_frame(f"opt_{und}_WEEK", years, frm, to)
        vix = self._year_frame("index_1m_INDIAVIX", years, frm, to)
        fut = pd.DataFrame()
        if self._path("fut_1m_live_index.parquet") is not None:
            fut = self._read("fut_1m_live_index.parquet", frm, to)
            fut = fut[fut.symbol.astype(str).str.upper().str.startswith(und)]
            fut["ts"] = _naive_ist(fut.ts)
            fut = fut[(fut.ts.dt.date >= frm) & (fut.ts.dt.date <= to)]
            if len(fut):
                fut["expiry"] = pd.to_datetime(fut.expiry).dt.date
                fut = fut.sort_values("expiry")
                fut["d"] = fut.ts.dt.date
        sess = set(self.sessions(und))
        ob = dict(tuple(opt.groupby("d"))) if len(opt) else {}
        vb = dict(tuple(vix.groupby("d"))) if len(vix) else {}
        fb = dict(tuple(fut.groupby("d"))) if len(fut) else {}
        for d, g in ix.groupby("d", sort=True):
            index = g[["ts", "open", "high", "low", "close", "volume"]].astype({"volume": float})
            if (index.volume.fillna(0) == 0).all():
                index["volume"] = float("nan")             # Dhan index volume is 0: no volume, not zero volume
            options = empty(OPTION_COLS)
            o = ob.get(d)
            exp, src = calendar.next_expiry(und, d, sess)
            if o is not None and len(o):
                options = pd.DataFrame(dict(ts=o.ts.values, expiry=exp, strike=o.strike.astype(float).values,
                                            side=o.side.astype(str).str[0].str.upper().values, open=o.open.values,
                                            high=o.high.values, low=o.low.values, close=o.close.values,
                                            volume=o.volume.astype(float).values, oi=o.oi.astype(float).values,
                                            iv_vendor=o.iv.astype(float).values if "iv" in o else float("nan")))
                options = options.drop_duplicates(["ts", "strike", "side"], keep="first")
            f = fb.get(d)
            fdf = empty(FUT_COLS)
            f_exp, f_roll = None, False
            if f is not None and len(f):
                f_exp, f_roll = pick_future(f.expiry, d)
                if f_exp is not None:
                    near = f[f.expiry == f_exp]
                    fdf = near[FUT_COLS].astype({c: float for c in FUT_COLS if c != "ts"})
            v = vb.get(d)
            vdf = v[["ts", "close"]] if v is not None and len(v) else empty(VIX_COLS)
            yield DayData(day=d, und=und, index=index.reset_index(drop=True), options=options.reset_index(drop=True),
                          fut=fdf.reset_index(drop=True), vix=vdf.reset_index(drop=True), fut_expiry=f_exp,
                          fut_roll=f_roll,
                          sources=dict(index=f"lake:index_1m_{und}", options=f"lake:opt_{und}_WEEK expiry={src}",
                                       fut=f"lake:fut_1m_live_index expiry={f_exp}" if len(fdf) else "",
                                       vix="lake:index_1m_INDIAVIX" if len(vdf) else ""))
