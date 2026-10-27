"""Writes research/experiments/entries/r122-t1.yaml and r122-t2.yaml.

    python3 research/hunt/r122_morning_premium/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r122cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"


def inr(x):
    return f"{x:+,.0f}".replace("+-", "-")


def main():
    res = json.loads((K.RESULTS / "results.json").read_text())
    for t, r in res["tests"].items():
        p = r["periods"]
        summ = (f"{r['name']}: " + "; ".join(
            f"{per} {p[per]['trades']} trades, win {p[per]['win']:.1f}%, mean Rs {inr(p[per]['mean'])} (gross "
            f"{inr(p[per]['gross'])}, charges {p[per]['charges']:,.0f}), worst day Rs {inr(p[per]['worst_day'])}, "
            f"months <= -13k {p[per]['months_at_stop']}" for per in K.PERIODS) +
            f"; final at 2x slippage Rs {inr(r['final_2x']['mean'])}; twin p {r["twin"]["p"]:.3f}; BH q {r['q_all']:.3g}. "
            + ("PASS" if r["gates"]["passed"] else "fail: " + "; ".join(r["gates"]["fails"])) + ".")
        ent = dict(
            id=f"r122-{t.lower()}", title=f"R122 {t}: sell the opening premium, {r['name']}", family="calendar-events",
            date="2026-10-27", doc="research/HUNT_R122_MORNING_PREMIUM.md",
            hypothesis=("Options are dearest at the open (R118 / R121): an ATM iron fly sold on the 09:20 close and "
                        "bought back at the 10:30 open makes money after real costs in every period, beats the same trade "
                        "at random later times, with a high win rate, inside Boss's limits."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 27 Oct 2026 (to 19 Oct 2026): opt_<UND>_WEEK "
                          "rolling nearest expiry ATM+-10 1-minute (real strikes), index minutes; every session 09:15-15:15"),
            period={"design": "sessions to 31 Dec 2023", "walk_forward": "2024-25",
                    "holdout": "final 1 Jan - 19 Oct 2026, read once"},
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: {K.SIGNAL_SOURCE}"], model_or_rule=r["name"] +
            " (sell C/P at the ATM strike, buy wings 2 straddles out, 1 lot each)",
            settings="research/hunt/r122_morning_premium/r122cfg.py, r121lib.py", prereg="research/hunt/r122_morning_premium/PREREG.md",
            variants_tried=1,
            results=dict(summary=summ, costs=("R25 liq fills x R54 late multiplier, R50 dated charges per order, stale "
                                               "legs repriced by Black-76 at their last IV with calendar time to expiry; twins: the same trade at a "
                                               "random bar 10:30-14:00 held 70 minutes (30 random rows a day)")),
            holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
            notes="Loop round 2. Nothing to paper or live.")
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(res["tests"]))


if __name__ == "__main__":
    main()
