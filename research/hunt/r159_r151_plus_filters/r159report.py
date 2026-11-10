"""R159 report and experiment entries from results/decision.json.

    python research/hunt/r159_r151_plus_filters/r159report.py
"""
from __future__ import annotations

import json

import yaml

import r159study as S
from r154report import rs


def pct(x, d=1):
    return "-" if x is None else f"{100 * x:.{d}f}%"


def cell(s: dict) -> str:
    if not s.get("trades"):
        return "0 trades"
    return f"{s['trades']:,} / {pct(s['win'])} / {rs(s['net'])} / {rs(s['per_trade'])} / {rs(-s['max_dd'])}"


def short(s: dict) -> str:
    return f"{s['trades']:,} trades, win {pct(s['win'])}, {rs(s['net'])} ({rs(s['per_trade'])} a trade)"


def main():
    d = json.loads((S.RESULTS / "decision.json").read_text())
    b2, b3, r2, r3 = d["_base"]["P2"], d["_base"]["P3"], d["_r72"]["P2"], d["_r72"]["P3"]
    passed = [t for t in S.TESTS if d[t]["passed"]]
    both = [t for t in S.TESTS if S.better(d[t]["P2"], b2) and S.better(d[t]["P3"], b3)]
    more_net3 = [t for t in S.TESTS if d[t]["P3"]["net"] > b3["net"]]
    more_win3 = [t for t in S.TESTS if d[t]["P3"]["trades"] >= S.MIN_FINAL and d[t]["P3"]["win"] > b3["win"]]
    same = [t for t in S.TESTS if d[t]["P3"]["trades"] == b3["trades"] and d[t]["P2"]["trades"] == b2["trades"]]

    def one(t):
        return f"{t} ({S.NAME[t].split(':')[0]}): {short(d[t]['P3'])}"
    L = ["# R159: support / resistance lines, W50, Supertrend and more on top of R151's Breadth Donchian picker", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'passes: ' + ', '.join(passed) if passed else 'nothing added on top of R151 passes'}.** "
         f"{len(passed)} of {len(S.TESTS)} additions passed the gates.",
         f"- **What the app runs now (Breadth Donchian + R151's picker), Apr - Oct 2026:** {short(b3)}. Plain Breadth Donchian: "
         f"{short(r3)}.",
         f"- **Supertrend on top:** {short(d['F9']['P3'])}. A little better on both counts in both periods "
         f"(Apr 2025 - Mar 2026: {short(d['F9']['P2'])} against {short(b2)}), but it removes only "
         f"{b3['trades'] - d['F9']['P3']['trades']} trades and the gain is within luck (random-skip p {d['F9']['twin_p']}, "
         f"q {d['F9']['q']}); its drawdown was larger ({rs(-d['F9']['P3']['max_dd'])} against {rs(-b3['max_dd'])}).",
         f"- **W50 on top:** {short(d['F3']['P3'])}: fewer trades, lower win rate, far less profit.",
         f"- **All support / resistance lines at once (ALLSR):** {short(d['F6']['P3'])}. **All lines + Supertrend (C2):** "
         f"{short(d['C2']['P3'])}: the win rate reaches 50%, but three quarters of the trades and of the profit are gone.",
         f"- **Many things together (C3: all lines + Supertrend + EMA20 + OI):** {short(d['C3']['P3'])}.",
         f"- **OI filters hurt:** {one('F11')}; {one('F12')}.",
         f"- Above the base on BOTH win rate and profit in both periods: {', '.join(both) or 'none'}. More profit in the "
         f"final period: {', '.join(more_net3) or 'none'}. A higher win rate in the final period: {', '.join(more_win3) or 'none'}."
         + (f" {', '.join(same)} changed nothing (the picker's trades already satisfy it)." if same else ""),
         "- **Why the lines do not help here:** R151's picker already reads the distance to the nearest level ahead and "
         "behind, the level's touches, whether the break crossed a level and the floor pivots. Adding the same information "
         "again as a hard skip only removes trades, including the big winners Breadth Donchian lives on.",
         "- Nothing changes in the app. Every addition is logged in `research/experiments/entries/r159-*.yaml`.", "",
         "Signal source: the index (R72's 4-of-5 Donchian break; the picker's and the filters' index readings; OI as context); "
         "R72's own exits. **Apr - Oct 2026 was read many times for this arm (R72 - R158): weak evidence. In Apr 2025 - Mar "
         "2026 the picker itself is in-sample (it was trained on those trades), so that period only shows what a filter adds "
         "to or removes from the picker's trades.**", "",
         "## How it was tested", "",
         "Pre-registered in `PREREG.md` before any number. Base = R151's own walk with the app's exported picker numbers "
         f"(threshold 0.4656), reproduced exactly on the final period ({b3['trades']} trades, {rs(b3['net'])}); plain R72 too "
         f"({r3['trades']:,}, {rs(r3['net'])}). Each addition is R155's filter read at the minute before the entry (a "
         "filter that cannot be read fails) and removes the break before the walk, as the picker's own skips. Gates: a "
         "higher win rate AND a higher net than the base in P2 and P3; net > 0; 2,000 random subsets of the base's final "
         f"trades of the same size, BH q <= 0.10 (m = {d['_m']['total']:,}); >= 40 final trades. 1 lot, 5 indices. (The first "
         "final run stopped at the repo's final-period guard before reading anything; it was re-run once after the guard was "
         "opened in the right order.)", "",
         "## Every addition (trades / win / net / a trade / max drawdown)", "",
         "| added on top of R72 + R151 | P2 (Apr 2025 - Mar 2026) | P3 final (Apr - 13 Oct 2026) | random skips' mean net | p | q | the same filter on plain R72, P3 | verdict |",
         "|---|---|---|---|---|---|---|---|",
         f"| **nothing (the app today)** | {cell(b2)} | {cell(b3)} | | | | {cell(r3)} | |"]
    for t in S.TESTS:
        x = d[t]
        v = "PASS" if x["passed"] else "fail: " + "; ".join(x["fails"])
        L.append(f"| {t} {S.NAME[t]} | {cell(x['P2'])} | {cell(x['P3'])} | {rs(x['twin_mean'])} | {x['twin_p']} | {x['q']} | "
                 f"{cell(x['plain_P3'])} | {v} |")
    (S.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    ent = S.ROOT / "research" / "experiments" / "entries"
    for t in S.TESTS:
        x = d[t]
        e = dict(
            id=f"r159-{t.lower()}", title=f"R159 {t} on top of R72 + R151's picker: {S.NAME[t]}",
            family="daily-filters", date="2026-11-10", doc="research/hunt/r159_r151_plus_filters/results/report.md",
            hypothesis=("Boss (10 Nov): add support / resistance lines, W50, Supertrend and more on R151. Keeping an R72 + "
                        f"R151 trade only when {S.NAME[t]} also holds gives a higher win rate AND net than the base in P2 and "
                        f"P3, with random-skip BH q <= 0.10 (m {d['_m']['total']}) and >= 40 final trades."),
            data_version="Drive lake copied 9 Nov 2026; R51 Amendment A clean stores; R151's exported picker model; R155 OI store",
            period=dict(design="2025-04-01..2026-03-31 (the picker is in-sample there; the filters are unfitted)",
                        holdout="2026-04-01..2026-10-13 read once by R159 (read many times for R72)"),
            instruments=list(S.K5.UNDS),
            features=["signal source: index 5-min bars of all five indices (Donchian 20, 4 of 5) + R151 picker features",
                      f"extra filter: {S.NAME[t]} (R155's definition; OI is option-chain context)"],
            model_or_rule=f"R72 Breadth Donchian + R151 picker + {t}", settings="research/hunt/r159_r151_plus_filters/r159study.py",
            prereg="research/hunt/r159_r151_plus_filters/PREREG.md", variants_tried=len(S.TESTS),
            results=dict(summary=(f"1 lot. P2: {short(x['P2'])} (base {short(b2)}). P3: {short(x['P3'])} (base {short(b3)}); "
                                  f"random skips' mean {rs(x['twin_mean'])}, p {x['twin_p']}, BH q {x['q']}. "
                                  + ("PASS." if x["passed"] else "Fails: " + "; ".join(x["fails"]) + ".")),
                         costs="dated Zerodha charges, R17 fills, 1 lot"),
            holdout_read=True, verdict="paper-only" if x["passed"] else "rejected",
            notes="Signal on the underlying; a filter only removes trades; nothing changes live.")
        (ent / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("report + entries written;", len(passed), "passed")


if __name__ == "__main__":
    main()
