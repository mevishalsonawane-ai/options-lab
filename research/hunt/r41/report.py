"""R41 summary (PREREG sections 5-6): BH families over the model tests and the trading rules, the paper gates, and the
markdown tables used in research/HUNT_R41_NEURAL_NETS.md.

    python research/hunt/r41/report.py          # -> results/summary.json + tables on stdout
"""
from __future__ import annotations

import json
import sys

import numpy as np

import r41cfg as K
import evalx as E

ARM_RULE = {"orb": "R7", "fade": "R8", "liq": "R9", "h0": "R10", "night": "R11", "l3": "R17", "solo": "R18"}
Q_NAME = {"q1": "Q1 index direction / size", "q2": "Q2 arm filter", "q3": "Q3 expiry blast", "q4": "Q4 morning gap",
          "q5": "Q5 crypto"}
RULE_TEXT = {"R1": "Q1 h15 top-10% confidence, ATM", "R2": "Q1 h30 top-10%", "R3": "Q1 h60 top-10%",
             "R4": "Q1 h15 + big-move filter", "R5": "Q1 h30 + big-move filter", "R6": "Q1 h60 + big-move filter",
             "R7": "Q2 skip ORB trades (bottom 30%)", "R8": "Q2 skip Range Fade trades", "R9": "Q2 skip Liquidity trades",
             "R10": "Q2 skip Hero H0 trades", "R11": "Q2 skip Night trades", "R17": "Q2 skip lottery L3 trades",
             "R18": "Q2 skip Solo trades", "R12": "Q3 buy the blast option (top 20%)",
             "R13": "Q4 overnight 1-ITM option (top 20%)", "R14": "Q5 crypto h15 (top 10%, stop + lock)",
             "R15": "Q5 crypto h60", "R16": "Q5 crypto h240"}


def _load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def cells(wf: dict, hold: dict) -> list[dict]:
    """Primary model-test cells: (question, target) with the WF and holdout NN-vs-best-baseline tests."""
    out = []
    for q in ("q1", "q3", "q4", "q5"):
        r = (wf or {}).get(q)
        if not r or "metrics" not in r:
            continue
        for t, m in r["metrics"].items():
            h = ((hold or {}).get(q) or {}).get("metrics", {}).get(t)
            out.append(dict(q=q, target=t, wf=m, hold=h))
    r2 = (wf or {}).get("q2") or {}
    for a, r in r2.get("arms", {}).items():
        if "metrics" not in r:
            continue
        h = (((hold or {}).get("q2") or {}).get(a) or {}).get("metrics", {}).get("win")
        out.append(dict(q="q2", target=f"win_{a}", wf=r["metrics"]["win"], hold=h))
    return out


def rules(wf: dict, hold: dict) -> list[dict]:
    out = []
    for q in ("q1", "q3", "q4", "q5"):
        r = (wf or {}).get(q)
        if not r or "rules" not in r:
            continue
        for rid, v in r["rules"].items():
            h = ((hold or {}).get(q) or {}).get("rules", {}).get(rid)
            out.append(dict(rid=rid, q=q, wf=v, hold=h))
    r2 = (wf or {}).get("q2") or {}
    for a, r in r2.get("arms", {}).items():
        if "rules" not in r:
            continue
        h = (((hold or {}).get("q2") or {}).get(a) or {}).get("rules", {}).get("Q2")
        out.append(dict(rid=ARM_RULE[a], q="q2", arm=a, wf=r["rules"]["Q2"], hold=h))
    return sorted(out, key=lambda x: int(x["rid"][1:]))


def gates(r: dict) -> dict:
    """PREREG section 6 gates on the NN rule (needs the holdout)."""
    h = (r.get("hold") or {}).get("nn")
    if not h:
        return dict(status="holdout not read")
    n_need = h.get("skipped", h["trades"]) if r["q"] == "q2" else h["trades"]
    fl = r["wf"]["nn"]["folds"]
    if r["q"] == "q2":                                  # a filter's fold is positive when it beats the unfiltered arm
        good = sum(1 for v in fl.values() if v["pnl_per_day"] > v["unfiltered"]["pnl_per_day"])
    else:
        good = sum(1 for v in fl.values() if v["pnl_per_day"] > 0)
    need = min(2 if r["q"] == "q5" else 3, len(fl))      # 3 of 4 India folds (2 of 3 crypto); all if fewer ran
    folds_ok = good >= need and len(fl) > 0
    stress = h.get("pnl_per_day_stress")
    g = dict(pnl=h["pnl_per_day"] > 0 and (stress is None or stress > 0),
             q=bool(r.get("q_hold") is not None and r["q_hold"] <= 0.05),
             folds=folds_ok, n=n_need >= 100,
             beats_trees=h["pnl_per_day"] >= ((r.get("hold") or {}).get("trees") or {}).get("pnl_per_day", -np.inf))
    if r["q"] == "q2":                                  # a filter must beat the unfiltered arm
        g["pnl"] = h["pnl_per_day"] > h["unfiltered"]["pnl_per_day"]
    g["pass"] = all(g.values())
    return g


def main():
    wf = {q: _load(f"wf_{q}.json") for q in ("q1", "q2", "q3", "q4", "q5")}
    hi, hc = _load("holdout_india.json") or {}, _load("holdout_crypto.json") or {}
    hold = dict(hi, **hc)
    C = cells(wf, hold)
    for key in ("wf", "hold"):
        ps = [c[key]["nn_vs_best"]["p"] if c[key] else np.nan for c in C]
        for c, q in zip(C, E.bh(ps)):
            c[f"q_{key}"] = q
    R = rules(wf, hold)
    for key, get in (("wf", lambda r: r["wf"]["nn"]["pooled"]["p_vs_twins"]),
                     ("hold", lambda r: (r["hold"] or {}).get("nn", {}).get("p_vs_twins", np.nan))):
        ps = [get(r) if (key == "wf" or r["hold"]) else np.nan for r in R]
        for r, q in zip(R, E.bh(ps)):
            r[f"q_{key}"] = q
    for r in R:
        r["gates"] = gates(r)
    summary = dict(cells=[dict(q=c["q"], target=c["target"], best=c["wf"]["best_baseline"],
                               wf=c["wf"]["nn_vs_best"], wf_folds=c["wf"].get("nn_better_folds"), q_wf=c["q_wf"],
                               hold=(c["hold"] or {}).get("nn_vs_best"), q_hold=c["q_hold"]) for c in C],
                   rules=[dict(rid=r["rid"], q=r["q"], arm=r.get("arm"), q_wf=r["q_wf"], q_hold=r["q_hold"],
                               gates=r["gates"]) for r in R])
    (K.RESULTS / "summary.json").write_text(json.dumps(summary, indent=1, default=lambda o: None) + "\n")
    tables(C, R)


def f(x, nd=3):
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "-"
    return f"{x:,.{nd}f}" if isinstance(x, float) else str(x)


def tables(C, R):
    print("\n### Model tests (NN = mean of MLP, TCN, GRU seed ensembles)\n")
    print("| question | target | rows / sessions (WF) | NN | best baseline | metric NN vs best | NN-best diff [95% CI] WF | "
          "NN better in folds | q WF | holdout NN vs best [95% CI] | q holdout |")
    print("|---|---|---|---|---|---|---|---|---|---|---|")
    for c in C:
        w = c["wf"]
        kind = "bin" if w.get("base_rate") is not None else "reg"
        prim = "logloss" if kind == "bin" else "r2"
        nn, b = w["models"]["nn"], w["models"][w["best_baseline"]]
        aux = f" (AUC {f(nn.get('auc'))} vs {f(b.get('auc'))})" if kind == "bin" else ""
        d = w["nn_vs_best"]
        h = (c["hold"] or {}).get("nn_vs_best")
        hs = f"{f(h['diff'], 4)} [{f(h['lo'], 4)}, {f(h['hi'], 4)}]" if h else "not read"
        print(f"| {c['q']} | {c['target']} | {w['n']:,} / {w['sessions']:,} | {f(nn[prim], 4)} | {w['best_baseline']} "
              f"{f(b[prim], 4)} | {prim}{aux} | {f(d['diff'], 4)} [{f(d['lo'], 4)}, {f(d['hi'], 4)}] | "
              f"{w.get('nn_better_folds')} | {f(c['q_wf'])} | {hs} | {f(c['q_hold'])} |")
    print("\n### Trading rules (NN scores; the same rule from the trees' scores beside it)\n")
    print("| rule | what | part | trades | trades/day | win rate | avg win | avg loss | P&L/day | P&L/day stress | "
          "% green days | worst day | max DD | cut short | p vs twins | BH q | trees P&L/day |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
    for r in R:
        for part, src, q in (("WF", r["wf"]["nn"]["pooled"], r["q_wf"]),
                             ("holdout", (r["hold"] or {}).get("nn"), r["q_hold"])):
            if not src:
                continue
            tr = (r["wf"]["trees"]["pooled"] if part == "WF" else (r["hold"] or {}).get("trees")) or {}
            extra = ""
            if r["q"] == "q2":
                extra = f" (unfiltered {f(src['unfiltered']['pnl_per_day'], 0)}, skipped {src['skipped']})"
            print(f"| {r['rid']} | {RULE_TEXT.get(r['rid'], '')}{extra} | {part} | {src['trades']:,} | "
                  f"{f(src['trades_per_day'], 2)} | {f(src['win_rate'], 2)} | {f(src['avg_win'], 0)} | "
                  f"{f(src['avg_loss'], 0)} | {f(src['pnl_per_day'], 0)} | {f(src.get('pnl_per_day_stress'), 0)} | "
                  f"{f(src['green_days'], 2)} | {f(src['worst_day'], 0)} | {f(src['max_drawdown'], 0)} | "
                  f"{src.get('big_winners_cut_short', '-')} | {f(src['p_vs_twins'])} | {f(q)} | "
                  f"{f(tr.get('pnl_per_day'), 0)} |")
    print("\n### Gates (holdout)\n")
    for r in R:
        print(f"- {r['rid']}: {r['gates']}")


if __name__ == "__main__":
    sys.exit(main())
