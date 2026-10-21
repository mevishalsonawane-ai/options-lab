"""R86 data loaders: the Drive lake copies and the CFTC files in /dev/shm (never in git). No network.

Environment: R86_LAKE (default /dev/shm/lake), R86_CFTC (default /dev/shm/cftc). Expected layout:
  lake/participants/oi/oi_YYYY.zip, lake/participants/bhav_index/bhav_index_YYYY.parquet, lake/index_daily.parquet,
  lake/index_1m/index_1m_<SYM>_<YEAR>.parquet, lake/equity_1m/equity_1m_<YEAR>.parquet, lake/r28_gex/gex_<UND>.parquet,
  lake/fut_daily_continuous.parquet; cftc/fut_disagg_txt_hist_2006_2016.zip and fut_disagg_txt_<YEAR>.zip.
Every raw file is parsed as data only.
"""
from __future__ import annotations

import io
import os
import re
import zipfile
from functools import lru_cache
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.of1.load import parse_participant

LAKE = Path(os.environ.get("R86_LAKE", "/dev/shm/lake"))
CFTC = Path(os.environ.get("R86_CFTC", "/dev/shm/cftc"))

COT_CODES = {"GOLD": "088691", "SILVER": "084691", "CRUDE": "067651"}
MCX_SYM = {"GOLD": "GOLDM", "SILVER": "SILVERM", "CRUDE": "CRUDEOIL"}
MCX_MULT = {"GOLD": 10.0, "SILVER": 5.0, "CRUDE": 10.0}     # GOLDM 100 g (quote / 10 g); SILVERM 5 kg; CRUDEOILM 10 bbl


@lru_cache(None)
def participants() -> pd.DataFrame:
    """One row per date: FII and Client index futures/options contracts (fii_*, cl_*)."""
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
                for g, pre in (("FII", "fii_"), ("Client", "cl_")):
                    for col in ("fut_idx_long", "fut_idx_short", "idx_call_long", "idx_call_short", "idx_put_long",
                                "idx_put_short"):
                        r[pre + col] = float(t.loc[g, col])
                rows.append(r)
    return pd.DataFrame(rows).drop_duplicates("date").sort_values("date").set_index("date")


@lru_cache(None)
def index_daily(sym: str) -> pd.DataFrame:
    d = pd.read_parquet(LAKE / "index_daily.parquet")
    d = d[d.symbol == sym].copy()
    d["date"] = pd.to_datetime(d.date)
    d = d.sort_values("date").drop_duplicates("date").set_index("date")[["open", "high", "low", "close"]]
    return d[(d.high >= d.low) & (d.close > 0)]


@lru_cache(None)
def mcx_daily(name: str) -> pd.DataFrame:
    d = pd.read_parquet(LAKE / "fut_daily_continuous.parquet")
    d = d[d.symbol == MCX_SYM[name]].copy()
    d["date"] = pd.to_datetime(d.date)
    d = d.sort_values("date").drop_duplicates("date").reset_index(drop=True)
    return d[["date", "open", "high", "low", "close", "volume", "oi"]]


@lru_cache(None)
def cot(name: str) -> pd.DataFrame:
    """Weekly managed-money rows for one market: report (date), mm_long, mm_short, oi."""
    code = COT_CODES[name]
    cols = ["Report_Date_as_YYYY-MM-DD", "CFTC_Contract_Market_Code", "Open_Interest_All",
            "M_Money_Positions_Long_All", "M_Money_Positions_Short_All"]
    parts = []
    for zp in sorted(CFTC.glob("fut_disagg_txt_*.zip")):
        with zipfile.ZipFile(zp) as z:
            for n in z.namelist():
                df = pd.read_csv(io.BytesIO(z.read(n)), usecols=cols, dtype=str)
                parts.append(df[df.CFTC_Contract_Market_Code.str.strip() == code])
    d = pd.concat(parts)
    out = pd.DataFrame({"report": pd.to_datetime(d["Report_Date_as_YYYY-MM-DD"].str.strip()),
                        "oi": pd.to_numeric(d.Open_Interest_All.str.replace(",", ""), errors="coerce"),
                        "mm_long": pd.to_numeric(d.M_Money_Positions_Long_All.str.replace(",", ""), errors="coerce"),
                        "mm_short": pd.to_numeric(d.M_Money_Positions_Short_All.str.replace(",", ""), errors="coerce")})
    return out.drop_duplicates("report").sort_values("report").reset_index(drop=True)


@lru_cache(None)
def minutes(sym: str) -> pd.DataFrame:
    """1-minute index bars with day and minute-of-day columns (IST)."""
    fs = sorted((LAKE / "index_1m").glob(f"index_1m_{sym}_*.parquet"))
    d = pd.concat([pd.read_parquet(f) for f in fs])
    ts = pd.to_datetime(d.ts)
    d = d.assign(day=ts.dt.tz_localize(None).dt.normalize(), mod=ts.dt.hour * 60 + ts.dt.minute)
    d = d[(d["mod"] >= 555) & (d["mod"] <= 929)]
    d = d.sort_values(["day", "mod"]).drop_duplicates(["day", "mod"]).reset_index(drop=True)
    return d[["day", "mod", "open", "high", "low", "close"]]


@lru_cache(None)
def turnover_minutes() -> pd.DataFrame:
    """Rupee turnover per minute summed over the equity_1m stocks (day, mod, turnover)."""
    parts = []
    for f in sorted((LAKE / "equity_1m").glob("equity_1m_*.parquet")):
        e = pd.read_parquet(f, columns=["ts", "close", "volume"])
        ts = pd.to_datetime(e.ts)
        e = pd.DataFrame({"day": ts.dt.tz_localize(None).dt.normalize(), "mod": ts.dt.hour * 60 + ts.dt.minute,
                          "tv": e.close.to_numpy(float) * e.volume.to_numpy(float)})
        parts.append(e.groupby(["day", "mod"], as_index=False).tv.sum())
    return pd.concat(parts).groupby(["day", "mod"], as_index=False).tv.sum()


@lru_cache(None)
def gex(und: str) -> pd.DataFrame:
    g = pd.read_parquet(LAKE / "r28_gex" / f"gex_{und}.parquet", columns=["day", "minute", "near_net_cr", "near_norm",
                                                                          "dte"])
    g["day"] = pd.to_datetime(g.day)
    return g


@lru_cache(None)
def nifty_fut_oi() -> pd.DataFrame:
    """NIFTY index futures per (date, expiry): OI in units, from the index bhavcopy."""
    parts = []
    for f in sorted((LAKE / "participants" / "bhav_index").glob("bhav_index_*.parquet")):
        b = pd.read_parquet(f, columns=["date", "und", "kind", "expiry", "oi_qty"])
        parts.append(b[(b.und == "NIFTY") & (b.kind == "FUT")])
    b = pd.concat(parts)
    b["date"] = pd.to_datetime(b.date)
    b["expiry"] = pd.to_datetime(b.expiry)
    return b.groupby(["date", "expiry"], as_index=False).oi_qty.sum()
