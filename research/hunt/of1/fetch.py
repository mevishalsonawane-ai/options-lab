#!/usr/bin/env python3
"""OF1 data fetch: NSE public daily files, 2 Jan 2012 (the first participant file in NSE's archive) to today.

    python3 -I research/hunt/of1/fetch.py [--from 2012-01-01] [--to 2026-10-12] [--workers 4]

What it downloads (13 Oct 2026; all public, no login, no token):
  participants/oi/fao_participant_oi_DDMMYYYY.csv     participant-wise OI (contracts): Client, DII, FII, Pro, TOTAL
  participants/vol/fao_participant_vol_DDMMYYYY.csv   participant-wise traded volume (contracts), same layout
  indices/ind_close_all_DDMMYYYY.csv                  every NSE index's OHLC close that day (NIFTY, BANK NIFTY, VIX ...)
  fii_stats/fii_stats_DD-Mon-YYYY.xls                 NSE's FII derivatives statistics (buy/sell value by product)
  bhav/YYYY-MM-DD.parquet                             the F&O bhavcopy cut to INDEX futures and options (FUTIDX/OPTIDX
                                                      old format, IDF/IDO UDiFF); the raw zip is not kept (it is
                                                      re-downloadable from NSE and is mostly stock rows)
A weekday with no file is a holiday (or not published): a `.missing` marker stops it being asked again.
Everything goes to <cache>/of1/raw (IRAALGO_CACHE, default ~/.cache/iraalgo), never into git. Requests are paced
(about 3 a second in all) with a browser user agent; NSE's www site blocks cloud IPs but its archive hosts did not.
"""
from __future__ import annotations

import argparse
import io
import os
import sys
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta
from pathlib import Path

import pandas as pd
import requests

CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
RAW = Path(os.environ.get("OF1_RAW", CACHE / "of1" / "raw")).expanduser()
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    "Chrome/126.0 Safari/537.36", "Accept": "*/*", "Referer": "https://www.nseindia.com/"}
HOST = "https://nsearchives.nseindia.com"
UDIFF_FROM = date(2024, 7, 8)            # NSE switched the F&O bhavcopy to the UDiFF layout on 8 Jul 2024
_lock = threading.Lock()
_last = [0.0]
PACE = float(os.environ.get("OF1_PACE", "0.33"))   # seconds between any two requests (all threads together)


def _get(url: str) -> requests.Response | None:
    for a in range(4):
        with _lock:
            wait = _last[0] + PACE - time.monotonic()
            if wait > 0:
                time.sleep(wait)
            _last[0] = time.monotonic()
        try:
            r = requests.get(url, headers=UA, timeout=60)
            if r.status_code in (200, 404):
                return r
        except requests.RequestException:
            pass
        time.sleep(2 ** (a + 1))
    return None


def urls(d: date) -> dict[str, tuple[str, Path]]:
    s = f"{d:%d%m%Y}"
    out = {
        "oi": (f"{HOST}/content/nsccl/fao_participant_oi_{s}.csv", RAW / "participants" / "oi" / f"fao_participant_oi_{s}.csv"),
        "vol": (f"{HOST}/content/nsccl/fao_participant_vol_{s}.csv", RAW / "participants" / "vol" / f"fao_participant_vol_{s}.csv"),
        "idx": (f"{HOST}/content/indices/ind_close_all_{s}.csv", RAW / "indices" / f"ind_close_all_{s}.csv"),
        "fii": (f"{HOST}/content/fo/fii_stats_{d.day:02d}-{d:%b}-{d.year}.xls", RAW / "fii_stats" / f"fii_stats_{d:%d-%b-%Y}.xls"),
    }
    if d < UDIFF_FROM:
        u = f"{HOST}/content/historical/DERIVATIVES/{d.year}/{d.strftime('%b').upper()}/fo{d.strftime('%d%b%Y').upper()}bhav.csv.zip"
    else:
        u = f"{HOST}/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"
    out["bhav"] = (u, RAW / "bhav" / f"{d}.parquet")
    return out


def _ok(kind: str, content: bytes) -> bool:
    if kind == "bhav":
        return content[:2] == b"PK"
    if kind == "fii":
        return content[:4] == b"\xd0\xcf\x11\xe0" or content[:2] == b"PK"   # xls (OLE2) or xlsx
    head = content[:200].lower()
    return b"<html" not in head and b"<!doctype" not in head and len(content) > 50


def index_rows(raw_zip: bytes, d: date) -> pd.DataFrame:
    """The bhavcopy cut to index futures/options, in one layout for both NSE formats.

    Columns: und, kind (FUT / CE / PE), expiry, strike, open, high, low, close, settle, contracts, oi_qty, chg_oi_qty,
    und_px (UDiFF only; NaN before 8 Jul 2024). OI is in units (shares of the index), as NSE prints it; traded volume
    is in contracts in both layouts (UDiFF TtlTradgVol is contracts: checked against the participant volume totals).
    """
    with zipfile.ZipFile(io.BytesIO(raw_zip)) as z:
        name = [n for n in z.namelist() if n.lower().endswith(".csv")][0]
        df = pd.read_csv(z.open(name), low_memory=False)
    df.columns = [c.strip() for c in df.columns]
    if "INSTRUMENT" in df.columns:                      # old layout
        df = df[df.INSTRUMENT.str.strip().isin(["FUTIDX", "OPTIDX"])]
        out = pd.DataFrame({
            "und": df.SYMBOL.str.strip(),
            "kind": [("FUT" if i.strip() == "FUTIDX" else o.strip()) for i, o in zip(df.INSTRUMENT, df.OPTION_TYP)],
            "expiry": pd.to_datetime(df.EXPIRY_DT.str.strip(), format="%d-%b-%Y").dt.date,
            "strike": df.STRIKE_PR.astype(float), "open": df.OPEN, "high": df.HIGH, "low": df.LOW, "close": df.CLOSE,
            "settle": df.SETTLE_PR, "contracts": df.CONTRACTS.astype(float), "oi_qty": df.OPEN_INT.astype(float),
            "chg_oi_qty": df.CHG_IN_OI.astype(float), "und_px": float("nan"),
        })
    else:                                               # UDiFF
        df = df[df.FinInstrmTp.isin(["IDF", "IDO"])]
        out = pd.DataFrame({
            "und": df.TckrSymb.str.strip(),
            "kind": [("FUT" if t == "IDF" else str(o).strip()) for t, o in zip(df.FinInstrmTp, df.OptnTp)],
            "expiry": pd.to_datetime(df.XpryDt).dt.date,
            "strike": df.StrkPric.astype(float).fillna(0.0), "open": df.OpnPric, "high": df.HghPric, "low": df.LwPric,
            "close": df.ClsPric, "settle": df.SttlmPric, "contracts": df.TtlTradgVol.astype(float),
            "oi_qty": df.OpnIntrst.astype(float), "chg_oi_qty": df.ChngInOpnIntrst.astype(float),
            "und_px": df.UndrlygPric.astype(float),
        })
    out.insert(0, "date", d)
    return out.reset_index(drop=True)


def fetch_day(d: date, kinds) -> dict[str, str]:
    res = {}
    for kind, (u, p) in urls(d).items():
        if kind not in kinds:
            continue
        miss = p.with_name(p.name + ".missing")
        if p.exists() or miss.exists():
            res[kind] = "have"
            continue
        p.parent.mkdir(parents=True, exist_ok=True)
        r = _get(u)
        if r is None:
            res[kind] = "error"                         # network: try again on the next run
            continue
        if r.status_code == 200 and _ok(kind, r.content):
            if kind == "bhav":
                index_rows(r.content, d).to_parquet(p, index=False)
            else:
                p.write_bytes(r.content)
            res[kind] = "got"
        else:
            miss.write_text(str(r.status_code))
            res[kind] = "missing"
    return res


def weekdays(a: date, b: date):
    d = a
    while d <= b:
        if d.weekday() < 5:
            yield d
        d += timedelta(days=1)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--from", dest="a", default="2012-01-01")
    ap.add_argument("--to", dest="b", default=str(date.today()))
    ap.add_argument("--workers", type=int, default=4)
    ap.add_argument("--kinds", default="oi,vol,idx,fii,bhav")
    args = ap.parse_args()
    kinds = set(args.kinds.split(","))
    days = list(weekdays(date.fromisoformat(args.a), date.fromisoformat(args.b)))
    days.reverse()                                      # newest first: the most relevant years arrive first
    tally: dict[str, int] = {}
    with ThreadPoolExecutor(args.workers) as ex:
        for i, res in enumerate(ex.map(lambda d: fetch_day(d, kinds), days), 1):
            for k, v in res.items():
                tally[f"{k}:{v}"] = tally.get(f"{k}:{v}", 0) + 1
            if i % 100 == 0:
                print(f"{i}/{len(days)} weekdays", dict(sorted(tally.items())), flush=True)
    print("done", dict(sorted(tally.items())))
    return 0


if __name__ == "__main__":
    sys.exit(main())
