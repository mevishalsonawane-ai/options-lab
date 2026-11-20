"""R196 report (PREREG.md section 8): results/report.md and the experiment log entries, from results/repro.json, w1.json,
w2.json and decision.json.

    python3 research/hunt/r196_sensex_arms/r196report.py && python3 research/experiments/validate.py

Plain-English notes, 20 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r196cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r196_sensex_arms/results/report.md"
PREREG = "research/hunt/r196_sensex_arms/PREREG.md"
FAMILY = {"R72-SX": "patterns-indicators", "R51-SX": "patterns-indicators", "LIQ-SX": "liquidity"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{x:.1f}%"


def row(name: str, s: dict) -> str:
    wm = s["worst_month"]
    return (f"| {name} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
            f"{rs(s['max_dd'])} | {wm['month'] or '-'} {rs(wm['net'])} | {rs(s['net_wo_top5'])} | "
            f"{'ok' if s['account_stop_ok'] else 'BREACH'} |")


HEAD = ("| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |\n"
        "|---|---:|---:|---:|---:|---:|---:|---:|---|")


def load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def build() -> str:
    rep, w2, dec = load("repro.json"), load("w2.json"), load("decision.json")
    out = ["# R196: SENSEX in R72, R51 and Liquidity 15+5 with today's exits", "",
           f"Pre-registration: `{PREREG}`. Span 1 Nov 2024 - 13 Nov 2026, 1 lot, dated Zerodha charges (BSE rates for "
           "SENSEX), R17 fills. Signal source of every rule: the index; options are only bought.", ""]
    out += ["## Reproduction (R193's pooled totals, all indices)", "", "| line | trades | net | R193 | gap |", "|---|---:|---:|---:|---:|"]
    for k, r in rep.items():
        if k.startswith("_") or "unchanged" in k:
            continue
        out.append(f"| {k} | {r['trades']:,} | {rs(r['net'])} | {r['published'][0]:,} / {rs(r['published'][1])} | "
                   f"{r['gap_trades']:+,} / {rs(r['gap_net'])} |")
    for k, r in rep.items():
        if "unchanged" in k:
            out.append(f"\n{k}: {'yes' if r['same'] else 'NO'} ({r['with_sx']:,} vs {r['without_sx']:,} trades)")
    out.append("")
    for t in K.TESTS:
        r, g = w2[t], dec["tests"][t]
        out += [f"## {t}: {K.LABEL[t]}", "", f"**Verdict: {'PASSED' if g['passed'] else 'FAILED'}** "
                f"(twin p {g['twin_p']:.4f}, normal p {g['p_normal']:.3g}, BH q {g['q']:.3g}, m = {dec['m_total']:,}; "
                f"walk-forward {g['wf_positive']} of 6 W1 quarters positive).", ""]
        if g["fails"]:
            out += ["Failed: " + "; ".join(g["fails"]) + ".", ""]
        out += ["Live rule (V5" + (" + R151 picker" if K.TEST_RULE[t][2] else "") + "), SENSEX trades only:", "", HEAD]
        out += [row(K.WINDOW_NAME[w], r["windows"][w]) for w in ("W1", "W2", "total")]
        out += ["", "Own exits (A: no picker, no breakeven, no lock), SENSEX trades only (descriptive):", "", HEAD]
        out += [row(K.WINDOW_NAME[w], r["own_exits_A"][w]) for w in ("W1", "W2", "total")]
        out += ["", "Quarters: " + "; ".join(f"{q['quarter']} {q['trades']} tr {rs(q['net'])}"
                                            for w in ("W1", "W2") for q in r["quarters"][w]), ""]
        out += [f"Twins: {r['twin']['plan']} (day, side) groups, 2,000 twins, mean {rs(r['twin']['mean'])}, sd "
                f"{rs(r['twin']['sd'])}, actual {rs(r['twin']['actual'])}.", ""]
        out += [f"Picker removed (SENSEX): " + ", ".join(f"{w} {n}" for w, n in r["picker_removed"].items())
                + f". SENSEX expiry days (never traded): " + ", ".join(f"{w} {n}" for w, n in r["expiry_days_skipped"].items())
                + ".", ""]
        out += ["Exits (total): " + ", ".join(f"{k} {v}" for k, v in r["exits"]["total"].items()), ""]
        pi = r["per_index"]["total"]
        out += ["Per index, live rule, total (descriptive): " + "; ".join(f"{u} {v['trades']} tr {rs(v['net'])}"
                                                                         for u, v in pi.items()), ""]
        ww = r["arm_with_without"]["total"]
        out += [f"Arm with SENSEX {ww['with_sensex']['trades']:,} tr {rs(ww['with_sensex']['net'])}; without "
                f"{ww['without_sensex']['trades']:,} tr {rs(ww['without_sensex']['net'])}.", ""]
    return "\n".join(out) + "\n"


def entries():
    w2, dec = load("w2.json"), load("decision.json")
    import yaml
    for t in K.TESTS:
        r, g = w2[t], dec["tests"][t]
        s = r["windows"]
        summ = "; ".join(f"{w} {s[w]['trades']} tr, {pct(s[w]['win'])} win, {rs(s[w]['net'])}, DD {rs(s[w]['max_dd'])}, "
                         f"worst month {rs(s[w]['worst_month']['net'])}" for w in ("W1", "W2", "total"))
        summ += (f"; twin p {g['twin_p']:.4f}, BH q {g['q']:.3g} (m = {dec['m_total']:,}); walk-forward "
                 f"{g['wf_positive']}/6 W1 quarters positive. " + ("Passed." if g["passed"] else
                                                                     "Failed: " + "; ".join(g["fails"])))
        e = dict(id=f"r196-{t.lower()}", title=f"R196 SENSEX: {K.LABEL[t]}", family=FAMILY[t], date="2026-11-20", doc=DOC,
                 hypothesis=f"{K.LABEL[t]} makes money on its own: net > 0 in W1 and W2, >= 40 trades each, 4 of 6 W1 "
                            "quarters positive, positive without its 5 best trades, beats random-entry twins, after BH",
                 data_version="Drive lake copy of 20 Nov 2026 (index_1m and options_rolling_1m WEEK 2024 - 13 Nov 2026, "
                              "with the 20 Oct - 13 Nov fill); R51 clean stores built by R196 from 1 Jan 2024",
                 period=dict(design="W1 2024-11-01..2026-03-31 (walk-forward by quarter; nothing fitted)",
                             holdout="W2 2026-04-01..2026-11-13, read once (results/W2_READ); seen before in pooled "
                                     "totals (R193) and, for Liquidity, R52's SENSEX hold period"),
                 instruments=["SENSEX"],
                 features=["signal source: the index (SENSEX's own 5-minute / 15-minute bars; R72 counts 4 of 5 "
                           "indices)", "buy the BFO option; stops, breakevens and locks on the premium"],
                 model_or_rule=K.LABEL[t] + " (r186engine.price; r152picker where it applies)",
                 settings="research/hunt/r196_sensex_arms/r196cfg.py; PREREG.md section 5", prereg=PREREG,
                 variants_tried=1,
                 results=dict(summary=summ, costs="dated Zerodha charges (BSE rates), R17 fills, 1 lot"),
                 holdout_read=True, verdict="passed" if g["passed"] else "rejected",
                 notes="Research only; nothing in the app changes until Boss chooses.")
        (ENTRIES / f"r196-{t.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(build())
    entries()
    print("written", K.RESULTS / "report.md")
