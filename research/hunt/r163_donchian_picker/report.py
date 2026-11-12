"""R163 report: results/report.md from results/wf.json and results/final.json (plain English summary first).

    python research/hunt/r163_donchian_picker/report.py
"""
from __future__ import annotations

import csv
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
NAMES = {"P151": "R151's live picker (logistic)", "G1": "G1 gradient trees, P(win)", "G2": "G2 gradient trees, expected net",
         "N1": "N1 small neural net (MLP)", "E1": "E1 calibrated ensemble (G1 + N1 + R151's logistic)"}


def L(x) -> str:
    """Rupees in lakh with sign."""
    return f"{'-' if x < 0 else ''}Rs {abs(x) / 1e5:.2f}L"


def R(x) -> str:
    return f"{'-' if x < 0 else ''}Rs {abs(x):,.0f}"


def P(x) -> str:
    return f"{100 * x:.1f}%"


def row(name, m, extra="") -> str:
    if not m or not m.get("trades"):
        return f"| {name} | 0 | - | - | - | - | - | - | - |{extra}"
    return (f"| {name} | {m['trades']:,} | {P(m['win'])} | {L(m['net'])} | {R(m['per_trade'])} | {L(m['net_2lots'])} | "
            f"{L(m['net_3lots'])} | {R(m['worst_month'])} | {R(m['max_dd'])} |{extra}")


HEAD = "| | trades | win | net (1 lot) | per trade | net 2 lots | net 3 lots | worst month | drawdown |"
SEP = "|---|---:|---:|---:|---:|---:|---:|---:|---:|"


def main() -> None:
    w = json.loads((RESULTS / "wf.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    pv = list(csv.DictReader((RESULTS / "pvalues.csv").open()))
    passed = [r for r in pv if r["pass"] == "True"]
    out = ["# R163: a stronger trade picker for the Donchian arms (R72, R51)", ""]
    out += ["## For Boss, in short", ""]
    if passed:
        out.append(f"- **Passed:** {', '.join(r['test'] for r in passed)}. Even so it is a second look at Apr - Oct 2026: at most a "
                   "shadow label next to R151's picker. Nothing in the app changes now.")
    else:
        out.append("- **No new picker passed. Keep R151's pickers as they are.** Gradient trees, a small neural net and a "
                   "calibrated ensemble, fed many more inputs (option-chain OI / PCR / IV, VIX, breadth, 15-60 minute trend, "
                   "S/R, expiry), did not raise the win rate 5 points above R151's picker without losing money, neither in "
                   "the walk-forward test (Apr 2025 - Mar 2026) nor in Apr - Oct 2026. Nothing in the app changes.")
    for arm in ("R72", "R51"):
        fa = f[arm]
        b = fa["P151"]["final"]
        best = max(fa["methods"].items(), key=lambda kv: kv[1]["final"]["win"] if kv[1]["final"]["trades"] >= 40 else -1)
        bm = best[1]["final"]
        out.append(f"- **{arm}, Apr - Oct 2026, 1 lot, the app's rule:** plain arm {fa['plain']['trades']:,} trades, "
                   f"{P(fa['plain']['win'])} won, {L(fa['plain']['net'])}. R151's picker {b['trades']:,} trades, {P(b['win'])} won, "
                   f"{L(b['net'])}. Best new model by win rate ({best[0]}): {bm['trades']:,} trades, {P(bm['win'])} won, "
                   f"{L(bm['net'])} ({100 * (bm['win'] - b['win']):+.1f} points vs R151; the bar was +5).")
    aucs = [r["auc"] for a in ("R72", "R51") for r in f[a]["methods"].values() if "auc" in r]
    out.append(f"- **Why:** at the moment of a break, whether it will win is close to a coin toss for every model "
               f"(AUC {min(aucs):.2f} - {max(aucs):.2f} on Apr - Oct 2026; 0.50 = no skill). When the trees say \"60%+\", "
               "about half of those trades won (calibration table).")
    r72 = f["R72"]
    out.append(f"- **Worth knowing about the live picker:** R151 measured its pickers with skipped breaks FREEING their side "
               f"(R72 {r72['P151']['research_walk']['trades']} trades, {L(r72['P151']['research_walk']['net'])}). The app keeps "
               f"the side busy, as if traded. In that walk R72's picker made {L(r72['P151']['final']['net'])}, about the same as "
               f"the plain arm ({L(r72['plain']['net'])}); its gains are a higher win rate ({P(r72['P151']['final']['win'])} vs "
               f"{P(r72['plain']['win'])}) and half the drawdown ({R(r72['P151']['final']['max_dd'])} vs {R(r72['plain']['max_dd'])}), "
               f"not more money. R51's picker: {L(f['R51']['P151']['final']['net'])} vs {L(f['R51']['plain']['net'])} plain.")
    out.append(f"- **At 2 and 3 lots** the plain arms make more in total than the pickers (R72 at 3 lots: plain "
               f"{L(r72['plain']['net_3lots'])}, R151's picker {L(r72['P151']['final']['net_3lots'])}), because charges weigh less "
               "per lot while the picker takes about half the trades. The picker still halves the drawdown.")
    out.append("")
    out += ["## What was tested", "",
            "- Pre-registered in `PREREG.md` (committed before any number). Signal source: the index (each arm's own Donchian "
            "break); option-chain data only as context; no option-chart input.",
            "- Four new pickers per arm (G1, G2, N1, E1) against R151's live logistic picker (P151, thresholds 0.4656 / 0.4469).",
            "- **The app's walk** everywhere: a skipped break still holds its side and counts in the day caps, so a picker only "
            "removes trades from the plain arm's list.",
            "- Walk-forward: 4 quarters Apr 2025 - Mar 2026, each scored by models trained only on earlier trades (from 20 Nov "
            "2024). Thresholds chosen there only. FINAL Apr - 13 Oct 2026: models trained on all trades to 31 Mar 2026, frozen, "
            "read once. FINAL has been read by many earlier studies of these arms (R151 included): a second look. The lake ends "
            "19 Oct 2026, so the post window (14 - 19 Oct, 4 sessions) is too short for any check.",
            "- Costs: dated Zerodha charges and R17's slippage on every trade; 2 and 3 lots from each trade's own fills.",
            "- R123's network score was not used (its predictions were never stored; rebuilding it was out of reach). Its "
            "tabular inputs went in directly.", ""]
    for arm in ("R72", "R51"):
        fa, wa = f[arm], w[arm]
        out += [f"## {arm}", "", f"### Walk-forward (Apr 2025 - Mar 2026, out-of-fold, app walk, {wa['design_trades']:,} design trades)", "",
                HEAD, SEP, row("plain arm", wa["plain"]), row(f"P151 (thr {wa['P151']['thr']})", wa["P151"]["wf"])]
        for mth, r in wa["methods"].items():
            pk = r["pick"]
            out.append(row(f"{mth} (keep {pk['keep']:.0%}, thr {pk['thr']:.4g})" if pk else f"{mth}: no threshold reached P151's net", pk or {}))
        out += ["", f"### FINAL (1 Apr - 13 Oct 2026, read once, app walk)", "", HEAD + " exact twin p | q (m all) | pass |",
                SEP + "---:|---:|---|", row("plain arm", fa["plain"], " - | - | - |"),
                row("P151 (live picker)", fa["P151"]["final"], f" {fa['P151']['hyper_p']:.3g} | comparator | - |")]
        for mth, r in fa["methods"].items():
            lab = mth + ("" if r["wf_picked"] else " (WF failed; thr = best WF win, report only)")
            out.append(row(lab, r["final"], f" {r['hyper_p']:.3g} | {r['q_all']:.3g} | {'yes' if r['pass'] else 'no'} |"))
        rw = fa["P151"]["research_walk"]
        out += ["", f"- **The app's rule vs R151's research walk:** R151's picker in the research walk (skipped breaks free their "
                f"side) gives {rw['trades']:,} trades, {P(rw['win'])} won, {L(rw['net'])}; in the app's walk "
                f"{fa['P151']['final']['trades']:,} trades, {P(fa['P151']['final']['win'])} won, {L(fa['P151']['final']['net'])}.",
                f"- P151 refit check: largest coefficient difference vs the exported model {fa['P151']['coef_max_abs_diff_vs_exported']:.2g}.", ""]
        out += ["Why each failed:", ""]
        for mth, r in fa["methods"].items():
            out.append(f"- {mth}: " + ("passed" if r["pass"] else "; ".join(r["fails"])))
        out += ["", "### Calibration: predicted P(win) vs what happened", "", "| model | period | " +
                " | ".join(b["bin"] for b in fa["P151"]["calibration"]) + " | AUC | Brier |", "|---|---|" + "---:|" * 8]
        for mth in ("P151", "G1", "N1", "E1"):
            for per, src in (("WF", wa["P151"] if mth == "P151" else wa["methods"][mth]),
                             ("FINAL", fa["P151"] if mth == "P151" else fa["methods"][mth])):
                cells = [f"{100 * b['actual']:.0f}% of {b['n']}" if b["n"] else "-" for b in src["calibration"]]
                out.append(f"| {mth} | {per} | " + " | ".join(cells) + f" | {src['auc']:.3f} | {src['brier']:.4f} |")
        g1 = wa["methods"]["G1"].get("perm_importance", [])[:15]
        out += ["", "### What the trees look at most (G1, permutation importance on walk-forward, AUC drop)", ""]
        out += [f"{i + 1}. `{r['feature']}` ({r['auc_drop']:+.4f})" for i, r in enumerate(g1)]
        gi = fa["methods"]["G1"].get("gain_importance", [])[:10]
        out += ["", "Frozen G1 by gain: " + ", ".join(f"`{r['feature']}`" for r in gi), ""]
    out += ["## Multiple testing", "", f"- Raw p-values (exact hypergeometric random-skip twin on FINAL win rate) for the joint BH with "
            f"R161 / R162: `research/hunt/r163_donchian_picker/results/pvalues.csv`.",
            f"- q counted two ways: within R163 (m = 8) and with every earlier test at p = 1 (m = {f['_meta']['n_prior']} + 8).", "",
            "| test | trades | win | net | raw p | q (R163) | q (all) | pass |", "|---|---:|---:|---:|---:|---:|---:|---|"]
    for r in pv:
        out.append(f"| {r['test']} | {r['trades']} | {P(float(r['win']))} | {L(float(r['net']))} | {float(r['p_raw']):.3g} | "
                   f"{float(r['q_r163']):.3g} | {float(r['q_all']):.3g} | {r['pass']} |")
    out += ["", "## Glossary", "", "- **win**: share of trades with net > 0 after charges and slippage.",
            "- **app walk**: a skipped break still holds its side and day caps, as the app does.",
            "- **exact twin p**: the chance that keeping the same number of the arm's trades at random wins at least as often.", ""]
    (RESULTS / "report.md").write_text("\n".join(out))
    print("\n".join(out[:12]))


if __name__ == "__main__":
    main()
