"""R105B outputs: results/*.json -> results/report.md and research/experiments/entries/r105b-*.yaml (40, failures
included).

    python research/hunt/r105b_direction/r105bout.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r105bbias as B  # noqa: E402
import r105blib as L  # noqa: E402

POST_HOC = {"D1", "D10"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{round(100 * x)}%"


def load():
    des = {a: json.loads((L.RESULTS / f"design_{a}.json").read_text()) for a in L.ARMS}
    fin = json.loads((L.RESULTS / "final.json").read_text())
    oos = json.loads((L.RESULTS / "oos.json").read_text()) if (L.RESULTS / "oos.json").exists() else None
    return des, fin, oos


def report():
    des, fin, oos = load()
    o = ["# R105B report: book direction biases on the paper arms (all tables)", "",
         f"Final read {fin['read']}. BH family: {fin['family']['new']} new + {fin['family']['prior']} earlier filter / "
         f"direction tests on these arms ({fin['family']['prior_p_below_005']} with p < 0.05). 1 lot, net after "
         "charges and slippage. 'dropped' = the arm's signals the bias removed; 'lost' = how many of them lost on "
         "their own. D1 and D10 are post-hoc (PREREG section 0).", ""]
    for a in L.ARMS:
        D, F = des[a], fin["arms"][a]
        o += [f"## {L.ARM_NAMES[a]}", "",
              "| bias | design trades / win / net | WF net | quarters | final trades / win / net | final max DD | "
              "dropped d (lost) | twin p d / f | BH q | verdict |", "|---|---|---|---|---|---|---|---|---|---|",
              f"| baseline | {D['base']['trades']} / {pct(D['base']['win'])} / {rs(D['base']['net'])} | "
              f"{rs(D['base_wf_net'])} | {D['base_q']} | {F['base']['trades']} / {pct(F['base']['win'])} / "
              f"{rs(F['base']['net'])} | {rs(F['base']['max_dd'])} | - | - | - | - |"]
        for r in B.RULES:
            t = fin["tests"][f"{a}:{r}"]
            dr = D["rules"][r]
            g = t["gate"]
            o.append(f"| {r} {B.NAMES[r]} | {t['design']['trades']} / {pct(t['design']['win'])} / {rs(t['design']['net'])}"
                     f" | {rs(t['wf_net'])} | {t['q']} | {t['final']['trades']} / {pct(t['final']['win'])} / "
                     f"{rs(t['final']['net'])} | {rs(t['final']['max_dd'])} | {dr['dropped']} ({dr['dropped_lost']}) | "
                     f"{t['p_design']} / {t['p_final']} | {t['q_bh']} | "
                     f"{'PASS' if g['passed'] else 'fail: ' + ', '.join(g['failed'])} |")
        if oos:
            B_ = oos["arms"][a]
            o += ["", f"OOS 14-19 Oct (4 sessions): baseline {B_['base']['trades']} trades / {rs(B_['base']['net'])}; "
                  + "; ".join(f"{r} {B_['rules'][r]['stats']['trades']} / {rs(B_['rules'][r]['stats']['net'])}"
                              for r in B.RULES)]
        o.append("")
    (L.RESULTS / "report.md").write_text("\n".join(o) + "\n")


def entries():
    des, fin, _ = load()
    for a in L.ARMS:
        for r in B.RULES:
            t = fin["tests"][f"{a}:{r}"]
            g = t["gate"]
            dr = des[a]["rules"][r]
            e = dict(
                id=f"r105b-{a.lower()}-{r.lower()}",
                title=f"R105B {r} {B.NAMES[r]} as a direction filter on {L.ARM_NAMES[a]}",
                family="daily-filters",
                date="2026-10-26",
                doc="research/HUNT_R105B_DIRECTION.md",
                hypothesis=("Boss (26 Oct: \"by all the pdf knowledge, find the direction and use it in Breadth "
                            f"Donchian and other strategies\"): {B.NAMES[r]} as a bias (calls only on up, puts only on "
                            f"down) makes {L.ARM_NAMES[a]} better in design, walk-forward (>= 3 of 4 quarters) and "
                            "final, beats random skips (p < 0.05 in design and final), BH q <= 0.10 over 40 new + the "
                            "earlier filter tests, >= 40 final trades, better without the 5 best, drawdown not worse "
                            "by > 10%." + (" Post-hoc: built from R104's holdout observation." if r in POST_HOC else "")),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK), R51 "
                              "Amendment A clean stores, 20 Nov 2024 - 13 Oct 2026 (OOS 14-19 Oct apart)"),
                period=dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
                            holdout=("1 Apr - 13 Oct 2026, read once for R105B (26 Oct); already read by ~25 studies, "
                                     "weak evidence")),
                instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                features=["signal source: the index (the arm's own signal; the bias from the index's daily / weekly / "
                          "60-minute / 15-minute bars or today's open); no option chart"],
                model_or_rule=f"{L.ARM_NAMES[a]} (R101 candidates, own exits) + bias {r}: {B.NAMES[r]}",
                settings="research/hunt/r105b_direction/r105bbias.py; PREREG.md",
                prereg="research/hunt/r105b_direction/PREREG.md",
                variants_tried=1,
                results=dict(
                    summary=(f"Design {t['design']['trades']} trades, win {pct(t['design']['win'])}, net "
                             f"{rs(t['design']['net'])} (baseline {t['base_design']['trades']} / "
                             f"{rs(t['base_design']['net'])}); WF {rs(t['wf_net'])} vs {rs(t['base_wf_net'])}, quarters "
                             f"{t['q']} vs {t['base_q']}; final {t['final']['trades']} trades, win "
                             f"{pct(t['final']['win'])}, net {rs(t['final']['net'])} (baseline "
                             f"{t['base_final']['trades']} / {rs(t['base_final']['net'])}); dropped {dr['dropped']} design "
                             f"signals ({dr['dropped_lost']} lost on their own); random-skip p {t['p_design']} / "
                             f"{t['p_final']}; BH q {t['q_bh']}. Verdict: {'PASS' if g['passed'] else 'FAIL'}"
                             + (" (" + ", ".join(g["failed"]) + ")" if g["failed"] else "") + "."),
                    costs="dated charges, R17 liquidity fills, next-minute fills, stops on the minute low, 1 lot"),
                holdout_read=True,
                verdict="paper-only" if g["passed"] else "rejected",
                notes="Signal on the underlying. Research only; a passer goes on paper only via the app session.",
            )
            (L.ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    report()
    entries()
