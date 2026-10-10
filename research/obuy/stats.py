"""Performance statistics, bootstrap confidence intervals and Monte Carlo of trade sequences.

Conventions: P&L in rupees after costs; 'daily' = the sum of the trades EXITED that day, on every trading day of the
sample (zeros on days without a trade) - Sharpe / Sortino are on daily P&L / CAPITAL, annualised with sqrt(248).
Max drawdown: from the running peak of the daily equity (starting at 0), in rupees.
"""
from __future__ import annotations

import numpy as np
import pandas as pd

CAPITAL = 500_000.0
DPY = 248.0


def daily_series(tr: pd.DataFrame, days=None):
    s = tr.groupby("day").net.sum() if len(tr) else pd.Series(dtype=float)
    if days is not None:
        s = s.reindex(days, fill_value=0.0)
    return s.sort_index()


def max_dd(eq):
    eq = np.asarray(eq, dtype=float)
    if not len(eq):
        return 0.0
    peak = np.maximum.accumulate(np.maximum(eq, 0))
    return float((eq - peak).min())


def summary(tr: pd.DataFrame, days=None, capital=CAPITAL):
    """One row of headline numbers. days: the sample's trading days (for per-year and Sharpe)."""
    n = len(tr)
    if n == 0:
        return dict(trades=0, net=0.0, per_year=0.0, win=np.nan, avg=np.nan, pf=np.nan, avg_r=np.nan, max_dd=0.0,
                    worst_day=0.0, worst_month=0.0, sharpe=np.nan, sortino=np.nan, years_pos=0, years=0, charges=0.0)
    net = tr.net.values.astype(float)
    d = daily_series(tr, days)
    nd = len(d) if days is not None else max(len(set(tr.day)), 1)
    years = nd / DPY
    gp, gl = net[net > 0].sum(), -net[net < 0].sum()
    r = d.values / capital
    sd = r.std(ddof=1) if len(r) > 1 else np.nan
    dn = np.sqrt(np.mean(np.minimum(r, 0) ** 2)) if len(r) else np.nan
    mon = tr.groupby(pd.to_datetime(tr.day).dt.to_period("M")).net.sum()
    yr = tr.groupby("year").net.sum()
    return dict(trades=n, net=float(net.sum()), per_year=float(net.sum() / years) if years > 0 else np.nan,
                win=float((net > 0).mean()), avg=float(net.mean()), pf=float(gp / gl) if gl > 0 else np.inf,
                avg_r=float(tr.R.mean()) if "R" in tr else np.nan, max_dd=max_dd(d.cumsum().values),
                worst_day=float(d.min()), worst_month=float(mon.min()), worst_month_at=str(mon.idxmin()),
                sharpe=float(r.mean() / sd * np.sqrt(DPY)) if sd and sd > 0 else np.nan,
                sortino=float(r.mean() / dn * np.sqrt(DPY)) if dn and dn > 0 else np.nan,
                years_pos=int((yr > 0).sum()), years=int(len(yr)), charges=float(tr.charges.sum()),
                worst_trade=float(net.min()), best_trade=float(net.max()))


def per_year(tr):
    if not len(tr):
        return pd.Series(dtype=float)
    return tr.groupby("year").net.sum()


# ------------------------------------------------------------------------------------------------ bootstrap CIs
def bootstrap_ci(tr: pd.DataFrame, days=None, B=2000, seed=1, alpha=0.05):
    """Percentile CIs: mean / trade, win rate, PF (trades resampled), Sharpe (days resampled)."""
    rng = np.random.default_rng(seed)
    net = tr.net.values.astype(float)
    n = len(net)
    if n < 5:
        return {}
    idx = rng.integers(0, n, size=(B, n))
    x = net[idx]
    mean = x.mean(axis=1)
    win = (x > 0).mean(axis=1)
    gp = np.where(x > 0, x, 0).sum(axis=1)
    gl = -np.where(x < 0, x, 0).sum(axis=1)
    pf = np.where(gl > 0, gp / np.maximum(gl, 1e-9), np.inf)
    d = daily_series(tr, days).values / CAPITAL
    jd = rng.integers(0, len(d), size=(B, len(d)))
    y = d[jd]
    sh = y.mean(axis=1) / np.maximum(y.std(axis=1, ddof=1), 1e-12) * np.sqrt(DPY)
    q = lambda a: (float(np.quantile(a, alpha / 2)), float(np.quantile(a, 1 - alpha / 2)))  # noqa: E731
    return dict(mean=q(mean), win=q(win), pf=q(pf), sharpe=q(sh), p_mean_le0=float((mean <= 0).mean()))


# ------------------------------------------------------------------------------------------------ Monte Carlo
def monte_carlo(tr: pd.DataFrame, days=None, capital=CAPITAL, B=10000, seed=2, risk_fracs=(0.01, 0.02), horizon_years=1.0):
    """Resample the trade sequence (with replacement) into 1-year paths of the strategy's trades-per-year.

    Fixed 1 lot: each trade's net per lot (net x lot / qty). Risk sizing f: lots = floor(equity x f / risk per lot),
    risk per lot = the trade's money at risk (entry - stop, or the whole premium without a stop, + charges) per lot;
    0 lots -> the trade is skipped. Drawdowns are from the running equity peak, in % of that peak.
    Returns per sizing: P(profit after 1 year), P(DD >= 20%), P(DD >= 50%), P(ruin: equity <= 0 ... reported as DD >= 50%
    being the practical ruin), median and 5th-percentile 1-year P&L."""
    if len(tr) < 10:
        return {}
    rng = np.random.default_rng(seed)
    nd = len(days) if days is not None else len(set(tr.day))
    n = max(int(round(len(tr) / (nd / DPY) * horizon_years)), 1)
    per_lot = (tr.net.values * tr.lot.values / tr.qty.values).astype(float)
    risk_lot = (tr.risk_rs.values * tr.lot.values / tr.qty.values).astype(float)
    idx = rng.integers(0, len(tr), size=(B, n))
    out = {}
    # fixed 1 lot
    pnl = per_lot[idx]
    eq = capital + np.cumsum(pnl, axis=1)
    out["1 lot"] = _mc_row(eq, capital)
    # fixed-fractional risk sizing
    for f in risk_fracs:
        eq = np.full(B, capital)
        path = np.empty((B, n))
        for t in range(n):
            j = idx[:, t]
            lots = np.floor(np.maximum(eq, 0) * f / np.maximum(risk_lot[j], 1e-9))
            eq = eq + lots * per_lot[j]
            path[:, t] = eq
        out[f"{f * 100:g}% risk"] = _mc_row(path, capital)
    out["trades_per_year"] = n
    return out


def _mc_row(eq, capital):
    peak = np.maximum.accumulate(np.concatenate([np.full((eq.shape[0], 1), capital), eq], axis=1), axis=1)[:, 1:]
    dd = 1 - eq / peak
    mdd = dd.max(axis=1)
    fin = eq[:, -1] - capital
    return dict(p_profit=float((fin > 0).mean()), p_dd20=float((mdd >= 0.2).mean()), p_dd50=float((mdd >= 0.5).mean()),
                p_ruin=float((eq.min(axis=1) <= 0).mean()), median=float(np.median(fin)), p5=float(np.quantile(fin, 0.05)),
                p95=float(np.quantile(fin, 0.95)), median_dd=float(np.median(mdd)))
