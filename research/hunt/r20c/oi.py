#!/usr/bin/env python3
"""R20c: the option open-interest walls from the NSE F&O daily bhavcopy (public, no credentials).
Copied from R20b (branch claude/r20b-sr-range, commit 52532ce); only the cache folder differs.

For each trading day we keep the end-of-day OI per strike and side of BANKNIFTY and NIFTY index options (all expiries),
in <cache>/r20c/bhav/<SYM>_<yyyymmdd>.csv. The study reads the PREVIOUS trading day's file for a session, so the walls
are known before 09:15 (point-in-time). The raw zip is parsed in memory and never written to disk.

Sources (the same as research/hunt/r15/features.py and h38/fetch_bhav.py):
  <= 2024-07-05  https://nsearchives.nseindia.com/content/historical/DERIVATIVES/YYYY/MON/foDDMONYYYYbhav.csv.zip
  >= 2024-07-08  https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip (UDiFF)
404 = holiday. Resumable (days already saved are skipped). Polite: ~1 request a second.

    python3 research/hunt/r20c/oi.py 2024-01-15 2026-04-14
"""
from __future__ import annotations

import io
import os
import sys
import time
import urllib.request
import zipfile
from datetime import date, timedelta
from pathlib import Path

import numpy as np
import pandas as pd

SYMS = ("BANKNIFTY", "NIFTY")
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      "Accept": "*/*"}
SWITCH = date(2024, 7, 8)
MON = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]


def bhav_dir() -> Path:
    root = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
    return Path(os.environ.get("R20C_OUT", root / "r20c")).expanduser() / "bhav"


def url(d: date) -> str:
    if d < SWITCH:
        return (f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d.year}/{MON[d.month - 1]}/"
                f"fo{d:%d}{MON[d.month - 1]}{d.year}bhav.csv.zip")
    return f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"


def get(u: str, tries: int = 4) -> bytes | None:
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if getattr(e, "code", None) == 404:
                return None
            time.sleep(2 ** (k + 1))
    return None


def parse(raw: bytes) -> pd.DataFrame:
    """Option rows of SYMS: sym, expiry, strike, side ('C'/'P'), oi."""
    z = zipfile.ZipFile(io.BytesIO(raw))
    df = pd.read_csv(z.open(z.namelist()[0]), low_memory=False)
    df.columns = [c.strip() for c in df.columns]
    if "TckrSymb" in df.columns:
        df = df[df.TckrSymb.isin(SYMS) & (df.FinInstrmTp == "IDO")]
        out = pd.DataFrame({"sym": df.TckrSymb, "expiry": pd.to_datetime(df.XpryDt).dt.date, "strike": df.StrkPric.astype(float),
                            "side": df.OptnTp.str.strip().str[0], "oi": df.OpnIntrst.astype(float)})
    else:
        df = df[df.SYMBOL.str.strip().isin(SYMS) & (df.INSTRUMENT.str.strip() == "OPTIDX")]
        out = pd.DataFrame({"sym": df.SYMBOL.str.strip(), "expiry": pd.to_datetime(df.EXPIRY_DT.str.strip(), format="%d-%b-%Y").dt.date,
                            "strike": df.STRIKE_PR.astype(float), "side": df.OPTION_TYP.str.strip().str[0], "oi": df.OPEN_INT.astype(float)})
    return out[out.side.isin(["C", "P"])]


def fetch(frm: date, to: date) -> None:
    out = bhav_dir()
    out.mkdir(parents=True, exist_ok=True)
    d = frm
    while d <= to:
        if d.weekday() < 5 and not all((out / f"{s}_{d:%Y%m%d}.csv").exists() for s in SYMS) \
                and not (out / f"none_{d:%Y%m%d}").exists():
            raw = get(url(d))
            if raw is None:
                (out / f"none_{d:%Y%m%d}").write_text("")          # holiday / not published
            else:
                b = parse(raw)
                for s in SYMS:
                    b[b.sym == s].drop(columns="sym").to_csv(out / f"{s}_{d:%Y%m%d}.csv", index=False)
            time.sleep(1.0)
        d += timedelta(days=1)


def load(sym: str, d: date) -> pd.DataFrame | None:
    p = bhav_dir() / f"{sym}_{d:%Y%m%d}.csv"
    if not p.exists():
        return None
    b = pd.read_csv(p)
    b["expiry"] = pd.to_datetime(b.expiry).dt.date
    return b


def walls(b: pd.DataFrame | None, session: date) -> tuple[float, float]:
    """(support, resistance) from one bhavcopy: the strikes with the biggest put OI and call OI in the nearest expiry on
    or after `session` (the day the walls are used). NaN when the file is missing or has no such expiry."""
    if b is None or not len(b):
        return np.nan, np.nan
    o = b[b.expiry >= session]
    if not len(o):
        return np.nan, np.nan
    o = o[o.expiry == o.expiry.min()]
    p, c = o[o.side == "P"], o[o.side == "C"]
    sup = float(p.loc[p.oi.idxmax(), "strike"]) if len(p) and p.oi.max() > 0 else np.nan
    res = float(c.loc[c.oi.idxmax(), "strike"]) if len(c) and c.oi.max() > 0 else np.nan
    return sup, res


if __name__ == "__main__":
    a = sys.argv[1:]
    fetch(date.fromisoformat(a[0]) if a else date(2024, 1, 15), date.fromisoformat(a[1]) if len(a) > 1 else date(2026, 4, 14))
    print("bhav files", len(list(bhav_dir().glob("*.csv"))))
