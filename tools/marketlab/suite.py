"""Building blocks for the strategy suite (daily decisions unless noted). Every signal at day t uses data
up to t's close and earns day t+1; parameters are chosen on the development period only."""
import math

import numpy as np
import pandas as pd

from .strategy import bs_straddle


def daily_ret(close):
    return np.log(close).diff()


def vol_target_trend(close, lookbacks=(20, 60, 120), target_vol=0.4, per_year=365, long_only=False, max_lev=1.0,
                     cost=0.0006, trend_filter=None):
    """Time-series momentum: average of the signs of the past `lookbacks` returns, scaled to a target
    volatility (using 30-day realised vol), capped at `max_lev`. Rebalanced daily (only if the weight
    moves by more than 10 % of a unit, to save costs)."""
    r = daily_ret(close)
    sig = sum(np.sign(np.log(close / close.shift(n))) for n in lookbacks) / len(lookbacks)
    if long_only:
        sig = sig.clip(lower=0)
    if trend_filter is not None:
        sig = sig.where(trend_filter, 0.0)
    rv = r.rolling(30).std() * math.sqrt(per_year)
    w = (sig * target_vol / rv).clip(-max_lev, max_lev).fillna(0.0)
    held, last = [], 0.0
    for x in w.values:
        if abs(x - last) > 0.1:
            last = x
        held.append(last)
    w = pd.Series(held, index=close.index)
    turn = w.diff().abs().fillna(w.abs())
    pnl = w.shift(1).fillna(0.0) * r.fillna(0.0) - turn.shift(1).fillna(0.0) * cost
    return w, pnl


def weekly_straddles(close_h, iv_daily, decide, weekday=4, hour=8, days=7, fee=0.0003, extra_cost_vol=0.5):
    """Weekly ATM straddles struck at `weekday` `hour` UTC, held `days` days. `decide(t0, prev_day)` returns
    +1 (buy), -1 (sell) or 0 (skip). Priced by Black-Scholes at the implied vol known the day before;
    buyers pay and sellers give up `extra_cost_vol` vol points (the bid/ask), plus `fee` per leg.
    P&L is a fraction of the underlying price at entry, booked on the expiry day."""
    rows = []
    for t0 in close_h.index[(close_h.index.dayofweek == weekday) & (close_h.index.hour == hour)]:
        t1 = t0 + pd.Timedelta(days=days)
        if t1 > close_h.index[-1]:
            break
        pday = t0.normalize() - pd.Timedelta(days=1)
        iv = iv_daily.get(pday, np.nan)
        if not np.isfinite(iv):
            continue
        side = decide(t0, pday)
        S0 = close_h[t0]
        ST = close_h[close_h.index <= t1].iloc[-1]
        T = days / 365
        if side > 0:
            prem = bs_straddle(S0, S0, T, (iv + extra_cost_vol) / 100)
            pnl = (abs(ST - S0) - prem) / S0 - 2 * fee
        elif side < 0:
            prem = bs_straddle(S0, S0, T, max(iv - extra_cost_vol, 1) / 100)
            pnl = (prem - abs(ST - S0)) / S0 - 2 * fee
        else:
            prem, pnl = np.nan, 0.0
        rows.append({"start": t0, "end": t1, "side": int(side), "iv": float(iv), "move": float(ST / S0 - 1),
                     "premium": float(prem / S0) if np.isfinite(prem) else None, "pnl": float(pnl)})
    return pd.DataFrame(rows)


def straddle_daily_pnl(trades, index):
    """Book each week's P&L on its expiry day, as a daily series on `index` (daily UTC)."""
    s = pd.Series(0.0, index=index)
    for _, t in trades.iterrows():
        d = t["end"].normalize()
        if d in s.index:
            s[d] += t["pnl"]
    return s


def funding_carry(funding_daily, basis_cost=0.0010, entry_rule=None):
    """Cash-and-carry: long spot + short perpetual collects funding (paid 3x a day on Binance). Delta
    neutral. `funding_daily` is the sum of the day's funding rates. Entering or leaving costs
    `basis_cost` (both legs, both sides of the spread). `entry_rule(series)` -> boolean series of days held
    (default: hold while the trailing 7-day average funding is positive)."""
    f = funding_daily.fillna(0.0)
    hold = entry_rule(f) if entry_rule is not None else (f.rolling(7).mean() > 0).shift(1).fillna(False)
    hold = hold.astype(float)
    turn = hold.diff().abs().fillna(hold)
    return hold, hold * f - turn * basis_cost / 2


def stats(pnl, per_year=365):
    x = pnl.fillna(0.0)
    if len(x) == 0:
        return {}
    eq = (1 + x).cumprod() if x.abs().max() < 0.5 else np.exp(x.cumsum())
    sd = x.std()
    return {"total_return": float(eq.iloc[-1] - 1), "sharpe": float(x.mean() / sd * math.sqrt(per_year)) if sd > 0 else 0.0,
            "max_drawdown": float((eq / eq.cummax() - 1).min()), "ann_vol": float(sd * math.sqrt(per_year)),
            "days": int(len(x)), "active_days": int((x != 0).sum())}


def split_stats(pnl, dev_start, win_start, per_year=365, warmup=130):
    """dev: the development year (alarms out-of-sample); pre: everything before the test window after a
    warm-up for the slowest indicators; window: the last 3 months; full: all."""
    pre_start = pnl.index[0] + pd.Timedelta(days=warmup)
    return {"dev": stats(pnl[(pnl.index >= dev_start) & (pnl.index < win_start)], per_year),
            "pre": stats(pnl[(pnl.index >= pre_start) & (pnl.index < win_start)], per_year),
            "window": stats(pnl[pnl.index >= win_start], per_year),
            "full": stats(pnl[pnl.index >= pnl.index[0]], per_year)}
