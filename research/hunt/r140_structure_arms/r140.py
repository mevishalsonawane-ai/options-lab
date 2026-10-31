"""R140 (PREREG.md): can higher-high / lower-low market structure make our four paper arms LOSE LESS?

    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r51/r51data.py build      # R101's clean stores
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r140_structure_arms/r140.py design
    IRAALGO_LAKE=<flat lake> R51_CLEAN=1 python research/hunt/r140_structure_arms/r140.py final   # read once

Boss, 31 Oct: "Will the higher high and lower low, and equity shift work for our existing strategies", then "profit is
not important, losing money is important, we should always check how could we lose less". The gates are about losses.

Signal source: the index (R139's 15- and 5-minute wick swings, built from the store's index minutes). The arms keep
buying their own options with their own stops; R115's engine (R101 units, R106 walk and forced exits) prices
everything, parity asserted against the published arms.
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import date, datetime, timezone
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
HUNT = HERE.parent
for p in (HUNT / "r115_price_action_arms", HUNT / "r139_market_structure"):
    if str(p) not in sys.path:
        sys.path.insert(0, str(p))
import r115cfg as K  # noqa: E402
import r115lib as L  # noqa: E402
import r115study as S  # noqa: E402
import r139 as B  # noqa: E402

R = B.R
N = 375
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
TWINS = 2000
SEED = 140
GATE_Q = 0.10
GATE_P = 0.05
MIN_FINAL, MIN_CHANGED = 40, 30
MONTH_LIMIT = -13_000.0

# the family (PREREG section 3): id -> (arm, kind, timeframe)
TESTS = {}
for _arm, _a in (("R72", "72"), ("R51", "51"), ("R52", "52"), ("LIQ", "L")):
    TESTS[f"T15-{_a}"] = (_arm, "filter", 15)
    TESTS[f"T5-{_a}"] = (_arm, "filter", 5)
for _arm, _a in (("R72", "72"), ("R51", "51")):
    TESTS[f"M15-{_a}"] = (_arm, "exit", 15)
    TESTS[f"M5-{_a}"] = (_arm, "exit", 5)
LABEL = {"filter": "skip the trade when the {tf}-min Dow trend (HH+HL / LH+LL) is against it",
         "exit": "sell when a {tf}-min bar closes beyond the last {tf}-min swing point against the trade"}


# ================================================================================================ structure per index
class Index:
    """One index's minutes from the R51 store (warm-up from Sep 2024), as R131 sides, with R139's structure."""

    def __init__(self, und: str):
        days = S.mods()["r51data"].load(und)
        self.days = sorted(days)
        self.pos = {d: i for i, d in enumerate(self.days)}
        idx = np.stack([days[d].idx for d in self.days])          # (D, 4, 375)
        self.side = R.Side(idx[:, 1].copy(), idx[:, 2].copy(), idx[:, 0].copy(), idx[:, 3].copy())
        self.tf = {}
        for tf in (5, 15):
            ch = self.side.chart(tf)
            Hs, Ls = B.structure(ch)
            self.tf[tf] = (ch, Hs, Ls, B.trend(ch, Hs, Ls))


def from_arrays(o, h, l, c, days) -> Index:
    """An Index from plain (D, 375) arrays (tests)."""
    x = Index.__new__(Index)
    x.days = list(days)
    x.pos = {d: i for i, d in enumerate(x.days)}
    x.side = R.Side(h, l, o, c)
    x.tf = {}
    for tf in (5, 15):
        ch = x.side.chart(tf)
        Hs, Ls = B.structure(ch)
        x.tf[tf] = (ch, Hs, Ls, B.trend(ch, Hs, Ls))
    return x


_IX: dict = {}


def index(und: str) -> Index:
    if und not in _IX:
        _IX[und] = Index(und)
    return _IX[und]


def last_bar(ix: Index, tf: int, day: date, er: int) -> int:
    """The last tf-minute bar CLOSED by the end of minute er - 1 of `day` (the decision minute); -1 if none."""
    ch = ix.tf[tf][0]
    d = ix.pos.get(day)
    if d is None or er < 1:
        return -1
    return int(np.searchsorted(ch.gend, d * N + er - 1, side="right")) - 1


def against(ix: Index, tf: int, day: date, er: int, side: str) -> bool:
    """T rules: the tf-min Dow trend at the decision is against the trade (DOWN for a call, UP for a put). Unknown or
    mixed: not against."""
    j = last_bar(ix, tf, day, er)
    if j < 0:
        return False
    t = int(ix.tf[tf][3][j])
    return (side == "C" and t == -1) or (side == "P" and t == 1)


def mss_row(ix: Index, tf: int, day: date, er: int, side: str) -> int:
    """M rules: the first tf-min bar of the same session that closes at or after the fill minute er and closes beyond
    the last swing point known at the previous bar's close (below the last swing low for a call, above the last swing
    high for a put). Sold at the next minute's open: that bar's end row + 1. -1 when none (or past 15:29)."""
    ch, Hs, Ls, _ = ix.tf[tf]
    d = ix.pos.get(day)
    if d is None:
        return -1
    k = int(np.searchsorted(ch.gend, d * N + er, side="left"))
    while k < ch.T and int(ch.day[k]) == d:
        if k >= 1:
            if side == "C":
                j = int(Ls[k - 1, 0])
                if j >= 0 and ch.c[k] < ch.l[j]:
                    r = int(ch.B.end[k]) + 1
                    return r if r < N else -1
            else:
                j = int(Hs[k - 1, 0])
                if j >= 0 and ch.c[k] > ch.h[j]:
                    r = int(ch.B.end[k]) + 1
                    return r if r < N else -1
        k += 1
    return -1


# ================================================================================================ losses (Boss's gates)
def loss_stats(trades: list) -> dict:
    """R115's stats plus the loss measures: the sum of losing days, losing days, worst month."""
    s = L.stats(trades)
    daily, monthly = defaultdict(float), defaultdict(float)
    for t in trades:
        daily[t["day"]] += float(t["net"])
        monthly[(t["day"].year, t["day"].month)] += float(t["net"])
    neg = [v for v in daily.values() if v < 0]
    s.update(loss_sum=round(float(sum(neg))), losing_days=len(neg), days=len(daily),
             worst_month=round(float(min(monthly.values()))) if monthly else 0,
             sum_losing_trades=round(float(sum(float(t["net"]) for t in trades if float(t["net"]) < 0))))
    return s


def loss_sum(day_nets) -> float:
    """Sum of the losing days of (day, net) pairs."""
    daily = defaultdict(float)
    for d, x in day_nets:
        daily[d] += float(x)
    return float(sum(v for v in daily.values() if v < 0))


def gates(rule: dict, base: dict, q: float, p: float, final_changed: int) -> dict:
    """PREREG section 5 (all must hold)."""
    fails = []
    for per in ("design", "wf", "final"):
        r, b = rule[per], base[per]
        if not r["loss_sum"] > b["loss_sum"]:
            fails.append(f"{per}: losing days not smaller")
        if r["max_dd"] > b["max_dd"]:
            fails.append(f"{per}: drawdown worse")
        if r["worst_day"] < b["worst_day"]:
            fails.append(f"{per}: worst day worse")
        if r["worst_month"] < b["worst_month"]:
            fails.append(f"{per}: worst month worse")
        if not r["net"] > 0:
            fails.append(f"{per}: the arm no longer makes money")
    if not p < GATE_P:
        fails.append(f"twin p {p:.3g} >= {GATE_P}")
    if q > GATE_Q:
        fails.append(f"BH q {q:.3g} > {GATE_Q}")
    if rule["final"]["trades"] < MIN_FINAL:
        fails.append(f"< {MIN_FINAL} final trades")
    if final_changed < MIN_CHANGED:
        fails.append(f"< {MIN_CHANGED} final trades differ")
    return dict(passed=not fails, fails=fails)


# ================================================================================================ the rules on the arms
def keep_fn(tf: int):
    return lambda g, j, key, c: not against(index(key[0]), tf, key[1], int(c["er"]), c["side"])


def exit_priced(us: list, tf: int) -> list:
    """Every candidate re-priced with the MSS row as a forced exit (R106's engine; the arm's own exits stay on, the
    earlier wins); a candidate with no MSS keeps R101's own price."""
    M = S.mods()
    ex = M["A"]._xl()
    out = []
    for key, cands, res, how in us:
        ix = index(key[0])
        fxs = [mss_row(ix, tf, key[1], int(c["er"]), c["side"]) for c in cands]
        if all(f < 0 for f in fxs):
            out.append(list(res))
            continue
        D = M["r51data"].load(key[0])[key[1]]
        pr = M["X"].price(key[0], D, [dict(c, fx=f) for c, f in zip(cands, fxs)], ex)
        out.append([p if f >= 0 else r for p, r, f in zip(pr, res, fxs)])
    return out


def rule_trades(arm: str, kind: str, tf: int, us: list) -> tuple:
    if kind == "exit":
        return S.walk(arm, us, exit_priced(us, tf)), 0
    fu = S.filtered(us, keep_fn(tf))
    return S.walk(arm, fu), sum(len(u[1]) for u in us) - sum(len(u[1]) for u in fu)


def filter_twins(arm: str, tf: int, us: list, lo: date, hi: date, n: int, rng) -> np.ndarray:
    """Drop as many candidates at random as the rule dropped, walk again; each twin's sum of losing days (final)."""
    kf = keep_fn(tf)
    flat = [(g, j) for g, u in enumerate(us) for j in range(len(u[1]))]
    k = sum(1 for g, u in enumerate(us) for j, c in enumerate(u[1]) if not kf(g, j, u[0], c))
    out = np.zeros(n)
    for x in range(n):
        gone = {flat[i] for i in L.random_drop(len(flat), k, rng)}
        tr = S.walk(arm, S.filtered(us, lambda g, j, key, c: (g, j) not in gone))
        out[x] = loss_sum((t["day"], t["net"]) for t in tr if lo <= t["day"] <= hi)
    return out


def exit_twins(rule_tr: list, base_res: dict, lo: date, hi: date, n: int, rng) -> tuple:
    """Each trade the MSS exit sold before today's exit is sold at a uniformly random minute in between instead (R115);
    each twin's sum of losing days."""
    M = S.mods()
    ex = M["A"]._xl()
    fixed, rows = [], []
    for t in rule_tr:
        if not lo <= t["day"] <= hi:
            continue
        p = base_res.get((t["und"], t["day"], int(t["er_sig"]), t["side"]))
        if p is None or int(t["xr"]) >= int(p["xr"]) or int(p["xr"]) - int(t["er"]) < 2:
            fixed.append((t["day"], float(t["net"])))
            continue
        D = M["r51data"].load(t["und"])[t["day"]]
        r = np.arange(int(t["er"]) + 1, int(p["xr"]), dtype=np.int64)
        rows.append((t["day"], np.asarray(M["X"].forced_exit_nets(t["und"], D, t, ex, r, -1), float)))
    out = np.zeros(n)
    for x in range(n):
        dn = list(fixed) + [(d, nets[rng.integers(0, len(nets))]) for d, nets in rows if len(nets)]
        out[x] = loss_sum(dn)
    return out, len(rows)


# ================================================================================================ evaluation
def evaluate(phase: str, lo: date, hi: date, twins: bool) -> dict:
    out = {"_meta": dict(phase=phase, lo=str(lo), hi=str(hi), twins=TWINS if twins else 0, seed=SEED), "arms": {}}
    rng = np.random.default_rng(SEED)
    pers = ("design", "wf") if phase == "design" else ("final",)
    for arm in K.ARMS:
        us = S.units(arm, lo, hi)
        base = S.walk(arm, us)
        par = S.parity(arm, phase, base)
        res = {"parity": par, "today": {p: loss_stats(S.by_period(base, p)) for p in pers}}
        base_res = {(key[0], key[1], int(c["er"]), c["side"]): r
                    for key, cs, rr, _ in us for c, r in zip(cs, rr) if r is not None}
        for test, (a, kind, tf) in TESTS.items():
            if a != arm:
                continue
            tr, removed = rule_trades(arm, kind, tf, us)
            r = {p: loss_stats(S.by_period(tr, p)) for p in pers}
            r["removed_candidates"] = removed
            for p in pers:
                r[p]["changed_vs_today"] = L.changed(S.by_period(tr, p), S.by_period(base, p))
                r[p]["open_0915_0930"] = L.stats(L.open_slice(S.by_period(tr, p)))
            if twins:
                act = loss_sum((t["day"], t["net"]) for t in S.by_period(tr, "final"))
                if kind == "filter":
                    tw = filter_twins(arm, tf, us, lo, hi, TWINS, rng)
                    r["twin"] = L.twin_p(act, tw)
                else:
                    tw, nch = exit_twins(tr, base_res, lo, hi, TWINS, rng)
                    r["twin"] = L.twin_p(act, tw)
                    r["twin"]["sold_earlier"] = nch
                r["twin"]["rule_loss_sum"] = round(act)
            res[test] = r
            print(arm, phase, test, {p: (r[p]["trades"], r[p]["net"], r[p]["loss_sum"], r[p]["max_dd"]) for p in pers},
                  "today", {p: (res["today"][p]["trades"], res["today"][p]["net"], res["today"][p]["loss_sum"],
                                res["today"][p]["max_dd"]) for p in pers}, flush=True)
        out["arms"][arm] = res
    return out


def run(phase: str):
    RESULTS.mkdir(parents=True, exist_ok=True)
    if phase == "final":
        if FINAL_FLAG.exists():
            raise SystemExit("R140 final already read once: " + FINAL_FLAG.read_text())
        assert (RESULTS / "design.json").exists(), "the design read comes first"
        FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat() + "\n")
        S.mods()["C"].open_final()
        out = evaluate("final", K.FINAL_START, K.FINAL_END, twins=True)
    else:
        out = evaluate("design", K.START, K.DESIGN_END, twins=False)
    out["_meta"]["written"] = datetime.now(timezone.utc).isoformat()
    (RESULTS / f"{phase}.json").write_text(json.dumps(out, indent=1, default=str))


def decide() -> dict:
    d = json.loads((RESULTS / "design.json").read_text())["arms"]
    f = json.loads((RESULTS / "final.json").read_text())["arms"]
    tests = list(TESTS)
    ps = [f[TESTS[t][0]][t]["twin"]["p"] for t in tests]
    q_own = L.bh(ps)
    q_all = L.bh(ps, K.N_PRIOR_ALL + len(tests))
    out = {}
    for t, qo, qa in zip(tests, q_own, q_all):
        arm = TESTS[t][0]
        rule = {"design": d[arm][t]["design"], "wf": d[arm][t]["wf"], "final": f[arm][t]["final"]}
        base = {"design": d[arm]["today"]["design"], "wf": d[arm]["today"]["wf"], "final": f[arm]["today"]["final"]}
        g = gates(rule, base, float(qo), float(ps[tests.index(t)]), int(f[arm][t]["final"]["changed_vs_today"]))
        g.update(p=ps[tests.index(t)], q=round(float(qo), 4), q_all_families=float(qa))
        out[t] = g
    (RESULTS / "decision.json").write_text(json.dumps(out, indent=1))
    return out


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd in ("design", "final"):
        run(cmd)
    elif cmd == "decide":
        print(json.dumps(decide(), indent=1))
    else:
        raise SystemExit(__doc__)
