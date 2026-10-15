#!/usr/bin/env python3
"""R49 study steps (PREREG.md). Research only, 15 Oct 2026.

    python3 research/hunt/r49/r49study.py design     # the 56 rules on DESIGN (21 Nov 2024 - 31 Mar 2026), quarters, walk-forward
    python3 research/hunt/r49/r49study.py final      # the ONE read of the FINAL TEST (1 Apr - 13 Oct 2026), twins, BH, gates
    python3 research/hunt/r49/r49study.py report     # results/report.md from design.json + final.json + R3's results
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from datetime import datetime, timezone

import numpy as np

import r49cfg as K
import r49data as D
import r49engine as R

SCOPES = ("pooled",) + K.UNDS


# ------------------------------------------------------------------------------------------------ sessions (guarded)
def sessions(part: str = "design") -> dict:
    """Sessions of every index for one period. 'final' only after `final` has written the read flag."""
    if part not in ("design", "final"):
        raise ValueError(part)
    if part == "final" and not K.FINAL_FLAG.exists():
        raise PermissionError("the final test is locked: only `r49study.py final` may read it (PREREG A2)")
    out = {}
    for u in K.UNDS:
        ss = [s for s in D.load(u) if K.period(s.day) == part]
        for s in ss:
            s.opts = {k: a.astype(np.float64) for k, a in s.opts.items()}
        out[u] = ss
    return out


def keys(sb: dict, unds=None, pred=None) -> list:
    return [(u, s.day) for u, ss in sb.items() if unds is None or u in unds for s in ss if pred is None or pred(s.day)]


def scope_trades(tr: list, scope: str) -> list:
    return tr if scope == "pooled" else [t for t in tr if t["und"] == scope]


def scope_keys(sb: dict, scope: str, pred=None) -> list:
    return keys(sb, None if scope == "pooled" else [scope], pred)


def blocks(tr: list, fn) -> dict:
    out = defaultdict(lambda: [0.0, 0])
    for t in tr:
        b = fn(t["day"])
        out[b][0] += t["net"]
        out[b][1] += 1
    return {b: [round(v[0]), v[1]] for b, v in sorted(out.items())}


def strip(t: dict) -> dict:
    return {k: (str(v) if k == "day" else v) for k, v in t.items()}


def evaluate(rs: list, sb: dict) -> tuple[dict, dict]:
    res, trades = {}, {}
    for r in rs:
        tr = []
        for u in K.UNDS:
            tr += R.run(sb[u], r)
        trades[r.id] = tr
        res[r.id] = {sc: dict(R.score(scope_trades(tr, sc), scope_keys(sb, sc)),
                              quarters=blocks(scope_trades(tr, sc), K.quarter),
                              months=blocks(scope_trades(tr, sc), lambda d: f"{d.year}-{d.month:02d}"),
                              stale=sum(t["why"] == "stale" for t in scope_trades(tr, sc)),
                              t_p=R.t_p(scope_trades(tr, sc), scope_keys(sb, sc)),
                              top5=R.top5_share(scope_trades(tr, sc)))
                     for sc in SCOPES}
        a = res[r.id]["pooled"]
        print(r.id, a["trades"], round(a["net"]), {u: round(res[r.id][u]["net"]) for u in K.UNDS}, flush=True)
    return res, trades


# ------------------------------------------------------------------------------------------------ design
QUARTERS = ("2024Q4", "2025Q1", "2025Q2", "2025Q3", "2025Q4", "2026Q1")


def walk_forward(res: dict, fam: list, scope: str) -> dict:
    """PREREG A3: for each quarter from 2025Q1, the (M, k) with the best net over the quarters before it (ties: the old
    rule, listed first), traded in that quarter."""
    fam = sorted(fam, key=lambda r: r.group != "O")
    picks, tot = {}, 0.0
    for i, q in enumerate(QUARTERS[1:], start=1):
        prior = QUARTERS[:i]
        best = max(fam, key=lambda r: (round(sum(res[r.id][scope]["quarters"].get(p, [0, 0])[0] for p in prior)), r.group == "O"))
        got = res[best.id][scope]["quarters"].get(q, [0, 0])[0]
        picks[q] = dict(pick=best.id, net=got)
        tot += got
    pick = max(fam, key=lambda r: (round(res[r.id][scope]["net"]), r.group == "O"))
    return dict(quarters=picks, net=round(tot), retuned_pick=pick.id, retuned_design_net=round(res[pick.id][scope]["net"]))


def design():
    sb = sessions("design")
    rs = R.rules()
    res, trades = evaluate(rs, sb)
    wf = {a: {sc: walk_forward(res, R.grid_family(a, rs), sc) for sc in SCOPES} for a in R.ARMS}
    out = dict(written=datetime.now(timezone.utc).isoformat(timespec="seconds"),
               sessions={u: len(sb[u]) for u in K.UNDS}, results=res, walk_forward=wf,
               rules=[dict(id=r.id, group=r.group, arm=r.arm, m=r.m, k=r.k, r23=r.r23, variant=r.v.__dict__) for r in rs])
    K.RESULTS.mkdir(parents=True, exist_ok=True)
    (K.RESULTS / "design.json").write_text(json.dumps(out, indent=1, default=str))
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "design_trades.pkl").write_bytes(pickle.dumps({k: [strip(t) for t in v] for k, v in trades.items()}))
    for a in R.ARMS:
        print(a, {sc: (wf[a][sc]["net"], wf[a][sc]["retuned_pick"]) for sc in SCOPES})


# ------------------------------------------------------------------------------------------------ the ONE final read
_G = {}


def _twin_job(i):
    r = _G["rs"][i]
    rng = np.random.default_rng(K.SEED + i)
    return [R.twin_by_und(_G["by_key"], _G["trades"][r.id], r, rng) for _ in range(K.TWINS)]


def design_gate(rid: str, scope: str, des: dict, rs_by: dict) -> tuple[bool, float, float | None]:
    r = rs_by[rid]
    own = des["results"][rid][scope]["net"]
    if r.group != "G":
        return own > 0, own, None
    wf = des["walk_forward"][r.arm][scope]["net"]
    return own > 0 and wf > 0, own, wf


def final():
    import multiprocessing as mp
    if K.FINAL_FLAG.exists():
        raise SystemExit("R49's final test has been read already (results/FINAL_READ). A new test needs a new PREREG.")
    des = json.loads((K.RESULTS / "design.json").read_text())
    K.FINAL_FLAG.write_text(datetime.now(timezone.utc).isoformat(timespec="seconds") + "\n")
    sb = sessions("final")
    rs = R.rules()
    rs_by = {r.id: r for r in rs}
    res, trades = evaluate(rs, sb)
    by_key = {(u, s.day): s for u, ss in sb.items() for s in ss}
    _G.update(rs=rs, by_key=by_key, trades=trades)
    with mp.get_context("fork").Pool(4) as pool:
        tw = pool.map(_twin_job, range(len(rs)))
    ps, idx = [], []
    for i, r in enumerate(rs):
        for sc in SCOPES:
            vals = np.array([sum(d.values()) if sc == "pooled" else d.get(sc, 0.0) for d in tw[i]])
            x = res[r.id][sc]
            x["twin_mean"] = float(vals.mean())
            x["twin_p"] = float((1 + (vals >= x["net"]).sum()) / (K.TWINS + 1))
            ps.append(x["twin_p"])
            idx.append((r.id, sc))
        print(r.id, "twin p", {sc: round(res[r.id][sc]["twin_p"], 3) for sc in SCOPES}, flush=True)
    q = R.bh(ps)
    for (rid, sc), qq in zip(idx, q):
        x = res[rid][sc]
        x["q"] = float(qq)
        dg, dnet, wf = design_gate(rid, sc, des, rs_by)
        few = x["trades"] < K.MIN_TRADES
        x["gates"] = dict(net=x["net"] > 0, p=x["twin_p"] < 0.05, q=x["q"] <= K.BH_Q, trades=not few,
                          top5=x["top5"] < 1.0, design=dg)
        x["design_net"], x["design_wf"] = dnet, wf
        x["verdict"] = "PASS" if all(x["gates"].values()) else ("too few trades to judge" if few else "FAIL")
    out = dict(read=K.FINAL_FLAG.read_text().strip(), m=len(ps), sessions={u: len(sb[u]) for u in K.UNDS}, results=res)
    (K.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    (K.OUT / "final_trades.pkl").write_bytes(pickle.dumps({k: [strip(t) for t in v] for k, v in trades.items()}))
    passed = [(rid, sc) for (rid, sc) in idx if res[rid][sc]["verdict"] == "PASS"]
    print("m", len(ps), "passes", passed)


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "report":
        import r49report
        r49report.main()
    else:
        {"design": design, "final": final}[cmd]()
