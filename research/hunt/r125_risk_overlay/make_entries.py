"""Writes research/experiments/entries/r125-z1.yaml and r125-z2.yaml.

    python3 research/hunt/r125_risk_overlay/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r125cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"


def inr(x):
    return f"{x:+,.0f}".replace("+-", "-")


def main():
    res = json.loads((K.RESULTS / "results.json").read_text())
    for z, r in res["tests"].items():
        p = r["periods"]
        summ = (f"{r['name']}: " + "; ".join(
            f"{per} {p[per]['trades']} trades, win {p[per]['win']:.1f}%, mean Rs {inr(p[per]['mean'])}, worst day Rs "
            f"{inr(p[per]['worst_day'])}, max DD Rs {p[per]['max_dd']:,.0f}, months <= -13k {p[per]['months_at_stop']}"
            for per in (*K.PERIODS, "all")) + f"; {r['skipped']} trades skipped; final at 2x slippage Rs "
            f"{inr(r['final_2x']['mean'])}; twin p {r['twin']['p']:.3f}; BH q {r['q_all']:.3g}, q within the loop "
            f"{r['q_loop']:.3g}. " + ("PASS" if r["gates"]["passed"] else "fail: " + "; ".join(r["gates"]["fails"])) + ".")
        ent = dict(
            id=f"r125-{z.lower()}", title=f"R125 {z}: {r['name']}", family="trade-management", date="2026-10-27",
            doc="research/HUNT_R125_RISK_OVERLAY.md",
            hypothesis=("R123 V2's expiry-day short straddle with an index stop fits inside Boss's limits (-Rs 13k a "
                        "month, -Rs 40k drawdown) with a one-trade-a-day rule (and a monthly breaker at half the "
                        "monthly stop), keeps a positive mean in every period, and the rule beats random skipping."),
            data_version="R123's trade lists (R98 expiry days, lake to 19 Oct 2026)",
            period={"design": "expiry days to 31 Dec 2023", "walk_forward": "2024-25",
                    "holdout": "final 2026 (NOT pristine: R123's trades by period were known; post hoc)"},
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: {K.SIGNAL_SOURCE}"], model_or_rule=r["name"],
            settings="research/hunt/r125_risk_overlay/r125cfg.py, r125lib.py", prereg="research/hunt/r125_risk_overlay/PREREG.md",
            variants_tried=1, results=dict(summary=summ, costs="R123's (R25 liq fills x R54 late multiplier, dated charges)"),
            holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
            notes="Loop round 5. Twins: random skipping of the same number of trades. Nothing to paper or live.")
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(res["tests"]))


if __name__ == "__main__":
    main()
