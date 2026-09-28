"""When and where do the big rises and drops happen, and what does a neural network learn about them?

Big move = a bar whose return is large against that period's own normal volatility (z-score against the
trailing standard deviation), so a 2 % BTC hour in a calm month counts and the same hour in a panic
does not. Every condition is measured at the close of the bar BEFORE the move.
"""
import numpy as np
import pandas as pd

from .evaluate import auc
from .features import base_features, calendar_features, rsi
from .nn import Ensemble

DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]


def _r(x, nd=4):
    return None if x is None or not np.isfinite(x) else round(float(x), nd)


def zscores(close, window):
    r = np.log(close).diff()
    sd = r.rolling(window, min_periods=window // 2).std().shift(1)
    return r, r / sd


def lift_by(key, up, dn, labels=None):
    """Big-up and big-down rate for each value of `key`, divided by the overall rate (1.0 = normal)."""
    df = pd.DataFrame({"k": key, "up": up.astype(float), "dn": dn.astype(float)}).dropna()
    bu, bd = df.up.mean(), df.dn.mean()
    out = []
    for k, g in df.groupby("k"):
        out.append({"key": labels[k] if labels is not None else (k if isinstance(k, str) else int(k)),
                    "n": int(len(g)), "up_rate": _r(g.up.mean(), 5), "down_rate": _r(g.dn.mean(), 5),
                    "up_lift": _r(g.up.mean() / bu if bu else np.nan, 2), "down_lift": _r(g.dn.mean() / bd if bd else np.nan, 2)})
    return out


def bucket_table(name, x, up, dn, edges=None, q=5, labels=None):
    """Event rates by buckets of a condition measured just before the bar."""
    x = pd.to_numeric(pd.Series(x), errors="coerce")
    if edges is not None:
        b = pd.cut(x, edges, labels=labels)
    else:
        try:
            b = pd.qcut(x, q, labels=labels or [f"Q{i + 1}" for i in range(q)], duplicates="drop")
        except ValueError:
            return None
    df = pd.DataFrame({"b": b, "up": up.astype(float).values, "dn": dn.astype(float).values}).dropna()
    bu, bd = df.up.mean(), df.dn.mean()
    rows = []
    for k, g in df.groupby("b", observed=True):
        rows.append({"bucket": str(k), "n": int(len(g)), "up_rate": _r(g.up.mean(), 5), "down_rate": _r(g.dn.mean(), 5),
                     "up_lift": _r(g.up.mean() / bu, 2) if bu else None, "down_lift": _r(g.dn.mean() / bd, 2) if bd else None})
    return {"condition": name, "rows": rows}


def after_moves(r, z, thr, horizons):
    """Average path after big up and big down bars: does the move continue or reverse?"""
    lr = r.fillna(0)
    cum = {h: lr[::-1].rolling(h, min_periods=h).sum()[::-1].shift(-1) for h in horizons}
    out = []
    for side, mask in (("after big rise", z > thr), ("after big drop", z < -thr)):
        row = {"side": side, "n": int(mask.sum())}
        for h in horizons:
            v = cum[h][mask].dropna()
            row[f"ret_{h}"] = _r(v.mean(), 5)
            row[f"same_dir_{h}"] = _r(((v > 0) if side == "after big rise" else (v < 0)).mean(), 3)
        out.append(row)
    base = {"side": "any bar", "n": int(len(z))}
    for h in horizons:
        v = cum[h].dropna()
        base[f"ret_{h}"] = _r(v.mean(), 5)
        base[f"same_dir_{h}"] = None
    out.append(base)
    return out


def clustering(z, thr, window):
    big = (z.abs() > thr).astype(float)
    nxt = big[::-1].rolling(window, min_periods=1).max()[::-1].shift(-1)
    return {"p_another_big_within": _r(nxt[big == 1].mean(), 3), "p_any_window": _r(nxt.mean(), 3), "window": window}


def biggest_days(d, z, extra, n=12):
    c = d["close"]
    r = np.log(c).diff()
    pre5 = np.log(c / c.shift(5)).shift(1)
    rv_ratio = (r.rolling(5).std() / r.rolling(60).std()).shift(1)
    hi20 = (c.shift(1) / c.rolling(20).max().shift(1) - 1)
    lo20 = (c.shift(1) / c.rolling(20).min().shift(1) - 1)
    rows = []
    for side, idx in (("rise", r.nlargest(n).index), ("drop", r.nsmallest(n).index)):
        for t in idx:
            row = {"date": str(t.date()), "side": side, "ret": _r(r[t]), "z": _r(z[t], 1), "prior_5d": _r(pre5[t]),
                   "vol_squeeze": _r(rv_ratio[t], 2), "from_20d_high": _r(hi20[t]), "from_20d_low": _r(lo20[t]),
                   "weekday": DAYS[t.dayofweek]}
            for k, s in (extra or {}).items():
                row[k] = _r(s.shift(1).get(t, np.nan), 2)
            rows.append(row)
    return rows


def detector_features(h, per_year):
    f = base_features(h, per_year).join(calendar_features(h.index, hourly=True))
    c = h["close"]
    r = np.log(c).diff()
    f["rv24_rv480"] = r.rolling(24).std() / r.rolling(480).std()
    f["from_480h_high"] = c / c.rolling(480).max() - 1
    f["from_480h_low"] = c / c.rolling(480).min() - 1
    f["ret_24h"] = np.log(c / c.shift(24))
    f["range_24h"] = (h["high"].rolling(24).max() - h["low"].rolling(24).min()) / c
    return f


def run_detector(h, per_year, thr_sigma=2.0, horizon=24, step=720, n_models=3, extra_daily=None):
    """Neural networks that flag, at each hour, whether the next `horizon` hours will bring a big rise
    (or drop): a move beyond thr_sigma x the normal 24-hour move. Walk-forward with a purge gap."""
    c = h["close"]
    r = np.log(c).diff()
    sd24 = r.rolling(480, min_periods=240).std() * np.sqrt(horizon)
    fwd = np.log(c.shift(-horizon) / c)
    f = detector_features(h, per_year)
    if extra_daily is not None:
        for k, s in extra_daily.items():
            f[k] = s.shift(1).reindex(h.index.floor("D")).values  # yesterday's value: known at every hour today
    tgt = pd.DataFrame({"up": (fwd > thr_sigma * sd24).astype(float), "dn": (fwd < -thr_sigma * sd24).astype(float),
                        "fwd": fwd}, index=h.index)
    tgt.loc[fwd.isna() | sd24.isna(), ["up", "dn"]] = np.nan
    df = f.join(tgt).replace([np.inf, -np.inf], np.nan).dropna(subset=["up", "dn"])
    df = df.loc[df[f.columns].notna().mean(axis=1) > 0.9]
    X = df[f.columns].values.astype(float)
    n = len(df)
    s0 = int(n * 0.6)
    res = {"threshold_sigma": thr_sigma, "horizon_h": horizon, "features": list(f.columns)}
    for side in ("up", "dn"):
        y = df[side].values
        pred = np.full(n, np.nan)
        for i in range(s0, n, step):
            m = Ensemble(n=n_models, task="clf", hidden=(32, 16), dropout=0.2, l2=1e-3, batch=256).fit(X[:i - horizon], y[:i - horizon])
            pred[i:i + step] = m.predict(X[i:i + step])
        p, yy = pred[s0:], y[s0:]
        test = df.iloc[s0:]
        base = yy.mean()
        top = p >= np.quantile(p, 0.9)
        top2 = p >= np.quantile(p, 0.98)
        prof = []
        Z = (test[f.columns] - test[f.columns].mean()) / test[f.columns].std()
        diff = Z[top].mean().sort_values(key=lambda s: -s.abs())
        for k, v in diff.head(10).items():
            prof.append({"feature": k, "z_in_alerts": _r(v, 2),
                         "mean_in_alerts": _r(test.loc[top, k].mean(), 5), "mean_all": _r(test[k].mean(), 5)})
        hours = pd.Series(test.index.hour)
        hr = []
        for hh in range(24):
            m_ = (hours == hh).values
            hr.append({"hour": hh, "alert_share": _r(top[m_].mean(), 3)})
        dow = []
        for k in range(7):
            m_ = (test.index.dayofweek == k)
            if m_.any():
                dow.append({"day": DAYS[k], "alert_share": _r(top[m_].mean(), 3)})
        weekly = pd.Series(p, index=test.index).resample("D").max()
        res[side] = {
            "test_start": str(test.index[0]), "test_end": str(test.index[-1]), "n": int(len(yy)),
            "base_rate": _r(base, 4), "auc": _r(auc(yy, p), 3),
            "top10_rate": _r(yy[top].mean(), 4), "top10_lift": _r(yy[top].mean() / base, 2) if base else None,
            "top2_rate": _r(yy[top2].mean(), 4), "top2_lift": _r(yy[top2].mean() / base, 2) if base else None,
            "profile": prof, "alert_by_hour": hr, "alert_by_day": dow,
            "daily_max_p": {"t": [str(t.date()) for t in weekly.index], "p": [_r(v, 3) for v in weekly.values]},
            "latest_p": _r(p[-1], 3), "latest_time": str(test.index[-1]),
        }
    return res


def full_patterns(d, h, per_year_h, cfg, daily_extra=None):
    """cfg: thr_h (hourly sigma), thr_d (daily sigma), round_step, weekend(bool)."""
    out = {"definition": {"hourly_sigma": cfg["thr_h"], "daily_sigma": cfg["thr_d"]}}
    # ---- hourly events
    rh, zh = zscores(h["close"], 480)
    uph, dnh = zh > cfg["thr_h"], zh < -cfg["thr_h"]
    out["hourly_counts"] = {"bars": int(zh.notna().sum()), "big_up": int(uph.sum()), "big_down": int(dnh.sum())}
    out["by_hour"] = lift_by(pd.Series(h.index.hour, index=h.index), uph, dnh)
    out["by_weekday_h"] = lift_by(pd.Series(h.index.dayofweek, index=h.index), uph, dnh, DAYS)
    # conditions just before the hour
    c = h["close"]
    prev = lambda s: s.shift(1)  # noqa: E731
    rv_ratio = prev(rh.rolling(24).std() / rh.rolling(480).std())
    ret24 = prev(np.log(c / c.shift(24)))
    rsi_h = prev(rsi(c))
    hi480 = prev(c / c.rolling(480).max() - 1)
    lo480 = prev(c / c.rolling(480).min() - 1)
    step = cfg["round_step"]
    rnd = prev((c - (c / step).round() * step).abs() / c)
    conds = [
        bucket_table("Volatility squeeze: last 24h vs last 20 days (low = squeezed)", rv_ratio, uph, dnh),
        bucket_table("Move over the previous 24 hours", ret24, uph, dnh),
        bucket_table("Hourly RSI(14)", rsi_h, uph, dnh, edges=[0, 30, 45, 55, 70, 100],
                     labels=["under 30", "30–45", "45–55", "55–70", "over 70"]),
        bucket_table("Distance below the 20-day high", hi480, uph, dnh, edges=[-1, -0.1, -0.05, -0.02, -0.005, 0.0001],
                     labels=["over 10% below", "5–10% below", "2–5% below", "0.5–2% below", "at the high"]),
        bucket_table("Distance above the 20-day low", lo480, uph, dnh, edges=[-0.0001, 0.005, 0.02, 0.05, 0.1, 10],
                     labels=["at the low", "0.5–2% above", "2–5% above", "5–10% above", "over 10% above"]),
        bucket_table(f"Distance to a round {step:,.0f} level", rnd, uph, dnh, edges=[-1, 0.002, 0.005, 0.01, 1],
                     labels=["within 0.2%", "0.2–0.5%", "0.5–1%", "over 1%"]),
    ]
    dctx = pd.DataFrame(index=h.index)
    sma200 = d["close"].rolling(200).mean()
    above = (d["close"] > sma200).astype(float).where(sma200.notna())
    dctx["above200"] = above.shift(1).reindex(h.index.floor("D")).values
    conds.append(bucket_table("Daily trend: above the 200-day average (yesterday)", dctx["above200"], uph, dnh,
                              edges=[-0.5, 0.5, 1.5], labels=["below", "above"]))
    out["conditions_h"] = [x for x in conds if x]
    out["after_h"] = after_moves(rh, zh, cfg["thr_h"], [1, 4, 24])
    out["cluster_h"] = clustering(zh, cfg["thr_h"], 24)
    # ---- daily events
    rd, zd = zscores(d["close"], 60)
    upd, dnd = zd > cfg["thr_d"], zd < -cfg["thr_d"]
    out["daily_counts"] = {"days": int(zd.notna().sum()), "big_up": int(upd.sum()), "big_down": int(dnd.sum())}
    out["by_weekday_d"] = lift_by(pd.Series(d.index.dayofweek, index=d.index), upd, dnd, DAYS)
    out["by_month_d"] = lift_by(pd.Series(d.index.month, index=d.index), upd, dnd,
                                {i + 1: m for i, m in enumerate(["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul",
                                                                  "Aug", "Sep", "Oct", "Nov", "Dec"])})
    dconds = []
    rv_d = (rd.rolling(5).std() / rd.rolling(60).std()).shift(1)
    dconds.append(bucket_table("Volatility squeeze: last 5 days vs last 60", rv_d, upd, dnd))
    dconds.append(bucket_table("Move over the previous 5 days", np.log(d["close"] / d["close"].shift(5)).shift(1), upd, dnd))
    for k, s in (daily_extra or {}).items():
        dconds.append(bucket_table(k + " (day before)", s.shift(1).reindex(d.index), upd, dnd))
    if cfg.get("first_friday"):
        ff = pd.Series(((d.index.dayofweek == 4) & (d.index.day <= 7)).astype(int), index=d.index)
        dconds.append(bucket_table("First Friday of the month (US jobs report)", ff, upd, dnd, edges=[-0.5, 0.5, 1.5],
                                   labels=["other days", "first Friday"]))
    if cfg.get("expiry_friday"):
        last_fri = pd.Series(((d.index.dayofweek == 4) & ((d.index + pd.Timedelta(days=7)).month != d.index.month)).astype(int), index=d.index)
        dconds.append(bucket_table("Monthly option expiry (last Friday)", last_fri, upd, dnd, edges=[-0.5, 0.5, 1.5],
                                   labels=["other days", "expiry Friday"]))
        near = pd.Series(np.where(d.index.dayofweek.isin([3, 4, 5]), 1, 0), index=d.index)
        dconds.append(bucket_table("Thursday to Saturday (around the weekly expiry)", near, upd, dnd, edges=[-0.5, 0.5, 1.5],
                                   labels=["Sun–Wed", "Thu–Sat"]))
    out["conditions_d"] = [x for x in dconds if x]
    out["after_d"] = after_moves(rd, zd, cfg["thr_d"], [1, 5, 20])
    out["cluster_d"] = clustering(zd, cfg["thr_d"], 5)
    out["biggest_days"] = biggest_days(d, zd, daily_extra)
    out["big_days"] = [{"date": str(t.date()), "side": "up" if zd[t] > 0 else "down", "ret": _r(rd[t])}
                       for t in zd.index[(zd.abs() > cfg["thr_d"]).fillna(False).values]]
    return out
