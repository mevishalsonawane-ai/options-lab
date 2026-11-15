"""Writes research/experiments/entries/r182-*.yaml: one entry per pre-registered test (4 Part A, 3 Part B), from
results/results.json. python research/hunt/r182_delta_study/r182entries.py. Plain-English notes, 15 Nov 2026."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[1] / "experiments" / "entries"
DOC = "research/hunt/r182_delta_study/results/report.md"
PREREG = "research/hunt/r182_delta_study/PREREG.md"
DATA = ("phone recordings exported 15 Nov 2026: 1-second order flow 15 Oct - 13 Nov 2026 (20 days, futures of NIFTY, "
        "BANKNIFTY, FINNIFTY, MIDCPNIFTY); minute order flow 15-28 Oct (cross-check); daily diagnostics 19 Oct - 14 Nov "
        "(paper orders only); outside git")


def rs(x):
    return "-" if x is None else (f"Rs -{abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}")


def main():
    r = json.loads((HERE / "results" / "results.json").read_text())
    tests = {t["id"]: t for t in r["tests"]}
    n = 0
    for u, a in r["part_a"].items():
        tid = f"r182-a-{u.lower()}"
        t, p, sm = tests[tid], a["primary"], a["same_minute"]
        verdict = "rejected" if not (p["lo"] > 0 or p["hi"] < 0) else "inconclusive"
        e = dict(
            id=tid, title=f"R182 Part A {u}: does the 5-minute futures order-flow delta predict the next 15 minutes' futures return?",
            family="flow-oi", date="2026-11-15", doc=DOC,
            hypothesis="A positive (negative) 5-minute tick-rule delta on the near future is followed by a rise (fall) over the "
                       "next 15 minutes: rank correlation > 0, day-block bootstrap p.",
            data_version=DATA,
            period=dict(design="none: one pre-registered test on all 20 days (no split; descriptive grid alongside)", holdout="none"),
            instruments=[u],
            features=["signal source: the index near future's 1-second tick-rule delta (buy_vol - sell_vol, the app's "
                      "OrderFlow recorder), scaled by the day's mean |1-minute delta| so far; target: the future's own log return"],
            model_or_rule="Spearman(D5 at the minute close, log return close -> close + 15 minutes), minute closes 09:30-15:14",
            settings=PREREG + " section 4; r182lib.py", prereg=PREREG, variants_tried=1,
            results=dict(summary=(f"{p['n']:,} minutes over {p['n_days']} days: rho {p['r']:+.3f} (95% day-bootstrap "
                                  f"{p['lo']:+.3f}..{p['hi']:+.3f}), p {p['p']:.3f} (circular-shift p {p['p_shift']:.3f}), BH q "
                                  f"{t['q']:.3f} (m {r['m_total']}). Same-minute delta vs price rho {sm['r']:+.3f} "
                                  f"({sm['lo']:+.3f}..{sm['hi']:+.3f})."),
                         costs="none (a correlation with the futures return, no trade)"),
            holdout_read=None, verdict=verdict,
            notes="Exploratory sample (20 days). Delta moves WITH price in the same minute; the test is whether it leads it.")
        (ENTRIES / f"{tid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        n += 1
    names = {"Liquidity": "Liquidity 15+5", "R51": "R51 Donchian 20", "R72": "R72 Breadth Donchian"}
    for arm in ("Liquidity", "R51", "R72"):
        tid = f"r182-b-{arm.lower()}"
        t, b = tests[tid], r["part_b"][arm]
        P = b["P"]
        tw = P["twins"]
        p = "-" if tw.get("p") is None else f"{tw['p']:.3f}"
        e = dict(
            id=tid, title=f"R182 Part B {names[arm]}: skip the entry when the 5-minute futures delta opposes the trade",
            family="flow-oi", date="2026-11-15", doc=DOC,
            hypothesis="Skipping the arm's entries whose underlying future's 5-minute order-flow delta opposes the option's "
                       "side (CE long, PE short) keeps the better trades: kept net above random skips of the same number.",
            data_version=DATA,
            period=dict(design="none: the arm's real paper trades 21 Oct - 13 Nov 2026, one pre-registered rule", holdout="none"),
            instruments=sorted(r["counts"][arm]["per_und"]),
            features=["signal source: the underlying index near future's 1-second tick-rule delta in the 5 minutes before "
                      "the entry second, aligned to the trade's side; the arm's own entries and exits unchanged"],
            model_or_rule="skip when side x (5-minute futures delta) < 0; no label (no flow, SENSEX, zero delta) = kept",
            settings=PREREG + " section 5; r182lib.py", prereg=PREREG, variants_tried=4,
            results=dict(summary=(f"{b['all']['n']} paper trades ({b['labelled']['n']} labelled): all {rs(b['all']['net'])}, win "
                                  f"{b['all']['win']:.0f}%. Rule skips {P['skipped']['n']} (win "
                                  f"{(P['skipped']['win'] if P['skipped']['win'] is not None else 0):.0f}%, {rs(P['skipped']['net'])}), "
                                  f"keeps {P['kept']['n']} (win {(P['kept']['win'] or 0):.0f}%, {rs(P['kept']['net'])}); "
                                  f"random-skip twins p {p}, BH q {t['q']:.3f} (m {r['m_total']}). Secondary V1-V3 descriptive."),
                         costs="the app's paper fills (its own slippage) + Costs('dated') charges per order"),
            holdout_read=None, verdict="rejected" if (tw.get("p") is None or tw["p"] > 0.20) else "inconclusive",
            notes="Exploratory: 179 trades over 16 trading days cannot pass the gates (>= 40 holdout trades, every period, BH). "
                  "R51 and R72 share many entries (same option, same second).")
        (ENTRIES / f"{tid}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
        n += 1
    print("wrote", n)


if __name__ == "__main__":
    main()
