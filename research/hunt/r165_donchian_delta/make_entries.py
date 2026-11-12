"""R165 experiment entries: one YAML per arm x rule x lots (failures included), from results/design.json, final.json and
decision.json.

    python research/hunt/r165_donchian_delta/make_entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import r165lib as L  # noqa: E402,F401

RESULTS = HERE / "results"
ENTRIES = ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r165_donchian_delta/results/report.md"
PREREG = "research/hunt/r165_donchian_delta/PREREG.md"
UNDS5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
ARMS = ("R72", "R51")
RULES = ("DB1", "DB2", "DB3", "SD50", "SD60", "SD70", "SD80", "XI1", "XI2", "PKD", "OF1", "OF2", "OF3")
LOTS = (1, 2, 3)
ARM_TXT = {
    "R72": ("R72 Breadth Donchian (4 of 5 indices break)", "index 5-min bars of all five indices (Donchian 20, 4 of 5)"),
    "R51": ("R51 Donchian 20 + Supertrend (10, 3)", "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"),
}
FAMILY = {"DB": "option-structure", "SD": "option-structure", "XI": "trade-management", "PK": "ml", "OF": "flow-oi"}


def rs(x) -> str:
    if x is None:
        return "-"
    return ("-" if x < 0 else "") + "Rs " + f"{abs(round(x)):,}"


def line(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return f"{s['trades']} trades, win {100 * s['win']:.1f}%, net {rs(s['net'])}"


def main():
    from r165study import LABEL, M_TOTAL   # noqa: E402  (labels only; no data is read at import)
    d = json.loads((RESULTS / "design.json").read_text())
    f = json.loads((RESULTS / "final.json").read_text())
    dec = json.loads((RESULTS / "decision.json").read_text())
    n = 0
    for arm in ARMS:
        title, src = ARM_TXT[arm]
        for r in RULES:
            for lots in LOTS:
                k, b = f"{r}@{lots}", f"B0@{lots}"
                g = dec[f"{arm}:{k}"]
                proxy = r.startswith("OF")
                e = dict(
                    id=f"r165-{arm.lower()}-{r.lower()}-{lots}lot",
                    title=f"R165 {r} on {title}, {lots} lot{'s' if lots > 1 else ''}: {LABEL[r]}",
                    family=FAMILY[r[:2]],
                    date="2026-11-12",
                    doc=DOC,
                    hypothesis=(f"Boss (12 Nov): raise the Donchian arms' win rate; \"check delta study in all the sessions\". "
                                f"{LABEL[r]} raises {title}'s win rate by >= 5 points with net not lower than today's rules "
                                "in the walk-forward window and the final, final net not lower than R151's app picker, "
                                f">= 40 final trades, beats random twins on win rate with BH q <= 0.10 (m = {M_TOTAL})."),
                    data_version="Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK, fut_1m_live_index) "
                                 "copied 12 Nov 2026; R51 Amendment A clean stores",
                    period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                                holdout="2026-04-01..2026-10-13 read once by R165 (already read by R51 - R160: weak evidence)"),
                    instruments=UNDS5,
                    features=[f"signal source: {src} (unchanged)",
                              ("order-flow PROXY: the bought option's 1-minute volume signed by the tick rule (no aggressor "
                               "data exists); option data used as a filter / exit around the index signal") if proxy else
                              ("the bought option's Black-Scholes delta (IV solved from the option's own price at the decision "
                               "minute) chooses / filters / exits around the index signal")],
                    model_or_rule=f"{title} with {LABEL[r]}, {lots} lot{'s' if lots > 1 else ''}",
                    settings="research/hunt/r165_donchian_delta/r165lib.py, r165study.py RULES; PREREG.md section 3",
                    prereg=PREREG,
                    variants_tried=len(RULES) * len(ARMS) * len(LOTS),
                    results=dict(
                        summary=(f"Walk-forward: {line(d[arm][k]['wf'])} (today {line(d[arm][b]['wf'])}). Final: "
                                 f"{line(f[arm][k]['final'])} (today {line(f[arm][b]['final'])}; R151 picker "
                                 f"{line(f[arm]['r151_picker'][f'@{lots}'])}); twin ({f[arm][k]['twin']['kind']}) raw p "
                                 f"{g['p']}, BH q {g['q']} (R165 alone {g['q_r165']}). "
                                 + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                        costs=(f"dated Zerodha charges on the whole {lots}-lot quantity, R17 fills (slippage from the "
                               "minute volume per position; stops -10 bps and 2x the half-spread, gaps at the open)")),
                    holdout_read=True,
                    verdict="paper-only" if g["passed"] else "rejected",
                    notes=("Weaker evidence: a proxy, not real order flow. " if proxy else "")
                    + "Signal on the underlying; nothing changes in the app.",
                )
                (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
