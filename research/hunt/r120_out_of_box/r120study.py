"""R120 study (PREREG.md): the five tests on 2021-26 expiry days, twins, BH, gates, report.

    python3 research/hunt/r120_out_of_box/r120study.py run      # -> results/results.json, results/report.md
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict, deque
from multiprocessing import Pool

import numpy as np

import r120cfg as K
import r120lib as L
import r119cfg as K119
import r119lib as L19
import r119study as S19
import r98data as R
import r54lib as LB
import r117lib as L117

TESTS = list(K.TESTS)


def grids(und: str) -> dict:
    ix = R._index_frames(und)
    return {d: R._grid(g, ["open", "high", "low", "close"]) for d, g in ix.groupby("d")} if len(ix) else {}


def mk(strat, und, d, sig, ex, net):
    t = L19.Trade(strat, und, d, sig, ex, [])
    t.net = float(net)
    return t


def _job(und):
    LB.load_late()
    own = grids(und)
    sess = sorted(own)
    twin = grids(K.TWIN[und])
    rec = defaultdict(list)
    q4h, q5h = {}, deque(maxlen=K.Q5_N)
    for day in R.load(und):
        per = K.period(day.day)
        ctx = L19.DayCtx(day)
        if per is not None:
            # Q1: the lot frontier, 1x and 2x costs; twins (final) at every decision row
            f1, f2 = L.q1_frontier(ctx), L.q1_frontier(ctx, mult=2.0)
            if f1 is not None:
                rec["Q1"].append((day.day, f1, f2))
                if per == "final":
                    rows = [L.q1_frontier(ctx, t) for t in range(K119.TWIN_A, K119.TWIN_B + 1)]
                    rec["Q1_twin"].append([r for r in rows if r is not None])
            # Q2
            for (t, x, mv, a, w), two in zip(L.q2_trades(ctx), L.q2_trades(ctx, 2.0)):
                rec["Q2"].append((day.day, t, x, a, w, two[3]))
            # Q3
            tr = L.q3_trade(ctx)
            if tr is not None:
                tr2 = L.q3_trade(ctx, mult=2.0)
                tw = None
                if per == "final":
                    tw = [x.net for x in (L.q3_trade(ctx, t) for t in range(K119.TWIN_A, K119.TWIN_B + 1)) if x is not None]
                rec["Q3"].append((day.day, tr.net, tr2.net if tr2 else np.nan, tw, tr.stale))
        # Q4 (history is kept for every expiry day, inside the periods or not)
        tg = twin.get(day.day)
        if tg is not None:
            rel = L.rel_moves(ctx.sp, L.ffill5(tg[:, 3]))
            if per is not None:
                for t, sgn in L.q4_signals(rel, q4h):
                    x = min(t + 1 + K119.FADE_HOLD, K119.ROW_1505)
                    pin, pout = L.open_at(day.ix, t + 1), L.open_at(day.ix, x)
                    a = L.fut_trade(und, day.day, ctx.lot, pin, pout, -sgn)
                    w = L.fut_trade(und, day.day, ctx.lot, pin, pout, sgn)
                    a2 = L.fut_trade(und, day.day, ctx.lot, pin, pout, -sgn, 2.0)
                    if a == a:
                        rec["Q4"].append((day.day, t, x, a, w, a2))
            L.push_hist(q4h, rel)
        else:
            rec["Q4_noday"].append(day.day)
        # Q5
        m = L.m30(day.ix)
        thr = L.q5_threshold(q5h)
        if per is not None and thr is not None and m == m and abs(m) >= thr:
            i = sess.index(day.day) if day.day in sess else -1
            if 0 <= i < len(sess) - 1:
                nd = sess[i + 1]
                g = own[nd]
                pin, pout = L.open_at(g, 0), L.open_at(g, K.Q5_EXIT)
                sgn = 1 if m > 0 else -1
                a = L.fut_trade(und, day.day, ctx.lot, pin, pout, -sgn, charge_day=nd)
                w = L.fut_trade(und, day.day, ctx.lot, pin, pout, sgn, charge_day=nd)
                a2 = L.fut_trade(und, day.day, ctx.lot, pin, pout, -sgn, 2.0, charge_day=nd)
                if a == a:
                    rec["Q5"].append((day.day, 0, K.Q5_EXIT, a, w, a2))
        if m == m:
            q5h.append(m)
    print(und, {k: len(v) for k, v in rec.items()}, flush=True)
    return und, dict(rec)


def twin_p(actual, opts, rng):
    tot = np.zeros(K.TWINS)
    for o in opts:
        o = np.asarray(o, float)
        o = o[~np.isnan(o)]
        if len(o):
            tot += o[rng.integers(0, len(o), K.TWINS)]
    return L117.twin_p(actual, tot)


def run(procs: int = 4):
    with Pool(procs) as p:
        parts = p.map(_job, R.UNDS)
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "records.pkl").write_bytes(pickle.dumps(parts))
    rng = np.random.default_rng(K.SEED)
    res = {"tests": {}, "q1_frontier": {}}
    # Q1: the frontier and N chosen on design
    q1 = [(u, d, f1, f2) for u, r in parts for d, f1, f2 in r.get("Q1", [])]
    front = {}
    for n in K.Q1_LOTS:
        front[n] = {per: S19.metrics([mk("Q1", u, d, 15, 350, f1[n]) for u, d, f1, _ in q1 if K.period(d) == per])
                    for per in K.PERIODS}
    res["q1_frontier"] = front
    n_star = L.choose_n({n: (front[n]["design"]["mean"], front[n]["design"]["months_at_stop"]) for n in K.Q1_LOTS})
    books = {
        "Q1": ([mk("Q1", u, d, 15, 350, f1[n_star]) for u, d, f1, _ in q1],
               [mk("Q1", u, d, 15, 350, f2[n_star]) for u, d, _, f2 in q1],
               [[x[n_star] for x in rows] for u, r in parts for rows in r.get("Q1_twin", [])]),
        "Q3": ([mk("Q3", u, d, 165, 350, a) for u, r in parts for d, a, _, _, _ in r.get("Q3", [])],
               [mk("Q3", u, d, 165, 350, b) for u, r in parts for d, _, b, _, _ in r.get("Q3", []) if b == b],
               [tw for u, r in parts for d, _, _, tw, _ in r.get("Q3", []) if tw is not None]),
    }
    for q in ("Q2", "Q4", "Q5"):
        rows = [(u, *x) for u, r in parts for x in r.get(q, [])]
        books[q] = ([mk(q, u, d, t, x, a) for u, d, t, x, a, w, a2 in rows],
                    [mk(q, u, d, t, x, a2) for u, d, t, x, a, w, a2 in rows],
                    [[a, w] for u, d, t, x, a, w, a2 in rows if K.period(d) == "final"])
    m_all = L117.count_prior(K.ROOT / "research" / "experiments" / "entries", K.ENTRIES_LAST_DAY) + len(TESTS)
    ps = []
    for q in TESTS:
        tr, tr2, tw = books[q]
        m = {per: S19.metrics([t for t in tr if K.period(t.day) == per]) for per in K.PERIODS}
        m["all"] = S19.metrics(tr)
        f2 = S19.metrics([t for t in tr2 if K.period(t.day) == "final"])
        fin = sum(t.net for t in tr if K.period(t.day) == "final")
        tp = twin_p(fin, tw, rng) if tw else dict(p=1.0, p_mc=1.0, hits=0, twin_mean=0.0, twin_sd=0.0)
        ps.append(tp["p"])
        res["tests"][q] = dict(name=K.TESTS[q]["name"], source=K.TESTS[q]["source"], periods=m, final_2x=f2, twin=tp)
    res["tests"]["Q1"]["n_lots"] = n_star
    q_all, q_own = L117.bh(ps, m_all), L117.bh(ps)
    for i, q in enumerate(TESTS):
        r = res["tests"][q]
        r.update(q_all=float(q_all[i]), q_r120=float(q_own[i]))
        f = r["periods"]["final"]
        fails = [f"{per} mean <= 0" for per in K.PERIODS if not r["periods"][per].get("trades") or r["periods"][per]["mean"] <= 0]
        if f.get("trades") and f["ex3"] <= 0:
            fails.append("final <= 0 without 3 best days")
        if not r["final_2x"].get("trades") or r["final_2x"]["mean"] <= 0:
            fails.append("final mean <= 0 at 2x costs")
        if r["twin"]["p"] >= K.GATE_P:
            fails.append(f"twin p {r['twin']['p']:.3g}")
        if r["q_all"] > K.GATE_Q:
            fails.append(f"BH q {r['q_all']:.3g}")
        if f.get("trades", 0) < K.MIN_FINAL:
            fails.append(f"< {K.MIN_FINAL} final trades")
        if f.get("trades") and f["months_at_stop"] > 0:
            fails.append(f"{f['months_at_stop']} final months <= -13k")
        if f.get("trades") and f["max_dd"] >= K.DD_STOP:
            fails.append("final drawdown >= 40k")
        r["gates"] = dict(passed=not fails, fails=fails)
    res["m_all"] = m_all
    res["q4_days_without_twin"] = sum(len(r.get("Q4_noday", [])) for _, r in parts)
    res["q3_stale_legs"] = sum(x[4] for _, r in parts for x in r.get("Q3", []))
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "report.md").write_text(report(res))
    print(report(res))


def rs(x):
    return "-" if x is None else f"{x:+,.0f}".replace("+-", "-")


def report(res: dict) -> str:
    md = ["# R120 results (generated by r120study.py)", "",
          f"BH m = {res['m_all']:,}. Q4 expiry days without twin-index minutes: {res['q4_days_without_twin']}; Q3 stale "
          f"legs repriced: {res['q3_stale_legs']}.", ""]
    for q, r in res["tests"].items():
        extra = f" N = {r['n_lots']} lots per leg (chosen on design)." if q == "Q1" else ""
        md += [f"## {q}: {r['name']}", "", f"Signal source: {r['source']}.{extra}", "",
               "| period | trades | win | mean Rs | total Rs | worst day | max DD | months <= -13k | w/o 3 best days |",
               "|---|---|---|---|---|---|---|---|---|"]
        for per in (*K.PERIODS, "all"):
            m = r["periods"][per]
            if not m.get("trades"):
                md.append(f"| {per} | 0 |  |  |  |  |  |  |  |")
                continue
            md.append(f"| {per} | {m['trades']} | {m['win']:.1f}% | {rs(m['mean'])} | {rs(m['total'])} | "
                      f"{rs(m['worst_day'])} | {m['max_dd']:,.0f} | {m['months_at_stop']} | {rs(m['ex3'])} |")
        f2 = r["final_2x"]
        md += ["", f"- Final at 2x costs: {f2.get('trades', 0)} trades, mean Rs {rs(f2.get('mean'))}.",
               f"- Final twin p {r['twin']['p']:.4f}; BH q (all) {r['q_all']:.3g}; q (R120's 5) {r['q_r120']:.3g}.",
               f"- **{'PASS' if r['gates']['passed'] else 'fail'}**" + ("" if r["gates"]["passed"] else ": "
               + "; ".join(r["gates"]["fails"])), ""]
    md += ["## Q1 frontier: R119's P1 iron fly at N lots per leg (descriptive)", "",
           "| lots | design mean | wf mean | final mean | design months <= -13k | wf months | final months | final worst day | final max DD |",
           "|---|---|---|---|---|---|---|---|---|"]
    for n, f in res["q1_frontier"].items():
        md.append(f"| {n} | {rs(f['design']['mean'])} | {rs(f['wf']['mean'])} | {rs(f['final']['mean'])} | "
                  f"{f['design']['months_at_stop']} | {f['wf']['months_at_stop']} | {f['final']['months_at_stop']} | "
                  f"{rs(f['final']['worst_day'])} | {f['final']['max_dd']:,.0f} |")
    return "\n".join(md) + "\n"


if __name__ == "__main__":
    if sys.argv[1:2] == ["run"]:
        run()
    elif sys.argv[1:2] == ["report"]:
        print(report(json.loads((K.RESULTS / "results.json").read_text())))
    else:
        print(__doc__)
