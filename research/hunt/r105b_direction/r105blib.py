"""R105B pure pieces (PREREG sections 1 and 3): config, the keep lists, walks, random-skip twins, stats, the gate and
BH with the earlier family. No data: tests/test_r105b.py covers them.

    lake      $IRAALGO_LAKE: index_1m, index_daily, options_rolling_1m (rclone from gdrive:IraAlgo-data-lake)
    stores    R51's Amendment A clean stores (R51_CLEAN=1); OOS builds its own copy to 19 Oct (r105/r105study.py build_ext)
    trades    $R105B_OUT (default ~/.cache/iraalgo/r105b), never in git
    results   research/hunt/r105b_direction/results (small JSON / markdown only)
"""
from __future__ import annotations

import os
import re
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
CACHE_ROOT = Path(os.environ.get("IRAALGO_CACHE", Path.home() / ".cache" / "iraalgo")).expanduser()
OUT = Path(os.environ.get("R105B_OUT", CACHE_ROOT / "r105b")).expanduser()
RESULTS = HERE / "results"
FINAL_FLAG = RESULTS / "FINAL_READ"
OOS_FLAG = RESULTS / "OOS_READ"
ENTRIES = ROOT / "research" / "experiments" / "entries"

START, DESIGN_END, FINAL_START, FINAL_END = date(2024, 11, 20), date(2026, 3, 31), date(2026, 4, 1), date(2026, 10, 13)
OOS_START, OOS_END = date(2026, 10, 14), date(2026, 10, 19)
WF_Q = ((date(2025, 4, 1), date(2025, 6, 30)), (date(2025, 7, 1), date(2025, 9, 30)),
        (date(2025, 10, 1), date(2025, 12, 31)), (date(2026, 1, 1), date(2026, 3, 31)))
WF_START = WF_Q[0][0]

ARMS = ("R72", "R51T", "LIQ", "R52")
ARM_NAMES = {"R72": "R72 Breadth Donchian", "R51T": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5",
             "R52": "R52 Open outside value"}
EXPECT = {("R72", "design"): (3071, 216929), ("R72", "final"): (1202, 171366),
          ("R51T", "design"): (4208, 50425), ("R51T", "final"): (1670, 134743),
          ("LIQ", "design"): (881, 257249), ("LIQ", "final"): (357, 11155),
          ("R52", "design"): (None, 290573), ("R52", "final"): (None, 48612)}

TWINS, SEED = 500, 1052
GATE_P, GATE_Q, MIN_FINAL, DD_TOL = 0.05, 0.10, 40, 0.10


# ================================================================================================ walks (R101's, pure)
def walk_book(cands, res, keep=None):
    out, busy = [], -1
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        if c["er"] <= busy:
            continue
        busy = r["xr"]
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def walk_side2(cands, res, keep=None, max_per_side=2):
    out, busy, n_side = [], {}, {}
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        if c["er"] <= busy.get(c["side"], -1):
            continue
        if n_side.get(c["side"], 0) >= max_per_side:
            continue
        busy[c["side"]] = r["xr"]
        n_side[c["side"]] = n_side.get(c["side"], 0) + 1
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        out.append(t)
    return out


def walk_one(cands, res, keep=None):
    for j, (c, r) in enumerate(zip(cands, res)):
        if r is None or (keep is not None and not keep[j]):
            continue
        t = dict(r); t.update({k: v for k, v in c.items() if k not in t and k != "fx"})
        return [t]
    return []


WALK = {"book": walk_book, "side2": walk_side2, "one": walk_one}


def run_keep(units: list, keeps: list) -> list:
    """Trades of the arm with one keep list per unit (None = keep all)."""
    tr = []
    for (key, cands, res, how), kp in zip(units, keeps):
        tr += WALK[how](cands, res, kp)
    for t in tr:
        t["day"] = t["day"] if isinstance(t["day"], date) else t["day"].date()
    tr.sort(key=lambda t: (t["day"], t["und"], t["er"], t.get("book", 0) or 0))
    return tr


def keeps_for(units: list, base_keep, bias_of=None) -> list:
    """base_keep(c) (T7 for R51T, else True) AND the bias's keep (bias_of(c) -> +1 / -1 / 0)."""
    from r105bbias import keep
    out = []
    for key, cands, res, how in units:
        out.append([bool(base_keep(c)) and (bias_of is None or keep(c["side"], bias_of(c))) for c in cands])
    return out


def twin_totals(units: list, base: list, var: list, n: int, rng) -> np.ndarray:
    """Random-skip twins (R101): drop as many of the baseline's kept candidates at random as the bias dropped, walk."""
    pool = [(g, j) for g, kp in enumerate(base) for j, k in enumerate(kp) if k]
    k = sum(1 for g, j in pool if not var[g][j])
    tot = np.zeros(n)
    for i in range(n):
        drop = {pool[x] for x in rng.choice(len(pool), size=k, replace=False)} if k else set()
        kp = [[b and (g, j) not in drop for j, b in enumerate(base[g])] for g in range(len(base))]
        tot[i] = sum(t["net"] for t in run_keep(units, kp))
    return tot


def p_of(tw, act: float) -> float:
    return round((1 + int((np.asarray(tw) >= act - 1e-9).sum())) / (len(tw) + 1), 4)


# ================================================================================================ stats and gate
def split(tr, lo, hi):
    return [t for t in tr if lo <= t["day"] <= hi]


def stats(tr: list) -> dict:
    n = len(tr)
    if not n:
        return dict(trades=0, win=None, net=0, worst_day=0, max_dd=0, ex_top5=0)
    nets = np.array([t["net"] for t in tr], float)
    dl = defaultdict(float)
    for t in tr:
        dl[t["day"]] += t["net"]
    seq = np.array([dl[d] for d in sorted(dl)])
    eq = np.cumsum(seq)
    dd = float((np.maximum.accumulate(np.concatenate([[0.0], eq]))[1:] - eq).max())
    return dict(trades=n, win=round(float((nets > 0).mean()), 4), net=round(float(nets.sum())),
                worst_day=round(float(seq.min())), max_dd=round(dd),
                ex_top5=round(float(nets.sum() - np.sort(nets)[::-1][:5].sum())), days=len(dl))


def quarters(tr):
    return [round(sum(t["net"] for t in split(tr, a, b))) for a, b in WF_Q]


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


PRIOR_IDS = ("r72-", "r73-", "r74-", "r75-", "r79-", "r80-", "r101-", "r104-", "r105-")
_P_RE = re.compile(r"\b(?:twin p|p|exit-twin p|twin-p)\s*[=:]?\s*(0?\.\d+|1\.0+|1)\b", re.I)
_ARM_RE = re.compile(r"donchian|breadth|liquidity|open outside value|\bOOV\b|\bR5[12]\b|\bR72\b", re.I)


def prior_family(entries_dir: Path = ENTRIES, r104_unlogged: int = 26) -> list:
    out = []
    for f in sorted(entries_dir.glob("*.yaml")):
        if not f.name.startswith(PRIOR_IDS):
            continue
        txt = f.read_text(errors="replace")
        if not _ARM_RE.search(txt):
            continue
        ps = [float(x) for x in _P_RE.findall(txt)]
        ps = [x for x in ps if 0.0 < x <= 1.0]
        out.append((f.stem, max(ps) if ps else 1.0))
    logged = sum(1 for i, _ in out if i.startswith("r104-"))
    out += [(f"r104-arm-unlogged-{j}", 1.0) for j in range(max(0, r104_unlogged - logged))]
    return out


def family_q(new_p: dict, prior: list) -> dict:
    names = list(new_p)
    q = bh([new_p[n] for n in names] + [p for _, p in prior])
    return {n: round(float(q[i]), 4) for i, n in enumerate(names)}


def gate(r: dict) -> dict:
    """PREREG section 3 on one test (r: the test's design / final figures and its baseline's)."""
    parts = dict(
        a_design=r["design"]["net"] > r["base_design"]["net"],
        a_wf=r["wf_net"] > r["base_wf_net"],
        a_final=r["final"]["net"] > r["base_final"]["net"],
        a_quarters=sum(x > y for x, y in zip(r["q"], r["base_q"])) >= 3,
        b_twin=r["p_design"] < GATE_P and r["p_final"] < GATE_P,
        c_bh=r["q_bh"] <= GATE_Q,
        d_trades=r["final"]["trades"] >= MIN_FINAL,
        e_top5=(r["design"]["ex_top5"] > r["base_design"]["ex_top5"] and r["final"]["ex_top5"] > r["base_final"]["ex_top5"]),
        f_dd=r["final"]["max_dd"] <= r["base_final"]["max_dd"] * (1 + DD_TOL),
    )
    return dict(parts=parts, passed=all(parts.values()), failed=[k for k, v in parts.items() if not v])
