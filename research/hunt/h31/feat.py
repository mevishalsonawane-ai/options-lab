"""h31 features: one row per (index, session d) with what is known at 15:20 IST, plus the next session and the
index overnight outcomes (descriptive only). See PREREG.md. Raw inputs are untrusted: run with python -I.

    OBUY_CACHE=<scratch>/hunt/h31/cache flock <scratch>/obuy.lock python3 -I research/hunt/h31/feat.py
"""
from __future__ import annotations

import csv
import glob
import json
import os
import sys
from bisect import bisect_left
from datetime import date, datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy.data import market  # noqa: E402

SCR = os.path.join(C.SCRATCH, "hunt")
OUT = os.path.join(SCR, "h31")
UNDS = ("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX")
S1519 = 15 * 60 + 19 - C.OPEN_M     # column of the 15:19 bar
S1419 = 14 * 60 + 19 - C.OPEN_M


def last_valid(a, k, back=5):
    seg = a[max(0, k - back):k + 1]
    ok = np.isfinite(seg)
    return seg[ok][-1] if ok.any() else np.nan


def breadth():
    """{day: (share up at 15:19 from minutes or NaN, share up at the daily close)}."""
    D = C.DATA
    closes = {}
    for f in sorted(glob.glob(os.path.join(D, "candles", "daily", "NSE_EQ", "*.parquet"))):
        s = pd.read_parquet(f, columns=["ts", "close"])
        s["d"] = s.ts.dt.tz_localize(None).dt.date
        closes[os.path.basename(f)[:-8]] = s.drop_duplicates("d", keep="last").set_index("d").close.astype(float)
    P = pd.DataFrame(closes).sort_index()
    P = P[P.index >= date(2019, 1, 1)]
    prev = P.shift(1)
    up = (P > prev).astype(float).where(P.notna() & prev.notna())
    daily_share = up.sum(axis=1) / up.notna().sum(axis=1).replace(0, np.nan)
    # minute: 15:19 close (or last bar <= 15:19)
    mrows = {}
    for sym in P.columns:
        fs = sorted(glob.glob(os.path.join(D, "candles", "minute", "NSE_EQ", sym, "*.parquet")))
        if not fs:
            continue
        m = pd.concat([pd.read_parquet(f, columns=["ts", "close"]) for f in fs])
        m["ts"] = m.ts.dt.tz_localize(None)
        mm = m.ts.dt.hour * 60 + m.ts.dt.minute
        m = m[(mm >= 15 * 60 + 10) & (mm <= 15 * 60 + 19)]
        m["d"] = m.ts.dt.date
        mrows[sym] = m.sort_values("ts").groupby("d").close.last().astype(float)
    M = pd.DataFrame(mrows).sort_index()
    pv = prev.reindex(M.index)[M.columns]
    mu = (M > pv).astype(float).where(M.notna() & pv.notna())
    min_share = mu.sum(axis=1) / mu.notna().sum(axis=1).replace(0, np.nan)
    min_share = min_share[mu.notna().sum(axis=1) >= 100]
    return pd.DataFrame(dict(br_min=min_share, br_daily=daily_share))


def es_session():
    j = json.load(open(os.path.join(SCR, "h27", "raw", "yahoo1h", "ES_F.json")))["chart"]["result"][0]
    q = j["indicators"]["quote"][0]
    o, c = {}, {}
    for t, op, cl in zip(j["timestamp"], q["open"], q["close"]):
        u = datetime.fromtimestamp(t, timezone.utc)
        if u.minute != 0:
            continue
        if u.hour == 4 and op:
            o[u.date()] = float(op)
        if u.hour == 8 and cl:
            c[u.date()] = float(cl)
    return {d: c[d] / o[d] - 1 for d in o if d in c}


def fii():
    out = {}
    for f in sorted(glob.glob(os.path.join(SCR, "h27", "raw", "poi", "*.csv"))):
        d = datetime.strptime(os.path.basename(f)[:8], "%Y%m%d").date()
        try:
            with open(f, newline="") as fh:
                for r in csv.reader(fh):
                    if r and r[0].strip() == "FII":
                        out[d] = float(r[1]) - float(r[2])
        except Exception:
            pass
    s = pd.Series(out).sort_index()
    ch = s.diff()
    z = (ch - ch.rolling(60).mean().shift(1)) / ch.rolling(60).std().shift(1)
    return z            # indexed by publication session (known the evening of that day)


def events():
    e = pd.read_csv(os.path.join(SCR, "h28", "events.csv"))
    e["date"] = pd.to_datetime(e.date).dt.date
    return {k: sorted(set(e[e.kind == k].date)) for k in ("fomc", "uscpi")}


def main():
    mk = market()
    br = breadth()
    print("breadth rows", len(br), "minute rows", br.br_min.notna().sum(), flush=True)
    ov = br.dropna()
    print("breadth min vs daily corr", round(ov.br_min.corr(ov.br_daily), 3), "sign agree",
          round(((ov.br_min > .5) == (ov.br_daily > .5)).mean(), 3), flush=True)
    es = es_session()
    fz = fii()
    ev = events()
    vx = mk.vix
    vmin = vx.minutes()
    vd = vx.daily.close
    vmed = vd.rolling(60).median()
    rows = []
    for u in UNDS:
        ix = mk.index(u)
        Mx = ix.mat()
        days = ix.days
        for i in range(1, len(days) - 1):
            d, nd, pd_ = days[i], days[i + 1], days[i - 1]
            o, h, l, c = (Mx[k][i] for k in ("o", "h", "l", "c"))
            x = last_valid(c, S1519)
            if not np.isfinite(x):
                continue
            hi, lo = np.nanmax(h[:S1519 + 1]), np.nanmin(l[:S1519 + 1])
            pc = last_valid(Mx["c"][i - 1], C.W - 1, 30)
            cl = last_valid(c, C.W - 1, 30)
            op0 = o[np.isfinite(o)][0] if np.isfinite(o).any() else np.nan
            no = Mx["o"][i + 1]
            nc = Mx["c"][i + 1]
            n_open = no[np.isfinite(no)][0] if np.isfinite(no).any() else np.nan
            r = dict(und=u, day=d, nday=nd, nights=(nd - d).days, wd=d.weekday(), exp=bool(ix.d[d]["exp"]),
                     nexp=bool(ix.d[nd]["exp"]), real=bool(ix.d[d]["real"] and ix.d[nd]["real"]), lot=ix.lot(d),
                     x=x, close=cl, pc=pc,
                     loc=(x - lo) / (hi - lo) if hi > lo else 0.5,
                     LH=x / last_valid(c, S1419) - 1, DAY=x / pc - 1, ITR=x / op0 - 1,
                     n_open=n_open, n_c0915=nc[0] if np.isfinite(nc[0]) else n_open,
                     n_c1015=last_valid(nc, 60), n_close=last_valid(nc, C.W - 1, 30))
            # breadth
            if d in br.index:
                bm, bd = br.br_min.get(d, np.nan), br.br_daily.get(d, np.nan)
                r["BR_min"] = bm
                r["BR"] = bm if np.isfinite(bm) else bd
                r["BR_lookahead"] = not np.isfinite(bm)
            else:
                r["BR"], r["BR_min"], r["BR_lookahead"] = np.nan, np.nan, True
            # VIX
            va = vmin.get(d)
            vnow = last_valid(va, S1519) if va is not None else np.nan
            vprev = vx.prev_close(d)
            r["VIX"] = vnow
            r["VIXCH"] = vnow / vprev - 1 if np.isfinite(vnow) else np.nan
            k = vd.index.searchsorted(d) - 1
            r["VIXLOW"] = bool(vnow < vmed.iloc[k]) if (np.isfinite(vnow) and k >= 0) else np.nan
            r["ES"] = es.get(d, np.nan)
            k = fz.index.searchsorted(d) - 1             # last file published BEFORE day d (evening of d-1)
            r["FIIZ"] = float(fz.iloc[k]) if k >= 0 and (d - fz.index[k]).days <= 5 else np.nan
            for kind, lst in ev.items():
                # an event on US date e belongs to the night after the last Indian session <= e
                j = bisect_left(lst, d)
                r[kind] = any(d <= e < nd for e in lst[j:j + 3])
            rows.append(r)
        print(u, len(rows), flush=True)
    f = pd.DataFrame(rows)
    f["gap"] = f.n_open / f.close - 1           # descriptive: 15:29 close -> next open
    f["ov"] = f.n_open / f.x - 1                # 15:19 -> next open
    f["ov1015"] = f.n_c1015 / f.x - 1
    f.to_parquet(os.path.join(OUT, "feat.parquet"))
    print(f.groupby("und").agg(n=("day", "size"), d0=("day", "min"), d1=("day", "max"), es=("ES", "count"),
                               fii=("FIIZ", "count"), vix=("VIXCH", "count"), br=("BR", "count"),
                               fomc=("fomc", "sum"), cpi=("uscpi", "sum")).to_string())


if __name__ == "__main__":
    main()
