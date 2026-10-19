"""R87 loaders: Dhan raw chunks (fetch.py) -> per-instrument minute tables, cached as Parquet under cfg.OUT.

index minutes   ts (naive IST, minute start), o, h, l, c; 09:15-15:29 only; duplicates dropped (last wins)
option minutes  ts, offset (-1..1; PREREG amendment A0), side C/P, o, h, l, c, iv, v (volume, units), strike, oi, spot; the nearest expiry
                (BANKNIFTY: weekly to 20 Nov 2024, monthly from 21 Nov 2024)
chain minute    per ts, from the ATM pair only (offset 0, the only offset kept for every year, PREREG A0): act (ATM call +
                put volume, the index's activity proxy: the index itself has no volume), coi / poi (ATM call / put
                OI), atm (offset-0 strike), straddle (ATM C + P)
"""
from __future__ import annotations

import glob
from datetime import date

import numpy as np
import pandas as pd

from . import cfg


def _read_chunks(pattern: str) -> pd.DataFrame:
    fs = sorted(glob.glob(pattern))
    parts = [pd.read_parquet(f) for f in fs]
    parts = [p for p in parts if len(p)]
    return pd.concat(parts, ignore_index=True) if parts else pd.DataFrame()


def _ts(epoch: pd.Series) -> pd.Series:
    return (pd.to_datetime(epoch.astype("int64"), unit="s", utc=True).dt.tz_convert("Asia/Kolkata")
            .dt.tz_localize(None).dt.floor("min"))


def session_only(df: pd.DataFrame) -> pd.DataFrame:
    m = df.ts.dt.hour * 60 + df.ts.dt.minute
    return df[(m >= cfg.OPEN_MIN) & (m < cfg.OPEN_MIN + cfg.NROWS)]


def index_minutes(und: str, refresh: bool = False) -> pd.DataFrame:
    p = cfg.OUT / f"idx_{und}.parquet"
    if p.exists() and not refresh:
        return pd.read_parquet(p)
    d = _read_chunks(f"{cfg.RAW}/idx/{und}/*.parquet")
    d = d.assign(ts=_ts(d.timestamp)).rename(columns=dict(open="o", high="h", low="l", close="c"))
    d = session_only(d[["ts", "o", "h", "l", "c"]]).drop_duplicates("ts", keep="last").sort_values("ts")
    d = d[(d.ts.dt.date >= cfg.START) & (d.ts.dt.date <= cfg.END)]
    d = d[(d[["o", "h", "l", "c"]] > 0).all(axis=1)].reset_index(drop=True)
    d = clean_sessions(d)
    p.parent.mkdir(parents=True, exist_ok=True)
    d.to_parquet(p, compression="zstd")
    return d


def clean_sessions(d: pd.DataFrame) -> pd.DataFrame:
    """PREREG amendment A2: keep regular sessions only. Dhan serves weekend mock / test sessions with frozen prints
    (e.g. Sat 7 Aug 2021), which give a near-zero ATR and absurd moves. Kept: weekdays with >= 120 minutes and fewer
    than half the minutes flat (high == low). Dropped as a side effect: the two budget weekend sessions and Muhurat."""
    day = d.ts.dt.normalize()
    g = pd.DataFrame(dict(n=d.groupby(day).size(), flat=(d.h == d.l).groupby(day).mean()))
    ok = g[(g.index.dayofweek < 5) & (g.n >= 120) & (g.flat < 0.5)].index
    return d[day.isin(ok)].reset_index(drop=True)


def option_minutes(und: str, refresh: bool = False) -> pd.DataFrame:
    p = cfg.OUT / f"opt_{und}.parquet"
    if p.exists() and not refresh:
        return pd.read_parquet(p)
    parts = []
    for f in sorted(glob.glob(f"{cfg.RAW}/opt/{und}/*/*/*.parquet")):
        flag, ser = f.split("/")[-3], f.split("/")[-2]
        x = pd.read_parquet(f)
        if not len(x):
            continue
        if abs(int(ser[:-1])) > 1:                        # PREREG amendment A0: ATM-1..+1 only
            continue
        x = x.assign(offset=int(ser[:-1]), side=ser[-1], flag=flag)
        parts.append(x)
    if not parts:
        return pd.DataFrame()
    d = pd.concat(parts, ignore_index=True)
    d = d.assign(ts=_ts(d.timestamp)).rename(columns=dict(open="o", high="h", low="l", close="c", volume="v"))
    # BANKNIFTY: weekly series to 20 Nov 2024, monthly afterwards (NSE stopped BANKNIFTY weeklies)
    if und == "BANKNIFTY":
        cut = pd.Timestamp(date(2024, 11, 21))
        d = d[((d.flag == "WEEK") & (d.ts < cut)) | ((d.flag == "MONTH") & (d.ts >= cut))]
    d = session_only(d[["ts", "offset", "side", "o", "h", "l", "c", "iv", "v", "strike", "oi", "spot"]])
    d = d.drop_duplicates(["ts", "offset", "side"], keep="last").sort_values(["ts", "offset", "side"])
    d = d.reset_index(drop=True)
    d.to_parquet(p, compression="zstd")
    return d


def chain_minute(opt: pd.DataFrame) -> pd.DataFrame:
    """Per-minute chain summary from option minutes (see module doc)."""
    if opt.empty:
        return pd.DataFrame(columns=["ts", "act", "coi", "poi", "atm", "straddle"])
    opt = opt[opt.offset == 0]
    g = opt.groupby("ts")
    both = g.side.nunique() == 2                       # A1: activity only where BOTH ATM sides printed
    act = g.v.sum(min_count=1).where(both)
    coi = opt[opt.side == "C"].groupby("ts").oi.sum(min_count=1)
    poi = opt[opt.side == "P"].groupby("ts").oi.sum(min_count=1)
    a = opt[opt.offset == 0]
    atm = a.groupby("ts").strike.first()
    st = a.pivot_table(index="ts", columns="side", values="c", aggfunc="last")
    straddle = (st.get("C") + st.get("P")) if {"C", "P"} <= set(st.columns) else pd.Series(dtype=float)
    out = pd.DataFrame(dict(act=act, coi=coi, poi=poi, atm=atm, straddle=straddle)).reset_index()
    return out.rename(columns={"index": "ts"})


def gex_sign_proxy(opt: pd.DataFrame) -> pd.DataFrame:
    """Per-minute dealer-gamma proxy (labelled an approximation; build.py passes the ATM pair only, PREREG A0, so the
    sign reduces to the ATM call-vs-put OI tilt): customers are assumed long
    calls and long puts (dealers short both is the textbook sign convention used by R28/brain.gex: calls +, puts -).
    Gamma per strike ~ phi(d) / (S * s), d = ln(S/K) / s, with s = sigma*sqrt(T) read from the ATM straddle
    (straddle ~ 0.8 * S * s), so no expiry date or rate is needed. Returns ts, gex (sign carries the meaning),
    gex_ratio = net / gross in [-1, 1]."""
    if opt.empty:
        return pd.DataFrame(columns=["ts", "gex", "gex_ratio"])
    a = opt[opt.offset == 0].pivot_table(index="ts", columns="side", values="c", aggfunc="last")
    sp = opt.groupby("ts").spot.median()
    s = ((a.get("C") + a.get("P")) / (0.8 * sp)).rename("s")
    x = opt[["ts", "side", "strike", "oi"]].merge(s, left_on="ts", right_index=True).merge(
        sp.rename("S"), left_on="ts", right_index=True)
    x = x[(x.s > 0) & (x.strike > 0) & np.isfinite(x.oi)]
    d = np.log(x.S / x.strike) / x.s
    g = np.exp(-0.5 * d * d) / (x.S * x.s) * x.oi * x.S * x.S * 0.01
    sgn = np.where(x.side == "C", 1.0, -1.0)
    x = x.assign(net=g * sgn, gross=g)
    agg = x.groupby("ts")[["net", "gross"]].sum()
    return pd.DataFrame(dict(gex=agg.net, gex_ratio=agg.net / agg.gross.replace(0, np.nan))).reset_index()


def option_day(opt_day: pd.DataFrame) -> dict:
    """Contracts of one day held by (side, strike): (5, 375) arrays O H L C V (NaN where no print). Rolling offsets
    are stitched by strike, so a held contract stays the same strike as the index moves (within ATM-1..+1)."""
    out = {}
    if opt_day.empty:
        return out
    r = ((opt_day.ts.dt.hour * 60 + opt_day.ts.dt.minute) - cfg.OPEN_MIN).to_numpy()
    for (side, k), idx in opt_day.groupby(["side", "strike"]).indices.items():
        A = np.full((5, cfg.NROWS), np.nan)
        sub = opt_day.iloc[idx]
        rr = r[idx]
        ok = (rr >= 0) & (rr < cfg.NROWS)
        for j, col in enumerate(("o", "h", "l", "c", "v")):
            A[j, rr[ok]] = sub[col].to_numpy()[ok]
        out[(side, float(k))] = A
    return out
