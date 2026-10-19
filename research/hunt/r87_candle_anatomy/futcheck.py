"""Descriptive cross-check on true futures volume and OI (PREREG section 1: futures minutes exist only for contracts
live on 19 Oct 2026, about Jul - Oct 2026, all inside the holdout; run once inside final.py, never used for a rule).

1. Is the option-activity proxy (ATM call + put volume) a fair stand-in for volume? Correlation of per-minute
   option activity with the near-month future's volume, and of their 5-minute sums.
2. For the main candle families on the index 5-minute candles: the near future's volume vs its usual level for that
   slot (median of the previous sessions available, >= 5) and the future's OI change over the candle (% of OI).
"""
from __future__ import annotations

import glob

import numpy as np
import pandas as pd

from . import build as B
from . import cfg
from . import data as D

FAMS = ("big_upper_wick", "big_lower_wick", "both_big_wicks", "small_both_wicks", "doji", "marubozu_green",
        "marubozu_red", "hammer", "shooting_star", "huge_range", "tiny_range", "fp_absorption", "fp_climax",
        "fp_stop_run_up", "fp_stop_run_down")


def near_future(und: str) -> pd.DataFrame:
    """Per minute, the contract with the most volume that day (the near month, except around rolls)."""
    fs = sorted(glob.glob(f"{cfg.RAW}/fut/{und}/*/*.parquet"))
    parts = []
    for f in fs:
        x = pd.read_parquet(f)
        if len(x):
            parts.append(x.assign(con=f.split("/")[-2]))
    if not parts:
        return pd.DataFrame()
    d = pd.concat(parts, ignore_index=True)
    d = d.assign(ts=D._ts(d.timestamp)).rename(columns=dict(volume="v", open_interest="oi"))
    d = D.session_only(d).drop_duplicates(["con", "ts"])
    d["day"] = d.ts.dt.normalize()
    pick = d.groupby(["day", "con"]).v.sum().reset_index().sort_values("v").drop_duplicates("day", keep="last")
    d = d.merge(pick[["day", "con"]], on=["day", "con"])
    return d[["ts", "day", "v", "oi", "close"]].sort_values("ts").reset_index(drop=True)


def check(und: str) -> dict:
    f = near_future(und)
    if f.empty:
        return dict(und=und, available=False)
    o = D.option_minutes(und)
    ch = D.chain_minute(o[o.ts >= f.ts.min()])
    j = f.merge(ch[["ts", "act"]], on="ts", how="inner")
    out = dict(und=und, available=True, days=int(f.day.nunique()), first=str(f.day.min().date()),
               last=str(f.day.max().date()), minutes=int(len(j)))
    if len(j) > 100:
        out["corr_1m"] = float(np.corrcoef(j.v, j.act.fillna(0))[0, 1])
        j5 = j.assign(b=j.ts.dt.floor("5min")).groupby("b")[["v", "act"]].sum()
        out["corr_5m"] = float(np.corrcoef(j5.v, j5.act)[0, 1])
    k = B.load(und, 5, f.day.min().date(), f.day.max().date())
    fm = f.assign(slot=((f.ts.dt.hour * 60 + f.ts.dt.minute - cfg.OPEN_MIN) // 5) * 5)
    fk = fm.groupby(["day", "slot"]).agg(fv=("v", "sum"), oi0=("oi", "first"), oi1=("oi", "last")).reset_index()
    pv = fk.pivot_table(index="day", columns="slot", values="fv")
    usual = pv.shift(1).rolling(20, min_periods=5).median().stack().rename("fv_usual")
    fk = fk.join(usual, on=["day", "slot"])
    k = k.merge(fk, on=["day", "slot"], how="inner")
    k["fv_ratio"] = k.fv / k.fv_usual
    k["doi_pct"] = (k.oi1 - k.oi0) / k.oi0 * 100
    base_v, base_oi = float(k.fv_ratio.median()), float(k.doi_pct.median())
    rows = []
    for fam in FAMS:
        x = k[k[fam].to_numpy(bool)]
        if len(x) < 10:
            continue
        rows.append(dict(family=fam, n=int(len(x)), fut_vol_ratio_median=float(x.fv_ratio.median()),
                         fut_oi_change_pct_median=float(x.doi_pct.median()),
                         oi_up_share=float((x.doi_pct > 0).mean())))
    out.update(all_candles=dict(n=int(len(k)), fut_vol_ratio_median=base_v, fut_oi_change_pct_median=base_oi,
                                oi_up_share=float((k.doi_pct > 0).mean())), families=rows)
    return out
