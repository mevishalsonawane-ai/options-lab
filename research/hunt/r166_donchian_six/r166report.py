"""R166 report and experiment entries from results/*.json and the hand-written results/boss_summary.md.

    python research/hunt/r166_donchian_six/r166report.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r166cfg as K
from r162report import pct, rs

ENTRIES = K.K162.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r166_donchian_six/results/report.md"
PREREG = "research/hunt/r166_donchian_six/PREREG.md"
HEAD = "| rule | trades | win % | break-even win % | net | avg win | avg loss | worst day | max DD | net without 5 best |"


def row(name, s, extra=""):
    return (f"| {name} | {s['trades']} | {pct(s['win'])} | {pct(s.get('be_win'))} | {rs(s['net'])} | {rs(s.get('avg_win'))} | "
            f"{rs(-(s.get('avg_loss') or 0))} | {rs(s.get('worst_day'))} | {rs(s.get('max_dd'))} | {rs(s.get('ex_top5'))} |{extra}")


def main():
    d = {a: json.loads((K.RESULTS / f"design_{a}.json").read_text()) for a in K.ARMS}
    f = {a: json.loads((K.RESULTS / f"final_{a}.json").read_text()) for a in K.ARMS}
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    out = ["# R166: six checks on the Donchian arms, one by one - results", ""]
    b = K.RESULTS / "boss_summary.md"
    if b.exists():
        out += [b.read_text().strip(), ""]
    out += ["## How it was tested", "",
            "Pre-registered in `PREREG.md` (plus Amendment 1, a count correction), both pushed before any number. Nov 2024 "
            "onward only (Boss). 1 lot, today's exits; entries unchanged (signal on the index). Engine, charges, slippage "
            "and twins as R162. A skipped break still holds its side and counts in the 2-a-side cap. Design 20 Nov 2024 - "
            "31 Mar 2026 (walk-forward Apr 2025 - Mar 2026); final 1 Apr - 13 Oct 2026, read once (weak evidence: read by "
            "many studies; for the delta rules not a fresh test, so they are judged on the walk-forward year). "
            f"BH m = {dec['_m']['total']}. Raw p-values: `results/raw_pvalues.csv`.", ""]
    n_pass = sum(1 for k, v in dec.items() if not k.startswith("_") and v["passed"])
    out += [f"**Tests: {len(K.TESTS)}; passed every gate: {n_pass}.**", ""]
    for fam, name in K.FAMILY.items():
        out += [f"## {fam}. {name.capitalize()}", ""]
        for a in K.ARMS:
            out += [f"**{K.ARM_NAME[a]}**", "", "| rule | period | trades | win % | net | max DD | twin p (net / win) | BH q | verdict |",
                    "|---|---|---|---|---|---|---|---|---|"]
            for per, src, key in (("final", f[a], "all"), ("walk-forward", d[a], "wf"), ("design", d[a], "all")):
                s = src["B0"][key]
                out.append(f"| B0 today | {per} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['max_dd'])} | | | |")
            for r in K.RULE_IDS:
                if K.RULES[r]["fam"] != fam:
                    continue
                g = dec[f"{a}:{r}"]
                for i, (per, src, key) in enumerate((("final", f[a], "all"), ("walk-forward", d[a], "wf"),
                                                     ("design", d[a], "all"))):
                    s = src[r][key]
                    tail = (f" {g['p_net']:.3f} / {g['p_win']:.3f} | {g['q_all']:.3f} | "
                            f"{'PASS' if g['passed'] else 'fail: ' + '; '.join(g['fails'])} |") if i == 0 else " | | |"
                    out.append(f"| {r}: {K.LABEL[r]} | {per} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | "
                               f"{rs(s['max_dd'])} |" + tail)
            ms = d[a].get("marked_share", {})
            out += ["", "Share of breaks each check marks (design): " +
                    ", ".join(f"{r} {pct(v)}" for r, v in ms.items() if K.RULES[r]["fam"] == fam) if fam not in "AC" else "", ""]
    out += ["## Full detail, final period (1 lot)", ""]
    for a in K.ARMS:
        out += [f"**{K.ARM_NAME[a]}**", "", HEAD, "|" + "---|" * 10, row("B0 today", f[a]["B0"]["all"])]
        out += [row(r, f[a][r]["all"]) for r in K.RULE_IDS] + [""]
    cov = {a: d[a].get("coverage") for a in K.ARMS}
    out += ["## Notes", "",
            f"- Option-feature coverage (design candidates): {json.dumps(cov)}. Unknown values never skip.",
            "- Not run: the unseen two years (Dec 2022 - Nov 2024), dropped on Boss's instruction (Nov 2024 onward only), "
            "before any number.",
            "- Delta beyond these two variants is R165's study (sibling); order flow from the phone's recordings is R164's.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(out))
    n = 0
    for a in K.ARMS:
        for r in K.RULE_IDS:
            g = dec[f"{a}:{r}"]
            fs, ds = f[a][r]["all"], d[a][r]
            line = lambda s: f"{s['trades']} trades, win {pct(s['win'])}, net {rs(s['net'])}, max DD {rs(s['max_dd'])}"  # noqa: E731
            e = dict(id=f"r166-{a.lower()}-{r.lower()}",
                     title=f"R166 {r} on {K.ARM_NAME[a]}: {K.LABEL[r]}",
                     family={"A": "option-structure", "B": "patterns-indicators", "C": "trade-management",
                             "D": "flow-oi", "E": "flow-oi", "F": "patterns-indicators"}[K.RULES[r]["fam"]],
                     date="2026-11-12", doc=DOC,
                     hypothesis=(f"Boss (12 Nov): analyse {K.FAMILY[K.RULES[r]['fam']]} on the Donchian arms. Rule: "
                                 f"{K.LABEL[r]}. Gates: better than today's (win +5 points with net not lower, or net "
                                 "higher with drawdown <= 1.1x) in walk-forward and final (delta: walk-forward); net > 0 in "
                                 f"every period; twin BH q <= 0.10 with m = {dec['_m']['total']}; >= 40 final trades."),
                     data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK) copied 12 Nov "
                                   "2026; R51 Amendment A clean stores; Nov 2024 onward only"),
                     period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                                 holdout="2026-04-01..2026-10-13 read once by R166 (read by many earlier studies)"),
                     instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                     features=["signal source: the index (unchanged arm entries); " +
                               ("option data used as context only" if K.RULES[r]["fam"] in "ADE" else "index data only")],
                     model_or_rule=f"{K.ARM_NAME[a]} with {r}: {K.LABEL[r]}, 1 lot",
                     settings="research/hunt/r166_donchian_six/r166cfg.py, r166lib.py; PREREG.md section 1",
                     prereg=PREREG, variants_tried=len(K.TESTS),
                     results=dict(summary=(f"Design: {line(ds['all'])} (today {rs(d[a]['B0']['all']['net'])}). Walk-forward: "
                                           f"{line(ds['wf'])} (today {pct(d[a]['B0']['wf']['win'])}, {rs(d[a]['B0']['wf']['net'])}). "
                                           f"Final: {line(fs)} (today {pct(f[a]['B0']['all']['win'])}, {rs(f[a]['B0']['all']['net'])}). "
                                           f"Twin p net {g['p_net']}, win {g['p_win']}; BH q {g['q_all']}. "
                                           + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                                  costs=("dated Zerodha charges, R17 fills (slippage from the 5-minute volume; stops -10 bps "
                                         "and 2x the half-spread)")),
                     holdout_read=True, verdict="paper-only" if g["passed"] else "rejected",
                     notes="Nothing changes in the app.")
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print("report.md", len(out), "lines;", n, "entries")


if __name__ == "__main__":
    main()
