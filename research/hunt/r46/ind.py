"""R46 indicators on the underlying (BTC spot): 5-minute bars from 1-minute bars, EMA, Wilder ADX, crossovers, and the
12 inputs of our MLP. Every value at bar i uses bars <= i only (test_r46 checks this by truncation)."""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import r46cfg as K


def to_5min(m1: pd.DataFrame, minutes: int = K.BAR_MIN) -> pd.DataFrame:
    """1-minute bars (index = minute start, UTC) -> bars stamped at their START. A bar is built from the minutes it
    has; a bar with no minute at all is dropped (nothing is filled). n = minutes present."""
    m = m1.dropna(subset=["open", "high", "low", "close"])
    g = m.groupby(m.index.floor(f"{minutes}min"))
    out = pd.DataFrame({"open": g.open.first(), "high": g.high.max(), "low": g.low.min(), "close": g.close.last(),
                        "volume": g.volume.sum(), "n": g.close.size()})
    return out


def ema(x: np.ndarray, n: int) -> np.ndarray:
    """Standard EMA, alpha = 2 / (n + 1), seeded with the first value (pandas ewm(adjust=False))."""
    x = np.asarray(x, float)
    out = np.empty_like(x)
    a = 2.0 / (n + 1)
    if not len(x):
        return out
    out[0] = x[0]
    for i in range(1, len(x)):
        out[i] = a * x[i] + (1 - a) * out[i - 1]
    return out


def wilder_adx(h, l, c, n: int = K.ADX_N) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """Wilder's ADX with +DI and -DI. TR, +DM, -DM from bar 1; their first smoothed value is the SUM of bars 1..n,
    then S = S - S/n + x. DX = 100 |+DI - -DI| / (+DI + -DI); ADX's first value (bar 2n-1) is the mean of the first n
    DX, then ADX = (ADX x (n-1) + DX) / n. NaN before enough bars."""
    h, l, c = (np.asarray(v, float) for v in (h, l, c))
    m = len(c)
    pdi, mdi, adx = (np.full(m, np.nan) for _ in range(3))
    up =np.r_[np.nan, h[1:] - h[:-1]]
    dn = np.r_[np.nan, l[:-1] - l[1:]]
    pdm = np.where((up > dn) & (up > 0), up, 0.0)
    mdm = np.where((dn > up) & (dn > 0), dn, 0.0)
    tr = np.r_[np.nan, np.maximum.reduce([h[1:] - l[1:], np.abs(h[1:] - c[:-1]), np.abs(l[1:] - c[:-1])])]
    if m <= n:
        return adx, pdi, mdi
    s_tr, s_p, s_m = tr[1:n + 1].sum(), pdm[1:n + 1].sum(), mdm[1:n + 1].sum()
    dx = np.full(m, np.nan)
    for i in range(n, m):
        if i > n:
            s_tr = s_tr - s_tr / n + tr[i]
            s_p = s_p - s_p / n + pdm[i]
            s_m = s_m - s_m / n + mdm[i]
        if s_tr > 0:
            pdi[i], mdi[i] = 100 * s_p / s_tr, 100 * s_m / s_tr
            tot = pdi[i] + mdi[i]
            dx[i] = 100 * abs(pdi[i] - mdi[i]) / tot if tot > 0 else 0.0
        else:
            pdi[i] = mdi[i] = dx[i] = 0.0
    first = 2 * n - 1
    if m > first:
        a = np.mean(dx[n:first + 1])
        adx[first] = a
        for i in range(first + 1, m):
            a = (a * (n - 1) + dx[i]) / n
            adx[i] = a
    return adx, pdi, mdi


def cross_up(a, b) -> np.ndarray:
    """crossover(a, b) at bar i: a[i] > b[i] and a[i-1] <= b[i-1] (False at bar 0 and where anything is NaN)."""
    a, b = np.asarray(a, float), np.asarray(b, float)
    out = np.zeros(len(a), bool)
    if len(a) > 1:
        out[1:] = (a[1:] > b[1:]) & (a[:-1] <= b[:-1])
    return out


def signals(b5: pd.DataFrame) -> pd.DataFrame:
    """Per 5-minute bar: EMAs, ADX, the call signal (EMA8 crosses above EMA21 and ADX > 15) and the mirrored put
    signal (EMA8 crosses below EMA21 and ADX > 15). Evaluated at the bar's close; the entry minute is the next bar's
    start (bar start + 5 min)."""
    c = b5.close.to_numpy(float)
    ef, es = ema(c, K.EMA_FAST), ema(c, K.EMA_SLOW)
    adx, pdi, mdi = wilder_adx(b5.high.to_numpy(float), b5.low.to_numpy(float), c)
    strong = np.nan_to_num(adx, nan=-1.0) > K.ADX_MIN
    S = pd.DataFrame(index=b5.index)
    S["ema_f"], S["ema_s"], S["adx"], S["pdi"], S["mdi"] = ef, es, adx, pdi, mdi
    S["call"] = cross_up(ef, es) & strong
    S["put"] = cross_up(es, ef) & strong
    S["entry"] = b5.index + pd.Timedelta(minutes=K.BAR_MIN)
    return S


def nn_features(b5: pd.DataFrame, S: pd.DataFrame, perp_close: pd.Series) -> pd.DataFrame:
    """The 12 pre-registered MLP inputs at each 5-minute bar's close (trailing windows only)."""
    c = b5.close
    lc = np.log(c)
    r = lc.diff()
    F = pd.DataFrame(index=b5.index)
    F["ret_1"] = r * 100
    F["ret_3"] = (lc - lc.shift(3)) * 100
    F["ret_12"] = (lc - lc.shift(12)) * 100
    F["ret_48"] = (lc - lc.shift(48)) * 100
    F["rv_12"] = r.rolling(12, min_periods=10).std() * 100
    F["rv_288"] = r.rolling(288, min_periods=240).std() * 100
    F["adx"] = S.adx
    F["di_diff"] = S.pdi - S.mdi
    F["ema_gap"] = (S.ema_f - S.ema_s) / c * 100
    v = b5.volume
    F["volz"] = np.log((v.rolling(12, min_periods=10).sum() / 12) / v.rolling(288, min_periods=240).mean())
    F["dist_hi"] = (lc - np.log(b5.high.rolling(288, min_periods=240).max())) * 100
    pc = perp_close.reindex(b5.index)
    F["basis"] = (pc / c - 1) * 1e4                     # perp premium over spot in bps (funding pressure proxy)
    return F.replace([np.inf, -np.inf], np.nan)[list(K.NN_FEATURES)]


def nn_label(m1_close: pd.Series, entries: pd.DatetimeIndex) -> np.ndarray:
    """1 if BTC spot is higher 60 minutes after the entry minute than at it (open of the entry minute = close of the
    minute before), 0 if not, NaN when either minute is missing. Read from rows after the decision only."""
    px0 = m1_close.reindex(entries - pd.Timedelta(minutes=1)).to_numpy(float)
    px1 = m1_close.reindex(entries + pd.Timedelta(minutes=K.HOLD_MIN - 1)).to_numpy(float)
    y = np.where(np.isfinite(px0) & np.isfinite(px1), (px1 > px0).astype(float), np.nan)
    return y
