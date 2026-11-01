"""R144d: loss anatomy and pre-registered skip filters for S5 Trail, CISD + network and the four MCX arms (PREREG.md).

    python research/hunt/r144d_loss_nn_mcx/r144d.py design   # reads ONLY design (D) trades -> results/filters_frozen.json
    python research/hunt/r144d_loss_nn_mcx/r144d.py test     # needs the committed filters_frozen.json -> anatomy + tests

Inputs: $R144D_OUT/trades_<ARM>.parquet (nn_arms.py, mcx_arms.py): one row per trade, `net` (Rs, 1 lot, after charges
and slippage), `day` (entry day), optional `exit_ts`, `exit_why`, and features `f_*` read before the entry.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
CACHE = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R144D_OUT", CACHE / "r144d")).expanduser()
RESULTS = HERE / "results"
ARMS = ("S5", "CISD", "NGEVE", "SILM", "TREND", "USSIL")
LABEL = {"S5": "S5 Trail", "CISD": "CISD + network", "NGEVE": "NG evening breakout", "SILM": "SILVERM morning",
         "TREND": "MCX trend (12-month)", "USSIL": "US-night silver"}
PERIODS = {"U": (date(2022, 12, 1), date(2024, 11, 19)), "D": (date(2024, 11, 20), date(2026, 3, 31)),
           "F": (date(2026, 4, 1), date(2026, 10, 19))}
D_HALF = date(2025, 3, 31)
TWINS = 2000
SEED = 144
MIN_N = 30
MAX_SKIP, MIN_SKIP, HALF_MIN = 0.40, 10, 5
CAT_MAX = 6                                   # a feature with at most this many distinct values is categorical


# ------------------------------------------------------------------------------------------------ basics
def period_of(d) -> str | None:
    for k, (a, b) in PERIODS.items():
        if a <= d <= b:
            return k
    return None


def load(arm: str) -> pd.DataFrame | None:
    p = OUT / f"trades_{arm}.parquet"
    if not p.exists():
        return None
    T = pd.read_parquet(p)
    T["day"] = pd.to_datetime(T.day).dt.date
    if "exit_ts" in T and T.exit_ts.notna().any():
        T["pday"] = pd.to_datetime(T.exit_ts).dt.date.where(T.exit_ts.notna(), T.day)
    else:
        T["pday"] = T.day
    T["period"] = [period_of(d) for d in T.day]
    order = ["day", "r0"] if "r0" in T else (["entry_ts"] if "entry_ts" in T else ["day"])
    return T.sort_values(order, kind="stable").reset_index(drop=True)


def features(T: pd.DataFrame) -> list:
    return [c for c in T.columns if c.startswith("f_") and pd.api.types.is_numeric_dtype(T[c])]


def money(net: np.ndarray, pday: np.ndarray) -> dict:
    """Net, win rate, the sum of losing days, drawdown, worst day, worst month (by the day the P&L was realised)."""
    if len(net) == 0:
        return dict(n=0, net=0.0, win=float("nan"), losing_days=0.0, max_dd=0.0, worst_day=0.0, worst_month=0.0)
    s = pd.Series(net, index=pd.to_datetime(pday))
    dayp = s.groupby(level=0).sum().sort_index()
    eq = dayp.cumsum()
    dd = float((eq.cummax().clip(lower=0) - eq).max())
    mon = dayp.groupby(dayp.index.to_period("M")).sum()
    return dict(n=int(len(net)), net=float(net.sum()), win=float((net > 0).mean()),
                losing_days=float(dayp[dayp < 0].sum()), max_dd=dd, worst_day=float(dayp.min()),
                worst_month=float(mon.min()))


def losing_days(net: np.ndarray, pday_codes: np.ndarray, ndays: int) -> float:
    s = np.bincount(pday_codes, weights=net, minlength=ndays)
    return float(s[s < 0].sum())


def bh(ps: list) -> list:
    p = np.asarray(ps, float)
    n = len(p)
    if n == 0:
        return []
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out.tolist()


# ------------------------------------------------------------------------------------------------ buckets (design cuts)
def cuts_for(x: pd.Series) -> dict:
    v = x.dropna().values
    if len(v) == 0:
        return dict(kind="empty")
    u = np.unique(v)
    if len(u) <= CAT_MAX:
        return dict(kind="cat", levels=[float(a) for a in u])
    return dict(kind="q", cuts=[float(c) for c in np.quantile(v, [0.2, 0.4, 0.6, 0.8])])


def bucket(x: pd.Series, c: dict) -> pd.Series:
    if c["kind"] == "empty":
        return pd.Series(["missing"] * len(x), index=x.index)
    if c["kind"] == "cat":
        return x.map(lambda v: "missing" if pd.isna(v) else f"={v:g}")
    q = np.searchsorted(c["cuts"], x.values, side="left")
    lab = np.array([f"Q{i + 1}" for i in q], dtype=object)
    lab[x.isna().values] = "missing"
    return pd.Series(lab, index=x.index)


def skip_mask(T: pd.DataFrame, flt: dict) -> np.ndarray:
    """True = the filter skips this trade. A missing feature value is never skipped."""
    if flt["kind"] == "either":
        return skip_mask(T, flt["a"]) | skip_mask(T, flt["b"])
    x = T[flt["feature"]].values.astype(float)
    with np.errstate(invalid="ignore"):
        if flt["kind"] == "low":
            m = x <= flt["cut"]
        elif flt["kind"] == "high":
            m = x > flt["cut"]
        else:
            m = np.isclose(x, flt["level"])
    return m & np.isfinite(x)


# ------------------------------------------------------------------------------------------------ anatomy
def streak(net: np.ndarray) -> int:
    best = cur = 0
    for v in net:
        cur = cur + 1 if v < 0 else 0
        best = max(best, cur)
    return best


def anatomy(T: pd.DataFrame, cuts: dict, feats: list) -> dict:
    net = T.net.values
    lose = net < 0
    out = dict(money(net, T.pday.values))
    out["loser_rate"] = float(lose.mean()) if len(T) else float("nan")
    out["avg_loss"] = float(net[lose].mean()) if lose.any() else 0.0
    out["avg_win"] = float(net[~lose].mean()) if (~lose).any() else 0.0
    out["money_lost"] = float(net[lose].sum())
    if not len(T):
        return out
    if "exit_why" in T:
        g = T[lose].groupby("exit_why").net
        out["lost_by_exit"] = {k: dict(n=int(v.size), lost=round(float(v.sum()))) for k, v in g}
        a = T.groupby("exit_why").net
        out["by_exit"] = {k: dict(n=int(v.size), loser_rate=round(float((v < 0).mean()), 3), avg=round(float(v.mean())))
                          for k, v in a}
    dayp = T.groupby("pday").net
    all_lost = dayp.transform(lambda s: bool((s < 0).all()) and len(s) > 1)
    out["losers_on_all_lost_days"] = float(all_lost[lose].mean()) if lose.any() else 0.0
    out["multi_trade_days"] = int((dayp.size() > 1).sum())
    out["longest_losing_streak"] = streak(net)
    dp = dayp.sum().sort_values(ascending=False)
    out["best5_days_share_of_net"] = float(dp.head(5).sum() / net.sum()) if net.sum() > 0 else None
    out["by_year"] = {int(y): dict(n=int(v.size), net=round(float(v.sum())), win=round(float((v > 0).mean()), 3))
                      for y, v in T.groupby(pd.to_datetime(T.day).dt.year).net}
    if "mins_held" in T:
        h = pd.cut(T.mins_held, [-1, 15, 60, 180, 10 ** 7], labels=["<=15m", "15-60m", "1-3h", ">3h"])
        out["by_hold"] = {str(k): dict(n=int(v.size), loser_rate=round(float((v < 0).mean()), 3),
                                       avg=round(float(v.mean()))) for k, v in T.groupby(h, observed=True).net}
    w = T.nsmallest(10, "net")
    keep = ["day", "und", "side", "net", "exit_why", "mins_held"] + feats
    out["worst10"] = [{k: (str(r[k]) if k == "day" else (None if pd.isna(r[k]) else
                                                           (round(float(r[k]), 4) if isinstance(r[k], (int, float, np.floating, np.integer)) else str(r[k]))))
                       for k in keep if k in w} for _, r in w.iterrows()]
    q = {}
    for f in feats:
        b = bucket(T[f], cuts[f])
        q[f] = {k: dict(n=int(v.size), loser_rate=round(float((v < 0).mean()), 3), avg=round(float(v.mean())))
                for k, v in T.net.groupby(b)}
    out["buckets"] = q
    return out


def steady(anat: dict, feats: list) -> list:
    """Buckets that lose more than the arm and on average in EVERY period with >= 30 trades (>= 2 such periods)."""
    big = [p for p, a in anat.items() if a.get("n", 0) >= MIN_N]
    if len(big) < 2:
        return []
    out = []
    for f in feats:
        keys = set.intersection(*[set(anat[p]["buckets"].get(f, {})) for p in big])
        for k in sorted(keys):
            if k == "missing":
                continue
            rows = {p: anat[p]["buckets"][f][k] for p in big}
            if all(r["n"] >= 5 and r["loser_rate"] > anat[p]["loser_rate"] and r["avg"] < 0 for p, r in rows.items()):
                out.append(dict(feature=f, bucket=k, periods={p: r for p, r in rows.items()},
                                arm_loser_rate={p: round(anat[p]["loser_rate"], 3) for p in big}))
    return out


# ------------------------------------------------------------------------------------------------ filter choice (D only)
def candidates(D: pd.DataFrame, cuts: dict, feats: list) -> list:
    out = []
    for f in feats:
        c = cuts[f]
        if c["kind"] == "q":
            out += [dict(kind="low", feature=f, cut=c["cuts"][0]), dict(kind="high", feature=f, cut=c["cuts"][3])]
        elif c["kind"] == "cat":
            out += [dict(kind="level", feature=f, level=v) for v in c["levels"]]
    return out


def design_score(D: pd.DataFrame, flt: dict) -> dict | None:
    m = skip_mask(D, flt)
    k = int(m.sum())
    if k < MIN_SKIP or k > MAX_SKIP * len(D):
        return None
    if D.net.values[m].mean() >= 0:
        return None
    for half in (D.day <= D_HALF, D.day > D_HALF):
        mh = m & half.values
        if mh.sum() >= HALF_MIN and D.net.values[mh].mean() >= 0:
            return None
    codes, uniq = pd.factorize(D.pday)
    base = losing_days(D.net.values, codes, len(uniq))
    aft = losing_days(D.net.values[~m], codes[~m], len(uniq))
    return dict(skipped=k, skipped_avg=float(D.net.values[m].mean()), ld_cut_per_skip=(aft - base) / k,
                ld_base=base, ld_after=aft)


def choose(D: pd.DataFrame, feats: list) -> tuple[dict, list]:
    cuts = {f: cuts_for(D[f]) for f in feats}
    scored = []
    for c in candidates(D, cuts, feats):
        s = design_score(D, c)
        if s is not None:
            scored.append((s["ld_cut_per_skip"], c, s))
    scored.sort(key=lambda x: -x[0])
    chosen = []
    for _, c, s in scored:
        if all(c["feature"] != x["feature"] for x in chosen):
            chosen.append(dict(c, design=s))
        if len(chosen) == 2:
            break
    if len(chosen) == 2:
        e = dict(kind="either", a={k: v for k, v in chosen[0].items() if k != "design"},
                 b={k: v for k, v in chosen[1].items() if k != "design"})
        s = design_score(D, e)
        if s is not None:
            chosen.append(dict(e, design=s))
    return cuts, chosen


def describe(flt: dict) -> str:
    if flt["kind"] == "either":
        return f"skip if either: ({describe(flt['a'])}) or ({describe(flt['b'])})"
    if flt["kind"] == "low":
        return f"skip {flt['feature']} <= {flt['cut']:.4g} (design lowest fifth)"
    if flt["kind"] == "high":
        return f"skip {flt['feature']} > {flt['cut']:.4g} (design highest fifth)"
    return f"skip {flt['feature']} = {flt['level']:g}"


# ------------------------------------------------------------------------------------------------ tests (U and F)
def twin_p(parts: list, rng, n: int = TWINS) -> float:
    """parts: [(net, day codes, ndays, skip mask)] per period; random skips of the same count within each period.
    p = share of twins whose (pooled) losing-days sum is at least as small as the filter's."""
    obs = sum(losing_days(nt[~m], cd[~m], nd) for nt, cd, nd, m in parts)
    ge = 0
    for _ in range(n):
        tot = 0.0
        for nt, cd, nd, m in parts:
            k = int(m.sum())
            sk = np.zeros(len(nt), bool)
            if k:
                sk[rng.choice(len(nt), k, replace=False)] = True
            tot += losing_days(nt[~sk], cd[~sk], nd)
        ge += tot >= obs
    return float((1 + ge) / (1 + n))


def test_filter(T: pd.DataFrame, flt: dict, rng) -> dict:
    res, parts = {}, []
    for p in ("U", "D", "F"):
        X = T[T.period == p]
        m = skip_mask(X, flt)
        b, a = money(X.net.values, X.pday.values), money(X.net.values[~m], X.pday.values[~m])
        r = dict(before=b, after=a, skipped=int(m.sum()))
        if p != "D" and len(X):
            codes, uniq = pd.factorize(X.pday)
            r["twin_p"] = twin_p([(X.net.values, codes, len(uniq), m)], rng)
            if m.any():
                parts.append((X.net.values, codes, len(uniq), m))
            g = dict(n30=len(X) >= MIN_N, losing_days_smaller=a["losing_days"] > b["losing_days"],
                     dd_no_worse=a["max_dd"] <= b["max_dd"] + 1e-6, worst_day_no_worse=a["worst_day"] >= b["worst_day"] - 1e-6,
                     worst_month_no_worse=a["worst_month"] >= b["worst_month"] - 1e-6, still_makes_money=a["net"] > 0,
                     twin=r["twin_p"] <= 0.05)
            r["gates"] = g
        res[p] = r
    res["p_pooled"] = twin_p(parts, rng) if parts else 1.0
    return res


def verdict(r: dict, q: float) -> tuple[bool, list]:
    why = []
    big = [p for p in ("U", "F") if r[p]["before"]["n"] >= MIN_N]
    small = [p for p in ("U", "F") if r[p]["before"]["n"] < MIN_N]
    for p in small:
        why.append(f"{p}: too small ({r[p]['before']['n']} trades)")
    if not big:
        return False, why + ["no test period with >= 30 trades"]
    for p in big:
        why += [f"{p}: {k}" for k, v in r[p]["gates"].items() if not v and k != "n30"]
    if q > 0.10:
        why.append(f"q {q:.3f} > 0.10")
    return (len([w for w in why if "too small" not in w]) == 0), why


# ------------------------------------------------------------------------------------------------ commands
def cmd_design():
    out = {}
    for arm in ARMS:
        T = load(arm)
        if T is None:
            out[arm] = dict(available=False)
            continue
        D = T[T.period == "D"].reset_index(drop=True)              # nothing outside design is read below
        feats = features(D)
        if len(D) < 2 * MIN_SKIP:
            out[arm] = dict(available=True, design_n=len(D), filters=[], note="design too small for any filter")
            continue
        cuts, chosen = choose(D, feats)
        out[arm] = dict(available=True, design_n=len(D), cuts=cuts, filters=chosen,
                        filter_text=[describe(c) for c in chosen])
        print(arm, len(D), [describe(c) for c in chosen], flush=True)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "filters_frozen.json").write_text(json.dumps(out, indent=1, default=float))


def cmd_test():
    frozen = json.loads((RESULTS / "filters_frozen.json").read_text())
    rng = np.random.default_rng(SEED)
    out, plist = {}, []
    for arm in ARMS:
        T = load(arm)
        fz = frozen.get(arm, {})
        if T is None or not fz.get("available"):
            out[arm] = dict(available=False)
            continue
        feats = [f for f in features(T) if f in fz.get("cuts", {})] if "cuts" in fz else features(T)
        cuts = fz.get("cuts") or {f: cuts_for(T[T.period == "D"][f]) for f in feats}
        an = {p: anatomy(T[T.period == p], cuts, feats) for p in ("U", "D", "F")}
        an["D1"] = anatomy(T[(T.period == "D") & (T.day <= D_HALF)], cuts, feats)
        an["D2"] = anatomy(T[(T.period == "D") & (T.day > D_HALF)], cuts, feats)
        an["outside"] = dict(n=int(T.period.isna().sum()),
                             first=str(T.day.min()), last=str(T.day.max()))
        st = steady({p: an[p] for p in ("U", "D", "F")}, feats)
        tests = []
        for c in fz.get("filters", []):
            flt = {k: v for k, v in c.items() if k != "design"}
            r = test_filter(T, flt, rng)
            r["filter"], r["text"] = flt, describe(flt)
            tests.append(r)
            plist.append((arm, describe(flt), r))
        out[arm] = dict(available=True, anatomy=an, steady=st, tests=tests)
        print(arm, {p: an[p]["n"] for p in ("U", "D", "F")}, "steady", len(st), "tests", len(tests), flush=True)
    qs = bh([r["p_pooled"] for _, _, r in plist])
    pv = []
    for (arm, txt, r), q in zip(plist, qs):
        r["q"] = q
        r["passes"], r["why"] = verdict(r, q)
        pv.append(dict(arm=arm, filter=txt, p_pooled=r["p_pooled"], p_U=r["U"].get("twin_p"), p_F=r["F"].get("twin_p"),
                       q=q, passes=r["passes"]))
    (RESULTS / "r144d.json").write_text(json.dumps(out, indent=1, default=str))
    (RESULTS / "pvalues.json").write_text(json.dumps(dict(
        note="raw p-values of every R144d test: twin tests (2,000 random skips of the same count), pooled U+F is the "
             "primary p for BH (q over all R144d filters); U and F alone are gates", tests=pv), indent=1, default=str))
    for x in pv:
        print(x, flush=True)


if __name__ == "__main__":
    {"design": cmd_design, "test": cmd_test}[sys.argv[1] if len(sys.argv) > 1 else "design"]()
