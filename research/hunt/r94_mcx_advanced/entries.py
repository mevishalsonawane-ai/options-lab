"""R94 experiment entries: one YAML per rule family (all its cells listed) in research/experiments/entries/, written
from results/design_all.json and results/holdout_all.json.

    python -m research.hunt.r94_mcx_advanced.entries
"""
from __future__ import annotations

import json

from research.hunt.r94_mcx_advanced import common as C

ROOT = C.HERE.parents[2]
DOC = "research/HUNT_R94_MCX_ADVANCED.md"
PREREG = "research/hunt/r94_mcx_advanced/PREREG.md"
LAKE = ("Drive lake gdrive:IraAlgo-data-lake copied 21 Oct 2026: fut_1m_live_mcx (contracts live on 19 Oct, 1-minute), "
        "fut_daily_continuous (near month, 2012 - 16 Oct 2026), options_rolling_1m MCX MONTH books (ATM-3..+3, 5 Aug "
        "2025 - 19 Oct 2026); Dukascopy 1-minute BID CFDs (XAUUSD, XAGUSD, LIGHTCMDUSD, GASCMDUSD) for idea 5")
COSTS = ("Zerodha MCX charges on every leg (McxCosts.kt), half-spread on every fill (futures R53 rule, far month 3x; "
         "options R85 strict ATM rate x (1 + 0.5 |offset|), >= 1 tick); 1 lot; margins without spread benefit")

# entry id -> (title, family, cell prefix, instruments, features, rule text, signal source)
GROUPS = {
    "r94-cal-z1": ("R94 idea 1 Z1: MCX calendar-spread mean reversion, z of ln(far/near) over 100 obs, |z| >= 2, exit z = 0 / 2 sessions / 2-sd stop",
                   "mcx-crypto", "c-Z1-", "16 consecutive-month futures pairs (GOLDM, SILVERM, SILVER, CRUDEOILM, CRUDEOIL, NATGASMINI, NATURALGAS)",
                   "the two futures legs (spread on minutes both printed, 15-minute grid)"),
    "r94-cal-z2": ("R94 idea 1 Z2: MCX calendar-spread mean reversion, z over 400 obs, exit z = 0 / 5 sessions / 2-sd stop",
                   "mcx-crypto", "c-Z2-", "the same 16 pairs", "the two futures legs"),
    "r94-cal-c1": ("R94 idea 1 C1: bullion calendar carry vs 6.5% fair, +/-3 points entry, 1 point exit, 3 more points stop",
                   "mcx-crypto", "c-C1-", "6 bullion pairs (GOLDM x3, SILVERM x2, SILVER)", "the two futures legs (implied carry)"),
    "r94-pairs": ("R94 idea 2: sector-index pair proxies (GOLD/SILVER, COPPER/ZINC/ALUMINIUM, CRUDEOIL/NATURALGAS), ratio z over 60 / 120 days, |z| >= 2, exit z = 0 / 20 days / |z| >= 3.5",
                     "mcx-crypto", "p-", "GOLD, SILVER, COPPER, ZINC, ALUMINIUM, CRUDEOIL, NATURALGAS (daily continuous)",
                     "the two roll-adjusted futures (log ratio)"),
    "r94-opt-ic": ("R94 idea 3 IC: MCX iron condor (short +/-1, long +/-3) with a 1x-credit stop, 50/25 profit lock, 80% take profit",
                   "option-structure", "o-IC-", "CRUDEOIL, CRUDEOILM, NATURALGAS, NATGASMINI, GOLDM, SILVERM option books",
                   "none (non-directional); ATM IV percentile as context filter; stops on premium"),
    "r94-opt-if": ("R94 idea 3 IF: MCX iron fly (short ATM, long +/-2), same stop / lock / take profit",
                   "option-structure", "o-IF-", "the same 6 books", "none (non-directional); IV filter context"),
    "r94-opt-ns": ("R94 idea 3 NS: MCX naked short strangle (+/-1), labelled comparison, research only",
                   "option-structure", "o-NS-", "the same 6 books", "none (non-directional); IV filter context"),
    "r94-opt-cc": ("R94 idea 3 CC: MCX covered call (long future + short C+1), 1% stop, 0.5/0.25% lock",
                   "option-structure", "o-CC-", "CRUDEOIL, NATURALGAS, GOLDM, SILVERM", "none (structure); IV filter context"),
    "r94-seas-diwali": ("R94 idea 4: bullion Diwali seasonals (long 10 rows before, short 10 rows after), 2 x ATR14 stop",
                        "calendar-events", "s-DW-", "GOLD, SILVER (daily continuous; GOLDM / SILVERM lot)", "the calendar"),
    "r94-seas-akshaya": ("R94 idea 4: long GOLD / SILVER 10 rows before Akshaya Tritiya, 2 x ATR14 stop",
                         "calendar-events", "s-AT-", "GOLD, SILVER", "the calendar"),
    "r94-seas-wedding": ("R94 idea 4: long GOLD / SILVER in the wedding months (Nov-Feb, Apr-May), monthly holds, 2 x ATR14 stop",
                         "calendar-events", "s-WED-", "GOLD, SILVER", "the calendar"),
    "r94-gap-g1": ("R94 idea 5 G1: MCX catch-up to the US overnight move (US-implied gap minus the MCX move to 09:05), 09:06-14:00",
                   "overnight", "g-G1-", "GOLDM, SILVERM, CRUDEOILM, NATGASMINI", "US benchmark CFD proxy (Dukascopy) and the MCX future itself"),
    "r94-gap-g2": ("R94 idea 5 G2: follow the US overnight move from 09:06 to 14:00 on MCX", "overnight", "g-G2-",
                   "GOLDM, SILVERM, CRUDEOILM, NATGASMINI", "US benchmark CFD proxy (Dukascopy)"),
}
# W60 / W120 are both idea-2 cells; one entry covers both windows (variants_tried counts them)
PERIOD = {"c": ("May - 31 Aug 2026 (per pair, from its first common print)", "1 Sep - 19 Oct 2026"),
          "p": ("2012 - 2021", "1 Jan 2022 - 16 Oct 2026"), "o": ("5 Aug 2025 - 31 Mar 2026", "1 Apr - 19 Oct 2026"),
          "s": ("2012 - 2021", "2022 - 16 Oct 2026"), "g": ("5 Aug 2025 - 31 Mar 2026", "1 Apr - 19 Oct 2026")}


def _q(s: str) -> str:
    return "'" + str(s).replace("'", "''") + "'"


def _cell_text(c, d, h):
    s = d["summary"]
    t = f"{c} {s['trades']} tr {s['net']:+,.0f} (p {d['p_used']:.3f}, q {d['q']:.2f}"
    t += ", PASS)" if d["gate"]["pass"] else ")"
    if h:
        hs = h["summary"]
        t += f" -> holdout {hs['trades']} tr {hs['net']:+,.0f}"
    return t


def main():
    D = json.loads((C.RESULTS / "design_all.json").read_text())["cells"]
    hp = C.RESULTS / "holdout_all.json"
    H = json.loads(hp.read_text()) if hp.exists() else {}
    for eid, (title, fam, prefix, inst, src) in GROUPS.items():
        cells = sorted(c for c in D if c.startswith(prefix))
        if not cells:
            continue
        both = [c for c in cells if H.get(c, {}).get("both_pass")]
        parts = [_cell_text(c, D[c], H.get(c)) for c in cells]
        summ = (f"{len(cells)} cells (one BH family of {len(D)} R94 cells). Design net after costs per cell, twin / "
                f"bootstrap p and BH q, then the one holdout read: " + "; ".join(parts) +
                f". Design passers {sum(D[c]['gate']['pass'] for c in cells)}, design + holdout passers {len(both)}.")
        oos = prefix.startswith("o-")
        verdict = "inconclusive" if oos else ("paper-only" if both else "rejected")
        if oos:
            summ = "OUT OF SCOPE (Boss, 21 Oct: option selling is out of scope); design only, holdout NOT read. " + summ
        des, hol = PERIOD[prefix[0]]
        y = [f"id: {eid}", f"title: {_q(title)}", f"family: {fam}", "date: '2026-10-21'", f"doc: {DOC}",
             f"hypothesis: {_q('Pre-registered (' + PREREG + '): ' + title + '; after real MCX costs on every leg it beats random entry (or zero for every-day option selling), p <= 0.05 and BH q <= 0.10 over all R94 cells, walk-forward, worst month >= -Rs 13k, then holds on the locked holdout read once; Boss risk rules: hard stop, 2% of Rs 1 lakh, liquid contracts. Signal source: ' + src + '.')}",
             f"data_version: {_q(LAKE)}", "period:", f"  design: {des}", f"  holdout: {hol}" + (" (not read: out of scope)" if oos else (" (read once)" if H else " (not yet read)")),
             "instruments:", f"- {_q(inst)}", "features:", f"- {_q('signal source: ' + src)}",
             f"model_or_rule: {_q(title)}", "settings: research/hunt/r94_mcx_advanced/ (PREREG.md, NOTES.md, the idea module)",
             f"prereg: {PREREG}", f"variants_tried: {len(cells)}", "results:", f"  summary: {_q(summ)}",
             f"  costs: {_q(COSTS)}", f"holdout_read: {'true' if H and not oos else 'false'}", f"verdict: {verdict}",
             f"notes: {_q('Cells: ' + ', '.join(cells))}"]
        (ROOT / "research" / "experiments" / "entries" / f"{eid}.yaml").write_text("\n".join(y) + "\n")
        print("wrote", eid, len(cells), "cells")


if __name__ == "__main__":
    main()
