#!/usr/bin/env python3
"""Writes R49's experiment entries (research/experiments/entries/r49-*.yaml), one per rule, from results/design.json
and results/final.json.

    python3 research/hunt/r49/r49entries.py
"""
from __future__ import annotations

import json

import yaml

import r49cfg as K
import r49engine as R

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R49_ORB_RECENT.md"
PREREG = "research/hunt/r49/PREREG.md"
DATA = ("Dhan lake on Drive, fetched 15 Oct 2026: options_rolling_1m opt_<UND>_WEEK_2024-2026 (nearest expiry on or after "
        "the day, one held contract per trade) and index_1m incl. INDIAVIX, read by research/hunt/r49/r49data.py")
PERIOD = {"design": "21 Nov 2024 - 31 Mar 2026 (336 sessions per index), NIFTY BANKNIFTY FINNIFTY MIDCPNIFTY SENSEX",
          "holdout": "final test 1 Apr - 13 Oct 2026 (132-133 sessions per index), read once 15 Oct 2026; not pristine "
                     "(R3, R24 and others had read parts of 2025-26)"}
COSTS = ("charges in force on the date (STT 0.1% to 31 Mar 2026, 0.15% from 1 Apr 2026; BSE rates for SENSEX); liq fills "
         "(5 bps + 1-4 ticks, stops 10 bps + 2x ticks); 1 lot of the day's contract (lot read from the data)")
WHAT = {
    "O": "the old rule exactly as the app had it when it was paused",
    "R3": "R3's frozen enhanced version (filters and cut values unchanged)",
    "R23": "R23's frozen ORB finalist (never read on the lake before)",
    "G": "a re-tune grid point (opening-range length M, stop / target scale k), chosen on design only",
}
SIGNAL = "Signal source: the index spot (1-minute, 5- / 15-minute bars) - the default; the option is only the instrument."


def eid(rid: str) -> str:
    return rid.replace(".", "p")


def fmt(x: dict, final: bool) -> str:
    s = (f"{x['trades']:,} trades, win rate {x['win_rate']:.0%}, net Rs {x['net']:+,.0f} (Rs {x['per_day']:+,.0f} a day), "
         f"avg win / loss Rs {x['avg_win']:+,.0f} / {x['avg_loss']:+,.0f}, worst day Rs {x['worst_day']:+,.0f}, max DD Rs {x['max_dd']:+,.0f}")
    if final:
        s += f", twin p {x['twin_p']:.3f}, BH q {x['q']:.2f}, {x['verdict']}"
    return s


def main():
    des = json.loads((K.RESULTS / "design.json").read_text())
    fin = json.loads((K.RESULTS / "final.json").read_text())
    for r in R.rules():
        d, f = des["results"][r.id], fin["results"][r.id]
        passes = [sc for sc in ("pooled",) + K.UNDS if f[sc]["verdict"] == "PASS"]
        per_und = "; ".join(f"{u} {f[u]['trades']} / {f[u]['net']:+,.0f} (p {f[u]['twin_p']:.2f}, {f[u]['verdict']})" for u in K.UNDS)
        rule = f"{r.id}: {WHAT[r.group]}; arm {r.arm}, range {r.m} min, stop / target x{r.k:g}" + (f", R23 {r.r23}" if r.r23 else "")
        e = dict(
            id=eid(r.id), title=f"R49 {r.id}: {WHAT[r.group]} (Nov 2024+ market only)", family="orb", date="2026-10-15",
            doc=DOC, data_version=DATA, period=PERIOD, prereg=PREREG,
            hypothesis=(f"On the market since NSE's Nov 2024 expiry change, {r.id} makes money net of all trading fees on the final "
                        "test, beats random-entry twins and survives BH over 336 tests (PREREG A6), pooled or on one index."),
            instruments=list(K.UNDS), features=["1-minute index", "opening range", "VIX (R3 filters)", "ATR14", "TWAP (R23)"],
            model_or_rule=rule + ". " + SIGNAL, settings="research/hunt/r49/PREREG.md A3; results/design.json rules[]",
            variants_tried=6,
            results=dict(summary=(f"Design (pooled): {fmt(d['pooled'], False)}. Final test (pooled): {fmt(f['pooled'], True)}. "
                                  f"Final by index: {per_und}. Passes: {', '.join(passes) or 'none'}."), costs=COSTS),
            holdout_read=True, verdict="paper-only" if passes else "rejected",
            notes="6 tests per rule (pooled + 5 indices), all in R49's BH family of 336.",
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("written", len(R.rules()))


if __name__ == "__main__":
    main()
