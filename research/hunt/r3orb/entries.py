#!/usr/bin/env python3
"""Writes R3's experiment entries (research/experiments/entries/r3-*.yaml) from results/design.json + holdout.json.

    python3 research/hunt/r3orb/entries.py
"""
from __future__ import annotations

import json

import yaml

import r3cfg as K
import r3engine as E

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R3_ORB_FAMILY.md"
PREREG = "research/hunt/r3orb/PREREG.md"
DATA = ("Dhan lake data-20261014 (options_rolling_1m opt_<UND>_WEEK = nearest expiry on or after the day, ATM+-10; "
        "index_1m incl. INDIAVIX) read by research/hunt/r3orb/r3data.py")
PERIOD = {"design": "BANKNIFTY 2021-09-03..2026-10-12 (1,255 sessions) + NIFTY 2021-08-02..2026-10-12 (1,284)",
          "holdout": "out-of-instrument: FINNIFTY 2021-09-03.. (1,254), MIDCPNIFTY 2022-02-21.. (906), SENSEX 2023-05-15.. "
                     "(845), all to 2026-10-12; read once 14 Oct 2026"}
COSTS = "app fees at today's rates (BSE for SENSEX); liq fills (5 bps + 1-4 ticks), stops 10 bps + 2x ticks; 1 lot in force"
WHAT = {
    "F": "the baseline + skip the worst tercile of one point-in-time feature (Part B)",
    "E": "no fixed target, ladder kept, +Rs 1,000 -> keep +Rs 500 a lot, Rs 500 give-back trail from +Rs 1,000, 45-min time stop if the peak never reached +25% of the target",
    "S": "1 strike in the money, at most 1 entry a side a day",
    "C": "F + E + S together",
}


def fmt(r):
    a = r["all"]
    return (f"{a['trades']:,} trades, net Rs {a['net']:+,.0f} (Rs {a['per_day']:+,.0f} a day), win rate {a['win_rate']:.0%}, "
            f"worst day Rs {a['worst_day']:+,.0f}, max drawdown Rs {a['max_dd']:+,.0f}")


def write(e):
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())["results"]
    h = json.loads((K.RESULTS / "holdout.json").read_text())
    hr = h["results"]
    pb = json.loads((K.RESULTS / "partb.json").read_text())
    base = dict(family="orb", date="2026-10-14", doc=DOC, data_version=DATA, period=PERIOD, prereg=PREREG)
    for a in E.ARMS:
        b = d[f"base-{a}"]
        write(dict(id=f"r3-rebuild-{a.replace('_', '-')}", title=f"R3 rebuild: {E.NAMES[a]} exactly as the app has it, on the lake",
                   **base, hypothesis="Not a test: the arm's current rules rebuilt on the full lake, per year and index.",
                   instruments=["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"], features=["1-minute index", "opening range 09:15-10:04"],
                   model_or_rule=f"{E.NAMES[a]} (OrbRules / SweepRules / RangeFadeRules), PREREG A2",
                   settings="research/hunt/r3orb/PREREG.md A2", variants_tried=None,
                   results=dict(summary=f"Design: {fmt(b)}; years > 0: {sum(v[0] > 0 for v in b['years'].values())} of {len(b['years'])}. "
                                        f"Holdout indices: {fmt(hr[f'base-{a}'])}.", costs=COSTS),
                   holdout_read=True, verdict="not-a-test",
                   notes="Baseline for the R3 variants. Matches R14 / R12 within a few percent (results/rebuild.md)."))
    for a in E.ARMS:
        for x in "FESC":
            i = f"r3-{a}-{x}"
            r, q = d[i], hr[i]
            p = pb[a]["pick"]
            filt = f" Filter: skip {p['feature']} {p['worst']} ({p['skip'][0]:.4g} .. {p['skip'][1]:.4g})." if x in "FC" else ""
            ok = r.get("design_pass") and q.get("holdout_pass")
            write(dict(id=i.replace("_", "-").lower(), title=f"R3 {E.NAMES[a]} variant {x}: {WHAT[x]}", **base,
                       hypothesis=f"{E.NAMES[a]} with {WHAT[x]} makes money net of the app's fees and fills on unseen data (PREREG A8 gates D1-D4, H1-H5).",
                       instruments=["BANKNIFTY", "NIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                       features=["1-minute index", "opening range", "VIX", "gap", "range width / ATR14", "signal time", "expiry day", "TWAP distance"],
                       model_or_rule=f"{E.NAMES[a]}: {WHAT[x]}.{filt}", settings="research/hunt/r3orb/PREREG.md A6 and Part B",
                       variants_tried=16,
                       results=dict(summary=(f"Design: {fmt(r)}; per year {[v[0] for v in r['years'].values()]}; gates D1-D4 "
                                             f"{''.join('y' if r['gates'][k] else 'n' for k in ('D1', 'D2', 'D3', 'D4'))}. Holdout: {fmt(q)}; "
                                             f"t p {q['t_p']:.3f}, twin mean Rs {q['twin_mean']:+,.0f} p {q['twin_p']:.3f}, BH q {q['q_t']:.2f} / {q['q_twin']:.2f} "
                                             f"(m {h['m']}); gates H1-H5 {''.join('y' if q['gates'][k] else 'n' for k in ('H1', 'H2', 'H3', 'H4', 'H5'))}. "
                                             f"{'PASS' if ok else 'FAIL'}."), costs=COSTS),
                       holdout_read=True, verdict="paper-only" if ok else "rejected"))
    write(dict(id="r3-anatomy", title="R3 anatomy: when the ORB family wins and loses (design indices)", **base,
               hypothesis="Descriptive: which day types, VIX, gap, range width, expiry, time, index and re-entries separate wins from losses.",
               instruments=["BANKNIFTY", "NIFTY"], features=["day type (hindsight)", "VIX", "VIX change", "gap", "width / ATR14", "signal time", "expiry", "TWAP distance"],
               model_or_rule="terciles and buckets of each arm's baseline trades", settings="research/hunt/r3orb/study.py anatomy",
               variants_tried=None,
               results=dict(summary="No point-in-time split makes any arm positive: the best third of every feature still loses for every arm "
                                    "(results/anatomy.md). Even with hindsight, ORB loses on days that trended its way (Rs -52 a trade, 1,094 trades); "
                                    "Sweep / Fade win only on trend days traded with the trend (known only after the close).", costs=COSTS),
               holdout_read=False, verdict="not-a-test"))
    print("written")


if __name__ == "__main__":
    main()
