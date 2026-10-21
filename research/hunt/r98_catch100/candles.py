"""R98 candle and market-clock features, causal (row i reads only rows 0..i of the session and fixed calendars).

Candles: 5-minute and 15-minute bars built from the future's minute closes (the series is close-only, so a bar's
open / high / low / close are its first / highest / lowest / last minute close). Row i uses the last COMPLETED bar
(its clock end <= the instant row i's close is known, ts + 1 min) and the two before it.
Clock anchors (IST minutes, US / UK daylight time exact): the Shanghai Gold Exchange afternoon auction (14:15 Beijing =
11:45 IST), the LBMA gold price fixes (10:30 and 15:00 London), the COMEX gold pit open (08:20 New York) and the
US data time (08:30 New York).
"""
from __future__ import annotations

from zoneinfo import ZoneInfo

import numpy as np
import pandas as pd

IST = ZoneInfo("Asia/Kolkata")


def _ist_minute(day, hh: int, mm: int, tz: str) -> int:
    d = pd.Timestamp(day)
    t = pd.Timestamp(year=d.year, month=d.month, day=d.day, hour=hh, minute=mm, tz=ZoneInfo(tz)).tz_convert(IST)
    return int((t.normalize().tz_localize(None) - d.normalize()).days * 1440 + t.hour * 60 + t.minute)


def anchors(day) -> dict:
    return dict(sge_pm=_ist_minute(day, 14, 15, "Asia/Shanghai"), lbma_am=_ist_minute(day, 10, 30, "Europe/London"),
                lbma_pm=_ist_minute(day, 15, 0, "Europe/London"), comex_open=_ist_minute(day, 8, 20, "America/New_York"),
                us_data=_ist_minute(day, 8, 30, "America/New_York"))


def bars(ts: pd.DatetimeIndex, F: np.ndarray, size: int) -> pd.DataFrame:
    """Clock bars of `size` minutes from minute closes: o h l c, n, end (the instant the bar is complete)."""
    s = pd.Series(np.asarray(F, float), index=ts)
    g = s.groupby(ts.floor(f"{size}min"))
    b = pd.DataFrame(dict(o=g.first(), h=g.max(), l=g.min(), c=g.last(), n=g.size()))
    b["end"] = b.index + pd.Timedelta(minutes=size)
    return b


def _rsi(c: np.ndarray, n: int = 14) -> np.ndarray:
    d = np.diff(c, prepend=c[0])
    up = pd.Series(np.maximum(d, 0)).ewm(alpha=1 / n, adjust=False).mean().values
    dn = pd.Series(np.maximum(-d, 0)).ewm(alpha=1 / n, adjust=False).mean().values
    with np.errstate(all="ignore"):
        return np.where(up + dn > 0, 100 * up / (up + dn), 50.0)


def bar_features(b: pd.DataFrame, tag: str) -> pd.DataFrame:
    """Per completed bar: shape of the last bar and the pattern of the last three, indicators on bar closes."""
    o, h, l, c = (b[x].values for x in "ohlc")
    rng = h - l
    body = c - o
    with np.errstate(all="ignore"):
        cl = np.where(rng > 0, (c - l) / rng, 0.5)
        uw = np.where(rng > 0, (h - np.maximum(o, c)) / rng, 0.0)
        lw = np.where(rng > 0, (np.minimum(o, c) - l) / rng, 0.0)
    tr = pd.Series(rng)
    atr = tr.ewm(alpha=1 / 14, adjust=False).mean().values
    col = np.sign(body)
    streak = np.zeros(len(b))
    for k in range(len(b)):
        streak[k] = col[k] if k == 0 or col[k] != col[k - 1] or col[k] == 0 else streak[k - 1] + col[k]
    pbody = np.r_[np.nan, body[:-1]]
    po, pc = np.r_[np.nan, o[:-1]], np.r_[np.nan, c[:-1]]
    ph, pl = np.r_[np.nan, h[:-1]], np.r_[np.nan, l[:-1]]
    engulf = np.where((body > 0) & (pbody < 0) & (c >= po) & (o <= pc), 1,
                      np.where((body < 0) & (pbody > 0) & (c <= po) & (o >= pc), -1, 0))
    inside = (h <= ph) & (l >= pl)
    outside = (h > ph) & (l < pl)
    ema = pd.Series(c).ewm(span=20, adjust=False).mean().values
    mu = pd.Series(c).rolling(20, min_periods=5).mean().values
    sd = pd.Series(c).rolling(20, min_periods=5).std().values
    with np.errstate(all="ignore"):
        pctb = np.where(sd > 0, (c - (mu - 2 * sd)) / (4 * sd), 0.5)
    mom3 = c - pd.Series(c).shift(3).values
    out = pd.DataFrame({
        f"{tag}_body": body, f"{tag}_rng": rng, f"{tag}_rng_atr": np.where(atr > 0, rng / atr, np.nan),
        f"{tag}_cl": cl, f"{tag}_uw": uw, f"{tag}_lw": lw, f"{tag}_streak": streak, f"{tag}_engulf": engulf,
        f"{tag}_inside": inside.astype(float), f"{tag}_outside": outside.astype(float),
        f"{tag}_doji": (np.abs(body) < 0.1 * np.maximum(rng, 1e-9)).astype(float),
        f"{tag}_hammer": ((lw >= 0.6) & (cl >= 0.6)).astype(float),
        f"{tag}_star": ((uw >= 0.6) & (cl <= 0.4)).astype(float),
        f"{tag}_atr": atr, f"{tag}_rsi": _rsi(c), f"{tag}_ema_dist": c - ema, f"{tag}_pctb": pctb,
        f"{tag}_mom3": mom3}, index=b.index)
    out["end"] = b["end"].values
    return out


def session_candles(ts, F, tod, sizes=(5, 15)) -> pd.DataFrame:
    """Per row of the session: the features of the last completed bar of each size (NaN before the first one)."""
    t = pd.DatetimeIndex(ts)
    known = t + pd.Timedelta(minutes=1)
    out = pd.DataFrame(index=range(len(t)))
    for size in sizes:
        bf = bar_features(bars(t, F, size), f"c{size}")
        pos = np.searchsorted(bf.end.values, known.values, side="right") - 1
        cols = [c for c in bf.columns if c != "end"]
        vals = bf[cols].values
        take = np.where(pos >= 0, pos, 0)
        block = vals[take].astype(float)
        block[pos < 0] = np.nan
        out = pd.concat([out, pd.DataFrame(block, columns=cols)], axis=1)
    return out


def clock_features(day, tod) -> pd.DataFrame:
    """Signed minutes from each clock anchor (negative = before it) and the day of the week."""
    a = anchors(day)
    tod = np.asarray(tod, int)
    out = pd.DataFrame({f"to_{k}": tod - v for k, v in a.items()})
    out["dow"] = pd.Timestamp(day).dayofweek
    return out


# features read with the trade's direction (multiplied by +1 long / -1 short); the rest are side-free
SIGNED_PREFIX = ("_body", "_streak", "_engulf", "_ema_dist", "_mom3")
CENTERED = {"_cl": 0.5, "_pctb": 0.5, "_rsi": 50.0}


def signed(df: pd.DataFrame, d: int) -> pd.DataFrame:
    """Direction-signed copy: positive = with the trade (e.g. a green last candle for a long)."""
    x = df.copy()
    for c in x.columns:
        if c.endswith(SIGNED_PREFIX):
            x[c] = d * x[c]
        for suf, mid in CENTERED.items():
            if c.endswith(suf):
                x[c] = d * (x[c] - mid)
    if d < 0:                                   # for a short, the wick "against" the trade is the lower wick
        for size in ("c5", "c15"):
            if f"{size}_uw" in x and f"{size}_lw" in x:
                x[f"{size}_uw"], x[f"{size}_lw"] = df[f"{size}_lw"].values, df[f"{size}_uw"].values
            if f"{size}_hammer" in x and f"{size}_star" in x:
                x[f"{size}_hammer"], x[f"{size}_star"] = df[f"{size}_star"].values, df[f"{size}_hammer"].values
    return x
