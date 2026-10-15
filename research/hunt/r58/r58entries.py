#!/usr/bin/env python3
"""Writes R58's experiment entries (research/experiments/entries/r58-*.yaml), one per rule, from results/design.json
and results/final.json.

    python3 research/hunt/r58/r58entries.py
"""
from __future__ import annotations

import json

import yaml

import r58cfg as K
import r58rules as RR

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R58_STOCK_OPTIONS_TOP10.md"
PREREG = "research/hunt/r58/PREREG.md"
DATA = ("Drive lake gdrive:IraAlgo-data-lake fetched 15 Oct 2026: stock_options_rolling_1m stkopt_<SYM>_MONTH (one held "
        "contract per trade), equity_1m, equity_daily, index_1m NIFTY, nse_bhavcopy (lots, expiries, universe); read by "
        "research/hunt/r58/r58data.py")
PERIOD = {"design": "20 Nov 2024 - 31 Mar 2026 (336 sessions), 10 stocks",
          "holdout": "final test 1 Apr - 12 Oct 2026 (132 sessions), read once 15 Oct 2026; stock options not read before"}
COSTS = ("obuy Costs('dated') option charges (STT 0.1% to 31 Mar 2026, 0.15% from 1 Apr 2026), per-stock fitted spread "
         "(R17 ticks or the design Roll half-spread, whichever is larger, + 5/10 bps); 2x reported; 1 lot of the contract's "
         "own lot on the date (bhavcopy / traded volumes)")
FAM = {"F1": "opening-range breakout on the stock", "F2": "relative strength of the stock vs NIFTY",
       "F3": "gap-and-go / gap-fade on the stock", "F4": "volume surge / VWAP trend on the stock",
       "F5": "daily Donchian 20 / 10-day momentum on the stock (swing)",
       "F6": "the closest index rules (R51 Donchian-20 5-min, R52 H37-10) on the stock"}
FEAT = {"F1": ["stock 1-minute bars", "opening range"], "F2": ["stock 1-minute / daily", "NIFTY 1-minute"],
        "F3": ["stock daily close", "first 15-minute bar"], "F4": ["5-minute volume vs 20-session slot average", "VWAP"],
        "F5": ["stock daily bars"], "F6": ["stock 5-minute bars", "previous-day TPO value area"]}


def fmt(x: dict, final: bool) -> str:
    s = (f"{x['trades']:,} trades, win {x['win_rate']:.0%}, net Rs {x['net']:+,.0f} (Rs {x['per_trade']:+,.0f} a trade), "
         f"avg win / loss Rs {x['avg_win']:+,.0f} / {x['avg_loss']:+,.0f}, worst day Rs {x['worst_day']:+,.0f}, "
         f"max DD Rs {x['max_dd']:+,.0f}, months green {x['months_green']}, premium x lot median Rs {x['capital_median']:,.0f}")
    if final:
        s += f"; twin p {x['twin_p']:.3f}, BH q {x['q']:.2f}: {x['verdict']}"
    return s


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    n = 0
    for r in RR.rules():
        d, f = des["results"][r.id], fin["results"][r.id]
        v = f["x1"]["verdict"]
        stocks = "; ".join(f"{s} {t} / {net:+,}" for s, (t, net) in f["x1"]["stocks"].items())
        e = dict(
            id=f"r58-{r.id}", title=f"R58 {r.id}: {FAM[r.family]}, ATM{' 1-ITM' if r.itm else ''} stock option bought",
            family="stocks", date="2026-10-15", doc=DOC,
            hypothesis=("Buying the nearest-monthly ATM option of a top-10 NIFTY 50 stock on this stock signal makes money "
                        "after real costs on one held contract (final net > 0, twin p < 0.05, BH q <= 0.10 over 38, 40+ "
                        "trades, top-5 < net, design gate)."),
            data_version=DATA, period=PERIOD, instruments=list(RR.stocks_for(r)), features=FEAT[r.family],
            model_or_rule=(f"{r.id} ({r.kind}, {r.p}, hold {r.hold} session(s)); signal source: the stock "
                           "(NIFTY for relative strength); band exit at 1.25 strike steps; out by 15:15 on T-3 before expiry"),
            settings="research/hunt/r58/PREREG.md section 5; research/hunt/r58/r58rules.py", prereg=PREREG,
            variants_tried=1,
            results=dict(summary=(f"Design: {fmt(d['x1'], False)}; 2x Rs {d['x2']['net']:+,.0f}; design gate "
                                  f"{'yes' if d['design_gate'] else 'no'}. Final: {fmt(f['x1'], True)}; 2x Rs "
                                  f"{f['x2']['net']:+,.0f}; stock in cash (same minutes) Rs {f['cash_x1']['net']:+,.0f} "
                                  f"on {f['cash_x1']['trades']} trades. Final by stock: {stocks}."), costs=COSTS),
            holdout_read=True, verdict="paper-only" if v.startswith("PASS") else "rejected",
            notes="One of 38 R58 variants in one BH family (research/hunt/r58/results/report.md).",
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print("written", n)


if __name__ == "__main__":
    main()
