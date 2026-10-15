"""R34 rules, the anchored walk-forward, twins and gates (PREREG.md).

    python3 research/hunt/r34/r34rules.py      # -> results/results.json (needs <R34_OUT>/rows_NIFTY.parquet)

Every choice (thresholds, exits, the ML model and its entry threshold) is made on expiries BEFORE the test year only.
A rule's trade on a test day is one decision row; its result is that row's label, computed with R26's engine at Rs 2,000
(the same simulation a live paper trade would get). The 2x-spread stress re-simulates the chosen trades.
"""
from __future__ import annotations

import json
import math
from itertools import product

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingRegressor

import r34cfg as K27
import r25data as D
import r25lib as R
import r25rules as RU
import r26lib as L26

R25_FEATS = ["mv5", "mv15", "mv30", "speed5", "brk15", "or_brk", "dist_pct", "dist_k", "dist_rng", "dist_prev", "dist_em",
             "iv", "iv_ch15", "oi_ch30", "opp_oi_ch30", "pcr_ch30", "st15", "st_low", "volb", "vix", "vix_ch", "maxpain",
             "gap", "trend_open", "trend_12", "own_run", "price", "mins_left"]
NEW_FEATS = ["rv_day", "rv60", "rv15", "rv_exp", "seas", "rv_fc", "fv_log", "fv_vix_log", "iv_bs", "iv_rv", "pin_dist",
             "gam_cp", "gam_tot", "first30", "since14", "pcr", "rod"]
FEATS = R25_FEATS + NEW_FEATS
EXITS = ("E1", "E3")
HGB = dict(max_iter=300, learning_rate=0.05, max_leaf_nodes=15, min_samples_leaf=200, l2_regularization=1.0,
           random_state=K27.SEED)
TAUS = (0.0, 100.0, 250.0, 500.0)


def load() -> pd.DataFrame:
    df = pd.read_parquet(K27.OUT / "rows_NIFTY.parquet")
    df["day"] = pd.to_datetime(df["day"]).dt.date
    df["year"] = [d.year for d in df.day]
    for c, s in (("fv_log", "fv"), ("fv_vix_log", "fv_vix")):
        x = df[s].replace([np.inf], 1e3).clip(lower=1e-3, upper=1e3)
        df[c] = np.log(x)
    df = df[np.isfinite(df.y_net_E1) & np.isfinite(df.y_net_E3)].reset_index(drop=True)
    return df


# ------------------------------------------------------------------------------------------------ choosing a row per day
def first_row(g: pd.DataFrame, mask: np.ndarray, score: np.ndarray) -> int | None:
    """The first decision time with a qualifying row; at that time the qualifying row with the HIGHEST score."""
    if not mask.any():
        return None
    sub = g[mask]
    t0 = sub.t.min()
    cand = sub[sub.t == t0]
    return int(cand.index[np.argmax(score[mask][(sub.t == t0).values])])


def pick_days(df: pd.DataFrame, mask: np.ndarray, score: np.ndarray) -> list[int]:
    out = []
    m = pd.Series(mask, index=df.index)
    s = pd.Series(score, index=df.index)
    for _, g in df.groupby("day", sort=True):
        i = first_row(g, m[g.index].values, s[g.index].values)
        if i is not None:
            out.append(i)
    return out


# ------------------------------------------------------------------------------------------------ the rules
def f1_mask(df, th):
    return (df.iv_rv <= th).values & np.isfinite(df.iv_rv.values)


def f1_score(df):
    return -df.iv_rv.fillna(9).values


def g1_mask(df, th):
    a, b = th
    return ((df.price >= 15) & (df.pin_dist >= a) & (df.mv15 >= b)).values


def g1_score(df):
    return -df.dist_pct.values                        # nearest the money


AT_1430 = R.close_row(14, 30)                       # the 14:30 bar close


def q_mask(df, k, sgn):
    """Q1 (sgn +1, momentum) / Q2 (sgn -1, reversal): at the 14:30 decision, |return since the previous close| >= k x
    the day's realized sigma so far (rv_day / sqrt(252)); buy a Rs 15-30 option on the momentum (Q1) or the opposite
    (Q2) side. `rod` is signed toward the option, so momentum = rod > 0 for that option."""
    sig = df.rv_day / math.sqrt(252)
    return ((df.t == AT_1430) & (df.price >= 15) & (sgn * df.rod >= k * sig)).values


def q_score(df):
    return -df.dist_pct.values


GRIDS = {"F1": [0.75, 0.85, 1.0], "G1": list(product([0.25, 0.5, 1.0], [0.05, 0.10, 0.20])),
         "Q1": [0.5, 1.0, 1.5], "Q2": [0.5, 1.0, 1.5]}
MASKS = {"F1": (f1_mask, f1_score), "G1": (g1_mask, g1_score),
         "Q1": (lambda df, k: q_mask(df, k, 1), q_score), "Q2": (lambda df, k: q_mask(df, k, -1), q_score)}
STRICT = {"F1": 0.75, "G1": (1.0, 0.20), "Q1": 1.5, "Q2": 1.5}


def net_of(df, idx, ex):
    return float(df.loc[idx, f"y_net_{ex}"].sum()) if idx else 0.0


def per1000(df, idx, ex):
    r = float(df.loc[idx, "y_risked"].sum()) if idx else 0.0
    return net_of(df, idx, ex) / r * 1000 if r > 0 else -math.inf


def choose_rule(rid: str, train: pd.DataFrame) -> tuple:
    """Best net per Rs 1,000 among (threshold, exit) with >= max(10, 10% of training expiries) trades; ties to fewer
    trades; none -> the strictest threshold with E1."""
    mk, sc = MASKS[rid]
    need = max(10, int(0.1 * train.day.nunique()))
    best = None
    for th in GRIDS[rid]:
        idx = pick_days(train, mk(train, th), sc(train))
        if len(idx) < need:
            continue
        for ex in EXITS:
            key = (per1000(train, idx, ex), -len(idx))
            if best is None or key > best[0]:
                best = (key, th, ex)
    return (best[1], best[2]) if best else (STRICT[rid], "E1")


def fit_hgb(train: pd.DataFrame, ex: str) -> HistGradientBoostingRegressor:
    y = train[f"y_p1000_{ex}"].values
    hi = np.nanpercentile(y, 99.9)
    m = HistGradientBoostingRegressor(**HGB)
    m.fit(train[FEATS].values, np.clip(y, None, hi))
    return m


def choose_tau(train: pd.DataFrame, ex: str) -> float:
    """Inner split: fit on the training years before the last one, pick tau on the last training year (>= 10 trades,
    best net per Rs 1,000); none -> the largest tau."""
    last = train.year.max()
    a, b = train[train.year < last], train[train.year == last]
    if a.day.nunique() < 20 or b.empty:
        return TAUS[-1]
    p = fit_hgb(a, ex).predict(b[FEATS].values)
    best = None
    for tau in TAUS:
        idx = pick_days(b, p > tau, p)
        if len(idx) < 10:
            continue
        key = (per1000(b, idx, ex), -len(idx))
        if best is None or key > best[0]:
            best = (key, tau)
    return best[1] if best else TAUS[-1]


def walk_forward(df: pd.DataFrame) -> dict:
    """Per rule: the chosen rows of every test year, and what was chosen."""
    out = {r: {"rows": [], "choices": {}} for r in ("M1", "M2", "F1", "G1", "Q1", "Q2")}
    for y in K27.TEST_YEARS:
        tr, te = df[df.year < y], df[df.year == y]
        for rid, ex in (("M1", "E1"), ("M2", "E3")):
            tau = choose_tau(tr, ex)
            p = fit_hgb(tr, ex).predict(te[FEATS].values)
            idx = pick_days(te, p > tau, p)
            out[rid]["rows"] += [(i, ex) for i in idx]
            out[rid]["choices"][y] = {"tau": tau, "exit": ex}
        for rid in ("F1", "G1", "Q1", "Q2"):
            th, ex = choose_rule(rid, tr)
            mk, sc = MASKS[rid]
            idx = pick_days(te, mk(te, th), sc(te))
            out[rid]["rows"] += [(i, ex) for i in idx]
            out[rid]["choices"][y] = {"threshold": th, "exit": ex}
    return out


# ------------------------------------------------------------------------------------------------ evaluation
class _T:
    """A trade record for r25rules.metrics."""

    def __init__(self, row, ex):
        self.und, self.day, self.net, self.risked = "NIFTY", row.day, float(row[f"y_net_{ex}"]), float(row.y_risked)
        self.fill_row, self.proxy, self.t = int(row.t), False, int(row.t)
        self.peak = float(row.y_peak)


class _Rule:
    unds = ("NIFTY",)


def metrics(df: pd.DataFrame, picks: list, days: list) -> dict:
    ts = [_T(df.loc[i], ex) for i, ex in picks]
    m = RU.metrics(ts, days, _Rule())
    n = len(ts)
    m["x2_pct"] = round(100 * sum(t.peak >= 2 for t in ts) / n, 1) if n else 0.0
    m["x4_pct"] = round(100 * sum(t.peak >= 4 for t in ts) / n, 1) if n else 0.0
    m["x10_pct"] = round(100 * sum(t.peak >= 10 for t in ts) / n, 1) if n else 0.0
    return m


def twins(df: pd.DataFrame, picks: list, rid: str, n: int = K27.TWINS, seed: int = K27.SEED) -> np.ndarray:
    """Each trade replaced by a random eligible row (rule's band) at a random decision time of the same day."""
    rng = np.random.default_rng(seed)
    by_day = {d: g for d, g in df.groupby("day")}
    pools = []
    for i, ex in picks:
        g = by_day[df.loc[i, "day"]]
        if rid in ("G1", "Q1", "Q2"):
            g = g[g.price >= 15]
        ts = np.array(sorted(g.t.unique()))
        pools.append((g, ts, ex))
    out = np.zeros(n)
    for k in range(n):
        tot = 0.0
        for g, ts, ex in pools:
            t = ts[rng.integers(len(ts))]
            h = g[g.t == t]
            tot += float(h[f"y_net_{ex}"].values[rng.integers(len(h))])
        out[k] = tot
    return out


def x2_spread(df: pd.DataFrame, picks: list, ds: dict) -> float:
    tot = 0.0
    for i, ex in picks:
        r = df.loc[i]
        tr = L26.simulate(ds[r.day], r.strike, 1 if r.side == "C" else -1, int(r.t), float(r.price), ex, K27.BUDGET, mult=2.0)
        tot += tr.net if tr else 0.0
    return tot


def main():
    df = load()
    days_all = sorted(D.load(K27.UND, "all"), key=lambda d: d.day)
    ds = {d.day: d for d in days_all}
    test_days = [d for d in days_all if d.day.year in K27.TEST_YEARS]
    wf = walk_forward(df)
    fam_prev = json.loads((K27.ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    res = {}
    for rid, w in wf.items():
        picks = w["rows"]
        m = metrics(df, picks, test_days)
        tw = twins(df, picks, rid)
        p = float((tw >= m["net"] - 1e-9).mean()) if len(picks) else 1.0
        years = {}
        for y in K27.TEST_YEARS:
            pk = [(i, ex) for i, ex in picks if df.loc[i, "year"] == y]
            years[str(y)] = metrics(df, pk, [d for d in test_days if d.day.year == y])
        bands = {}
        for b in ("1-10", "10-15", "15-30"):
            pk = [(i, ex) for i, ex in picks if df.loc[i, "band"] == b]
            bands[b] = {"trades": len(pk), "net": round(sum(float(df.loc[i, f'y_net_{ex}']) for i, ex in pk), 0)}
        res[rid] = {"m": m, "years": years, "bands": bands, "choices": {str(k): v for k, v in w["choices"].items()},
                    "twins": {"p": round(p, 4), "mean": round(float(tw.mean()), 0), "p05": round(float(np.percentile(tw, 5)), 0),
                              "p95": round(float(np.percentile(tw, 95)), 0)},
                    "x2_spread": round(x2_spread(df, picks, ds), 0),
                    "trades_list": [dict(day=str(df.loc[i, "day"]), t=int(df.loc[i, "t"]), side=df.loc[i, "side"],
                                         strike=float(df.loc[i, "strike"]), price=float(df.loc[i, "price"]), exit=ex,
                                         net=round(float(df.loc[i, f"y_net_{ex}"]), 0), peak=round(float(df.loc[i, "y_peak"]), 2))
                                    for i, ex in picks]}
        print(rid, m["trades"], m["net"], "p", round(p, 3), flush=True)
    fam = {k: v["p"] for k, v in fam_prev.items()} | {f"R34-{k}": v["twins"]["p"] for k, v in res.items()}
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for k, v in res.items():
        v["q"] = round(qs[f"R34-{k}"], 4)
        m, ys = v["m"], v["years"]
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 >= 3 of 4 years > 0": sum(ys[y]["net"] > 0 for y in ys) >= 3, "4 BH q < 0.05": v["q"] < 0.05,
             "5 x2 spread > 0": v["x2_spread"] > 0, "6 w/o best day > 0": m["net_without_best_day"] > 0,
             "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    # descriptive: mean net per Rs 1,000 by iv_rv and by band, every row (all years)
    desc = {}
    df["ivrv_bin"] = pd.cut(df.iv_rv, [0, 0.8, 0.9, 1.0, 1.2, 1.5, 2, 99])
    for ex in EXITS:
        g = df.groupby(["band", "ivrv_bin"], observed=True)[f"y_p1000_{ex}"].agg(["count", "mean"]).round(1)
        desc[ex] = {f"{a} | {b}": {"n": int(r["count"]), "per1000": float(r["mean"])} for (a, b), r in g.iterrows()}
    out = {"rows": len(df), "days": int(df.day.nunique()), "test_days": len(test_days), "bh_size": len(fam),
           "bh_min_q": round(min(qs.values()), 4), "rules": res, "desc_ivrv": desc,
           "base_per1000": {ex: round(float(df[f'y_p1000_{ex}'].mean()), 1) for ex in EXITS}}
    (K27.RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH", len(fam), "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
