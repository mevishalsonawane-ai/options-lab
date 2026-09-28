"""Backtest helpers for the study-based strategies: walk-forward alarm predictions, position -> P&L,
trade lists, daily reports and summaries. Positions are decided at a bar's close and earn the NEXT
bar's return, so nothing trades on information it could not have had."""
import math

import numpy as np
import pandas as pd

from .nn import Ensemble
from .patterns import detector_features


def detector_predictions(h, per_year, thr_sigma=2.0, horizon=24, step=720, n_models=3, extra_daily=None, start_frac=0.6):
    """Hourly walk-forward probabilities of a big rise / big drop within `horizon` hours (same networks as
    the pattern study). Each block is predicted by networks trained only on data ending `horizon` hours
    before the block starts."""
    c = h["close"]
    r = np.log(c).diff()
    sd24 = r.rolling(480, min_periods=240).std() * np.sqrt(horizon)
    fwd = np.log(c.shift(-horizon) / c)
    f = detector_features(h, per_year)
    if extra_daily is not None:
        for k, s in extra_daily.items():
            f[k] = s.shift(1).reindex(h.index.floor("D")).values
    f = f.replace([np.inf, -np.inf], np.nan)
    up = (fwd > thr_sigma * sd24).astype(float).where(fwd.notna() & sd24.notna())
    dn = (fwd < -thr_sigma * sd24).astype(float).where(fwd.notna() & sd24.notna())
    ok = f.notna().mean(axis=1) > 0.9
    X = f.values.astype(float)
    idx = np.where(ok.values)[0]
    n = len(idx)
    s0 = int(n * start_frac)
    out = pd.DataFrame(index=h.index, columns=["p_up", "p_dn"], dtype=float)
    for side, y_all in (("p_up", up.values), ("p_dn", dn.values)):
        for i in range(s0, n, step):
            tr = idx[:max(i - horizon, 1)]
            tr = tr[~np.isnan(y_all[tr])]
            m = Ensemble(n=n_models, task="clf", hidden=(32, 16), dropout=0.2, l2=1e-3, batch=256).fit(X[tr], y_all[tr])
            te = idx[i:i + step]
            out.iloc[te, out.columns.get_loc(side)] = m.predict(X[te])
    out["big_up"], out["big_dn"] = up, dn
    return out.dropna(subset=["p_up"])


def pnl_from_positions(pos, ret, cost):
    """pos[t] is held over bar t+1. Returns per-bar net log-return series aligned to the bar it is earned on."""
    pos = pos.fillna(0.0)
    held = pos.shift(1).fillna(0.0)
    turn = (pos - pos.shift(1).fillna(0.0)).abs()
    gross = held * ret.fillna(0.0)
    fees = turn.shift(1).fillna(0.0) * cost  # the change made at the close of t-1 is paid in bar t
    return gross - fees, fees


def hold_signal(sig, bars):
    """1 while a signal fired within the last `bars` bars (including now)."""
    return sig.astype(float).rolling(bars, min_periods=1).max().fillna(0.0)


def trades_from_positions(pos, close, cost, label):
    """Round trips from a position series (sizes of -1/0/+1)."""
    pos = pos.fillna(0.0)
    out, cur, t0, p0 = [], 0.0, None, None
    for t, p in pos.items():
        if p != cur:
            if cur != 0:
                ret = cur * math.log(close[t] / p0) - 2 * cost * abs(cur)
                out.append({"strategy": label, "side": "long" if cur > 0 else "short", "entry_time": str(t0),
                            "entry": round(float(p0), 2), "exit_time": str(t), "exit": round(float(close[t]), 2),
                            "hours": round((t - t0).total_seconds() / 3600, 1), "ret": round(ret, 5)})
            cur, t0, p0 = p, t, close[t]
    if cur != 0:
        t = pos.index[-1]
        ret = cur * math.log(close[t] / p0) - cost * abs(cur)
        out.append({"strategy": label, "side": "long" if cur > 0 else "short", "entry_time": str(t0), "entry": round(float(p0), 2),
                    "exit_time": None, "exit": round(float(close[t]), 2), "hours": round((t - t0).total_seconds() / 3600, 1),
                    "ret": round(ret, 5), "open": True})
    return out


def summary(logret, per_year, trades=None, bench=None):
    lr = logret.fillna(0.0)
    eq = np.exp(lr.cumsum())
    n = len(lr)
    sd = lr.std()
    days = (lr.index[-1] - lr.index[0]).days or 1
    daily = lr.resample("D").sum()
    s = {
        "start": str(lr.index[0]), "end": str(lr.index[-1]),
        "total_return": float(eq.iloc[-1] - 1), "annualised": float(eq.iloc[-1] ** (365 / days) - 1),
        "ann_vol": float(sd * math.sqrt(per_year)), "sharpe": float(lr.mean() / sd * math.sqrt(per_year)) if sd > 0 else 0.0,
        "max_drawdown": float((eq / eq.cummax() - 1).min()),
        "best_day": float(daily.max()), "worst_day": float(daily.min()),
        "up_days": int((daily > 0).sum()), "down_days": int((daily < 0).sum()), "flat_days": int((daily == 0).sum()),
    }
    if trades is not None:
        closed = [t for t in trades if not t.get("open")]
        rets = np.array([t["ret"] for t in closed]) if closed else np.array([])
        s.update({"trades": len(trades), "win_rate": float((rets > 0).mean()) if len(rets) else None,
                  "avg_trade": float(rets.mean()) if len(rets) else None,
                  "avg_win": float(rets[rets > 0].mean()) if (rets > 0).any() else None,
                  "avg_loss": float(rets[rets <= 0].mean()) if (rets <= 0).any() else None,
                  "best_trade": float(rets.max()) if len(rets) else None, "worst_trade": float(rets.min()) if len(rets) else None,
                  "profit_factor": float(rets[rets > 0].sum() / -rets[rets < 0].sum()) if (rets < 0).any() else None})
    if bench is not None:
        b = bench.fillna(0.0)
        s["benchmark_return"] = float(math.exp(b.sum()) - 1)
        s["benchmark_max_drawdown"] = float((np.exp(b.cumsum()) / np.exp(b.cumsum()).cummax() - 1).min())
    return s


def monthly(logret, bench):
    m = logret.resample("ME").sum()
    b = bench.resample("ME").sum()
    return [{"month": t.strftime("%Y-%m"), "ret": float(math.exp(v) - 1), "benchmark": float(math.exp(b.get(t, 0.0)) - 1)}
            for t, v in m.items()]


def bs_straddle(S, K, T, sigma):
    """Black-Scholes value of a call + put (zero rates), T in years, sigma as a decimal."""
    if T <= 0 or sigma <= 0:
        return abs(S - K)
    from math import erf, log, sqrt
    N = lambda x: 0.5 * (1 + erf(x / sqrt(2)))  # noqa: E731
    d1 = (log(S / K) + 0.5 * sigma * sigma * T) / (sigma * sqrt(T))
    d2 = d1 - sigma * sqrt(T)
    call = S * N(d1) - K * N(d2)
    put = K * N(-d2) - S * N(-d1)
    return call + put


def build_report(close_h, comps, positions, trades, alarms, capital, start, last30, per_year, notes_by_day=None):
    """Account-level report. comps: {name: per-bar simple return on account equity}, each earned on the
    bar it is indexed by. positions: {name: position series (for the daily log)}."""
    idx = close_h.index[close_h.index >= start]
    comp = pd.DataFrame({k: v.reindex(idx).fillna(0.0) for k, v in comps.items()})
    tot = comp.sum(axis=1)
    eq = capital * (1 + tot).cumprod()
    lr = np.log1p(tot)
    bench = np.log(close_h.reindex(idx) / close_h.shift(1).reindex(idx)).fillna(0.0)
    bench.iloc[0] = 0.0
    tr_in = [t for t in trades if pd.Timestamp(t["entry_time"]) >= start or (t.get("exit_time") and pd.Timestamp(t["exit_time"]) >= start)]
    for t in tr_in:
        te = pd.Timestamp(t["entry_time"])
        e_at = float(eq[eq.index <= te].iloc[-1]) if (eq.index <= te).any() else capital
        t["usd"] = round(t["ret"] * capital if "premium_pct" in t else e_at * math.expm1(t["ret"]), 2)
        if te < start:
            t["carried_in"] = True
    s = summary(lr, per_year, tr_in, bench)
    s["start_capital"], s["end_capital"] = capital, float(eq.iloc[-1])
    s["pnl_usd"] = float(eq.iloc[-1] - capital)
    s["benchmark_end_usd"] = float(capital * math.exp(bench.sum()))
    # contribution of each part (dollar P&L, additive per bar)
    prev_eq = eq.shift(1).fillna(capital)
    s["by_component_usd"] = {k: float((comp[k] * prev_eq).sum()) for k in comp.columns}
    for k, p in positions.items():
        pp = p.reindex(idx).fillna(0.0)
        s.setdefault("exposure", {})[k] = {"long_share": float((pp > 0).mean()), "short_share": float((pp < 0).mean())}
    months = []
    for m, g in eq.groupby(eq.index.tz_localize(None).to_period("M")):
        e0 = float(eq[eq.index < g.index[0]].iloc[-1]) if (eq.index < g.index[0]).any() else capital
        b = bench[g.index]
        months.append({"month": str(m), "start_usd": e0, "end_usd": float(g.iloc[-1]), "ret": float(g.iloc[-1] / e0 - 1),
                       "benchmark": float(math.exp(b.sum()) - 1),
                       "trades": sum(1 for t in tr_in if pd.Timestamp(t["entry_time"]).tz_localize(None).to_period("M") == m and not t.get("carried_in"))})
    # daily log
    days = []
    for d, g in eq.groupby(eq.index.floor("D")):
        if d < last30:
            continue
        e0 = float(eq[eq.index < g.index[0]].iloc[-1]) if (eq.index < g.index[0]).any() else capital
        c = close_h.reindex(g.index)
        c_prev = close_h[close_h.index < g.index[0]].iloc[-1]
        row = {"date": str(d.date()), "weekday": d.day_name()[:3], "start_usd": round(e0, 2), "end_usd": round(float(g.iloc[-1]), 2),
               "pnl_usd": round(float(g.iloc[-1]) - e0, 2), "ret": float(g.iloc[-1] / e0 - 1),
               "market_close": round(float(c.iloc[-1]), 2), "market_ret": float(c.iloc[-1] / c_prev - 1),
               "by_component_usd": {k: round(float((comp.loc[g.index, k] * prev_eq[g.index]).sum()), 2) for k in comp.columns}}
        pos = {}
        for k, p in positions.items():
            pp = p.reindex(g.index).fillna(0.0)
            if (pp > 0).all():
                pos[k] = "long all day"
            elif (pp < 0).all():
                pos[k] = "short all day"
            elif (pp == 0).all():
                pos[k] = "flat"
            else:
                parts = [f"{n}h {w}" for n, w in ((int((pp > 0).sum()), "long"), (int((pp < 0).sum()), "short"), (int((pp == 0).sum()), "flat")) if n]
                pos[k] = ", ".join(parts)
        row["positions"] = pos
        row["opened"] = [t for t in trades if pd.Timestamp(t["entry_time"]).floor("D") == d]
        row["closed"] = [t for t in trades if t.get("exit_time") and pd.Timestamp(t["exit_time"]).floor("D") == d]
        if alarms is not None:
            a = alarms.reindex(g.index)
            row["max_p_up"], row["max_p_dn"] = float(a["p_up"].max()), float(a["p_dn"].max())
        row["note"] = (notes_by_day or {}).get(str(d.date()), "")
        days.append(row)
    curve = eq.resample("4h").last().dropna()
    bcurve = (capital * np.exp(bench.cumsum())).resample("4h").last().dropna()
    return {"summary": s, "months": months, "days": days,
            "trades": tr_in,
            "curve": {"t": [str(t) for t in curve.index], "eq": [round(float(v), 3) for v in curve.values],
                      "bench": [round(float(v), 3) for v in bcurve.reindex(curve.index).values]}}
