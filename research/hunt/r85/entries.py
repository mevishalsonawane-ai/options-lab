"""R85 experiment-log entries (research/experiments/entries/r85-*.yaml), written from the result files.

    python3 -m research.hunt.r85.entries        # before the final: design numbers; after it: final numbers too
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

from research.hunt.r85 import part2 as P2
from research.hunt.r85 import strategy as S

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R85_MCX_CANDLES.md"
PREREG = "research/hunt/r85/PREREG.md"
DATA = ("Drive lake options_rolling_1m opt_<SYM>_MONTH_2025/2026 (MCX near future = spot, option minutes ATM-3..+3), "
        "fut_daily_continuous; 5 Aug 2025 - 13 Oct 2026 (/dev/shm, not committed)")
COSTS = ("McxCosts option charges + premium half-spread CRUDEOIL 0.30/0.60%, NATURALGAS 0.45/0.90%, GOLDM/SILVERM "
         "0.30/0.60% (from/before 17:00 IST), entry and exit")


def rs(x) -> str:
    return f"Rs {x:+,.0f}"


def part1_entry() -> dict:
    s = json.loads((S.RESULTS / "part1_summary.json").read_text())
    return {
        "id": "r85-part1", "title": "R85 Part 1: MCX candle anatomy (shape, colour, context -> next 1-10 candles)",
        "family": "mcx-crypto", "date": "2026-10-19", "doc": DOC,
        "hypothesis": "Some MCX candle shapes or contexts on the underlying future predict the next 3 candles' "
                      "direction better than a random time of the same session (design only).",
        "data_version": DATA, "period": {"design": "2025-08-05..2026-03-31 (day candles 2010-2022)"},
        "instruments": ["GOLD", "GOLDM", "SILVER", "SILVERM", "CRUDEOIL", "NATURALGAS"],
        "features": ["colour, body/wick %, range/ATR, close location, 11 shapes", "trend, session mean distance, day "
                     "high/low, previous-day levels, volatility regime, option activity, session bucket, events, "
                     "expiry", "signal source: the MCX future (close-only candles)"],
        "model_or_rule": "cell = instrument x timeframe x condition x colour x session; effect = mean signed 3-candle "
                         "move minus the same-session baseline; day-clustered z; BH",
        "settings": "research/hunt/r85/PREREG.md section 3; NOTES.md D1 (colour-matched baseline)",
        "prereg": PREREG, "variants_tried": None,
        "results": {"summary": f"{s['tested_primary']} intraday cells tested at h = 3: {s['q_lt_0.10']} pass BH q < "
                               f"0.10 on the pre-registered baseline, {s['q_lt_0.10_and_colour_matched_q_lt_0.10']} "
                               f"also on the drift-neutral colour-matched baseline, 4 of those move more than an "
                               f"option buyer's round trip. Day candles: {s['day_tested']} tested, "
                               f"{s['day_q_lt_0.10']} q < 0.10. No 1-minute cell beats the option cost.",
                    "costs": COSTS},
        "holdout_read": None, "verdict": "not-a-test",
        "notes": "Descriptive; the 4 cells that beat costs became R1-R4 (entries r85-r1..r85-r4).",
    }


def rule_entry(rule: str, dz: dict, fz: dict | None) -> dict:
    d = dz[rule]
    ds = d["design"][d["frozen"]]
    summ = (f"Design (5 Aug 2025 - 31 Mar 2026), frozen {d['frozen']}: {ds['trades']} trades, net {rs(ds['net'])} "
            f"(gross {rs(ds.get('gross', 0))}, costs {rs(-ds.get('cost', 0))}; futures move over the same windows "
            f"{rs(ds.get('fut_gross', 0))}, diagnostic only); walk-forward (Nov 2025 - Mar 2026) "
            f"{rs(d['walk_forward']['wf_net'])}; design twin p {d['design_twin_p']:.3f}.")
    verdict, read = "inconclusive", False
    if fz is not None:
        f = fz[rule]
        fs = f["final"]
        failed = [k for k, v in f["gates"].items() if not v]
        summ += (f" Final ({f['period'][0]}..{f['period'][1]}, read once): {fs['trades']} trades, net {rs(fs['net'])}, "
                 f"win {100 * (fs['win_rate'] or 0):.0f}%, max DD {rs(-fs['max_dd'])}, worst day {rs(fs['worst_day'])}, "
                 f"months < -Rs 13k: {fs.get('months_below_13k', 0)}, without top 5 {rs(fs.get('top5_removed', 0))}; "
                 f"twin p {f['final_twin_p']:.3f}, BH q {f['bh_q']:.3f} (4 rules). "
                 + ("PASS." if f["verdict"] == "PASS" else "Gates failed: " + ", ".join(failed) + "."))
        verdict = "paper-only" if f["verdict"] == "PASS" else "rejected"
        read = True
    return {
        "id": f"r85-{rule.lower()}", "title": f"R85 {rule}: {P2.RULES[rule]['desc']}", "family": "mcx-crypto",
        "date": "2026-10-19", "doc": DOC,
        "hypothesis": f"{P2.RULES[rule]['desc']} (Part 1 design cell, q < 0.10 on both baselines) earns after strict "
                      f"MCX option costs, 1 lot, in walk-forward and the final.",
        "data_version": DATA,
        "period": {"design": "2025-08-05..2026-03-31", "holdout": "2026-04-01..2026-10-13 (final, read once)"},
        "instruments": [P2.RULES[rule]["inst"], S.OPTION_BOOK[P2.RULES[rule]["inst"]][0] + " options"],
        "features": ["signal source: the MCX future (close-only candles), option activity as context (R1)"],
        "model_or_rule": f"buy the 1-ITM {'call' if P2.RULES[rule]['side'] > 0 else 'put'} at the next printed minute; "
                         f"stop premium -X% or the future back through the candle's far end; lock at +L%, trail; "
                         f"hold 3 candles",
        "settings": "research/hunt/r85/PREREG.md amendment A1", "prereg": PREREG, "variants_tried": len(P2.VARIANTS),
        "results": {"summary": summ, "costs": COSTS}, "holdout_read": read, "verdict": verdict,
    }


def write():
    dz = json.loads((S.RESULTS / "part2_design.json").read_text())
    fp = S.RESULTS / "part2_final.json"
    fz = json.loads(fp.read_text()) if fp.exists() else None
    ents = [part1_entry()] + [rule_entry(r, dz, fz) for r in P2.RULES]
    for e in ents:
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=True))
    print("wrote", [e["id"] for e in ents])


if __name__ == "__main__":
    write()
