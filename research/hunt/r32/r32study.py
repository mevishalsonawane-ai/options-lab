"""R32 analyses (PREREG.md): the event study, the walk-forward model, the mined patterns, the four trading rules.

    python3 research/hunt/r32/r32study.py      # -> results/results.json (needs <cache>/r32/rows.parquet)
"""
from __future__ import annotations

import json
import math
import sys
from itertools import combinations
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier
from sklearn.metrics import roc_auc_score

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
for p in (ROOT / "research" / "hunt" / "r31", HERE):
    if str(p) in sys.path:
        sys.path.remove(str(p))
    sys.path.insert(0, str(p))

import r31trail as TR  # noqa: E402  (puts r25-r30 on the path)
import r32feat as X  # noqa: E402
import r25data as D  # noqa: E402
import r25lib as R  # noqa: E402
import r25rules as RU  # noqa: E402
import r26lib as L26  # noqa: E402

RESULTS = HERE / "results"
TEST_YEARS = (2023, 2024, 2025, 2026)
META = ("und", "day", "t", "strike", "side", "y_peak", "year", "band")
HGB = dict(max_iter=300, learning_rate=0.05, max_leaf_nodes=31, min_samples_leaf=500, l2_regularization=1.0, random_state=32)
QS = (0.999, 0.9995, 0.9999)
TWINS, SEED = 1000, 32
FAMILIES = {
    "candles (2/3/4 patterns)": ["op_green2", "op_green3", "op_green4", "op_hh", "op_hl", "op_inside", "op_engulf", "op_rise3", "op_body",
                                 "op_comp", "op_pos30"],
    "option momentum": ["op_r1", "op_r2", "op_r3", "op_r5", "op_r10", "op_r15", "op_r30"],
    "option volume": ["op_vol1", "op_vol5_30", "op_vol12"],
    "option OI": ["op_oi1", "op_oi5", "op_oi15", "op_oi30"],
    "option IV": ["op_iv5", "op_iv15"],
    "EMA / Supertrend / VWAP (option)": ["op_ema", "op_emax", "op_st7", "op_st7_age", "op_st10", "op_st10_age", "op_vwap"],
    "index trend (returns, EMA, ADX, TWAP)": ["ix_r1", "ix_r5", "ix_r15", "ix_r30", "ix_ema", "ix_adx", "ix_twap", "ix_rngpos",
                                               "ix_since_ext", "ix_open", "ix_first30", "ix_gap"],
    "index volatility / VIX": ["ix_rv15", "ix_rv60", "ix_rvx", "vix", "vix_ch", "atm_iv", "st_ch15"],
    "chain OI (PCR, walls, max pain)": ["pcr", "pcr_ch15", "wall_em", "wall_oi", "maxpain"],
    "gamma exposure": ["gex_s", "gex_n"],
    "pricing (iv_rv, distance, time, price)": ["iv_rv", "rv_fc", "dist_em", "mins_left", "price"],
    "heavyweights": ["hv_r1", "hv_r5", "hv_agree"],
}
FEATS = [f for v in FAMILIES.values() for f in v]
DAYS: dict = {}


def day(und, d):
    if und not in DAYS:
        DAYS[und] = {x.day: x for x in D.load(und, "all")}
    return DAYS[und][d]


def load() -> pd.DataFrame:
    df = pd.read_parquet(X.OUT / "rows.parquet")
    df["day"] = pd.to_datetime(df["day"]).dt.date
    df["year"] = [d.year for d in df.day]
    df["band"] = pd.cut(df.price, [0, 3, 5, 10, 15, 30.001], labels=["1-3", "3-5", "5-10", "10-15", "15-30"]).astype(str)
    for c in FEATS:
        df[c] = df[c].replace([np.inf, -np.inf], np.nan).astype(np.float32)
    df["y10"] = df.y_peak >= 10
    df["y4"] = df.y_peak >= 4
    df["y2"] = df.y_peak >= 2
    return df


# ------------------------------------------------------------------------------------------------ 1. event study
PATTERNS = {
    "2 green candles in a row": lambda d: d.op_green2 >= 2,
    "3 green candles in a row": lambda d: d.op_green3 >= 3,
    "4 green candles in a row": lambda d: d.op_green4 >= 4,
    "3 rising closes": lambda d: d.op_rise3 >= 1,
    "2+ higher highs": lambda d: d.op_hh >= 2,
    "inside bar": lambda d: d.op_inside >= 1,
    "bullish engulfing": lambda d: d.op_engulf >= 1,
    "volume >= 2x its EMA10": lambda d: d.op_vol1 >= 2,
    "both Supertrends green": lambda d: (d.op_st7 == 1) & (d.op_st10 == 1),
    "option EMA9 > EMA21": lambda d: d.op_ema > 0,
    "option above its VWAP": lambda d: d.op_vwap > 0,
    "strike OI falling (15 min)": lambda d: d.op_oi15 < 0,
    "strike OI rising (15 min)": lambda d: d.op_oi15 > 0,
    "index EMA9 > EMA21 toward option": lambda d: d.ix_ema > 0,
    "index beyond TWAP toward option": lambda d: d.ix_twap > 0,
    "index ADX > 25": lambda d: d.ix_adx > 25,
    "all 3 heavyweights agree (1 min)": lambda d: d.hv_agree >= 3,
    "option cheap vs move (iv_rv <= 1)": lambda d: d.iv_rv <= 1,
    "wall within 1 expected move": lambda d: (d.wall_em >= 0) & (d.wall_em <= 1),
    "GEX_short above its noon value": lambda d: d.gex_s > 1,
    "range compressed (5 vs 30 bars < 0.3)": lambda d: d.op_comp < 0.3,
}


def event_study(df: pd.DataFrame) -> dict:
    y = df[df.y10]
    idx = y.groupby(["und", "day", "strike", "side"]).price.idxmin()
    L = df.loc[idx.values]
    ctrl_parts = []
    pool = df[~df.y2]
    keyed = {k: g for k, g in pool.groupby(["und", "day", "band"])}
    for _, r in L.iterrows():
        g = keyed.get((r.und, r.day, r.band))
        if g is not None:
            ctrl_parts.append(g[(g.t - r.t).abs() <= 2])
    Cn = pd.concat(ctrl_parts).drop_duplicates() if ctrl_parts else pool.iloc[:0]
    smd = {}
    for f in FEATS:
        a, b = L[f].astype(float), Cn[f].astype(float)
        s = math.sqrt((a.var() + b.var()) / 2) if a.notna().sum() > 2 and b.notna().sum() > 2 else np.nan
        smd[f] = round(float((a.mean() - b.mean()) / s), 3) if s and s > 0 else None
    pats = {}
    for name, fn in PATTERNS.items():
        ra, rb = float(fn(L).mean()), float(fn(Cn).mean())
        pats[name] = {"launch_pct": round(100 * ra, 1), "control_pct": round(100 * rb, 1), "lift": round(ra / rb, 2) if rb > 0 else None}
    paths = path_profiles(L, Cn)
    top = sorted(((k, v) for k, v in smd.items() if v is not None), key=lambda kv: -abs(kv[1]))
    return {"launches": int(len(L)), "launch_days": int(L.groupby(["und", "day"]).ngroups), "controls": int(len(Cn)),
            "by_band": L.band.value_counts().to_dict(), "by_index": L.und.value_counts().to_dict(),
            "smd_top20": top[:20], "smd": smd, "patterns": pats, "paths": paths,
            "launch_time_median": int(L.t.median())}


def path_profiles(L: pd.DataFrame, Cn: pd.DataFrame, n: int = 30) -> dict:
    """Mean price and volume over the 30 bars before the row, each / its value at the row (launch vs control)."""
    rng = np.random.default_rng(SEED)
    cs = Cn.iloc[rng.choice(len(Cn), size=min(len(Cn), 3000), replace=False)] if len(Cn) else Cn
    out = {}
    for name, frame in (("launch", L), ("control", cs)):
        P, V = [], []
        for _, r in frame.iterrows():
            a = day(r.und, r.day).opts[(r.strike, r.side)]
            c = X.ffill(a[:, 3])[r.t - n: r.t + 1]
            v = np.nan_to_num(a[:, 4])[r.t - n: r.t + 1]
            if len(c) == n + 1 and np.isfinite(c[-1]) and c[-1] > 0:
                P.append(c / c[-1])
                V.append(v / max(v[-10:].mean(), 1))
        P, V = np.array(P), np.array(V)
        out[name] = {"price_vs_now": [round(float(x), 3) for x in np.nanmean(P, axis=0)[::5]],
                     "volume_vs_last10": [round(float(x), 2) for x in np.nanmean(V, axis=0)[::5]], "n": int(len(P))}
    out["bars"] = list(range(-n, 1, 5))
    return out


# ------------------------------------------------------------------------------------------------ 2. model
def fit(train: pd.DataFrame) -> HistGradientBoostingClassifier:
    m = HistGradientBoostingClassifier(**HGB)
    m.fit(train[FEATS].values, train.y10.values)
    return m


def model_report(df: pd.DataFrame) -> tuple[dict, dict]:
    rep, scores = {}, {}
    for y in TEST_YEARS:
        tr, te = df[df.year < y], df[df.year == y]
        m = fit(tr)
        p = m.predict_proba(te[FEATS].values)[:, 1]
        scores[y] = (m, p)
        base = float(te.y10.mean())
        o = np.argsort(-p)
        top1 = te.y10.values[o[: max(1, len(o) // 100)]].mean()
        top01 = te.y10.values[o[: max(1, len(o) // 1000)]].mean()
        rep[str(y)] = {"rows": int(len(te)), "base_y10_pct": round(100 * base, 2), "auc": round(float(roc_auc_score(te.y10, p)), 3),
                       "top1pct_y10_pct": round(100 * float(top1), 2), "top01pct_y10_pct": round(100 * float(top01), 2),
                       "lift_top1pct": round(float(top1) / base, 2) if base > 0 else None}
    # permutation importance by family on the last fold
    m, p = scores[TEST_YEARS[-1]]
    te = df[df.year == TEST_YEARS[-1]]
    auc0 = roc_auc_score(te.y10, p)
    rng = np.random.default_rng(SEED)
    imp = {}
    for fam, cols in FAMILIES.items():
        Xs = te[FEATS].values.copy()
        perm = rng.permutation(len(te))
        for c in cols:
            j = FEATS.index(c)
            Xs[:, j] = Xs[perm, j]
        imp[fam] = round(float(auc0 - roc_auc_score(te.y10, m.predict_proba(Xs)[:, 1])), 4)
    rep["family_importance_auc_drop_2026"] = dict(sorted(imp.items(), key=lambda kv: -kv[1]))
    return rep, scores


# ------------------------------------------------------------------------------------------------ 3. mined patterns
def mine(train: pd.DataFrame) -> tuple:
    """Best 2- or 3-feature tercile conjunction by y10 lift (>= 2,000 rows on >= 50 expiries) on the training rows."""
    cuts = {f: np.nanquantile(train[f].astype(float), [1 / 3, 2 / 3]) for f in FEATS}
    codes = {f: np.where(train[f].isna(), -1, np.digitize(train[f].astype(float), cuts[f])) for f in FEATS}
    y = train.y10.values
    base = y.mean()
    daykey = (train.und.astype(str) + train.day.astype(str)).values
    best = []
    for a, b in combinations(FEATS, 2):
        key = codes[a] * 3 + codes[b]
        ok = (codes[a] >= 0) & (codes[b] >= 0)
        cnt = np.bincount(key[ok], minlength=9)
        pos = np.bincount(key[ok], weights=y[ok], minlength=9)
        for k in range(9):
            if cnt[k] >= 2000:
                best.append((pos[k] / cnt[k] / base, ((a, k // 3), (b, k % 3)), int(cnt[k])))
    best.sort(key=lambda x: -x[0])
    cands = [b for b in best[:60]]
    trip = []
    for lift, conds, n in cands[:30]:
        mask = np.ones(len(train), bool)
        for f, bin_ in conds:
            mask &= codes[f] == bin_
        for c in FEATS:
            if c in (conds[0][0], conds[1][0]):
                continue
            for bin_ in range(3):
                mm = mask & (codes[c] == bin_)
                n3 = int(mm.sum())
                if n3 >= 2000:
                    trip.append((y[mm].mean() / base, conds + ((c, bin_),), n3))
    allc = sorted(cands + trip, key=lambda x: -x[0])
    for lift, conds, n in allc:
        mask = np.ones(len(train), bool)
        for f, bin_ in conds:
            mask &= codes[f] == bin_
        if len(set(daykey[mask])) >= 50:
            return conds, cuts, round(float(lift), 2), n
    return None, cuts, None, 0


def match(df: pd.DataFrame, conds, cuts) -> np.ndarray:
    mask = np.ones(len(df), bool)
    for f, bin_ in conds:
        x = df[f].astype(float).values
        code = np.where(np.isnan(x), -1, np.digitize(x, cuts[f]))
        mask &= code == bin_
    return mask


# ------------------------------------------------------------------------------------------------ 4. trading
def first_rows(df: pd.DataFrame, mask: np.ndarray, score: np.ndarray) -> list[int]:
    out = []
    sub = df[mask].assign(_s=score[mask])
    for _, g in sub.groupby(["und", "day"], sort=True):
        t0 = g.t.min()
        h = g[g.t == t0]
        out.append(int(h._s.idxmax()))
    return out


def trade(df: pd.DataFrame, i: int, ex: str, mult: float = 1.0):
    r = df.loc[i]
    dy = day(r.und, r.day)
    sgn = 1 if r.side == "C" else -1
    if ex == "TR25":
        return TR.simulate(dy, float(r.strike), sgn, int(r.t), float(r.price), 2000.0, mult=mult)
    return L26.simulate(dy, float(r.strike), sgn, int(r.t), float(r.price), "E1", 2000.0, mult)


def net_per1000(df, idx, ex):
    trs = [trade(df, i, ex) for i in idx]
    trs = [t for t in trs if t]
    risk = sum(t.risked for t in trs)
    return (sum(t.net for t in trs) / risk * 1000 if risk else -math.inf), len(trs)


def choose_q(df: pd.DataFrame, y: int, ex: str) -> tuple[float, float]:
    """Inner split: a model fit on years < y-1 scores year y-1 (out of sample). Amendment 1 (PREREG.md): tau = the q-quantile
    of those OUT-OF-SAMPLE scores (in-sample training scores sat far above any test score: 0 trades). The q giving the best
    net per Rs 1,000 on year y-1 (>= 15 trades) is kept; else the largest q. Returns (q, tau)."""
    inner_tr, val = df[df.year < y - 1], df[df.year == y - 1]
    if inner_tr.empty or val.empty:
        return QS[-1], math.inf
    m = fit(inner_tr)
    s_val = m.predict_proba(val[FEATS].values)[:, 1]
    best = None
    for q in QS:
        tau = float(np.quantile(s_val, q))
        idx = first_rows(val, s_val > tau, s_val)
        v, n = net_per1000(val, idx, ex)
        if n >= 15 and (best is None or v > best[0]):
            best = (v, q, tau)
    if best:
        return best[1], best[2]
    return QS[-1], float(np.quantile(s_val, QS[-1]))


def metrics(df, picks: list) -> dict:
    trs = []
    for i, ex in picks:
        t = trade(df, i, ex)
        if t:
            trs.append(t)
    class _R:
        unds = X.UNDS
    test_days = [d for u in X.UNDS for d in DAYS[u].values() if d.day.year in TEST_YEARS] if DAYS else []
    m = RU.metrics(trs, test_days, _R())
    m["x10_pct"] = round(100 * float(np.mean([t.peak_mult >= 10 for t in trs])), 1) if trs else 0.0
    m["x4_pct"] = round(100 * float(np.mean([t.peak_mult >= 4 for t in trs])), 1) if trs else 0.0
    m["years"] = {str(y): round(sum(t.net for t in trs if t.day.year == y), 0) for y in TEST_YEARS}
    m["x2_spread"] = round(sum((trade(df, i, ex, 2.0).net if trade(df, i, ex, 2.0) else 0.0) for i, ex in picks), 0)
    return m, trs


def twins(df: pd.DataFrame, picks: list, n: int = TWINS) -> np.ndarray:
    rng = np.random.default_rng(SEED)
    by = {k: g for k, g in df.groupby(["und", "day"])}
    pools = []
    for i, ex in picks:
        g = by[(df.loc[i, "und"], df.loc[i, "day"])]
        pools.append((g, np.array(sorted(g.t.unique())), ex))
    cache = {}
    out = np.zeros(n)
    for k in range(n):
        tot = 0.0
        for g, ts, ex in pools:
            t = ts[rng.integers(len(ts))]
            h = g[g.t == t]
            j = int(h.index[rng.integers(len(h))])
            key = (j, ex)
            if key not in cache:
                tr = trade(df, j, ex)
                cache[key] = tr.net if tr else 0.0
            tot += cache[key]
        out[k] = tot
    return out


def main():
    df = load()
    for u in X.UNDS:
        day(u, next(iter(df[df.und == u].day)))
    out = {"rows": int(len(df)), "days": {u: int(df[df.und == u].day.nunique()) for u in X.UNDS},
           "base_rates_pct": {k: round(100 * float(df[k].mean()), 2) for k in ("y2", "y4", "y10")}}
    out["event_study"] = event_study(df)
    print("event study done", out["event_study"]["launches"], flush=True)
    rep, scores = model_report(df)
    out["model"] = rep
    print(json.dumps(rep, indent=1), flush=True)
    picks = {"P1": [], "P2": [], "P3": [], "P4": []}
    choices = {}
    for y in TEST_YEARS:
        te = df[df.year == y]
        m, p = scores[y]
        tr = df[df.year < y]
        for rid, ex in (("P1", "TR25"), ("P2", "E1")):
            q, tau = choose_q(df, y, ex)
            idx = first_rows(te, p > tau, p)
            picks[rid] += [(i, ex) for i in idx]
            choices.setdefault(rid, {})[y] = {"q": q, "tau": round(tau, 4)}
        conds, cuts, lift, n = mine(tr)
        if conds:
            mm = match(te, conds, cuts)
            idx = first_rows(te, mm, p)
            picks["P3"] += [(i, "TR25") for i in idx]
            picks["P4"] += [(i, "E1") for i in idx]
            tl = float(te.y10.values[mm].mean() / te.y10.mean()) if mm.any() and te.y10.mean() > 0 else None
        else:
            tl = None
        choices.setdefault("pattern", {})[y] = {"conds": [(f, int(b)) for f, b in conds] if conds else None,
                                                "train_lift": lift, "train_rows": n, "test_lift": round(tl, 2) if tl else None,
                                                "test_rows": int(mm.sum()) if conds else 0}
        print(y, choices["pattern"][y], flush=True)
    out["choices"] = choices
    fam = json.loads((ROOT / "research/hunt/r31/results/results.json").read_text())
    from r31trail import earlier_family
    family = earlier_family() | {f"R31-{k}": v["p"] for k, v in fam["rules"].items()}
    res = {}
    for rid, pk in picks.items():
        m, trs = metrics(df, pk)
        tw = twins(df, pk)
        p_ = float((tw >= m["net"] - 1e-9).mean()) if pk else 1.0
        family[f"R32-{rid}"] = p_
        res[rid] = {"m": m, "twins": {"p": round(p_, 4), "mean": round(float(tw.mean()), 0)},
                    "trades_list": [dict(und=t.und, day=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike, sig=t.sig_row,
                                         entry=t.entry, peak=round(t.peak_mult, 2), net=round(t.net, 0)) for t in trs]}
        print(rid, m["trades"], m["net"], "p", round(p_, 3), flush=True)
    qs = dict(zip(family, RU.bh(list(family.values()))))
    for rid, v in res.items():
        v["q"] = round(qs[f"R32-{rid}"], 4)
        m = v["m"]
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0,
             "3 >= 3 of 4 years > 0": sum(x > 0 for x in m["years"].values()) >= 3, "4 BH q < 0.05": v["q"] < 0.05,
             "5 x2 spread > 0": m["x2_spread"] > 0, "6 w/o best day > 0": m["net_without_best_day"] > 0,
             "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    out["rules"] = res
    out["bh_size"], out["bh_min_q"] = len(family), round(min(qs.values()), 4)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "results.json").write_text(json.dumps(out, indent=1, default=str))
    print("BH", len(family), "min q", out["bh_min_q"], "passes", [k for k, v in res.items() if v["passes"]])


if __name__ == "__main__":
    main()
