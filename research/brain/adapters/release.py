"""Adapter for the repo release `research-data` (what research/hunt/r15 used).

Files (written by research/dump_year.py, dump_extra.py, dump_expiry.py; naive IST, bar starts):
  banknifty_prev_year_wide.parquet, banknifty_year_wide.parquet   13 Feb 2024 - 23 Feb 2026, 498 sessions
      day, right IX/CE/PE, expiry, strike, ts, open, high, low, close, volume, open_interest
      index rows (right IX) + the nearest expiry AFTER the day at strikes within 1,000 points of the 09:20 index
  expiry_days.parquet      same columns + underlying: the expiring contract ON expiry days (BANKNIFTY from Feb 2025)
  extra_series.parquet     ts, OHLC, series NIFTY / INDIAVIX / BNFUT (near-month BANKNIFTY future, no volume/OI)

Download once with `python -m research.brain.cube fetch` (uses `gh api` or GH_TOKEN; the token is never printed).
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import urllib.request
from datetime import date
from pathlib import Path
from typing import Iterator

import pandas as pd
import pyarrow.parquet as pq

from .base import FUT_COLS, VIX_COLS, DayData, empty

REPO = "mevishalsonawane-ai/options-lab"
TAG = "research-data"

LAYOUT = {
    "BANKNIFTY": dict(main=["banknifty_prev_year_wide.parquet", "banknifty_year_wide.parquet"],
                      expiry="expiry_days.parquet", extra="extra_series.parquet", fut="BNFUT", vix="INDIAVIX"),
    "NIFTY": dict(main=["nifty_prev.parquet", "nifty_year.parquet"],
                  expiry="expiry_days.parquet", extra="extra_series.parquet", fut=None, vix="INDIAVIX"),
}


# Contracts the archive lists under an expiry date the exchange later moved. NSE moved monthly expiries from Wednesday to
# Thursday from 1 Jan 2025: BANKNIFTY's Jan 2025 monthly appears as 29 Jan up to 1 Jan 2025 and as 30 Jan after it.
EXPIRY_MOVED = {date(2025, 1, 29): date(2025, 1, 30)}


def files_for(und: str) -> list[str]:
    L = LAYOUT[und]
    return L["main"] + [L["expiry"], L["extra"]]


class ReleaseAdapter:
    name = "release"

    def __init__(self, raw_dir: str | os.PathLike, layout: dict | None = None):
        self.raw = Path(raw_dir)
        self.layout = layout or LAYOUT
        self._sessions: dict[str, list[date]] = {}

    def _p(self, f: str) -> Path:
        return self.raw / f

    def available(self, und: str) -> bool:
        L = self.layout.get(und)
        return bool(L) and any(self._p(f).exists() for f in L["main"])

    def fingerprint(self, und: str) -> str:
        L = self.layout[und]
        parts = []
        for f in L["main"] + [L["expiry"], L["extra"]]:
            p = self._p(f)
            parts.append(f"{f}:{p.stat().st_size}" if p.exists() else f"{f}:-")
        return "release|" + "|".join(parts)

    def sessions(self, und: str) -> list[date]:
        if und not in self._sessions:
            s: set[date] = set()
            for f in self.layout[und]["main"]:
                if self._p(f).exists():
                    t = pq.read_table(self._p(f), columns=["day"]).column("day").to_pandas()
                    s |= set(pd.to_datetime(t.unique()).date)
            self._sessions[und] = sorted(s)
        return self._sessions[und]

    def _read(self, f: str, frm: date, to: date, extra_filter=None) -> pd.DataFrame:
        p = self._p(f)
        if not p.exists():
            return pd.DataFrame()
        flt = [("day", ">=", pd.Timestamp(frm)), ("day", "<=", pd.Timestamp(to))]
        if extra_filter:
            flt += extra_filter
        return pd.read_parquet(p, filters=flt)

    def load(self, und: str, frm: date, to: date) -> Iterator[DayData]:
        L = self.layout[und]
        main = [self._read(f, frm, to) for f in L["main"]]
        main = pd.concat([m for m in main if len(m)], ignore_index=True) if any(len(m) for m in main) else pd.DataFrame()
        if main.empty:
            return
        exp = self._read(L["expiry"], frm, to, [("underlying", "==", und)]) if L.get("expiry") else pd.DataFrame()
        extra = pd.DataFrame()
        if L.get("extra") and self._p(L["extra"]).exists():
            extra = pd.read_parquet(self._p(L["extra"]), filters=[
                ("ts", ">=", pd.Timestamp(frm)), ("ts", "<", pd.Timestamp(to) + pd.Timedelta(days=1))])
            extra["d"] = extra.ts.dt.date
        main["d"] = main.day.dt.date
        if len(exp):
            exp["d"] = exp.day.dt.date
        ex_by = dict(tuple(exp.groupby("d"))) if len(exp) else {}
        xt_by = dict(tuple(extra.groupby("d"))) if len(extra) else {}
        seen_days: set[date] = set()
        for d, g in main.groupby("d", sort=True):
            if d in seen_days:            # the two yearly files overlap on no day today; keep the first if they ever do
                continue
            seen_days.add(d)
            ix = g[g.right == "IX"]
            index = ix[["ts", "open", "high", "low", "close"]].assign(volume=float("nan"))   # the index has no volume
            opts = [_options(g[g.right != "IX"])]
            if d in ex_by:
                opts.append(_options(ex_by[d][ex_by[d].right != "IX"]))
            options = pd.concat(opts, ignore_index=True).drop_duplicates(["ts", "expiry", "strike", "side"], keep="first")
            x = xt_by.get(d, pd.DataFrame(columns=["series"]))
            fut = empty(FUT_COLS)
            if L.get("fut"):
                f = x[x.series == L["fut"]]
                if len(f):
                    fut = f[["ts", "close"]].assign(volume=float("nan"), oi=float("nan"))
            v = x[x.series == L["vix"]] if L.get("vix") else x.iloc[0:0]
            vix = v[["ts", "close"]] if len(v) else empty(VIX_COLS)
            yield DayData(day=d, und=und, index=index.reset_index(drop=True), options=options, fut=fut.reset_index(drop=True),
                          vix=vix.reset_index(drop=True),
                          sources=dict(index="release:" + "+".join(L["main"]),
                                       options="release:main" + ("+expiry_days" if d in ex_by else ""),
                                       fut=f"release:{L['extra']}:{L['fut']}" if len(fut) else "",
                                       vix=f"release:{L['extra']}:{L['vix']}" if len(vix) else ""))

    def nearest_expiries(self, und: str) -> pd.DataFrame:
        """(day, expiry) of the nearest contract per session, across the main and expiry-day files."""
        L = self.layout[und]
        parts = []
        for f in L["main"] + [L["expiry"]]:
            p = self._p(f)
            if not p.exists():
                continue
            cols = ["day", "expiry", "right"] + (["underlying"] if f == L["expiry"] else [])
            t = pq.read_table(p, columns=cols).to_pandas()
            if "underlying" in t:
                t = t[t.underlying == und]
            t = t[t.right != "IX"]
            c = t.groupby(["day", "expiry"]).size().reset_index(name="n")
            parts.append(c[c.n >= 300])                    # ignore stray rows of another contract
        c = pd.concat(parts)
        c["expiry"] = pd.to_datetime(c.expiry.dt.date.map(lambda d: EXPIRY_MOVED.get(d, d)))
        c = c[c.expiry >= c.day]
        out = c.sort_values("expiry").groupby("day").expiry.first().reset_index()
        return pd.DataFrame(dict(day=out.day.dt.date, expiry=out.expiry.dt.date))


def _options(o: pd.DataFrame) -> pd.DataFrame:
    if o.empty:
        return pd.DataFrame(columns=["ts", "expiry", "strike", "side", "open", "high", "low", "close", "volume", "oi", "iv_vendor"])
    exp = o.expiry.dt.date.map(lambda d: EXPIRY_MOVED.get(d, d))
    return pd.DataFrame(dict(ts=o.ts.values, expiry=exp.values, strike=o.strike.astype(float).values,
                             side=o.right.map({"CE": "C", "PE": "P"}).values, open=o.open.values, high=o.high.values,
                             low=o.low.values, close=o.close.values, volume=o.volume.astype(float).values,
                             oi=o.open_interest.astype(float).values, iv_vendor=float("nan")))


# --- download ---------------------------------------------------------------------------------------------------------
def _assets() -> dict[str, dict]:
    if shutil.which("gh"):
        out = subprocess.run(["gh", "api", f"repos/{REPO}/releases/tags/{TAG}"], capture_output=True, text=True)
        if out.returncode == 0:
            return {a["name"]: a for a in json.loads(out.stdout)["assets"]}
    req = urllib.request.Request(f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}",
                                 headers={"Accept": "application/vnd.github+json", "User-Agent": "iraalgo-brain"})
    tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if tok:
        req.add_header("Authorization", f"Bearer {tok}")
    with urllib.request.urlopen(req, timeout=60) as r:
        return {a["name"]: a for a in json.load(r)["assets"]}


def fetch(names: list[str], dest: Path, force: bool = False) -> list[Path]:
    """Download release assets into dest (skips files already there with the release's byte size)."""
    dest.mkdir(parents=True, exist_ok=True)
    assets = _assets()
    got = []
    for n in names:
        a = assets.get(n)
        p = dest / n
        if a is None:
            print(f"  {n}: not in release {TAG}")
            continue
        if p.exists() and p.stat().st_size == a["size"] and not force:
            print(f"  {n}: cached ({a['size'] / 1e6:.1f} MB)")
            got.append(p)
            continue
        tmp = p.with_suffix(p.suffix + ".part")
        if shutil.which("gh"):
            with open(tmp, "wb") as o:
                r = subprocess.run(["gh", "api", "-H", "Accept: application/octet-stream",
                                    f"repos/{REPO}/releases/assets/{a['id']}"], stdout=o, stderr=subprocess.PIPE)
            ok = r.returncode == 0
        else:
            req = urllib.request.Request(a["url"], headers={"Accept": "application/octet-stream", "User-Agent": "iraalgo-brain"})
            tok = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
            if tok:
                req.add_header("Authorization", f"Bearer {tok}")
            with urllib.request.urlopen(req, timeout=600) as r, open(tmp, "wb") as o:
                shutil.copyfileobj(r, o)
            ok = True
        if not ok or tmp.stat().st_size != a["size"]:
            print(f"  {n}: download failed (asset may be mid-upload; retry)")
            tmp.unlink(missing_ok=True)
            continue
        os.replace(tmp, p)
        print(f"  {n}: downloaded ({a['size'] / 1e6:.1f} MB)")
        got.append(p)
    return got
