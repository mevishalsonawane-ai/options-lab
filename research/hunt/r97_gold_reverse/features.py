"""R97 step 2: what the market looked like in the minutes BEFORE a move, using only what was known at that minute.

`day_features` returns one row per grid minute of a session. Every column at row i is computed from rows 0..i of the
session, the previous session's levels, and context series up to the minute's close (look-ahead guard:
tests/test_r97.py truncates the inputs after row i and checks row i is unchanged).

MCX rows are minute bars stamped with the bar START; the close is known at ts + 1 min. Context series (XAUUSD,
SILVER) are read at or before that same instant.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

ROUND = 1000.0


def _ago(s: pd.Series, t: pd.DatetimeIndex, minutes: int) -> np.ndarray:
    """Value of series s (sorted index) at or before t - minutes (NaN when older than minutes + 5 min)."""
    if s is None or not len(s):
        return np.full(len(t), np.nan)
    q = t - pd.Timedelta(minutes=minutes)
    pos = s.index.searchsorted(q, side="right") - 1
    v = np.where(pos >= 0, s.values[np.clip(pos, 0, None)], np.nan)
    age = np.where(pos >= 0, (q - s.index[np.clip(pos, 0, None)]).total_seconds() / 60.0, np.inf)
    return np.where(age <= 5, v, np.nan)


def day_features(ts, F, tod, prev: dict | None, vol: pd.Series | None = None, xau: pd.Series | None = None,
                 ag: pd.Series | None = None, events: list | None = None) -> pd.DataFrame:
    """ts: bar starts (naive IST) of one session; F: minute closes; tod: minute of day; prev: previous session's
    high / low / close (or None); vol: option volume per bar start; xau / ag: context closes indexed by the instant
    they are known; events: [(kind, release minute)] of the day."""
    t = pd.DatetimeIndex(ts)
    known = t + pd.Timedelta(minutes=1)              # when the row's close is known
    s = pd.Series(np.asarray(F, float), index=t)
    tod = np.asarray(tod, int)
    out = pd.DataFrame(index=range(len(t)))
    out["tod"] = tod
    out["F"] = s.values
    for w in (30, 60):
        r = s.rolling(f"{w}min")
        out[f"rng{w}"] = (r.max() - r.min()).values
    out["ret15"] = s.values - _ago(s, t, 15)
    out["ret5"] = s.values - _ago(s, t, 5)
    mn15 = s.rolling("15min").min().values
    mx15 = s.rolling("15min").max().values
    out["twap_dev"] = s.values - s.expanding().mean().values
    out["day_hi"] = s.cummax().values
    out["day_lo"] = s.cummin().values
    # previous session levels
    if prev:
        pdh, pdl, pdc = prev["high"], prev["low"], prev["close"]
        out["gap"] = float(s.values[0] - pdc)
        out["x_up_pdh15"] = (s.values > pdh) & (mn15 <= pdh)
        out["x_dn_pdl15"] = (s.values < pdl) & (mx15 >= pdl)
        out["above_pdh"] = s.values > pdh
        out["below_pdl"] = s.values < pdl
        out["prev_range"] = float(pdh - pdl)
    else:
        for c in ("gap", "prev_range"):
            out[c] = np.nan
        for c in ("x_up_pdh15", "x_dn_pdl15", "above_pdh", "below_pdl"):
            out[c] = False
    # opening range 09:00-09:29, known from 09:30
    orm = tod < 570
    if orm.any() and (~orm).any():
        orh, orl = float(s.values[orm].max()), float(s.values[orm].min())
        ok = tod >= 570
        out["x_up_or15"] = ok & (s.values > orh) & (mn15 <= orh)
        out["x_dn_or15"] = ok & (s.values < orl) & (mx15 >= orl)
    else:
        out["x_up_or15"] = False
        out["x_dn_or15"] = False
    # round numbers (multiples of 1000 points) crossed in the last 15 minutes
    out["x_up_round15"] = np.floor(s.values / ROUND) > np.floor(mn15 / ROUND)
    out["x_dn_round15"] = np.floor(mx15 / ROUND) > np.floor(s.values / ROUND)
    # option activity: volume of the last 5 bars over 5 x the per-bar mean of the 120 bars before them
    if vol is not None and len(vol):
        v = vol.reindex(t).fillna(0.0).values
        cs = np.r_[0.0, np.cumsum(v)]
        i = np.arange(len(v))
        last5 = cs[i + 1] - cs[np.maximum(i - 4, 0)]
        lo = np.maximum(i - 124, 0)
        hi = np.maximum(i - 4, 0)
        base = np.where(hi - lo > 0, (cs[hi] - cs[lo]) / np.maximum(hi - lo, 1), np.nan)
        out["vol_ratio"] = np.where(base > 0, last5 / (5 * base), np.nan)
    else:
        out["vol_ratio"] = np.nan
    # context moves in basis points over the last 5 / 15 minutes (read at the instant the MCX close is known)
    out["mcx5_bp"] = 1e4 * (s.values / _ago(s, t, 5) - 1)
    out["mcx15_bp"] = 1e4 * (s.values / _ago(s, t, 15) - 1)
    for name, ser in (("xau", xau), ("ag", ag)):
        now = _ago(ser, known, 0)
        for w in (5, 15):
            out[f"{name}{w}_bp"] = 1e4 * (now / _ago(ser, known, w) - 1)
    # scheduled US release: minutes since it (negative = before), NaN on days without one
    rel = np.full(len(t), np.nan)
    for _, mnt in (events or []):
        d = tod - mnt
        rel = np.where(np.isnan(rel) | (np.abs(d) < np.abs(rel)), d, rel)
    out["ev_rel"] = rel
    return out


def directional(fe: pd.DataFrame, i: int, d: int) -> dict:
    """The row-i features signed for a trade in direction d (+1 long / -1 short): positive = 'with the trade'."""
    r = fe.iloc[i]
    up = d > 0
    return dict(
        tod=int(r.tod), rng30=float(r.rng30), rng60=float(r.rng60), ret15=float(d * r.ret15),
        twap_dev=float(d * r.twap_dev), gap=float(d * r.gap) if np.isfinite(r.gap) else np.nan,
        prev_range=float(r.prev_range),
        break_pd=bool(r.x_up_pdh15 if up else r.x_dn_pdl15),
        beyond_pd=bool(r.above_pdh if up else r.below_pdl),
        break_or=bool(r.x_up_or15 if up else r.x_dn_or15),
        break_round=bool(r.x_up_round15 if up else r.x_dn_round15),
        vol_ratio=float(r.vol_ratio),
        mcx5_bp=float(d * r.mcx5_bp), mcx15_bp=float(d * r.mcx15_bp),
        xau5_bp=float(d * r.xau5_bp), xau15_bp=float(d * r.xau15_bp),
        xau_lead15_bp=float(d * (r.xau15_bp - r.mcx15_bp)),
        ag15_bp=float(d * r.ag15_bp),
        at_event=bool(np.isfinite(r.ev_rel) and -15 <= r.ev_rel <= 30),
        new_day_ext=bool((r.F >= r.day_hi) if up else (r.F <= r.day_lo)),
    )
