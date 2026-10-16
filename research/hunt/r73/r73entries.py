"""R73 experiment entries: one YAML per gated test (18 entry rules x 2 exit sets, failures included) plus the labelled
C1 extra, from results/*.json.

    python research/hunt/r73/r73entries.py && python -m research.experiments.validate
"""
from __future__ import annotations

import json

import yaml

import r73cfg as R
import r73rules as P
from r73report import name, pct, rs

ENTRIES = R.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R73_DONCHIAN_TREND_WAIT.md"
PREREG = "research/hunt/r73/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake (index_1m, options_rolling_1m WEEK), R51 Amendment A clean stores, 20 Nov "
        "2024 - 13 Oct 2026")
PERIOD = dict(design="20 Nov 2024 - 31 Mar 2026 (walk-forward quarters Apr 2025 - Mar 2026)",
              holdout=("1 Apr - 13 Oct 2026, read once for R73 (16 Oct); the second computation of the same frozen plan "
                       "(the first run as 'R72' computed it once and its results were lost unrecorded); the window was "
                       "already read for Donchian itself by R51-R72 (weak)"))
UNDS = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
COSTS = ("dated charges, R17 liq fills, next-minute fills, stops on the minute low with gap fills at the worse open, locks "
         "from the next minute, one held contract, 1 lot in force")
EXN = {"P": "exits P (paper today: -15%, A4 ladder + L25, 30 min)", "Q": "exits Q (A4 ladder, no L25)"}


def line(s: dict) -> str:
    if not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades on {s['days']} days, win {pct(s['win'])}, net {rs(s['net'])}, avg win "
            f"{rs(s['avg_win'])} / loss {rs(s['avg_loss'])}, worst day {rs(s['worst_day'])}, max DD {rs(s['max_dd'])}, "
            f"green days {pct(s['green_days'])}")


def main():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    rec = set(v for v in fin["decision"]["recommended"].values() if v)
    n = 0
    for k, t in fin["tests"].items():
        v, x = t["variant"], t["exits"]
        b = t["base"]
        vb = t["vs_base"]
        wc = t.get("wait_cost")
        wait = (f" Cost of waiting on {wc['n']} shared signals: the index moved {wc['points_mean']} points (mean) in the "
                f"break's direction before the entry; the option cost {rs(wc['rs_lot_mean'])} a lot more "
                f"({pct(wc['pct_mean'])}).") if wc else ""
        slip = f" At twice the slippage: final {rs(t['slip2']['net'])}." if t.get("slip2") else ""
        how = ("trend filter at the signal bar's close" if v in P.TRENDS else
               "wait of 1-minute index candles after the signal bar" if v in P.WAITS else
               f"best trend filter + best wait chosen on design ({des['c_pick']['T']} + {des['c_pick']['W']}; "
               "walk-forward quarters re-chosen on the days before each)")
        e = dict(
            id=f"r73-{v.lower()}-{x.lower()}",
            title=f"R73 {name(v, des)} on Donchian 20, {EXN[x]}",
            family="daily-filters" if v in P.TRENDS else "trade-management",
            date="2026-10-16",
            doc=DOC,
            hypothesis=("Boss (16 Oct: \"add trend find for Donchian, or wait for 5 candles [1 min] before buying any "
                        "options ... it could help us with less loss\"): taking Donchian 20's index breakouts only "
                        f"when {P.NAMES.get(v, 'C')} ({how}) loses less than Donchian and beats skipping the same number "
                        "of its trades at random. Gates as R51 (BH over R73's 36 tests), plus random-skip twins."),
            data_version=DATA,
            period=PERIOD,
            instruments=UNDS,
            features=["signal source: the index (Donchian 20 on 5-minute index bars; the filter / wait on the index's "
                      "5-minute and 1-minute candles); no option-chain data, no option chart"],
            model_or_rule=f"Donchian 20 (paper arm) + {name(v, des)}; {EXN[x]}; 1-ITM, entries 09:20-15:10",
            settings="research/hunt/r73/r73rules.py, research/hunt/r73/r73cfg.py EXIT_SETS; PREREG.md",
            prereg=PREREG,
            variants_tried=1 if v != "C" else 72,
            results=dict(
                summary=(f"Design: {line(t['design'])}; walk-forward net {rs(t['wf_net'])} (Donchian: design "
                         f"{rs(b['design_net'])}, walk-forward {rs(b['wf_net'])}). Final: {line(t['final'])} (Donchian "
                         f"{rs(b['final']['net'])} on {b['final']['trades']} trades). Random-entry twin p {t['p']} (BH q "
                         f"{t['q']}); random-skip twin p {t['skip_p']} (BH q {t['skip_q']}). Vs Donchian in the final: "
                         f"skipped {vb['skipped']} trades: {vb['losers_avoided']} losers avoided "
                         f"({rs(vb['losers_avoided_rs'])}), {vb['winners_missed']} winners missed "
                         f"({rs(vb['winners_missed_rs'])}; {vb['big_missed']} closed +30% or more, "
                         f"{rs(vb['big_missed_rs'])}); {vb['extra']} trades Donchian did not take "
                         f"({rs(vb['extra_net'])}).{wait} Verdict: {t['verdict']} "
                         f"({', '.join(t['fails']) or 'all gates'}).{slip}"),
                costs=COSTS),
            holdout_read=True,
            verdict="paper-only" if k in rec else "rejected",
            notes=("Signal on the underlying (the index); research only, nothing changes paper or live."
                   + (" Recommended for the Donchian PAPER arm only if Boss switches it on." if k in rec else "")
                   + (" Passed R51's gates but not the random-skip test / not better than Donchian everywhere, so not "
                      "recommended." if t["verdict"] == "PASS" and k not in rec else "")
                   + (" Information only: a loss-reduction trade-off (smaller final drawdown and worst day than "
                      "Donchian, lower net)." if k in fin["decision"]["tradeoffs"] else "")),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    ex, exd = fin["extra_c1"], des["extra_c1"]
    e = dict(
        id="r73-extra-c1",
        title=f"R73 extra (not gated): {ex['variant']} on top of R72's C1 Breadth Donchian, exits P",
        family="daily-filters",
        date="2026-10-16",
        doc=DOC,
        hypothesis=("Does the best R73 filter (chosen on design) add anything on top of R72's Breadth Donchian (C1)? "
                    "Labelled extra, not part of R73's 36 gated tests."),
        data_version=DATA,
        period=PERIOD,
        instruments=UNDS,
        features=["signal source: the five indices (C1) and the index (the filter)"],
        model_or_rule=f"R72 C1 (4 of 5 indices break on the same bar) + {name(ex['variant'], des)}; exits P",
        settings="research/hunt/r73/r73study.py extra_c1",
        prereg=PREREG,
        variants_tried=1,
        results=dict(summary=(f"Design: C1 {line(exd['c1'])}; C1 + filter {line(exd['c1_plus'])}. Final: C1 "
                              f"{line(ex['c1'])}; C1 + filter {line(ex['c1_plus'])}; random-skip twin p {ex['skip_p']}."),
                     costs=COSTS),
        holdout_read=True,
        verdict="inconclusive",
        notes="Not gated (one labelled look, no BH); information for Boss only, nothing changes.",
    )
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(n + 1, "entries")


if __name__ == "__main__":
    main()
