"""The few crypto-brain features the F1C rules (CC4-CC7) read, recomputed from raw minutes with the brain's own
definitions (research/brain/crypto/features), on the Binance USD-M perp 1m bars (PREREG section 4: the perp is the
signal source; the brain used Binance spot closes, NOTES.md). A value on row t uses rows <= t only (the row closes at
t + 1 min).

- ret_15m, ret_60m: price.py `_ret` - log(c_t / c_{t-k}) x 100.
- atr14_1h_pct: price.py `atr14_1h` - ATR(14) of rolling 60-minute bars ending at t, t-60, ..., t-780.
- pdh_dist_pct, pdl_dist_pct: levels_f.py `pdh` / `pdl` - close vs the previous UTC day's high / low, NaN unless that
  day has >= 90% of its 1440 closes (registry.Ctx.prev_days).
- oi_chg_60m_pct: derivs.py `oi_chg60` - OI placed on the row containing create_time + 5 min (cube.metrics_avail,
  clean.place_asof), carried forward at most 15 minutes (config.MAX_AGE metrics_5m), then (oi_t / oi_{t-60} - 1) x 100.
- dvol: price.py `dvol` - the hourly DVOL close placed on the row containing hour start + 1 h (adapters.DvolAdapter
  avail), carried forward at most 120 minutes.
"""
from __future__ import annotations

import numpy as np
import pandas as pd


def shift(a, k: int) -> np.ndarray:
    a = np.asarray(a, float)
    out = np.full(len(a), np.nan)
    if 0 < k < len(a):
        out[k:] = a[:-k]
    elif k == 0:
        out[:] = a
    return out


def roll(a, k: int, how: str, min_frac: float = 0.8) -> np.ndarray:
    r = pd.Series(np.asarray(a, float)).rolling(k, min_periods=max(1, int(k * min_frac)))
    return getattr(r, how)().to_numpy()


def ret(c, k: int) -> np.ndarray:
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.log(np.asarray(c, float) / shift(c, k)) * 100


def atr14_1h_pct(h, l, c) -> np.ndarray:
    h60, l60 = roll(h, 60, "max"), roll(l, 60, "min")
    pc = shift(c, 60)
    tr = np.fmax(h60, pc) - np.fmin(l60, pc)
    tr[~np.isfinite(pc)] = np.nan
    n = len(c)
    acc, cnt = np.zeros(n), np.zeros(n)
    for j in range(14):
        x = shift(tr, 60 * j)
        acc += np.nan_to_num(x)
        cnt += np.isfinite(x)
    with np.errstate(invalid="ignore", divide="ignore"):
        return np.where(cnt == 14, acc / 14, np.nan) / np.asarray(c, float) * 100


def prev_day(ts: pd.DatetimeIndex, a, c, how: str, complete: float = 0.9) -> np.ndarray:
    """how() of a over the previous UTC calendar day; NaN unless that day has >= complete x 1440 finite closes."""
    day = pd.DatetimeIndex(ts).tz_convert("UTC").normalize()
    s = pd.Series(np.asarray(a, float), index=day)
    per = getattr(s.groupby(level=0), how)()
    cnt = pd.Series(np.isfinite(np.asarray(c, float)), index=day).groupby(level=0).sum()
    per[cnt < complete * 1440] = np.nan
    full = per.reindex(pd.date_range(per.index.min(), per.index.max(), freq="D"))
    return full.shift(1).reindex(day).to_numpy()


def place_asof(avail, values, grid: pd.DatetimeIndex, max_age: int) -> np.ndarray:
    """Each value on the grid row containing its availability instant, carried forward at most max_age rows."""
    t = pd.DatetimeIndex(avail).floor("min")
    pos = grid.get_indexer(t)
    out = np.full(len(grid), np.nan)
    ok = pos >= 0
    order = np.argsort(np.asarray(t[ok]), kind="stable")
    out[pos[ok][order]] = np.asarray(values, float)[ok][order]
    idx = np.where(np.isfinite(out), np.arange(len(out)), -1)
    last = np.maximum.accumulate(idx) if len(out) else idx
    age = np.arange(len(out)) - last
    val = np.where(last >= 0, out[np.maximum(last, 0)], np.nan)
    val[(last < 0) | (age > max_age)] = np.nan
    return val


def build(m1: pd.DataFrame, oi: pd.DataFrame | None = None, dvol: pd.Series | None = None) -> pd.DataFrame:
    """Feature frame on the 1m grid of m1 (index t = bar start, columns o h l c)."""
    g = pd.DatetimeIndex(m1.index)
    h, l, c = (m1[k].to_numpy(float) for k in ("h", "l", "c"))
    F = pd.DataFrame(index=g)
    F["close"] = c
    F["ret_15m"] = ret(c, 15)
    F["ret_60m"] = ret(c, 60)
    F["atr14_1h_pct"] = atr14_1h_pct(h, l, c)
    with np.errstate(invalid="ignore", divide="ignore"):
        F["pdh_dist_pct"] = (c / prev_day(g, h, c, "max") - 1) * 100
        F["pdl_dist_pct"] = (c / prev_day(g, l, c, "min") - 1) * 100
    if oi is not None:
        o = place_asof(pd.DatetimeIndex(oi.ts) + pd.Timedelta(minutes=5), oi.oi.to_numpy(float), g, 15)
        with np.errstate(invalid="ignore", divide="ignore"):
            F["oi_chg_60m_pct"] = (o / shift(o, 60) - 1) * 100
    if dvol is not None:
        F["dvol"] = place_asof(pd.DatetimeIndex(dvol.index) + pd.Timedelta(hours=1), dvol.to_numpy(float), g, 120)
    return F
