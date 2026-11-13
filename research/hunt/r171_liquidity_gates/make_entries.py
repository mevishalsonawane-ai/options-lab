"""Write research/experiments/entries/r171-<gate>.yaml (one per gate, all four strategies) from results/oos.json.

    python research/hunt/r171_liquidity_gates/make_entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
STRATS = ("R51", "R72", "LIQ", "EXP")
RULES = {
    "G1a": "same-day pre-entry window: >= 80% of the 30 minutes before entry traded on the chosen strike, and a trade in the last 5",
    "G1b": "same-day pre-entry window: >= 80% of the 60 minutes traded, and a trade in the last 5",
    "G1c": "same-day pre-entry window: >= 95% of the 30 minutes traded, and a trade in the last 5",
    "G2a": "cost cap: round trip (brokerage + statutory + quoted spread) <= 2.0% of the premium",
    "G2b": "cost cap: round trip <= 2.5% of the premium",
    "G2c": "cost cap: round trip <= 3.0% of the premium",
    "G2d": "premium floor: premium >= Rs 10 (tick-floor effects)",
    "G2e": "cost cap 2.5% judged with the roll spread",
    "G2f": "cost cap 2.5% judged with the stress spread",
    "G3a": "depth: median lots a minute over the 30-minute window >= 10 x the order",
    "G3b": "depth: median lots a minute >= 50 x the order",
    "G3c": "Roll spike: the window's relative Roll spread <= 2x the contract's own normal (previous 5 sessions)",
    "G3d": "Roll spike: <= 3x the contract's own normal",
    "G4a": "DTE-coupled: spread_in_theta_minutes (quoted spread) <= 60 for DTE >= 2; no cap on DTE 0-1",
    "G4b": "DTE-coupled: caps 60 / 45 / 30 / 20 minutes for DTE 2-3 / 4-7 / 8-21 / 22+; no cap on DTE 0-1",
    "G4c": "G4b plus the same-day window at >= 60% on DTE 0-1 and >= 80% otherwise",
    "G4d": "G4b with the MEASURED pre-entry Roll spread instead of the assumed one",
    "C": "pre-registered combination per strategy, chosen on design only (AND of each family's design-best qualifying variant)",
}


def rs(x):
    return "-" if x is None else f"{'-' if x < 0 else ''}Rs {abs(x):,.0f}"


def main():
    oos = json.loads((HERE / "results" / "oos.json").read_text())
    for g, rule in RULES.items():
        parts, fails = [], 0
        for s in STRATS:
            r = oos["strategies"][s]["rows"].get(g)
            if r is None:
                parts.append(f"{s}: no combination (nothing qualified on design)")
                continue
            k, b = r["kept"]["quoted"], r["base"]["quoted"]
            fails += r["verdict"] != "pass"
            label = f"{s}" + (f" ({'+'.join(oos['strategies'][s]['combo'])})" if g == "C" else "")
            parts.append(f"{label}: kept {k['n']} of {b['n']} OOS trades, {rs(k['per_trade'])} a trade vs {rs(b['per_trade'])} "
                         f"(gain roll {rs(r['gain']['roll'])} / stress {rs(r['gain']['stress'])}), worst day {rs(k['worst_day'])}, "
                         f"max DD {rs(k['max_dd'])}, random-skip p {r['p_mc']:.3g}, q {r['q']:.3g}, {r['verdict']}")
        e = {
            "id": f"r171-{g.lower()}",
            "title": f"R171 {g}: {rule}",
            "family": "sizing-execution",
            "date": "2026-11-13",
            "doc": "research/hunt/r171_liquidity_gates/results/report.md",
            "hypothesis": (f"Boss (13 Nov): skip-only liquidity / execution-cost gate {g} ({rule}) raises net per trade on R51, R72, "
                           f"Liquidity 15+5 and the options_lab expiry put vs no gate, beats random skips of the same size "
                           f"(BH q <= 0.10, m = {oos['m_total']}), >= 40 OOS trades, gain also under roll and stress costs and "
                           f"on design, worst day / max DD not worse."),
            "data_version": ("GitHub release research-data (nifty_prev, nifty_year, banknifty_prev_year_wide, banknifty_year_wide, "
                             "finnifty_index, sensex_index) fetched 13 Nov 2026; options_lab/data/expiry_cache; nothing on or "
                             "after 2026-02-24"),
            "period": {"design": "2024-02-13..2025-03-31",
                       "holdout": "2025-04-01..2026-02-23 out-of-sample for the gates, read once (the expiry put's sealed last 30 sessions not read)"},
            "instruments": ["NIFTY", "BANKNIFTY"],
            "features": ["signal source: the index (R51 / R72 / Liquidity); the options' parity forward (expiry put)",
                         "pre-entry option volume, premium, Roll estimate from 1-minute prices, DTE (filters only)"],
            "model_or_rule": f"{g}: {rule}",
            "settings": "research/hunt/r171_liquidity_gates/PREREG.md; r171arms.py; r171study.py",
            "prereg": "research/hunt/r171_liquidity_gates/PREREG.md",
            "variants_tried": 4,
            "results": {"summary": "; ".join(parts) + f". {4 - fails} of 4 passed.",
                        "costs": "dated statutory charges + Rs 20 an order (obuy 'dated') and the options_lab/costs.py spread law "
                                 "crossed half each way; primary quoted, roll and stress shown; 1 lot at the dated lot"},
            "holdout_read": True,
            "verdict": "rejected" if fails == 4 else "inconclusive",
        }
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


if __name__ == "__main__":
    main()
