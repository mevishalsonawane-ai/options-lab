"""R204 / R205 report (PREREG.md section 5): results/report.md and the experiment log entries, from results/final.json,
decision.json and funds.json. Plain-English notes, 20 Nov 2026.

    python3 research/hunt/r204_be_time_funds/r204report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r204cfg as K  # noqa: E402

K2 = K.K2
ENTRIES = K2.ROOT / "research" / "experiments" / "entries"
SECOND = ("The holdout is a SECOND LOOK: these ideas came from a diagnosis of the whole span (PREREG section 0), so a rule "
          "that clears the gate is not \"passed\" until new days agree.")
DATA = "Data: the dhan-data branch to 5 Oct 2026 (not R190's lake to 13 Nov). 1 lot, after dated charges and slippage."


def rs(x) -> str:
    if x is None:
        return "n/a"
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def chg(x) -> str:
    return f"{'+' if x >= 0 else '-'}Rs {abs(x):,.0f}"


def pct(x) -> str:
    return "n/a" if x is None else f"{x:.1f}%"


def rule_rows(r: dict, dec: dict, w: str) -> list:
    out = ["| arm | rule | trades changed / removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | "
           "worst month (vs B) | twin p | gate |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for a in K.ARMS:
        b = r[a]["B"]["windows"][w]
        out.append(f"| {K2.ARM_NAME[a]} | **B: today's rule** | - | {b['trades']:,} | {pct(b['win'])} | {rs(b['net'])} | "
                   f"{rs(b['avg_loss'])} | {rs(b['max_dd'])} | {rs(b['worst_month']['net'])} | - | - |")
        for rule in K.RULES:
            if (a, rule) not in K.TESTS:
                continue
            v = r[a][rule]
            s = v["windows"][w]
            tw = v["twin"].get(w)
            g = dec["tests"][f"{a}:{rule}"]
            out.append(f"| {K2.ARM_NAME[a]} | {rule}: {K.LABEL[rule]} | {v['changed'][w]:,} | {s['trades']:,} | {pct(s['win'])} | "
                       f"{rs(s['net'])} ({chg(s['net'] - b['net'])}) | {rs(s['avg_loss'])} | {rs(s['max_dd'])} "
                       f"({chg(s['max_dd'] - b['max_dd'])}) | {rs(s['worst_month']['net'])} "
                       f"({chg(s['worst_month']['net'] - b['worst_month']['net'])}) | "
                       f"{'n/a' if tw is None else format(tw['p'], '.3f')} | "
                       f"{('clears (second look)' if g['passes'] else 'fail') if w == 'holdout' else '-'} |")
    return out


def effect_text(rule: str, e: dict) -> str:
    if rule in K.EXIT_RULES:
        return (f"saved {rs(e['saved_on_losers'])} on B's losers, gave up {rs(-e['given_up_on_winners'])} on B's winners "
                f"({e['winners_turned_losers']} winners became losers)")
    return f"the removed trades had made {rs(e['removed_net'])} ({e['removed_losers']} of them losers)"


def funds_rows(f: dict, name: str, w: str) -> list:
    out = ["| account | trades taken | not taken (their net) | win % | net | net as % of account | max drawdown | "
           "drawdown as % of account | worst month | worst day | most money in use |", "|---|---|---|---|---|---|---|---|---|---|---|"]
    for acc, row in f[name].items():
        x = row[w]
        label = acc if acc == "no limit" else f"Rs {int(acc):,}"
        out.append(f"| {label} | {x['taken']:,} | {x['not_taken']:,} ({rs(x['not_taken_net'])}) | {pct(x['win'])} | {rs(x['net'])} | "
                   f"{'n/a' if x['net_pct_of_account'] is None else str(x['net_pct_of_account']) + '%'} | {rs(x['max_dd'])} | "
                   f"{'n/a' if x['max_dd_pct_of_account'] is None else str(x['max_dd_pct_of_account']) + '%'} | "
                   f"{rs(x['worst_month']['net'])} ({x['worst_month']['month']}) | {rs(x['worst_day'])} | {rs(row['most_money_in_use'])} |")
    return out


def arm_split(f: dict, name: str, w: str) -> list:
    arms = list(next(iter(f[name].values()))[w]["by_arm"])
    out = ["| account | " + " | ".join(f"{K2.ARM_NAME[a]}: taken / not taken, net" for a in arms) + " |",
           "|---|" + "---|" * len(arms)]
    for acc, row in f[name].items():
        label = acc if acc == "no limit" else f"Rs {int(acc):,}"
        out.append(f"| {label} | " + " | ".join(f"{row[w]['by_arm'][a]['taken']:,} / {row[w]['by_arm'][a]['not_taken']:,}, "
                                                 f"{rs(row[w]['by_arm'][a]['net'])}" for a in arms) + " |")
    return out


def report() -> str:
    r = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    f = json.loads((K.RESULTS / "funds.json").read_text())
    n = len(dec["clears"])
    out = ["# R204 / R205 results: earlier breakeven, earlier time stop, Liquidity without the MIDCPNIFTY 5-minute book, account sizes",
           "", f"Pre-registered in `PREREG.md` before any number. {DATA} {SECOND}", "",
           f"**{n} of {len(K.TESTS)} tests clear the gate on the second look**" + (": " + ", ".join(dec["clears"]) if n else "") + ".",
           "", "\"Twin p\" = the share of 2,000 random twins that did at least as well (small is good). For max drawdown a minus "
           "change is good; for worst month a plus change is good.",
           "", ("The final run's design rows equal `design.json`." if not r["_meta"].get("design_mismatch") else
                "Design rows that differ from `design.json`: " + ", ".join(r["_meta"]["design_mismatch"]) + ".")]
    for w in ("holdout", "design", "last_year", "total"):
        out += ["", f"## R204, {K2.WINDOW_NAME[w]}" + (" - a second look" if w == "holdout" else ""), ""]
        out += rule_rows(r, dec, w)
    out += ["", "## R204: why each one fails or clears", ""]
    for a, rule in K.TESTS:
        g, v = dec["tests"][f"{a}:{rule}"], r[a][rule]
        out.append(f"- **{K2.ARM_NAME[a]}, {rule}** ({'clears on a second look' if g['passes'] else 'fail'}): "
                   f"{'every item holds' if g['passes'] else '; '.join(g['fails'])}. Design: {effect_text(rule, v['effect']['design'])}. "
                   f"Holdout: {effect_text(rule, v['effect']['holdout'])}. BH q {g['q']:.3g} (m = {dec['m_total']:,}; within these "
                   f"10: {g['q_family']:.3g}); better in {g['folds_better']} of 4 design quarters; design twin p {g['design_twin_p']:.3f}.")
    out += ["", "## R205: what an account of a given size can take (descriptive)", "",
            "One shared pot of money, 1 lot a trade, every day starting with the account size; a trade the free cash cannot pay "
            "for is not taken. Same-minute order: R72, Liquidity, R51. No re-walk (an approximation, PREREG 4)."]
    for name in K.SETS:
        for w in ("total", "last_year", "holdout"):
            out += ["", f"### {name}, {K2.WINDOW_NAME[w]}", ""] + funds_rows(f, name, w)
        out += ["", f"### {name}: by arm, {K2.WINDOW_NAME['total']}", ""] + arm_split(f, name, "total")
    lq = f["liquidity_from_april_2026"]
    out += ["", "## Liquidity from 1 April 2026 (baseline, descriptive)", "",
            f"All books: {lq['all']['trades']} trades, {lq['all']['win']}% wins, {rs(lq['all']['net'])}; max drawdown "
            f"{rs(lq['stats']['max_dd'])}; worst month {rs(lq['stats']['worst_month']['net'])} ({lq['stats']['worst_month']['month']}).",
            "", "| month | trades | win % | net |", "|---|---|---|---|"]
    out += [f"| {k} | {v['trades']} | {v['win']}% | {rs(v['net'])} |" for k, v in lq["by_month"].items()]
    out += ["", "| book | trades | win % | net |", "|---|---|---|---|"]
    out += [f"| {k} | {v['trades']} | {v['win']}% | {rs(v['net'])} |" for k, v in lq["by_book"].items()]
    return "\n".join(out) + "\n"


def entries():
    import yaml
    r = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    f = json.loads((K.RESULTS / "funds.json").read_text())
    for a, rule in K.TESTS:
        v, b, g = r[a][rule], r[a]["B"], dec["tests"][f"{a}:{rule}"]

        def line(w):
            s, t = v["windows"][w], b["windows"][w]
            return (f"{w} {s['trades']} tr, {s['win']}% win, Rs {s['net']:,}, DD Rs {s['max_dd']:,}, worst month Rs "
                    f"{s['worst_month']['net']:,}, avg loss Rs {s['avg_loss']:,} (B: {t['trades']} tr, {t['win']}% win, Rs "
                    f"{t['net']:,}, DD Rs {t['max_dd']:,}, worst month Rs {t['worst_month']['net']:,}, avg loss Rs "
                    f"{t['avg_loss']:,}); {v['changed'][w]} trades changed / removed")

        e = dict(
            id=f"r204-{a.lower()}-{rule.lower()}",
            title=f"R204 {K2.ARM_NAME[a]}: {K.LABEL[rule]}, on top of today's rule (V5)",
            family="trade-management" if rule in K.EXIT_RULES else "liquidity",
            date="2026-11-20",
            doc="research/hunt/r204_be_time_funds/results/report.md",
            hypothesis=(f"On {K2.ARM_NAME[a]}, '{K.LABEL[rule]}' on top of today's rule (V5) is better than V5 alone in design and "
                        "holdout (higher net, or within 5% with drawdown and worst month 20% smaller), in 3 of 4 walk-forward "
                        "quarters, with no higher drawdown, better than random twins, after BH. The idea came from a diagnosis of "
                        "the whole span, so the holdout is a second look"),
            data_version=("the dhan-data branch of options-lab (Dhan v2, fetched 6 Oct 2026) in the lake's layout "
                          "(r202data.py); R51 clean stores 21 Nov 2024 - 5 Oct 2026"),
            period=dict(design="2024-11-21..2026-03-31 (four walk-forward quarters Apr 2025 - Mar 2026)",
                        holdout="2026-04-01..2026-10-05, a SECOND LOOK (read by R202 / R203 and by the diagnosis that "
                                "suggested this rule); results/HOLDOUT_READ"),
            instruments=list(K2.LIQ_UNDS if a == "LIQ" else K2.UNDS),
            features=["signal source: the index (the arms' own signals, unchanged)",
                      "buy the option; the new rule is an exit on the held option's premium (or a book switch)"],
            model_or_rule=f"{rule}: {K.LABEL[rule]} (r204lib.py; r186engine.price with today's breakeven and lock)",
            settings="research/hunt/r204_be_time_funds/r204cfg.py; PREREG.md section 2",
            prereg="research/hunt/r204_be_time_funds/PREREG.md",
            variants_tried=1,
            results=dict(summary=(line("design") + "; " + line("holdout") + f"; holdout twin p {g['twin_p']:.4f}, BH q "
                                  f"{g['q']:.3g} (m = {dec['m_total']:,}). " + ("Clears the gate on a second look (not a pass)."
                                                                                 if g["passes"] else "Does not pass: " + "; ".join(g["fails"]))),
                         costs="dated Zerodha charges, R17 fills, 1 lot"),
            holdout_read=True,
            verdict="inconclusive" if g["passes"] else "rejected",
            notes="Research only; nothing in the app changes. " + ("Needs new days before it counts as passed; offered to Boss for "
                                                                    "shadow or their own decision." if g["passes"] else
                                                                    "Stays a note unless Boss makes it an exception."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    t = f["all three arms"]
    summ = "; ".join(f"Rs {int(k):,}: {v['total']['taken']} taken, {v['total']['not_taken']} not taken, net Rs {v['total']['net']:,}, "
                     f"DD Rs {v['total']['max_dd']:,}" for k, v in t.items() if k != "no limit")
    e = dict(
        id="r205-account-sizes", title="R205: R72, R51 and Liquidity 15+5 replayed through one pot of money (account sizes)",
        family="sizing-execution", date="2026-11-20", doc="research/hunt/r204_be_time_funds/results/report.md",
        hypothesis="none (descriptive: which trades an account of a given size could have paid for, 1 lot, today's rules)",
        data_version="the baseline trades of r204study.py final (dhan-data build, 21 Nov 2024 - 5 Oct 2026)",
        period=dict(design="2024-11-21..2026-10-05 (one span; not a test)"),
        instruments=list(K2.UNDS),
        features=["signal source: the index (the arms' own signals); options bought; 1 lot; one shared pot of money"],
        model_or_rule="r204lib.funded_replay: free cash resets daily; exits before entries; same-minute order R72, Liquidity, R51",
        settings="research/hunt/r204_be_time_funds/r204cfg.py; PREREG.md section 4", prereg="research/hunt/r204_be_time_funds/PREREG.md",
        variants_tried=None,
        results=dict(summary="All three arms, total span: " + summ + f"; no limit: net Rs {t['no limit']['total']['net']:,}, "
                             f"DD Rs {t['no limit']['total']['max_dd']:,}.", costs="dated Zerodha charges, R17 fills, 1 lot"),
        holdout_read=None, verdict="not-a-test",
        notes="Research only. No re-walk (a refused trade does not free the arm's side), lots setting not modelled.")
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(report())
    entries()
    print("report.md and entries written")
