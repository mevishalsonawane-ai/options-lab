"""R16 (PREREG.md) data builder. Everything goes to <scratch>; nothing here is committed except this code.

    python3 -I research/hunt/r16/data.py <scratch>

Inputs expected in <scratch>/data: the release files (banknifty_prev_year_wide, banknifty_year, nifty_prev,
nifty_year parquet). Downloads: Yahoo daily ^NSEI ^NSEBANK ^INDIAVIX; NSE F&O bhavcopy (only the option rows of
NIFTY/BANKNIFTY are kept) for expiry discovery and for the session before every expiry.
Outputs: <scratch>/BANKNIFTY.npz, NIFTY.npz (1-min grids), daily_<sym>.csv, vix.csv, expiries.csv, bhav/*.csv.
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
from datetime import date, datetime, timedelta, timezone

import numpy as np
import pandas as pd

UA = {"User-Agent": "Mozilla/5.0"}
IST = timezone(timedelta(hours=5, minutes=30))
N = 375
FILES = {"BANKNIFTY": ["banknifty_prev_year_wide.parquet", "banknifty_year.parquet"],
         "NIFTY": ["nifty_prev.parquet", "nifty_year.parquet"]}
YAHOO = {"NIFTY": "%5ENSEI", "BANKNIFTY": "%5ENSEBANK", "VIX": "%5EINDIAVIX"}


def get(url, tries=4):
    for k in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            if getattr(e, "code", None) == 404:
                return None
            time.sleep(2 ** (k + 1))
    return None


def yahoo(sym, start=date(2010, 1, 1), end=date(2026, 10, 13)):
    p1 = int(datetime(start.year, start.month, start.day, tzinfo=timezone.utc).timestamp())
    p2 = int(datetime(end.year, end.month, end.day, tzinfo=timezone.utc).timestamp())
    j = json.loads(get(f"https://query1.finance.yahoo.com/v8/finance/chart/{sym}?period1={p1}&period2={p2}&interval=1d"))
    r = j["chart"]["result"][0]
    q = r["indicators"]["quote"][0]
    d = [datetime.fromtimestamp(t, IST).date() for t in r["timestamp"]]
    return pd.DataFrame({"day": d, "open": q["open"], "high": q["high"], "low": q["low"], "close": q["close"]}).dropna()


# ------------------------------------------------------------------------------------------------ 1-min grids
def grids(scr, sym):
    parts = [pd.read_parquet(os.path.join(scr, "data", f)) for f in FILES[sym]]
    df = pd.concat(parts, ignore_index=True)
    df["ts"] = pd.to_datetime(df.ts)
    df["d"] = df.ts.dt.date
    df["m"] = (df.ts.dt.hour * 60 + df.ts.dt.minute) - (9 * 60 + 15)
    df = df[(df.m >= 0) & (df.m < N)]
    days, IX, OC, OI, STR, EXP = [], [], [], [], [], []
    for d, g in df.groupby("d"):
        ix = g[g.right == "IX"].drop_duplicates("m").set_index("m")
        if len(ix) < 300:
            continue
        a = ix.reindex(range(N))[["open", "high", "low", "close"]]
        a["close"] = a.close.ffill().bfill()
        for c in ("open", "high", "low"):
            a[c] = a[c].fillna(a.close)
        o = g[g.right != "IX"]
        exps = sorted(e for e in pd.to_datetime(o.expiry).dt.date.unique() if e >= d)
        if not exps:
            continue
        o = o[pd.to_datetime(o.expiry).dt.date == exps[0]]
        ks = np.sort(o.strike.unique())
        oc = np.full((N, len(ks), 2), np.nan)
        oi = np.full((N, len(ks), 2), np.nan)
        for (k, r), s in o.groupby(["strike", "right"]):
            s = s.drop_duplicates("m").set_index("m").reindex(range(N))
            j, rr = np.searchsorted(ks, k), (0 if r == "CE" else 1)
            oc[:, j, rr] = s.close.ffill().values
            v = s.open_interest.where(s.open_interest > 0).ffill().values
            oi[:, j, rr] = v
        days.append(d)
        IX.append(a.values)
        OC.append(oc)
        OI.append(oi)
        STR.append(ks)
        EXP.append(exps[0])
    K = max(len(k) for k in STR)
    pad = lambda arrs: np.stack([np.pad(x, ((0, 0), (0, K - x.shape[1]), (0, 0)), constant_values=np.nan) for x in arrs])
    strikes = np.stack([np.pad(k.astype(float), (0, K - len(k)), constant_values=np.nan) for k in STR])
    np.savez_compressed(os.path.join(scr, f"{sym}.npz"), days=np.array([str(d) for d in days]), ix=np.stack(IX),
                        oc=pad(OC), oi=pad(OI), strikes=strikes, expiry=np.array([str(e) for e in EXP]))
    print(sym, len(days), "sessions", days[0], days[-1], flush=True)


# ------------------------------------------------------------------------------------------------ bhavcopy
def bhav(day, out):
    f = os.path.join(out, f"{day:%Y%m%d}.csv")
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
        df = df[df.TckrSymb.isin(["NIFTY", "BANKNIFTY"]) & (df.FinInstrmTp == "IDO")]
        df = pd.DataFrame({"sym": df.TckrSymb, "expiry": pd.to_datetime(df.XpryDt).dt.date, "strike": df.StrkPric,
                           "right": df.OptnTp, "oi": df.OpnIntrst})
    else:
        df = df[df.SYMBOL.isin(["NIFTY", "BANKNIFTY"]) & (df.INSTRUMENT == "OPTIDX")]
        df = pd.DataFrame({"sym": df.SYMBOL, "expiry": pd.to_datetime(df.EXPIRY_DT, format="%d-%b-%Y").dt.date,
                           "strike": df.STRIKE_PR, "right": df.OPTION_TYP, "oi": df.OPEN_INT})
    df.to_csv(f, index=False)
    return f


def expiries(scr, sessions):
    out = os.path.join(scr, "bhav")
    os.makedirs(out, exist_ok=True)
    sess = [d for d in sessions if d >= date(2021, 12, 1)]
    probe = sess[::4]
    with ThreadPoolExecutor(6) as ex:
        fs = list(ex.map(lambda d: bhav(d, out), probe))
    exp = set()
    for f in fs:
        if f:
            b = pd.read_csv(f)
            for s, e in b[["sym", "expiry"]].drop_duplicates().itertuples(index=False):
                exp.add((s, pd.Timestamp(e).date()))
    sset = set(sess)
    rows = []
    for s, e in sorted(exp):
        if e not in sset or e < date(2022, 1, 1):
            continue
        i = sess.index(e)
        rows.append(dict(sym=s, expiry=e, prev=sess[i - 1]))
    ex_df = pd.DataFrame(rows)
    with ThreadPoolExecutor(6) as ex:
        list(ex.map(lambda d: bhav(d, out), sorted(set(ex_df.prev))))
    mp = []
    for r in ex_df.itertuples():
        f = os.path.join(out, f"{r.prev:%Y%m%d}.csv")
        if not os.path.exists(f):
            mp.append(np.nan)
            continue
        b = pd.read_csv(f)
        b = b[(b.sym == r.sym) & (pd.to_datetime(b.expiry).dt.date == r.expiry) & (b.oi > 0)]
        if b.empty:
            mp.append(np.nan)
            continue
        k = np.sort(b.strike.unique())
        c, p = b[b.right == "CE"], b[b.right == "PE"]
        pay = [(c.oi * np.maximum(K - c.strike, 0)).sum() + (p.oi * np.maximum(p.strike - K, 0)).sum() for K in k]
        mp.append(float(k[int(np.argmin(pay))]))
    ex_df["max_pain"] = mp
    ex_df.to_csv(os.path.join(scr, "expiries.csv"), index=False)
    print("expiries", ex_df.groupby("sym").size().to_dict(), "missing max pain", int(ex_df.max_pain.isna().sum()))


def main():
    scr = sys.argv[1]
    what = sys.argv[2:] or ["yahoo", "grids", "expiries"]
    if "yahoo" in what:
        for k, s in YAHOO.items():
            yahoo(s).to_csv(os.path.join(scr, f"daily_{k}.csv"), index=False)
            print("yahoo", k, flush=True)
    if "grids" in what:
        for sym in FILES:
            grids(scr, sym)
    if "expiries" in what:
        y = pd.read_csv(os.path.join(scr, "daily_NIFTY.csv"), parse_dates=["day"])
        expiries(scr, sorted(y.day.dt.date))


if __name__ == "__main__":
    main()
