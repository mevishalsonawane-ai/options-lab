"""R144b-S (PREREG.md): retrain Solo 2.0 with a purged monthly walk-forward on Dec 2022 - Oct 2026.

    IRAALGO_LAKE=<flat lake> python research/hunt/r144b_solo_retrain/r144bs.py rows old    # Dec 2022 - 19 Nov 2024
    IRAALGO_LAKE=<flat lake> python research/hunt/r144b_solo_retrain/r144bs.py rows main   # 20 Nov 2024 - 13 Oct 2026
    python research/hunt/r144b_solo_retrain/r144bs.py run                                  # walk-forward, gates

`rows old` needs R144b's old R57 store (`r144b.py build-solo-old`). Each `rows` call runs in its own process because
the old store re-points R57's settings. Rows are cached in $R144BS_OUT (default ~/.cache/iraalgo/r144bs; never in
git). R57's engine, features, model recipes, walk-forward and take rule are used unchanged.
Signal source: the index (each setup's own bars); options and VIX only as model inputs.
"""
from __future__ import annotations

import json
import os
import pickle
import sys
from collections import Counter, defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
sys.path.insert(0, str(HUNT / "r144b_loss_orb_liq"))
import r144b as R  # noqa: E402
import r144b_lib as X  # noqa: E402

RESULTS = HERE / "results"
OUT = Path(os.environ.get("R144BS_OUT", R.CACHE_ROOT / "r144bs")).expanduser()
SEED, TWINS = 1445, 2000
PARTS = {"old": (date(2022, 12, 1), date(2024, 11, 19)), "main": (date(2024, 11, 20), date(2026, 10, 13))}
PERIODS = {"P1": (date(2023, 7, 1), date(2024, 11, 19)), "P2": (date(2024, 11, 20), date(2026, 3, 31)),
           "P3": (date(2026, 4, 1), date(2026, 10, 13))}
TEST_MONTHS = [(y, m) for y in range(2023, 2027) for m in range(1, 13) if (2023, 7) <= (y, m) <= (2026, 10)]
VARIANTS = {"V1": dict(kind="logit", setups=("S1", "S2", "S3", "S4")),
            "V2": dict(kind="logit", setups=("S1", "S2", "S3")),
            "V3": dict(kind="gbc", setups=("S1", "S2", "S3"))}
GATE_P, GATE_Q, MIN_TRADES = 0.05, 0.10, 40
KEEP = ("und", "day", "er", "side", "setup", "net", "y_win", "f", "why", "strike")


# ================================================================================================ rows (one store each)
def rows(part: str):
    lo, hi = PARTS[part]
    K, D, _, St, Sy = R._r57(part == "old")
    rs = [{k: r.get(k) for k in KEEP} for r in Sy.a_rows(St.candidates(lo, hi)) if r["setup"] != "C4"]
    cal = sorted({d for u in K.UNDS for d, x in D.load(u).items() if x.atm is not None and lo <= d <= hi})
    OUT.mkdir(parents=True, exist_ok=True)
    (OUT / f"rows_{part}.pkl").write_bytes(pickle.dumps(dict(rows=rs, calendar=cal)))
    print(part, len(rs), "candidates,", len(cal), "sessions", flush=True)


# ================================================================================================ pure helpers
def in_period(d: date, per: str) -> bool:
    lo, hi = PERIODS[per]
    return lo <= d <= hi


def twin_p(picks: list, rows_by_day: dict, n: int, rng) -> tuple:
    """Random same-day pick: on each day the variant traded, a uniformly random candidate of its setups. Returns
    (p = share of twins with net >= the variant's, (1 + hits) / (1 + n); the twins' mean net)."""
    if not picks:
        return 1.0, 0.0
    real = float(sum(t["net"] for t in picks))
    pools = [np.array([r["net"] for r in rows_by_day[t["day"]]], float) for t in picks]
    tw = np.zeros(n)
    for i in range(n):
        tw[i] = sum(p[rng.integers(0, len(p))] for p in pools)
    return (1 + int((tw >= real).sum())) / (1 + n), float(tw.mean())


def gates(st: dict, ref: dict, p: float, q: float) -> list:
    fails = []
    if not st["net"] > 0:
        fails.append("net <= 0")
    if not p <= GATE_P:
        fails.append(f"twin p {p:.3g} > {GATE_P}")
    if st["trades"] < MIN_TRADES:
        fails.append(f"< {MIN_TRADES} trades")
    if st["max_dd"] > ref["max_dd"]:
        fails.append("drawdown worse than the frozen A2")
    if not q <= GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    return fails


def describe(tr: list) -> dict:
    s = X.loss_stats(tr)
    s["per_trade"] = round(s["net"] / s["trades"]) if s["trades"] else 0
    for key in ("und", "setup"):
        g = defaultdict(list)
        for t in tr:
            g[t[key]].append(float(t["net"]))
        s[f"by_{key}"] = {k: dict(n=len(v), net=round(sum(v)), win=round(float(np.mean(np.array(v) > 0)), 3))
                          for k, v in sorted(g.items())}
    return s


# ================================================================================================ the run
def load_rows() -> tuple:
    rs, cal = [], set()
    for part in PARTS:
        c = pickle.loads((OUT / f"rows_{part}.pkl").read_bytes())
        rs += c["rows"]
        cal.update(c["calendar"])
    return rs, sorted(cal)


def _r57m():
    _, _, Ev, _, Sy = R._r57(False)
    import r57model as M
    return M, Ev, Sy


def run() -> dict:
    M, Ev, Sy = _r57m()
    rs, cal = load_rows()
    names = M.names_a(False)
    rng = np.random.default_rng(SEED)
    out = {"_meta": dict(written=str(date.today()), candidates=len(rs), sessions=len(cal), twins=TWINS, seed=SEED,
                         test_months=f"{TEST_MONTHS[0]} - {TEST_MONTHS[-1]}"), "variants": {}, "reference": {}}
    in_test = lambda d: any(in_period(d, p) for p in PERIODS)  # noqa: E731
    # references (not gated)
    a2 = json.loads((HUNT / "r57" / "results" / "models" / "A2.json").read_text())
    ra = [r for r in rs if in_test(r["day"])]
    sc = np.array([Ev.score(a2, r["f"]) for r in ra])
    frozen = Sy.select_a(ra, sc, np.full(len(ra), a2["threshold"]), "logit")
    s1 = [r for r in ra if r["setup"] == "S1"]
    ref = {p: X.loss_stats([t for t in frozen if in_period(t["day"], p)]) for p in PERIODS}
    out["reference"]["frozen_A2"] = {p: describe([t for t in frozen if in_period(t["day"], p)]) for p in PERIODS}
    out["reference"]["S1_alone"] = {p: describe([t for t in s1 if in_period(t["day"], p)]) for p in PERIODS}
    rows_tests = []
    for vid, v in VARIANTS.items():
        vr = [r for r in rs if r["setup"] in v["setups"]]
        Xm = M.matrix([r["f"] for r in vr], names)
        y = np.array([r["y_win"] for r in vr])
        days = np.array([r["day"] for r in vr], dtype=object)
        scv, thr, _ = M.walk(days, Xm, y, v["kind"], TEST_MONTHS, cal)
        picks = Sy.select_a(vr, scv, thr, v["kind"])
        by_day = defaultdict(list)
        for r in vr:
            by_day[r["day"]].append(r)
        tested = np.isfinite(scv)
        res = dict(kind=v["kind"], setups=list(v["setups"]), auc_wf=M.auc(y[tested], scv[tested]), periods={})
        for p in PERIODS:
            pk = [t for t in picks if in_period(t["day"], p)]
            tp, tw_mean = twin_p(pk, by_day, TWINS, rng)
            res["periods"][p] = dict(describe(pk), twin_p=tp, random_pick_mean_net=round(tw_mean))
            rows_tests.append((vid, p, tp))
        out["variants"][vid] = res
    qs = X.bh([x[2] for x in rows_tests])
    for (vid, p, tp), q in zip(rows_tests, qs):
        r = out["variants"][vid]["periods"][p]
        r["q"] = q
        r["fails"] = gates(r, ref[p], tp, q)
    for vid, v in out["variants"].items():
        v["passed"] = all(not v["periods"][p]["fails"] for p in PERIODS)
    passed = [v for v in out["variants"] if out["variants"][v]["passed"]]
    out["named"] = min(passed, key=lambda v: sum(out["variants"][v]["periods"][p]["loss_sum"] for p in PERIODS)) \
        if passed else None
    RESULTS.mkdir(exist_ok=True)
    (RESULTS / "retrain.json").write_text(json.dumps(out, indent=1, default=str))
    (RESULTS / "pvalues.json").write_text(json.dumps(dict(
        study="R144b-S", note="Solo 2.0 retrain; BH within this study over 9 tests",
        tests=[dict(id=f"r144bs-{v}-{p}", raw_p=tp, q_within_study=q) for (v, p, tp), q in zip(rows_tests, qs)]),
        indent=1))
    return out


if __name__ == "__main__":
    cmd = (sys.argv[1:] or [""])[0]
    if cmd == "rows":
        rows(sys.argv[2])
    elif cmd == "run":
        run()
    else:
        print(__doc__)
