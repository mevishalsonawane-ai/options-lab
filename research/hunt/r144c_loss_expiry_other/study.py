"""R144c study (PREREG.md sections 4-6): anatomy of the losing trades, design-only skip filters, their test on the final
months and the unseen two years, random twins, BH, R142's lose-less gates.

    python -I research/hunt/r144c_loss_expiry_other/study.py choose    # design only -> results/filters.json (frozen)
    python -I research/hunt/r144c_loss_expiry_other/study.py run       # anatomy + the test -> results/*.json

`choose` reads only design-period trades. `run` refuses to start unless results/filters.json exists, so the test periods
are read after the filters are frozen.
"""
from __future__ import annotations

import json
import math
import pickle
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import lake as LK  # noqa: E402
import features as FE  # noqa: E402

RESULTS = HERE / "results"
ARMS = FE.ARMS
LABEL = {"FT3": "FT3 near-ATM morning", "HERO": "Expiry Hero", "HEROTKT": "Hero Rs 1,000 ticket", "NIGHT": "Night (R3)",
         "VIXDIV": "VIX divergence", "FLOW": "IraThinker Flow"}
PERIODS = ("early", "unseen", "design", "final")
TESTS = ("unseen", "final")
H1_END = date(2025, 6, 30)
MIN_DESIGN, MIN_STEADY, MAX_FILTERS = 60, 30, 3
TWINS, SEED = 2000, 1443
GATE_P, GATE_Q, MIN_TEST, MIN_CHANGED = 0.05, 0.10, 40, 30
BOSS_WIN = 0.65


# ------------------------------------------------------------------------------------------------ data
def load(arm: str) -> list:
    tr = pickle.loads((LK.OUT / f"trades_{arm}.pkl").read_bytes())
    fs = pickle.loads((LK.OUT / f"feats_{arm}.pkl").read_bytes())
    fk = {f["_key"]: f for f in fs}
    for t in tr:
        t["f"] = fk.get((t["arm"], t["und"], t["day"], t["side"], t["entry_row"]), {})
        t["period"] = LK.period(t["day"])
    return tr


def per(tr, p):
    return [t for t in tr if t["period"] == p]


# ------------------------------------------------------------------------------------------------ stats
def stats(tr: list) -> dict:
    n = len(tr)
    if not n:
        return dict(trades=0, net=0, win=None, loss_sum=0, max_dd=0, worst_day=0, worst_month=0, avg_win=None,
                    avg_loss=None, losers=0, lost=0)
    nets = np.array([t["net"] for t in tr])
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in tr:
        daily[t["day"]] += t["net"]
        monthly[(t["day"].year, t["day"].month)] += t["net"]
    dv = np.array([daily[d] for d in sorted(daily)])
    eq = np.cumsum(dv)
    dd = float(np.max(np.maximum.accumulate(np.concatenate([[0], eq]))[1:] - eq)) if len(eq) else 0.0
    w, lz = nets[nets > 0], nets[nets <= 0]
    return dict(trades=n, net=round(float(nets.sum())), win=round(float((nets > 0).mean()), 4),
                loss_sum=round(float(dv[dv < 0].sum())), max_dd=round(dd), worst_day=round(float(dv.min())),
                worst_month=round(float(min(monthly.values()))), avg_win=round(float(w.mean())) if len(w) else None,
                avg_loss=round(float(lz.mean())) if len(lz) else None, losers=int((nets <= 0).sum()),
                lost=round(float(lz.sum())), avg=round(float(nets.mean())))


def loss_sum(tr) -> float:
    daily = defaultdict(float)
    for t in tr:
        daily[t["day"]] += t["net"]
    return float(sum(v for v in daily.values() if v < 0))


def bh(ps):
    ps = np.asarray(ps, float)
    n = len(ps)
    if not n:
        return []
    o = np.argsort(ps)
    q = ps[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out.tolist()


# ------------------------------------------------------------------------------------------------ anatomy
def edges(vals):
    v = np.array([x for x in vals if x is not None and x == x], float)
    if len(v) < 25:
        return None
    return np.quantile(v, [0.2, 0.4, 0.6, 0.8]).tolist()


def qbin(x, ed):
    if x is None or not x == x or ed is None:
        return None
    return int(np.searchsorted(ed, x, side="right"))


def streak(tr):
    best = cur = 0
    for t in sorted(tr, key=lambda z: (z["day"], z["entry_row"])):
        cur = cur + 1 if t["net"] <= 0 else 0
        best = max(best, cur)
    return best


def anatomy(arm: str, tr: list) -> dict:
    out = {"periods": {p: stats(per(tr, p)) for p in PERIODS}, "all": stats(tr)}
    out["by_index"] = {u: {p: stats([t for t in per(tr, p) if t["und"] == u]) for p in PERIODS}
                       for u in sorted({t["und"] for t in tr})}
    out["by_side"] = {s: {p: stats([t for t in per(tr, p) if t["side"] == s]) for p in PERIODS} for s in ("C", "P")}
    ex = defaultdict(list)
    for t in tr:
        ex[t["exit_kind"]].append(t)
    out["by_exit"] = {k: dict(n=len(v), loser_rate=round(float(np.mean([t["net"] <= 0 for t in v])), 3),
                              lost=round(sum(t["net"] for t in v if t["net"] <= 0)), net=round(sum(t["net"] for t in v)))
                      for k, v in ex.items()}
    rch = [t for t in tr if t.get("reach") == t.get("reach") and t.get("reach") is not None]
    if rch:
        def grp(lo, hi):
            g = [t for t in rch if lo <= t["reach"] < hi]
            return dict(n=len(g), loser_rate=round(float(np.mean([t["net"] <= 0 for t in g])), 3) if g else None,
                        lost=round(sum(t["net"] for t in g if t["net"] <= 0)))
        out["by_best_after_entry"] = {"never +5%": grp(0, 1.05), "+5% to 2x": grp(1.05, 2.0), "2x to 5x": grp(2.0, 5.0),
                                      "5x or more": grp(5.0, 1e9)}
    # bunching: losers on days where every trade of the arm lost
    byd = defaultdict(list)
    for t in tr:
        byd[t["day"]].append(t)
    allbad = {d for d, v in byd.items() if len(v) >= 2 and all(t["net"] <= 0 for t in v)}
    los = [t for t in tr if t["net"] <= 0]
    out["bunching"] = dict(losers=len(los), on_all_lose_multi_trade_days=sum(t["day"] in allbad for t in los),
                           longest_losing_streak={p: streak(per(tr, p)) for p in PERIODS},
                           losing_days={p: sum(1 for d, v in byd.items() if LK.period(d) == p and sum(t["net"] for t in v) < 0)
                                        for p in PERIODS},
                           days={p: sum(1 for d in byd if LK.period(d) == p) for p in PERIODS})
    out["worst10"] = [dict(day=str(t["day"]), und=t["und"], side=t["side"], strike=t["strike"], entry_min=t["entry_row"],
                           premium=round(t["entry_px"], 2), exit=t["exit_kind"], net=round(t["net"]))
                      for t in sorted(tr, key=lambda z: z["net"])[:10]]
    # features by fifth (design edges), per period; steady flags
    des = per(tr, "design")
    base_lr = {p: (np.mean([t["net"] <= 0 for t in per(tr, p)]) if per(tr, p) else None) for p in PERIODS}
    feats = {}
    for k in FE.NUMERIC:
        ed = edges([t["f"].get(k) for t in des])
        if ed is None:
            feats[k] = dict(edges=None, note="too few design values")
            continue
        tab = {}
        for p in PERIODS:
            bins = defaultdict(list)
            for t in per(tr, p):
                b = qbin(t["f"].get(k), ed)
                if b is not None:
                    bins[b].append(t)
            tab[p] = {str(b): dict(n=len(v), loser_rate=round(float(np.mean([t["net"] <= 0 for t in v])), 3),
                                   avg=round(float(np.mean([t["net"] for t in v])))) for b, v in sorted(bins.items())}
        steady = []
        for b in map(str, range(5)):
            ok = all(b in tab[p] and tab[p][b]["n"] >= MIN_STEADY and base_lr[p] is not None and
                     tab[p][b]["loser_rate"] > base_lr[p] and tab[p][b]["avg"] < 0 for p in ("unseen", "design", "final"))
            if ok:
                steady.append(int(b))
        small = any(len(per(tr, p)) < 5 * MIN_STEADY for p in ("unseen", "design", "final"))
        feats[k] = dict(edges=[round(e, 4) for e in ed], table=tab, steady_bad_fifths=steady,
                        too_small_to_tell=small)
    tabq = {}
    for p in PERIODS:
        g = defaultdict(list)
        for t in per(tr, p):
            g[t["f"].get("quadrant")].append(t)
        tabq[p] = {str(q): dict(n=len(v), loser_rate=round(float(np.mean([t["net"] <= 0 for t in v])), 3),
                                avg=round(float(np.mean([t["net"] for t in v])))) for q, v in g.items()}
    feats["quadrant"] = dict(table=tabq)
    out["features"] = feats
    out["base_loser_rate"] = {p: (round(float(v), 3) if v is not None else None) for p, v in base_lr.items()}
    out["feature_coverage"] = {k: round(float(np.mean([t["f"].get(k) is not None and t["f"].get(k) == t["f"].get(k)
                                                         for t in tr])), 2) for k in FE.NAMES} if tr else {}
    return out


# ------------------------------------------------------------------------------------------------ filters
def drops(flt: dict, t: dict) -> bool:
    """Is trade t skipped by the frozen filter? An unknown feature never skips."""
    x = t["f"].get(flt["feature"])
    if flt["kind"] == "cat":
        return x == flt["value"]
    if x is None or not x == x:
        return False
    return x < flt["cut"] if flt["kind"] == "low" else x > flt["cut"]


def candidates(des: list) -> list:
    out = []
    for k in FE.NUMERIC:
        ed = edges([t["f"].get(k) for t in des])
        if ed is None:
            continue
        out.append(dict(feature=k, kind="low", cut=round(float(ed[0]), 4)))
        out.append(dict(feature=k, kind="high", cut=round(float(ed[3]), 4)))
    for q in FE.QUAD:
        out.append(dict(feature="quadrant", kind="cat", value=q))
    return out


def choose(arm: str, tr: list) -> dict:
    """PREREG section 5, on design trades only."""
    des = per(tr, "design")
    res = dict(arm=arm, design_trades=len(des), chosen=[], eligible=[])
    if len(des) < MIN_DESIGN:
        res["note"] = f"under {MIN_DESIGN} design trades: no filter (describe only)"
        return res
    lr = np.mean([t["net"] <= 0 for t in des])
    halves = ([t for t in des if t["day"] <= H1_END], [t for t in des if t["day"] > H1_END])
    for c in candidates(des):
        gone = [t for t in des if drops(c, t)]
        share = len(gone) / len(des)
        if not 0.10 <= share <= 0.40:
            continue
        if not (np.mean([t["net"] <= 0 for t in gone]) > lr and sum(t["net"] for t in gone) < 0):
            continue
        if not all(loss_sum([t for t in h if not drops(c, t)]) > loss_sum(h) for h in halves):
            continue
        saved = loss_sum([t for t in des if not drops(c, t)]) - loss_sum(des)
        res["eligible"].append(dict(c, dropped=len(gone), share=round(share, 3), saved=round(saved),
                                    saved_per_trade=round(saved / len(gone), 1),
                                    gone_loser_rate=round(float(np.mean([t["net"] <= 0 for t in gone])), 3),
                                    gone_net=round(sum(t["net"] for t in gone))))
    res["eligible"].sort(key=lambda z: -z["saved_per_trade"])
    for e in res["eligible"]:
        if len(res["chosen"]) >= MAX_FILTERS:
            break
        g = {id(t) for t in des if drops(e, t)}
        if any(len(g & {id(t) for t in des if drops(c, t)}) > 0.5 * len(g) for c in res["chosen"]):
            continue
        res["chosen"].append(e)
    return res


def twin_p(test: list, kept: list, rng) -> tuple:
    k = len(test) - len(kept)
    act = loss_sum(kept)
    if k <= 0:
        return 1.0, act, []
    tw = np.empty(TWINS)
    idx = np.arange(len(test))
    for i in range(TWINS):
        g = set(rng.choice(idx, k, replace=False).tolist())
        tw[i] = loss_sum([t for j, t in enumerate(test) if j not in g])
    p = (1 + int((tw >= act).sum())) / (1 + TWINS)
    return p, act, tw


def gates(f: dict, b: dict, p: float, q: float, n_test: int, changed: int) -> list:
    fails = []
    for pp in TESTS:
        r, s = f[pp], b[pp]
        if not r["trades"]:
            fails.append(f"{pp}: no trades")
            continue
        if not r["loss_sum"] > s["loss_sum"]:
            fails.append(f"{pp}: losing days not smaller")
        if r["max_dd"] > s["max_dd"]:
            fails.append(f"{pp}: drawdown worse")
        if r["worst_day"] < s["worst_day"]:
            fails.append(f"{pp}: worst day worse")
        if r["worst_month"] < s["worst_month"]:
            fails.append(f"{pp}: worst month worse")
        if not r["net"] > 0:
            fails.append(f"{pp}: the arm does not make money" + (" (it lost before the filter too)" if s["net"] <= 0 else ""))
    if not p <= GATE_P:
        fails.append(f"twin p {p:.3g} > {GATE_P}")
    if q > GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    if n_test < MIN_TEST:
        fails.append(f"< {MIN_TEST} test trades")
    if changed < MIN_CHANGED:
        fails.append(f"< {MIN_CHANGED} test trades skipped")
    return fails


# ------------------------------------------------------------------------------------------------ commands
def cmd_choose():
    out = {"_meta": dict(written=datetime.now(timezone.utc).isoformat(), note="chosen on design trades only (PREREG 5)")}
    for arm in ARMS:
        out[arm] = choose(arm, load(arm))
        print(arm, out[arm]["design_trades"], [(c["feature"], c["kind"], c.get("cut", c.get("value"))) for c in out[arm]["chosen"]])
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "filters.json").write_text(json.dumps(out, indent=1, default=str))


def cmd_run():
    flt = json.loads((RESULTS / "filters.json").read_text())
    rng = np.random.default_rng(SEED)
    anat, tests = {}, []
    for arm in ARMS:
        tr = load(arm)
        anat[arm] = anatomy(arm, tr)
        base = {p: stats(per(tr, p)) for p in PERIODS}
        test = [t for t in tr if t["period"] in TESTS]
        for c in flt[arm]["chosen"]:
            kept = [t for t in tr if not drops(c, t)]
            r = {p: stats(per(kept, p)) for p in PERIODS}
            kt = [t for t in test if not drops(c, t)]
            p, act, _ = twin_p(test, kt, rng)
            name = f"r144c-{arm.lower()}-{c['feature'].replace('_', '-')}-{c['kind']}"
            tests.append(dict(id=name, arm=arm, filter=c, base=base, after=r, twin_p=p, test_loss_sum=act,
                              n_test=len(test), changed=len(test) - len(kt),
                              win_before_after={pp: (base[pp]["win"], r[pp]["win"]) for pp in PERIODS}))
            print(name, round(p, 4), flush=True)
    qs = bh([t["twin_p"] for t in tests])
    for t, q in zip(tests, qs):
        t["q"] = round(q, 4)
        t["fails"] = gates(t["after"], t["base"], t["twin_p"], q, t["n_test"], t["changed"])
        t["passed"] = not t["fails"]
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / "anatomy.json").write_text(json.dumps(anat, indent=1, default=str))
    (RESULTS / "tests.json").write_text(json.dumps(tests, indent=1, default=str))
    (RESULTS / "pvalues.json").write_text(json.dumps(
        {"family": "R144c skip filters (BH within R144c)", "tests": {t["id"]: dict(p=t["twin_p"], q=t["q"], passed=t["passed"])
                                                                     for t in tests}}, indent=1))
    for t in tests:
        print(t["id"], "p", round(t["twin_p"], 4), "q", t["q"], "PASS" if t["passed"] else t["fails"])


if __name__ == "__main__":
    {"choose": cmd_choose, "run": cmd_run}[(sys.argv[1:] or ["run"])[0]]()
