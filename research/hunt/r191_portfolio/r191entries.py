"""R191 experiment entries: one YAML per portfolio variant (failures included), from decision.json and the era results.

    python3 research/hunt/r191_portfolio/r191entries.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r191study as S  # noqa: E402

ENTRIES = S.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r191_portfolio/results/report.md"
PREREG = "research/hunt/r191_portfolio/PREREG.md"
DATA = ("R190's priced trade streams (~/.cache/iraalgo/r190: Night via the R147 builder caches, arms_trades.pkl L1 / D2, "
        "rules_NIFTY.pkl VIX flag), from the Drive lake copy of 17 Nov 2026 plus the dhan-data NIFTY options of 2020-21")
INSTR = {"N": ["NIFTY", "BANKNIFTY"], "L": ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"],
         "R": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]}
SRC = {"N": "Night: index close location, 59-stock breadth (option OI build-up as context)",
       "L": "Liquidity 15+5: the index", "R": "R72: the five indices' 5-minute Donchian breaks (R151 picker)"}


def line(e: str, x: dict) -> str:
    s = x["stats"]
    return (f"era {e} {s['trades']} tr, {s['win']:.1f}% win, {s['months_up_pct']:.0f}% months up, Rs {s['net']:,.0f}, "
            f"DD Rs {s['max_dd']:,.0f}, worst month Rs {s['worst_month']:,.0f}, twin p {x['p_emp']:.4f}")


def main():
    dec = json.loads((HERE / "decision.json").read_text())
    b = json.loads(S.ERA_B_JSON.read_text())
    ca = json.loads(S.ERA_CA_JSON.read_text())
    eras = {"A": ca["A"], "B": b["B"], "C": ca["C"]}
    for vid, (comps, rules, name) in S.VARIANTS.items():
        d = dec["variants"][vid]
        inst = sorted({i for c in comps for i in INSTR[c]})
        feats = [SRC[c] for c in comps] + ["buy the option; R190's dated Zerodha charges and slippage; 1 lot per trade"]
        if rules.vix:
            feats.append("India VIX previous close vs its 252-day 90th percentile (skip filter)")
        e = dict(
            id=f"r191-{vid.lower()}", title=f"R191 portfolio {vid}: {name}", family="sizing-execution",
            date="2026-11-17", doc=DOC,
            hypothesis=(f"Portfolio {vid} ({name}) built from existing trade streams with risk rules that use only "
                        "closed P&L known at the time: profitable in eras A, B and C with 40+ trades, robust to the top "
                        "5 trades, beats month-block random-side twins (p < 0.05) in each era, BH q <= 0.10 (m 4,770), "
                        "and inside the account stop (DD < Rs 40k, every month > -Rs 13k) in eras B and C?"),
            data_version=DATA,
            period=dict(design="era B 2021-12-01..2024-10-31 (rules fixed in advance; nothing fitted)",
                        holdout="eras C 2024-11-01..2026-11-13 and A 2020-08-03..2021-07-30, read once after "
                                "results/ERA_CA_READ.flag; both seen by R190 at the stream level"),
            instruments=inst, features=feats, model_or_rule=name,
            settings="research/hunt/r191_portfolio/r191study.py VARIANTS; r191lib.py; PREREG.md section 4",
            prereg=PREREG, variants_tried=1,
            results=dict(summary="; ".join(line(k, eras[k][vid]) for k in ("A", "B", "C")) +
                         f". BH q {d['q']:.3g}. " + ("RELIABLE" if d["reliable"] else
                                                     "Not reliable; failed: " + "; ".join(d["fails"])),
                         costs="R190's: dated Zerodha charges; Night app +-5 bps + h24 half-spread; arms' R17 fills"),
            holdout_read=True, verdict="paper-only" if d["reliable"] else "rejected",
            notes="Signal on the underlying, option bought. Research only; nothing in the app changes.")
        (ENTRIES / f"r191-{vid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120,
                                                                          allow_unicode=False))
    print("entries written")


if __name__ == "__main__":
    main()
