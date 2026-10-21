"""R55 study (PREREG.md).

    python3 research/hunt/r55/r55study.py design     # 30 cells on DESIGN, twins, BH, paired comparisons -> design.json
    python3 research/hunt/r55/r55study.py final      # the ONE read of the final test -> final.json (refuses a second run)

Needs R54's day pickles (R54_OUT, `r54data.py build`) and R54's results/liquidity.json (the late-minute multipliers).
Trade lists go to <R55_OUT>/trades (not in git).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time
from multiprocessing import Pool

import r55cfg as K
import r54data as DD
import r54lib as LB
import r55lib as L

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


def _twin_job(i):
    LB.load_late()
    d = DAYS[i]
    return (d.und, d.day), L.twin_tables_day(d)


def twin_tables(procs: int) -> dict:
    """{(end, exit): {(und, date): {(sgn, t): net}}}, every minute of the twin window, computed once per day."""
    out: dict = {(z, x): {} for z in K.ENDS for x in K.EXITS}
    with Pool(procs) as pool:
        for key, tb in pool.imap_unordered(_twin_job, range(len(DAYS))):
            for zx, v in tb.items():
                out[zx][key] = v
    return out


def callouts(trades: list) -> list:
    out = []
    for t in trades:
        if (t.und, str(t.day)) in K.CALLOUTS:
            x = t.exits[-1]
            out.append(dict(und=t.und, d=str(t.day), side="CE" if t.side > 0 else "PE", strike=t.strike,
                            entry=t.entry, fill_row=t.fill_row, exit_row=x[0], exit=x[2], why=x[3],
                            armed_row=t.armed_row, peak=round(t.peak_mult, 1), net=t.net,
                            settle_px=round(getattr(t, "settle_px", float("nan")), 2) if t.settled else None))
    return out


def no_guard(e: str, z: str, x: str) -> float:
    """Informational only (R54 amendment 1; not a gate input): the cell's net with the stray-print guard off."""
    LB.GUARD = False
    try:
        return round(sum(t.net for t in L.run_cell(DAYS, e, z, x)), 0)
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
        tr = L.run_cell(DAYS, e, z, x)
        m = L.metrics(tr, DAYS)
        tw = L.twins(tr, tables[(z, x)], n=K.TWINS, seed=K.SEED)
        r = dict(cell=cid(c), entry=e, end=z, exit=x, m=m, p=L.p_value(m["net"], tw) if tr else 1.0,
                 twin_mean=round(float(tw.mean()), 0),
                 spread2x=round(sum(t.net for t in L.run_cell(DAYS, e, z, x, mult=2.0)), 0),
                 callouts=callouts(tr), no_guard_net=no_guard(e, z, x))
        res[cid(c)] = r
        dly[cid(c)] = L.daily(tr, DAYS)
        (K.OUT / "trades" / f"{part}_{cid(c)}.pkl").write_bytes(pickle.dumps(
            [dict(t.row(), exits=t.exits, settled=t.settled, armed_row=t.armed_row) for t in tr]))
        print(part, cid(c), m["tickets"], m["net"], round(r["p"], 3), flush=True)
    qs = L.bh([res[cid(c)]["p"] for c in cells()])
    for c, q in zip(cells(), qs):
        res[cid(c)]["q"] = q
    comp = {}
    for e in K.ENTRIES:
        for z in K.ENDS:
            for x in ("T2", "T3", "T5", "H"):
                comp[f"{x} vs T0 | {e}-{z}"] = L.paired(dly[f"{e}-{z}-{x}"], dly[f"{e}-{z}-T0"])
        for x in K.EXITS:
            for z in ("1515", "SETTLE"):
                comp[f"{z} vs 1505 | {e}-{x}"] = L.paired(dly[f"{e}-{z}-{x}"], dly[f"{e}-1505-{x}"])
    flagged = sorted({f"{d.und} {d.day}" for d in DAYS if L.settle_price(d.ix[:, 3])[1]})
    return dict(part=part, n_days=len(DAYS), by_index={u: sum(d.und == u for d in DAYS) for u in K.UNDS},
                late_mult=LB.LATE, cells=res, compare=comp, bh_family=len(qs), settle_flagged_days=flagged)


def best_trail(des: dict) -> dict:
    """PREREG: at 12:30, per end time, the trail start with the highest DESIGN net."""
    out = {}
    for z in K.ENDS:
        ks = [f"1230-{z}-{x}" for x in K.EXITS]
        out[z] = max(ks, key=lambda k: des["cells"][k]["m"]["net"]).split("-")[2]
    return out


def _write(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str) + "\n")


def cmd_design(procs: int) -> None:
    DAYS[:] = load("design")
    out = evaluate("design", procs)
    out["best_trail_design"] = best_trail(out)
    _write("design.json", out)


def cmd_final(procs: int) -> None:
    if (K.RESULTS / "final.json").exists():
        sys.exit("final.json exists: the final test was already read once (PREREG.md)")
    des = json.loads((K.RESULTS / "design.json").read_text())
    DAYS[:] = load("final")
    out = evaluate("final", procs)
    for k, r in out["cells"].items():
        r["gates"] = L.gates(r["m"], r["p"], r["q"], des["cells"][k]["m"]["net"])
    for k, v in out["compare"].items():
        v["reading"] = L.reading(des["compare"][k], v)
    out["passed"] = [k for k, r in out["cells"].items() if r["gates"]["pass"]]
    _write("final.json", out)


if __name__ == "__main__":
    procs = int(os.environ.get("R55_PROCS", os.cpu_count() or 2))
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        cmd_design(procs)
    elif cmd == "final":
        cmd_final(procs)
    else:
        sys.exit(__doc__)
