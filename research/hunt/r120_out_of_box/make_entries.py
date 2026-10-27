"""Writes research/experiments/entries/r120-q1..q5.yaml (5), failures included.

    python3 research/hunt/r120_out_of_box/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r120cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"


def inr(x):
    return f"{x:+,.0f}".replace("+-", "-")


def main():
    res = json.loads((K.RESULTS / "results.json").read_text())
    for q, r in res["tests"].items():
        p = r["periods"]
        size = f" at {r['n_lots']} lots per leg (chosen on design)" if q == "Q1" else ""
        summ = (f"{r['name']}{size}: " + "; ".join(
            f"{per} {p[per]['trades']} trades, win {p[per]['win']:.1f}%, mean Rs {inr(p[per]['mean'])}, total "
            f"Rs {inr(p[per]['total'])}, worst day Rs {inr(p[per]['worst_day'])}, months <= -13k {p[per]['months_at_stop']}"
            for per in K.PERIODS if p[per].get("trades")) + f"; final w/o 3 best days Rs {inr(p['final']['ex3'])}, "
            f"max DD Rs {p['final']['max_dd']:,.0f}; twin p {r['twin']['p']:.3f}; BH q {r['q_all']:.3g} (m {res['m_all']:,}). "
            + ("PASS" if r["gates"]["passed"] else "fail: " + "; ".join(r["gates"]["fails"])) + ".")
        ent = dict(
            id=f"r120-{q.lower()}", title=f"R120 {q}: {r['name']}", family="calendar-events", date="2026-10-27",
            doc="research/HUNT_R120_OUT_OF_BOX.md",
            hypothesis=f"On expiry days, {r['name']} makes money after real costs in design, walk-forward and final, "
                       "beats random twins, and stays inside Boss's -Rs 13k month / -Rs 40k drawdown limits.",
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 27 Oct 2026 (to 19 Oct 2026): R98 expiry-day "
                          "records, five indices; index minutes of every session (twin index, next session)"),
            period={"design": "expiry days to 31 Dec 2023 (Q1's lot count fitted here)",
                    "walk_forward": "2024-25", "holdout": "final 1 Jan - 19 Oct 2026, read once (Q1-Q3 not pristine: "
                                                          "R118 / R119 saw these patterns and P1's P&L)"},
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: {r['source']}"], model_or_rule=r["name"] + size,
            settings="research/hunt/r120_out_of_box/r120cfg.py, r120lib.py",
            prereg="research/hunt/r120_out_of_box/PREREG.md", variants_tried=10 if q == "Q1" else 1,
            results=dict(summary=summ, costs=("options: R119's engine (R25 liq fills x R54 late multiplier, dated "
                                               "charges, stale legs repriced; Q1 +1 tick per 5 lots beyond 5); futures: "
                                               "R104 dated index-futures charges (STT 0.01% -> 0.05% from Apr 2026), "
                                               "half-spread max(0.05, 0.5 bp) + 0.5 bp slippage per fill, index as proxy")),
            holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
            notes="Random twins 2,000 (seed 120). Nothing to paper or live.")
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(res["tests"]))


if __name__ == "__main__":
    main()
