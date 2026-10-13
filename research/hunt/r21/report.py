"""R21 report: results/results.json -> results/report.md (tables) and results/summary.json (for entries.py).

    python3 research/hunt/r21/report.py && python3 research/hunt/r21/entries.py
"""
from __future__ import annotations

import json

import config as K

IDEA = {"V0": "baseline (app's Hero)", "V1": "(a) one-sided k 2.5", "V2": "(b) straddle 10% in last 15 min",
        "V3": "(a)+(c) to 15:00", "V4": "(a)+(c)+(d) Rs 1-15", "V5": "V4 + lock 2x after 4x",
        "V6": "V4 + give-back trail", "V7": "V4, plain 15:05 exit", "V8": "(f) blind 13:30"}


def gate_ok(v) -> bool:
    return all(x is True or x == "True" for x in v["gates"].values())


def main():
    r = json.loads((K.RESULTS / "results.json").read_text())
    L = [f"# R21 results ({r['source']}): design {r['design_days']} expiry days, holdout {r['holdout_days']} (read once)", "",
         f"Big days (hindsight: some Rs 1-10 option reached 5x before 15:05): design {r['big_design']} / {r['design_days']}, "
         f"holdout {r['big_holdout']} / {r['holdout_days']}.", ""]
    for part in ("design", "holdout"):
        L += [f"## {part}", "", "| id | idea | trades | wins | net Rs | per expiry Rs | worst Rs | max DD Rs | big days caught / missed |",
              "|---|---|---|---|---|---|---|---|---|"]
        for k, v in r["variants"].items():
            x = v[part]
            L.append(f"| {k} | {IDEA[k]} | {x['trades']} | {x['wins']} | {x['net']:+,.0f} | {x['per_expiry']:+,.0f} | "
                     f"{x['worst']:+,.0f} | {x['max_dd']:+,.0f} | {x['big_caught']} / {x['big_missed']} |")
        L.append("")
    L += ["## gates", "", "| id | holdout 2x spread Rs | twin mean Rs | twin p | BH q | G1 design>0 | G2 holdout>0, >=5 | G3 q<0.05 | G4 stress | G5 > V0 | passes |",
          "|---|---|---|---|---|---|---|---|---|---|---|"]
    yn = lambda b: "yes" if (b is True or b == "True") else "no"  # noqa: E731
    for k, v in r["variants"].items():
        g = v["gates"]
        L.append(f"| {k} | {v['holdout_2x_spread']['net']:+,.0f} | {v['twin']['mean']:+,.0f} | {v['twin']['p']:.3f} | {v['twin']['bh_q']:.3f} | "
                 f"{yn(g['g1_design_net'])} | {yn(g['g2_holdout'])} | {yn(g['g3_twin_bh'])} | {yn(g['g4_stress'])} | {yn(g['g5_beats_v0'])} | "
                 f"{'PASS' if gate_ok(v) else 'fail'} |")
    wf = r["walk_forward"]
    L += ["", f"## walk-forward (V4, k re-chosen monthly on earlier expiries): {wf['trades']} trades, {wf['wins']} wins, "
          f"net Rs {wf['total']:+,.0f}", "", "| month | k | expiries | trades | net Rs |", "|---|---|---|---|---|"]
    for m in wf["months"]:
        L.append(f"| {m['month']} | {m['k']} | {m['days']} | {m['trades']} | {m['net']:+,.0f} |")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    summ = {"variants": {}, "anatomy": (
        "Design 33 expiry days (27 NIFTY + 6 BANKNIFTY): some Rs 1-10 option (13:00-14:30) reached 5x before 15:05 on 24, "
        "10x on 11, 20x on 7 (hindsight); 11 of the 24 were back under 2x by 15:05 (median give-back 68%). The straddle "
        "condition held on 32 of 33 days; 15-minute 0.25% momentum on only 9. Nothing at 13:00 separated big days.")}
    for k, v in r["variants"].items():
        d, h = v["design"], v["holdout"]
        txt = (f"Design {d['trades']} trades, {d['wins']} wins, net Rs {d['net']:+,.0f} ({r['design_days']} expiries). "
               f"Holdout {h['trades']} trades, {h['wins']} wins, net Rs {h['net']:+,.0f} ({r['holdout_days']} expiries; "
               f"worst Rs {h['worst']:+,.0f}; big days caught {h['big_caught']} of {h['big_days']}). Twin p {v['twin']['p']:.3f}, "
               f"BH q {v['twin']['bh_q']:.3f}. {'PASSED' if gate_ok(v) else 'FAILED'} the gates.")
        summ["variants"][k] = dict(text=txt, verdict="paper-only" if gate_ok(v) else "rejected")
    (K.RESULTS / "summary.json").write_text(json.dumps(summ, indent=1))
    print("\n".join(L))


if __name__ == "__main__":
    main()
