"""R141 (PREREG.md): every losing trade of the four paper index arms, and the patterns in them. Descriptive.

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r141_losing_trades/r141.py   # -> results/losers.json

Trades: R115's engine (today's rules, parity with the published arms). Features: R140 Amendment A's (index bars,
R101's context). The final period (1 Apr - 13 Oct 2026) was already read by R140; here it is only a check.
"""
from __future__ import annotations

import json
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r140_structure_arms"))
import r140a as A  # noqa: E402

M, K, S = A.M, A.K, A.S
RESULTS = HERE / "results"
PERIODS = {"H1": A.H1, "H2": A.H2, "final": (K.FINAL_START, K.FINAL_END)}
MIN_N = 30
NEVER, GAVE = 0.05, 0.15
TIME_BUCKETS = ((0, 15, "09:15-09:30"), (15, 75, "09:30-10:30"), (75, 165, "10:30-12:00"), (165, 255, "12:00-13:30"),
                (255, 376, "after 13:30"))
HOLD_BUCKETS = ((0, 15, "<= 15 min"), (15, 60, "15-60 min"), (60, 180, "1-3 h"), (180, 999, "> 3 h"))
SIZE_BUCKETS = ((-1e9, -3000, "loss > Rs 3,000"), (-3000, -1500, "Rs 1,500-3,000"), (-1500, -500, "Rs 500-1,500"),
                (-500, 0, "loss < Rs 500"))
WEEKDAY = ("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")


def bucket(x, table):
    for a, b, name in table:
        if a <= x < b:
            return name
    return None


def peak_type(t) -> str:
    p = t.get("peak")
    if p is None or not np.isfinite(p):
        return "unknown"
    return "never worked (peak < 5%)" if p < NEVER else ("gave it back (peak >= 15%)" if p >= GAVE else "in between")


def in_per(t, per) -> bool:
    a, b = PERIODS[per] if per in PERIODS else (K.START, K.DESIGN_END)
    return a <= t["day"] <= b


def summary(tr: list) -> dict:
    if not tr:
        return dict(n=0)
    nets = np.array([float(t["net"]) for t in tr])
    lo = nets < 0
    return dict(n=len(tr), losers=int(lo.sum()), loser_rate=round(float(lo.mean()), 3), avg=round(float(nets.mean())),
                sum_losses=round(float(nets[lo].sum())), avg_loss=round(float(nets[lo].mean())) if lo.any() else 0)


def slices(trades: list, label) -> dict:
    """{slice: {design / H1 / H2 / final: summary}, plus steady} for a labelling function trade -> slice (None: skip)."""
    groups = defaultdict(list)
    for t in trades:
        s = label(t)
        if s is not None:
            groups[str(s)].append(t)
    base = {p: summary([t for t in trades if in_per(t, p)]) for p in ("design", "H1", "H2", "final")}
    out = {}
    for s, tr in groups.items():
        row = {p: summary([t for t in tr if in_per(t, p)]) for p in ("design", "H1", "H2", "final")}
        row["steady_losing"] = steady(row, base)
        out[s] = row
    return out


def steady(row: dict, base: dict) -> bool:
    """PREREG: loser rate above the arm's, average net below 0, >= 30 trades, in H1, H2 and the final separately."""
    for p in ("H1", "H2", "final"):
        r, b = row[p], base[p]
        if r.get("n", 0) < MIN_N or not b.get("n"):
            return False
        if not (r["loser_rate"] > b["loser_rate"] and r["avg"] < 0):
            return False
    return True


def clustering(trades: list) -> dict:
    by_day = defaultdict(list)
    for t in trades:
        by_day[(t["day"], t["und"])].append(float(t["net"]))
    lose = [t for t in trades if t["net"] < 0]
    all_loss_days = {k for k, v in by_day.items() if all(x < 0 for x in v)}
    on_all_loss = sum(1 for t in lose if (t["day"], t["und"]) in all_loss_days)
    seq = [float(t["net"]) < 0 for t in sorted(trades, key=lambda t: (t["day"], int(t["er"]), t["und"]))]
    streak = best = 0
    for x in seq:
        streak = streak + 1 if x else 0
        best = max(best, streak)
    per_day = Counter(sum(1 for x in v if x < 0) for v in by_day.values())
    return dict(index_days=len(by_day), all_loss_index_days=len(all_loss_days),
                losers_on_all_loss_days=round(on_all_loss / len(lose), 3) if lose else 0,
                longest_losing_streak=best, losers_per_index_day={str(k): v for k, v in sorted(per_day.items())})


def tree(trades: list, feats: dict, names: list) -> dict:
    """A depth-2 tree (leaves >= 100) on the design trades: losers vs winners; leaves shown per period."""
    from sklearn.tree import DecisionTreeClassifier
    des = [t for t in trades if in_per(t, "design")]

    def X(tr):
        rows = []
        for t in tr:
            f = feats.get(A.trade_key(t), {})
            rows.append([float(f.get(n)) if isinstance(f.get(n), (int, float, np.floating, np.integer))
                         and np.isfinite(float(f.get(n))) else np.nan for n in names])
        return np.array(rows, float)
    Xd = X(des)
    med = np.nanmedian(Xd, axis=0)
    fill = lambda Z: np.where(np.isfinite(Z), Z, med)  # noqa: E731
    y = np.array([t["net"] < 0 for t in des])
    clf = DecisionTreeClassifier(max_depth=2, min_samples_leaf=100, random_state=141).fit(fill(Xd), y)
    tr_ = clf.tree_

    def path(leaf):
        def walk(node, acc):
            if node == leaf:
                return acc
            if tr_.children_left[node] == -1:
                return None
            f, th = names[tr_.feature[node]], tr_.threshold[node]
            return walk(tr_.children_left[node], acc + [f"{f} <= {th:.3g}"]) or \
                walk(tr_.children_right[node], acc + [f"{f} > {th:.3g}"])
        return " and ".join(walk(0, []) or [])
    leaf_of = dict(zip(map(id, trades), clf.apply(fill(X(trades)))))
    out = slices(trades, lambda t: path(int(leaf_of[id(t)])))
    return out


def arm_report(arm: str) -> dict:
    us = S.units(arm, K.START, K.FINAL_END)
    trades = S.walk(arm, us)
    feats = A.all_features(us)
    stores = S.mods()["r51data"]
    exp = {}

    def is_exp(t):
        k = (t["und"], t["day"])
        if k not in exp:
            exp[k] = bool(stores.load(t["und"])[t["day"]].expiry)
        return "expiry day" if exp[k] else "not expiry"
    des = [t for t in trades if in_per(t, "design")]
    out = dict(overview={p: summary([t for t in trades if in_per(t, p)]) for p in ("design", "H1", "H2", "final")})
    losers = [t for t in trades if t["net"] < 0]
    out["how"] = dict(
        exit=slices(trades, lambda t: t["why"]),
        held=slices(trades, lambda t: bucket(int(t["xr"]) - int(t["er"]), HOLD_BUCKETS)),
        peak=slices(trades, peak_type),
        loss_size={p: dict(Counter(bucket(float(t["net"]), SIZE_BUCKETS) for t in losers if in_per(t, p)))
                   for p in ("design", "final")},
        losers_peak={p: dict(Counter(peak_type(t) for t in losers if in_per(t, p))) for p in ("design", "final")})
    out["where"] = dict(
        index=slices(trades, lambda t: t["und"]), side=slices(trades, lambda t: "call" if t["side"] == "C" else "put"),
        weekday=slices(trades, lambda t: WEEKDAY[t["day"].weekday()]), expiry=slices(trades, is_exp),
        time=slices(trades, lambda t: bucket(int(t["er"]), TIME_BUCKETS)))
    out["clustering"] = {p: clustering([t for t in trades if in_per(t, p)]) for p in ("design", "final")}
    fx = {}
    for f in A.FEATS:
        vals = [feats.get(A.trade_key(t), {}).get(f, np.nan) for t in des]
        ed = A.edges(vals) if f in A.QUINT else np.array([])
        fx[f] = dict(name=A.NAMES[f], edges=[round(float(e), 4) for e in ed],
                     slices=slices(trades, lambda t, f=f, ed=ed: A.slice_of(f, feats.get(A.trade_key(t), {}).get(
                         f, np.nan), ed)))
    out["features"] = fx
    out["tree"] = tree(trades, feats, [f for f in A.FEATS if f != "C5"])
    steady_list = []
    for grp in ("how", "where"):
        for k, v in out[grp].items():
            if isinstance(v, dict):
                for s, row in v.items():
                    if isinstance(row, dict) and row.get("steady_losing"):
                        steady_list.append(dict(kind=f"{grp}:{k}", slice=s, **{p: row[p] for p in ("design", "final")}))
    for f, v in fx.items():
        for s, row in v["slices"].items():
            if row["steady_losing"]:
                steady_list.append(dict(kind=f"feature:{f} {A.NAMES[f]}", slice=s,
                                        **{p: row[p] for p in ("design", "final")}))
    for s, row in out["tree"].items():
        if row["steady_losing"]:
            steady_list.append(dict(kind="tree leaf", slice=s, **{p: row[p] for p in ("design", "final")}))
    out["steady_losing_patterns"] = steady_list
    print(arm, out["overview"]["design"], out["overview"]["final"], "steady", len(steady_list), flush=True)
    return out


def run():
    S.mods()["C"].open_final()          # descriptive check only: the final was already read by R140
    out = {"_meta": dict(written=str(date.today()), note="descriptive; final already read by R140")}
    for arm in K.ARMS:
        out[arm] = arm_report(arm)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "losers.json").write_text(json.dumps(out, indent=1, default=str))


if __name__ == "__main__":
    run()
