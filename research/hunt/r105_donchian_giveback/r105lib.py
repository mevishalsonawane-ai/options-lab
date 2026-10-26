"""R105 pure pieces (PREREG.md): the rule table, stats, the giveback cohort, the gate, BH with the earlier Donchian
family, random-exit twin totals and the paper bootstrap. No data, no lake: test_r105.py covers all of it.

    lake      $IRAALGO_LAKE (default ~/.cache/iraalgo/lake), copied with rclone from gdrive:IraAlgo-data-lake
    stores    R51's Amendment A clean stores (R51_CLEAN=1); the OOS read builds its own copy to 19 Oct (r105study.py)
    trades    $R105_OUT (default ~/.cache/iraalgo/r105): priced trades (never in git)
    results   research/hunt/r105_donchian_giveback/results (small JSON / markdown only)
"""
from __future__ import annotations

import math
import os
import re
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R105_OUT", CACHE_ROOT / "r105")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OOS_FLAG = RESULTS / "OOS_READ"
ENTRIES = ROOT / "research" / "experiments" / "entries"

START, DESIGN_END, FINAL_START, FINAL_END = date(2024, 11, 20), date(2026, 3, 31), date(2026, 4, 1), date(2026, 10, 13)
OOS_START, OOS_END = date(2026, 10, 14), date(2026, 10, 19)
WF_QUARTERS = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
               (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
WF_START, WF_END = WF_QUARTERS[0][0], WF_QUARTERS[-1][1]

ARMS = ("C", "B")
ARM_NAMES = {"C": "R72 Breadth Donchian", "B": "R51 Donchian 20 + Supertrend"}
EXPECT = {("C", "design"): (3071, 216929), ("C", "final"): (1202, 171366),
          ("B", "design"): (4208, 50425), ("B", "final"): (1670, 134743)}

NAN = math.nan
# exits P: -15% stop, L25 (+5% once +25%), breakeven once +30%, +30% once +60%, +60% once +100%, 30 minutes, 15:15
P_STEPS = ((0.30, NAN), (0.60, 0.30), (1.00, 0.60), (0.25, 0.05))
P_STOP, P_TMAX, P_SQ = 0.15, 30, (15, 15)

# PREREG section 2. Keys of each rule: extra steps, the engine's new inputs, and 'part' (X9's two legs).
OFF = dict(steps=(), target=-1.0, be_rs=-1.0, tt_off=-1, tt_pk=0.0, gb_trig=-1.0, gb_keep=0.0, idx_mode=0, idx_arm=0.0)
RULES = {
    "X1": dict(steps=((0.10, NAN),)),
    "X2": dict(steps=((0.15, NAN),)),
    "X3": dict(steps=((0.15, 0.03),)),
    "X4": dict(be_rs=1000.0),
    "X5": dict(tt_off=15, tt_pk=0.08),
    "X6": dict(gb_trig=0.12, gb_keep=0.50),
    "X7": dict(idx_mode=1, idx_arm=0.0),
    "X8": dict(idx_mode=1, idx_arm=0.10),
    "X9": dict(part=True),
    "X10": dict(steps=((0.15, NAN),), idx_mode=2, idx_arm=0.15),
}
X9_LEG1 = dict(target=0.15)
X9_LEG2 = dict(steps=((0.15, NAN),))
NAMES = {
    "X1": "BE10: stop to breakeven once +10%",
    "X2": "BE15: stop to breakeven once +15%",
    "X3": "L3AT15: stop to +3% once +15%",
    "X4": "RS1K: stop to breakeven once the open profit reaches Rs 1,000",
    "X5": "TT15: after 15 minutes, breakeven if it has been +8%",
    "X6": "GB50: once +12%, keep half of the best gain",
    "X7": "IDX0: index close back through the breakout level (from the fill)",
    "X8": "IDX10: X7, armed once +10%",
    "X9": "PART15 (Carli / Douglas): 1 of 2 lots at +15%, the other to breakeven",
    "X10": "TOM05 (Trader Tom): breakeven at +1R (+15%), then trail the last 10-minute index bar",
}
SOURCE = {"X1": "premium", "X2": "premium", "X3": "premium", "X4": "premium (rupees)", "X5": "premium + time",
          "X6": "premium", "X7": "index", "X8": "index (armed by premium)", "X9": "premium (book)",
          "X10": "premium + index (book)"}
TESTS = [f"{a}:{x}" for a in ARMS for x in RULES]

TWINS, SEED, BOOT = 500, 105, 20000
GATE_P, GATE_Q, MIN_CHANGED, TOL = 0.05, 0.10, 30, 0.02
PAPER = {"C": dict(n=11, net=-3583.0), "B": dict(n=19, net=-11997.0), "B14": dict(n=14, net=-16188.0),
         "B14plain": dict(n=14, net=-5591.0)}
PAPER_SESSIONS = 6                          # 19, 20, 21, 22, 23, 26 Oct
COHORT_RS = (1000.0, 2000.0)
COHORT_PCT = (0.08, 0.15, 0.25)


def rule_spec(rule: str | None) -> dict:
    """The engine inputs of a rule on top of exits P (None = the baseline). X9 returns its two legs."""
    if rule is None:
        return dict(OFF, steps=P_STEPS)
    r = RULES[rule]
    if r.get("part"):
        return dict(part=True, leg1=dict(OFF, steps=P_STEPS, **X9_LEG1),
                    leg2=dict(OFF, steps=P_STEPS + X9_LEG2["steps"]))
    s = dict(OFF, **{k: v for k, v in r.items() if k != "steps"})
    s["steps"] = P_STEPS + tuple(r.get("steps", ()))
    return s


# ================================================================================================ stats
def period_of(d: date) -> str | None:
    if START <= d <= DESIGN_END:
        return "design"
    if FINAL_START <= d <= FINAL_END:
        return "final"
    if OOS_START <= d <= OOS_END:
        return "oos"
    return None


def quarter_of(d: date) -> int | None:
    for i, (a, b) in enumerate(WF_QUARTERS):
        if a <= d <= b:
            return i
    return None


def daily(trades: list) -> dict:
    out = defaultdict(float)
    for t in trades:
        out[t["day"]] += t["net"]
    return dict(out)


def max_dd(day_nets) -> float:
    eq = np.cumsum(np.asarray(day_nets, float))
    if not len(eq):
        return 0.0
    peak = np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:]
    return float((peak - eq).max())


def stats(trades: list) -> dict:
    n = len(trades)
    if not n:
        return dict(trades=0, net=0, win=None, worst_day=0, max_dd=0, days=0)
    net = np.array([t["net"] for t in trades], float)
    dl = daily(trades)
    dv = np.array([dl[d] for d in sorted(dl)])
    return dict(trades=n, net=round(float(net.sum())), win=round(float((net > 0).mean()), 4),
                avg_win=round(float(net[net > 0].mean())) if (net > 0).any() else None,
                avg_loss=round(float(net[net <= 0].mean())) if (net <= 0).any() else None,
                worst_day=round(float(dv.min())), max_dd=round(max_dd(dv)), days=len(dl),
                green_days=round(float((dv > 0).mean()), 4))


def split(trades: list, lo: date, hi: date) -> list:
    return [t for t in trades if lo <= t["day"] <= hi]


def quarters(trades: list) -> list:
    return [round(sum(t["net"] for t in split(trades, a, b))) for a, b in WF_QUARTERS]


# ================================================================================================ the cohort
def cohort(trades: list) -> dict:
    """PREREG section 4 on a list of trades carrying 'pk_rs' (best open profit, Rs) and 'pk' (best gain, fraction)."""
    def grp(sel):
        xs = [t for t in trades if sel(t)]
        return dict(n=len(xs), net=round(sum(t["net"] for t in xs)), keys=[key(t) for t in xs])
    lo, hi = COHORT_RS
    a, b, c = COHORT_PCT
    lost = lambda t: t["net"] < 0  # noqa: E731
    return {
        "all": dict(n=len(trades), net=round(sum(t["net"] for t in trades))),
        "reached_rs1000": grp(lambda t: t["pk_rs"] >= lo),
        "rs1000_lost": grp(lambda t: t["pk_rs"] >= lo and lost(t)),
        "rs1000_2000_lost": grp(lambda t: lo <= t["pk_rs"] < hi and lost(t)),
        "rs2000plus_lost": grp(lambda t: t["pk_rs"] >= hi and lost(t)),
        "pct8_25_lost": grp(lambda t: a <= t["pk"] < c and lost(t)),
        "pct8_15_lost": grp(lambda t: a <= t["pk"] < b and lost(t)),
        "pct15_25_lost": grp(lambda t: b <= t["pk"] < c and lost(t)),
        "pct25plus_lost": grp(lambda t: t["pk"] >= c and lost(t)),
        "never8": grp(lambda t: t["pk"] < a),
    }


def key(t: dict) -> tuple:
    return (t["und"], str(t["day"]), t["side"], int(t["er"]))


def drop_keys(obj):
    """The result files keep each cohort's count and total, not its per-trade key list (keeps them small)."""
    if isinstance(obj, dict):
        return {k: drop_keys(v) for k, v in obj.items() if k != "keys"}
    if isinstance(obj, list):
        return [drop_keys(v) for v in obj]
    return obj


def same_trades(trades: list, keys) -> float:
    ks = {tuple(k) for k in keys}
    return round(sum(t["net"] for t in trades if key(t) in ks))


# ================================================================================================ twins, BH, gate
def twin_totals(base: list, k: int, n: int, rng, net_forced, leg_weight: float = 1.0) -> np.ndarray:
    """n sets of random-minute exits (PREREG 3c): each picks min(k, eligible) baseline trades held >= 2 rows and sells
    each at the open of a uniform row in (er, xr]. net_forced(trade, rows) -> nets. leg_weight 0.5 = X9's lot 1."""
    tot = np.full(n, float(sum(t["net"] for t in base)))
    elig = [i for i, t in enumerate(base) if t["xr"] - t["er"] >= 2]
    m = min(k, len(elig))
    if m == 0:
        return tot
    req = defaultdict(lambda: ([], []))
    for s in range(n):
        for j in rng.choice(len(elig), size=m, replace=False):
            t = base[elig[j]]
            req[elig[j]][0].append(s)
            req[elig[j]][1].append(int(rng.integers(t["er"] + 1, t["xr"] + 1)))
    for i in sorted(req):
        sets, rows = req[i]
        nets = np.asarray(net_forced(base[i], np.asarray(rows, np.int64)), float)
        np.add.at(tot, np.asarray(sets), leg_weight * (nets - base[i]["net"]))
    return tot


def p_of(tw: np.ndarray, actual: float) -> float:
    return round((1 + int((np.asarray(tw) >= actual - 1e-9).sum())) / (len(tw) + 1), 4)


def bh(p) -> np.ndarray:
    p = np.asarray(p, float)
    n = len(p)
    if not n:
        return p
    o = np.argsort(p)
    q = p[o] * n / np.arange(1, n + 1)
    q = np.minimum.accumulate(q[::-1])[::-1]
    out = np.empty(n)
    out[o] = np.minimum(q, 1.0)
    return out


PRIOR_IDS = ("r63-", "r64-", "r65-", "r66-", "r67-", "r68-", "r69-", "r72-", "r73-", "r75-", "r101-", "r104-")
_P_RE = re.compile(r"\b(?:twin p|p|exit-twin p|twin-p)\s*[=:]?\s*(0?\.\d+|1\.0+|1)\b", re.I)


def prior_family(entries_dir: Path = ENTRIES) -> list:
    """PREREG 3d: [(id, p)] of the earlier Donchian tests (largest p stated, else 1.0), plus R104 F11 at p = 1 for
    the tests not logged one by one."""
    out = []
    for f in sorted(entries_dir.glob("*.yaml")):
        if not f.name.startswith(PRIOR_IDS):
            continue
        txt = f.read_text(errors="replace")
        if not re.search(r"donchian|breadth|\bR51\b|\bR72\b", txt, re.I):
            continue
        ps = [float(x) for x in _P_RE.findall(txt)]
        ps = [x for x in ps if 0.0 < x <= 1.0]
        out.append((f.stem, max(ps) if ps else 1.0))
    logged_f11 = sum(1 for i, _ in out if i.startswith("r104-") and "f11" in i)
    out += [(f"r104-f11-unlogged-{j}", 1.0) for j in range(max(0, 20 - logged_f11))]
    return out


def family_q(new_p: dict, prior: list) -> dict:
    """BH over the new tests and the prior family together; returns {test: q} for the new tests only."""
    names = list(new_p)
    allp = [new_p[n] for n in names] + [p for _, p in prior]
    q = bh(allp)
    return {n: round(float(q[i]), 4) for i, n in enumerate(names)}


def not_worse(r: dict, b: dict) -> bool:
    """PREREG 3b: worst day and max DD within 2% of the baseline's."""
    return (r["worst_day"] >= b["worst_day"] - TOL * abs(b["worst_day"]) and r["max_dd"] <= b["max_dd"] * (1 + TOL))


def ex_top5_gain(deltas) -> float:
    d = np.sort(np.asarray(deltas, float))[::-1]
    pos = d[d > 0][:5]
    return float(d.sum() - pos.sum())


def gate(r: dict) -> dict:
    """PREREG section 3 on one test's result dict; returns each part and the verdict."""
    parts = dict(
        a_design=r["design"]["net"] > r["base_design"]["net"],
        a_wf=r["wf_net"] > r["base_wf_net"],
        a_final=r["final"]["net"] > r["base_final"]["net"],
        a_quarters=sum(x > y for x, y in zip(r["q"], r["base_q"])) >= 3,
        b_design=not_worse(r["design"], r["base_design"]),
        b_final=not_worse(r["final"], r["base_final"]),
        c_twin=r["p_design"] < GATE_P and r["p_final"] < GATE_P,
        d_bh=r["q_bh"] <= GATE_Q,
        e_changed=r["changed_design"] >= MIN_CHANGED,
        f_ex_top5=r["ex_top5_design"] >= 0,
        g_slip2=r["slip2_final_net"] > r["base_slip2_final_net"],
    )
    return dict(parts=parts, passed=all(parts.values()), failed=[k for k, v in parts.items() if not v])


# ================================================================================================ paper bootstrap
def boot_iid(nets, n: int, draws: int, rng) -> np.ndarray:
    x = np.asarray(nets, float)
    return x[rng.integers(0, len(x), size=(draws, n))].sum(axis=1)


def runs(values, n: int) -> np.ndarray:
    """Every sum of n consecutive values (time order)."""
    x = np.asarray(values, float)
    if len(x) < n:
        return np.zeros(0)
    c = np.concatenate([[0.0], np.cumsum(x)])
    return c[n:] - c[:-n]


def pct_le(dist, value: float) -> dict:
    d = np.asarray(dist, float)
    if not len(d):
        return dict(n=0, p_le=None, pctile=None)
    p = float((d <= value + 1e-9).mean())
    return dict(n=int(len(d)), p_le=round(p, 4), pctile=round(100 * p, 1), median=round(float(np.median(d))),
                p05=round(float(np.percentile(d, 5))), p95=round(float(np.percentile(d, 95))))
