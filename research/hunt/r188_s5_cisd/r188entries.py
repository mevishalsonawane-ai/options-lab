"""Writes research/experiments/entries/r188-*.yaml (23 tests) from results/final.json. 17 Nov 2026."""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402
import r188report as RP  # noqa: E402

ENTRIES = C.HERE.parents[1] / "experiments" / "entries"
ARM = {"S5": "S5 Trail (R123 N1-S5 + R128 T2-S5x1)", "CISD": "CISD + network (R133 S3-V2-E3)"}
SIGNAL = {"S5": "signal source: R123's network N1 on the index, India VIX, breadth and option-chain context (the index)",
          "CISD": "signal source: the index's 5-minute bars (R131 CISD), R123 N1's EV as a filter"}


def fmt(s: dict) -> str:
    return (f"{s['n']} trades, {s['win']:.1%} won, net Rs {s['total']:,.0f}, worst month Rs {s['worst_month']:,.0f}, "
            f"max drawdown Rs {s['max_dd']:,.0f}")


def main():
    F = json.loads((C.RESULTS / "final.json").read_text())
    n = 0
    for arm, lv in C.TESTS:
        r = F[arm].get(lv, {})
        b = F[arm]["base"]
        if r.get("not_formed") or "design" not in r:
            summ = (f"Not formed: fewer than 2 levers passed the design gates (S5: only LBE1000). Counted in BH m "
                    f"{C.M_TOTAL:,} with p = 1.")
            fails = ["not formed"]
        else:
            g = r["gates"]
            fails = [f"design {k}" for k, ok in g["design"].items() if not ok] + \
                    [f"final {k}" for k, ok in g["final"].items() if not ok] + \
                    ([] if g["final_n"] else ["final trades < 30"]) + ([] if g["q"] else ["twin BH q > 0.10"])
            summ = (f"1 lot. Design (2023 - 15 Mar 2026): {fmt(r['design'])} vs base {fmt(b['design'])}. Final (1 Apr - "
                    f"19 Oct 2026): {fmt(r['final'])} vs base {fmt(b['final'])}. New month (21 Oct - 13 Nov 2026): "
                    f"{r['new']['n']} trades, net Rs {r['new']['total']:,.0f} vs base {b['new']['n']}, Rs "
                    f"{b['new']['total']:,.0f}. Twin p {r['twin_p']:.3f}, BH q {r['q']:.2f} (m {C.M_TOTAL:,}). "
                    f"Fails: {', '.join(fails)}.")
        label = RP.NAMES[lv]
        if lv == "NTHR":
            label += f" (T = {F['_design'][arm]['NTHR_chosen']:+.0f}, chosen on design)"
        if lv == "COMBO" and F["_design"][arm]["COMBO_parts"]:
            label += " (" + " + ".join(F["_design"][arm]["COMBO_parts"]) + ")"
        e = dict(id=f"r188-{arm.lower()}-{lv.lower()}", title=f"R188 {ARM[arm]}: {label}",
                 family="trade-management" if lv in C.EXIT_LEVERS or lv == "COMBO" else "daily-filters",
                 date="2026-11-17", doc="research/hunt/r188_s5_cisd/results/report.md",
                 hypothesis=f"{label} on top of {arm} as the app runs it raises net AND win rate and lowers the worst "
                            "month or drawdown, in design and final",
                 data_version="Drive lake (to 19 Oct 2026) + 16 Nov gap files (to 13 Nov 2026); R123's saved N1 "
                              "walk-forward and holdout predictions (derived/r123/cleaned); R134 nnlive for new days",
                 period=dict(design="2023-01-02..2026-03-15 (R123 folds F1-F4)",
                             holdout="2026-04-01..2026-10-19 (the base already read there; new month 2026-10-21.."
                                     "2026-11-13 descriptive)"),
                 instruments=list(C.UNDS),
                 features=[SIGNAL[arm] + "; exits on the option premium (IDX and CISD's stop on the index); FT3PO "
                           "reads the bought option's price and OI as context"],
                 model_or_rule="research/hunt/r188_s5_cisd/r188sim.py", settings=f"PREREG.md section 3 ({lv}); 1 lot",
                 prereg="research/hunt/r188_s5_cisd/PREREG.md", variants_tried=len(C.TESTS),
                 results=dict(summary=summ, costs="Costs('dated') Zerodha charges + Fills('liq') slippage, 1 lot; "
                                                  "2x slippage reported"),
                 holdout_read=True, verdict="rejected")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        n += 1
    print("wrote", n)


if __name__ == "__main__":
    main()
