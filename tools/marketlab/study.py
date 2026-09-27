"""The chart study: returns, trend, volatility, drawdowns, seasonality, key price levels and
cross-asset links, computed from daily and hourly OHLCV."""
import numpy as np
import pandas as pd

from .features import atr, rsi


def _f(x, nd=4):
    return None if x is None or not np.isfinite(x) else round(float(x), nd)


def summary(d, per_year):
    c = d["close"]
    r = np.log(c).diff().dropna()
    eq = c / c.iloc[0]
    dd = c / c.cummax() - 1
    yrs = (d.index[-1] - d.index[0]).days / 365.25
    return {
        "start": str(d.index[0].date()), "end": str(d.index[-1].date()),
        "first_close": _f(c.iloc[0], 2), "last_close": _f(c.iloc[-1], 2),
        "total_return": _f(eq.iloc[-1] - 1), "cagr": _f(eq.iloc[-1] ** (1 / yrs) - 1),
        "ann_vol": _f(r.std() * np.sqrt(per_year)), "sharpe": _f(r.mean() / r.std() * np.sqrt(per_year), 2),
        "max_drawdown": _f(dd.min()), "max_drawdown_date": str(dd.idxmin().date()),
        "best_day": _f(r.max()), "best_day_date": str(r.idxmax().date()),
        "worst_day": _f(r.min()), "worst_day_date": str(r.idxmin().date()),
        "up_days": _f((r > 0).mean()), "skew": _f(r.skew(), 2), "kurtosis": _f(r.kurt(), 2),
        "tail_days_3sd": int((r.abs() > 3 * r.std()).sum()),
        "all_time_high_in_window": _f(d["high"].max(), 2), "ath_date": str(d["high"].idxmax().date()),
        "low_in_window": _f(d["low"].min(), 2), "low_date": str(d["low"].idxmin().date()),
        "bars": int(len(d)), "years": _f(yrs, 2),
    }


def yearly_monthly(d):
    c = d["close"]
    y = c.resample("YE").last()
    yearly = (y / y.shift().fillna(c.iloc[0]) - 1)
    m = c.resample("ME").last()
    monthly = (m / m.shift().fillna(c.iloc[0]) - 1)
    grid = {}
    for t, v in monthly.items():
        grid.setdefault(str(t.year), [None] * 12)[t.month - 1] = _f(v)
    by_month = monthly.groupby(monthly.index.month).agg(["mean", lambda s: (s > 0).mean(), "count"])
    return {
        "yearly": {str(t.year): _f(v) for t, v in yearly.items()},
        "monthly_grid": grid,
        "month_of_year": [{"month": int(k), "mean": _f(r["mean"]), "up_share": _f(r["<lambda_0>"]),
                           "n": int(r["count"])} for k, r in by_month.iterrows()],
    }


def seasonality(d, h):
    r = np.log(d["close"]).diff().dropna()
    names = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
    dow = []
    for k, s in r.groupby(r.index.dayofweek):
        dow.append({"day": names[k], "mean": _f(s.mean(), 5), "vol": _f(s.std(), 5), "up_share": _f((s > 0).mean()),
                    "t_stat": _f(s.mean() / (s.std() / np.sqrt(len(s))), 2), "n": int(len(s))})
    out = {"day_of_week": dow}
    if h is not None and len(h) > 500:
        rh = np.log(h["close"]).diff().dropna()
        rng = (h["high"] - h["low"]) / h["close"]
        hod = []
        for k, s in rh.groupby(rh.index.hour):
            hod.append({"hour_utc": int(k), "mean": _f(s.mean(), 6), "vol": _f(s.std(), 6),
                        "avg_range": _f(rng[rng.index.hour == k].mean(), 5),
                        "up_share": _f((s > 0).mean()),
                        "t_stat": _f(s.mean() / (s.std() / np.sqrt(len(s))), 2), "n": int(len(s))})
        out["hour_of_day"] = hod
    return out


def drawdowns(d, top=6):
    c = d["close"]
    peak = c.cummax()
    dd = c / peak - 1
    out, in_dd, start = [], False, None
    for t, v in dd.items():
        if v < 0 and not in_dd:
            in_dd, start = True, t
        elif v == 0 and in_dd:
            seg = dd[start:t]
            out.append((seg.min(), start, seg.idxmin(), t))
            in_dd = False
    if in_dd:
        seg = dd[start:]
        out.append((seg.min(), start, seg.idxmin(), None))
    out.sort(key=lambda x: x[0])
    res = []
    for depth, s, trough, rec in out[:top]:
        pk = c[:s].index[-2] if len(c[:s]) > 1 else s
        res.append({"depth": _f(depth), "peak": str(pk.date()), "trough": str(trough.date()),
                    "recovered": str(rec.date()) if rec is not None else None,
                    "days_down": int((trough - pk).days),
                    "days_to_recover": int((rec - trough).days) if rec is not None else None})
    return res


def trend(d):
    c = d["close"]
    s50, s200 = c.rolling(50).mean(), c.rolling(200).mean()
    valid = s200.notna()
    above = (c > s200)[valid]
    cross = np.sign(s50 - s200)[valid]
    ch = cross.diff().fillna(0)
    events = []
    for t, v in ch[ch != 0].items():
        i = c.index.get_loc(t)
        fwd = {}
        for n in (30, 90):
            fwd[f"ret_{n}d"] = _f(c.iloc[i + n] / c.iloc[i] - 1) if i + n < len(c) else None
        events.append({"date": str(t.date()), "type": "golden cross" if v > 0 else "death cross",
                       "price": _f(c.iloc[i], 2), **fwd})
    # streaks
    up = (c.diff() > 0).astype(int)
    best_up = best_dn = run = 0
    last = None
    for u in up.iloc[1:]:
        run = run + 1 if u == last else 1
        last = u
        if u:
            best_up = max(best_up, run)
        else:
            best_dn = max(best_dn, run)
    # trend efficiency (how straight the path is) over 20 bars, averaged
    eff = (c.diff(20).abs() / c.diff().abs().rolling(20).sum()).dropna()
    return {"share_above_sma200": _f(above.mean()), "crosses": events,
            "longest_up_streak": int(best_up), "longest_down_streak": int(best_dn),
            "trend_efficiency_20": _f(eff.mean()), "trend_efficiency_now": _f(eff.iloc[-1])}


def volatility(d, per_year):
    r = np.log(d["close"]).diff()
    rv30 = r.rolling(30).std() * np.sqrt(per_year)
    ac = {f"ret_lag{k}": _f(r.autocorr(k), 3) for k in (1, 2, 3, 5)}
    ac.update({f"absret_lag{k}": _f(r.abs().autocorr(k), 3) for k in (1, 5, 20)})
    return {"rv30_now": _f(rv30.iloc[-1]), "rv30_median": _f(rv30.median()), "rv30_min": _f(rv30.min()),
            "rv30_max": _f(rv30.max()), "rv30_percentile_now": _f((rv30 <= rv30.iloc[-1]).mean()),
            "autocorr": ac}


def volume_profile(h, bins_pct=0.005, top=8):
    """Price levels where the most volume traded (hourly bars, volume spread over each bar's range)."""
    if h is None or "volume" not in h or h["volume"].fillna(0).sum() == 0:
        return []
    lo, hi = h["low"].min(), h["high"].max()
    edges = np.exp(np.arange(np.log(lo), np.log(hi) + bins_pct, bins_pct))
    vol = np.zeros(len(edges))
    for l, hh, v in zip(h["low"].values, h["high"].values, h["volume"].fillna(0).values):
        a, b = np.searchsorted(edges, l), np.searchsorted(edges, hh)
        b = max(b, a + 1)
        vol[a:b] += v / (b - a)
    # local maxima, spaced at least 3 % apart
    order = np.argsort(-vol)
    picks = []
    for i in order:
        if vol[i] == 0:
            break
        if all(abs(np.log(edges[i] / edges[j])) > 0.03 for j in picks):
            picks.append(i)
        if len(picks) >= top:
            break
    total = vol.sum()
    return sorted([{"price": _f(edges[i], 2), "share": _f(vol[i] / total, 4)} for i in picks],
                  key=lambda x: x["price"])


def state_now(d, per_year):
    c = d["close"]
    last = c.iloc[-1]
    out = {"close": _f(last, 2), "date": str(d.index[-1].date()), "rsi14": _f(rsi(c).iloc[-1], 1),
           "atr14_pct": _f(atr(d).iloc[-1] / last)}
    for n in (20, 50, 200):
        out[f"vs_sma{n}"] = _f(last / c.rolling(n).mean().iloc[-1] - 1)
    out["from_high"] = _f(last / c.cummax().iloc[-1] - 1)
    for n, name in ((252, "52w"),):
        w = d.iloc[-min(n, len(d)):]
        out[f"{name}_high"], out[f"{name}_low"] = _f(w["high"].max(), 2), _f(w["low"].min(), 2)
    return out


def correlations(d, others, window=90):
    """Rolling and full-period correlation of daily log returns with other assets (aligned by date)."""
    r = np.log(d["close"]).diff()
    r.index = r.index.normalize()
    r = r.groupby(level=0).last()
    out, series = {}, {}
    for name, o in others.items():
        if o is None or len(o) < 100:
            continue
        ro = np.log(o["close"]).diff() if name not in ("US10Y",) else o["close"].diff()
        ro.index = ro.index.normalize()
        ro = ro.groupby(level=0).last()
        j = pd.concat([r, ro], axis=1, join="inner").dropna()
        if len(j) < 60:
            continue
        rc = j.iloc[:, 0].rolling(window).corr(j.iloc[:, 1]).dropna()
        out[name] = {"full": _f(j.iloc[:, 0].corr(j.iloc[:, 1]), 3), "now": _f(rc.iloc[-1], 3),
                     "min": _f(rc.min(), 3), "max": _f(rc.max(), 3)}
        rcw = rc.resample("W").last().dropna()
        series[name] = {"t": [str(t.date()) for t in rcw.index], "v": [_f(v, 3) for v in rcw.values]}
    return out, series


def daily_series(d):
    c = d["close"]
    s50, s200 = c.rolling(50).mean(), c.rolling(200).mean()
    dd = c / c.cummax() - 1
    return {"t": [str(t.date()) for t in d.index],
            "o": [_f(v, 2) for v in d["open"]], "h": [_f(v, 2) for v in d["high"]],
            "l": [_f(v, 2) for v in d["low"]], "c": [_f(v, 2) for v in c],
            "v": [_f(v, 2) for v in d["volume"].fillna(0)] if "volume" in d else None,
            "sma50": [_f(v, 2) for v in s50], "sma200": [_f(v, 2) for v in s200],
            "dd": [_f(v, 4) for v in dd]}


def full_study(d, h, per_year, others):
    corr, corr_series = correlations(d, others)
    rv30 = np.log(d["close"]).diff().rolling(30).std() * np.sqrt(per_year)
    return {
        "summary": summary(d, per_year), "calendar": yearly_monthly(d), "seasonality": seasonality(d, h),
        "drawdowns": drawdowns(d), "trend": trend(d), "volatility": volatility(d, per_year),
        "volume_profile": volume_profile(h), "state": state_now(d, per_year),
        "correlations": corr, "series": {"daily": daily_series(d), "correlation": corr_series,
                                         "rv30": [_f(v, 4) for v in rv30]},
    }
