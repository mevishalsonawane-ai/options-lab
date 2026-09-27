"""Inputs derived from the 1-minute bars and from the (published-in-advance) event calendar, on the hourly
decision clock (index = decision time = the close of the hour)."""
import numpy as np
import pandas as pd


def from_minutes(m1, clock):
    """Order-flow and path-shape inputs from 1-minute bars, known at each decision time."""
    c = m1["close"]
    r = np.log(c).diff()
    h = pd.DataFrame(index=clock)
    hour = m1.index.floor("1h") + pd.Timedelta(hours=1)  # the decision time at which a minute is known
    if "taker_buy_volume" in m1:
        delta = (2 * m1["taker_buy_volume"] - m1["volume"]).groupby(hour).sum()  # buys minus sells, per hour
        vol = m1["volume"].groupby(hour).sum()
        for n in (1, 4, 24, 168):
            h[f"d_cvd_{n}h"] = (delta.rolling(n).sum() / vol.rolling(n).sum()).reindex(clock)
    pv = (m1["close"] * m1["volume"]).groupby(hour).sum()
    v = m1["volume"].groupby(hour).sum()
    last = c.groupby(hour).last()
    vwap24 = pv.rolling(24).sum() / v.rolling(24).sum().replace(0, np.nan)
    h["d_vwap24_dist"] = (last / vwap24 - 1).reindex(clock)
    hr = np.log(last).diff()
    h["d_skew_24h"] = r.groupby(hour).sum().rolling(24).skew().reindex(clock)
    rs = r.rolling(1440, min_periods=600)
    h["d_min_skew_1d"] = rs.skew().groupby(hour).last().reindex(clock)
    h["d_min_kurt_1d"] = rs.kurt().groupby(hour).last().reindex(clock)
    z = hr / hr.rolling(480, min_periods=200).std().shift(1)
    big = (z.abs() > 3).astype(float)
    last_big = pd.Series(np.where(big > 0, np.arange(len(big)), np.nan), index=big.index).ffill()
    h["d_hours_since_big"] = np.log1p(pd.Series(np.arange(len(big)), index=big.index) - last_big).fillna(np.log1p(2000)).reindex(clock)
    # gap between the last price before a pause (> 2 hours without trading) and the first price after it
    gap_t = m1.index.to_series().diff() > pd.Timedelta(hours=2)
    gaps = pd.Series(np.where(gap_t, np.log(c / c.shift(1)), np.nan), index=m1.index).dropna()
    if len(gaps):
        g = gaps.copy()
        g.index = g.index.floor("1h") + pd.Timedelta(hours=1)
        g = g.groupby(level=0).last()
        h["d_last_gap"] = g.reindex(clock, method="ffill")
    return h


def calendar(clock, events=None):
    """Scheduled-event inputs. Decision days are known in advance, so using them is not looking ahead."""
    t = pd.Series(clock, index=clock)
    e = pd.DataFrame(index=clock)
    e["cal_month_sin"] = np.sin(2 * np.pi * clock.month / 12)
    e["cal_month_cos"] = np.cos(2 * np.pi * clock.month / 12)
    e["cal_quarter_end"] = ((clock.month % 3 == 0) & (clock.day >= 24)).astype(float)
    # Deribit expiries: every Friday 08:00 UTC; monthly = last Friday of the month; quarterly in Mar/Jun/Sep/Dec
    days_to_fri = (4 - clock.dayofweek) % 7
    nxt = (clock.normalize() + pd.to_timedelta(days_to_fri, unit="D") + pd.Timedelta(hours=8))
    nxt = nxt.where(nxt > clock, nxt + pd.Timedelta(days=7))
    e["cal_hours_to_expiry"] = (nxt - clock) / pd.Timedelta(hours=1)
    monthly = (nxt + pd.Timedelta(days=7)).month != nxt.month
    e["cal_monthly_expiry_week"] = monthly.astype(float)
    e["cal_quarterly_expiry_week"] = (monthly & (nxt.month % 3 == 0)).astype(float)
    if events is not None and len(events):
        for kind, hour in (("fomc", 18), ("jobs", 12)):  # decision 18:00 UTC (19:00 in winter), jobs 12:30 UTC (13:30)
            ts = pd.DatetimeIndex(pd.to_datetime(events.loc[events["event"] == kind, "date"]).dt.tz_localize("UTC") + pd.Timedelta(hours=hour)).sort_values()
            if len(ts) == 0:
                continue
            pos = ts.searchsorted(clock)
            tv = ts.as_unit("ns").asi8
            ck = clock.as_unit("ns").asi8
            hour_ns = 3600 * 10 ** 9
            to_next = np.where(pos < len(ts), (tv[np.minimum(pos, len(ts) - 1)] - ck) / hour_ns, np.nan)
            since = np.where(pos > 0, (ck - tv[np.maximum(pos - 1, 0)]) / hour_ns, np.nan)
            e[f"cal_hours_to_{kind}"] = np.log1p(to_next)
            e[f"cal_hours_since_{kind}"] = np.log1p(since)
            e[f"cal_{kind}_day"] = (clock.normalize().isin(ts.normalize())).astype(float)
    return e
