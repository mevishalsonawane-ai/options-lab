"""h27 exploratory (NOT pre-registered, reported separately with its own BH):
 (a) ES / NQ futures move from the US cash close (16:00 ET) to 08:30 IST (close of the last Yahoo 1h bar that ENDS by
     09:15 IST). Yahoo keeps 1h bars for 730 days only, so pre-holdout = Oct 2024 .. Sep 2025.
 (b) GIFT NIFTY (Dhan daily): implied gap = GIFT close dated D-1 (night session, ends 02:45 IST D) / NIFTY close D-1
     - trailing 60-day median basis; 'surprise' = actual gap - implied gap.
    python3 -I research/hunt/h27/info.py [holdout]
"""
from __future__ import annotations

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
from scipy import stats  # noqa: E402

from obuy.overfit import bh  # noqa: E402

SCR = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h27"
DATA = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/dhan/repo/dhan-data"
HOLD = date(2025, 10, 1)
IST = ZoneInfo("Asia/Kolkata")
NY = ZoneInfo("America/New_York")


def hourly(name):
    j = json.load(open(os.path.join(SCR, "raw", "yahoo1h", name + ".json")))["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    t = pd.to_datetime(np.array(j["timestamp"]), unit="s", utc=True)
    d = pd.DataFrame({"t": t, "c": q["close"]}).dropna()
    d["end"] = d.t + pd.Timedelta(hours=1)
    return d


def overnight(name, days):
    h = hourly(name)
    out = {}
    for d in days:
        cut = datetime(d.year, d.month, d.day, 9, 15, tzinfo=IST)
        before = h[h.end <= cut]
        if not len(before):
            continue
        last = before.iloc[-1]
        if (cut - last.end.to_pydatetime()).total_seconds() > 3 * 3600:
            continue
        # US cash close: the bar ending at 16:00 ET on the last US weekday before d
        ends_ny = before.end.dt.tz_convert(NY)
        cl = before[(ends_ny.dt.hour == 16) & (ends_ny.dt.minute == 0)]
        if not len(cl):
            continue
        c0 = cl.iloc[-1]
        if (cut - c0.end.to_pydatetime()).total_seconds() > 4 * 86400:
            continue
        out[d] = last.c / c0.c - 1
    return pd.Series(out)


def main(holdout):
    P = pd.read_parquet(os.path.join(SCR, "panel.parquet"))
    P = P[(P.day >= HOLD) if holdout else (P.day < HOLD)]
    days = sorted(set(P.day))
    es, nq = overnight("ES_F", days), overnight("NQ_F", days)
    g = pd.read_parquet(os.path.join(DATA, "candles/daily/IDX_I/GIFTNIFTY.parquet"))
    n = pd.read_parquet(os.path.join(DATA, "candles/daily/IDX_I/NIFTY.parquet"))
    for x in (g, n):
        x["d"] = x.ts.dt.tz_localize(None).dt.date
    g = g.drop_duplicates("d").set_index("d").close
    n = n.drop_duplicates("d").set_index("d").close
    j = pd.DataFrame({"g": g, "n": n}).dropna().sort_index()
    basis = (j.g / j.n - 1)
    j["implied"] = (j.g / j.n - 1) - basis.rolling(60, min_periods=20).median()
    imp = {}
    jd = list(j.index)
    for d in days:
        i = pd.Index(jd).searchsorted(d) - 1
        if i >= 0 and (d - jd[i]).days <= 4:
            imp[d] = j.implied.iloc[i]
    imp = pd.Series(imp)
    rows = []
    for u, gg in P.groupby("und"):
        gg = gg.set_index("day")
        feats = {"ES_ovn": es, "NQ_ovn": nq, "GIFT_surprise": gg.gap - imp.reindex(gg.index)}
        for k, s in feats.items():
            s = s.reindex(gg.index)
            for h in ("1015", "1115", "1510"):
                ok = s.notna() & gg["r" + h].notna()
                if ok.sum() < 40:
                    continue
                r, p = stats.pearsonr(s[ok], gg["r" + h][ok])
                rows.append(dict(und=u, feat=k, hz=h, n=int(ok.sum()), corr=r, p=p,
                                 corr_gap=float(np.corrcoef(s[ok], gg.gap[ok])[0, 1])))
    R = pd.DataFrame(rows)
    R["q"] = bh(R.p.values)
    R.to_csv(os.path.join(SCR, f"info_{'hold' if holdout else 'pre'}.csv"), index=False)
    pd.set_option("display.width", 200)
    print(R.round(4).to_string(index=False))


if __name__ == "__main__":
    main(len(sys.argv) > 1 and sys.argv[1] == "holdout")
