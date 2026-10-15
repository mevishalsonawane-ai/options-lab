#!/usr/bin/env python3
"""Writes R62b's experiment entries (research/experiments/entries/r62b-*.yaml): one per gated variant (16, one BH
family) and one for the fib 0.33 / 0.50 sensitivity runs (information only), from results/design.json and
results/final.json.

    python research/hunt/r62b/r62bentries.py
"""
from __future__ import annotations

import json

import yaml

import r62bcfg as K

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R62B_MSB_ORDER_BLOCK.md"
PREREG = "research/hunt/r62b/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake fetched 15 Oct 2026: index_1m and options_rolling_1m opt_<U>_WEEK 2024-2026 "
        "(R51 Amendment A clean store = R47 foreign / stray-row filter, plus research/data/spikes.py rule; one held "
        "contract per trade); read by research/hunt/r62b/r62bdata.py")
PERIOD = {"design": "20 Nov 2024 - 31 Mar 2026 (walk-forward by quarter)",
          "holdout": "final test 1 Apr - 14 Oct 2026, read once 15 Oct 2026 (not pristine: earlier studies saw these "
                     "sessions, never with these rules)"}
COSTS = ("R51 engine: R17 'liq' fills (buy +5 bps + 1/2/4 ticks; market sells -5 bps), obuy Costs('dated') charges (STT "
         "from 1 Apr 2026; BSE rates for SENSEX), next-minute fills, 1 lot in force; 2x = double bps / ticks and charges")
SIGNAL = ("signal source: the INDEX spot 1-minute bars (EmreKb MSB-OB port on 5- / 15-minute bars, 1-hour trend "
          "filter); the 1-ITM nearest-expiry option is only what is bought; no option-chain input")


def describe(v: str) -> str:
    p = K.parse_variant(v)
    conf = {"tch": "first touch of the zone (buy next minute)",
            "rej": "first touch confirmed by a signal-bar close back out of the zone (or the next bar, green / red)"}
    return (f"EmreKb 'Market Structure Break & Order Block' (zigzag 9, fib 0.273) on {p['tf']}-minute index bars: "
            f"after an MSB, {conf[p['conf']]} of its fresh order-block zone; "
            f"{'1-hour MSB state must agree' if p['htf'] else 'no trend filter'}; stop beyond the swing, target the "
            f"opposing swing{' only if reward:risk >= 1.5' if p['target'] == 'rr' else ''}; time exit 12 bars; "
            f"square-off 15:15 (15:05 expiry); buy the 1-ITM option")


def fmt(x: dict) -> str:
    if not x.get("trades"):
        return "0 trades"
    return (f"{x['trades']:,} trades, win {x['win']:.0%}, net Rs {x['net']:+,.0f}, avg win / loss Rs {x['avg_win'] or 0:+,.0f}"
            f" / {x['avg_loss'] or 0:+,.0f}, mean realised R {x['r_mult']:+.2f}, max DD Rs {x['max_dd']:,.0f}, "
            f"months green {x['months_green']}")


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())["results"]
    fin = json.loads((K.RESULTS / "final.json").read_text())["results"]
    n = 0
    for v in K.VARIANTS:
        d, f = des[v], fin[v]
        e = dict(
            id=f"r62b-{v}", title=f"R62b {v}: EmreKb MSB & Order Block pullback on the index, 1-ITM option",
            family="patterns-indicators", date="2026-10-15", doc=DOC,
            hypothesis=("The video's MSB & Order Block pullback (claimed 75-80% win rate) makes money after real costs "
                        "(gates: final net > 0, random-entry twin p < 0.05, BH q <= 0.10 over 16 variants, 40+ trades, "
                        "top-5 < net, design positive in >= 3 of 5 quarters)."),
            data_version=DATA, period=PERIOD, instruments=list(K.IDX), features=[SIGNAL],
            model_or_rule=describe(v), settings="research/hunt/r62b/PREREG.md sections 1-5; r62bcfg.py",
            prereg=PREREG, variants_tried=16,
            results=dict(summary=(f"Design: {fmt(d['x1'])}; 2x costs Rs {d['x2']['net']:+,.0f}; design gate "
                                  f"{d['design_gate']}. Final (read once): {fmt(f['x1'])}; 2x costs Rs "
                                  f"{f['x2']['net']:+,.0f}; twin p {f['p']}, BH q {f['q']}. Verdict: {f['verdict']}. "
                                  f"Claimed win rate 75-80%."), costs=COSTS),
            holdout_read=True, verdict="adopted" if f["verdict"] == "PASS" else "rejected",
            notes="Research only. Pine source obtained (EmreKb, MPL-2.0) and ported line by line; signal on the "
                  "underlying. One of 16 R62b tests in one BH family (research/hunt/r62b/results/report.md).")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    parts = []
    for fib in K.FIB_SENS:
        for part, R in (("design", des), ("final", fin)):
            xs = [R[f"{v}@{fib}"]["x1"] for v in K.VARIANTS]
            pos = sum(1 for x in xs if x.get("net", 0) > 0)
            wins = [x["win"] for x in xs if x.get("trades")]
            parts.append(f"fib {fib} {part}: {pos} of 16 variants net > 0; win rates {min(wins):.0%}-{max(wins):.0%}; "
                         f"total trades {sum(x.get('trades', 0) for x in xs):,}")
    e = dict(
        id="r62b-fib-sensitivity", title="R62b sensitivity: the 16 variants at fib 0.33 (script default) and 0.50",
        family="patterns-indicators", date="2026-10-15", doc=DOC,
        hypothesis="Information only: does the fib confirmation factor change the picture? (never gated)",
        data_version=DATA, period=PERIOD, instruments=list(K.IDX), features=[SIGNAL],
        model_or_rule="the 16 R62b variants with fib_factor 0.33 and 0.50", settings="research/hunt/r62b/PREREG.md 5",
        prereg=PREREG, variants_tried=32,
        results=dict(summary="; ".join(parts) + ". Not gated, no twins.", costs=COSTS),
        holdout_read=True, verdict="rejected",
        notes="Context for the fib choice only; does not enter the BH family.")
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(n + 1, "entries")


if __name__ == "__main__":
    main()
