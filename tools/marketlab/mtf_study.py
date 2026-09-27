"""One neural network over every timeframe (1m ... 24h), joined by date and time on an hourly decision clock.

For each target, networks with (a) hourly features only and (b) all timeframes are trained walk-forward from
the development year, scored on the development year and the last 3 months, and the all-timeframe model is
opened up by shuffling one timeframe at a time. The big-move networks also produce new hourly alarms."""
import math
import time

import numpy as np
import pandas as pd

from .evaluate import auc
from .mtf import build_all
from .nn import Ensemble

GROUPS = ["1m", "5m", "15m", "30m", "1h", "3h", "6h", "12h", "24h", "m1", "x_opt", "x_dvol", "x_fund", "x_pos", "x_flow",
          "x_sent", "x_vol", "x_macro", "x_cot", "x_cross"]
GROUP_NAMES = {"m1": "inside-the-hour (1-minute detail)", "d": "order flow & path shape (from 1-minute bars)",
               "cal": "event calendar (FOMC, jobs, expiries)", "x_etf": "ETF flows", "x_cme": "CME basis",
               "x_tradfi": "stocks, VIX & rates", "x_stable": "stablecoin supply", "x_onchain": "on-chain",
               "x_premium": "perp premium index", "x_fx": "currencies", "x_commod": "miners, copper, oil", "x_rates": "Treasury yields", "x_opt": "option surface", "x_dvol": "DVOL", "x_fund": "funding",
               "x_pos": "open interest & long/short", "x_flow": "perp basis & Coinbase premium", "x_sent": "Fear & Greed",
               "x_vol": "GVZ & VIX", "x_macro": "dollar, yields, breakevens", "x_cot": "COT positioning", "x_cross": "other markets"}


def targets(h1):
    """h1: hourly bars indexed by CLOSE time. Targets look forward from each close."""
    c = h1["close"]
    r = np.log(c).diff()
    t = pd.DataFrame(index=h1.index)
    for n in (1, 4, 24):
        fwd = np.log(c.shift(-n) / c)
        t[f"dir_{n}h"] = (fwd > 0).astype(float).where(fwd.notna())
        t[f"fwd_{n}h"] = fwd
    sd24 = r.rolling(480, min_periods=240).std() * math.sqrt(24)
    fwd24 = t["fwd_24h"]
    ok = fwd24.notna() & sd24.notna()
    t["big_up_24h"] = (fwd24 > 2 * sd24).astype(float).where(ok)
    t["big_down_24h"] = (fwd24 < -2 * sd24).astype(float).where(ok)
    fw = pd.concat([r.shift(-k) for k in range(1, 25)], axis=1)
    t["vol_24h"] = np.log(fw.std(axis=1) * math.sqrt(24 * 365) + 1e-6).where(fw.notna().all(axis=1))
    return t


def walk(X, y, idx, dev_start, horizon, task, step, n_models):
    s0 = int(np.searchsorted(idx, dev_start))
    pred = np.full(len(idx), np.nan)
    last = None
    for i in range(s0, len(idx), step):
        tr = np.arange(0, max(i - horizon, 1))
        tr = tr[~np.isnan(y[tr])]
        last = Ensemble(n=n_models, task=task, hidden=(48, 24), dropout=0.2, l2=1e-3, batch=256).fit(X[tr], y[tr])
        pred[i:i + step] = last.predict(X[i:i + step])
    return pred


def score(task, y, p):
    ok = ~np.isnan(y) & ~np.isnan(p)
    if ok.sum() < 30:
        return None
    if task == "clf":
        return {"auc": auc(y[ok], p[ok]), "accuracy": float(((p[ok] > 0.5) == (y[ok] == 1)).mean()), "base": float(y[ok].mean()), "n": int(ok.sum())}
    return {"corr": float(np.corrcoef(y[ok], p[ok])[0, 1]), "n": int(ok.sum())}


def extra_block(clock, frames):
    """Join extra market data onto the hourly clock. `frames` maps a prefix to a DataFrame already indexed by
    the time each row became KNOWN (the caller applies publication lags)."""
    from .mtf import join_asof
    fs = []
    for prefix, f in frames.items():
        f = f.copy()
        f.columns = [f"x_{prefix}_{c}" for c in f.columns]
        f.index.name = "time"
        fs.append(f.sort_index().ffill())  # a value stays the latest known value until its next update
    return join_asof(clock, fs) if fs else pd.DataFrame(index=clock)


def run(m1, dev_start, win_start, cost, step=1000, n_models=3, log=print, extra=None, events=None, select=True):
    t0 = time.time()
    bars, X = build_all(m1)
    h1 = bars["1h"].set_index("close_time")
    X = X.reindex(h1.index)
    cal = pd.DataFrame(index=X.index)
    cal["hour_sin"], cal["hour_cos"] = np.sin(2 * np.pi * X.index.hour / 24), np.cos(2 * np.pi * X.index.hour / 24)
    cal["dow_sin"], cal["dow_cos"] = np.sin(2 * np.pi * X.index.dayofweek / 7), np.cos(2 * np.pi * X.index.dayofweek / 7)
    X = X.join(cal)
    from .derived import calendar as event_calendar, from_minutes
    blocks = [from_minutes(m1, X.index), event_calendar(X.index, events)]
    if extra:
        blocks.append(extra_block(X.index, extra))
    E = pd.concat(blocks, axis=1)
    E = E.loc[:, E.notna().mean() > 0.5]  # drop inputs that are mostly missing
    xcols = list(E.columns)
    X = X.join(E)
    X = X.replace([np.inf, -np.inf], np.nan)
    X = X[X.drop(columns=xcols).notna().mean(axis=1) > 0.9]
    T = targets(h1).reindex(X.index)
    log(f"  features: {X.shape[1]} columns x {len(X)} hours, built in {time.time() - t0:.0f}s")
    out = {"hours": int(len(X)), "timeframe_bars": {k: int(len(v)) for k, v in bars.items()}, "targets": {}}
    base_cols = [c for c in X.columns if c not in xcols]
    sets = {"hourly only": [c for c in X.columns if c.startswith("1h_") or c in cal.columns],
            "all timeframes": base_cols,
            "everything": list(X.columns)}
    groups = {}
    for c in xcols:
        g = "d" if c.startswith("d_") else "cal" if c.startswith("cal_") else c.split("_")[1]
        groups.setdefault(g, []).append(c)
    out["input_groups"] = {g: len(v) for g, v in groups.items()}
    idx = X.index
    T0 = T
    if select and groups:
        # keep a group only if adding it to "all timeframes" helps on the DEVELOPMENT year (quick networks)
        quick = [("dir_24h", "clf", 24), ("big_up_24h", "clf", 24), ("big_down_24h", "clf", 24), ("vol_24h", "reg", 24)]
        mid = dev_start + (win_start - dev_start) / 2
        halves = [(idx >= dev_start) & (idx < mid), (idx >= mid) & (idx < win_start)]

        def dev_score(cols):
            """Skill per target in each half of the development year: array [half, target]."""
            sc = np.zeros((2, len(quick)))
            for j, (tname, task, hz) in enumerate(quick):
                y = T0[tname].values
                p = walk(X[cols].values.astype(float), y, idx, dev_start, hz, task, 2000, 2)
                for i, hmask in enumerate(halves):
                    r = score(task, y[hmask], p[hmask])
                    sc[i, j] = (r["auc"] - 0.5) if task == "clf" else r["corr"]
            return sc
        base_sc = dev_score(base_cols)
        gains = {}
        for g, cols in groups.items():
            gains[g] = dev_score(base_cols + cols) - base_sc
            log(f"  group {g:>8} ({len(cols):2d} inputs): mean gain 1st half {gains[g][0].mean():+.4f}, 2nd half {gains[g][1].mean():+.4f}")
        keep = [g for g, v in gains.items() if v[0].mean() > 0.005 and v[1].mean() > 0.005]
        out["selection"] = {"base_dev_scores": base_sc.tolist(), "gains": {g: v.tolist() for g, v in gains.items()}, "kept": keep,
                            "rule": "keep a group only if it raises the mean score by more than 0.005 in BOTH halves of the development year"}
        sets["selected (chosen on dev year)"] = base_cols + [c for g in keep for c in groups[g]]
        log(f"  kept groups: {keep}")
    specs = [("dir_1h", "clf", 1), ("dir_4h", "clf", 4), ("dir_24h", "clf", 24), ("big_up_24h", "clf", 24),
             ("big_down_24h", "clf", 24), ("vol_24h", "reg", 24)]
    out["n_features"] = {k: len(v) for k, v in sets.items()}
    preds = {}
    for tname, task, hz in specs:
        y = T[tname].values
        res = {}
        for sname, cols in sets.items():
            p = walk(X[cols].values.astype(float), y, idx, dev_start, hz, task, step, n_models)
            preds[(tname, sname)] = p
            dev = (idx >= dev_start) & (idx < win_start)
            win = idx >= win_start
            res[sname] = {"dev": score(task, y[dev], p[dev]), "window": score(task, y[win], p[win])}
            if tname.startswith("dir_"):
                fwd = T[tname.replace("dir", "fwd")].values
                for seg, m in (("dev", dev), ("window", win)):
                    sel = np.where(m & ~np.isnan(p) & ~np.isnan(fwd))[0][::hz]  # non-overlapping trades
                    pnl = np.where(p[sel] > 0.5, 1, -1) * fwd[sel] - 2 * cost
                    res[sname][seg]["trade_total"] = float(pnl.sum())
                    res[sname][seg]["trades"] = int(len(sel))
        log(f"  {tname}: " + " | ".join(f"{s}: dev {list(res[s]['dev'].values())[0]:.3f} win {list(res[s]['window'].values())[0]:.3f}" for s in res)
            + f"  ({time.time() - t0:.0f}s)")
        # which timeframes does the all-timeframe network use? shuffle one group at a time on the dev year
        cols = sets.get("selected (chosen on dev year)") or sets["everything"]
        tr = np.where((idx < dev_start - pd.Timedelta(hours=hz)) & ~np.isnan(y))[0]
        ev = np.where((idx >= dev_start) & (idx < win_start) & ~np.isnan(y))[0]
        Xa = X[cols].values.astype(float)
        m = Ensemble(n=n_models, task=task, hidden=(48, 24), dropout=0.2, l2=1e-3, batch=256).fit(Xa[tr], y[tr])
        metric = (lambda yy, pp: auc(yy, pp)) if task == "clf" else (lambda yy, pp: float(np.corrcoef(yy, pp)[0, 1]))
        base = metric(y[ev], m.predict(Xa[ev]))
        rng = np.random.default_rng(0)
        imp = {}
        for g in GROUPS[:10] + sorted(groups):
            gi = [j for j, c in enumerate(cols) if c.startswith(g + "_") or (g in groups and c in groups[g])]
            if not gi:
                continue
            drops = []
            for _ in range(3):
                Xp = Xa[ev].copy()
                perm = rng.permutation(len(ev))
                Xp[:, gi] = Xp[perm][:, gi]  # shuffle the whole timeframe block together, keeping it internally consistent
                drops.append(base - metric(y[ev], m.predict(Xp)))
            imp[GROUP_NAMES.get(g, GROUP_NAMES.get("x_" + g, g))] = float(np.mean(drops))
        res["importance_dev"] = {"base": float(base), "drop_when_shuffled": imp}
        out["targets"][tname] = res
    best = "selected (chosen on dev year)" if "selected (chosen on dev year)" in sets else "everything"
    out["extra_inputs"] = xcols
    alarms = pd.DataFrame({"p_up": preds[("big_up_24h", best)], "p_dn": preds[("big_down_24h", best)]}, index=idx).dropna()
    return out, alarms
