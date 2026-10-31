"""R139 Amendment B (PREREG_B.md): reversal logic. F1-F11 take every R139 rule the OTHER way (the opposite option at
the same minute, the same stop distance, 2R); SR3 is S3 with stop-and-reverse (when S3's stop is hit, buy the opposite
option at the next minute, stop back at S3's entry level, 2R, once per trade).

    python research/hunt/r139_market_structure/r139b.py design    # -> results/design_b.json
    python research/hunt/r139_market_structure/r139b.py holdout   # the ONE Amendment B read (holdout ALREADY SEEN)

Signal source: the index spot (R139's detectors, unchanged). Only the side bought and the exits change.
"""
from __future__ import annotations

import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r139 as B  # noqa: E402

R, K, D3 = B.R, B.K, B.D3
N = R.N
RESULTS, OUT = B.RESULTS, B.OUT
SEED = 1392
TWINS = B.TWINS
GATES = B.GATES
BASE = [v for v, x in B.VARIANTS.items() if not x.get("nn")]          # S1 .. S11
VARIANTS = {**{f"F{v[1:]}": dict(kind="flip", base=v, label=f"{v} taken the other way") for v in BASE},
            "SR3": dict(kind="sar", base="S3", label="S3 with stop-and-reverse (once per trade)")}


def flip(sides: dict, cands: list) -> list:
    """Each (side, C) becomes (-side, C'): the same minute, the stop the same index distance on the other side of the
    entry open, 2R. A candidate whose entry open is missing or already beyond its stop is dropped (as in B.sequence)."""
    out = []
    for sd, c in cands:
        ref = sides[sd].mo[c.d][c.r0] if c.r0 < N else np.nan
        if not np.isfinite(ref) or ref <= c.stop:
            continue
        sp = ref - c.stop
        out.append((-sd, B.C(c.d, c.r0, float(-ref - sp))))
    out.sort(key=lambda x: (x[1].d, x[1].r0, x[0]))
    return out


def sequence_sar(sides: dict, cands: list, blocked: set, days, keep) -> list:
    """B.sequence, and when a trade exits at its stop, a reverse trade at the next minute (if <= 15:00): the opposite
    side, stop 0.02% beyond the first trade's entry open, 2R; it occupies the index like any trade. Reverse trades are
    marked rev=True."""
    trades, busy, i = [], -1, 0
    while i < len(cands):
        sd, c = cands[i]
        j = i
        while j < len(cands) and cands[j][1].d == c.d and cands[j][1].r0 == c.r0:
            j += 1
        same, i = cands[i:j], j
        if len({x[0] for x in same}) == 2 or c.d * N + c.r0 <= busy or c.r0 > R.LAST_ENTRY or c.d in blocked:
            continue
        T = B.sequence(sides, [(sd, c)], blocked, days, lambda d: True)
        if not T:
            continue
        t = T[0]
        busy = c.d * N + t["exit"]
        if keep(days[c.d]):
            trades.append(dict(t, rev=False))
        if t["why"] == "stop" and t["exit"] + 1 <= R.LAST_ENTRY:
            ref = sides[sd].mo[c.d][c.r0]
            rc = B.C(c.d, t["exit"] + 1, R.stop_below(float(-ref)))
            T2 = B.sequence(sides, [(-sd, rc)], blocked, days, lambda d: True)
            if T2:
                busy = c.d * N + T2[0]["exit"]
                if keep(days[c.d]):
                    trades.append(dict(T2[0], rev=True))
    return trades


def run_index(args) -> dict:
    und, part = args
    lo, hi = (K.DESIGN if part == "design" else K.HOLDOUT)
    g = D3.load_grid(und, hi)
    W, Mo = R.Series(und, hi, "WEEK"), R.Series(und, hi, "MONTH")
    wexp, mexp = W.expiry_days(), Mo.expiry_days()
    first_opt = min(int(p.stem[-4:]) for p in W.files) if W.files else 9999
    blocked = {i for i, d in enumerate(g.days) if (d in wexp and d in mexp) or d.year < first_opt}
    keep = lambda d: lo <= d <= hi  # noqa: E731
    lot, step = K.LOT[und], K.STEP[und]
    rng = np.random.default_rng(SEED + K.UNDS.index(und) + (100 if part != "design" else 0))
    sides = R.sides_of(g)
    cache = {}

    def src_for(day, closes):
        if day not in cache:
            cache.clear()
            cache[day] = (Mo if day in wexp else W).day(day, closes)
        return cache[day]

    sig = {}
    for v, var in VARIANTS.items():
        cands = B.candidates(sides, B.VARIANTS[var["base"]])
        if var["kind"] == "flip":
            sig[v] = [dict(t, rev=False) for t in B.sequence(sides, flip(sides, cands), blocked, g.days, keep)]
        else:
            sig[v] = sequence_sar(sides, cands, blocked, g.days, keep)
    by_day = {}
    for v, T in sig.items():
        for t in T:
            by_day.setdefault(t["d"], []).append((v, t))
    rows = {v: [] for v in sig}
    for d in sorted(by_day):
        opts = src_for(g.days[d], g.c[d])
        if opts is None:
            continue
        lastc = R.last_before(g.c[d])
        tcache = {}
        for v, t in by_day[d]:
            if not np.isfinite(lastc[t["entry"]]):
                continue
            cp = "C" if t["side"] > 0 else "P"
            tr = R.option_trade(opts.contracts.get((cp, D3.atm(lastc[t["entry"]], step))), t["entry"], t["exit"],
                                t["why"], lot, g.days[d], und)
            if tr is None:
                continue
            S = sides[t["side"]]
            r1s = rng.integers(t["entry"], R.LAST_ENTRY + 1, size=TWINS)
            tw = R.twin_nets(r1s, lastc, S.mh[d], S.ml[d], S.mo[d], opts.contracts, cp, step, t["stop_pts"],
                             t["tgt_pts"], lot, g.days[d], und, tcache)
            rows[v].append(dict(t, und=und, net=tr[0], netx2=tr[1], twins=tw))
    print(f"R139B {part} {und}: " + ", ".join(f"{k} {len(x)}" for k, x in rows.items()), flush=True)
    return rows


def lock(status: str):
    p = OUT / "HOLDOUT_READ_B.json"
    now = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    if status == "start":
        if (RESULTS / "holdout_b.json").exists() or p.exists():
            raise R.HoldoutError("refused: the R139 Amendment B holdout was already read")
        if not (RESULTS / "design_b.json").exists():
            raise R.HoldoutError("refused: commit the Amendment B design results first")
        rec = dict(status="started", started_at=now, note="holdout months already seen by R139")
    else:
        rec = dict(json.loads(p.read_text()), status="done", done_at=now)
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(json.dumps(rec))


def run(part: str):
    from multiprocessing import Pool
    if part == "holdout":
        lock("start")
    with Pool(int(os.environ.get("R139_PROCS", "4"))) as pool:
        res = pool.map(run_index, [(u, part) for u in K.UNDS])
    out = dict(part=part, period=[str(x) for x in (K.DESIGN if part == "design" else K.HOLDOUT)], variants={},
               note=("design" if part == "design" else "holdout 1 Apr - 19 Oct 2026, ALREADY SEEN by R139"))
    frames = {}
    for v in VARIANTS:
        T = pd.DataFrame([r for x in res for r in x[v]])
        frames[v] = T
        s = B.stats(T, part == "design") if len(T) else dict(n=0)
        s["p"] = R.twin_p(T, SEED)[0] if len(T) else 1.0
        s["label"] = VARIANTS[v]["label"]
        if v == "SR3" and len(T):
            s["reverse_legs"] = R.money(T[T.rev]) if T.rev.any() else dict(n=0)
            s["reverse_legs"].pop("exits", None)
        out["variants"][v] = s
    if part == "design":
        qs = R.R3.bh([out["variants"][v].get("p", 1.0) for v in VARIANTS])
        for v, q in zip(VARIANTS, qs):
            s = out["variants"][v]
            s["q"] = float(q)
            s["gates"] = B.gates(s, q)
            s["passes_design"] = all(s["gates"].values())
    else:
        for s in out["variants"].values():
            s["passes_holdout"] = (s.get("n", 0) >= GATES["holdout_n"] and s.get("avg_net", -1) > 0
                                   and s.get("avg_net_x2", -1) > 0)
    RESULTS.mkdir(parents=True, exist_ok=True)
    (RESULTS / f"{part}_b.json").write_text(json.dumps(out, indent=1, default=str))
    OUT.mkdir(parents=True, exist_ok=True)
    for v, f in frames.items():
        if len(f):
            f.drop(columns=["twins"]).to_parquet(OUT / f"b_trades_{v}_{part}.parquet")
    if part == "holdout":
        lock("done")
    print({v: (s.get("n"), round(s.get("avg_net", 0) or 0), round(s.get("win", 0) or 0, 2), round(s.get("p", 1), 3))
           for v, s in out["variants"].items()})


if __name__ == "__main__":
    run(sys.argv[1] if len(sys.argv) > 1 else "design")
