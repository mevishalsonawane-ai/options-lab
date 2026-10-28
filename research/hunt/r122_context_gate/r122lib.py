"""R122 loop engine (PREREG sections 3-4): variants, monthly walk-forward on design, the frozen fit for the final,
random-skip twins (analytic normal p + empirical 95th percentile) and the step-3 gates. 28 Oct 2026."""
from __future__ import annotations

import math
from collections import defaultdict
from datetime import date

import numpy as np

import r122feat as F

SEED = 122
TWINS = 20_000
EXCLUDE_POOLED = ("ORB_FRESH",)          # ORB's duplicate: shown per arm, never pooled or trained on
CORE = ("R72", "R51", "R52", "LIQ")
WF_MONTHS = [date(2025, m, 1) for m in range(4, 13)] + [date(2026, m, 1) for m in range(1, 4)]
DESIGN_START, DESIGN_END = date(2024, 11, 20), date(2026, 3, 31)
MODEL_FEATURES = tuple(f for f in F.FEATURES)


# ---------------------------------------------------------------- twins


def twin_stats(nets, k: int) -> tuple:
    """Mean and variance of the KEPT net when k of the n trades are skipped uniformly at random (no replacement)."""
    x = np.asarray(nets, float)
    n = len(x)
    if n == 0:
        return 0.0, 0.0
    total = x.sum()
    if k <= 0:
        return float(total), 0.0
    if k >= n:
        return 0.0, 0.0
    var_pop = x.var()                                  # population variance
    mean_kept = total * (n - k) / n
    var_removed = k * var_pop * (n - k) / (n - 1)
    return float(mean_kept), float(var_removed)


def twin_draws(nets, k: int, n: int = TWINS, seed: int = SEED) -> np.ndarray:
    x = np.asarray(nets, float)
    if k <= 0 or len(x) == 0:
        return np.full(n, x.sum())
    rng = np.random.default_rng(seed)
    out = np.empty(n)
    for i in range(0, n, 1000):
        m = min(1000, n - i)
        idx = np.argsort(rng.random((m, len(x))), axis=1)[:, :k]
        out[i:i + m] = x.sum() - x[idx].sum(axis=1)
    return out


def norm_p(z: float) -> float:
    return 0.5 * math.erfc(z / math.sqrt(2)) if math.isfinite(z) else (0.0 if z > 0 else 1.0)


def max_dd(trades: list, keep: list) -> float:
    by = defaultdict(float)
    for t, k in zip(trades, keep):
        by[t["day"]] += t["net"] if k else 0.0
    peak = cum = worst = 0.0
    for d in sorted(by):
        cum += by[d]
        peak = max(peak, cum)
        worst = max(worst, peak - cum)
    return worst


def evaluate(trades: list, keep: list, draws: bool = False, seed: int = SEED) -> dict:
    """Per arm and pooled (without ORB_FRESH): trades, skipped, net before / after, win before / after, max DD, twin
    mean / sd, z, normal p (and the empirical twin 95th percentile when draws)."""
    by = defaultdict(list)
    for t, k in zip(trades, keep):
        by[t["arm"]].append((t, k))
    arms, pm, pv, pb, pa, pdraw = {}, 0.0, 0.0, 0.0, 0.0, None
    pooled_t, pooled_k = [], []
    for i, (arm, xs) in enumerate(sorted(by.items())):
        ts = [t for t, _ in xs]
        ks = [k for _, k in xs]
        nets = np.array([t["net"] for t in ts])
        kept = nets[np.array(ks, bool)] if len(ks) else nets
        skipped = len(ts) - int(np.sum(ks))
        mean, var = twin_stats(nets, skipped)
        z = (kept.sum() - mean) / math.sqrt(var) if var > 0 else float("nan")
        a = dict(trades=len(ts), skipped=skipped, net_before=round(float(nets.sum()), 2),
                 net_after=round(float(kept.sum()), 2), win_before=_win(nets), win_after=_win(kept),
                 dd_before=round(max_dd(ts, [True] * len(ts)), 2), dd_after=round(max_dd(ts, ks), 2),
                 twin_mean=round(mean, 2), twin_sd=round(math.sqrt(var), 2), z=_r(z), p=_r(norm_p(z)) if var > 0 else 1.0)
        if draws:
            dr = twin_draws(nets, skipped, seed=seed + i)
            a["twin_p95"] = round(float(np.percentile(dr, 95)), 2)
            if arm not in EXCLUDE_POOLED:
                pdraw = dr if pdraw is None else pdraw + dr
        arms[arm] = a
        if arm in EXCLUDE_POOLED:
            continue
        pm += mean; pv += var; pb += nets.sum(); pa += kept.sum()
        pooled_t += ts; pooled_k += ks
    z = (pa - pm) / math.sqrt(pv) if pv > 0 else float("nan")
    pooled = dict(trades=len(pooled_t), skipped=len(pooled_t) - int(sum(pooled_k)), net_before=round(pb, 2),
                  net_after=round(pa, 2), uplift=round(pa - pm, 2), twin_sd=round(math.sqrt(pv), 2), z=_r(z),
                  p=(_r(norm_p(z)) if pv > 0 else 1.0), dd_before=round(max_dd(pooled_t, [True] * len(pooled_t)), 2),
                  dd_after=round(max_dd(pooled_t, pooled_k), 2))
    if draws and pdraw is not None:
        pooled["twin_p95"] = round(float(np.percentile(pdraw, 95)), 2)
        pooled["p_empirical"] = round(float((1 + np.sum(pdraw >= pa - 0.01)) / (1 + len(pdraw))), 6)
    return dict(arms=arms, pooled=pooled)


def _win(x):
    return round(float(np.mean(np.asarray(x) > 0)), 4) if len(x) else None


def _r(x):
    return None if x is None or not math.isfinite(x) else round(float(x), 6)


# ---------------------------------------------------------------- variants

def X(trades, cols=MODEL_FEATURES) -> np.ndarray:
    m = np.array([[t[c] for c in cols] for t in trades], float)
    arms = sorted({a for a in F.ARMS if a not in EXCLUDE_POOLED})
    oh = np.array([[1.0 if t["arm"] == a or (t["arm"] == "ORB_FRESH" and a == "ORB_ORB") else 0.0 for a in arms]
                   for t in trades])
    return np.hstack([m, oh])


def rule_mask(name: str, train: list, test: list) -> list:
    """Keep (True) / skip (False) for a single round-1 rule; train is used only for R3's VIX cut."""
    def keep(f):
        return [not f(t) for t in test]
    if name == "R1_TREND":
        return keep(lambda t: t["trend20"] < 0)
    if name == "R2_INTRADAY":
        return keep(lambda t: t["move_open"] < 0)
    if name == "R3_VIX_HIGH":
        v = sorted({(t["day"], t["vix_prev"]) for t in train if math.isfinite(t["vix_prev"])})
        cut = np.percentile([x for _, x in v], 200 / 3) if v else math.inf
        return keep(lambda t: t["vix_prev"] > cut)
    if name == "R4_GAP_AGAINST":
        return keep(lambda t: t["gap"] < -0.25)
    if name == "R5_OPPOSED":
        return keep(lambda t: t["oppose60"] > 0)
    if name == "R6_SUPERTREND":
        return keep(lambda t: t["st5"] < 0)
    if name == "R7_EMA":
        return keep(lambda t: t["ema5"] < 0)
    if name == "R8_CHASE":
        return keep(lambda t: t["move_open"] > 1.0)
    if name == "R9_TM_SETUP":
        return keep(lambda t: t["setup_n"] >= 10 and t["setup_r"] < 0)
    raise KeyError(name)


def model_scores(kind: str, train: list, test: list, cols=MODEL_FEATURES):
    """Predicted win probability for train (in-sample, kNN leaving each trade out) and test."""
    ytr = np.array([t["net"] > 0 for t in train], int)
    xtr, xte = X(train, cols), X(test, cols)
    med = np.nanmedian(xtr, axis=0)
    med = np.where(np.isfinite(med), med, 0.0)
    fill = lambda a: np.where(np.isfinite(a), a, med)  # noqa: E731
    if kind == "L3":
        from sklearn.ensemble import HistGradientBoostingClassifier
        m = HistGradientBoostingClassifier(max_depth=3, max_iter=200, learning_rate=0.05, min_samples_leaf=40,
                                           random_state=SEED).fit(xtr, ytr)
        return m.predict_proba(xtr)[:, 1], m.predict_proba(xte)[:, 1]
    a, b = fill(xtr), fill(xte)
    mu, sd = a.mean(0), a.std(0)
    sd = np.where(sd > 0, sd, 1.0)
    a, b = (a - mu) / sd, (b - mu) / sd
    if kind == "L1":
        from sklearn.linear_model import LogisticRegression
        m = LogisticRegression(C=0.1, max_iter=2000).fit(a, ytr)
        return m.predict_proba(a)[:, 1], m.predict_proba(b)[:, 1]
    if kind == "L2":
        from sklearn.neighbors import NearestNeighbors
        k = 50
        nn = NearestNeighbors(n_neighbors=k + 1).fit(a)
        _, itr = nn.kneighbors(a)
        ptr = ytr[itr[:, 1:]].mean(1)                     # leave itself out
        _, ite = nn.kneighbors(b, n_neighbors=k)
        return ptr, ytr[ite].mean(1)
    raise KeyError(kind)


def learned_mask(kind: str, train: list, test: list, share: float = 0.20, cols=MODEL_FEATURES) -> list:
    """Skip the test trades whose score is below their arm's `share` quantile of the training scores."""
    ptr, pte = model_scores(kind, train, test, cols)
    cut = {}
    for arm in {t["arm"] for t in test}:
        a = "ORB_ORB" if arm == "ORB_FRESH" else arm
        s = ptr[np.array([t["arm"] == a for t in train])]
        cut[arm] = np.quantile(s if len(s) >= 30 else ptr, share)
    return [bool(p >= cut[t["arm"]]) for t, p in zip(test, pte)]


def variant_mask(spec: dict, train: list, test: list) -> list:
    """spec: dict(kind='rule', rules=[...]) (skip if ANY rule skips) or dict(kind='L1'|'L2'|'L3', share, cols)."""
    train = [t for t in train if t["arm"] not in EXCLUDE_POOLED]
    if spec["kind"] == "rule":
        keep = [True] * len(test)
        for r in spec["rules"]:
            keep = [a and b for a, b in zip(keep, rule_mask(r, train, test))]
        return keep
    return learned_mask(spec["kind"], train, test, spec.get("share", 0.20), tuple(spec.get("cols", MODEL_FEATURES)))


def walk_forward(spec: dict, design: list) -> list:
    """Keep masks for the walk-forward months: each month predicted by a fit on design trades before it."""
    keep = {}
    for i, m0 in enumerate(WF_MONTHS):
        m1 = WF_MONTHS[i + 1] if i + 1 < len(WF_MONTHS) else date(2026, 4, 1)
        test = [t for t in design if m0 <= t["day"] < m1]
        train = [t for t in design if t["day"] < m0]
        if not test:
            continue
        for t, k in zip(test, variant_mask(spec, train, test)):
            keep[id(t)] = k
    wf = [t for t in design if id(t) in keep]
    return wf, [keep[id(t)] for t in wf]


def step3_gates(wf: dict, fin: dict, q: float) -> dict:
    fails = []
    if not wf["pooled"]["net_after"] > wf["pooled"]["net_before"]:
        fails.append("walk-forward: pooled net not above the arms' own")
    if not fin["pooled"]["net_after"] > fin["pooled"]["net_before"]:
        fails.append("final: pooled net not above the arms' own")
    if not (fin["pooled"]["net_after"] > fin["pooled"].get("twin_p95", math.inf) and (fin["pooled"]["p"] or 1) <= 0.05):
        fails.append(f"final: not above the twin's 95th percentile with p <= 0.05 (p {fin['pooled']['p']})")
    if not q <= 0.10:
        fails.append(f"BH q {q:.3g} > 0.10")
    small = [a for a, x in fin["arms"].items() if x["net_after"] > x["net_before"] and x["trades"] - x["skipped"] < 40]
    if small:
        fails.append("fewer than 40 final trades kept on " + ", ".join(small))
    if fin["pooled"]["dd_after"] > fin["pooled"]["dd_before"]:
        fails.append("final: pooled drawdown worse")
    up = sum(1 for a in CORE if a in fin["arms"] and fin["arms"][a]["net_after"] >= fin["arms"][a]["net_before"])
    if up < 2:
        fails.append(f"final: net held on only {up} of the 4 paper index arms")
    return dict(passed=not fails, fails=fails)
