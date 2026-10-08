"""Binance public bulk archive (data.binance.vision; the REST API is geo-blocked with HTTP 451 here, the archive is not).

python3 -I fetch_binance.py klines     # USDT-M perpetual 1m candles BTCUSDT/ETHUSDT 2020-01 .. yesterday, spot 1m 2018-2019
python3 -I fetch_binance.py funding    # perpetual funding-rate history (8-hourly)
python3 -I fetch_binance.py metrics    # 5-minute open interest + long/short + taker ratios (daily files, 2020-09 ..)
Output: DATA/bn_{perp|spot}_{SYM}_1m.parquet, bn_funding.parquet, bn_metrics.parquet. Zips are deleted after parsing.
"""
from __future__ import annotations

import io
import os
import sys
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import date, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")  # pandas deps under python -I
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from common import DATA, get, log, save  # noqa: E402

B = "https://data.binance.vision/data"
SYMS = ("BTCUSDT", "ETHUSDT")
TODAY = date.today()


def months(a, b):
    y, m = a
    while (y, m) <= b:
        yield y, m
        m += 1
        if m > 12:
            y, m = y + 1, 1


def read_zip_csv(url):
    b = get(url, min_gap=0.05, raw=True)
    if b is None:
        return None
    with zipfile.ZipFile(io.BytesIO(b)) as z:
        raw = z.read(z.namelist()[0])
    first = raw[:20].decode(errors="ignore")
    header = 0 if first[:1].isalpha() else None
    return pd.read_csv(io.BytesIO(raw), header=header)


def kl_frame(d):
    if d is None or not len(d):
        return None
    d = d.iloc[:, :11]
    d.columns = ["ot", "o", "h", "l", "c", "v", "ct", "qv", "n", "tbv", "tbq"]
    ot = d.ot.astype("int64")
    ot = np.where(ot > 1e14, ot // 1000, ot)     # spot files moved to microseconds in 2025
    return pd.DataFrame({"t": (ot // 60000).astype("int32"), "o": d.o.astype("float32"), "h": d.h.astype("float32"),
                         "l": d.l.astype("float32"), "c": d.c.astype("float32"), "v": d.v.astype("float32"),
                         "tbv": d.tbv.astype("float32"), "n": d.n.astype("int32")})


def klines():
    last_full = (TODAY.replace(day=1) - timedelta(days=1))
    jobs = []
    for s in SYMS:
        for y, m in months((2020, 1), (last_full.year, last_full.month)):
            jobs.append(("perp", s, f"{B}/futures/um/monthly/klines/{s}/1m/{s}-1m-{y}-{m:02d}.zip"))
        d = TODAY.replace(day=1)
        while d < TODAY:
            jobs.append(("perp", s, f"{B}/futures/um/daily/klines/{s}/1m/{s}-1m-{d}.zip"))
            d += timedelta(days=1)
        for y, m in months((2018, 1), (2019, 12)):
            jobs.append(("spot", s, f"{B}/spot/monthly/klines/{s}/1m/{s}-1m-{y}-{m:02d}.zip"))
    with ThreadPoolExecutor(3) as ex:
        res = list(ex.map(lambda j: kl_frame(read_zip_csv(j[2])), jobs))
    for kind in ("perp", "spot"):
        for s in SYMS:
            parts = [r for j, r in zip(jobs, res) if j[0] == kind and j[1] == s and r is not None]
            df = pd.concat(parts).drop_duplicates("t").sort_values("t").reset_index(drop=True)
            p = save(df, f"bn_{kind}_{s}_1m.parquet")
            log(kind, s, len(df), pd.to_datetime(df.t.iloc[0] * 60, unit="s"), pd.to_datetime(df.t.iloc[-1] * 60, unit="s"),
                f"{os.path.getsize(p) / 1e6:.1f} MB", "missing files:",
                sum(1 for j, r in zip(jobs, res) if j[0] == kind and j[1] == s and r is None))


def funding():
    out = []
    for s in SYMS:
        for y, m in months((2020, 1), (TODAY.year, TODAY.month)):
            d = read_zip_csv(f"{B}/futures/um/monthly/fundingRate/{s}/{s}-fundingRate-{y}-{m:02d}.zip")
            if d is None:
                continue
            d = d.iloc[:, [0, 2]]
            d.columns = ["ts", "rate"]
            d["sym"] = s
            out.append(d)
    f = pd.concat(out)
    f["ts"] = f.ts.astype("int64")
    f = f.drop_duplicates(["sym", "ts"]).sort_values(["sym", "ts"])
    save(f, "bn_funding.parquet")
    log("funding", len(f), f.groupby("sym").ts.agg(["min", "max", "count"]).to_dict())


def metrics():
    jobs = []
    for s in SYMS:
        d = date(2020, 9, 1)
        while d < TODAY:
            jobs.append((s, f"{B}/futures/um/daily/metrics/{s}/{s}-metrics-{d}.zip"))
            d += timedelta(days=1)

    def one(j):
        d = read_zip_csv(j[1])
        if d is None or not len(d):
            return None
        d = d.rename(columns=str.strip)
        o = pd.DataFrame({"ts": pd.to_datetime(d.create_time).values.astype("datetime64[s]").astype("int64"),
                          "oi": d.sum_open_interest.astype("float64"),
                          "oiv": d.sum_open_interest_value.astype("float64"),
                          "toplsr": pd.to_numeric(d.get("count_toptrader_long_short_ratio"), errors="coerce"),
                          "lsr": pd.to_numeric(d.get("count_long_short_ratio"), errors="coerce"),
                          "takr": pd.to_numeric(d.get("sum_taker_long_short_vol_ratio"), errors="coerce")})
        o["sym"] = j[0]
        return o
    with ThreadPoolExecutor(3) as ex:
        res = list(ex.map(one, jobs))
    f = pd.concat([r for r in res if r is not None]).drop_duplicates(["sym", "ts"]).sort_values(["sym", "ts"])
    for c in ("oi", "oiv", "toplsr", "lsr", "takr"):
        f[c] = f[c].astype("float32")
    save(f, "bn_metrics.parquet")
    log("metrics", len(f), "missing days", sum(r is None for r in res), f.groupby("sym").ts.agg(["min", "max", "count"]).to_dict())


if __name__ == "__main__":
    for a in sys.argv[1:]:
        globals()[a]()
