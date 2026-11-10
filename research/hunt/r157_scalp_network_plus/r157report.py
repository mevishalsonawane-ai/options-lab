"""R157 report: results/report.md (plain summary for Boss first) from results/*.json and R156's results.

    python research/hunt/r157_scalp_network_plus/r157report.py
"""
from __future__ import annotations

import json

import numpy as np

import r157cfg as K
from r156report import PN, line, pct, rs

K6 = K.K6
R156 = K.HERE.parent / "r156_scalp_network" / "results"


def med(vals) -> float | None:
    v = [x for x in vals if x is not None]
    return round(float(np.median(v)), 3) if v else None


def pool(rules: dict, tests) -> dict:
    n = sum(rules[t]["all"]["trades"] for t in tests)
    net = sum(rules[t]["all"]["net"] for t in tests)
    win = sum((rules[t]["all"]["win"] or 0) * rules[t]["all"]["trades"] for t in tests) / n if n else None
    return dict(trades=n, net=net, win=win, per_trade=(net / n if n else None))


def pl(s: dict, lots: int = 1) -> str:
    if not s["trades"]:
        return "0 trades"
    return f"{s['trades']:,} trades, win {pct(s['win'])}, {rs(s['per_trade'] / lots)} a lot a trade"


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    imp = json.loads((K.RESULTS / "importance.json").read_text())
    d6 = json.loads((R156 / "design.json").read_text())
    f6 = json.loads((R156 / "final.json").read_text())
    dec6 = json.loads((R156 / "decision.json").read_text())
    per = {"O1": d["O1"], "P2": d["P2"], "P3": f["P3"]}
    per6 = {"O1": d6["O1"], "P2": d6["P2"], "P3": f6["P3"]}
    passed = [t for t in K.TESTS if dec[t]["passed"]]
    pos3 = [t for t in K.TESTS if dec[t]["P3"]["net"] > 0]
    pos_all = [t for t in K.TESTS if all(dec[t][p]["net"] > 0 for p in ("O1", "P2", "P3"))]
    beat = [t for t in K.TESTS if dec[t]["q"] <= K.GATE_Q]
    minq = min(dec[t]["q"] for t in K.TESTS)

    def auc7(p, js):
        return med(per[p]["forecast"]["auc"][f"{K.RULE_IDS[j]}@1"] for j in js)

    def auc6(p, js):
        return med(per6[p]["forecast"]["auc"][K6.RULE_IDS[j]]["net"] for j in js)

    def grp(p, g, n):
        return per[p]["forecast"]["groups"][f"{g}@{n}"]

    def tests_of(kind, n, js=range(K.NR)):
        return [f"{kind}-{K.RULE_IDS[j]}@{n}" for j in js]
    gA, gB, gR = "fixed, option points", "fixed, index points", "run exits"
    g = imp["groups"]
    tot = sum(max(v, 0) for v in g.values()) or 1.0
    gs = sorted(g.items(), key=lambda kv: -kv[1])
    cross = g["the other four indices' last hour"]
    top99 = {n: {p: pool(per[p]["rules"], tests_of("TOP99", n)) for p in per} for n in K.LOTS}
    ev = {n: {p: pool(per[p]["rules"], tests_of("EV", n)) for p in per} for n in K.LOTS}
    evrun = {p: pool(per[p]["rules"], tests_of("EV", 1, range(24, 28))) for p in per}
    best_wr = max((t for t in K.TESTS if dec[t]["P3"]["trades"] >= K.MIN_FINAL), key=lambda t: dec[t]["P3"]["win"])
    best_pt = max((t for t in K.TESTS if dec[t]["P3"]["trades"] >= K.MIN_FINAL),
                  key=lambda t: dec[t]["P3"]["per_trade"] / int(t.rsplit("@", 1)[1]))
    b1, b3, b6 = dec["BEST@1"], dec["BEST@3"], dec6["BEST"]
    t10 = {p: grp(p, gA, 1)["tenths"][-1] for p in per}
    t10_6 = {p: per6[p]["forecast"]["calibration"]["A"][-1] for p in per}
    L = ["# R157: the scalp network, enhanced for win rate and profit", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'some rules pass' if passed else 'the enhancements raise the win rate a little, not the profit'}.** "
         f"{len(passed)} of {len(K.TESTS)} trading rules passed the gates.",
         "- **What was added to R156's network:** (1) the other four indices' last hour of bars, (2) results at 3 lots as well "
         "as 1, (3) four 'let the winner run' exits (stop 10 or 15 points, then a trailing stop once the trade is that far "
         "up), (4) a pickier rule: only the top 1% of forecasts.",
         f"- **Telling winners from losers (0.5 = coin):** option-point exits {auc7('P3', K6.A_IDX)} now vs {auc6('P3', K6.A_IDX)} in "
         f"R156 (Apr - Oct 2026); index-point exits {auc7('P3', K6.B_IDX)} vs {auc6('P3', K6.B_IDX)}. Scrambling the other "
         f"indices' bars costs the forecast {100 * max(cross, 0) / tot:.0f}% of what all inputs together carry.",
         f"- **Highest win rate reached (final period, 40+ trades):** {best_wr}: {line(dec[best_wr]['P3'])}.",
         f"- **Top 1% of forecasts only (TOP99, all 28 exits, 1 lot):** " + "; ".join(
             f"{PN[p].split(' (')[0]}: {pl(top99[1][p])}" for p in per) + ".",
         f"- **The network picks everything (BEST):** 1 lot: {pl(b1['O1'])} / {pl(b1['P2'])} / {pl(b1['P3'])} (O1 / P2 / P3); "
         f"3 lots: {pl(b3['O1'], 3)} / {pl(b3['P2'], 3)} / {pl(b3['P3'], 3)}. R156's BEST: {rs(b6['O1']['per_trade'])} / "
         f"{rs(b6['P2']['per_trade'])} / {rs(b6['P3']['per_trade'])} a trade.",
         f"- **3 lots:** an average option-point scalp loses {rs(grp('P3', gA, 3)['base']['per_trade'] / 3)} a lot at 3 lots against "
         f"{rs(grp('P3', gA, 1)['base']['per_trade'])} at 1 lot (final period): cheaper per lot, still a loss.",
         f"- **'Let the winner run' exits:** an average one wins {pct(grp('P3', gR, 1)['base']['win'])} where "
         f"{pct(grp('P3', gR, 1)['base']['be_win'])} is needed ({rs(grp('P3', gR, 1)['base']['per_trade'])} a trade, final period); the "
         f"network's own picks on them: " + "; ".join(f"{PN[p].split(' (')[0]}: {pl(evrun[p])}" for p in per) + ".",
         f"- **Best result per lot in the final period (40+ trades):** {best_pt}: {line(dec[best_pt]['P3'])}; earlier periods "
         f"{rs(dec[best_pt]['O1']['net'])} and {rs(dec[best_pt]['P2']['net'])}.",
         f"- {len(pos3)} of {len(K.TESTS)} rules made money in the final period, {len(pos_all)} in all three periods"
         + (" (" + ", ".join(f"{t}: {dec[t]['P3']['trades']} final trades" for t in pos_all) + ")" if pos_all else "")
         + f"; {len(beat)} beat random entries after the multiple-testing correction (smallest q {minq}).",
         "- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r157-*.yaml`.", "",
         "Signal source: the index and the other four indices (1-minute bars); option-chain OI and India VIX as context; the "
         "1-ITM option is bought. **The final period (Apr - 13 Oct 2026) has now been read four times for scalps (R154 - R157): "
         "weak evidence either way.**", "",
         "## Did the enhancements help? R157 against R156 (R156's 24 exits, 1 lot)", "",
         "| period | AUC option points: R157 / R156 | AUC index points: R157 / R156 | top tenth, option points: win / needed / a trade, R157 | the same, R156 |",
         "|---|---|---|---|---|"]
    for p in per:
        L.append(f"| {PN[p]} | {auc7(p, K6.A_IDX)} / {auc6(p, K6.A_IDX)} | {auc7(p, K6.B_IDX)} / {auc6(p, K6.B_IDX)} | "
                 f"{pct(t10[p]['win'])} / {pct(t10[p]['be_win'])} / {rs(t10[p]['per_trade'])} | "
                 f"{pct(t10_6[p]['win'])} / {pct(t10_6[p]['be_win'])} / {rs(t10_6[p]['per_trade'])} |")
    L += ["", "## Every kind of exit: the average moment, the network's top tenth and its top 1%", "",
          "| period | exits | lots | average: win / needed / a trade | top tenth | top 1% |", "|---|---|---|---|---|---|"]
    for p in per:
        for n in K.LOTS:
            for gname in (gA, gB, gR):
                x = grp(p, gname, n)
                t, o = x["tenths"][-1], x["top1"]
                L.append(f"| {PN[p]} | {gname} | {n} | {pct(x['base']['win'])} / {pct(x['base']['be_win'])} / {rs(x['base']['per_trade'])} | "
                         f"{pct(t['win'])} / {pct(t['be_win'])} / {rs(t['per_trade'])} | "
                         f"{pct(o['win'])} / {pct(o['be_win'])} / {rs(o['per_trade'])} |")
    L += ["", "Rupees are for the whole position (divide the 3-lot rows by 3 for a lot).", "",
          "## Rule families pooled (5 indices)", "", "| family | lots | O1 | P2 | P3 final |", "|---|---|---|---|---|"]
    for n in K.LOTS:
        L.append(f"| EV (forecast net > 0), 28 exits | {n} | " + " | ".join(pl(ev[n][p], n) for p in per) + " |")
        L.append(f"| TOP99 (top 1% win forecast), 28 exits | {n} | " + " | ".join(pl(top99[n][p], n) for p in per) + " |")
        L.append(f"| BEST | {n} | " + " | ".join(pl(dec[f'BEST@{n}'][p], n) for p in per) + " |")
    L += ["", "## What the network leans on (shuffle test, Apr - Dec 2025)", "", "| input group | loss increase | share |", "|---|---|---|"]
    L += [f"| {k} | {v} | {100 * max(v, 0) / tot:.0f}% |" for k, v in gs]
    L += ["", "## Every rule (pooled over the 5 indices; rupees for the position)", "",
          "| rule | O1 trades / win / net | P2 trades / win / net | P3 trades | P3 win / needed | P3 net | twins' mean | p | q | verdict |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    for t in K.TESTS:
        g_ = dec[t]
        v = "PASS" if g_["passed"] else "fail: " + "; ".join(g_["fails"])
        L.append(f"| {t} | {g_['O1']['trades']:,} / {pct(g_['O1']['win'])} / {rs(g_['O1']['net'])} | {g_['P2']['trades']:,} / "
                 f"{pct(g_['P2']['win'])} / {rs(g_['P2']['net'])} | {g_['P3']['trades']:,} | {pct(g_['P3']['win'])} / "
                 f"{pct(g_['P3']['be_win'])} | {rs(g_['P3']['net'])} | {rs(g_['twin_mean'])} | {g_['twin_p']} | {g_['q']} | {v} |")
    L += ["", "## Post window (14-19 Oct 2026, never gated)", "",
          f"BEST@1: {line(dec['BEST@1']['P4'])}. BEST@3: {line(dec['BEST@3']['P4'])}."]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    (K.RESULTS / "summary.json").write_text(json.dumps(dict(
        passed=passed, m=dec["_m"], positive_final=pos3, positive_all=pos_all, beat_twins=beat, min_q=minq,
        best_win_rate=(best_wr, dec[best_wr]["P3"]), best_per_lot=(best_pt, dec[best_pt]["P3"]),
        auc_P3=dict(A=(auc7("P3", K6.A_IDX), auc6("P3", K6.A_IDX)), B=(auc7("P3", K6.B_IDX), auc6("P3", K6.B_IDX))),
        importance=gs), indent=1, default=str))
    print("report written;", len(passed), "passed")


if __name__ == "__main__":
    main()
