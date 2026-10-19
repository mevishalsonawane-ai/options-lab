"""L2 clean (MB1): lake rows -> per-minute frames, values never invented.

  spot_frame(opt)      the option book of one symbol -> one row per minute: the near future's close (median of the
                       minute's `spot` values > 0, R53's rule) and the option context: ATM IV (mean of the ATM call and
                       put IV > 0), ATM straddle (call + put close at offset 0), put/call OI over ATM-5..+5, option
                       volume over ATM-3..+3
  fut_frame(cont)      the continuous future (research/data/mcx.py, previous-day volume roll) -> fo, fh, fl, fc, fvol,
                       foi, fexp (the held contract's expiry)
  option_expiries(sf)  the option expiry days, R53's straddle-jump rule (research/hunt/r53/mcx/data.detect_expiries).
                       MCX publishes the expiry calendar in advance; this infers that calendar from the data once, and
                       the cube takes it as a fixed input (it is not recomputed from partial data)

Timestamps become naive IST. Rows outside the MCX session (research/data/mcx.in_session) are dropped and counted.
Duplicated minutes collapse (the median for spot, the last row for the future).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.data import mcx as lake_mcx
from research.hunt.r53.mcx.data import detect_expiries

IST = "Asia/Kolkata"


def naive_ist(ts: pd.Series) -> pd.Series:
    ts = pd.to_datetime(ts)
    return ts.dt.tz_convert(IST).dt.tz_localize(None) if ts.dt.tz is not None else ts


def _session(ts: pd.Series) -> np.ndarray:
    return lake_mcx.in_session(ts.dt.tz_localize(IST)).to_numpy()


def spot_frame(opt: pd.DataFrame) -> tuple[pd.DataFrame, dict]:
    """opt: lake option rows (ts, offset, side, close, iv, volume, oi, spot). Returns (frame, counts)."""
    cols = ["ts", "close", "atm_iv", "straddle", "pcr_oi", "opt_vol"]
    if opt is None or not len(opt):
        return pd.DataFrame(columns=cols), {"rows": 0}
    o = opt.copy()
    o["ts"] = naive_ist(o.ts)
    keep = _session(o.ts)
    out_rows = int((~keep).sum())
    o = o[keep]
    o["side"] = o.side.astype(str)
    g = o.groupby("ts")
    sp = o[o.spot > 0].groupby("ts").spot.median()
    a = o[o.offset == 0]
    iv = a[a.iv > 0].groupby("ts").iv.mean()
    c0 = a[(a.side == "C") & (a.close > 0)].groupby("ts").close.last()
    p0 = a[(a.side == "P") & (a.close > 0)].groupby("ts").close.last()
    w = o[o.offset.between(-5, 5)]
    put_oi = w[w.side == "P"].groupby("ts").oi.sum()
    call_oi = w[w.side == "C"].groupby("ts").oi.sum()
    vol = o[o.offset.between(-3, 3)].groupby("ts").volume.sum()
    f = pd.DataFrame(index=g.size().index)
    f["close"] = sp
    f["atm_iv"] = iv
    f["straddle"] = c0 + p0
    with np.errstate(invalid="ignore", divide="ignore"):
        f["pcr_oi"] = (put_oi / call_oi.where(call_oi > 0)).replace([np.inf, -np.inf], np.nan)
    f["opt_vol"] = vol
    f = f.reset_index().rename(columns={"index": "ts"})
    return f[cols].sort_values("ts").reset_index(drop=True), {"rows": int(len(f)), "outside_session": out_rows}


def fut_frame(cont: pd.DataFrame) -> pd.DataFrame:
    """cont: continuous minutes (ts, open, high, low, close, volume, oi, expiry). Session rows only."""
    cols = ["ts", "fo", "fh", "fl", "fc", "fvol", "foi", "fexp"]
    if cont is None or not len(cont):
        return pd.DataFrame(columns=cols)
    f = cont.copy()
    f["ts"] = naive_ist(f.ts)
    f = f[_session(f.ts)]
    f = f.sort_values("ts").drop_duplicates("ts", keep="last")
    out = pd.DataFrame({"ts": f.ts.values, "fo": f.open.values, "fh": f.high.values, "fl": f.low.values,
                        "fc": f.close.values, "fvol": f.volume.values, "foi": f.oi.values,
                        "fexp": pd.to_datetime(f.expiry.astype(str)).values})
    return out.reset_index(drop=True)


def option_expiries(sf: pd.DataFrame) -> list:
    """Option expiry days (dates) from a spot_frame's straddle (R53 rule: a jump > 2.5x across a day boundary)."""
    if not len(sf):
        return []
    day = pd.DatetimeIndex(sf.ts).normalize().values
    ex = detect_expiries(day, sf.straddle.to_numpy(float))
    return [pd.Timestamp(x).date() for x in ex]
