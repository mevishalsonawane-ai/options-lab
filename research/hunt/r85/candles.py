"""R85 candles: MCX minutes -> candles with every measure of PREREG section 3 (19 Oct 2026).

Source: R53's `Market` (research/hunt/r53/mcx/data.py): the near future `F` per minute (median of the lake option rows'
`spot`, CLOSE-only) and the printed option legs. A candle of `tf` minutes is anchored at the 09:00 open:
  open  = the last minute close before the candle on the same day (the day's first close for the day's first candle),
  high / low = max / min of that open and the candle's minute closes, close = its last minute close.
So wicks miss intra-minute extremes (stated with every wick number). A candle needs >= half its minutes.

Every column is known at the candle's close (no look-ahead); the forward outcome columns (`fwd_*`) are the only ones
that look ahead and are used only as outcomes. `tests/test_candles.py` checks this by truncating the data.
"""
from __future__ import annotations

from datetime import date

import numpy as np
import pandas as pd

OPEN_M = 9 * 60
HORIZONS = (1, 3, 5, 10)
BUCKETS = ("MORNING", "MIDDAY", "LULL", "PREUS", "USOPEN", "LATE")
# mini-lot units per point of the series' quote (GOLD/GOLDM quote per 10 g, GOLDM lot 100 g; SILVERM 5 kg; CRUDEOILM
# 10 bbl; NATGASMINI 250 mmBtu)
MINI_UNITS = {"GOLD": 10.0, "GOLDM": 10.0, "SILVER": 5.0, "SILVERM": 5.0, "CRUDEOIL": 10.0, "NATURALGAS": 250.0,
              "CRUDEOILM": 10.0, "NATGASMINI": 250.0}

# US data days (R82 intraday.py lists; FOMC / CPI / NFP)
FOMC = """2025-01-29 2025-03-19 2025-05-07 2025-06-18 2025-07-30 2025-09-17 2025-10-29 2025-12-10 2026-01-28 2026-03-18
2026-04-29 2026-06-17 2026-07-29 2026-09-16 2026-10-28"""
CPI = """2025-01-15 2025-02-12 2025-03-12 2025-04-10 2025-05-13 2025-06-11 2025-07-15 2025-08-12 2025-09-11 2025-10-24
2025-12-18 2026-01-13 2026-02-13 2026-03-11 2026-04-10 2026-05-12 2026-06-10 2026-07-14 2026-08-12 2026-09-11 2026-10-14"""
NFP = """2025-01-10 2025-02-07 2025-03-07 2025-04-04 2025-05-02 2025-06-06 2025-07-03 2025-08-01 2025-09-05 2025-11-20
2025-12-16 2026-01-09 2026-02-11 2026-03-06 2026-04-03 2026-05-08 2026-06-05 2026-07-02 2026-08-07 2026-09-04 2026-10-02"""
US_DATA_DAYS = {pd.Timestamp(x).date() for x in (FOMC + " " + CPI + " " + NFP).split()}


def us_open_ist(d) -> int:
    """US cash open (09:30 New York) as IST minute of day: 19:00 in US summer time, 20:00 in US winter time."""
    from research.hunt.r53.mcx.data import us_dst
    return 19 * 60 if us_dst(d) else 20 * 60


def bucket_of(tod: int, d) -> str:
    """Session bucket of a candle starting at minute `tod` (IST) on day d (PREREG section 3)."""
    uo = us_open_ist(d)
    if tod < 11 * 60:
        return "MORNING"
    if tod < 14 * 60:
        return "MIDDAY"
    if tod < 17 * 60:
        return "LULL"
    if tod < uo:
        return "PREUS"
    if tod < uo + 150:
        return "USOPEN"
    return "LATE"


def event_days(sym: str, start: date, end: date) -> set:
    """Event days relevant to an instrument: US CPI/NFP/FOMC for all; EIA Wednesday for crude; NG storage Thursday."""
    out = {d for d in US_DATA_DAYS if start <= d <= end}
    from research.hunt.r83 import events as E
    if sym.startswith("CRUDE"):
        out |= set(E.eia_events(start, end))
    if sym.startswith("NATURALGAS") or sym.startswith("NATGAS"):
        out |= set(E.storage_events(start, end))
    return out


# ------------------------------------------------------------------------------------------------ minutes
def minutes_from_market(m) -> pd.DataFrame:
    """One row per grid minute: ts, day (date), tod, F, seg (contract segment), act (printed option volume of the
    ATM-3..+3 legs that minute), exp_day (bool), gi (the Market grid index, for trading a candle signal)."""
    act = np.zeros(len(m.ts))
    for ii, V in m.legs.values():
        np.add.at(act, ii, V[:, 4])
    t = pd.DatetimeIndex(m.ts)
    return pd.DataFrame({"ts": t, "day": t.date, "tod": m.tod.astype(int), "F": m.F.astype(float),
                         "seg": m.seg.astype(int), "act": act, "exp_day": m.is_exp, "gi": np.arange(len(m.ts))})


def session_minutes(mn: pd.DataFrame) -> pd.DataFrame:
    """Keep minutes 09:00 <= tod < session close (23:30 / 23:55 by US time), Monday-Friday, F > 0."""
    from research.hunt.r53.mcx.data import us_dst
    days = pd.Series(mn.day.unique())
    close = {d: (23 * 60 + 30 if us_dst(d) else 23 * 60 + 55) for d in days}
    cl = mn.day.map(close).values
    keep = (mn.tod.values >= OPEN_M) & (mn.tod.values < cl) & (mn.F.values > 0) & \
           (pd.DatetimeIndex(mn.ts).dayofweek < 5)
    return mn[keep].reset_index(drop=True)


# ------------------------------------------------------------------------------------------------ candles
def build_candles(mn: pd.DataFrame, tf: int) -> pd.DataFrame:
    """Close-only candles of tf minutes from session minutes (see module doc). Columns: day, start (tod of the candle
    start), o, h, l, c, n (minutes), act, seg, exp_day, i_last (row of mn of the candle's last minute)."""
    mn = mn.reset_index(drop=True)
    b = ((mn.tod.values - OPEN_M) // tf).astype(int)
    key = pd.DataFrame({"day": mn.day.values, "b": b})
    grp = key.groupby(["day", "b"], sort=True).indices
    rows = []
    F, dayv = mn.F.values, mn.day.values
    for (d, bb), ix in grp.items():
        ix = np.sort(ix)
        if len(ix) < (tf + 1) // 2:
            continue
        i0 = ix[0]
        o = F[i0 - 1] if i0 > 0 and dayv[i0 - 1] == d else F[i0]
        cs = F[ix]
        rows.append((d, OPEN_M + bb * tf, o, max(o, cs.max()), min(o, cs.min()), cs[-1], len(ix),
                     mn.act.values[ix].sum(), mn.seg.values[ix[-1]], bool(mn.exp_day.values[ix[-1]]), int(ix[-1])))
    cd = pd.DataFrame(rows, columns=["day", "start", "o", "h", "l", "c", "n", "act", "seg", "exp_day", "i_last"])
    if "gi" in mn:
        cd["gi"] = mn.gi.values[cd.i_last.values] if len(cd) else []
    return cd


def _ema(x: np.ndarray, span: int) -> np.ndarray:
    return pd.Series(x).ewm(span=span, adjust=False).mean().values


def add_features(cd: pd.DataFrame, mn: pd.DataFrame | None, sym: str, exp_days=None, events: set | None = None,
                 tf: int = 5) -> pd.DataFrame:
    """Every PREREG section 3 measure, known at each candle's close, plus forward outcomes fwd_s{h}, fwd_u{h},
    fwd_rng{h} (same day only). `mn` (session minutes) gives the session mean of closes so far; None skips it."""
    cd = cd.reset_index(drop=True).copy()
    o, h, l, c = (cd[k].values.astype(float) for k in "ohlc")
    rng = h - l
    body = c - o
    with np.errstate(divide="ignore", invalid="ignore"):
        cd["body_pct"] = np.where(rng > 0, np.abs(body) / rng, 0.0)
        cd["uw_pct"] = np.where(rng > 0, (h - np.maximum(o, c)) / rng, 0.0)
        cd["lw_pct"] = np.where(rng > 0, (np.minimum(o, c) - l) / rng, 0.0)
        cd["cl_loc"] = np.where(rng > 0, (c - l) / rng, 0.5)
    col = np.sign(body)
    col[(rng <= 0) | (np.abs(body) <= 0.1 * rng)] = 0
    cd["colour"] = col.astype(int)
    day = cd.day.values
    same_prev = np.r_[False, day[1:] == day[:-1]]
    pc = np.r_[np.nan, c[:-1]]
    tr = np.where(same_prev, np.maximum.reduce([rng, np.abs(h - pc), np.abs(l - pc)]), rng)
    atr = pd.Series(tr).rolling(14, min_periods=5).mean().values
    atr = np.where(atr > 0, atr, np.nan)          # a flat stretch has no ATR: its candles are not scored
    cd["atr"] = atr
    with np.errstate(divide="ignore", invalid="ignore"):
        cd["rng_atr"] = rng / atr
        cd["body_atr"] = np.abs(body) / atr
    cd["vol_reg"] = atr / pd.Series(atr).rolling(100, min_periods=30).median().values
    ema = _ema(c, 20)
    slope = np.r_[np.nan, np.diff(ema)]
    cd["trend"] = np.where((c > ema) & (slope > 0), 1, np.where((c < ema) & (slope < 0), -1, 0))
    # day running high / low and previous-day levels (only when the contract did not change)
    g = pd.Series(h).groupby(day)
    cd["day_hi"] = g.cummax().values
    cd["day_lo"] = pd.Series(l).groupby(day).cummin().values
    prev_hi = np.r_[np.nan, cd.day_hi.values[:-1]]
    prev_lo = np.r_[np.nan, cd.day_lo.values[:-1]]
    first = ~same_prev
    days = pd.unique(day)
    dhl = cd.groupby("day").agg(H=("h", "max"), L=("l", "min"), C=("c", "last"), S=("seg", "first"),
                                SL=("seg", "last"))
    dhl = dhl.reindex(days)
    roll = (dhl.S != dhl.SL.shift(1)).to_numpy(copy=True)      # contract changed since the previous day's close
    roll[0] = True
    pdh, pdl, pdc = (pd.Series(np.where(roll, np.nan, x.shift(1).values.astype(float)), index=days)
                     for x in (dhl.H, dhl.L, dhl.C))
    m_pdh, m_pdl = cd.day.map(pdh).values.astype(float), cd.day.map(pdl).values.astype(float)
    cd["roll_day"] = cd.day.map(pd.Series(roll, index=days)).values.astype(bool)
    cd["broke_pdh"] = (h > m_pdh) & np.where(first, True, prev_hi <= m_pdh)
    cd["broke_pdl"] = (l < m_pdl) & np.where(first, True, prev_lo >= m_pdl)
    with np.errstate(invalid="ignore"):
        cd["near_hi"] = (cd.day_hi.values - c) <= 0.25 * atr
        cd["near_lo"] = (c - cd.day_lo.values) <= 0.25 * atr
    # gap (first candle of the day only; NaN on roll days)
    gap = np.full(len(cd), np.nan)
    m_pdc = cd.day.map(pdc).values.astype(float)
    prev_atr = np.r_[np.nan, atr[:-1]]
    gap[first] = (o[first] - m_pdc[first]) / prev_atr[first]
    cd["gap_atr"] = gap
    # session mean of minute closes so far (design has no volume, so no VWAP)
    if mn is not None:
        cm = mn.groupby("day").F.transform(lambda x: x.expanding().mean()).values
        cd["smean_dist"] = (c - cm[cd.i_last.values]) / atr
    else:
        cd["smean_dist"] = np.nan
    # option activity vs the median of the same candle slot over the previous 20 days
    slot = cd.start.values
    act = pd.Series(cd.act.values.astype(float))
    med = act.groupby(slot).transform(lambda x: x.shift(1).rolling(20, min_periods=10).median())
    with np.errstate(divide="ignore", invalid="ignore"):
        cd["act_ratio"] = np.where(med.values > 0, act.values / med.values, np.nan)
    # calendar
    cd["bucket"] = [bucket_of(int(t), d) for t, d in zip(cd.start.values, day)]
    cd["dow"] = pd.DatetimeIndex(pd.to_datetime(cd.day)).dayofweek
    if exp_days is not None and len(exp_days):
        # weekdays from the candle's day to the next option expiry (a calendar count, known in advance; MCX
        # holidays are not removed)
        ed = np.sort(np.array([np.datetime64(pd.Timestamp(x).date(), "D") for x in exp_days]))
        dd = np.array([np.datetime64(d, "D") for d in days])
        k = np.searchsorted(ed, dd, side="left")
        dte = np.full(len(days), np.nan)
        ok = k < len(ed)
        dte[ok] = np.busday_count(dd[ok], ed[k[ok]])
        cd["dte"] = cd.day.map(dict(zip(days, dte))).values.astype(float)
    else:
        cd["dte"] = np.nan
    cd["exp_week"] = cd.dte.values <= 4
    ev = events or set()
    cd["event_day"] = np.array([d in ev for d in day])
    add_shapes(cd)
    add_forward(cd)
    return cd


def add_shapes(cd: pd.DataFrame) -> None:
    """Single-candle and 2-3 candle shapes (PREREG section 3), in place."""
    o, h, l, c = (cd[k].values.astype(float) for k in "ohlc")
    col = cd.colour.values
    rng = h - l
    bp, uw, lw = cd.body_pct.values, cd.uw_pct.values, cd.lw_pct.values
    ra = np.nan_to_num(cd.rng_atr.values, nan=0.0)
    ba = np.nan_to_num(cd.body_atr.values, nan=0.0)
    body = np.abs(c - o)
    cd["s_doji"] = col == 0
    cd["s_marubozu"] = (bp >= 0.8) & (ra >= 0.5) & (col != 0)
    cd["s_bigbody"] = (ba >= 1.0) & (col != 0)
    cd["s_small"] = (ra < 0.5) & (col != 0)
    cd["s_hammer"] = (lw >= 0.6) & (lw * rng >= 2 * body) & (uw <= 0.15) & (ra >= 0.5)
    cd["s_star"] = (uw >= 0.6) & (uw * rng >= 2 * body) & (lw <= 0.15) & (ra >= 0.5)
    day = cd.day.values
    sp1 = np.r_[False, day[1:] == day[:-1]]
    sp2 = sp1 & np.r_[False, sp1[:-1]]
    sp3 = sp2 & np.r_[False, sp2[:-1]]
    po, pc, ph, pl, pcol = (np.r_[np.nan, x[:-1]] for x in (o, c, h, l, col.astype(float)))
    pbody = np.abs(pc - po)
    with np.errstate(invalid="ignore"):
        cd["s_bull_engulf"] = sp1 & (pcol == -1) & (col == 1) & (o <= pc) & (c >= po) & (body > pbody)
        cd["s_bear_engulf"] = sp1 & (pcol == 1) & (col == -1) & (o >= pc) & (c <= po) & (body > pbody)
        cd["s_inside"] = sp1 & (h <= ph) & (l >= pl) & (col != 0)
        cd["s_outside"] = sp1 & (h > ph) & (l < pl) & (col != 0)
    c1, c2, c3 = (np.r_[[0] * k, col[:-k]] for k in (1, 2, 3))
    cd["s_three"] = sp2 & (col != 0) & (col == c1) & (c1 == c2)
    cd["s_flip3"] = sp3 & (col != 0) & (c1 != 0) & (c1 == c2) & (c2 == c3) & (col == -c1)


def add_forward(cd: pd.DataFrame, horizons=HORIZONS) -> None:
    """Forward outcomes, same day only: fwd_u{h} = (C[t+h] - C[t]) / ATR[t]; fwd_s{h} = colour x fwd_u{h};
    fwd_rng{h} = (max H - min L over t+1..t+h) / ATR[t]. NaN when the day ends first or ATR is unknown."""
    c, h, l = cd.c.values, cd.h.values, cd.l.values
    atr, col, day = cd.atr.values, cd.colour.values, cd.day.values
    n = len(cd)
    for k in horizons:
        u = np.full(n, np.nan)
        r = np.full(n, np.nan)
        j = np.arange(n) + k
        ok = j < n
        ok[ok] = day[j[ok]] == day[np.arange(n)[ok]]
        idx = np.nonzero(ok)[0]
        u[idx] = (c[idx + k] - c[idx]) / atr[idx]
        if len(idx):
            H = pd.Series(h).rolling(k).max().shift(-k).values
            L = pd.Series(l).rolling(k).min().shift(-k).values
            r[idx] = (H[idx] - L[idx]) / atr[idx]
        cd[f"fwd_u{k}"] = u
        cd[f"fwd_s{k}"] = np.where(col != 0, col * u, np.nan)
        cd[f"fwd_rng{k}"] = r


# ------------------------------------------------------------------------------------------------ day candles
def daily_candles(d: pd.DataFrame) -> pd.DataFrame:
    """Day candles from the continuous daily file (index = date; open/high/low/close/oi). True OHLC. Forward windows
    run across days (h trading days); gaps are close-to-open (roll days unknown: noted in the write-up)."""
    cd = pd.DataFrame({"day": [x.date() for x in d.index], "start": 0, "o": d.open.values, "h": d.high.values,
                       "l": d.low.values, "c": d.close.values, "n": 1, "act": d.volume.values.astype(float),
                       "seg": 0, "exp_day": False, "i_last": np.arange(len(d))})
    o, h, l, c = (cd[k].values.astype(float) for k in "ohlc")
    rng = h - l
    body = c - o
    with np.errstate(divide="ignore", invalid="ignore"):
        cd["body_pct"] = np.where(rng > 0, np.abs(body) / rng, 0.0)
        cd["uw_pct"] = np.where(rng > 0, (h - np.maximum(o, c)) / rng, 0.0)
        cd["lw_pct"] = np.where(rng > 0, (np.minimum(o, c) - l) / rng, 0.0)
        cd["cl_loc"] = np.where(rng > 0, (c - l) / rng, 0.5)
    col = np.sign(body)
    col[(rng <= 0) | (np.abs(body) <= 0.1 * rng)] = 0
    cd["colour"] = col.astype(int)
    pc = np.r_[np.nan, c[:-1]]
    tr = np.maximum.reduce([rng, np.abs(h - pc), np.abs(l - pc)])
    tr[0] = rng[0]
    atr = pd.Series(tr).rolling(14, min_periods=5).mean().values
    atr = np.where(atr > 0, atr, np.nan)          # a flat stretch has no ATR: its candles are not scored
    cd["atr"] = atr
    with np.errstate(divide="ignore", invalid="ignore"):
        cd["rng_atr"] = rng / atr
        cd["body_atr"] = np.abs(body) / atr
        cd["gap_atr"] = (o - pc) / np.r_[np.nan, atr[:-1]]
    cd["vol_reg"] = atr / pd.Series(atr).rolling(100, min_periods=30).median().values
    ema = _ema(c, 20)
    slope = np.r_[np.nan, np.diff(ema)]
    cd["trend"] = np.where((c > ema) & (slope > 0), 1, np.where((c < ema) & (slope < 0), -1, 0))
    ph, pl = np.r_[np.nan, h[:-1]], np.r_[np.nan, l[:-1]]
    cd["broke_pdh"] = h > ph
    cd["broke_pdl"] = l < pl
    hi20 = pd.Series(h).rolling(20).max().values
    lo20 = pd.Series(l).rolling(20).min().values
    with np.errstate(invalid="ignore"):
        cd["near_hi"] = (hi20 - c) <= 0.25 * atr
        cd["near_lo"] = (c - lo20) <= 0.25 * atr
    cd["smean_dist"] = np.nan
    cd["act_ratio"] = np.nan
    cd["bucket"] = "DAY"
    cd["dow"] = pd.DatetimeIndex(pd.to_datetime(cd.day)).dayofweek
    cd["dte"] = np.nan
    cd["exp_week"] = False
    cd["event_day"] = False
    cd["roll_day"] = False
    # forward windows cross days for day candles: use a single synthetic "day" so add_forward does not cut them
    real_day = cd.day.copy()
    cd["day"] = 0
    add_shapes(cd)
    add_forward(cd)
    cd["day"] = real_day
    return cd
