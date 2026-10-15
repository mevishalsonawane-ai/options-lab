"""R50 study (PREREG.md).

    python3 research/hunt/r50/r50study.py design     # originals + re-tune grids on DESIGN, walk-forward, twins -> design.json
    python3 research/hunt/r50/r50study.py final      # the ONE read of the final test -> final.json (refuses a second run)

Both write small JSON to results/; trade lists go to <OUT> (not in git).
"""
from __future__ import annotations

import json
import os
import pickle
import sys
import time
from multiprocessing import Pool

import numpy as np

import r50cfg as K
import r50data as DD
import r50lib as L
import r50rules as RR

DAYS: dict = {}          # part -> [Day] (all indices, date order); set before the pool forks


def load(part: str) -> list:
    if part not in DAYS:
        out = []
        for u in K.UNDS:
            out += DD.load(u, part)
        out.sort(key=lambda d: (d.day, d.und))
        DAYS[part] = out
    return DAYS[part]


def prepare() -> None:
    """GEX flags read every expiry from 2024 (the scale's warm-up) - computed once, before the pool forks."""
    key = K.OUT / "gex_flags.pkl"
    if key.exists():
        L.GEX_FLAGS.update(pickle.loads(key.read_bytes()))
        return
    warm = {u: DD.load(u, "warm") for u in K.UNDS}
    fl = L.gex_flags(warm)
    key.write_bytes(pickle.dumps(fl))


def n_days(days: list, rs) -> int:
    return sum(d.und in rs.unds for d in days)


def evaluate(args) -> dict:
    """One rule on one part: trades, metrics, blocks, 2x-spread net and (twins=True) the random-entry p."""
    rs, part, with_twins = args
    days = DAYS[part]
    t0 = time.time()
    trades = L.run(days, rs)
    out = dict(id=rs.id, part=part, m=L.metrics(trades, n_days(days, rs)), blocks=L.by_block(trades))
    out["spread2x"] = round(sum(t.net for t in L.run(days, rs, mult=2.0)), 0)
    if with_twins and trades:
        tw = L.twins(days, rs, trades)
        out["twin_mean"] = round(float(tw.mean()), 0)
        out["p"] = L.p_value(out["m"]["net"], tw)
    elif with_twins:
        out["p"] = 1.0
    out["by_index"] = {}
    for t in trades:
        a = out["by_index"].setdefault(t.und, [0, 0.0])
        a[0] += 1
        a[1] = round(a[1] + t.net, 2)
    (K.OUT / "trades").mkdir(parents=True, exist_ok=True)
    (K.OUT / "trades" / f"{part}_{rs.id.replace(':', '_')}.pkl").write_bytes(pickle.dumps([t.row() for t in trades]))
    out["secs"] = round(time.time() - t0, 1)
    print(rs.id, part, out["m"]["trades"], out["m"]["net"], out.get("p"), out["secs"], flush=True)
    return out


def walk_forward(cells: dict) -> dict:
    """cells: {name: design result}. For B1..B4 the cell with the best net on B0..B(i-1) trades B(i)."""
    names = list(cells)
    bl = [b for b, _, _ in K.BLOCKS]
    picks, wf = {}, 0.0
    for i in range(1, len(bl)):
        train = {n: sum(cells[n]["blocks"][b] for b in bl[:i]) for n in names}
        best = max(names, key=lambda n: (train[n], -names.index(n)))
        picks[bl[i]] = best
        wf += cells[best]["blocks"][bl[i]]
    full = max(names, key=lambda n: (cells[n]["m"]["net"], -names.index(n)))
    return dict(picks=picks, wf_net=round(wf, 0), chosen=full)


def _write(name: str, obj) -> None:
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / name).write_text(json.dumps(obj, indent=1, default=str) + "\n")


def expiry_list() -> dict:
    return {u: [str(d) for d in DD.calendar(u)] for u in K.UNDS}


def cmd_design(procs: int) -> None:
    load("design")
    prepare()
    _write("expiry_days.json", expiry_list())
    jobs = [(rs, "design", True) for rs in RR.ORIGINALS]
    for tid, cells in RR.RETUNES.items():
        jobs += [(rs, "design", False) for _, rs in cells]
    with Pool(procs) as pool:
        res = pool.map(evaluate, jobs, chunksize=1)
    by = {r["id"]: r for r in res}
    out = {"originals": {rs.id: by[rs.id] for rs in RR.ORIGINALS}, "retunes": {}}
    for rs in RR.ORIGINALS:
        o = out["originals"][rs.id]
        o["wf_net"] = round(sum(o["blocks"][b] for b, _, _ in K.BLOCKS[1:]), 0)
    for tid, cells in RR.RETUNES.items():
        cr = {name: by[rs.id] for name, rs in cells}
        out["retunes"][tid] = dict(cells=cr, **walk_forward(cr))
    _write("design.json", out)


def gates(r: dict, wf_net: float) -> dict:
    m = r["m"]
    g = {"net>0": m["net"] > 0, "p<0.05": r["p"] < K.GATE_P, "q<=0.10": r["q"] <= K.GATE_Q,
         "40+ trades": m["trades"] >= K.GATE_TRADES, "net without top 5 > 0": m["ex5"] > 0, "design WF > 0": wf_net > 0}
    g["pass"] = all(g.values())
    g["too_few"] = m["trades"] < K.GATE_TRADES
    return g


def cmd_final(procs: int) -> None:
    flag = K.RESULTS / "final.json"
    if flag.exists():
        sys.exit("final.json exists: the final test was already read once (PREREG.md)")
    des = json.loads((K.RESULTS / "design.json").read_text())
    load("final")
    prepare()
    jobs = [(rs, "final", True) for rs in RR.ORIGINALS]
    chosen = {}
    for tid, cells in RR.RETUNES.items():
        name = des["retunes"][tid]["chosen"]
        rs = dict(cells)[name]
        chosen[tid] = rs.id
        jobs.append((rs, "final", True))
    with Pool(procs) as pool:
        res = pool.map(evaluate, jobs, chunksize=1)
    qs = L.bh([r["p"] for r in res])
    for r, q in zip(res, qs):
        r["q"] = q
    out = {"originals": {}, "retunes": {}, "bh_family": len(res)}
    for r in res:
        if r["id"] in RR.BY_ID:
            r["gates"] = gates(r, des["originals"][r["id"]]["wf_net"])
            out["originals"][r["id"]] = r
        else:
            tid = r["id"].split(":")[0]
            r["gates"] = gates(r, des["retunes"][tid]["wf_net"])
            out["retunes"][tid] = r
    out["passed"] = [r["id"] for r in res if r["gates"]["pass"]]
    _write("final.json", out)


if __name__ == "__main__":
    procs = int(os.environ.get("R50_PROCS", os.cpu_count() or 2))
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "design":
        cmd_design(procs)
    elif cmd == "final":
        cmd_final(procs)
    else:
        sys.exit(__doc__)
