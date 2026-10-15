"""Gold lab pack, step 3: the aligned feature store (15 Oct 2026).

One row per clean 1-minute row, keyed by ts_utc (the minute's OPEN time). Every value on a row uses only data up to
and including that minute's CLOSE (ts_utc + 1 minute); tests/test_xau_lab.py truncates the input after each test row
and checks that every column is unchanged (the look-ahead guard). Higher-timeframe values come from the last
COMPLETED bar (bar end <= this minute's close), never from the bar still forming.

Signal source: everything here is computed on XAUUSD itself (the underlying: spot gold mid / bid / ask). Prices are
USD an ounce. "ATR units" divide by atr14_15m (the 15-minute Wilder ATR, the same R the labels use).

Columns (README.md has the full list): returns ret_{1,5,15,60,240} (log, clock minutes, as of the last row at or
before t - h), atr14_{1m,5m,15m,1h,4h,1d}, rv_{30,240} (realised volatility, sqrt of summed squared 1-min log
returns over the last 30 / 240 rows), day_pos / sess_pos (where the close sits in today's / this session's range so
far, 0 = low, 1 = high), vwap_sess and vwap_dist (session VWAP weighted by tick volume, distance in ATR units),
pdh pdl pdc and their distances (previous trading day), asia_high asia_low asia_done and distances (today's Asia
range, still forming while asia_done is 0), swing levels / trend / BOS / CHoCH for 15m and 1h (r52 / matrix_v22
definitions, see `structure`), previous session volume profile poc_prev vah_prev val_prev and distances, spread,
spread_bp, spread_z, vol_log, vol_z, clock and event columns copied from the clean base, bad_tick_lag1.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

import xau_bars

RET_H = (1, 5, 15, 60, 240)
ATR_N = 14
PIV = 3                 # fractal pivots: 3 bars each side, confirmed 3 bars later (research/hunt/r52/fam_misc.py)
VP_BIN = 0.5            # volume profile bucket, USD
VA_SHARE = 0.70         # value area share of the session's volume
Z_WIN = 1440            # rows for the spread / volume z-scores
STRUCT_TF = ("15m", "1h")
COPY = ["session", "ist_minute", "utc_minute", "dow_utc", "dow_trading", "gap_min", "weekend_open",
        "min_to_event", "min_since_event", "next_event", "flag_zero_vol_side", "flag_flat", "flag_stale",
        "flag_crossed", "flag_out_of_hours", "flag_wide_spread"]


def _ns(s):
    return s.to_numpy("datetime64[ns]").astype("int64")


def wilder_atr(h, lo, c, n=ATR_N):
    pc = c.shift(1)
    tr = pd.concat([h - lo, (h - pc).abs(), (lo - pc).abs()], axis=1).max(axis=1)
    return tr.ewm(alpha=1 / n, adjust=False).mean()


def asof_return(ts, close, h):
    """log(close_t / close at the last row with ts <= ts_t - h minutes); NaN before the data starts."""
    t = _ns(ts)
    j = np.searchsorted(t, t - h * 60_000_000_000, side="right") - 1
    c = close.to_numpy(float)
    prev = np.where(j >= 0, c[np.maximum(j, 0)], np.nan)
    return np.log(c / prev)


def last_completed(df, bars):
    """Index into `bars` (clock timeframe) of the last bar with ts_end <= the row's close; -1 when none."""
    close_t = _ns(df.ts_utc) + 60_000_000_000
    return np.searchsorted(_ns(bars.ts_end), close_t, side="right") - 1


def take(arr, idx):
    arr = np.asarray(arr, dtype=float)
    return np.where(idx >= 0, arr[np.maximum(idx, 0)], np.nan)


def structure(bars, piv=PIV):
    """Per bar, after it closes: last confirmed swing high / low, trend (+1 / -1 / 0) and the break event
    (+1 BOS up, +2 CHoCH up, -1 BOS down, -2 CHoCH down, 0 none). Mechanics of research/hunt/r52/fam_misc.py
    `matrix_v22` (and research/matrix_v22.py): bar j = i - piv is a swing high when its high is the highest of
    bars j - piv .. i (so it is confirmed piv bars later, at bar i); a close above the last UNBROKEN swing high is a
    break up (CHoCH when the trend was down, else BOS) and that level is then used up; mirror for lows."""
    H, L, C = (bars[k].to_numpy(float) for k in ("high", "low", "close"))
    n = len(H)
    out = {k: np.full(n, np.nan) for k in ("swing_hi", "swing_lo", "trend", "event")}
    sh = sl = lsh = lsl = None
    trend = 0
    for i in range(n):
        j = i - piv
        if j >= piv:
            if H[j] == H[j - piv:i + 1].max():
                sh = lsh = H[j]
            if L[j] == L[j - piv:i + 1].min():
                sl = lsl = L[j]
        ev = 0
        if sh is not None and C[i] > sh:
            ev, sh = (2 if trend < 0 else 1), None
            trend = 1
        elif sl is not None and C[i] < sl:
            ev, sl = (-2 if trend > 0 else -1), None
            trend = -1
        out["swing_hi"][i] = np.nan if lsh is None else lsh
        out["swing_lo"][i] = np.nan if lsl is None else lsl
        out["trend"][i], out["event"][i] = trend, ev
    return pd.DataFrame(out)


def value_area(close, vol, bin_=VP_BIN, share=VA_SHARE):
    """(POC, VAH, VAL) of one session: tick volume bucketed by mid close; the value area grows from the POC one
    bucket at a time towards the side with more volume until it holds `share` of the total."""
    c, v = np.asarray(close, float), np.asarray(vol, float)
    if len(c) == 0:
        return np.nan, np.nan, np.nan
    k = np.floor(c / bin_).astype(np.int64)
    lo = k.min()
    prof = np.bincount(k - lo, weights=np.where(v > 0, v, 0), minlength=k.max() - lo + 1)
    if prof.sum() <= 0:
        prof = np.bincount(k - lo, minlength=k.max() - lo + 1).astype(float)
    p = int(np.argmax(prof))
    a = b = p
    tot, need = prof[p], share * prof.sum()
    while tot < need and (a > 0 or b < len(prof) - 1):
        up = prof[b + 1] if b < len(prof) - 1 else -1
        dn = prof[a - 1] if a > 0 else -1
        if up >= dn:
            b += 1
            tot += up
        else:
            a -= 1
            tot += dn
    return (lo + p + 0.5) * bin_, (lo + b + 1) * bin_, (lo + a) * bin_


def build_features(df):
    """Feature frame aligned with the clean frame `df` (xau_clean.clean output)."""
    df = df.reset_index(drop=True)
    f = pd.DataFrame({"ts_utc": df.ts_utc})
    ts, c = df.ts_utc, df.close
    for h in RET_H:
        f[f"ret_{h}"] = asof_return(ts, c, h)
    f["atr14_1m"] = wilder_atr(df.high, df.low, c).to_numpy()
    bars = {tf: xau_bars.make_bars(df, tf) for tf in ("5m", "15m", "1h", "4h")}
    for tf, b in bars.items():
        idx = last_completed(df, b)
        f[f"atr14_{tf}"] = take(wilder_atr(b.high, b.low, b.close), idx)
        if tf in STRUCT_TF:
            s = structure(b)
            for k in s:
                f[f"{k}_{tf}"] = take(s[k], idx)
            # bars since the last break, counted in completed bars of this timeframe
            ev_idx = np.where(s.event.to_numpy() != 0, np.arange(len(s)), -1)
            last_ev = np.maximum.accumulate(ev_idx) if len(ev_idx) else ev_idx
            f[f"bars_since_break_{tf}"] = np.where((idx >= 0) & (take(last_ev, idx) >= 0),
                                                   idx - take(last_ev, idx), np.nan)
    day = xau_bars.make_bars(df, "1d")
    day_atr = wilder_atr(day.high, day.low, day.close).to_numpy()
    pos = np.searchsorted(day.trading_day.to_numpy("datetime64[ns]"), df.trading_day.to_numpy("datetime64[ns]"))
    prev = pos - 1                                      # previous trading day (complete by construction)
    f["atr14_1d"] = take(day_atr, prev)
    f["pdh"], f["pdl"], f["pdc"] = take(day.high, prev), take(day.low, prev), take(day.close, prev)
    r1 = np.log(c / c.shift(1))
    for n in (30, 240):
        f[f"rv_{n}"] = np.sqrt((r1 ** 2).rolling(n, min_periods=n // 2).sum()).to_numpy()
    td = df.trading_day
    dh, dl = df.high.groupby(td).cummax(), df.low.groupby(td).cummin()
    f["day_pos"] = ((c - dl) / (dh - dl)).where(dh > dl, 0.5).to_numpy()
    sess_id = ((df.session != df.session.shift()) | (td != td.shift())).cumsum()
    sh_, sl_ = df.high.groupby(sess_id).cummax(), df.low.groupby(sess_id).cummin()
    f["sess_pos"] = ((c - sl_) / (sh_ - sl_)).where(sh_ > sl_, 0.5).to_numpy()
    tp = (df.high + df.low + c) / 3
    vol = df.volume.clip(lower=0)
    cv, cpv = vol.groupby(sess_id).cumsum(), (tp * vol).groupby(sess_id).cumsum()
    f["vwap_sess"] = (cpv / cv).where(cv > 0, tp).to_numpy()
    asia = df.session == "asia"
    ah = df.high.where(asia).groupby(td).cummax().groupby(td).ffill()
    al = df.low.where(asia).groupby(td).cummin().groupby(td).ffill()
    f["asia_high"], f["asia_low"] = ah.to_numpy(), al.to_numpy()
    seen = asia.groupby(td).cummax()
    f["asia_done"] = (seen & ~asia).astype("int8").to_numpy()
    # previous session's volume profile (the session instance before this row's one)
    prof = {k: v for k, v in ((sid, value_area(g.close, g.volume)) for sid, g in df.groupby(sess_id, sort=True))}
    sids = sess_id.to_numpy()
    keys = np.array(sorted(prof))
    vals = np.array([prof[k] for k in keys]) if len(keys) else np.zeros((0, 3))
    pi = np.searchsorted(keys, sids) - 1
    for n_, col in enumerate(("poc_prev", "vah_prev", "val_prev")):
        f[col] = take(vals[:, n_] if len(vals) else [], pi)
    atr = f.atr14_15m.to_numpy()
    cc = c.to_numpy(float)
    for lvl in ("vwap_sess", "pdh", "pdl", "pdc", "asia_high", "asia_low", "poc_prev", "vah_prev", "val_prev",
                "swing_hi_15m", "swing_lo_15m", "swing_hi_1h", "swing_lo_1h"):
        f[f"dist_{lvl}"] = (cc - f[lvl].to_numpy()) / atr
    f["spread"] = df.spread.to_numpy()
    f["spread_bp"] = (df.spread / c * 1e4).to_numpy()
    m, s = df.spread.rolling(Z_WIN, min_periods=60).mean(), df.spread.rolling(Z_WIN, min_periods=60).std()
    f["spread_z"] = ((df.spread - m) / s.where(s > 0)).to_numpy()
    lv = np.log1p(vol)
    m, s = lv.rolling(Z_WIN, min_periods=60).mean(), lv.rolling(Z_WIN, min_periods=60).std()
    f["vol_log"], f["vol_z"] = lv.to_numpy(), ((lv - m) / s.where(s > 0)).to_numpy()
    for col in COPY:
        f[col] = df[col].to_numpy() if col not in ("session", "next_event") else df[col].values
    f["bad_tick_lag1"] = df.flag_bad_tick.shift(1, fill_value=False).to_numpy(bool)
    return f
