"""R93: write research/experiments/entries/r93-*.yaml (one per variant) from results/design.json and holdout.json.

    python -m research.hunt.r93_breadth_donchian_mcx.entries
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

from research.hunt.r93_breadth_donchian_mcx import cfg as K

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / "research" / "experiments" / "entries"
IDS = {"I-A": "r93-i-a-plain", "I-B": "r93-i-b-breadth", "I-C": "r93-i-c-cross-market", "D-A": "r93-d-a-plain-daily",
       "D-B": "r93-d-b-breadth-daily"}
RULE = {
    "I-A": "5-minute close-only bars of each MCX family's near future; close above the previous 20 bars' high -> buy the "
           "1-ITM call (below the low -> put); copper / zinc / aluminium trade the future",
    "I-B": "I-A, but a break counts only when at least M families of its group break the same way on the same 5-minute "
           "bar (metals 3 of GOLD, SILVER, COPPER, ZINC, ALUMINIUM; energy 2 of CRUDE, NATGAS; minis once)",
    "I-C": "I-A, but a break counts only when the international driver's own 5-minute Donchian 20 breaks the same way "
           "over the same IST minutes (XAUUSD, XAGUSD, WTI, Henry Hub, COMEX copper; Dukascopy); zinc / aluminium none",
    "D-A": "daily close beyond the previous 20 sessions' back-adjusted high / low -> the future at the next open (mini lot)",
    "D-B": "D-A, but only when at least M families of the group break the same way on the same day (metals 3 of 5, "
           "energy 2 of 2)",
}


def rs(x) -> str:
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,.0f}"


def entry(v: str, D: dict, H: dict) -> dict:
    daily = v in K.DAILY
    d, h = D["variants"][v], H["variants"][v]
    s, hs = d["design"], h["holdout"]
    wf = D["walk_forward_daily" if daily else "walk_forward_intraday"]["fold_sum"][v]
    failed = [k for k, ok in h["gates"].items() if k != "PASS" and not ok]
    fam = ", ".join(f"{f} {rs(x['net'])} ({x['trades']})" for f, x in h["holdout_breakdown"]["by_family"].items())
    summary = (f"Design: {s['trades']} trades, gross {rs(s['gross'])}, net {rs(s['net'])}, win {100 * s['win_rate']:.0f}%, "
               f"max DD {rs(s['max_dd'])}, worst month {rs(s['worst_month'])}, design twin p {d['design_twin_p']:.3f}; "
               f"walk-forward {rs(wf)}. Holdout (read once): {hs['trades']} trades, gross {rs(hs['gross'])}, net "
               f"{rs(hs['net'])}, win {100 * hs['win_rate']:.0f}%, avg win / loss {rs(hs['avg_win'])} / "
               f"{rs(hs['avg_loss'])}, worst month {rs(hs['worst_month'])} ({hs['months_below_stop']} of "
               f"{hs['months']} months below -Rs 13k), max DD {rs(hs['max_dd'])}, without top 5 {rs(hs['net_wo_top5'])}, "
               f"at 2x spread {rs(hs['net_2x_slip'])}; twins avg {rs(h['twin_mean'])}, twin p {h['twin_p']:.3f}, BH q "
               f"{h['bh_q']:.3f} (5 variants). By family: {fam}. Gates failed: {', '.join(failed) or 'none'}. "
               f"Verdict: {'PASS' if h['gates']['PASS'] else 'FAIL'}.")
    costs = ("Options: Zerodha MCX option charges + premium half-spread GOLDM / SILVERM / CRUDEOILM 0.30% (0.60% before "
             "17:00), NATGASMINI 0.45% / 0.90%, entry and exit. Futures: Zerodha MCX futures charges + half-spread 0.03% "
             "crude, 0.06% gas, 0.05% metals + 0.02% on stops. Also reported at 2x the half-spreads.")
    return {
        "id": IDS[v], "title": f"R93 {v}: {K.LABEL[v]} on MCX commodities", "family": "mcx-crypto",
        "date": "2026-10-21", "doc": "research/HUNT_R93_BREADTH_DONCHIAN_MCX.md",
        "hypothesis": (f"Boss (21 Oct): does Breadth Donchian work on commodities? {v} ({K.LABEL[v]}) earns after real MCX "
                       "costs, beats random entries on the same days and sides (twin p < 0.05, BH q <= 0.10 over 5 "
                       "variants), in design, walk-forward and the locked holdout."),
        "data_version": ("Drive lake gdrive:IraAlgo-data-lake (options_rolling_1m MONTH books of 19-20 Oct: near-future "
                         "spot + ATM-3..+3 minutes; fut_1m_mcx_continuous + fut_mcx_rolls for ALUMINIUM; "
                         "fut_daily_continuous to 16 Oct 2026); Dukascopy 1-minute drivers fetched 21 Oct (not committed)"),
        "period": ({"design": "2012-01-01..2022-12-31 (walk-forward 2019, 2020, 2021, 2022)",
                    "holdout": "2023-01-01..2026-10-16 (read once, 21 Oct)"} if daily else
                   {"design": "2025-08-05..2026-03-31 (walk-forward Nov-Dec 2025, Jan-Feb 2026, Mar 2026)",
                    "holdout": "2026-04-01..2026-10-19 (read once, 21 Oct)"}),
        "instruments": ["GOLD/GOLDM", "SILVER/SILVERM", "COPPER", "ZINC", "ALUMINIUM", "CRUDEOIL/CRUDEOILM",
                        "NATURALGAS/NATGASMINI"],
        "features": ["signal source: the MCX future itself (the near future under the option book; daily: the parent's "
                     "near-month daily, back-adjusted)" + ("; the international driver's own price (I-C)" if v == "I-C"
                                                         else ""),
                     "traded: " + ("the future at the mini lot (no option history before Aug 2025)" if daily else
                                   "1-ITM GOLDM / SILVERM / CRUDEOILM / NATGASMINI options on real prints; COPPER, "
                                   "ZINCMINI, ALUMINI futures (options thin or absent)")],
        "model_or_rule": RULE[v] + (". Daily exits: stop 2 x ATR14, chandelier lock 3 x ATR14, out after 10 sessions or "
                                    "before a roll" if daily else ". R72 exits XL: stop -15% premium, +5% once +25%, "
                                    "30/60/100 ladder, out after 30 min; futures: channel-mid stop, 30 min; fills 09:10 "
                                    "to close - 20, square-off close - 15, 2 a side a day, never on option expiry day"),
        "settings": "research/hunt/r93_breadth_donchian_mcx/cfg.py; PREREG.md sections 2-5",
        "prereg": "research/hunt/r93_breadth_donchian_mcx/PREREG.md",
        "variants_tried": 5,
        "results": {"summary": summary, "costs": costs},
        "holdout_read": True,
        "verdict": "paper-only" if h["gates"]["PASS"] else "rejected",
        "notes": ("Signal on the underlying (the MCX future). Research only; nothing changes paper or live. Notes N1-N4 in "
                  "NOTES.md (fill-only twins, causal daily rolls, futures twin stop, zinc's April expiry)."),
    }


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    H = json.loads((K.RESULTS / "holdout.json").read_text())
    for v in K.VARIANTS:
        e = entry(v, D, H)
        (OUT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        print(e["id"], e["verdict"])


if __name__ == "__main__":
    main()
