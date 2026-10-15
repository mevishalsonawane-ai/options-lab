"""Writes research/experiments/entries/r51-*.yaml: every R51 variant (originals, re-tunes, references), failures included.

    python research/hunt/r51/entries.py
"""
from __future__ import annotations

import json
import re

import yaml

import r51cfg as K
from report import old_of

ENT = K.ROOT / "research" / "experiments" / "entries"
FAMILY = {"Liquidity 15+5 (paused arm)": "liquidity", "Liquidity 15+5 (live arm)": "liquidity",
          "R28 GEX filter": "flow-oi", "Night (R3) arm": "overnight", "R27 Night holds": "overnight",
          "MCX paper arm": "mcx-crypto", "R44 sweep": "patterns-indicators",
          "R45 premium scalp (non-default)": "outside-strategies", "F1 factory": "patterns-indicators",
          "R29 EMA ribbons": "patterns-indicators", "R41 neural nets": "ml", "B2 all-at-once": "ml"}
INSTR = {"M4": ["SILVERM"], "LIQ-FIN": ["FINNIFTY"], "LIQ-BN": ["BANKNIFTY"], "G7": ["BANKNIFTY"],
         "NIGHT": ["NIFTY", "BANKNIFTY"], "G10": ["NIFTY", "BANKNIFTY"], "R45": ["NIFTY"]}


def rs(x):
    return "-" if x is None else f"Rs {x:,.0f}"


def summ(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {100 * s['win']:.0f}%, net {rs(s['net'])} ({rs(s['per_day'])}/day), avg win "
            f"{rs(s['avg_win'])} / loss {rs(s['avg_loss'])}, worst day {rs(s['worst_day'])}, max DD {rs(s['max_dd'])}, "
            f"without top 5 {rs(s['ex_top5'])}")


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text())
    for old in ENT.glob("r51-*.yaml"):
        old.unlink()
    n = 0
    for name, d in D.items():
        f = F.get(name, {})
        eid = "r51-" + re.sub(r"[^a-z0-9]+", "-", name.lower()).strip("-")
        key = next((k for k in INSTR if name.startswith(k)), None)
        instr = INSTR[key] if key else (["NIFTY", "BANKNIFTY", "FINNIFTY", "SENSEX"] if name.startswith(("F1", "B2"))
                                        else list(K.UNDS))
        if d["kind"] == "ref":
            verdict = "not-a-test"
        elif f.get("pass"):
            verdict = "paper-only"
        elif f.get("verdict") == "too few trades to judge":
            verdict = "inconclusive"
        else:
            verdict = "rejected"
        des = f"design {summ(d['design'])}"
        if d["kind"] == "retune":
            des += f"; chosen {d['final_pick']}; walk-forward out-of-fold {rs(d['wf_net'])} on {d['wf_trades']} trades"
        else:
            des += f"; twin p {d['design'].get('p')}; folds {d.get('folds')}"
        fin = f"final test (1 Apr - 13 Oct 2026, read once) {summ(f)}; twin p {f.get('p')}, BH q {f.get('q')}: " \
              f"{f.get('verdict')}"
        e = dict(id=eid, title=f"R51 {name}: {d['desc']}", family=FAMILY[d["family"]], date="2026-10-15",
                 doc="research/HUNT_R51_CANCELLED_RECENT.md",
                 hypothesis=f"Re-tested on the Nov 2024+ market only, this {'re-tune' if d['kind'] == 'retune' else 'rule'} "
                            "makes money after real costs on one held contract (gates: final net > 0, twin p < 0.05, BH q "
                            "<= 0.10 over the study, 40+ final trades, top-5 < net, design walk-forward > 0).",
                 data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK files; brain cube + "
                              "features rebuilt from the lake), 21 Nov 2024 - 13 Oct 2026",
                 period=dict(design=f"{d.get('design_start', str(K.START))} - 2026-03-31 (walk-forward quarters from Apr 2025)",
                             holdout="2026-04-01 - 2026-10-13 final test, read once (not pristine: earlier studies saw "
                                     "parts of 2025-26)"),
                 instruments=instr, features=[f"signal source: {d['source']}"], model_or_rule=d["desc"],
                 settings=f"research/hunt/r51/fam_{d['module']}.py ({f.get('config', '')})",
                 prereg="research/hunt/r51/PREREG.md",
                 variants_tried=len(d.get("grid_design_net", {})) or 1,
                 results=dict(summary=f"{des}. {fin}. OLD: {old_of(name) or 'see the write-up'}",
                              costs="dated charges (STT 0.15% from 1 Apr 2026), R17 liq fills, next-minute fills, gap "
                                    "fills at the worse price, one held contract, lots in force"),
                 holdout_read=True, verdict=verdict)
        (ENT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=False, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
