"""R104 Amendment 1: late-day momentum on the index (Baltussen, Da, Lammers, Martens 2021). Index only, no option.

    R103_LAKE=<lake> R103_OUT=<cache> R102_LAKE=<lake> python research/hunt/r104_follow/r104late.py design
    ... r104late.py holdout          # once, design passers only (results/LATE_HOLDOUT_READ)
"""
from __future__ import annotations

import json
import sys
from collections import defaultdict
from datetime import date
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / "r103_sell"))
import r103data as RD  # noqa: E402
import r103study as S3  # noqa: E402

RESULTS = HERE / "results"
FLAG = RESULTS / "LATE_HOLDOUT_READ"
COST = 0.0005
ROWS = {"N": (325, 355), "E": (320, 350)}        # 14:40 -> 15:10; expiry 14:35 -> 15:05
SEED = 1041
TESTS = [(u, t) for u in RD.UNDS for t in ("E", "N")]


def last_close(D) -> float:
    c = D.idx[3]
    ok = np.flatnonzero(np.isfinite(c))
    return float(c[ok[-1]]) if len(ok) else float("nan")


def rows_for(days: dict, exp: set, part: str) -> dict:
    """{'E' / 'N': [dict(day, r1, r2, pnl)]} with pnl = sign(r1) x r2 - COST (fractions)."""
    out = defaultdict(list)
    prev = None
    for d in sorted(days):
        D = days[d]
        pc = prev
        prev = last_close(D)
        if d < RD.START or pc is None or not np.isfinite(pc) or S3.period_of(d) != part:
            continue
        t = "E" if d in exp else "N"
        a, b = ROWS[t]
        x0, x1 = D.idx[3, a], D.idx[3, b]
        if not (np.isfinite(x0) and np.isfinite(x1)):
            continue
        r1, r2 = x0 / pc - 1, x1 / x0 - 1
        if r1 == 0:
            continue
        out[t].append(dict(day=d, r1=r1, r2=r2, pnl=float(np.sign(r1) * r2 - COST)))
    return out


def stats(rs: list, rng=None) -> dict:
    if not rs:
        return dict(n=0)
    p = np.array([x["pnl"] for x in rs]) * 1e4                       # bps
    r1 = np.array([x["r1"] for x in rs]); r2 = np.array([x["r2"] for x in rs])
    years = defaultdict(float)
    for x, v in zip(rs, p):
        years[x["day"].year] += v
    best5 = np.sort(p)[-5:].sum() if len(p) >= 5 else p.sum()
    out = dict(n=len(p), mean_bps=round(float(p.mean()), 2), gross_mean_bps=round(float(p.mean() + COST * 1e4), 2),
               hit=round(float(np.mean(np.sign(r1) == np.sign(r2))), 4), slope=round(float(np.polyfit(r1, r2, 1)[0]), 4),
               by_year={int(y): round(v, 1) for y, v in sorted(years.items())},
               post_regime_bps=round(float(sum(v for x, v in zip(rs, p) if x["day"] >= S3.NEW_REGIME)), 1),
               ex_best5_bps=round(float(p.sum() - best5), 1), total_bps=round(float(p.sum()), 1))
    if rng is not None:
        means = p[rng.integers(0, len(p), size=(S3.BOOT, len(p)))].mean(axis=1)
        out["boot_p"] = float((means <= 0).mean())
    return out


def run(part: str) -> dict:
    if part == "holdout":
        if FLAG.exists():
            raise SystemExit("the late-momentum holdout was already read once")
        dz = json.loads((RESULTS / "late_design.json").read_text())
        passed = set(dz["passed"])
        RESULTS.mkdir(parents=True, exist_ok=True)
        FLAG.write_text(f"read on {date.today().isoformat()}; passers {sorted(passed)}\n")
        if not passed:
            res = dict(part="holdout", read=False, note="no late-momentum test passed design; nothing read")
            (RESULTS / "late_holdout.json").write_text(json.dumps(res, indent=1) + "\n")
            return res
    rng = np.random.default_rng(SEED)
    rows = []
    for u in RD.UNDS:
        days = RD.load(u)
        exp = S3.expiry_days(u, days)
        byt = rows_for(days, exp, part)
        for t in ("E", "N"):
            tid = f"{u}-{t}-late"
            if part == "holdout" and tid not in passed:
                continue
            rows.append(dict(id=tid, **stats(byt.get(t, []), rng)))
    if part == "design":
        for r, q in zip(rows, S3.bh([r.get("boot_p", 1.0) for r in rows])):
            r["q_bh"] = round(q, 4)
            yrs = [r.get("by_year", {}).get(y, 0) for y in S3.WF_YEARS]
            r["pass"] = bool(r["n"] and r["mean_bps"] > 0 and r["boot_p"] < S3.BOOT_P and q <= S3.BH_Q
                             and sum(v > 0 for v in yrs) >= 3 and r["post_regime_bps"] > 0 and r["ex_best5_bps"] > 0)
        res = dict(part="design", passed=[r["id"] for r in rows if r["pass"]], tests=rows)
    else:
        for r in rows:
            r["confirmed"] = bool(r["n"] and r["mean_bps"] > 0)
        res = dict(part="holdout", read=True, tests=rows)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"late_{part}.json").write_text(json.dumps(res, indent=1, default=str) + "\n")
    return res


if __name__ == "__main__":
    part = sys.argv[1] if len(sys.argv) > 1 else ""
    if part not in ("design", "holdout"):
        raise SystemExit(__doc__)
    print(json.dumps(run(part), indent=1, default=str))
