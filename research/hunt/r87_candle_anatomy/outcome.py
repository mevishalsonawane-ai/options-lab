"""Forward outcomes of every candle (PREREG section 5). These are LABELS: they read candles after t and are never used
as inputs (their names start with f_ ; tests/test_lookahead.py checks that no context column changes when the future
is cut away, and that every f_ column does).

All moves are in units of the candle's own ATR (atr = ATR14 through the previous candle), measured from its close.
  f_ret{h}     (C[t+h] - C[t]) / atr, h in HORIZONS; NaN unless t+h is in the same session (intraday view)
  f_any{h}     the same, allowed to cross sessions (the "50 candles after" path view; overnight gaps included)
  f_mfe10      max(H[t+1..t+10]) - C[t], same session only (up excursion), / atr
  f_mae10      C[t] - min(L[t+1..t+10]), same session (down excursion), / atr
  f_upfirst20  1 if C + 1 ATR trades before C - 1 ATR within the next 20 candles of the session, 0 if the reverse,
               NaN if neither, or both inside one candle (order unknown)
  f_lobrk10    1 if a later candle of the session (<= 10) trades below this candle's LOW (the lower wick level is
               broken), 0 if it held; f_hibrk10 the same for the HIGH
"""
from __future__ import annotations

import numpy as np
import pandas as pd

HORIZONS = (1, 3, 5, 10, 20, 50)
PATH_K = 50


def _shift(a: np.ndarray, h: int) -> np.ndarray:
    out = np.full(len(a), np.nan)
    if h < len(a):
        out[: len(a) - h] = a[h:]
    return out


def _window(a: np.ndarray, k: int) -> np.ndarray:
    """(n, k) matrix of a[t+1..t+k] (NaN beyond the end)."""
    p = np.r_[a.astype(float), np.full(k, np.nan)]
    return np.lib.stride_tricks.sliding_window_view(p[1:], k)[: len(a)]


def outcomes(k: pd.DataFrame) -> pd.DataFrame:
    c, h, l, atr = (k[x].to_numpy(float) for x in ("c", "h", "l", "atr"))
    dcode = pd.factorize(k.day)[0].astype(float)
    out = {}
    with np.errstate(invalid="ignore", divide="ignore"):
        for hz in HORIZONS:
            ch = _shift(c, hz)
            same = _shift(dcode, hz) == dcode
            r = (ch - c) / atr
            out[f"f_any{hz}"] = r
            out[f"f_ret{hz}"] = np.where(same, r, np.nan)
        for kk, name in ((10, "10"), (20, "20")):
            W_d = _window(dcode, kk) == dcode[:, None]
            W_h = np.where(W_d, _window(h, kk), np.nan)
            W_l = np.where(W_d, _window(l, kk), np.nan)
            has = W_d[:, 0]
            if kk == 10:
                out["f_mfe10"] = np.where(has, (np.nanmax(np.where(W_d, W_h, -np.inf), 1) - c) / atr, np.nan)
                out["f_mae10"] = np.where(has, (c - np.nanmin(np.where(W_d, W_l, np.inf), 1)) / atr, np.nan)
                out["f_lobrk10"] = np.where(has, (np.nanmin(np.where(W_d, W_l, np.inf), 1) < l).astype(float), np.nan)
                out["f_hibrk10"] = np.where(has, (np.nanmax(np.where(W_d, W_h, -np.inf), 1) > h).astype(float), np.nan)
            else:
                upk = (W_h >= (c + atr)[:, None])
                dnk = (W_l <= (c - atr)[:, None])
                big = kk + 1
                fu = np.where(upk.any(1), upk.argmax(1), big)
                fd = np.where(dnk.any(1), dnk.argmax(1), big)
                res = np.where(fu < fd, 1.0, np.where(fd < fu, 0.0, np.nan))
                out["f_upfirst20"] = np.where(np.isfinite(atr) & has, res, np.nan)
    return pd.DataFrame(out, index=k.index)


def path_matrix(c: np.ndarray, atr: np.ndarray, idx: np.ndarray, K: int = PATH_K) -> np.ndarray:
    """(len(idx), 2K+1): (C[t+j] - C[t]) / atr[t] for j = -K..K over the continuous candle series (sessions crossed;
    the before-part is context, the after-part is outcome). NaN outside the series."""
    c = np.asarray(c, float)
    pad = np.r_[np.full(K, np.nan), c, np.full(K, np.nan)]
    W = np.lib.stride_tricks.sliding_window_view(pad, 2 * K + 1)[np.asarray(idx)]
    with np.errstate(invalid="ignore", divide="ignore"):
        return (W - c[idx][:, None]) / np.asarray(atr, float)[idx][:, None]
