"""R18 holdout (PREREG.md): read ONCE. Every arm x variant is computed in this one read; only the design candidates
(results/design_<source>.json) are tests, BH over the candidates read. A flag file refuses a second run.

    R18_SCRATCH=<scratch> python3 research/hunt/r18/holdout.py     # -> results/hold_<source>.json
"""
from __future__ import annotations

import json
import sys
from datetime import datetime, timezone

import arms as A
import config as K
import lib
from lib import VARIANTS, Grid
from run import evaluate, split_sessions


def main():
    if K.HOLD_FLAG.exists():
        print(f"holdout already read ({K.HOLD_FLAG.read_text().strip()}); a second read is refused")
        return 1
    design = json.load(open(K.RESULTS / f"design_{K.SOURCE}.json"))["arms"]
    cands = [(a, v) for a in design for v in VARIANTS[1:] if design[a][v]["candidate"]]
    grids = {u: Grid(u) for u in K.UNDS}
    for g in grids.values():
        g.allowed = set(split_sessions(g, hold=True))
        if len(g.allowed) < K.MIN_HOLD_SESSIONS[K.SOURCE]:
            print(f"{g.und}: {len(g.allowed)} holdout sessions, fewer than {K.MIN_HOLD_SESSIONS[K.SOURCE]}; the read waits")
            return 1
    out = {}
    for arm in A.ARMS:
        g = grids[A.UND[arm]]
        trades = A.build(arm, grids)
        out[arm] = evaluate(g, trades, twins=200)
    # everything is computed silently first, so a crash shows nothing; the flag goes down before anything is shown
    K.HOLD_FLAG.parent.mkdir(parents=True, exist_ok=True)
    K.HOLD_FLAG.write_text(datetime.now(timezone.utc).isoformat())
    for arm in A.ARMS:
        v0 = out[arm]["V0"]
        print(f"{A.NAMES[arm]:42} trades {v0['trades']:5} net {v0['net']:>10,} | " +
              " ".join(f"{v}:{out[arm][v].get('delta', 0):+,}" for v in VARIANTS[1:]), flush=True)
    q = lib.bh([out[a][v]["p"] for a, v in cands]) if cands else []
    passed = {}
    for (a, v), qq in zip(cands, q):
        r = out[a][v]
        r["q_candidates"] = round(float(qq), 4)
        r["pass"] = bool(r["delta"] > 0 and qq < K.HOLD_P)
        if r["pass"] and (a not in passed or design[a][v]["delta"] > design[a][passed[a]]["delta"]):
            passed[a] = v
    meta = dict(source=K.SOURCE, candidates=cands, passed=passed,
                holdout={u: [str(min(g.allowed)), str(max(g.allowed)), len(g.allowed)] for u, g in grids.items()})
    with open(K.RESULTS / f"hold_{K.SOURCE}.json", "w") as f:
        json.dump(dict(meta=meta, arms=out), f, indent=1, default=str)
    print("candidates:", cands, "passed:", passed)
    return 0


if __name__ == "__main__":
    sys.exit(main())
