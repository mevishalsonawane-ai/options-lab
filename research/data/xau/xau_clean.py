"""Gold lab pack, step 1: the clean 1-minute base from Dukascopy XAUUSD BID + ASK candles (15 Oct 2026).

Input: the parquet written by fetch_duka_xau_bidask.py. One row per minute where either side traded; ts_utc is the
minute's OPEN time and every value on a row is known at its CLOSE (ts_utc + 1 minute).

What `clean()` adds (README.md lists every column):
* mid OHLC = (bid + ask) / 2 per field. The mid high / low are the average of the two sides' highs / lows, which
  can differ from the true highest mid when the bid and ask peaked at different seconds (small; stated in README).
* spread = close_ask - close_bid (USD an ounce, at the minute's close), spread_open the same at the open.
* clock: IST and UTC minute of day, UTC weekday, the trading day (rolls at 17:00 New York, the usual FX / metals
  day, so a Sunday-evening open belongs to Monday), gap_min (minutes since the previous row), weekend_open.
* session (DST-aware, by local wall clocks): asia = 00:00 UTC to the London open; london = 08:00 London to the
  New York open; overlap = 08:00 New York to 16:30 London; ny = after the London close to 17:00 New York;
  off = 17:00 New York to 00:00 UTC (the daily break and the early Pacific hours).
* US event clock from the PRE-LISTED calendar us_events.csv (official BLS / Fed schedules, dates known in advance;
  nothing is inferred from prices): minutes to the next and since the last CPI / NFP / FOMC release.
* flags (quality marks, rows are never deleted):
    flag_zero_vol_side  one side has zero volume (the fetch already dropped minutes where both are zero)
    flag_flat           mid high == mid low (no movement inside the minute)
    flag_stale          mid close unchanged for STALE_RUN or more rows in a row (flagged from the STALE_RUN-th on)
    flag_crossed        spread <= 0, or high < low on either side
    flag_out_of_hours   in the weekend closure (Fri 17:00 -> Sun 18:00 New York) or the daily 17:00-18:00 break
    flag_wide_spread    spread > WIDE x the median spread of the previous 1440 rows
    flag_bad_tick       spikes.py-style: the mid close jumps more than J away from BOTH neighbours on the same
                        side and the next close is back within J of the previous one (it reverts), or a wick
                        (high above max(open, close), low below min) longer than 2 J on either side. J = max(
                        12 x the median |1-min mid change| of the previous 60 rows, 1.00 USD). It uses the NEXT
                        row, so it is known one minute late: features use only bad_tick_lag1.
"""
from __future__ import annotations

import os

import numpy as np
import pandas as pd

HERE = os.path.dirname(os.path.abspath(__file__))
EVENTS_CSV = os.path.join(HERE, "us_events.csv")
STALE_RUN = 5
WIDE = 5.0
SPIKE_MULT, SPIKE_FLOOR = 12.0, 1.0
SESSIONS = ["asia", "london", "overlap", "ny", "off"]
NY, LDN = "America/New_York", "Europe/London"


def _local_minutes(ts_utc, tz):
    t = ts_utc.dt.tz_convert(tz)
    return (t.dt.hour * 60 + t.dt.minute).to_numpy()


def trading_day(ts_utc):
    """Trading date: the New York date of ts + 7 hours (the day rolls at 17:00 New York)."""
    return (ts_utc.dt.tz_convert(NY) + pd.Timedelta(hours=7)).dt.date


def session_label(ts_utc):
    """One of SESSIONS for each timestamp (see the module docstring)."""
    utc = (ts_utc.dt.hour * 60 + ts_utc.dt.minute).to_numpy()
    ldn, ny = _local_minutes(ts_utc, LDN), _local_minutes(ts_utc, NY)
    out = np.full(len(utc), "asia", dtype=object)          # 00:00 UTC up to the London open
    ldn_on = (ldn >= 8 * 60) & (ldn < 16 * 60 + 30)
    ny_on = (ny >= 8 * 60) & (ny < 17 * 60)
    out[ldn_on & ~ny_on] = "london"
    out[ldn_on & ny_on] = "overlap"
    out[ny_on & ~ldn_on] = "ny"                             # after the London close (NY opens after London)
    out[(ny >= 17 * 60) & (utc >= 12 * 60)] = "off"         # 17:00 New York up to 00:00 UTC
    return pd.Categorical(out, categories=SESSIONS)


def out_of_hours(ts_utc):
    """True in the weekend closure (Fri 17:00 -> Sun 18:00 New York) and the daily 17:00-18:00 New York break."""
    t = ts_utc.dt.tz_convert(NY)
    m, d = (t.dt.hour * 60 + t.dt.minute).to_numpy(), t.dt.weekday.to_numpy()
    brk = (m >= 17 * 60) & (m < 18 * 60)
    wk = ((d == 4) & (m >= 17 * 60)) | (d == 5) | ((d == 6) & (m < 18 * 60))
    return brk | wk


def load_events(path=EVENTS_CSV):
    """Pre-listed US releases as UTC timestamps (event, ts_utc), sorted."""
    e = pd.read_csv(path)
    ts = pd.to_datetime(e.date + " " + e.time_et).dt.tz_localize(NY).dt.tz_convert("UTC")
    return pd.DataFrame({"event": e.event, "ts_utc": ts}).sort_values("ts_utc").reset_index(drop=True)


def event_clock(ts_utc, events):
    """(minutes to the next release, minutes since the last release, next event type) for each bar CLOSE time."""
    t = (ts_utc + pd.Timedelta(minutes=1)).to_numpy("datetime64[ns]").astype("int64")
    ev = events.ts_utc.to_numpy("datetime64[ns]").astype("int64")
    i = np.searchsorted(ev, t, side="left")  # first event at or after the bar close
    nxt = np.where(i < len(ev), (ev[np.minimum(i, len(ev) - 1)] - t) / 6e10, np.nan)
    j = i - 1                                 # last event strictly before the bar close
    last = np.where(j >= 0, (t - ev[np.maximum(j, 0)]) / 6e10, np.nan)
    typ = np.where(i < len(ev), events.event.to_numpy()[np.minimum(i, len(ev) - 1)], None)
    return nxt, last, typ


def spike_threshold(mid_close):
    d = mid_close.diff().abs()
    base = d.shift(1).rolling(60, min_periods=10).median().fillna(SPIKE_FLOOR / SPIKE_MULT)
    return np.maximum(SPIKE_MULT * base, SPIKE_FLOOR)


def bad_ticks(df):
    """Boolean array: spikes.py-style reverting close spikes and over-long wicks (module docstring)."""
    c = df.close
    J = spike_threshold(c)
    p, n = c.shift(1), c.shift(-1)
    up = (c - p > J) & (c - n > J)
    dn = (p - c > J) & (n - c > J)
    spike = (up | dn) & ((n - p).abs() <= J)
    wick = pd.Series(False, index=df.index)
    for s in ("bid", "ask"):
        o, cl, h, lo = df[f"open_{s}"], df[f"close_{s}"], df[f"high_{s}"], df[f"low_{s}"]
        wick |= (h - np.maximum(o, cl) > 2 * J) | (np.minimum(o, cl) - lo > 2 * J)
    return (spike | wick).fillna(False).to_numpy(bool)


def clean(raw, events=None):
    """The clean base frame from the raw fetch output (see the module docstring)."""
    df = raw.sort_values("ts_utc").drop_duplicates("ts_utc").reset_index(drop=True).copy()
    ts = df.ts_utc
    for f in ("open", "high", "low", "close"):
        df[f] = (df[f"{f}_bid"] + df[f"{f}_ask"]) / 2
    df["spread"] = df.close_ask - df.close_bid
    df["spread_open"] = df.open_ask - df.open_bid
    df["volume"] = df.volume_bid.fillna(0) + df.volume_ask.fillna(0)
    df["mid_close"] = df.close
    ist = df.ts_ist if "ts_ist" in df else ts.dt.tz_convert("Asia/Kolkata")
    df["ts_ist"] = ist
    df["ist_minute"] = (ist.dt.hour * 60 + ist.dt.minute).astype("int16")
    df["utc_minute"] = (ts.dt.hour * 60 + ts.dt.minute).astype("int16")
    df["dow_utc"] = ts.dt.weekday.astype("int8")
    df["trading_day"] = pd.to_datetime(trading_day(ts))
    df["dow_trading"] = df.trading_day.dt.weekday.astype("int8")
    df["session"] = session_label(ts)
    gap = ts.diff().dt.total_seconds() / 60
    df["gap_min"] = gap.fillna(1.0).astype("float32")
    df["weekend_open"] = (gap > 24 * 60).to_numpy()
    ev = load_events() if events is None else events
    nxt, last, typ = event_clock(ts, ev)
    df["min_to_event"], df["min_since_event"] = nxt.astype("float32"), last.astype("float32")
    df["next_event"] = pd.Categorical(typ, categories=["CPI", "NFP", "FOMC"])
    vb, va = df.volume_bid.fillna(0), df.volume_ask.fillna(0)
    df["flag_zero_vol_side"] = ((vb <= 0) | (va <= 0)).to_numpy()
    df["flag_flat"] = (df.high == df.low).to_numpy()
    same = df.close.diff().eq(0)
    run = same.groupby((~same).cumsum()).cumsum()  # rows in a row with an unchanged close
    df["flag_stale"] = (run >= STALE_RUN - 1).to_numpy()
    df["flag_crossed"] = ((df.spread <= 0) | (df.high_bid < df.low_bid) | (df.high_ask < df.low_ask)).to_numpy()
    df["flag_out_of_hours"] = out_of_hours(ts)
    med = df.spread.shift(1).rolling(1440, min_periods=60).median()
    df["flag_wide_spread"] = (df.spread > WIDE * med).fillna(False).to_numpy(bool)
    df["flag_bad_tick"] = bad_ticks(df)
    return df
