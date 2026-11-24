"""R210's experiment-log entries (research/experiments/entries/r210-*.yaml). Before the run every entry is 'proposed'
(pre-registered, never run); after `decide` / `holdout` the same script writes the results and verdicts.

    python3 -I research/hunt/r210_trail_ideas/r210entries.py          # proposed (before any number)
    python3 -I research/hunt/r210_trail_ideas/r210entries.py results  # after the run

Plain-English notes, 24 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r210cfg as K  # noqa: E402

ENTRIES = HERE.parents[1] / "experiments" / "entries"
SRC = {"HU": "the index (5-minute index bars, ATR(14), swing lows / highs); stage times on the premium",
       "HUNT": "the index (5-minute index bars, ATR(14), swing lows / highs); stage times on the premium",
       "HP": "the OPTION'S OWN premium chart (5-minute premium bars): an option-chart rule, labelled, not the default",
       "CH": "the index (5-minute index bars, ATR(14))",
       "F15": "the index (15-minute index bars, EMA(20))",
       "F60": "the index (60-minute index bars, EMA(20))"}


def entry(tid: str, results: dict | None) -> dict:
    arm, kind = K.TESTS[tid]
    base = K.LABEL["V5"]
    e = dict(
        id=f"r210-{tid.lower()}",
        title=f"R210 {K.ARM_NAME[arm]}: {K.LABEL[kind]} vs the current exits (V5)",
        family="daily-filters" if kind in ("F15", "F60") else "trade-management",
        date="2026-11-24",
        doc="research/hunt/r210_trail_ideas/PREREG.md",
        hypothesis=(f"{K.LABEL[kind]} beats {base} on {K.ARM_NAME[arm]}: higher net on the design years "
                    "(1 Nov 2024 - 31 Mar 2026), in at least 3 of 4 walk-forward folds, worst month not worse, BH q <= 0.10 "
                    f"(m = {K.M_TOTAL:,}); then, read once, higher net and a worst month not worse on 1 Apr - 13 Nov 2026"),
        data_version=("Drive lake copy (R190's flat folder ~/.cache/iraalgo/r190/lake, with the 20 Oct - 13 Nov 2026 fill); "
                      "R51 clean stores 3 Aug 2020 - 13 Nov 2026 (R190), as R193"),
        period=dict(design="2024-11-01..2026-03-31 (folds F1 Nov 2024 - Feb 2025, F2 Mar - Jun 2025, F3 Jul - Oct 2025, "
                           "F4 Nov 2025 - Mar 2026)",
                    holdout="2026-04-01..2026-11-13 (locked for R210's variants; read once, design survivors only)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=[f"signal source: {SRC[kind]}", "entries: the arm's own index breaks, unchanged; options bought, 1 lot"],
        model_or_rule=f"{K.LABEL[kind]} (baseline: {base})",
        settings="research/hunt/r210_trail_ideas/r210cfg.py; PREREG.md section 3",
        prereg="research/hunt/r210_trail_ideas/PREREG.md",
        variants_tried=None,
        results=dict(summary="pre-registered on 24 Nov 2026; not run yet (the data run follows the push of PREREG.md)",
                     costs="dated Zerodha charges, R17 fills and slippage, 1 lot"),
        holdout_read=False,
        verdict="proposed",
        notes="Research only; nothing in the app changes. Idea from a third-party prompt-only skill pack (no backtests).",
    )
    if results is not None:
        e.update(results)
    return e


def results_for(tid: str) -> dict:
    d = json.loads((K.RESULTS / "design.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    hp = K.RESULTS / "holdout.json"
    h = json.loads(hp.read_text()) if hp.exists() else {"read": False}
    arm = K.TESTS[tid][0]
    v, b, g = d[tid]["stats"]["design"], d["V5:" + arm]["stats"]["design"], dec["tests"][tid]

    def s(x):
        return (f"{x['trades']} tr, {x['win']}% win, Rs {x['net']:,}, worst month Rs {x['worst_month']['net']:,}, "
                f"DD Rs {x['max_dd']:,}")
    txt = (f"design {s(v)} (V5 {s(b)}); folds beaten {', '.join(g['folds_won']) or 'none'}; sign-flip p {g['p']:.4f}, "
           f"BH q {g['q']:.3g} (m = {K.M_TOTAL:,}). ")
    read = bool(h.get("read")) and tid in h
    if not g["passes"]:
        txt += "Fails the design gate: " + "; ".join(g["fails"]) + ". Holdout not read."
        verdict = "rejected"
    else:
        hv = h[tid]
        txt += f"Holdout (read once): {s(hv['stats'])} vs V5 {s(h['V5:' + arm])}. "
        txt += "CANDIDATE only (Boss's choice)." if hv["verdict"]["candidate"] else "Fails: " + "; ".join(hv["verdict"]["fails"])
        verdict = "inconclusive" if hv["verdict"]["candidate"] else "rejected"
    return dict(variants_tried=1, holdout_read=read, verdict=verdict,
                doc="research/hunt/r210_trail_ideas/results/RESULT.md",
                results=dict(summary=txt, costs="dated Zerodha charges, R17 fills and slippage, 1 lot"))


def main() -> None:
    after = (sys.argv[1:] or [""])[0] == "results"
    for tid in K.TESTS:
        e = entry(tid, results_for(tid) if after else None)
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    print(len(K.TESTS), "entries written")


if __name__ == "__main__":
    main()
