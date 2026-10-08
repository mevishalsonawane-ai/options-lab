"""h41 part 2: volume by time of day, the 15:00 minute, and how far the index travels in 15/30/60 minutes.

Run:  python3 -I research/hunt/h41/volume_moves.py
Out:  scratchpad/hunt/h41/volume_moves.log + CSVs.

V1  option volume share by 15-min bucket: NIFTY & SENSEX nearest-weekly ATM+-10 (CE+PE), BANKNIFTY nearest monthly
V2  cash volume share by bucket: 10 NIFTY heavyweights, NSE_EQ minutes (data from Oct 2024)
X   the 14:58-15:02 minutes (is the 15:00 spike real?)
H   |index move| over the next 15/30/60 min from fixed start times, bps and points, and what 1 lot of a 1-ITM option
    (delta ~0.6) gains or loses on that move, vs the round-trip cost of ~Rs 100-200 measured in h24/h34
"""
from __future__ import annotations

import glob
import os
import sys
from datetime import date

sys.path.append("/root/.local/lib/python3.11/site-packages")
HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.dirname(os.path.dirname(HERE)))
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import pyarrow.parquet as pq  # noqa: E402
from obuy import config as C  # noqa: E402
from obuy import data as D  # noqa: E402

OUT = os.path.join(C.SCRATCH, "hunt", "h41")
LOG = open(os.path.join(OUT, "volume_moves.log"), "w")
P2_START, P3_START = date(2024, 11, 20), date(2025, 7, 4)
BUCKETS = [(0, 15, "09:15-09:30"), (15, 45, "09:30-10:00"), (45, 105, "10:00-11:00"), (105, 165, "11:00-12:00"),
           (165, 225, "12:00-13:00"), (225, 285, "13:00-14:00"), (285, 345, "14:00-15:00"), (345, 375, "15:00-15:30")]


def say(*a):
    s = " ".join(str(x) for x in a)
    print(s)
    LOG.write(s + "\n")
    LOG.flush()


def period(d):
    return "P1 pre-Nov24" if d < P2_START else ("P2 Nov24-Jul25" if d < P3_START else "P3 post-JS")


def bucket_share(mat):
    """mat: (days, 375) volume -> mean per-day share (%) per bucket, and per-minute rate relative to the day's mean."""
    tot = mat.sum(1, keepdims=True)
    ok = tot[:, 0] > 0
    sh = mat[ok] / tot[ok]
    out = {}
    for a, b, n in BUCKETS:
        out[n] = 100 * sh[:, a:b].sum(1).mean()
    out["first5min_%"] = 100 * sh[:, :5].sum(1).mean()
    out["last30_rate_vs_mid"] = sh[:, 345:].mean() / sh[:, 165:225].mean()
    out["first15_rate_vs_mid"] = sh[:, :15].mean() / sh[:, 165:225].mean()
    return out, int(ok.sum())


def minute_matrix(df):
    df = df.copy()
    ts = df.ts.dt.tz_localize(None)
    df["day"] = ts.dt.date
    df["m"] = ts.dt.hour * 60 + ts.dt.minute - C.OPEN_M
    df = df[(df.m >= 0) & (df.m < C.W)]
    g = df.groupby(["day", "m"]).volume.sum().unstack(fill_value=0)
    g = g.reindex(columns=range(C.W), fill_value=0)
    return g


# ------------------------------------------------------------------------------------------------ V1 option volume
say("## V1 option volume share by bucket (% of the day's ATM+-10 nearest-expiry option volume)")
rows = []
for und, ser in [("NIFTY", "WEEK"), ("SENSEX", "WEEK"), ("BANKNIFTY", "MONTH")]:
    parts = []
    for side in ("CALL", "PUT"):
        for f in sorted(glob.glob(os.path.join(C.DATA, "options", und, ser, side, "*.parquet"))):
            t = pq.read_table(f, columns=["ts", "volume", "offset"]).to_pandas()
            t["side"] = side
            parts.append(t)
    df = pd.concat(parts).drop_duplicates(["ts", "offset", "side"])
    g = minute_matrix(df)
    g = g[g.sum(1) > 0]
    per = np.array([period(d) for d in g.index])
    for p in ["ALL", "P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]:
        s = np.ones(len(g), bool) if p == "ALL" else per == p
        if s.sum() < 20:
            continue
        o, n = bucket_share(g.values[s].astype(float))
        rows.append(dict(src=f"{und} {ser.lower()} options", per=p, n=n, **o))
    del df, g, parts

# ------------------------------------------------------------------------------------------------ V2 stock volume
HEAVY = ["HDFCBANK", "ICICIBANK", "RELIANCE", "INFY", "BHARTIARTL", "LT", "ITC", "TCS", "SBIN", "AXISBANK"]
parts = []
for s in HEAVY:
    for f in sorted(glob.glob(os.path.join(C.DATA, "candles", "minute", "NSE_EQ", s, "*.parquet"))):
        t = pd.read_parquet(f, columns=["ts", "close", "volume"])
        t["volume"] = t.volume * t.close   # traded value
        parts.append(t[["ts", "volume"]])
g = minute_matrix(pd.concat(parts))
g = g[g.sum(1) > 0]
per = np.array([period(d) for d in g.index])
for p in ["ALL", "P1 pre-Nov24", "P2 Nov24-Jul25", "P3 post-JS"]:
    s = np.ones(len(g), bool) if p == "ALL" else per == p
    if s.sum() < 20:
        continue
    o, n = bucket_share(g.values[s].astype(float))
    rows.append(dict(src="10 heavyweights cash value", per=p, n=n, **o))
V = pd.DataFrame(rows)
V.to_csv(os.path.join(OUT, "V_volume_by_bucket.csv"), index=False)
say(V.set_index(["src", "per"]).to_string(float_format=lambda x: f"{x:.1f}"))
prof = pd.Series(g.values.astype(float).sum(0) / g.values.sum() * 100,
                 index=[f"{(555 + i) // 60:02d}:{(555 + i) % 60:02d}" for i in range(C.W)])
prof.to_csv(os.path.join(OUT, "V_heavy_minute_profile.csv"))
say("heavyweights minute share %: " + ", ".join(f"{k} {prof[k]:.2f}" for k in ["09:15", "09:16", "09:20", "09:30", "10:00", "12:00", "14:00", "15:00", "15:15", "15:25", "15:29"]))

# ------------------------------------------------------------------------------------------------ X + H on index minutes
mk = D.market()
say("\n## X. the minutes around 15:00 (mean |1-min return| bps)")
rowsX, rowsH = [], []
for u in ["NIFTY", "BANKNIFTY", "SENSEX"]:
    ix = mk.index(u)
    M = ix.mat()
    dd = ix.daily()
    keep = (np.isfinite(M["c"]).sum(1) >= 370) & dd.real.values
    days = [d for d, k in zip(ix.days, keep) if k]
    c = pd.DataFrame(M["c"][keep].T).ffill().bfill().values.T
    r = np.abs(np.diff(np.log(c), axis=1)) * 1e4      # r[:, j] = move into minute j+1
    per = np.array([period(d) for d in days])
    yr = np.array([d.year for d in days])
    for g_, s in [("ALL", np.ones(len(days), bool))] + [(f"Y{y}", yr == y) for y in sorted(set(yr))]:
        row = dict(und=u, group=g_, n=int(s.sum()))
        for j in range(341, 348):   # minute 342 = 14:57 ... into 15:02
            m = j + 1
            row[f"{(555 + m) // 60:02d}:{(555 + m) % 60:02d}"] = r[s, j].mean()
        row["midday_avg"] = r[s, 165:225].mean()
        rowsX.append(row)
    lot = ix.lot(days[-1], "today")
    for start in ["09:20", "10:00", "11:00", "12:00", "13:00", "14:00", "14:45"]:
        j0 = int(start[:2]) * 60 + int(start[3:]) - C.OPEN_M
        for k in (15, 30, 60):
            if j0 + k >= C.W:
                continue
            mv = np.log(c[:, j0 + k] / c[:, j0])   # from the close of the start minute
            pts = np.abs(c[:, j0 + k] - c[:, j0])
            for p in ["ALL", "P3 post-JS"]:
                s = np.ones(len(days), bool) if p == "ALL" else per == p
                rowsH.append(dict(und=u, per=p, start=start, mins=k, n=int(s.sum()),
                                  med_abs_bps=np.median(np.abs(mv[s])) * 1e4, p75_abs_bps=np.percentile(np.abs(mv[s]), 75) * 1e4,
                                  med_pts=np.median(pts[s]), p75_pts=np.percentile(pts[s], 75),
                                  lot=lot, med_rs_1lot_delta06=np.median(pts[s]) * 0.6 * lot,
                                  p75_rs_1lot_delta06=np.percentile(pts[s], 75) * 0.6 * lot))
    mk.release(u)
X = pd.DataFrame(rowsX)
X.to_csv(os.path.join(OUT, "X_1500.csv"), index=False)
say(X.set_index(["und", "group"]).to_string(float_format=lambda x: f"{x:.2f}"))
H = pd.DataFrame(rowsH)
H.to_csv(os.path.join(OUT, "H_moves.csv"), index=False)
say("\n## H. how far the index moves in the next 15/30/60 minutes (abs), and Rs for 1 lot of a delta-0.6 option")
say(H.set_index(["und", "per", "start", "mins"]).to_string(float_format=lambda x: f"{x:.1f}"))
say("done")
