"""h27 build: parse the raw downloads (untrusted; run with python -I) into one feature row per Indian trading day,
plus per-index intraday outcomes. Only information known by 09:15 IST of day D goes into the cues (see PREREG.md).

    OBUY_CACHE=<scratch>/hunt/h27/cache python3 -I research/hunt/h27/build.py
Writes <scratch>/hunt/h27/feat.parquet (one row per day) and outcomes.parquet (one row per index-day).
"""
from __future__ import annotations

import csv
import glob
import json
import os
import sys
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27"
RAW = os.path.join(SCR, "raw")
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
STALE = 4   # calendar days a foreign close may lag the Indian day and still count


def yahoo(name):
    """Daily bars of one Yahoo file -> DataFrame indexed by LOCAL exchange date: open, close."""
    j = json.load(open(os.path.join(RAW, "yahoo", name + ".json")))["chart"]["result"][0]
    tz = ZoneInfo(j["meta"]["exchangeTimezoneName"])
    q = j["indicators"]["quote"][0]
    rows = []
    for t, o, c in zip(j["timestamp"], q["open"], q["close"]):
        if o is None or c is None or not (o > 0 and c > 0):
            continue
        rows.append((datetime.fromtimestamp(t, tz).date(), float(o), float(c)))
    d = pd.DataFrame(rows, columns=["d", "open", "close"]).drop_duplicates("d", keep="last").set_index("d").sort_index()
    d["ret"] = d.close / d.close.shift(1) - 1
    d["gap"] = d.open / d.close.shift(1) - 1
    d["oc"] = d.close / d.open - 1
    return d


def last_before(df, col, day, strict=True):
    """Value of df[col] on the last local date < day (strict) or <= day, if within STALE days, else 0."""
    idx = df.index
    i = idx.searchsorted(day, side="left" if strict else "right") - 1
    if i < 0:
        return np.nan
    if (day - idx[i]).days > STALE:
        return 0.0
    return float(df[col].iloc[i])


def on_day(df, col, day):
    return float(df[col].loc[day]) if day in df.index else 0.0


def poi():
    """FII index futures net long (contracts) per day from the participant OI files."""
    out = {}
    for f in sorted(glob.glob(os.path.join(RAW, "poi", "*.csv"))):
        d = datetime.strptime(os.path.basename(f)[:8], "%Y%m%d").date()
        try:
            with open(f, newline="") as fh:
                for r in csv.reader(fh):
                    if r and r[0].strip() == "FII":
                        out[d] = float(r[1]) - float(r[2])
        except Exception:
            pass
    s = pd.Series(out).sort_index()
    return s


def main():
    from obuy.data import market
    mk = market()
    # ------------------------------------------------------------- Indian days and outcomes
    out_rows = []
    daysets = {}
    for u in UNDS:
        ix = mk.index(u)
        M = ix.mat()
        dl = ix.daily()
        prev_close = dl.close.shift(1)
        for i, d in enumerate(ix.days):
            if not ix.d[d]["real"] or i == 0:
                continue
            o, c = M["o"][i], M["c"][i]

            def at(hm):
                k = hm - 555
                v = c[k]
                if not np.isfinite(v):
                    seg = c[max(0, k - 3):k + 1]
                    v = seg[np.isfinite(seg)][-1] if np.isfinite(seg).any() else np.nan
                return v
            op = o[0] if np.isfinite(o[0]) else np.nan
            pc = prev_close.iloc[i]
            out_rows.append(dict(und=u, day=d, open=op, pc=pc, gap=op / pc - 1, c920=at(560), c930=at(570),
                                 c1015=at(615), c1115=at(675), c1510=at(910), close=dl.close.iloc[i],
                                 lot=ix.d[d]["lot"], exp=ix.d[d]["exp"]))
        daysets[u] = set(ix.days)
    oc = pd.DataFrame(out_rows)
    for h in ("1015", "1115", "1510"):
        oc["r" + h] = oc["c" + h] / oc.c920 - 1
    oc["r930"] = oc.c930 / oc.open - 1
    # ------------------------------------------------------------- foreign data
    Y = {k: yahoo(k) for k in ["_GSPC", "_NDX", "HDB", "IBN", "INFY", "INDA", "_N225", "_KS11", "_HSI", "CL_F",
                               "INR_X", "DX_Y_NYB", "_TNX", "GC_F", "_VIX", "_NSEI"]}
    vix = mk.vix.daily
    fii = poi()
    days = sorted(set(oc.day))
    nsei = Y["_NSEI"]
    rows = []
    for d in days:
        r = dict(day=d)
        r["SPX"] = last_before(Y["_GSPC"], "ret", d)
        r["NDX"] = last_before(Y["_NDX"], "ret", d)
        r["SPXOC"] = last_before(Y["_GSPC"], "oc", d)
        r["ADR"] = np.nanmean([last_before(Y[k], "ret", d) for k in ("HDB", "IBN", "INFY")])
        inda = last_before(Y["INDA"], "ret", d)
        nret = last_before(nsei, "ret", d)
        r["INDARES"] = inda - nret
        r["ASIA"] = np.nanmean([on_day(Y[k], "gap", d) for k in ("_N225", "_KS11", "_HSI")])
        r["CRUDE"] = -last_before(Y["CL_F"], "ret", d)
        r["USDINR"] = -last_before(Y["INR_X"], "ret", d)
        r["DXY"] = -last_before(Y["DX_Y_NYB"], "ret", d)
        tnx = Y["_TNX"]
        i = tnx.index.searchsorted(d) - 1
        r["US10Y"] = -(tnx.close.iloc[i] - tnx.close.iloc[i - 1]) if i > 0 else np.nan
        r["GOLD"] = -last_before(Y["GC_F"], "ret", d)
        r["USVIX"] = -last_before(Y["_VIX"], "ret", d)
        vi = vix.index.searchsorted(d) - 1
        r["INVIX"] = -(vix.close.iloc[vi] / vix.close.iloc[vi - 1] - 1) if vi > 0 else np.nan
        fi = fii.index.searchsorted(d) - 1
        r["FII_lvl"] = fii.iloc[fi] if fi >= 0 and (d - fii.index[fi]).days <= STALE else np.nan
        r["FII_chg"] = fii.iloc[fi] - fii.iloc[fi - 1] if fi > 0 and (d - fii.index[fi]).days <= STALE else np.nan
        rows.append(r)
    F = pd.DataFrame(rows).set_index("day")
    F["FII"] = F.FII_chg
    CUES = ["SPX", "NDX", "SPXOC", "ADR", "INDARES", "ASIA", "CRUDE", "USDINR", "DXY", "US10Y", "GOLD", "USVIX",
            "INVIX", "FII"]
    Z = pd.DataFrame(index=F.index)
    for k in CUES:
        x = F[k].astype(float)
        win = 60 if k == "FII" else 250
        sd = x.rolling(win, min_periods=40).std().shift(1)      # trailing, excludes today
        Z[k] = (x / sd).clip(-5, 5)
    Z["COMP"] = Z[["SPX", "ADR", "ASIA"]].mean(axis=1)
    F = F.join(Z.add_prefix("z_"))
    F.to_parquet(os.path.join(SCR, "feat.parquet"))
    oc.to_parquet(os.path.join(SCR, "outcomes.parquet"))
    print("days", len(F), F.index.min(), F.index.max())
    print(F[["z_" + k for k in CUES + ["COMP"]]].describe().T[["count", "mean", "std"]])
    print(oc.groupby("und").day.agg(["count", "min", "max"]))


if __name__ == "__main__":
    main()
