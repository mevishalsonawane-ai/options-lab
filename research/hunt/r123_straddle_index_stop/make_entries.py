"""Writes research/experiments/entries/r123-t1.yaml and r123-t2.yaml.

    python3 research/hunt/r123_straddle_index_stop/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r123cfg as K

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
            f"; final at 2x slippage Rs {inr(r['final_2x']['mean'])}; twin p {r['twin']['p']:.3f}; BH q {r['q_all']:.3g}. "
            + ("PASS" if r["gates"]["passed"] else "fail: " + "; ".join(r["gates"]["fails"])) + ".")
        ent = dict(
            id=f"r123-{t.lower()}", title=f"R123 {t}: {r['name']}", family="calendar-events",
            date="2026-10-27", doc="research/HUNT_R123_STRADDLE_INDEX_STOP.md",
            hypothesis=("On expiry days, a naked ATM straddle sold at 09:30 and closed when the index moves k "
                        "straddles (else at 15:05) keeps the short-premium edge with 4 orders instead of 8, makes money "
                        "after real costs in every period, beats random entry times, inside Boss's limits."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 27 Oct 2026 (to 19 Oct 2026): opt_<UND>_WEEK "
                          "rolling nearest expiry ATM+-10 1-minute (R98 expiry-day records), index minutes"),
            period={"design": "expiry days to 31 Dec 2023", "walk_forward": "2024-25",
                    "holdout": "final 1 Jan - 19 Oct 2026, read once"},
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: {K.SIGNAL_SOURCE}"], model_or_rule=r["name"] +
            " (1 lot each leg)",
            settings="research/hunt/r123_straddle_index_stop/r123cfg.py, r121lib.py", prereg="research/hunt/r123_straddle_index_stop/PREREG.md",
            variants_tried=1,
            results=dict(summary=summ, costs=("R25 liq fills x R54 late multiplier, R50 dated charges per order, stale "
                                               "legs repriced by Black-76; twins: the same rule entered at a random bar 09:30-14:30")),
            holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
            notes="Loop round 3. Failed the gates: stays a note (CLAUDE.md). Nothing to paper or live.")
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(res["tests"]))


if __name__ == "__main__":
    main()
