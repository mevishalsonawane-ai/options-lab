"""R53 MCX runner (PREREG sections 1-4).

    python -m research.hunt.r53.mcx.run design     -> results/mcx_design.json (every variant on DESIGN only)
    python -m research.hunt.r53.mcx.run final      -> results/mcx_final.json (the original + the design pick of each
                                                       rule, read ONCE: common.open_final("mcx", ...) refuses a second read)
    python -m research.hunt.r53.mcx.run smoke      -> design trades of every variant, no twins, nothing written to results/

Environment: IRAALGO_LAKE (the lake), IRAALGO_CACHE (Yahoo / XAUUSD inputs; work files under <cache>/r53/mcx).
BH and the gates are applied later, across all books (not here).
"""
from __future__ import annotations

import argparse
import json
import sys
import time
import zlib
from concurrent.futures import ProcessPoolExecutor
from pathlib import Path

import numpy as np
import pandas as pd

from research.hunt.r53 import common as C
from research.hunt.r53.mcx import data as D
from research.hunt.r53.mcx import rules as R
from research.hunt.r53.mcx import trend as T

TRADES_DIR = C.RESULTS / "trades"
MAX_CSV = 200_000
BOOK = "mcx"


def seed_of(*parts) -> int:
    return C.SEED + zlib.crc32(":".join(map(str, parts)).encode()) % 1_000_000


def period_days(m, part: str):
    return [d for d in m.days if C.part_of(pd.Timestamp(d), mcx=True) == part]


def save_trades(trades: list, rid: str, var: str, part: str) -> str:
    cols = ["entry_ts", "exit_ts", "sig_ts", "leg", "side", "prev_side", "strike", "fill", "exit", "why", "stale",
            "stopF", "tgtF", "gross", "cost", "net"]
    df = pd.DataFrame(trades)
    df = df[[c for c in cols if c in df.columns]]
    name = f"mcx_{rid}_{var}_{part}.csv.gz"
    TRADES_DIR.mkdir(parents=True, exist_ok=True)
    p = TRADES_DIR / name
    df.to_csv(p, index=False, compression="gzip", float_format="%.4f")
    if p.stat().st_size > MAX_CSV:
        q = D.work_dir() / "trades" / name
        q.parent.mkdir(parents=True, exist_ok=True)
        p.replace(q)
        return str(q)
    try:
        return str(p.relative_to(C.HERE))
    except ValueError:
        return str(p)


def metrics(trades, twins, n_days):
    s = C.summary(trades)
    s["net_per_trading_day"] = float(s["net"] / n_days) if n_days else None
    s["trading_days"] = int(n_days)
    s["p"] = C.twin_p(s["net"], twins) if s["trades"] else None
    s["twins"] = int(len(twins))
    s["twin_mean"] = float(np.mean(twins)) if len(twins) else None
    s["twin_p95"] = float(np.percentile(twins, 95)) if len(twins) else None
    s["stale_exits"] = int(sum(1 for t in trades if t.get("stale")))
    s["gross"] = float(sum(t["gross"] for t in trades))
    s["costs"] = float(sum(t["cost"] for t in trades))
    s["longs"] = int(sum(1 for t in trades if t.get("side", 0) > 0))
    s["shorts"] = int(sum(1 for t in trades if t.get("side", 0) < 0))
    s["net_longs"] = float(sum(t["net"] for t in trades if t.get("side", 0) > 0))
    s["net_shorts"] = float(sum(t["net"] for t in trades if t.get("side", 0) < 0))
    return s


# ------------------------------------------------------------------------------------------------ minute rules
def run_minute_rule(cls, part: str, variants=None, n_twins=C.N_TWINS, write=True):
    rule = cls()
    ctx = R.Ctx()
    m = ctx.m(rule.sym)
    days = period_days(m, part)
    out, vtr = {}, {}
    for v, P in rule.variants.items():
        if variants is not None and v not in variants:
            continue
        t0 = time.time()
        tr = rule.run(ctx, P, days)
        tr = [t for t in tr if C.part_of(pd.Timestamp(t["entry_ts"]), mcx=True) == part]
        vtr[v] = tr
        rng = np.random.default_rng(seed_of(rule.rid, v, part))
        if n_twins:
            two_way = P.get("two_way", rule.two_way)
            rule.two_way = two_way
            tw = rule.twins(ctx, P, days, len(tr), tr, n_twins, rng)
            rule.two_way = cls.two_way
        else:
            tw = np.array([])
        res = metrics(tr, tw, len(days))
        res["params"] = P
        res["seconds"] = round(time.time() - t0, 1)
        if write:
            res["trades_file"] = save_trades(tr, rule.rid, v, part) if tr else None
        out[v] = res
        print(f"{rule.rid:14s} {v:8s} {part:6s} n={res['trades']:4d} net={res['net']:10.0f} p={res['p']} "
              f"{res['seconds']}s", flush=True)
    return rule.rid, out, vtr


def _job(args):
    name, part, variants, n_twins, write = args
    cls = {c.__name__: c for c in R.MINUTE_RULES}[name]
    rid, out, vtr = run_minute_rule(cls, part, variants, n_twins, write)
    slim = {v: [dict(entry_ts=str(t["entry_ts"]), net=t["net"]) for t in tr] for v, tr in vtr.items()}
    return rid, out, slim


# ------------------------------------------------------------------------------------------------ MA10
def run_trend(part: str, variants=("orig",), n_twins=C.N_TWINS, write=True):
    dfs = T.load_legs()
    sides = T.rule_sides(dfs)
    tr = T.run(dfs, sides, part)
    rng = np.random.default_rng(seed_of("MA10", "orig", part))
    tw = T.twin_nets(dfs, sides, part, n_twins, rng) if n_twins else np.array([])
    lo, hi = (C.DESIGN if part == "design" else C.FINAL)
    days = sorted({d for df in dfs.values() for d in df.index if lo <= d.date() <= hi})
    res = metrics(tr, tw, len(days))
    res["params"] = {"lookback": T.LOOKBACK, "legs": {k: v[0] for k, v in T.LEGS.items()}}
    res["by_leg"] = {lg: float(sum(t["net"] for t in tr if t["leg"] == lg)) for lg in sorted({t["leg"] for t in tr})}
    if write:
        res["trades_file"] = save_trades(tr, "MA10", "orig", part) if tr else None
    print(f"MA10 orig {part} n={res['trades']} net={res['net']:.0f} p={res['p']}", flush=True)
    return "MA10", {"orig": res}, {"orig": [dict(entry_ts=str(t["entry_ts"]), net=t["net"]) for t in tr]}


def _map(jobs, workers):
    if workers <= 1:
        return map(_job, jobs)
    ex = ProcessPoolExecutor(max_workers=workers)
    try:
        return list(ex.map(_job, jobs))
    finally:
        ex.shutdown()


# ------------------------------------------------------------------------------------------------ modes
def design(n_twins=C.N_TWINS, workers=4, only=None):
    jobs = [(c.__name__, "design", None, n_twins, True) for c in R.MINUTE_RULES if only is None or c.rid in only]
    res = {}
    for rid, out, slim in _map(jobs, workers):
        res[rid] = (out, slim)
    if only is None or "MA10" in only:
        rid, out, slim = run_trend("design", n_twins=n_twins)
        res[rid] = (out, slim)
    doc = {"book": BOOK, "part": "design", "period": {"mcx_minutes": [str(x) for x in C.MCX_DESIGN],
                                                       "ma10_daily": [str(x) for x in C.DESIGN]},
           "n_twins": n_twins, "rules": {}}
    for rid, (out, slim) in sorted(res.items()):
        vt = {v: [dict(entry_ts=pd.Timestamp(t["entry_ts"]), net=t["net"]) for t in tr] for v, tr in slim.items()}
        wf = C.walk_forward(vt, "orig", mcx=(rid != "MA10"))
        doc["rules"][rid] = {"variants": out, "walk_forward": wf, "design_pick": wf["design_pick"]}
    if only is None:
        C.save("mcx_design.json", doc)
    else:
        (D.work_dir() / "mcx_design_partial.json").write_text(json.dumps(doc, indent=1, default=str))
    return doc


def final(n_twins=C.N_TWINS, workers=4):
    dpath = C.RESULTS / "mcx_design.json"
    if not dpath.exists():
        raise SystemExit("run design first: results/mcx_design.json is missing")
    des = json.loads(dpath.read_text())
    plan = {rid: ["orig"] + ([r["design_pick"]] if r["design_pick"] else []) for rid, r in des["rules"].items()}
    ideas = [f"{rid}:{v}" for rid, vs in sorted(plan.items()) for v in vs]
    C.open_final(BOOK, ideas)                     # the one read; a second call raises FinalReadError
    by_name = {c.rid: c.__name__ for c in R.MINUTE_RULES}
    jobs = [(by_name[rid], "final", vs, n_twins, True) for rid, vs in plan.items() if rid in by_name]
    res = {}
    for rid, out, _ in _map(jobs, workers):
        res[rid] = out
    if "MA10" in plan:
        res["MA10"] = run_trend("final", n_twins=n_twins)[1]
    doc = {"book": BOOK, "part": "final", "period": [str(x) for x in C.FINAL], "ideas": ideas, "n_twins": n_twins,
           "rules": {rid: {"read": plan[rid], "design_pick": des["rules"][rid]["design_pick"],
                           "design_wf": {"orig_wf_net": des["rules"][rid]["walk_forward"]["orig_wf_net"],
                                         "retune_wf_net": des["rules"][rid]["walk_forward"]["retune_wf_net"]},
                           "variants": res[rid]} for rid in sorted(res)}}
    C.save("mcx_final.json", doc)
    return doc


def ma5u_design(n_twins=C.N_TWINS):
    """MA5u (post-lock correction of MA5's daily cap): design of orig + hold30 -> results/mcx_design_ma5u.json."""
    rid, out, vtr = run_minute_rule(R.MA5u, "design", n_twins=n_twins)
    vt = {v: [dict(entry_ts=pd.Timestamp(t["entry_ts"]), net=t["net"]) for t in tr] for v, tr in vtr.items()}
    wf = C.walk_forward(vt, "orig", mcx=True)
    doc = {"book": BOOK, "part": "design", "note": "MA5u = MA5 without the one-a-day cap (post-lock correction)",
           "n_twins": n_twins, "rules": {rid: {"variants": out, "walk_forward": wf, "design_pick": wf["design_pick"]}}}
    C.save("mcx_design_ma5u.json", doc)
    return doc


def ma5u_final(n_twins=C.N_TWINS):
    dpath = C.RESULTS / "mcx_design_ma5u.json"
    if not dpath.exists():
        raise SystemExit("run ma5u-design first")
    r = json.loads(dpath.read_text())["rules"]["MA5u"]
    vs = ["orig"] + ([r["design_pick"]] if r["design_pick"] else [])
    C.open_final(BOOK, [f"MA5u:{v}" for v in vs])         # the one read of MA5u
    rid, out, _ = run_minute_rule(R.MA5u, "final", variants=vs, n_twins=n_twins)
    doc = {"book": BOOK, "part": "final", "period": [str(x) for x in C.FINAL], "n_twins": n_twins,
           "note": "post-lock correction: MA5's capped final had been seen first, so this cannot count as a clean pass",
           "rules": {rid: {"read": vs, "design_pick": r["design_pick"],
                           "design_wf": {k: r["walk_forward"][k] for k in ("orig_wf_net", "retune_wf_net")},
                           "variants": out}}}
    C.save("mcx_final_ma5u.json", doc)
    return doc


def smoke(only=None):
    for c in R.MINUTE_RULES:
        if only and c.rid not in only:
            continue
        run_minute_rule(c, "design", n_twins=0, write=False)
    if not only or "MA10" in only:
        run_trend("design", n_twins=0, write=False)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("mode", choices=["design", "final", "smoke", "ma5u-design", "ma5u-final"])
    ap.add_argument("--only", nargs="*")
    ap.add_argument("--twins", type=int, default=C.N_TWINS)
    ap.add_argument("--workers", type=int, default=4)
    a = ap.parse_args(argv)
    if a.mode == "smoke":
        smoke(a.only)
    elif a.mode == "ma5u-design":
        ma5u_design()
    elif a.mode == "ma5u-final":
        ma5u_final()
    elif a.mode == "design":
        if a.twins != C.N_TWINS and not a.only:
            raise SystemExit("the full design run uses the pre-registered 500 twins")
        design(a.twins, a.workers, a.only)
    else:
        if a.twins != C.N_TWINS or a.only:
            raise SystemExit("the final read uses every rule and the pre-registered 500 twins")
        final(a.twins, a.workers)


if __name__ == "__main__":
    sys.exit(main())
