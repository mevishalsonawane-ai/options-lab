"""Why did the strategies not make money? Bar-by-bar and multi-bar neural-network scans, the size of
the moves on offer, and a post-mortem of each trade."""
import math

import numpy as np
import pandas as pd

from .evaluate import auc
from .features import atr, base_features, calendar_features
from .nn import Ensemble


def sequence_features(h, per_year, n_lags=24):
    """Price-only features plus the last `n_lags` bars as a sequence (returns and ranges), so the network
    sees the shape of the recent path, not just summaries of it."""
    f = base_features(h, per_year, lags=n_lags).join(calendar_features(h.index, hourly=True))
    c = h["close"]
    rng = (h["high"] - h["low"]) / c
    for k in range(1, 7):
        f[f"range_l{k}"] = rng.shift(k - 1)
    f["from_480h_high"] = c / c.rolling(480).max() - 1
    f["from_480h_low"] = c / c.rolling(480).min() - 1
    f["rv24_rv480"] = np.log(c).diff().rolling(24).std() / np.log(c).diff().rolling(480).std()
    return f.replace([np.inf, -np.inf], np.nan)


def _wf(X, y, idx, s0, step, horizon, task, n_models):
    pred = np.full(len(idx), np.nan)
    for i in range(s0, len(idx), step):
        tr = np.arange(0, max(i - horizon, 1))
        tr = tr[~np.isnan(y[tr])]
        m = Ensemble(n=n_models, task=task, hidden=(32, 16), dropout=0.2, l2=1e-3, batch=256).fit(X[tr], y[tr])
        pred[i:i + step] = m.predict(X[i:i + step])
    return pred


def horizon_scan(h, per_year, cost, horizons=(1, 2, 4, 8, 12, 24), step=1500, n_models=2, start_frac=0.6, split=None):
    """For each horizon H: can a network, at every bar, call (a) the direction of the next H bars and
    (b) how big the move will be? Scored out-of-sample, overall and on the rows at/after `split`."""
    f = sequence_features(h, per_year)
    ok = (f.notna().mean(axis=1) > 0.9).values
    c = h["close"]
    X_all = f.values.astype(float)
    rows = np.where(ok)[0]
    X = X_all[rows]
    s0 = int(len(rows) * start_frac)
    out = []
    series = {}
    for H in horizons:
        fwd = np.log(c.shift(-H) / c).values[rows]
        hi = np.log(h["high"].rolling(H).max().shift(-H) / c).values[rows]
        lo = np.log(h["low"].rolling(H).min().shift(-H) / c).values[rows]
        span = hi - lo  # the full high-low range inside the next H bars
        y_dir = np.where(np.isnan(fwd), np.nan, (fwd > 0).astype(float))
        y_mag = np.log(np.abs(fwd) + 1e-5)
        p_dir = _wf(X, y_dir, rows, s0, step, H, "clf", n_models)
        p_mag = _wf(X, y_mag, rows, s0, step, H, "reg", n_models)
        t_idx = h.index[rows]
        seg = {"all_test": np.arange(s0, len(rows))}
        if split is not None:
            seg["dev"] = np.where((np.arange(len(rows)) >= s0) & (t_idx < split))[0]
            seg["window"] = np.where(t_idx >= split)[0]
        res = {"horizon": H}
        for name, sidx in seg.items():
            sidx = sidx[~np.isnan(fwd[sidx])]
            if len(sidx) < 50:
                continue
            yd, pd_, r, sp = y_dir[sidx], p_dir[sidx], fwd[sidx], span[sidx]
            hit = (pd_ > 0.5) == (yd == 1)
            conf = np.abs(pd_ - 0.5) >= 0.05
            avg_abs = float(np.mean(np.abs(r)))
            be = 0.5 + cost / avg_abs  # accuracy needed to cover a round trip (2 x cost) on the average move
            # trade every bar in the predicted direction, non-overlapping (every H-th bar)
            take = sidx[::H]
            pos = np.where(p_dir[take] > 0.5, 1.0, -1.0)
            pnl = pos * fwd[take] - 2 * cost
            pmag = p_mag[sidx]
            mag_true = np.abs(r)
            top = pmag >= np.quantile(pmag, 0.9)
            res[name] = {
                "n": int(len(sidx)), "avg_abs_move": avg_abs, "avg_range": float(np.nanmean(sp)),
                "dir_accuracy": float(hit.mean()), "dir_auc": auc(yd, pd_),
                "dir_conf_share": float(conf.mean()), "dir_conf_accuracy": float(hit[conf].mean()) if conf.any() else None,
                "breakeven_accuracy": float(be),
                "trade_every_bar_avg": float(pnl.mean()), "trade_every_bar_total": float(pnl.sum()),
                "trade_every_bar_n": int(len(pnl)),
                "mag_corr": float(np.corrcoef(pmag, np.log(mag_true + 1e-5))[0, 1]),
                "mag_top10_avg_move": float(mag_true[top].mean()), "mag_rest_avg_move": float(mag_true[~top].mean()),
            }
        # accuracy by entry hour for the full test
        sidx = seg["all_test"][~np.isnan(fwd[seg["all_test"]])]
        hrs = t_idx[sidx].hour
        hit = (p_dir[sidx] > 0.5) == (y_dir[sidx] == 1)
        res["by_hour"] = [{"hour": int(k), "accuracy": float(hit[hrs == k].mean()), "n": int((hrs == k).sum()),
                           "avg_abs_move": float(np.abs(fwd[sidx][hrs == k]).mean())} for k in sorted(set(hrs))]
        out.append(res)
        series[H] = pd.DataFrame({"p_dir": p_dir, "p_mag": p_mag, "fwd": fwd}, index=t_idx)
        print(f"  H={H}: acc {res['all_test']['dir_accuracy']:.3f} (need {res['all_test']['breakeven_accuracy']:.3f}), "
              f"auc {res['all_test']['dir_auc']:.3f}, size corr {res['all_test']['mag_corr']:.3f}")
    return out, series


def sessions(h, start=None, point=None):
    """What each UTC day offered: high-low range in price points and %, open-to-close move."""
    g = h.groupby(h.index.floor("D"))
    d = pd.DataFrame({"open": g["open"].first(), "high": g["high"].max(), "low": g["low"].min(), "close": g["close"].last()})
    if start is not None:
        d = d[d.index >= start]
    d["range_pts"] = d["high"] - d["low"]
    d["range_pct"] = d["range_pts"] / d["open"]
    d["oc_pct"] = d["close"] / d["open"] - 1
    s = {"days": int(len(d)), "avg_range_pts": float(d["range_pts"].mean()), "median_range_pts": float(d["range_pts"].median()),
         "avg_range_pct": float(d["range_pct"].mean()), "avg_abs_oc_pct": float(d["oc_pct"].abs().mean()),
         "share_oc_of_range": float((d["oc_pct"].abs() / d["range_pct"]).mean())}
    if point:
        s["days_over"] = {str(p): int((d["range_pts"] > p).sum()) for p in point}
    return s, d


def trade_autopsy(trades, h):
    """Max favourable / adverse excursion of each directional trade, and what it kept."""
    out = []
    for t in trades:
        if t["side"] not in ("long", "short") or not t.get("exit_time"):
            continue
        a, b = pd.Timestamp(t["entry_time"]), pd.Timestamp(t["exit_time"])
        w = h[(h.index > a) & (h.index <= b)]
        if w.empty:
            continue
        e = t["entry"]
        sgn = 1 if t["side"] == "long" else -1
        mfe = (w["high"].max() / e - 1) if sgn > 0 else (1 - w["low"].min() / e)
        mae = (w["low"].min() / e - 1) if sgn > 0 else (1 - w["high"].max() / e)
        t_best = (w["high"].idxmax() if sgn > 0 else w["low"].idxmin())
        before = h[(h.index <= a) & (h.index > a - pd.Timedelta(hours=24))]
        pre = (e / before["close"].iloc[0] - 1) * sgn if len(before) else np.nan
        out.append({**{k: t[k] for k in ("strategy", "side", "entry_time", "exit_time", "entry", "exit", "ret")},
                    "mfe": float(mfe), "mae": float(mae), "kept": float(t["ret"] / mfe) if mfe > 0 else None,
                    "hours_to_best": float((t_best - a).total_seconds() / 3600),
                    "move_before_entry_24h": float(pre)})
    return out


def atr_series(h):
    return atr(h, 14) / h["close"]


def summarize_bt(pnl_log, per_year):
    lr = pnl_log.fillna(0.0)
    eq = np.exp(lr.cumsum())
    sd = lr.std()
    return {"total_return": float(eq.iloc[-1] - 1), "max_drawdown": float((eq / eq.cummax() - 1).min()),
            "sharpe": float(lr.mean() / sd * math.sqrt(per_year)) if sd > 0 else 0.0}
