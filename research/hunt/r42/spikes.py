"""R42 bad-tick filters for 1-minute option bars (see PREREG.md, written 14 Oct 2026).

F1 (close spike): per contract (day, side, strike), take the median close of up to 5 priced neighbours before
(ref_prev) and of up to 5 after (ref_next), within 15 clock minutes, same day. Flag if the close is > 1.5 x BOTH refs,
or < BOTH refs / 1.5 (amendment 1: two one-sided medians, so the minute before a genuine big re-pricing is kept).
At least one neighbour on each side is needed, else the minute is not judged (kept).
F2 (band): F1, or high > 1.5 x both refs, or low < both refs / 1.5.
S0 (naive sweep, comparison only): |close / previous close - 1| > 50% on consecutive rows of a rolling series.
"""
import numpy as np
import pandas as pd

K_NEIGH, WIN_MIN, RATIO = 5, 15, 1.5


def contract_keys(df):
    """day + side + strike: the same strike under different ATM offsets is one contract."""
    day = df.ts.dt.tz_localize(None).dt.normalize() if df.ts.dt.tz is not None else df.ts.dt.normalize()
    return [day.rename("_day"), df.side.astype(str).rename("_side"), df.strike.astype(float).rename("_k")]


def flag_spikes(df, k=K_NEIGH, win_min=WIN_MIN, ratio=RATIO):
    """df: ts, side, strike, close (and high, low). Returns a frame aligned to df.index with columns
    ref (geometric mean of the two one-sided medians), judged (bool), f1 (bool), f2 (bool)."""
    out = pd.DataFrame({"ref": np.nan, "judged": False, "f1": False, "f2": False}, index=df.index)
    if df.empty:
        return out
    keys = contract_keys(df)
    d = df.assign(_day=keys[0].values, _side=keys[1].values, _k=keys[2].values)
    d = d.sort_values(["_day", "_side", "_k", "ts"], kind="mergesort")
    d = d[~d.duplicated(["_day", "_side", "_k", "ts"], keep="first")]  # one price per contract-minute
    gid = d.groupby(["_day", "_side", "_k"], sort=False, observed=True).ngroup().values
    t = d.ts.values.astype("datetime64[m]").astype(np.int64)
    c = d.close.values.astype(float)
    n = len(d)
    W = win_min
    prev, nxt = [], []
    for s in range(1, k + 1):
        for sgn, acc in ((-1, prev), (1, nxt)):
            j = np.arange(n) + sgn * s
            jj = np.clip(j, 0, n - 1)
            ok = (j >= 0) & (j < n) & (gid[jj] == gid) & (np.abs(t[jj] - t) <= W)
            acc.append(np.where(ok, c[jj], np.nan))
    P, Q = np.column_stack(prev), np.column_stack(nxt)
    judged = np.isfinite(P).any(1) & np.isfinite(Q).any(1)
    rp, rn = np.full(n, np.nan), np.full(n, np.nan)
    if judged.any():
        rp[judged] = np.nanmedian(P[judged], axis=1)
        rn[judged] = np.nanmedian(Q[judged], axis=1)
    hi_ref, lo_ref = np.fmax(rp, rn), np.fmin(rp, rn)
    ref = np.sqrt(rp * rn)  # reported only
    with np.errstate(invalid="ignore", divide="ignore"):
        f1 = judged & ((c > ratio * hi_ref) | (c < lo_ref / ratio))
        f2 = f1.copy()
        if "high" in d and "low" in d:
            f2 |= judged & ((d.high.values > ratio * hi_ref) | (d.low.values < lo_ref / ratio))
    res = pd.DataFrame({"ref": ref, "judged": judged, "f1": f1, "f2": f2}, index=d.index)
    out.loc[res.index, ["ref", "judged", "f1", "f2"]] = res.values
    for col in ("judged", "f1", "f2"):
        out[col] = out[col].astype(bool)
    return out


def naive_sweep(df, thr=0.5):
    """S0: consecutive rows of a rolling series (offset, side, day); flags strike rolls too."""
    if df.empty:
        return pd.Series(False, index=df.index)
    day = df.ts.dt.tz_localize(None).dt.normalize() if df.ts.dt.tz is not None else df.ts.dt.normalize()
    d = df.assign(_day=day).sort_values(["offset", "side", "_day", "ts"], kind="mergesort")
    prev = d.groupby(["offset", "side", "_day"], observed=True, sort=False).close.shift(1)
    flag = (d.close / prev - 1).abs() > thr
    return flag.reindex(df.index).fillna(False).astype(bool)
