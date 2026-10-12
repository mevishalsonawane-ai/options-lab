"""R15 day table (PREREG.md): pre-open features for BANKNIFTY from public data. No P&L here.

    python3 -I research/hunt/r15/features.py <scratch dir>

Sources: Yahoo daily ^NSEBANK and ^INDIAVIX (public chart endpoint); NSE F&O bhavcopy archives (old format to
Jul 2024, UDiFF after). Only BANKNIFTY rows are kept (<scratch>/bhav/BN_<yyyymmdd>.csv). Output: <scratch>/days.csv,
one row per session with the features of THAT session's previous session (known before 09:15).
"""
from __future__ import annotations

import io
import json
import os
import sys
import time
import urllib.request
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, datetime, timezone, timedelta

import numpy as np
import pandas as pd

UA = {"User-Agent": "Mozilla/5.0"}
IST = timezone(timedelta(hours=5, minutes=30))
START, END = date(2023, 1, 1), date(2026, 3, 1)


def get(url, tries=4):
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=30) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if getattr(e, "code", None) == 404:
                return None
            time.sleep(2 ** (k + 1))
    return None


def yahoo(sym):
    p1 = int(datetime(START.year, START.month, START.day, tzinfo=timezone.utc).timestamp())
    p2 = int(datetime(END.year, END.month, END.day, tzinfo=timezone.utc).timestamp())
    j = json.loads(get(f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?period1={p1}&period2={p2}&interval=1d"))
    r = j["chart"]["result"][0]
    q = r["indicators"]["quote"][0]
    d = [datetime.fromtimestamp(t, IST).date() for t in r["timestamp"]]
    return pd.DataFrame({"day": d, "open": q["open"], "high": q["high"], "low": q["low"], "close": q["close"]}).dropna()


def bhav(day, out):
    f = os.path.join(out, f"BN_{day:%Y%m%d}.csv")
    if os.path.exists(f):
        return f
    raw = None
    if day >= date(2024, 7, 8):
        raw = get(f"https://nsearchives.nseindia.com/content/fo/BhavCopy_NSE_FO_0_0_0_{day:%Y%m%d}_F_0000.csv.zip")
    if raw is None:
        mon = day.strftime("%b").upper()
        raw = get(f"https://archives.nseindia.com/content/historical/DERIVATIVES/{day:%Y}/{mon}/fo{day:%d}{mon}{day:%Y}bhav.csv.zip")
    if raw is None:
        return None
    z = zipfile.ZipFile(io.BytesIO(raw))
    df = pd.read_csv(z.open(z.namelist()[0]))
    if "TckrSymb" in df.columns:
        df = df[df.TckrSymb == "BANKNIFTY"]
        df = pd.DataFrame({"kind": df.FinInstrmTp.map({"IDF": "FUT", "IDO": "OPT"}), "expiry": pd.to_datetime(df.XpryDt).dt.date,
                           "strike": df.StrkPric, "right": df.OptnTp, "oi": df.OpnIntrst, "doi": df.ChngInOpnIntrst})
    else:
        df = df[df.SYMBOL == "BANKNIFTY"]
        df = pd.DataFrame({"kind": df.INSTRUMENT.map({"FUTIDX": "FUT", "OPTIDX": "OPT"}),
                           "expiry": pd.to_datetime(df.EXPIRY_DT, format="%d-%b-%Y").dt.date,
                           "strike": df.STRIKE_PR, "right": df.OPTION_TYP, "oi": df.OPEN_INT, "doi": df.CHG_IN_OI})
    df.to_csv(f, index=False)
    return f


def bhav_feats(day, f):
    b = pd.read_csv(f, parse_dates=["expiry"])
    b["expiry"] = b.expiry.dt.date
    fut = b[b.kind == "FUT"]
    oi, doi = fut.oi.sum(), fut.doi.sum()
    o = b[(b.kind == "OPT") & (b.expiry >= day)]
    pcr = np.nan
    if len(o):
        near = o[o.expiry == o.expiry.min()]
        c, p = near[near.right == "CE"].oi.sum(), near[near.right == "PE"].oi.sum()
        pcr = p / c if c > 0 else np.nan
    return dict(day=day, fut_oi=oi, fut_doi_pct=doi / (oi - doi) if oi - doi > 0 else np.nan, pcr=pcr)


def pct_flag(x, n, lo_q=None, hi_q=None, minn=20):
    """For each i: x[i] against the n values BEFORE i (x[i-n..i-1]); NaN under minn."""
    out = np.full(len(x), np.nan)
    for i in range(len(x)):
        h = x[max(0, i - n):i]
        h = h[~np.isnan(h)]
        if len(h) < minn or np.isnan(x[i]):
            continue
        v = False
        if lo_q is not None and x[i] < np.quantile(h, lo_q):
            v = True
        if hi_q is not None and x[i] > np.quantile(h, hi_q):
            v = True
        out[i] = v
    return out


def main():
    out = sys.argv[1]
    bdir = os.path.join(out, "bhav")
    os.makedirs(bdir, exist_ok=True)
    bn = yahoo("%5ENSEBANK")
    vix = yahoo("%5EINDIAVIX")[["day", "close"]].rename(columns={"close": "vix"})
    days = [d for d in bn.day if d >= date(2023, 6, 1)]
    with ThreadPoolExecutor(4) as ex:
        files = list(ex.map(lambda d: bhav(d, bdir), days))
    miss = [str(d) for d, f in zip(days, files) if f is None]
    print(f"bhavcopy: {len(days) - len(miss)} of {len(days)} sessions; missing: {miss[:10]}", flush=True)
    bf = pd.DataFrame([bhav_feats(d, f) for d, f in zip(days, files) if f is not None])
    t = bn.merge(vix, on="day", how="left").merge(bf, on="day", how="left").sort_values("day").reset_index(drop=True)
    # Features of session i itself; the flags below are then shifted so session D reads its PREVIOUS session.
    t["rng"] = (t.high - t.low) / t.close
    t["ret"] = t.close / t.close.shift(1) - 1
    F = pd.DataFrame({"day": t.day})
    F["F1_QUIET"] = pct_flag(t.rng.values, 60, lo_q=0.33, minn=20)
    F["F2_LOWVIX"] = pct_flag(t.vix.values.astype(float), 250, lo_q=0.33, minn=60)
    F["F3_PCR_EXT"] = pct_flag(t.pcr.values.astype(float), 120, lo_q=0.10, hi_q=0.90, minn=40)
    F["F4_FUTOI_UP"] = pct_flag(t.fut_doi_pct.values.astype(float), 60, hi_q=0.67, minn=20)
    F["F5_AFTERDROP"] = np.where(t.ret.isna(), np.nan, (t.ret <= -0.01).astype(float))
    raw = t[["rng", "vix", "pcr", "fut_doi_pct", "ret"]]
    D = pd.concat([F[["day"]], F.drop(columns="day").shift(1), raw.shift(1).add_prefix("prev_")], axis=1)
    D.to_csv(os.path.join(out, "days.csv"), index=False)
    print(D.describe().T.to_string())


if __name__ == "__main__":
    main()
