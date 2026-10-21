"""R93 signals (PREREG.md sections 1 and 3). Pure: numpy / pandas only, no clock, no files.

Every function reads only rows at or before the row it decides (tests/test_lookahead.py proves it by deleting and
perturbing every row after a cut).

- bars5: close-only 5-minute bars of one MCX series, anchored at 09:00 IST, session close by US time.
- channel: Donchian 20 on bars; a bar's channel is valid only if its previous 20 bars are of its own contract segment.
- breadth: same-bar M-of-group confirmation (variant I-B / D-B).
- driver_bars / driver_confirm: the international driver's own 5-minute Donchian 20 break on the same IST minutes (I-C).
- daily_series: near-month daily with causal roll rows, the back-adjusted channel and ATR14 (D-A / D-B).
"""
from __future__ import annotations

import numpy as np
import pandas as pd

from research.hunt.r93_breadth_donchian_mcx import cfg as K


# ------------------------------------------------------------------------------------------------ MCX session
def us_summer(d) -> bool:
    """US daylight-saving time on date d (second Sunday of March to first Sunday of November), McxSession rule."""
    d = pd.Timestamp(d).normalize()
    mar = pd.Timestamp(d.year, 3, 1)
    start = mar + pd.Timedelta(days=(6 - mar.weekday()) % 7 + 7)
    nov = pd.Timestamp(d.year, 11, 1)
    end = nov + pd.Timedelta(days=(6 - nov.weekday()) % 7)
    return bool(start <= d < end)


def close_min(d) -> int:
    """Minute of day (IST) at which the MCX session closes: 23:30 in US summer time, 23:55 in US winter time."""
    return 23 * 60 + (30 if us_summer(d) else 55)


# ------------------------------------------------------------------------------------------------ 5-minute bars
def bars5(ts, F, seg=None) -> pd.DataFrame:
    """Close-only 5-minute bars. ts: minute stamps (naive IST, bar start), F: minute closes, seg: contract segment per
    minute (None = one segment). Minutes outside 09:00 - close on weekdays are dropped. A bar needs >= 3 minutes.

    Columns: day (datetime64 day), start (minute of day of the bar's first minute), o, h, l, c, seg, last_ts (the bar's
    last minute stamp), n (minutes). open = the last minute close before the bar on the same day (the bar's first close
    on the day's first bar); high / low = max / min of the open and the bar's closes."""
    ts = pd.DatetimeIndex(np.asarray(ts, dtype="datetime64[ns]"))
    F = np.asarray(F, dtype=float)
    seg = np.zeros(len(F), np.int64) if seg is None else np.asarray(seg, np.int64)
    df = pd.DataFrame({"ts": ts, "F": F, "seg": seg})
    df = df[np.isfinite(df.F) & (df.F > 0)]
    df["day"] = df.ts.dt.normalize()
    df["tod"] = df.ts.dt.hour * 60 + df.ts.dt.minute
    cl = {d: close_min(d) for d in df.day.unique()}
    df = df[(df.tod >= K.OPEN_MIN) & (df.tod < df.day.map(cl)) & (df.ts.dt.dayofweek < 5)]
    df = df.sort_values("ts").drop_duplicates("ts", keep="last").reset_index(drop=True)
    if not len(df):
        return pd.DataFrame(columns=["day", "start", "o", "h", "l", "c", "seg", "last_ts", "n"])
    df["start"] = K.OPEN_MIN + (df.tod - K.OPEN_MIN) // K.BAR_MIN * K.BAR_MIN
    prev = df.groupby("day").F.shift(1)                      # the minute close before, same day
    g = df.groupby(["day", "start"], sort=True)
    out = g.agg(c=("F", "last"), hi=("F", "max"), lo=("F", "min"), first=("F", "first"), seg=("seg", "last"),
                last_ts=("ts", "last"), n=("F", "size")).reset_index()
    first_prev = prev[(g.cumcount() == 0).values].values    # rows are in (day, start) order: one per bar
    o = np.where(np.isfinite(first_prev), first_prev, out["first"].values)
    out["o"] = o
    out["h"] = np.maximum(out.hi.values, o)
    out["l"] = np.minimum(out.lo.values, o)
    out = out[out.n >= K.MIN_BAR_MINUTES].reset_index(drop=True)
    return out[["day", "start", "o", "h", "l", "c", "seg", "last_ts", "n"]]


def channel(h, l, c, seg=None, n: int = K.N_CH):
    """Donchian channel over the previous n bars, valid only when those n bars and the bar itself share one segment.
    Returns (chi, clo, side) with side +1 (close > chi), -1 (close < clo), 0 otherwise / invalid."""
    h, l, c = (np.asarray(x, dtype=float) for x in (h, l, c))
    T = len(c)
    seg = np.zeros(T, np.int64) if seg is None else np.asarray(seg, np.int64)
    chi, clo = np.full(T, np.nan), np.full(T, np.nan)
    if T > n:
        hs = pd.Series(h).rolling(n).max().shift(1).values
        ls = pd.Series(l).rolling(n).min().shift(1).values
        same = pd.Series((seg[1:] == seg[:-1]).astype(float)).rolling(n).sum().values   # (k-1, k) pairs equal
        ok = np.zeros(T, bool)
        ok[n:] = same[n - 1:] == n                                                       # pairs i-n..i all equal
        chi = np.where(ok, hs, np.nan)
        clo = np.where(ok, ls, np.nan)
    side = np.zeros(T, np.int8)
    with np.errstate(invalid="ignore"):
        side[np.nan_to_num(c > chi, nan=0).astype(bool)] = 1
        side[np.nan_to_num(c < clo, nan=0).astype(bool)] = -1
    return chi, clo, side


def with_channel(b: pd.DataFrame) -> pd.DataFrame:
    """bars5 output + chi, clo, mid, side."""
    b = b.copy()
    chi, clo, side = channel(b.h.values, b.l.values, b.c.values, b.seg.values)
    b["chi"], b["clo"], b["side"] = chi, clo, side
    b["mid"] = (chi + clo) / 2
    return b


# ------------------------------------------------------------------------------------------------ breadth (I-B / D-B)
def breadth(fam_sides: dict, groups: dict = K.GROUPS, M: dict = K.BREADTH_M) -> dict:
    """fam_sides: {family: pd.Series side (+1 / -1 / 0) indexed by a bar key (e.g. (day, start) or a date)}.
    Returns {family: pd.Series of the confirmed side (the family's own side where at least M[group] families of its
    group have the same side on the same key, else 0)}. A family with no row for a key counts as not breaking."""
    out = {}
    for grp, fams in groups.items():
        have = [f for f in fams if f in fam_sides]
        if not have:
            continue
        P = pd.concat({f: fam_sides[f] for f in have}, axis=1).fillna(0)
        up = (P > 0).sum(axis=1)
        dn = (P < 0).sum(axis=1)
        for f in have:
            s = fam_sides[f]
            u = up.reindex(s.index).values
            d = dn.reindex(s.index).values
            conf = np.where((s.values > 0) & (u >= M[grp]), 1, np.where((s.values < 0) & (d >= M[grp]), -1, 0))
            out[f] = pd.Series(conf.astype(np.int8), index=s.index)
    return out


# ------------------------------------------------------------------------------------------------ drivers (I-C)
def driver_bars(ts_ist, close) -> pd.DataFrame:
    """The driver's close-only 5-minute bars on the IST clock grid, all hours (it trades nearly round the clock), with
    its own Donchian 20 (no segments). Columns: t (bar start, naive IST), c, chi, clo, side."""
    df = pd.DataFrame({"ts": pd.DatetimeIndex(np.asarray(ts_ist, dtype="datetime64[ns]")),
                       "F": np.asarray(close, dtype=float)})
    df = df[np.isfinite(df.F) & (df.F > 0)].sort_values("ts").drop_duplicates("ts", keep="last")
    df["t"] = df.ts.dt.floor(f"{K.BAR_MIN}min")
    prev = df.F.shift(1)
    g = df.groupby("t", sort=True)
    b = g.agg(c=("F", "last"), hi=("F", "max"), lo=("F", "min"), first=("F", "first"), n=("F", "size")).reset_index()
    fp = prev[(g.cumcount() == 0).values].values
    o = np.where(np.isfinite(fp), fp, b["first"].values)
    b["h"] = np.maximum(b.hi.values, o)
    b["l"] = np.minimum(b.lo.values, o)
    b = b[b.n >= K.MIN_BAR_MINUTES].reset_index(drop=True)
    chi, clo, side = channel(b.h.values, b.l.values, b.c.values)
    b["chi"], b["clo"], b["side"] = chi, clo, side
    return b[["t", "c", "chi", "clo", "side"]]


def driver_confirm(bars: pd.DataFrame, drv: pd.DataFrame | None) -> np.ndarray:
    """For each MCX bar (day, start, side), its side where the driver's bar over the same IST minutes has the same side,
    else 0. drv None (no driver) -> all 0."""
    if drv is None or not len(bars):
        return np.zeros(len(bars), np.int8)
    t = pd.DatetimeIndex(bars.day.values) + pd.to_timedelta(bars.start.values, unit="m")
    ds = pd.Series(drv.side.values, index=pd.DatetimeIndex(drv.t.values))
    ds = ds[~ds.index.duplicated(keep="last")]
    d = ds.reindex(t).fillna(0).values
    s = bars.side.values
    return np.where((s != 0) & (d == s), s, 0).astype(np.int8)


# ------------------------------------------------------------------------------------------------ daily
def roll_rows(dates, oi, ratio: float = K.ROLL_OI, spacing_days: int = K.ROLL_SPACING_DAYS) -> np.ndarray:
    """Causal roll rows: open interest >= ratio x the previous row's, and no roll marked in the previous spacing_days
    calendar days (greedy, forward: decided at each row's close from that row and earlier ones only)."""
    dates = pd.DatetimeIndex(pd.to_datetime(np.asarray(dates)))
    oi = np.asarray(oi, dtype=float)
    out = np.zeros(len(oi), bool)
    last = None
    for i in range(1, len(oi)):
        if oi[i - 1] > 0 and np.isfinite(oi[i]) and oi[i] / oi[i - 1] >= ratio:
            if last is None or (dates[i] - last).days >= spacing_days:
                out[i] = True
                last = dates[i]
    return out


def back_adjust(o, h, l, c, roll):
    """Additive back-adjustment: on a roll row r the gap open[r] - close[r-1] is the roll gap; every earlier price is
    shifted by the later roll gaps. Comparisons among rows <= i do not depend on rolls after i (they shift all equally)."""
    o, h, l, c = (np.asarray(x, dtype=float) for x in (o, h, l, c))
    roll = np.asarray(roll, bool)
    gap = np.zeros(len(c))
    r = np.flatnonzero(roll)
    r = r[r > 0]
    gap[r] = o[r] - c[r - 1]
    later = np.concatenate([np.cumsum(gap[::-1])[::-1][1:], [0.0]])   # sum of gaps of rows > t
    return o + later, h + later, l + later, c + later


def atr_roll(h, l, c, roll, n: int = K.D_ATR_N):
    """Mean true range over n rows ending at each row; on a roll row (and the first row) the true range is high - low."""
    h, l, c = (np.asarray(x, dtype=float) for x in (h, l, c))
    pc = np.concatenate([[np.nan], c[:-1]])
    tr = np.maximum.reduce([h - l, np.abs(h - pc), np.abs(l - pc)])
    tr = np.where(np.asarray(roll, bool) | np.isnan(pc), h - l, tr)
    return pd.Series(tr).rolling(n, min_periods=n).mean().values


def daily_series(df: pd.DataFrame) -> pd.DataFrame:
    """df: one family's near-month daily rows (date, open, high, low, close, oi), any order. Returns the rows sorted with
    roll, back-adjusted channel (chi, clo, side), atr. Rows with a non-positive price are dropped first."""
    d = df[(df.close > 0) & (df.open > 0) & (df.high > 0) & (df.low > 0)].copy()
    d["date"] = pd.to_datetime(d.date).dt.normalize()
    d = d.sort_values("date").drop_duplicates("date", keep="last").reset_index(drop=True)
    roll = roll_rows(d.date.values, d.oi.values)
    ao, ah, al, ac = back_adjust(d.open.values, d.high.values, d.low.values, d.close.values, roll)
    chi, clo, side = channel(ah, al, ac)
    d["roll"] = roll
    d["chi"], d["clo"], d["side"] = chi, clo, side
    d["atr"] = atr_roll(d.high.values, d.low.values, d.close.values, roll)
    return d
