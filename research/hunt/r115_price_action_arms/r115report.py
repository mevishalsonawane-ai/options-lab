"""R115 report tables and experiment entries from results/{design,final,decision}.json (no lake needed).

    python research/hunt/r115_price_action_arms/r115report.py tables     # markdown tables to stdout
    python research/hunt/r115_price_action_arms/r115report.py entries    # research/experiments/entries/r115-*.yaml
"""
from __future__ import annotations

import json
import sys

import r115cfg as K


def load():
    d = json.loads((K.RESULTS / "design.json").read_text())["arms"]
    f = json.loads((K.RESULTS / "final.json").read_text())["arms"]
    g = json.loads((K.RESULTS / "decision.json").read_text())
    return d, f, g


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(x)
    s = f"{abs(x):,}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def cell(s: dict) -> str:
    return f"{s['trades']:,} / {pct(s['win'])} / {rs(s['net'])}"


def rows(test: str, d, f) -> dict:
    arm = (K.TESTS.get(test) or K.REPORTED[test])[0]
    return {"design": d[arm][test]["design"], "wf": d[arm][test]["wf"], "final": f[arm][test]["final"]}, \
           {"design": d[arm]["today"]["design"], "wf": d[arm]["today"]["wf"], "final": f[arm]["today"]["final"]}


def name(test: str) -> str:
    arm, kind, rule = (K.TESTS.get(test) or K.REPORTED[test])
    lab = K.LABEL.get(rule, "strong break bar, the 09:20 break exempt")
    return f"{test} {K.ARM_NAME[arm]}: {lab}"


def tables() -> str:
    d, f, g = load()
    out = ["### Main table: each rule vs today's rules (trades / win rate / net, 1 lot, after charges)", "",
           "| rule | design | walk-forward | final | final worst day | final max DD | twin p | q (all 9.3M) | "
           "q (R64+ count) | verdict |", "|---|---|---|---|---:|---:|---:|---:|---:|---|"]
    for t in K.TESTS:
        r, b = rows(t, d, f)
        arm = K.TESTS[t][0]
        tw = f[arm][t].get("twin", {})
        dec = g[t]
        out.append(f"| {name(t)} | {cell(r['design'])} | {cell(r['wf'])} | {cell(r['final'])} | "
                   f"{rs(r['final']['worst_day'])} | {rs(r['final']['max_dd'])} | {tw.get('p', 1):.3g} | "
                   f"{dec['q_all']:.3g} | {dec['q_r64']:.3g} | {'PASS' if dec['passed'] else 'fail'} |")
    out += ["", "Today's rules, for comparison:", "", "| arm | design | walk-forward | final | final worst day | "
            "final max DD |", "|---|---|---|---|---:|---:|"]
    for arm in K.ARMS:
        b = {"design": d[arm]["today"]["design"], "wf": d[arm]["today"]["wf"], "final": f[arm]["today"]["final"]}
        out.append(f"| {K.ARM_NAME[arm]} | {cell(b['design'])} | {cell(b['wf'])} | {cell(b['final'])} | "
                   f"{rs(b['final']['worst_day'])} | {rs(b['final']['max_dd'])} |")
    out += ["", "### The 09:15-09:30 entries under each rule (trades / win / net) vs today's", "",
            "| rule | design: rule | design: today's | final: rule | final: today's |", "|---|---|---|---|---|"]
    for t in list(K.TESTS) + list(K.REPORTED):
        r, b = rows(t, d, f)
        out.append(f"| {t} | {cell(r['design']['open_0915_0930'])} | {cell(b['design']['open_0915_0930'])} | "
                   f"{cell(r['final']['open_0915_0930'])} | {cell(b['final']['open_0915_0930'])} |")
    out += ["", "### What each filter's dropped signals did under today's rules", "",
            "| rule | design | final |", "|---|---|---|"]
    for t in list(K.TESTS) + list(K.REPORTED):
        arm = (K.TESTS.get(t) or K.REPORTED[t])[0]
        if "dropped_trades" in d[arm][t]:
            out.append(f"| {t} | {cell(d[arm][t]['dropped_trades']['design'])} | "
                       f"{cell(f[arm][t]['dropped_trades']['final'])} |")
    out += ["", "### Reported only (not gated): the strong-bar rule with the 09:20 break exempt", "",
            "| rule | design | walk-forward | final |", "|---|---|---|---|"]
    for t in K.REPORTED:
        r, b = rows(t, d, f)
        out.append(f"| {name(t)} | {cell(r['design'])} | {cell(r['wf'])} | {cell(r['final'])} |")
    out += ["", "### Gate failures", ""]
    for t in K.TESTS:
        out.append(f"- **{t}**: " + ("passed" if g[t]["passed"] else "; ".join(g[t]["fails"])))
    return "\n".join(out)


def entries():
    import yaml
    d, f, g = load()
    root = K.HERE.parents[2]
    for t in K.TESTS:
        arm, kind, rule = K.TESTS[t]
        r, b = rows(t, d, f)
        tw = f[arm][t]["twin"]
        summ = (f"{K.ARM_NAME[arm]}, {K.LABEL[rule]} vs today's rules. Design {r['design']['trades']} trades "
                f"Rs {r['design']['net']:,} vs {b['design']['trades']} / Rs {b['design']['net']:,}; walk-forward "
                f"{r['wf']['trades']} / Rs {r['wf']['net']:,} vs {b['wf']['trades']} / Rs {b['wf']['net']:,}; final "
                f"{r['final']['trades']} / Rs {r['final']['net']:,} vs {b['final']['trades']} / Rs {b['final']['net']:,} "
                f"(worst day Rs {r['final']['worst_day']:,} vs {b['final']['worst_day']:,}, max DD Rs "
                f"{r['final']['max_dd']:,} vs {b['final']['max_dd']:,}). Twin p {tw['p']:.3g} (Monte-Carlo "
                f"{tw['p_mc']:.3g}, mean Rs {tw['twin_mean']:,}); BH q {g[t]['q_all']:.3g} over {K.M_ALL:,} "
                f"(q {g[t]['q_r64']:.3g} over the R64+ count {K.M_R64:,}). "
                + ("Passed." if g[t]["passed"] else "Fails: " + "; ".join(g[t]["fails"]) + "."))
        e = dict(
            id=f"r115-{t.lower()}", title=f"R115 {t}: {K.ARM_NAME[arm]}, {K.LABEL[rule]}",
            family="trade-management" if kind == "exit" else "patterns-indicators", date="2026-10-27",
            doc="research/HUNT_R115_PRICE_ACTION_ARMS.md",
            hypothesis=f"{K.LABEL[rule]} on the arm's own index bars improves {K.ARM_NAME[arm]} (net up in design, "
                       "walk-forward and final; worst day and drawdown not worse; beats its random twin at BH q <= 0.10 "
                       f"over {K.M_ALL:,} tests).",
            data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) copied "
                         "27 Oct; R51 Amendment A clean stores to 13 Oct",
            period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-13 read once by R115 (already read by R51-R114, so weak evidence)"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the index's own 5-minute bars (Liquidity: its 15 / 5-minute book bar; OOV: the "
                      "09:15-09:19 index bar); the 1-ITM weekly option is bought, stops / locks on its premium"],
            model_or_rule=f"{t}: {K.LABEL[rule]}", settings="research/hunt/r115_price_action_arms/r115cfg.py TESTS",
            prereg="research/hunt/r115_price_action_arms/PREREG.md", variants_tried=1,
            results=dict(summary=summ, costs="dated Zerodha charges, R17 fills, 1 lot"), holdout_read=True,
            verdict="paper-only" if g[t]["passed"] else "rejected",
            notes="Signal on the underlying. Nothing changes live." + ("" if g[t]["passed"] else
                                                                      " Nothing changes on paper either."))
        (root / "research" / "experiments" / "entries" / f"r115-{t.lower()}.yaml").write_text(
            yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))


if __name__ == "__main__":
    cmd = sys.argv[1] if len(sys.argv) > 1 else ""
    if cmd == "tables":
        print(tables())
    elif cmd == "entries":
        entries()
    else:
        raise SystemExit(__doc__)
