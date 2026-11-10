"""R156 report: results/report.md (plain summary for Boss first) from results/{design,final,decision,importance}.json.

    python research/hunt/r156_scalp_network/r156report.py
"""
from __future__ import annotations

import json
import pickle

import numpy as np

import r156cfg as K
from r154report import rs as _rs

PN = {"O1": "O1 (2023 - Mar 2025)", "P2": "P2 (Apr 2025 - Mar 2026)", "P3": "P3 final (Apr - 13 Oct 2026)",
      "P4": "P4 post (14-19 Oct)"}


def rs(x) -> str:
    return "-" if x is None or (isinstance(x, float) and not np.isfinite(x)) else _rs(x)


def pct(x, d: int = 0) -> str:
    return "-" if x is None else f"{100 * float(x):.{d}f}%"


def line(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return f"{s['trades']:,} trades, win {pct(s['win'])} (needed {pct(s['be_win'])}), {rs(s['net'])} ({rs(s['per_trade'])} a trade)"


def med_auc(fc: dict, cols, key: str = "net"):
    v = [fc["auc"][K.RULE_IDS[j]][key] for j in cols if fc["auc"][K.RULE_IDS[j]][key] is not None]
    return round(float(np.median(v)), 3) if v else None


def pooled(rules: dict, tests) -> dict:
    """Sum of several tests' pooled stats (trades, net; win as the trade-weighted mean)."""
    n = sum(rules[t]["all"]["trades"] for t in tests)
    net = sum(rules[t]["all"]["net"] for t in tests)
    win = sum((rules[t]["all"]["win"] or 0) * rules[t]["all"]["trades"] for t in tests) / n if n else None
    return dict(trades=n, net=net, win=win, per_trade=(net / n if n else None))


def cal_table(cal: list) -> list:
    out = ["| tenth (1 = lowest forecast) | moments x exits | forecast win | real win | win needed | net a trade |", "|---|---|---|---|---|---|"]
    for c in cal:
        out.append(f"| {c['bin']} | {c['n']:,} | {pct(c['forecast'])} | {pct(c['win'])} | {pct(c['be_win'])} | {rs(c['per_trade'])} |")
    return out


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    imp = json.loads((K.RESULTS / "importance.json").read_text())
    per = {"O1": d["O1"], "P2": d["P2"], "P3": f["P3"]}
    passed = [t for t in K.TESTS if dec[t]["passed"]]
    ev_tests = [t for t in K.TESTS if t.startswith("EV-")]
    top_tests = [t for t in K.TESTS if t.startswith("TOP-")]
    best = dec["BEST"]
    pos3 = [t for t in K.TESTS if dec[t]["P3"]["net"] > 0]
    pos_all = [t for t in K.TESTS if dec[t]["O1"]["net"] > 0 and dec[t]["P2"]["net"] > 0 and dec[t]["P3"]["net"] > 0]
    n_beat = [t for t in K.TESTS if dec[t]["q"] <= K.GATE_Q]
    minq = min(dec[t]["q"] for t in K.TESTS)
    aucs = {p: {rd: (med_auc(per[p]["forecast"], cols), med_auc(per[p]["forecast"], cols, "trees"))
                for rd, cols in (("A", K.A_IDX), ("B", K.B_IDX))} for p in per}
    top = {p: {rd: per[p]["forecast"]["calibration"][rd][-1] for rd in "AB"} for p in per}
    base = {p: per[p]["forecast"]["base"] for p in per}
    g = imp["groups"]
    gs = sorted(g.items(), key=lambda kv: -kv[1])
    tot_imp = sum(max(v, 0) for v in g.values()) or 1.0
    bestP3 = max(K.TESTS, key=lambda t: dec[t]["P3"]["per_trade"] if dec[t]["P3"]["trades"] >= K.MIN_FINAL else -1e9)
    ex = d["examples"]
    fcf = pickle.loads((K.OUT / "fc_final.pkl").read_bytes())
    share_pos = float((fcf["ev"] > 0).mean())
    hist = {}
    for name, _, _ in K.FOLDS:
        try:
            hist[name] = pickle.loads((K.OUT / f"fc_{name}.pkl").read_bytes())["hist"]
        except FileNotFoundError:
            pass

    L = ["# R156: a neural network trained on the scalp itself", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'some rules pass' if passed else 'the network cannot make 10-15 point scalping profitable'}.** "
         f"{len(passed)} of {len(K.TESTS)} trading rules built on its forecasts passed the gates.",
         f"- **What it was:** a network that reads the last hour of 1-minute index bars plus 43 numbers (support / resistance, "
         f"trend, OI, wicks, VIX, time), trained on the real after-cost result of {ex['total']:,} scalp examples (every 5-minute "
         "moment, calls and puts, all 5 indices, 24 exits), 3 networks averaged, always tested on later days than it learned from.",
         f"- **Can it tell a winning scalp from a losing one? Only slightly.** Scored 0.5 = coin, 1.0 = perfect: option-point "
         f"scalps {aucs['P2']['A'][0]} (Apr 2025 - Mar 2026) and {aucs['P3']['A'][0]} (Apr - Oct 2026); index-point scalps "
         f"{aucs['P2']['B'][0]} and {aucs['P3']['B'][0]}. Plain decision trees on the 43 numbers score "
         f"{aucs['P3']['A'][1]} / {aucs['P3']['B'][1]}.",
         f"- **Its best tenth of moments still loses.** Option points, Apr - Oct 2026: the network's top tenth won "
         f"{pct(top['P3']['A']['win'])} against {pct(top['P3']['A']['be_win'])} needed ({rs(top['P3']['A']['per_trade'])} a trade); "
         f"an average moment won {pct(base['P3']['A']['win'])} against {pct(base['P3']['A']['be_win'])} "
         f"({rs(base['P3']['A']['per_trade'])}). Index points: top tenth {pct(top['P3']['B']['win'])} vs "
         f"{pct(top['P3']['B']['be_win'])} needed ({rs(top['P3']['B']['per_trade'])}); average {pct(base['P3']['B']['win'])} vs "
         f"{pct(base['P3']['B']['be_win'])} ({rs(base['P3']['B']['per_trade'])}).",
         f"- **Trading its picks (1 lot):** the network choosing the moment, side and exit itself (BEST): "
         f"{PN['O1']}: {line(best['O1'])}; {PN['P2']}: {line(best['P2'])}; {PN['P3']}: {line(best['P3'])}.",
         f"- **Best single rule in the final period:** {bestP3}: {line(dec[bestP3]['P3'])}; earlier: {rs(dec[bestP3]['O1']['net'])} "
         f"and {rs(dec[bestP3]['P2']['net'])}.",
         f"- **It mostly learned to say no.** Across the final period it forecast a profit for only {pct(share_pos, 1)} of the "
         "moment x side x exit choices. The few it did take lost less than an average scalp but still lost: the 24 "
         f"'forecast profit' rules together made {rs(pooled(per['P3']['rules'], ev_tests)['per_trade'])} a trade.",
         f"- {len(pos3)} of {len(K.TESTS)} rules made money in the final period; {len(pos_all)} in all three periods ("
         + ", ".join(f"{t} with {dec[t]['P3']['trades']} final trades" for t in pos_all)
         + f": far too few to mean anything); {len(n_beat)} beat random entries after the multiple-testing correction "
         f"(smallest q {minq}).",
         "- **The network is no better than plain decision trees** on the same 43 numbers (table below), so the extra "
         "machinery found nothing the simpler model missed.",
         "- **What the network leans on:** " + "; ".join(f"{k} {100 * max(v, 0) / tot_imp:.0f}%" for k, v in gs[:4])
         + " (share of the forecast lost when that input is scrambled).",
         "- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r156-*.yaml`.", "",
         "Signal source: the index (its 1-minute bars and index-derived context); the option chain and India VIX only as "
         "context; the 1-ITM nearest-expiry option is bought.", "",
         "## How it was tested", "",
         "Pre-registered in `PREREG.md` before any label or model. Examples: every 09:15-anchored 5-minute close from 09:19 to "
         f"14:44, both sides, per index: {', '.join(f'{u} {n:,}' for u, n in ex['by_und'].items())} = {ex['total']:,} "
         f"({pct(ex['labelled'])} of the moment x exit cells have a fill). **The pre-registration guessed 'about 1.3 million' "
         "examples; the real count is the one above** (66 moments x 2 sides x the sessions with options). Labels are R154's "
         "engine results (checked equal rule by rule). Network: three 1-D convolutions over the last 60 minutes + a table "
         "branch, 24 x (win chance, expected net); Adam, 6 epochs at most, early stopping on the last 10% of training sessions; "
         "3 seeds averaged. Walk-forward: the models that score 2023, 2024, 2025, Jan - Mar 2026 and the final each saw only "
         "earlier sessions. Rules: EV-r (take the side with the higher forecast net if > 0), TOP-r (forecast win chance in the "
         "top tenth of the model's training forecasts), BEST (the best exit and side among 12 / 12 / 24); one open position "
         "per index, 5 trades per index a day. Gates: net > 0 in O1, P2 and P3; beats 2,000 random-entry twins (same number "
         f"of trades per index and session, random moments and sides) with BH q <= 0.10 (m = {dec['_m']['total']:,}); >= 40 "
         "final trades. **P3 was read for scalps by R154 and R155: a third look.**", "",
         "## Can a scalp's result be forecast? (AUC: 0.5 = coin)", "",
         "| period | option points: network / trees (median of 12 exits) | index points: network / trees |", "|---|---|---|"]
    for p in per:
        L.append(f"| {PN[p]} | {aucs[p]['A'][0]} / {aucs[p]['A'][1]} | {aucs[p]['B'][0]} / {aucs[p]['B'][1]} |")
    L += ["", "Per exit rule (network AUC in O1 / P2 / P3):", "", "| exit | O1 | P2 | P3 |", "|---|---|---|---|"]
    for r in K.RULE_IDS:
        L.append(f"| {r} | {per['O1']['forecast']['auc'][r]['net']} | {per['P2']['forecast']['auc'][r]['net']} | "
                 f"{per['P3']['forecast']['auc'][r]['net']} |")
    for p in ("P2", "P3"):
        for rd, nm in (("A", "option points"), ("B", "index points")):
            L += ["", f"## The forecast in tenths, {nm}, {PN[p]}", ""] + cal_table(per[p]["forecast"]["calibration"][rd])
    L += ["", "## Every trading rule (1 lot, pooled over the 5 indices)", "",
          "| rule | O1 trades / win / net | P2 trades / win / net | P3 trades | P3 win / needed | P3 net | P3 a trade | random twins' mean | p | q | verdict |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    for t in K.TESTS:
        g_ = dec[t]
        verdict = "PASS" if g_["passed"] else "fail: " + "; ".join(g_["fails"])
        L.append(f"| {t} | {g_['O1']['trades']:,} / {pct(g_['O1']['win'])} / {rs(g_['O1']['net'])} | {g_['P2']['trades']:,} / "
                 f"{pct(g_['P2']['win'])} / {rs(g_['P2']['net'])} | {g_['P3']['trades']:,} | {pct(g_['P3']['win'])} / "
                 f"{pct(g_['P3']['be_win'])} | {rs(g_['P3']['net'])} | {rs(g_['P3']['per_trade'])} | {rs(g_['twin_mean'])} | "
                 f"{g_['twin_p']} | {g_['q']} | {verdict} |")
    L += ["", "EV rules together: " + "; ".join(f"{PN[p]}: {pooled(per[p]['rules'], ev_tests)['trades']:,} trades, "
                                                f"{rs(pooled(per[p]['rules'], ev_tests)['per_trade'])} a trade" for p in per) + ".",
          "TOP rules together: " + "; ".join(f"{PN[p]}: {pooled(per[p]['rules'], top_tests)['trades']:,} trades, "
                                             f"{rs(pooled(per[p]['rules'], top_tests)['per_trade'])} a trade" for p in per) + ".",
          "", "## BEST (the network picks moment, side and exit): where its trades are", "",
          "| period | by index (trades / net) | calls | by hour (trades / net) | exits it chose most |", "|---|---|---|---|---|"]
    for p in per:
        r = per[p]["rules"]["BEST"]
        bu = "; ".join(f"{u} {s['trades']} / {rs(s['net'])}" for u, s in r["by_und"].items())
        bh_ = "; ".join(f"{h}h {v[0]} / {rs(v[1])}" for h, v in sorted(r["by_hour"].items(), key=lambda kv: int(kv[0])))
        br = ", ".join(f"{k} {v}" for k, v in sorted((r["by_rule"] or {}).items(), key=lambda kv: -kv[1])[:4])
        L.append(f"| {PN[p]} | {bu} | {pct(r['calls'])} | {bh_} | {br} |")
    L += ["", "## What the network leans on (shuffle test, Apr - Dec 2025, fold 2025's models)", "",
          f"Win-forecast loss {imp['base_loss']} on {imp['rows']:,} examples; the increase when one input group is shuffled "
          "across examples:", "", "| input group | loss increase | share |", "|---|---|---|"]
    L += [f"| {k} | {v} | {100 * max(v, 0) / tot_imp:.0f}% |" for k, v in gs]
    L += ["", "## Training (validation loss per epoch; the lowest is kept)", "", "| fold | seed 156 | seed 157 | seed 158 |", "|---|---|---|---|"]
    for name, hs in hist.items():
        L.append(f"| {name} | " + " | ".join(", ".join(f"{h[2]:.4f}" for h in one) for one in hs) + " |")
    L += ["", "## Post window (14-19 Oct 2026, 4 sessions, never gated)", "",
          f"BEST: {line(dec['BEST']['P4'])}. EV rules together: {pooled(f['P4']['rules'], ev_tests)['trades']:,} trades, "
          f"{rs(pooled(f['P4']['rules'], ev_tests)['net'])}.", "",
          "## Earlier findings, honestly", "",
          "- R154: no entry makes the scalp pay. R155: no filter does; a logistic picker loses less.",
          "- R16, B1, R41, R123, R124: the size of the next move is predictable, its direction is a coin. A scalp with a "
          "target and a stop of similar size is a bet on direction, minus costs. R156's network, trained on the scalp itself, "
          "leans on the same inputs that predict size (the last hour of bars, VIX and the day's scale) and gains only a "
          "small edge in picking winners, well short of the win rate the costs require."]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    summ = dict(passed=passed, m=dec["_m"], auc=aucs, top_tenth=top, base=base, best=dict(O1=best["O1"], P2=best["P2"], P3=best["P3"]),
                positive_final=pos3, positive_all=pos_all, beat_twins=n_beat, importance=gs, examples=ex)
    (K.RESULTS / "summary.json").write_text(json.dumps(summ, indent=1, default=str))
    print("report written;", len(passed), "passed")


if __name__ == "__main__":
    main()
