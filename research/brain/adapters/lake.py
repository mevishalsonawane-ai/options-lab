"""Adapter for the Dhan data lake (research/data/README.md, CATALOG.json; built by the data-lake session).

Planned files (Parquet, ts tz-aware IST, bar starts 09:15..15:29):
  index_1m_<SYM>_<YEAR>.parquet       ts, open, high, low, close, volume          (SYM incl. INDIAVIX)
  index_daily.parquet                 symbol, date, open, high, low, close, volume
  opt_<UND>_WEEK_<YEAR>.parquet       ts, offset (-10..+10), side C/P, open, high, low, close, iv, volume, strike, oi,
                                      spot -- Dhan "rollingoption": the contract that was ATM+offset at that minute,
                                      nearest expiry. No expiry column: the expiry comes from research.brain.calendar.
  fut_1m_live_index.parquet           symbol, expiry, security_id, ts, OHLC, volume, oi (contracts live on download day)

Get the files with `python research/data/fetch_release.py --dest $IRAALGO_LAKE` (default ~/.cache/iraalgo/lake).
If CATALOG.json or the files are absent, available() is False and the cube builder skips this source cleanly.
"""
from __future__ import annotations

import json
import os
import re
from datetime import date
from pathlib import Path
from typing import Iterator

import pandas as pd

from .. import calendar, config
from .base import FUT_COLS, OPTION_COLS, VIX_COLS, DayData, empty

REPO_ROOT = Path(__file__).resolve().parents[3]
CATALOG = REPO_ROOT / "research" / "data" / "CATALOG.json"


def _naive_ist(ts: pd.Series) -> pd.Series:
    ts = pd.to_datetime(ts)
    if ts.dt.tz is not None:
        ts = ts.dt.tz_convert(config.TZ).dt.tz_localize(None)
    return ts


class LakeAdapter:
    name = "lake"

    def __init__(self, lake_dir: str | os.PathLike, catalog: str | os.PathLike | None = None):
        self.dir = Path(lake_dir)
        self.catalog = Path(catalog) if catalog else CATALOG
        self._sessions: dict[str, list[date]] = {}

    def _files(self, pattern: str) -> list[Path]:
        if not self.dir.exists():
            return []
        rx = re.compile(pattern)
        return sorted(p for p in self.dir.iterdir() if rx.fullmatch(p.name))

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

    def _year_frame(self, stem: str, years: range, frm: date, to: date) -> pd.DataFrame:
        parts = []
        for y in years:
            p = self.dir / f"{stem}_{y}.parquet"
            if p.exists():
                parts.append(pd.read_parquet(p))
        if not parts:
            return pd.DataFrame()
        df = pd.concat(parts, ignore_index=True)
        df["ts"] = _naive_ist(df.ts)
        df = df[(df.ts.dt.date >= frm) & (df.ts.dt.date <= to)]
        df["d"] = df.ts.dt.date
        return df

    def load(self, und: str, frm: date, to: date) -> Iterator[DayData]:
        if not self.available(und):
            return
        years = range(frm.year, to.year + 1)
        ix = self._year_frame(f"index_1m_{und}", years, frm, to)
        if ix.empty:
            return
        opt = self._year_frame(f"opt_{und}_WEEK", years, frm, to)
        vix = self._year_frame("index_1m_INDIAVIX", years, frm, to)
        fut = pd.DataFrame()
        fp = self.dir / "fut_1m_live_index.parquet"
        if fp.exists():
            fut = pd.read_parquet(fp)
            fut = fut[fut.symbol.astype(str).str.upper().str.startswith(und)]
            fut["ts"] = _naive_ist(fut.ts)
            fut = fut[(fut.ts.dt.date >= frm) & (fut.ts.dt.date <= to)]
            if len(fut):
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
            if f is not None and len(f):
                near = f[f.expiry == f.expiry.min()]           # the nearest live contract on that day
                fdf = near[["ts", "close", "volume", "oi"]].astype(float, errors="ignore")
            v = vb.get(d)
            vdf = v[["ts", "close"]] if v is not None and len(v) else empty(VIX_COLS)
            yield DayData(day=d, und=und, index=index.reset_index(drop=True), options=options.reset_index(drop=True),
                          fut=fdf.reset_index(drop=True), vix=vdf.reset_index(drop=True),
                          sources=dict(index=f"lake:index_1m_{und}", options=f"lake:opt_{und}_WEEK expiry={src}",
                                       fut="lake:fut_1m_live_index" if len(fdf) else "",
                                       vix="lake:index_1m_INDIAVIX" if len(vdf) else ""))
