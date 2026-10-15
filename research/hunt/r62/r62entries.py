#!/usr/bin/env python3
"""Writes R62's experiment entries (research/experiments/entries/r62-*.yaml): one per primary test (16 variants x 2
books, one BH family of 32) and one per secondary book (16 variants each, context only), from results/design.json and
results/final.json.

    python research/hunt/r62/r62entries.py
"""
from __future__ import annotations

import json

import yaml

import r62cfg as K

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R62_VOLUME_PROFILE_SMC.md"
PREREG = "research/hunt/r62/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake fetched 15 Oct 2026: index_1m and options_rolling_1m opt_<U>_WEEK (R51 "
        "clean store + spikes.py rule, one held contract per trade), equity_1m (stocks and index constituents); read by "
        "research/hunt/r62/r62data.py")
PERIOD = {"design": "20 Nov 2024 - 31 Mar 2026 (walk-forward by quarter)",
          "holdout": "final test 1 Apr - 14 Oct 2026 (indices) / 12 Oct 2026 (stocks), read once 15 Oct 2026"}
COSTS = {"idx": ("R51 engine: R17 'liq' fills (5 bps + 1/2/4 ticks), obuy Costs('dated') charges (STT 0.1% / 0.15% "
                 "from 1 Apr 2026), 1 lot in force; 2x = double bps/ticks and charges"),
         "cash": ("R58 cash charges (MIS intraday / delivery swing) + slippage max(Rs 0.05, 1 bp) a side, stops x2; 1 F&O "
                  "lot of shares (latest lot, split-adjusted scale); 2x = double both")}
INSTR = {"IDX": list(K.IDX), "IDX-b": ["NIFTY", "BANKNIFTY"], "IDX-c": list(K.IDX), "STK": ["F&O stocks"],
         "STK-c": ["F&O stocks"], "STK-sw": ["F&O stocks"]}
SOURCE = {"IDX": "(a) option-chain volume", "IDX-b": "(b) constituents' traded value", "IDX-c": "(c) TPO",
          "STK": "the stock's volume", "STK-c": "(c) TPO", "STK-sw": "the stock's volume"}
TRADE = {"IDX": "the 1-ITM nearest-expiry index option bought (held contract)", "IDX-b": "the 1-ITM index option",
         "IDX-c": "the 1-ITM index option", "STK": "the stock in cash, intraday MIS, long and short",
         "STK-c": "the stock in cash, intraday", "STK-sw": "the stock in delivery, long only, held 1-5 sessions"}
ZONE = {"poc": "pullback to the anchored leg's POC", "lvn": "pullback into the anchored leg's low-volume node"}


def describe(v: str) -> str:
    p = K.parse_variant(v)
    conf = {"poc": "POC overlaps a clean 5-min Order Block", "lvn": "LVN overlaps a 5-min FVG"}[p["zone"]]
    return (f"{ZONE[p['zone']]}; {'with' if p['conf'] else 'no'} confluence ({conf}); "
            f"{'1-hour structure must agree' if p['htf'] else 'no HTF filter'}; target "
            f"{'2R' if p['target'] == '2r' else 'the structural high / low'}; stop beyond the leg's swing extreme")


def fmt(x: dict) -> str:
    if not x.get("trades"):
        return "0 trades"
    return (f"{x['trades']:,} trades, win {x['win']:.0%}, net Rs {x['net']:+,.0f}, avg win / loss Rs {x['avg_win'] or 0:+,.0f}"
            f" / {x['avg_loss'] or 0:+,.0f}, mean R {x['r_mult']:+.2f}, worst day Rs {x['worst_day']:+,.0f}, max DD Rs "
            f"{-x['max_dd']:+,.0f}, months green {x['months_green']}")


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())["results"]
    fin = json.loads((K.RESULTS / "final.json").read_text())["results"]
    n = 0
    for book in K.PRIMARY:
        kind = "idx" if book.startswith("IDX") else "cash"
        for v in K.VARIANTS:
            d, f = des[f"{book}/{v}"], fin[f"{book}/{v}"]
            p = "-" if f["p"] is None else f"{f['p']:.3f}"
            e = dict(
                id=f"r62-{book.lower()}-{v}", title=f"R62 {book} {v}: anchored volume profile + SMC entry ({SOURCE[book]})",
                family="patterns-indicators" if kind == "idx" else "stocks", date="2026-10-15", doc=DOC,
                hypothesis=("Boss's video strategy (15 Oct): an anchored swing-low-to-swing-high volume profile's POC / LVN, "
                            "retested in the trend's direction with SMC confluence, makes money after real costs (final net "
                            "> 0, twin p < 0.05, BH q <= 0.10 over 32, 40+ trades, top-5 < net, design gate)."),
                data_version=DATA, period=PERIOD, instruments=INSTR[book],
                features=["15-min fractal swings, BOS / ChoCH", f"anchored 50-row volume profile ({SOURCE[book]})",
                          "5-min FVG / Order Block", "1-hour structure"],
                model_or_rule=(f"{v}: {describe(v)}; signal source: the underlying ({'index spot' if kind == 'idx' else 'stock'}); "
                               f"traded: {TRADE[book]}; 5-min touch + next-bar confirmation, fills next minute"),
                settings="research/hunt/r62/PREREG.md sections 3-6; research/hunt/r62/r62smc.py", prereg=PREREG,
                variants_tried=1,
                results=dict(summary=(f"Design: {fmt(d['x1'])}; 2x Rs {d['x2']['net']:+,.0f}; design gate "
                                      f"{'yes' if d['design_gate'] else 'no'}. Final: {fmt(f['x1'])}; 2x Rs "
                                      f"{f['x2']['net']:+,.0f}; twin p {p}, BH q {f['q']:.2f}: {f['verdict']}."),
                             costs=COSTS[kind]),
                holdout_read=True, verdict="paper-only" if f["verdict"] == "PASS" else "rejected",
                notes="One of 32 R62 primary tests in one BH family (research/hunt/r62/results/report.md).")
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    for book in ("IDX-b", "IDX-c", "STK-c", "STK-sw"):
        kind = "idx" if book.startswith("IDX") else "cash"
        rows = []
        for v in K.VARIANTS:
            d, f = des[f"{book}/{v}"]["x1"], fin[f"{book}/{v}"]["x1"]
            rows.append(f"{v}: design {d.get('trades', 0)} / Rs {d.get('net', 0):+,.0f}, final {f.get('trades', 0)} / "
                        f"Rs {f.get('net', 0):+,.0f}")
        e = dict(
            id=f"r62-{book.lower()}-context", title=f"R62 {book}: the 16 variants with {SOURCE[book]} (secondary, not gated)",
            family="patterns-indicators" if kind == "idx" else "stocks", date="2026-10-15", doc=DOC,
            hypothesis="Context for R62's primary books: the same 16 rules with another volume source or holding period.",
            data_version=DATA, period=PERIOD, instruments=INSTR[book],
            features=["15-min fractal swings, BOS / ChoCH", f"anchored 50-row volume profile ({SOURCE[book]})",
                      "5-min FVG / Order Block", "1-hour structure"],
            model_or_rule=f"the 16 R62 variants; signal source: the underlying; traded: {TRADE[book]}",
            settings="research/hunt/r62/PREREG.md section 6", prereg=PREREG, variants_tried=16,
            results=dict(summary="; ".join(rows) + ". Context only: never eligible to pass.", costs=COSTS[kind]),
            holdout_read=True, verdict="rejected",
            notes="Secondary book (PREREG section 2 / 6); its own BH over 16 is in results/final.json as q_own.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print("written", n)


if __name__ == "__main__":
    main()
