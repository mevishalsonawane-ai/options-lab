"""Candle anatomy (PREREG section 3): every candle's shape, measured only from its own bar and earlier bars.

Candles of `tf` minutes are anchored at 09:15 (09:15-09:19, 09:20-09:24, ...); the day's last candle may be shorter
(15:15-15:29 for 15m is a full candle). A candle needs at least half its minutes, else it is dropped. Candles never
span two sessions.

Columns added (all known at the candle's close):
  rng, body, up, lo           range, |close - open|, upper wick, lower wick (points)
  body_pct, up_pct, lo_pct    shares of the range (0 when the range is 0)
  clv                         close location in the range, 0 = at the low, 1 = at the high
  col                         +1 green (close > open), -1 red, 0 flat
  atr                         Wilder ATR(14) of the same candles THROUGH THE PREVIOUS candle (true range does not
                              cross a session: the first candle of a day uses its own high - low)
  size                        rng / atr
  shape flags                 SHAPES below (single candle), PATTERNS (two / three candles, same session only)
  cell                        the data-driven grid cell: body bin x upper bin x lower bin x colour x size bin
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from . import cfg

ATR_N = 14


def resample(m: pd.DataFrame, tf: int) -> pd.DataFrame:
    """m: minute bars (ts, o, h, l, c [, act]) of one instrument. Returns candles: ts (candle start), tl (its last
    minute present), day, slot (row of its first minute), o h l c, n (minutes present), act (sum, NaN if none)."""
    d = m.copy()
    d["day"] = d.ts.dt.normalize()
    r = (d.ts.dt.hour * 60 + d.ts.dt.minute - cfg.OPEN_MIN).to_numpy()
    d["slot"] = (r // tf) * tf
    if tf == 1:
        out = d.assign(n=1, tl=d.ts)
    else:
        g = d.groupby(["day", "slot"], sort=True)
        agg = dict(o=("o", "first"), h=("h", "max"), l=("l", "min"), c=("c", "last"), n=("c", "size"),
                   tl=("ts", "last"))
        if "act" in d:
            agg["act"] = ("act", lambda s: s.sum(min_count=1))
        out = g.agg(**agg).reset_index()
        full = np.minimum(tf, cfg.NROWS - out.slot.to_numpy())
        out = out[out.n.to_numpy() * 2 >= full]
        out["ts"] = out.day + pd.to_timedelta(cfg.OPEN_MIN + out.slot, unit="min")
    cols = ["ts", "tl", "day", "slot", "o", "h", "l", "c", "n"] + (["act"] if "act" in out else [])
    return out[cols].sort_values("ts").reset_index(drop=True)


def wilder_atr_prev(h, l, c, day) -> np.ndarray:
    """ATR(14) through the PREVIOUS candle (NaN for the first 14 candles of the series)."""
    h, l, c = (np.asarray(a, float) for a in (h, l, c))
    day = np.asarray(day)
    pc = np.r_[np.nan, c[:-1]]
    same = np.r_[False, day[1:] == day[:-1]]
    tr = np.where(same, np.maximum(h, pc) - np.minimum(l, pc), h - l)
    atr = np.full(len(h), np.nan)
    if len(h) > ATR_N:
        a = tr[:ATR_N].mean()
        atr[ATR_N - 1] = a
        for i in range(ATR_N, len(h)):
            a = a + (tr[i] - a) / ATR_N
            atr[i] = a
    return np.r_[np.nan, atr[:-1]]


def anatomy(k: pd.DataFrame) -> pd.DataFrame:
    k = k.copy()
    o, h, l, c = (k[x].to_numpy(float) for x in "ohlc")
    rng = h - l
    body = np.abs(c - o)
    up = h - np.maximum(o, c)
    lo = np.minimum(o, c) - l
    with np.errstate(invalid="ignore", divide="ignore"):
        safe = np.where(rng > 0, rng, np.nan)
        k["body_pct"] = np.where(rng > 0, body / safe, 0.0)
        k["up_pct"] = np.where(rng > 0, up / safe, 0.0)
        k["lo_pct"] = np.where(rng > 0, lo / safe, 0.0)
        k["clv"] = np.where(rng > 0, (c - l) / safe, 0.5)
    k["rng"], k["body"], k["up"], k["lo"] = rng, body, up, lo
    k["col"] = np.sign(c - o).astype(int)
    k["atr"] = wilder_atr_prev(h, l, c, k.day.to_numpy())
    with np.errstate(invalid="ignore", divide="ignore"):
        k["size"] = rng / k.atr.to_numpy()
    return k


# ------------------------------------------------------------------------------------------------ single-candle shapes
def shapes(k: pd.DataFrame) -> pd.DataFrame:
    """Shape flags (fixed in PREREG section 3). Needs anatomy() columns and `trend` (+1 up, -1 down, 0 flat; from
    context.trend_state) for the four names that depend on the trend before the candle."""
    b, u, w, s, col = (k[x].to_numpy() for x in ("body_pct", "up_pct", "lo_pct", "size", "col"))
    bodya = k.body.to_numpy() / k.atr.to_numpy()
    tr = k["trend"].to_numpy() if "trend" in k else np.zeros(len(k))
    f = {}
    doji = b <= 0.10
    f["doji"] = doji
    f["dragonfly_doji"] = doji & (w >= 0.60) & (u <= 0.15)
    f["gravestone_doji"] = doji & (u >= 0.60) & (w <= 0.15)
    f["long_legged_doji"] = doji & (u >= 0.30) & (w >= 0.30) & (s >= 1.0)
    f["spinning_top"] = (b > 0.10) & (b <= 0.35) & (u >= 0.25) & (w >= 0.25)
    f["marubozu_green"] = (b >= 0.85) & (s >= 0.8) & (col > 0)
    f["marubozu_red"] = (b >= 0.85) & (s >= 0.8) & (col < 0)
    f["big_body_green"] = (bodya >= 1.2) & (col > 0)
    f["big_body_red"] = (bodya >= 1.2) & (col < 0)
    ham = (w >= 0.60) & (b > 0.10) & (b <= 0.35) & (u <= 0.15)
    inv = (u >= 0.60) & (b > 0.10) & (b <= 0.35) & (w <= 0.15)
    f["hammer"] = ham & (tr < 0)
    f["hanging_man"] = ham & (tr > 0)
    f["hammer_shape_flat"] = ham & (tr == 0)
    f["inverted_hammer"] = inv & (tr < 0)
    f["shooting_star"] = inv & (tr > 0)
    f["inverted_shape_flat"] = inv & (tr == 0)
    f["big_upper_wick"] = (u >= 0.50) & (s >= 1.0)          # Boss's "big head"
    f["big_lower_wick"] = (w >= 0.50) & (s >= 1.0)          # Boss's "big tail"
    f["both_big_wicks"] = (u >= 0.30) & (w >= 0.30) & (s >= 1.0)
    f["small_both_wicks"] = (u <= 0.10) & (w <= 0.10) & (s >= 0.5)
    f["tiny_range"] = s < 0.5
    f["huge_range"] = s >= 2.0
    f["green"] = col > 0
    f["red"] = col < 0
    out = pd.DataFrame({x: np.asarray(v, bool) & np.isfinite(s) for x, v in f.items()}, index=k.index)
    return out


SHAPES = ("doji", "dragonfly_doji", "gravestone_doji", "long_legged_doji", "spinning_top", "marubozu_green",
          "marubozu_red", "big_body_green", "big_body_red", "hammer", "hanging_man", "hammer_shape_flat",
          "inverted_hammer", "shooting_star", "inverted_shape_flat", "big_upper_wick", "big_lower_wick",
          "both_big_wicks", "small_both_wicks", "tiny_range", "huge_range", "green", "red")


# ------------------------------------------------------------------------------------------------ multi-candle patterns
def patterns(k: pd.DataFrame) -> pd.DataFrame:
    """Two- and three-candle patterns; the earlier candles must be in the same session."""
    o, h, l, c, col, body, atr = (k[x].to_numpy(float) for x in ("o", "h", "l", "c", "col", "body", "atr"))
    day = k.day.to_numpy()

    def sh(a, n):
        return np.r_[np.full(n, np.nan), a[:-n]] if n < len(a) else np.full(len(a), np.nan)

    s1 = np.r_[False, day[1:] == day[:-1]]
    s2 = s1 & np.r_[False, False, day[2:] == day[:-2]] if len(day) > 2 else np.zeros(len(day), bool)
    o1, h1, l1, c1, col1, b1 = sh(o, 1), sh(h, 1), sh(l, 1), sh(c, 1), sh(col, 1), sh(body, 1)
    o2, c2, col2, b2 = sh(o, 2), sh(c, 2), sh(col, 2), sh(body, 2)
    bp = k.body_pct.to_numpy()
    bp1, bp2 = sh(bp, 1), sh(bp, 2)
    with np.errstate(invalid="ignore"):
        f = {
            "inside_bar": s1 & (h <= h1) & (l >= l1),
            "outside_bar": s1 & (h > h1) & (l < l1),
            "bull_engulfing": s1 & (col > 0) & (col1 < 0) & (c >= o1) & (o <= c1) & (body > b1),
            "bear_engulfing": s1 & (col < 0) & (col1 > 0) & (c <= o1) & (o >= c1) & (body > b1),
            "bull_harami": s1 & (col1 < 0) & (b1 >= 0.6 * atr) & (col > 0) & (o >= c1) & (c <= o1),
            "bear_harami": s1 & (col1 > 0) & (b1 >= 0.6 * atr) & (col < 0) & (o <= c1) & (c >= o1),
            "tweezer_bottom": s1 & (col1 < 0) & (col > 0) & (np.abs(l - l1) <= 0.05 * atr),
            "tweezer_top": s1 & (col1 > 0) & (col < 0) & (np.abs(h - h1) <= 0.05 * atr),
            "three_white": s2 & (col > 0) & (col1 > 0) & (col2 > 0) & (c > c1) & (c1 > c2) & (bp >= 0.5)
                           & (bp1 >= 0.5) & (bp2 >= 0.5),
            "three_black": s2 & (col < 0) & (col1 < 0) & (col2 < 0) & (c < c1) & (c1 < c2) & (bp >= 0.5)
                           & (bp1 >= 0.5) & (bp2 >= 0.5),
            "morning_star": s2 & (col2 < 0) & (b2 >= 0.8 * atr) & (b1 <= 0.3 * atr) & (col > 0)
                            & (c > (o2 + c2) / 2),
            "evening_star": s2 & (col2 > 0) & (b2 >= 0.8 * atr) & (b1 <= 0.3 * atr) & (col < 0)
                            & (c < (o2 + c2) / 2),
        }
    return pd.DataFrame({x: np.asarray(v, bool) & np.isfinite(atr) for x, v in f.items()}, index=k.index)


PATTERNS = ("inside_bar", "outside_bar", "bull_engulfing", "bear_engulfing", "bull_harami", "bear_harami",
            "tweezer_bottom", "tweezer_top", "three_white", "three_black", "morning_star", "evening_star")
FAMILIES = SHAPES + PATTERNS


# ------------------------------------------------------------------------------------------------ data-driven grid
def grid_cell(k: pd.DataFrame) -> pd.Series:
    """'B<body>U<upper>L<lower><G|R|F>S<size>': body bins D (<=10%) S (10-35) M (35-70) L (>70); wick bins 0 (<15%)
    1 (15-40) 2 (>40); size bins s (<0.8 ATR) n (0.8-1.5) b (>1.5). NaN size -> no cell."""
    b = np.digitize(k.body_pct.to_numpy(), [0.10 + 1e-12, 0.35 + 1e-12, 0.70 + 1e-12])
    u = np.digitize(k.up_pct.to_numpy(), [0.15, 0.40 + 1e-12])
    w = np.digitize(k.lo_pct.to_numpy(), [0.15, 0.40 + 1e-12])
    s = k["size"].to_numpy()
    sb = np.digitize(np.nan_to_num(s, nan=-1), [0.8, 1.5 + 1e-12])
    col = k.col.to_numpy()
    cc = np.where(col > 0, "G", np.where(col < 0, "R", "F"))
    out = [f"B{'DSML'[bi]}U{ui}L{wi}{ci}S{'snb'[si]}" for bi, ui, wi, ci, si in zip(b, u, w, cc, sb)]
    return pd.Series(np.where(np.isfinite(s), out, None), index=k.index)
