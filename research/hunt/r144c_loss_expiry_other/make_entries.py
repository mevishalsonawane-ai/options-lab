"""Write research/experiments/entries/r144c-*.yaml from results/ (one per filter test, one descriptive entry per arm).

    python research/hunt/r144c_loss_expiry_other/make_entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ENT = ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R144C_LOSS_EXPIRY_OTHER.md"
PRE = "research/hunt/r144c_loss_expiry_other/PREREG.md"
DATA = "Drive lake copied 1 Nov 2026 (index_1m, options_rolling_1m, equity_1m; to 19 Oct 2026)"
LABEL = {"FT3": "FT3 near-ATM morning", "HERO": "Expiry Hero", "HEROTKT": "Hero Rs 1,000 ticket", "NIGHT": "Night (R3)",
         "VIXDIV": "VIX divergence", "FLOW": "IraThinker Flow"}
INSTR = {"FT3": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], "HERO": ["NIFTY"], "HEROTKT": ["NIFTY", "SENSEX"],
         "NIGHT": ["NIFTY", "BANKNIFTY"], "VIXDIV": ["NIFTY", "BANKNIFTY", "INDIAVIX"],
         "FLOW": ["NIFTY", "BANKNIFTY", "FINNIFTY", "INDIAVIX"]}
SOURCE = {"FT3": "option-price pick (labelled; clock + the option's own price, IV, straddle distance)",
          "HERO": "option-price (ATM straddle premium) + NIFTY's 15-min move", "HEROTKT": "clock; strike by the option's own price",
          "NIGHT": "the index (close location, move, breadth) + day-end OI build-up", "VIXDIV": "the index and India VIX",
          "FLOW": "the index's opening-range break + India VIX's break"}
COSTS = {"FT3": "R98 charges and LIMIT fill, Rs 5,000 a ticket", "HERO": "R98 charges, Rs 5,000 a ticket, R98-style LIMIT fill",
         "HEROTKT": "R54 / R55 fills and charges, 1 lot", "NIGHT": "R27 fills, app charges, 0.16% half-spread, 1 lot",
         "VIXDIV": "R1 costs: app charges, 0.16% half-spread, 1 lot", "FLOW": "R98 charges, 0.16% half-spread, 1 lot"}


def rs(x):
    return "n/a" if x is None else f"Rs {x:,.0f}"


def pct(x):
    return "n/a" if x is None else f"{100 * x:.0f}%"


def base(eid, title, arm):
    return dict(id=eid, title=title, family="trade-management", date="2026-11-01", doc=DOC, data_version=DATA,
                period=dict(design="2024-11-20..2026-03-31 (filters chosen here only)",
                            holdout="2026-04-01..2026-10-19 (final) and 2022-12-01..2024-11-19 (unseen two years); read once"),
                instruments=INSTR[arm], prereg=PRE, holdout_read=True, features=[f"signal source: {SOURCE[arm]}"])


def main():
    tests = json.loads((HERE / "results" / "tests.json").read_text())
    anat = json.loads((HERE / "results" / "anatomy.json").read_text())
    n_tests = len(tests)
    for t in tests:
        a, f = t["arm"], t["filter"]
        what = f"{f['feature']} {'= ' + f['value'] if f['kind'] == 'cat' else ('< ' if f['kind'] == 'low' else '> ') + str(f['cut'])}"
        e = base(t["id"], f"R144c {LABEL[a]}: skip trades with {what}", a)
        parts = []
        for p in ("unseen", "design", "final"):
            b, r = t["base"][p], t["after"][p]
            parts.append(f"{p} {b['trades']}->{r['trades']} trades, win {pct(b['win'])}->{pct(r['win'])} (65% rule: below), "
                         f"net {rs(b['net'])}->{rs(r['net'])}, losing days {rs(b['loss_sum'])}->{rs(r['loss_sum'])}")
        e.update(hypothesis=f"skipping trades with {what} (chosen on design only) loses less on the final months and the unseen years",
                 model_or_rule="research/hunt/r144c_loss_expiry_other/study.py", settings=f"frozen cut; {COSTS[a]}",
                 variants_tried=n_tests, verdict="passed" if t["passed"] else "rejected",
                 results=dict(summary="; ".join(parts) + f". Twin p {t['twin_p']:.3g}, BH q {t['q']:.3g}. "
                              + ("Passed every gate." if t["passed"] else "Fails: " + "; ".join(t["fails"])),
                              costs=COSTS[a]))
        (ENT / f"{t['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    for a, x in anat.items():
        eid = f"r144c-{a.lower()}-describe"
        e = base(eid, f"R144c {LABEL[a]}: every losing trade, patterns (descriptive)", a)
        ps = x["periods"]
        summ = "; ".join(f"{p} {ps[p]['trades']} trades, {pct(1 - ps[p]['win']) if ps[p]['win'] is not None else 'n/a'} lose, "
                         f"net {rs(ps[p]['net'])}" for p in ("unseen", "design", "final"))
        e.update(hypothesis=f"{LABEL[a]}'s losing trades share patterns at entry that hold in every period",
                 model_or_rule="research/hunt/r144c_loss_expiry_other/study.py",
                 settings="descriptive; steady = loser rate above the arm and avg < 0 in unseen, design and final (>= 30 each)",
                 variants_tried=None, verdict="not-a-test",
                 results=dict(summary=f"descriptive, no rule adopted. {summ}. See {DOC}.", costs=COSTS[a]))
        (ENT / f"{eid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120))
    print("written", n_tests + len(anat))


if __name__ == "__main__":
    main()
