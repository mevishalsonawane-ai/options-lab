"""R121 study (PREREG.md): every same-expiry night 2021-26, five indices; T1 all nights, T2 the night before expiry.

    python3 research/hunt/r121_overnight/r121study.py run      # -> results/results.json, results/report.md
"""
from __future__ import annotations

import json
import pickle
import sys
from collections import defaultdict
from multiprocessing import Pool

import numpy as np

import r121cfg as K
import r121lib as L
import r119study as S19
import r98data as R
import r54lib as LB
import r117lib as L117


def _job(und):
    LB.load_late()
    tr, tr2 = [], []
    n_nights, skipped = 0, 0
    for d, n, eve, morn, exp in L.nights(und):
        if K.period(d) is None:
            continue
        n_nights += 1
        e, m = L.ctx(eve), L.ctx(morn)
        a = L.overnight(e, m, d, n, exp)
        if a is None:
            skipped += 1
            continue
        tr.append(a)
        b = L.overnight(e, m, d, n, exp, mult=2.0)
        if b is not None:
            tr2.append(b)
    print(und, "nights", n_nights, "traded", len(tr), "skipped", skipped, flush=True)
    return und, tr, tr2, n_nights, skipped


def boot_p(trs, n=K.BOOT, seed=K.SEED) -> float:
    """One-sided: the share of night-bootstrap means <= 0 (nights = calendar dates d, all indices that night)."""
    if not trs:
        return 1.0
    by = defaultdict(lambda: [0.0, 0])
    for t in trs:
        by[t.day][0] += t.net
        by[t.day][1] += 1
    s = np.array([v[0] for v in by.values()])
    c = np.array([v[1] for v in by.values()])
    rng = np.random.default_rng(seed)
    idx = rng.integers(0, len(s), size=(n, len(s)))
    means = s[idx].sum(1) / c[idx].sum(1)
    return float((means <= 0).mean())


def subset(trs, test):
    return trs if test == "T1" else [t for t in trs if t.extra.get("eve_expiry")]


def breakdown(trs) -> dict:
    out = {}
    for lab, f in (("weekday nights", lambda t: t.extra["gap_days"] == 1),
                   ("weekend / holiday nights", lambda t: t.extra["gap_days"] > 1),
                   ("night before expiry", lambda t: t.extra["eve_expiry"]),
                   ("earlier nights", lambda t: not t.extra["eve_expiry"])):
        x = [t for t in trs if f(t)]
        out[lab] = S19.metrics(x)
    for u in R.UNDS:
        out[u] = S19.metrics([t for t in trs if t.und == u])
    return out


def run(procs: int = 3):
    with Pool(procs) as p:
        parts = p.map(_job, R.UNDS)
    tr = [t for _, a, _, _, _ in parts for t in a]
    tr2 = [t for _, _, b, _, _ in parts for t in b]
    K.OUT.mkdir(parents=True, exist_ok=True)
    (K.OUT / "trades.pkl").write_bytes(pickle.dumps(dict(trades=tr, trades2x=tr2)))
    m_all = L117.count_prior(K.ROOT / "research" / "experiments" / "entries", K.ENTRIES_LAST_DAY) + len(K.TESTS)
    res = {"tests": {}, "m_all": m_all, "nights": {u: dict(nights=n, skipped=s) for u, _, _, n, s in parts}}
    ps = []
    for t in K.TESTS:
        x, x2 = subset(tr, t), subset(tr2, t)
        m = {per: S19.metrics([y for y in x if K.period(y.day) == per]) for per in K.PERIODS}
        m["all"] = S19.metrics(x)
        f2 = S19.metrics([y for y in x2 if K.period(y.day) == "final"])
        p = boot_p([y for y in x if K.period(y.day) == "final"])
        ps.append(p)
        res["tests"][t] = dict(name=K.TESTS[t], periods=m, final_2x=f2, boot_p=p,
                               breakdown={per: breakdown([y for y in x if K.period(y.day) == per]) for per in K.PERIODS})
    q_all, q_own = L117.bh(ps, m_all), L117.bh(ps)
    for i, t in enumerate(K.TESTS):
        r = res["tests"][t]
        r.update(q_all=float(q_all[i]), q_r121=float(q_own[i]))
        f = r["periods"]["final"]
        fails = [f"{per} mean <= 0" for per in K.PERIODS if not r["periods"][per].get("trades") or r["periods"][per]["mean"] <= 0]
        if f.get("trades") and f["ex3"] <= 0:
            fails.append("final <= 0 without 3 best days")
        if not r["final_2x"].get("trades") or r["final_2x"]["mean"] <= 0:
            fails.append("final mean <= 0 at 2x slippage")
        if r["boot_p"] >= K.GATE_P:
            fails.append(f"bootstrap p {r['boot_p']:.3g}")
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


ROW_H = ("| | trades | WIN | mean Rs | total Rs | gross / trade | charges / trade | worst night | max DD | months <= -13k | "
         "w/o 3 best | stale legs | margin (est.) |")


def row(lab, m):
    if not m.get("trades"):
        return f"| {lab} | 0 |  |  |  |  |  |  |  |  |  |  |  |"
    return (f"| {lab} | {m['trades']} | {m['win']:.1f}% | {rs(m['mean'])} | {rs(m['total'])} | {rs(m['gross'])} | "
            f"{m['charges']:,.0f} | {rs(m['worst_day'])} | {m['max_dd']:,.0f} | {m['months_at_stop']} | {rs(m['ex3'])} | "
            f"{m['stale']} | {m['margin']:,.0f} |")


def report(res: dict) -> str:
    md = ["# R121 results (generated by r121study.py)", "", f"Signal source: {K.SIGNAL_SOURCE}. BH m = {res['m_all']:,}.",
          "", "Nights (same expiry) and skipped (a leg missing at entry): " +
          ", ".join(f"{u} {v['nights']} / {v['skipped']}" for u, v in res["nights"].items()), ""]
    for t, r in res["tests"].items():
        md += [f"## {t}: {r['name']}", "", ROW_H, "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
        for per in (*K.PERIODS, "all"):
            md.append(row(per, r["periods"][per]))
        f2 = r["final_2x"]
        md += ["", f"- Final at 2x slippage: {f2.get('trades', 0)} trades, mean Rs {rs(f2.get('mean'))}.",
               f"- Final bootstrap p {r['boot_p']:.4f}; BH q (all) {r['q_all']:.3g}; q (R121's 2) {r['q_r121']:.3g}.",
               f"- **{'PASS' if r['gates']['passed'] else 'fail'}**" + ("" if r["gates"]["passed"] else ": "
               + "; ".join(r["gates"]["fails"])), ""]
        for per in K.PERIODS:
            md += [f"### {t} by night type and index, {per}", "", ROW_H, "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for lab, m in r["breakdown"][per].items():
                md.append(row(lab, m))
            md.append("")
    return "\n".join(md) + "\n"


if __name__ == "__main__":
    if sys.argv[1:2] == ["run"]:
        run()
    elif sys.argv[1:2] == ["report"]:
        print(report(json.loads((K.RESULTS / "results.json").read_text())))
    else:
        print(__doc__)
