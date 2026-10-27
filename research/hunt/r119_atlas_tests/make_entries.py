"""Writes research/experiments/entries/r119-<id>.yaml: one per R119 strategy (6), failures included.

    python3 research/hunt/r119_atlas_tests/make_entries.py
"""
from __future__ import annotations

import json

import yaml

import r119cfg as K

ENT = K.ROOT / "research" / "experiments" / "entries"
PATTERN = {"P1": "R118 patterns 1-3 (straddle out-prices the typical move; fat tails; implied > realised early)",
           "P2": "R118 patterns 1-3, from 12:00", "P3": "R118 patterns 3-4 (from 15:00 the straddle under-prices)",
           "P4": "R118 pattern 7 (risk-neutral odds overstate cheap options' chance of ending ITM)",
           "P5": "R118 pattern 5 (expiry days mean-revert minute to minute, VR(30) 0.76)",
           "P6": "R118 pattern 8 (OTM calls reach 10x more often than puts)"}


def inr(x):
    return f"{x:+,.0f}".replace("+-", "-")


def main():
    res = json.loads((K.RESULTS / "results.json").read_text())
    for s, r in res["strats"].items():
        p = r["periods"]
        summ = (f"{r['name']}: " + "; ".join(
            f"{per} {p[per]['trades']} trades, win {p[per]['win']:.1f}%, mean Rs {inr(p[per]['mean'])} (gross "
            f"{inr(p[per]['gross'])}, charges {p[per]['charges']:,.0f}), total Rs {inr(p[per]['total'])}"
            for per in K.PERIODS) + f"; final worst day Rs {inr(p['final']['worst_day'])}, w/o 3 best days Rs "
            f"{inr(p['final']['ex3'])}, at 2x slippage mean Rs {inr(r['final_2x']['mean'])}; twin p {r['twin']['p']:.3f}, "
            f"BH q {r['q_all']:.3g} (m {res['m_all']:,}). {'PASS' if r['gates']['passed'] else 'fail: ' + '; '.join(r['gates']['fails'])}.")
        ent = dict(
            id=f"r119-{s.lower()}", title=f"R119 {s}: {r['name']}", family="calendar-events", date="2026-10-27",
            doc="research/HUNT_R119_ATLAS_TESTS.md",
            hypothesis=f"From {PATTERN[s]}: the fixed rule makes money on expiry days after real charges and slippage, "
                       "in design, walk-forward and final, and beats random twins.",
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 27 Oct 2026 (to 19 Oct 2026): R98 expiry-day "
                          "records, five indices, rolling ATM+-10 1-minute options, index minutes"),
            period={"design": "expiry days to 31 Dec 2023", "walk_forward": "2024-25 (nothing fitted)",
                    "holdout": "final 1 Jan - 19 Oct 2026, read once (NOT pristine: R118 found the patterns on all of "
                               "2021-26; one 2026 day was seen in a smoke test)"},
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=[f"signal source: {r['source']}"], model_or_rule=r["name"],
            settings="research/hunt/r119_atlas_tests/r119cfg.py, r119lib.py",
            prereg="research/hunt/r119_atlas_tests/PREREG.md", variants_tried=1,
            results=dict(summary=summ, costs=("R50 / obuy dated charges per order (BSE rates for SENSEX), R25 'liq' "
                                               "fills (5 bps + 1-4 ticks by volume) x R54 late multiplier, stale legs "
                                               "repriced by Black-76 at their last IV (R103 Am. 2); 1 lot, dated lots")),
            holdout_read=True, verdict="passed" if r["gates"]["passed"] else "rejected",
            notes="Random twins 2,000 (seed 119). Nothing to paper or live.")
        (ENT / f"{ent['id']}.yaml").write_text(yaml.safe_dump(ent, sort_keys=False, allow_unicode=True, width=120))
    print("wrote", len(res["strats"]))


if __name__ == "__main__":
    main()
