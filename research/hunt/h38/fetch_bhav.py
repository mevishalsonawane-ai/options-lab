#!/usr/bin/env python3
"""H38: fetch NSE F&O daily bhavcopies (public, no credentials) and keep only what H38 needs.

Kept per trading day (raw zip is parsed in memory and never written to disk):
  futidx.parquet   every index-future contract of NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY:
                   date, sym, expiry, open, high, low, close, settle, contracts, value_lakh, oi, chg_oi, und_px (UDiFF only)
  optagg.parquet   index-option aggregates per (date, sym, expiry, side): oi, chg_oi, contracts

Sources (checked live 2026-10-08):
  <= 2024-07-05  https://nsearchives.nseindia.com/content/historical/DERIVATIVES/YYYY/MON/foDDMONYYYYbhav.csv.zip
  >= 2024-07-08  https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_YYYYMMDD_F_0000.csv.zip (UDiFF)
Polite: one request per ~1.2 s, resumable (done days are skipped), 404 = holiday.

    python3 -I research/hunt/h38/fetch_bhav.py 2020-01-01 2026-10-07
"""
from __future__ import annotations

import io
import os
import sys
import time
import zipfile
from datetime import date, timedelta

sys.path.append("/root/.local/lib/python3.11/site-packages")
import pandas as pd  # noqa: E402
import requests  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad"
OUT = os.path.join(SCR, "hunt", "h38", "data", "bhav")
SYMS = {"NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"}
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
      "Accept": "*/*"}
SWITCH = date(2024, 7, 8)
MON = ["JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"]


def url(d: date) -> str:
    if d < SWITCH:
        return (f"https://nsearchives.nseindia.com/content/historical/DERIVATIVES/{d.year}/{MON[d.month - 1]}/"
                f"fo{d.day:02d}{MON[d.month - 1]}{d.year}bhav.csv.zip")
    return f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{d:%Y%m%d}_F_0000.csv.zip"


def parse(d: date, blob: bytes):
    with zipfile.ZipFile(io.BytesIO(blob)) as z:
        name = [n for n in z.namelist() if n.lower().endswith(".csv")][0]
        df = pd.read_csv(z.open(name), low_memory=False)
    if d < SWITCH:
        df = df[df.SYMBOL.isin(SYMS)]
        f = df[df.INSTRUMENT == "FUTIDX"]
        fut = pd.DataFrame({"sym": f.SYMBOL, "expiry": pd.to_datetime(f.EXPIRY_DT, format="%d-%b-%Y"),
                            "open": f.OPEN, "high": f.HIGH, "low": f.LOW, "close": f.CLOSE, "settle": f.SETTLE_PR,
                            "contracts": f.CONTRACTS, "value_lakh": f.VAL_INLAKH, "oi": f.OPEN_INT,
                            "chg_oi": f.CHG_IN_OI, "und_px": float("nan")})
        o = df[df.INSTRUMENT == "OPTIDX"]
        opt = pd.DataFrame({"sym": o.SYMBOL, "expiry": pd.to_datetime(o.EXPIRY_DT, format="%d-%b-%Y"),
                            "side": o.OPTION_TYP, "oi": o.OPEN_INT, "chg_oi": o.CHG_IN_OI, "contracts": o.CONTRACTS})
    else:
        df = df[df.TckrSymb.isin(SYMS)]
        f = df[df.FinInstrmTp == "IDF"]
        fut = pd.DataFrame({"sym": f.TckrSymb, "expiry": pd.to_datetime(f.XpryDt),
                            "open": f.OpnPric, "high": f.HghPric, "low": f.LwPric, "close": f.ClsPric,
                            "settle": f.SttlmPric, "contracts": f.TtlTradgVol, "value_lakh": f.TtlTrfVal / 1e5,
                            "oi": f.OpnIntrst, "chg_oi": f.ChngInOpnIntrst, "und_px": f.UndrlygPric})
        o = df[df.FinInstrmTp == "IDO"]
        opt = pd.DataFrame({"sym": o.TckrSymb, "expiry": pd.to_datetime(o.XpryDt), "side": o.OptnTp,
                            "oi": o.OpnIntrst, "chg_oi": o.ChngInOpnIntrst, "contracts": o.TtlTradgVol})
    fut.insert(0, "date", pd.Timestamp(d))
    opt = opt.groupby(["sym", "expiry", "side"], as_index=False)[["oi", "chg_oi", "contracts"]].sum()
    opt.insert(0, "date", pd.Timestamp(d))
    return fut, opt


def main(a: str, b: str):
    os.makedirs(OUT, exist_ok=True)
    d0, d1 = date.fromisoformat(a), date.fromisoformat(b)
    s = requests.Session()
    s.headers.update(UA)
    months = {}
    d = d0
    while d <= d1:
        if d.weekday() < 5:
            months.setdefault((d.year, d.month), []).append(d)
        d += timedelta(days=1)
    for (y, m), days in sorted(months.items()):
        fp = os.path.join(OUT, f"fut_{y}{m:02d}.parquet")
        op = os.path.join(OUT, f"opt_{y}{m:02d}.parquet")
        hp = os.path.join(OUT, f"holidays_{y}{m:02d}.txt")
        if os.path.exists(fp) and os.path.exists(op) and not (y == d1.year and m == d1.month):
            continue
        F, O, hol, fail = [], [], [], []
        for d in days:
            ok = False
            for attempt in range(4):
                try:
                    r = s.get(url(d), timeout=40)
                except requests.RequestException as e:
                    print(d, "net", type(e).__name__, flush=True)
                    time.sleep(5 * (attempt + 1))
                    continue
                time.sleep(1.2)
                if r.status_code == 404:
                    hol.append(d.isoformat()); ok = True
                    break
                if r.status_code != 200 or r.content[:2] != b"PK":
                    print(d, "HTTP", r.status_code, len(r.content), flush=True)
                    time.sleep(10 * (attempt + 1))
                    continue
                f, o = parse(d, r.content)
                F.append(f); O.append(o); ok = True
                break
            if not ok:
                fail.append(d.isoformat())
        if F:
            pd.concat(F).to_parquet(fp, compression="zstd", index=False)
            pd.concat(O).to_parquet(op, compression="zstd", index=False)
        with open(hp, "w") as fh:
            fh.write("\n".join(hol) + ("\nFAILED " + " ".join(fail) if fail else "") + "\n")
        print(f"{y}-{m:02d}: {len(F)} days, {len(hol)} holidays/404, {len(fail)} failed", flush=True)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
