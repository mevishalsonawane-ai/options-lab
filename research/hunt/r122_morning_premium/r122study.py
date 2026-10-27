"""R122 study (PREREG.md): U1 non-expiry sessions, U2 expiry days; iron fly 09:20 -> 10:30.

    python3 research/hunt/r122_morning_premium/r122study.py run      # -> results/results.json, results/report.md
"""
from __future__ import annotations

import json
import pickle
import sys
from multiprocessing import Pool

import numpy as np

import r122cfg as K
import r122lib as L
import r119study as S19
import r98data as R
import r54lib as LB
import r117lib as L117


def _job(und):
    LB.load_late()
    rng = np.random.default_rng(K.SEED + R.UNDS.index(und))
    tr, tr2, tw = [], [], []
    seen = skipped = 0
    for d, day, exp, is_exp in L.sessions(und):
        per = K.period(d)
        if per is None:
            continue
        seen += 1
        c = L.ctx(day)
        a = L.trade(c, K.ROW_DEC, K.ROW_EXIT, exp)
        if a is None:
            skipped += 1
            continue
        tr.append(a)
        b = L.trade(c, K.ROW_DEC, K.ROW_EXIT, exp, mult=2.0)
        if b is not None:
            tr2.append(b)
        if per == "final":
            nets = [x.net for x in (L.trade(c, int(t), int(t) + K.HOLD, exp) for t in L.twin_rows(rng)) if x is not None]
            tw.append((d, is_exp, nets))
    print(und, "sessions", seen, "traded", len(tr), "skipped", skipped, flush=True)
    return und, tr, tr2, tw, seen, skipped


def run(procs: int = 3):
    with Pool(procs) as p:
        parts = p.map(_job, R.UNDS)
    tr = [t for _, a, _, _, _, _ in parts for t in a]
    tr2 = [t for _, _, b, _, _, _ in parts for t in b]
    tw = [x for _, _, _, w, _, _ in parts for x in w]
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "trades.pkl").write_bytes(pickle.dumps(dict(trades=tr, trades2x=tr2, twins=tw)))
    m_all = L117.count_prior(K.ROOT / "research" / "experiments" / "entries", K.ENTRIES_LAST_DAY) + len(K.TESTS)
    res = {"tests": {}, "m_all": m_all, "sessions": {u: dict(sessions=s, skipped=k) for u, _, _, _, s, k in parts}}
    rng = np.random.default_rng(K.SEED)
    ps = []
    for t in K.TESTS:
        want = (t == "U2")
        x = [y for y in tr if y.extra["expiry"] == want]
        x2 = [y for y in tr2 if y.extra["expiry"] == want]
        m = {per: S19.metrics([y for y in x if K.period(y.day) == per]) for per in K.PERIODS}
        m["all"] = S19.metrics(x)
        f2 = S19.metrics([y for y in x2 if K.period(y.day) == "final"])
        fin = [y for y in x if K.period(y.day) == "final"]
        opts = {(y.und, y.day) for y in fin}
        tot = np.zeros(K.TWINS)
        for (u, d, is_exp, nets) in [(u, *w) for u, _, _, ww, _, _ in parts for w in ww]:
            if is_exp == want and (u, d) in opts and nets:
                o = np.asarray(nets, float)
                tot += o[rng.integers(0, len(o), K.TWINS)]
        tp = L117.twin_p(sum(y.net for y in fin), tot) if fin else dict(p=1.0, p_mc=1.0, hits=0, twin_mean=0.0, twin_sd=0.0)
        ps.append(tp["p"])
        by = {u: {per: S19.metrics([y for y in x if y.und == u and K.period(y.day) == per]) for per in K.PERIODS}
              for u in R.UNDS}
        res["tests"][t] = dict(name=K.TESTS[t], periods=m, final_2x=f2, twin=tp, by_index=by)
    q_all, q_own = L117.bh(ps, m_all), L117.bh(ps)
    for i, t in enumerate(K.TESTS):
        r = res["tests"][t]
        r.update(q_all=float(q_all[i]), q_r122=float(q_own[i]))
        f = r["periods"]["final"]
        fails = [f"{per} mean <= 0" for per in K.PERIODS if not r["periods"][per].get("trades") or r["periods"][per]["mean"] <= 0]
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
        if f.get("trades") and f["months_at_stop"] > 0:
            fails.append(f"{f['months_at_stop']} final months <= -13k")
        if f.get("trades") and f["max_dd"] >= K.DD_STOP:
            fails.append("final drawdown >= 40k")
        r["gates"] = dict(passed=not fails, fails=fails)
    K.RESULTS.mkdir(exist_ok=True)
    (K.RESULTS / "results.json").write_text(json.dumps(res, indent=1, default=str))
    (K.RESULTS / "report.md").write_text(report(res))
    print(report(res))


def rs(x):
    return "-" if x is None else f"{x:+,.0f}".replace("+-", "-")


H = ("| | trades | WIN | mean Rs | total Rs | gross / trade | charges / trade | worst day | max DD | months <= -13k | "
     "w/o 3 best | stale legs |")


def row(lab, m):
    if not m.get("trades"):
        return f"| {lab} | 0 |  |  |  |  |  |  |  |  |  |  |"
    return (f"| {lab} | {m['trades']} | {m['win']:.1f}% | {rs(m['mean'])} | {rs(m['total'])} | {rs(m['gross'])} | "
            f"{m['charges']:,.0f} | {rs(m['worst_day'])} | {m['max_dd']:,.0f} | {m['months_at_stop']} | {rs(m['ex3'])} | "
            f"{m['stale']} |")


def report(res: dict) -> str:
    md = ["# R122 results (generated by r122study.py)", "", f"Signal source: {K.SIGNAL_SOURCE}. BH m = {res['m_all']:,}.",
          "", "Sessions / skipped (a leg missing at entry): " +
          ", ".join(f"{u} {v['sessions']} / {v['skipped']}" for u, v in res["sessions"].items()), ""]
    for t, r in res["tests"].items():
        md += [f"## {t}: {r['name']}", "", H, "|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for per in (*K.PERIODS, "all"):
            md.append(row(per, r["periods"][per]))
        f2 = r["final_2x"]
        md += ["", f"- Final at 2x slippage: {f2.get('trades', 0)} trades, mean Rs {rs(f2.get('mean'))}.",
               f"- Final twin p {r['twin']['p']:.4f} (twins' mean total Rs {rs(r['twin']['twin_mean'])}); BH q (all) "
               f"{r['q_all']:.3g}; q (R122's 2) {r['q_r122']:.3g}.",
               f"- **{'PASS' if r['gates']['passed'] else 'fail'}**" + ("" if r["gates"]["passed"] else ": "
               + "; ".join(r["gates"]["fails"])), "", f"### {t} by index", "",
               "| index | design mean (n, win) | wf mean (n, win) | final mean (n, win) |", "|---|---|---|---|"]
        for u, b in r["by_index"].items():
            md.append(f"| {u} | " + " | ".join(
                f"{rs(b[p].get('mean'))} ({b[p].get('trades', 0)}, {b[p].get('win', 0):.0f}%)" for p in K.PERIODS) + " |")
        md.append("")
    return "\n".join(md) + "\n"


if __name__ == "__main__":
    if sys.argv[1:2] == ["run"]:
        run()
    elif sys.argv[1:2] == ["report"]:
        print(report(json.loads((K.RESULTS / "results.json").read_text())))
    else:
        print(__doc__)
