"""15-minute decision grid for BTC and ETH perps: bar channels (for the CNN), static features, labels.

python3 -I features.py      -> DATA/feat_15m.parquet (one row per asset x 15-min bar close)

Every feature at bar t uses only information known at that bar's close T = (bar + 1) * 15 min:
  candles up to the bar's close; funding settlements with ts <= T; OI metrics with create_time <= T;
  DVOL hourly bars whose hour has CLOSED by T; event times from the h28 calendar (known in advance).
"""
from __future__ import annotations

import os
import sys
from datetime import datetime, time as dtime
from zoneinfo import ZoneInfo

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
sys.path.append("/root/.local/lib/python3.11/site-packages")
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from common import DATA, log, save  # noqa: E402

EVENTS = "/tmp/claude-0/-home-user-options-lab/7dc6f79a-8e73-5596-b016-f157c0335823/scratchpad/hunt/h28/events.csv"
ASSETS = {"BTC": "BTCUSDT", "ETH": "ETHUSDT"}
BAR = 15                          # minutes
W7, W30 = 672, 2880               # 7 and 30 days of 15-min bars
CH = ["c_r", "c_rng", "c_lv", "c_tb"]   # per-bar sequence channels (own asset; the model adds the other asset's)
HOR = {15: 1, 60: 4, 240: 16}     # horizon minutes -> bars


def bars(sym):
    m = pd.read_parquet(os.path.join(DATA, f"bn_perp_{sym}_1m.parquet"))
    m["b"] = m.t // BAR
    g = m.groupby("b")
    b = pd.DataFrame({"o": g.o.first(), "h": g.h.max(), "l": g.l.min(), "c": g.c.last(), "v": g.v.sum(),
                      "tbv": g.tbv.sum(), "nmin": g.t.size()})
    full = np.arange(b.index.min(), b.index.max() + 1)
    b = b.reindex(full)
    b["c"] = b.c.ffill()
    for k in ("o", "h", "l"):
        b[k] = b[k].fillna(b.c)
    b[["v", "tbv", "nmin"]] = b[["v", "tbv", "nmin"]].fillna(0)
    b.index.name = "b"
    return b.astype({"o": "float64", "h": "float64", "l": "float64", "c": "float64"})


def event_times():
    e = pd.read_csv(EVENTS)
    e = e[e.kind.isin(["uscpi", "fomc"]) & (e.date >= "2019-06-01")]
    ny = ZoneInfo("America/New_York")
    out = []
    for d, k in zip(e.date, e.kind):
        t = dtime(8, 30) if k == "uscpi" else dtime(14, 0)
        dt = datetime.combine(pd.Timestamp(d).date(), t, ny)
        out.append((int(dt.timestamp()), k))
    return pd.DataFrame(out, columns=["ts", "kind"]).sort_values("ts")


def asset_frame(name, sym):
    b = bars(sym)
    T = (b.index.values + 1) * BAR * 60                       # bar close, unix seconds
    c = b.c.values
    r = np.r_[np.nan, np.diff(np.log(c))]
    f = pd.DataFrame(index=b.index)
    f["T"] = T
    f["c"] = c
    f["asset"] = name
    rs = pd.Series(r, index=b.index)
    sig = rs.rolling(W7, min_periods=W7 // 2).std().values
    f["sig"] = sig
    lv = np.log1p(b.v.values * c)
    f["c_r"] = np.clip(r / sig, -10, 10)
    f["c_rng"] = np.clip(np.log(b.h.values / b.l.values) / sig - 1.5, -5, 15)
    f["c_lv"] = np.clip(lv - pd.Series(lv).rolling(W30, min_periods=W7).mean().values, -5, 5)
    f["c_tb"] = np.where(b.v.values > 0, b.tbv.values / np.maximum(b.v.values, 1e-12) - 0.5, 0.0)
    lc = np.log(c)
    for k in (4, 16, 96, 288, 672):
        f[f"ret{k}"] = np.clip((lc - np.r_[np.full(k, np.nan), lc[:-k]]) / (sig * np.sqrt(k)), -8, 8)
    ann = np.sqrt(96 * 365)
    f["rv1d"] = rs.rolling(96).std().values * ann
    f["rv7d"] = sig * ann
    f["rvratio"] = np.log(f.rv1d / f.rv7d)
    hh = pd.Series(np.log(b.h.values)).rolling(96).max().values
    ll = pd.Series(np.log(b.l.values)).rolling(96).min().values
    f["hi24"] = (hh - lc) / (sig * np.sqrt(96))
    f["lo24"] = (lc - ll) / (sig * np.sqrt(96))
    f["lvol"] = f.c_lv.rolling(4).mean()
    f["tb4"] = f.c_tb.rolling(4).mean()
    # funding (8h settlements)
    fu = pd.read_parquet(os.path.join(DATA, "bn_funding.parquet"))
    fu = fu[fu.sym == sym].copy()
    fu["ts"] = fu.ts // 1000
    fu["rate"] = fu.rate.astype(float) * 1e4                        # bp per 8h
    fu["f3"] = fu.rate.rolling(3).mean()
    fu["fz"] = (fu.rate - fu.rate.rolling(90, min_periods=30).mean()) / fu.rate.rolling(90, min_periods=30).std()
    f = asof(f, fu[["ts", "rate", "f3", "fz"]].rename(columns={"rate": "fund"}), "ts")
    # open interest (5-min metrics)
    mt = pd.read_parquet(os.path.join(DATA, "bn_metrics.parquet"))
    mt = mt[mt.sym == sym].sort_values("ts")
    mt["loi"] = np.log(mt.oiv.astype(float))
    # Binance stamps each 5-min metrics row with its window END until 2024-03-03 and its window START from
    # 2024-03-04 (checked: taker ratio vs kline taker volume, corr 0.999 with the matching window). Use the END.
    mt["ts"] = np.where(mt.ts >= 1709510400, mt.ts + 300, mt.ts)
    m2 = mt[["ts", "loi", "toplsr", "takr"]].copy()
    f = asof(f, m2, "ts")
    for k, nm in ((4, "oi1h"), (16, "oi4h"), (96, "oi24h")):
        f[nm] = np.clip(f.loi - f.loi.shift(k), -0.5, 0.5) * 10
    f["toplsr"] = np.log(f.toplsr.astype(float))
    f["takr"] = np.log(f.takr.astype(float))
    f["has_oi"] = f.loi.notna().astype(np.float32)
    # DVOL: hourly bars, usable once the hour has closed
    dv = pd.read_parquet(os.path.join(DATA, "deribit_dvol_1h.parquet"))
    dv = dv[dv.cur == name].copy()
    dv["ts"] = dv.ts // 1000 + 3600
    dv["dvol"] = dv.c / 100
    dv["dvol24"] = np.log(dv.c / dv.c.shift(24))
    f = asof(f, dv[["ts", "dvol", "dvol24"]], "ts")
    f["vrp"] = f.dvol - f.rv7d
    f["has_dvol"] = f.dvol.notna().astype(np.float32)
    # calendar (IST)
    ist = pd.to_datetime(f["T"], unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
    hod = (ist.dt.hour + ist.dt.minute / 60).values
    f["hod_s"], f["hod_c"] = np.sin(2 * np.pi * hod / 24), np.cos(2 * np.pi * hod / 24)
    dow = ist.dt.dayofweek.values + hod / 24
    f["dow_s"], f["dow_c"] = np.sin(2 * np.pi * dow / 7), np.cos(2 * np.pi * dow / 7)
    f["weekend"] = (ist.dt.dayofweek.values >= 5).astype(np.float32)
    f["hod_ist"] = ist.dt.hour.values
    f["dow_ist"] = ist.dt.dayofweek.values
    ev = event_times()
    et = ev.ts.values
    j = np.searchsorted(et, f["T"].values, side="right")
    nxt = np.where(j < len(et), et[np.minimum(j, len(et) - 1)], np.nan)
    prv = np.where(j > 0, et[np.maximum(j - 1, 0)], np.nan)
    f["ev_next_h"] = np.clip((nxt - f["T"].values) / 3600, 0, 72) / 72
    f["ev_prev_h"] = np.clip((f["T"].values - prv) / 3600, 0, 72) / 72
    f["ev_near"] = ((f.ev_next_h * 72 <= 2) | (f.ev_prev_h * 72 <= 2)).astype(np.float32)
    # labels (forward log returns); NaN where not available
    for h, k in HOR.items():
        f[f"y{h}"] = np.r_[lc[k:] - lc[:-k], np.full(k, np.nan)]
    f["nmin"] = b.nmin.values
    return f.reset_index()


def asof(f, g, key):
    g = g.sort_values(key).dropna(subset=[key])
    x = pd.merge_asof(f.reset_index().sort_values("T"), g.rename(columns={key: "T_"}), left_on="T", right_on="T_",
                      direction="backward")
    return x.drop(columns=["T_"]).set_index("b")


def main():
    fs = {a: asset_frame(a, s) for a, s in ASSETS.items()}
    # cross-asset features (other asset's recent move relative to own)
    for a, o in (("BTC", "ETH"), ("ETH", "BTC")):
        x = fs[a].merge(fs[o][["b", "ret4", "ret16", "ret96"]].rename(columns=lambda c: c if c == "b" else "o_" + c),
                        on="b", how="left")
        for k in ("ret4", "ret16", "ret96"):
            x[f"x_{k}"] = x[f"o_{k}"] - x[k]
        fs[a] = x.drop(columns=[c for c in x.columns if c.startswith("o_")])
    f = pd.concat(fs.values(), ignore_index=True)
    f = f.replace([np.inf, -np.inf], np.nan)
    f = f[f["T"] >= pd.Timestamp("2020-01-08", tz="UTC").timestamp()]
    num = f.select_dtypes("float64").columns.difference(["T", "c", "y15", "y60", "y240"])
    f[num] = f[num].astype("float32")
    save(f, "feat_15m.parquet")
    log("features", f.shape, f.groupby("asset").T.agg(["min", "max"]).to_dict())
    log("nan share", f.isna().mean().round(3)[lambda s: s > 0].to_dict())


if __name__ == "__main__":
    main()
