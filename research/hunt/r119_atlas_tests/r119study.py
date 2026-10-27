"""R119 study (PREREG.md): the six strategies on 2021-26 expiry days, twins, BH, gates, report.

    python3 research/hunt/r119_atlas_tests/r119study.py run      # -> results/results.json, results/report.md

Nothing is fitted, so design, walk-forward and final are read in one run (PREREG); the run is done once.
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from multiprocessing import Pool

import numpy as np
import pandas as pd

import r119cfg as K
import r119lib as L
import r98data as R
import r54lib as LB
import r117lib as L117

STRATS = list(K.STRATS)


def _job(und):
    LB.load_late()
    out = {"trades": defaultdict(list), "trades2x": defaultdict(list), "twins": defaultdict(list),
           "signals": defaultdict(int)}
    for day in R.load(und):
        per = K.period(day.day)
        if per is None:
            continue
        ctx = L.DayCtx(day)
        for s in STRATS:
            tr = L.trades_for(ctx, s)
            out["signals"][s] += 1
            out["trades"][s] += tr
            out["trades2x"][s] += L.trades_for(ctx, s, mult=2.0)
            if per == "final" and tr:
                out["twins"][s] += L.twin_options(ctx, s, tr)
    print(und, {s: len(out["trades"][s]) for s in STRATS}, flush=True)
    return {k: dict(v) for k, v in out.items()}


def metrics(trs: list) -> dict:
    trs = sorted(trs, key=lambda t: (t.day, t.und, t.sig_row))
    v = np.array([t.net for t in trs], float)
    if not len(v):
        return dict(trades=0)
    byday = defaultdict(float)
    bymonth = defaultdict(float)
    for t in trs:
        byday[t.day] += t.net
        bymonth[(t.day.year, t.day.month)] += t.net
    best = sorted(byday.values(), reverse=True)
    cum = np.concatenate([[0.0], np.cumsum(v)])
    return dict(trades=len(v), win=float((v > 0).mean() * 100), mean=float(v.mean()), total=float(v.sum()),
                worst_day=float(min(byday.values())), max_dd=float((np.maximum.accumulate(cum) - cum).max()),
                months_at_stop=int(sum(x <= K.MONTH_STOP for x in bymonth.values())),
                ex3=float(v.sum() - sum(best[:3])), stale=int(sum(t.stale for t in trs)),
                charges=float(np.mean([t.charges for t in trs])), gross=float(np.mean([t.gross for t in trs])),
                margin=float(np.mean([t.margin for t in trs])) if any(t.margin for t in trs) else 0.0,
                days=len(byday))


def run(procs: int = 4):
    with Pool(procs) as p:
        parts = p.map(_job, R.UNDS)
    tr = {s: [t for x in parts for t in x["trades"].get(s, [])] for s in STRATS}
    tr2 = {s: [t for x in parts for t in x["trades2x"].get(s, [])] for s in STRATS}
    tw = {s: [o for x in parts for o in x["twins"].get(s, [])] for s in STRATS}
    sig = {s: sum(x["signals"].get(s, 0) for x in parts) for s in STRATS}
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "trades.pkl").write_bytes(pickle.dumps(dict(trades=tr, trades2x=tr2)))
    res = {"strats": {}, "m_all": None}
    m_all = L117.count_prior(K.ROOT / "research" / "experiments" / "entries", K.ENTRIES_LAST_DAY) + len(STRATS)
    rng = np.random.default_rng(K.SEED)
    ps = []
    for s in STRATS:
        m = {per: metrics([t for t in tr[s] if K.period(t.day) == per]) for per in K.PERIODS}
        m["all"] = metrics(tr[s])
        m2 = {per: metrics([t for t in tr2[s] if K.period(t.day) == per]) for per in K.PERIODS}
        fin = [t for t in tr[s] if K.period(t.day) == "final"]
        tot = np.zeros(K.TWINS)
        for opts in tw[s]:
            o = np.asarray(opts, float)
            if len(o):
                tot += o[rng.integers(0, len(o), K.TWINS)]
        tp = L117.twin_p(sum(t.net for t in fin), tot) if fin else dict(p=1.0, p_mc=1.0, hits=0, twin_mean=0, twin_sd=0)
        ps.append(tp["p"])
        res["strats"][s] = dict(name=K.STRATS[s]["name"], source=K.STRATS[s]["source"], days=sig[s], periods=m,
                                final_2x=m2["final"], twin=tp)
    q_all, q_own = L117.bh(ps, m_all), L117.bh(ps)
    for i, s in enumerate(STRATS):
        r = res["strats"][s]
        r.update(q_all=float(q_all[i]), q_r119=float(q_own[i]))
        f = r["periods"]["final"]
        fails = []
        for per in K.PERIODS:
            if not r["periods"][per].get("trades") or r["periods"][per]["mean"] <= 0:
                fails.append(f"{per} mean <= 0")
        if f.get("trades") and f["ex3"] <= 0:
            fails.append("final <= 0 without 3 best days")
        if not r["final_2x"].get("trades") or r["final_2x"]["mean"] <= 0:
            fails.append("final mean <= 0 at 2x slippage")
        if r["twin"]["p"] >= K.GATE_P:
            fails.append(f"twin p {r['twin']['p']:.3g}")
        if r["q_all"] > K.GATE_Q:
            fails.append(f"BH q {r['q_all']:.3g}")
        if f.get("trades", 0) < K.MIN_FINAL:
            fails.append(f"< {K.MIN_FINAL} final trades")
        r["gates"] = dict(passed=not fails, fails=fails)
    res["m_all"] = m_all
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "report.md").write_text(report(res))
    print(report(res))


def rs(x):
    return "-" if x is None else f"{x:+,.0f}".replace("+-", "-")


def report(res: dict) -> str:
    md = ["# R119 results (generated by r119study.py)", "",
          f"BH m = {res['m_all']:,} (every logged family to 27 Oct + R119's 6). 1 lot per leg, dated lots and charges, "
          "R25 liq fills x R54 late multiplier, stale legs repriced (Black-76).", ""]
    for s, r in res["strats"].items():
        md += [f"## {s}: {r['name']}", "", f"Signal source: {r['source']}. Expiry days scanned: {r['days']}.", "",
               "| period | trades | win | mean Rs | total Rs | gross / trade | charges / trade | worst day | max DD | "
               "months <= -13k | w/o 3 best days | stale legs | margin (est.) |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for per in (*K.PERIODS, "all"):
            m = r["periods"][per]
            if not m.get("trades"):
                md.append(f"| {per} | 0 |  |  |  |  |  |  |  |  |  |  |  |")
                continue
            md.append(f"| {per} | {m['trades']} | {m['win']:.1f}% | {rs(m['mean'])} | {rs(m['total'])} | {rs(m['gross'])} | "
                      f"{m['charges']:,.0f} | {rs(m['worst_day'])} | {m['max_dd']:,.0f} | {m['months_at_stop']} | "
                      f"{rs(m['ex3'])} | {m['stale']} | {m['margin']:,.0f} |")
        f2 = r["final_2x"]
        md += ["", f"- Final at 2x slippage: {f2.get('trades', 0)} trades, mean Rs {rs(f2.get('mean'))}, total "
               f"Rs {rs(f2.get('total'))}.",
               f"- Final twin p {r['twin']['p']:.4f} (twins' mean total Rs {rs(r['twin']['twin_mean'])}); BH q (all) "
               f"{r['q_all']:.3g}; q (R119's 6 only) {r['q_r119']:.3g}.",
               f"- **{'PASS' if r['gates']['passed'] else 'fail'}**" + ("" if r["gates"]["passed"] else ": "
               + "; ".join(r["gates"]["fails"])), ""]
    return "\n".join(md) + "\n"


if __name__ == "__main__":
    if sys.argv[1:2] == ["run"]:
        run()
    elif sys.argv[1:2] == ["report"]:
        print(report(json.loads((K.RESULTS / "results.json").read_text())))
    else:
        print(__doc__)
