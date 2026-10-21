"""R54 study (PREREG.md).

    python3 research/hunt/r54/r54study.py design     # 24 cells on DESIGN, twins, BH, paired comparisons -> design.json
    python3 research/hunt/r54/r54study.py final      # the ONE read of the final test -> final.json (refuses a second run)

Run r54liq.py first (results/liquidity.json: the late-minute multipliers, measured on design days). Trade lists go to
<OUT>/trades (not in git).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time
from multiprocessing import Pool

import numpy as np

import r54cfg as K
import r54data as DD
import r54lib as LB

DAYS: list = []          # set before the pool forks


def load(part: str) -> list:
    out = []
    for u in K.UNDS:
        out += DD.load(u, part)
    out.sort(key=lambda d: (d.day, d.und))
    return out


def cells() -> list[tuple[str, str, str]]:
    return [(e, z, x) for e in K.ENTRIES for z in K.ENDS for x in K.EXITS]


def cid(c) -> str:
    return "-".join(c)


def _twin_job(args):
    i, end, ex = args
    d = DAYS[i]
    return (d.und, d.day), end, ex, LB.twin_table(d, end, ex)


def twin_tables(procs: int) -> dict:
    """{(end, exit): {(und, date): {(sgn, t): net}}}: every minute of the twin window, computed once per end / exit."""
    jobs = [(i, z, x) for i in range(len(DAYS)) for z in K.ENDS for x in K.EXITS]
    out: dict = {}
    with Pool(procs) as pool:
        for key, z, x, tb in pool.imap_unordered(_twin_job, jobs, chunksize=4):
            out.setdefault((z, x), {})[key] = tb
    return out


def callouts(trades: list) -> list:
    out = []
    for t in trades:
        if (t.und, str(t.day)) in K.CALLOUTS:
            out.append(dict(und=t.und, d=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike,
                            entry=t.entry, fill_row=t.fill_row, exit=t.exits[-1][2], why=t.exits[-1][3],
                            peak=round(t.peak_mult, 1), net=t.net))
    return out


def no_guard(e: str, z: str, x: str) -> float:
    """Informational only (not a gate input): the cell's net with R47's stray-print guard switched off."""
    LB.GUARD = False
    try:
        return round(sum(t.net for t in LB.run_cell(DAYS, e, z, x)), 0)
    finally:
        LB.GUARD = True


def evaluate(part: str, procs: int) -> dict:
    LB.load_late()
    t0 = time.time()
    tables = twin_tables(procs)
    print("twin tables", round(time.time() - t0), "s", flush=True)
    res, dly = {}, {}
    (K.OUT / "trades").mkdir(parents=True, exist_ok=True)
    for c in cells():
        e, z, x = c
        tr = LB.run_cell(DAYS, e, z, x)
        m = LB.metrics(tr, DAYS)
        tw = LB.twins(tr, tables[(z, x)])
        r = dict(cell=cid(c), entry=e, end=z, exit=x, m=m, p=LB.p_value(m["net"], tw) if tr else 1.0,
                 twin_mean=round(float(tw.mean()), 0),
                 spread2x=round(sum(t.net for t in LB.run_cell(DAYS, e, z, x, mult=2.0)), 0),
                 callouts=callouts(tr), no_guard_net=no_guard(e, z, x))
        res[cid(c)] = r
        dly[cid(c)] = LB.daily(tr, DAYS)
        (K.OUT / "trades" / f"{part}_{cid(c)}.pkl").write_bytes(pickle.dumps([dict(t.row(), exits=t.exits) for t in tr]))
        print(part, cid(c), m["tickets"], m["net"], round(r["p"], 3), flush=True)
    qs = LB.bh([res[cid(c)]["p"] for c in cells()])
    for c, q in zip(cells(), qs):
        res[cid(c)]["q"] = q
    comp = {}
    for z in K.ENDS:
        for x in K.EXITS:
            comp[f"1430 vs 1230 | {z}-{x}"] = LB.paired(dly[f"1430-{z}-{x}"], dly[f"1230-{z}-{x}"])
    for e in K.ENTRIES:
        for x in K.EXITS:
            for z in ("1510", "1515"):
                comp[f"{z} vs 1505 | {e}-{x}"] = LB.paired(dly[f"{e}-{z}-{x}"], dly[f"{e}-1505-{x}"])
    return dict(part=part, n_days=len(DAYS), by_index={u: sum(d.und == u for d in DAYS) for u in K.UNDS},
                late_mult=LB.LATE, cells=res, compare=comp, bh_family=len(qs))


def _write(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str) + "\n")


def cmd_design(procs: int) -> None:
    DAYS[:] = load("design")
    _write("design.json", evaluate("design", procs))


def cmd_final(procs: int) -> None:
    if (K.RESULTS / "final.json").exists():
        sys.exit("final.json exists: the final test was already read once (PREREG.md)")
    des = json.loads((K.RESULTS / "design.json").read_text())
    DAYS[:] = load("final")
    out = evaluate("final", procs)
    for k, r in out["cells"].items():
        r["gates"] = LB.gates(r["m"], r["p"], r["q"], des["cells"][k]["m"]["net"])
    for k, v in out["compare"].items():
        v["reading"] = LB.reading(des["compare"][k], v)
    out["passed"] = [k for k, r in out["cells"].items() if r["gates"]["pass"]]
    _write("final.json", out)


if __name__ == "__main__":
    procs = int(os.environ.get("R54_PROCS", os.cpu_count() or 2))
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        cmd_design(procs)
    elif cmd == "final":
        cmd_final(procs)
    else:
        sys.exit(__doc__)
