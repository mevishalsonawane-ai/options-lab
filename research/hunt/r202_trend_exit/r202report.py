"""R202 / R203 report (PREREG.md section 7): results/design.md after the design run; results/report.md and the experiment
log entries after the holdout read and the gate. Plain-English notes, 20 Nov 2026.

    python3 research/hunt/r202_trend_exit/r202report.py design
    python3 research/hunt/r202_trend_exit/r202report.py final
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r202cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
GAP = ("Data: the dhan-data branch to 5 Oct 2026 (not R190's lake to 13 Nov); it reproduces R193's months within 2.5% "
       "(PREREG 1).")


def rs(x) -> str:
    if x is None:
        return "n/a"
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def chg(x) -> str:
    return f"{'+' if x >= 0 else '-'}Rs {abs(x):,.0f}"


def pct(x) -> str:
    return "n/a" if x is None else f"{x:.1f}%"


def base_table(r: dict, wins: tuple) -> list:
    out = ["| arm | window | trades | win % | net | average loss | max drawdown | worst month |", "|---|---|---|---|---|---|---|---|"]
    for a in K.ARMS:
        for w in wins:
            s = r[a]["B"]["windows"][w]
            out.append(f"| {K.ARM_NAME[a]} | {K.WINDOW_NAME[w]} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} | "
                       f"{rs(s['avg_loss'])} | {rs(s['max_dd'])} | {rs(s['worst_month']['net'])} ({s['worst_month']['month']}) |")
    return out


def effect_text(rule: str, e: dict) -> str:
    if rule in K.EXIT_RULES:
        return f"saved {rs(e['saved_on_losers'])} on B's losers, gave up {rs(-e['given_up_on_winners'])} on B's winners"
    return f"the removed trades had made {rs(e['removed_net'])} ({e['removed_losers']} of them losers)"


def window_rows(r: dict, a: str, rules: tuple, w: str, dec: dict | None) -> list:
    b = r[a]["B"]["windows"][w]
    out = [f"| rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | "
           f"worst month (vs B) | twin p{' | verdict' if dec else ''} |", "|---|---|---|---|---|---|---|---|---|" + ("---|" if dec else "")]
    out.append(f"| **B: today's rule** | - | {b['trades']:,} | {pct(b['win'])} | {rs(b['net'])} | {rs(b['avg_loss'])} | "
               f"{rs(b['max_dd'])} | {rs(b['worst_month']['net'])} | -{' | -' if dec else ''} |")
    for rule in rules:
        v = r[a][rule]
        s = v["windows"][w]
        tw = v["twin"].get(w)
        row = (f"| {rule}: {K.LABEL[rule]} | {v['changed'][w]:,} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} "
               f"({chg(s['net'] - b['net'])}) | {rs(s['avg_loss'])} | {rs(s['max_dd'])} ({chg(s['max_dd'] - b['max_dd'])}) | "
               f"{rs(s['worst_month']['net'])} ({chg(s['worst_month']['net'] - b['worst_month']['net'])}) | "
               f"{'n/a' if tw is None else format(tw['p'], '.3f')}")
        if dec:
            g = dec["tests"][f"{a}:{rule}"]
            row += " | " + ("**PASS**" if g["passes"] else "fail")
        out.append(row + " |")
    return out


def design_md() -> str:
    r = json.loads((K.RESULTS / "design.json").read_text())
    out = ["# R202 / R203 design results (21 Nov 2024 - 31 Mar 2026; the holdout is still locked)", "",
           GAP + " 1 lot, after charges and slippage. \"Twin p\" = the share of 2,000 random twins that did at least as "
           "well (small is good).", "", "## Baseline (what the arms run today)", ""]
    out += base_table(r, ("design",))
    for study, rules, name in (("R202", K.TREND, "trend-turn exits"), ("R203", K.LOSS, "other loss-cutting rules")):
        for a in K.ARMS:
            out += ["", f"## {study} {name}: {K.ARM_NAME[a]}, design", ""]
            out += window_rows(r, a, rules, "design", None)
            fb = r[a]["B"]["folds"]
            out += ["", "Net change against B in the four walk-forward quarters (Apr-Jun 2025, Jul-Sep 2025, Oct-Dec 2025, "
                    "Jan-Mar 2026): " + "; ".join(f"{rule} " + " / ".join(chg(f['net'] - g['net'])
                                                                           for f, g in zip(r[a][rule]["folds"], fb))
                                                  for rule in rules) + "."]
    return "\n".join(out) + "\n"


def fails_text(g: dict) -> str:
    return "passes every item" if g["passes"] else "; ".join(g["fails"])


def final_md() -> str:
    r = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n_pass = len(dec["passes"])
    out = ["# R202 / R203 results: sell when the trend turns, and other ways to lose less (R72, R51, Liquidity 15+5)", "",
           f"Pre-registered in `PREREG.md` before any number. {GAP} 1 lot, after dated charges and slippage. Holdout "
           f"read once ({(K.HOLDOUT_FLAG.read_text().strip() if K.HOLDOUT_FLAG.exists() else 'n/a')} UTC). "
           f"**{n_pass} of {len(K.TESTS)} tests pass the gate**"
           + (": " + ", ".join(dec["passes"]) if n_pass else "") + ".", "",
           "\"Twin p\" = the share of 2,000 random twins (the same trades cut at random minutes, or the same number of "
           "trades skipped at random) that did at least as well; small is good. \"vs B\" = against today's rule on the "
           "same days: for max drawdown a minus change is a smaller drawdown (good); for worst month a plus change is a "
           "less bad month (good).", "",
           "## Baseline B: what the arms run today (V5)", ""]
    out += base_table(r, ("design", "holdout", "total", "last_year"))
    if r["_meta"].get("design_mismatch"):
        out += ["", "Design rows that differ from `design.json` in the final run: " + ", ".join(r["_meta"]["design_mismatch"]) + "."]
    else:
        out += ["", "The final run's design rows equal `design.json` for every rule and arm."]
    for study, rules, name in (("R202", K.TREND, "trend-turn exits"), ("R203", K.LOSS, "other loss-cutting rules")):
        for a in K.ARMS:
            for w in ("holdout", "design", "last_year", "total"):
                out += ["", f"## {study} {name}: {K.ARM_NAME[a]}, {K.WINDOW_NAME[w]}", ""]
                out += window_rows(r, a, rules, w, dec if w == "holdout" else None)
            out += ["", f"Why each one fails or passes ({K.ARM_NAME[a]}):", ""]
            for rule in rules:
                g = dec["tests"][f"{a}:{rule}"]
                v = r[a][rule]
                out.append(f"- **{rule}** ({'PASS' if g['passes'] else 'fail'}): {fails_text(g)}. Holdout: "
                           f"{effect_text(rule, v['effect']['holdout'])}; BH q {g['q']:.3g} (m = {dec['m_total']:,}); "
                           f"better in {g['folds_better']} of 4 design quarters."
                           + (f" Walk-forward skips: {v['plan']}." if "plan" in v else ""))
    return "\n".join(out) + "\n"


def entries():
    import yaml
    r = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    for a, rule in K.TESTS:
        v, b, g = r[a][rule], r[a]["B"], dec["tests"][f"{a}:{rule}"]
        study = K.STUDY[rule].lower()

        def line(w):
            s, t = v["windows"][w], b["windows"][w]
            return (f"{w} {s['trades']} tr, {s['win']}% win, Rs {s['net']:,}, DD Rs {s['max_dd']:,}, worst month Rs "
                    f"{s['worst_month']['net']:,}, avg loss Rs {s['avg_loss']:,} (B: {t['trades']} tr, {t['win']}% win, Rs "
                    f"{t['net']:,}, DD Rs {t['max_dd']:,}, worst month Rs {t['worst_month']['net']:,}, avg loss Rs "
                    f"{t['avg_loss']:,}); {v['changed'][w]} trades changed / removed")

        e = dict(
            id=f"{study}-{a.lower()}-{rule.lower()}",
            title=f"{K.STUDY[rule]} {K.ARM_NAME[a]}: {K.LABEL[rule]}, on top of today's rule (V5)",
            family="trade-management" if rule in K.EXIT_RULES else "daily-filters",
            date="2026-11-20",
            doc="research/hunt/r202_trend_exit/results/report.md",
            hypothesis=(f"On {K.ARM_NAME[a]}, '{K.LABEL[rule]}' on top of today's rule (V5) is better than V5 alone in design "
                        "and holdout (higher net, or within 5% with drawdown and worst month 20% smaller), in 3 of 4 "
                        "walk-forward quarters, with no higher drawdown, better than random twins, after BH"),
            data_version=("the dhan-data branch of options-lab (Dhan v2, fetched 6 Oct 2026) in the lake's layout "
                          "(r202data.py); R51 clean stores 21 Nov 2024 - 5 Oct 2026 built here; reproduces R193's months "
                          "within 2.5%"),
            period=dict(design="2024-11-21..2026-03-31 (four walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-05, read once (results/HOLDOUT_READ); locked for these rules only"),
            instruments=list(K.LIQ_UNDS if a == "LIQ" else K.UNDS),
            features=["signal source: the index (the trade's own index; the five indices for breadth; NIFTY for the "
                      "market rule; the arms' own index signals)",
                      "buy the option; stops, breakevens and locks on the premium"],
            model_or_rule=f"{rule}: {K.LABEL[rule]} (r202lib.py; r186engine.price with today's breakeven and lock)",
            settings="research/hunt/r202_trend_exit/r202cfg.py; PREREG.md sections 3-4",
            prereg="research/hunt/r202_trend_exit/PREREG.md",
            variants_tried=1,
            results=dict(summary=(line("design") + "; " + line("holdout") + f"; holdout twin p {g['twin_p']:.4f}, BH q "
                                  f"{g['q']:.3g} (m = {dec['m_total']:,}). " + ("Passes the gate." if g["passes"] else
                                                                                 "Does not pass: " + "; ".join(g["fails"]))),
                         costs="dated Zerodha charges, R17 fills, 1 lot"),
            holdout_read=True,
            verdict="inconclusive" if g["passes"] else "rejected",
            notes=("Research only; nothing in the app changes. A pass is offered to Boss, who decides whether to build it "
                   "(paper, live or both)." if g["passes"] else "Research only; nothing in the app changes. Stays a note "
                   "unless Boss makes it an exception."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))


if __name__ == "__main__":
    what = (sys.argv[1:] or [""])[0]
    if what == "design":
        (K.RESULTS / "design.md").write_text(design_md())
        print("design.md written")
    elif what == "final":
        (K.RESULTS / "report.md").write_text(final_md())
        entries()
        print("report.md and entries written")
    else:
        sys.exit(__doc__)
