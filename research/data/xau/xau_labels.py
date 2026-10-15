"""Gold lab pack, step 4: labels for strategy search (15 Oct 2026). Kept in their OWN file (xauusd_1m_labels.parquet)
so they can never leak into the feature store; join on ts_utc only to score a rule or train a model.

Every label looks FORWARD from the close of the minute ts_utc (the moment the features are known):
* fwd_ret_{5,15,60,240}: log(mid close at the last row at or before t + h minutes / mid close at t), where t is the
  minute's open time, so the horizon ends h minutes after it; NaN when no row lies in (t, t + h].
  fwd_cover_{h} = rows in (t, t + h] / h (1.0 = no missing minute; low values = the horizon crosses a closure).
* Entries: a LONG buys at the next row's ASK open, a SHORT sells at the next row's BID open, only when that row starts
  within ENTRY_MAX_WAIT minutes (else NaN). A long exits on the BID and a short on the ASK, so the spread is paid.
  Broker fees / commission are NOT in these labels; subtract them per trade.
* {long,short}_mfe_{60,240} / _mae_{60,240}: maximum favourable / adverse excursion (USD an ounce, >= 0 for a normal
  bar) over the minutes t+1 .. t+H, measured on the exit side (long: bid high / low; short: ask low / high).
* {long,short}_hit_{60,240}: +1 when a +1R target is touched before a -1R stop within H minutes, -1 when the stop is
  touched first, 0 when neither; a minute that touches both counts as the STOP (conservative). R = atr14_15m at t
  (the feature store's 15-minute ATR, known at t). {long,short}_tth_{H}: minutes from t to the first touch.
  Target and stop are measured on the exit side from the entry price, so they are net of the spread.
"""
from __future__ import annotations

import warnings

import numpy as np
import pandas as pd
from numpy.lib.stride_tricks import sliding_window_view

FWD_H = (5, 15, 60, 240)
EXC_H = (60, 240)
ENTRY_MAX_WAIT = 5
CHUNK = 20_000


def _minutes(ts):
    t = ts.to_numpy("datetime64[ns]").astype("int64") // 60_000_000_000
    return t - t[0]


def _grid(m, vals, pad):
    g = np.full(int(m[-1]) + 1 + pad, np.nan)
    g[m] = vals
    return g


def _first(mask):
    """Index of the first True per row, or a large number when none."""
    any_ = mask.any(axis=1)
    return np.where(any_, mask.argmax(axis=1), 1 << 30)


def build_labels(df, atr15):
    """Label frame aligned with the clean frame df (rows and order); atr15 = feature store atr14_15m."""
    df = df.reset_index(drop=True)
    n = len(df)
    out = pd.DataFrame({"ts_utc": df.ts_utc})
    if n == 0:
        return out
    m = _minutes(df.ts_utc)
    c = df.close.to_numpy(float)
    rows = np.arange(n)
    for h in FWD_H:
        j = np.searchsorted(m, m + h, side="right") - 1
        ok = j > rows
        out[f"fwd_ret_{h}"] = np.where(ok, np.log(c[j] / c), np.nan).astype("float32")
        out[f"fwd_cover_{h}"] = ((j - rows) / h).astype("float32")
    nxt = np.minimum(rows + 1, n - 1)
    has = (rows + 1 < n) & (m[nxt] - m <= ENTRY_MAX_WAIT)
    ea = np.where(has, df.open_ask.to_numpy(float)[nxt], np.nan)
    eb = np.where(has, df.open_bid.to_numpy(float)[nxt], np.nan)
    R = np.asarray(atr15, float)
    Hmax = max(EXC_H)
    grids = {k: _grid(m, df[k].to_numpy(float), Hmax + 1) for k in ("high_bid", "low_bid", "high_ask", "low_ask")}
    res = {}
    for H in EXC_H:
        for k in ("long_mfe", "long_mae", "short_mfe", "short_mae", "long_hit", "short_hit", "long_tth", "short_tth"):
            res[f"{k}_{H}"] = np.full(n, np.nan, dtype="float32")
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)  # all-NaN windows give NaN, as intended
        for H in EXC_H:
            views = {k: sliding_window_view(g, H) for k, g in grids.items()}
            for s in range(0, n, CHUNK):
                sl = slice(s, min(s + CHUNK, n))
                start = m[sl] + 1
                hb, lb = views["high_bid"][start], views["low_bid"][start]
                ha, la = views["high_ask"][start], views["low_ask"][start]
                a, b, r = ea[sl], eb[sl], R[sl]
                res[f"long_mfe_{H}"][sl] = np.nanmax(hb, axis=1) - a
                res[f"long_mae_{H}"][sl] = a - np.nanmin(lb, axis=1)
                res[f"short_mfe_{H}"][sl] = b - np.nanmin(la, axis=1)
                res[f"short_mae_{H}"][sl] = np.nanmax(ha, axis=1) - b
                for side, tgt, stp in (("long", hb >= (a + r)[:, None], lb <= (a - r)[:, None]),
                                       ("short", la <= (b - r)[:, None], ha >= (b + r)[:, None])):
                    ft, fs = _first(tgt), _first(stp)
                    hit = np.where(ft < fs, 1.0, np.where(fs < (1 << 30), -1.0, 0.0))
                    valid = ~np.isnan(a if side == "long" else b) & ~np.isnan(r) & (r > 0)
                    res[f"{side}_hit_{H}"][sl] = np.where(valid, hit, np.nan)
                    first = np.minimum(ft, fs)
                    res[f"{side}_tth_{H}"][sl] = np.where(valid & (first < (1 << 30)), first + 1, np.nan)
    for k, v in res.items():
        out[k] = v
    out["R_atr15"] = R.astype("float32")
    return out
