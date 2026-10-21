#!/usr/bin/env python3
"""R92 experiment entries: one YAML per idea (family x arm x mode, 20 in all) in research/experiments/entries/.
Each lists the number of cells it holds (variants_tried); the 257 cells are one BH family.

    python3 research/hunt/r92_orb_time/r92entries.py
"""
from __future__ import annotations

import json
from collections import defaultdict

import r92cfg as K

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
NAME = {"orb": "ORB", "orb_fresh": "ORB Fresh", "orb_sweep": "ORB Sweep", "range_fade": "Range Fade"}
FAM = {"T": "entry-time slots", "R": "opening-range length", "D": "day type", "X": "exit time",
       "C": "hour x expiry day", "H12": "R3's 12:00-12:59 lead (post hoc from R3)"}
DATA = ("Drive lake gdrive:IraAlgo-data-lake (21 Oct 2026 copy): options_rolling_1m opt_<UND>_WEEK_2021-2026 (real "
        "1-minute prints, nearest expiry, ATM+-10) + index_1m incl. INDIAVIX, to 19 Oct 2026; R3 session builder with "
        "R49 lots and expiry fix")
HYP = ("Pre-registered (research/hunt/r92_orb_time/PREREG.md): the ORB family's app rules (signal on the index, "
       "ATM option of the 09:20 bar, -P / +P (Sweep +2P), the ladder, out 15:10 / 15:05, 1 entry a side a day) make "
       "money after real charges and slippage in this {fam} cell: net > 0 with >= 100 trades, money q <= 0.10 (BH over "
       "257 cells), random-entry twin at other minutes p < 0.05, >= 3 of 4 years and >= 3 of 5 indices > 0, > 0 "
       "without the top 5, worst month per index >= -Rs 13k; then holdout net > 0, >= 30 trades, twin p < 0.10.")


def _q(s: str) -> str:
    return "'" + s.replace("'", "''") + "'"


def group_key(c):
    return (c["family"], c["arm"], c["mode"])


def gid(fam, arm, mode):
    a = {"orb": "orb", "orb_fresh": "fresh", "orb_sweep": "sweep", "range_fade": "fade"}[arm]
    return f"r92-{fam.lower()}-{a}" + (f"-{mode.lower()}" if fam == "T" else "")


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    Hp = K.RESULTS / "holdout.json"
    H = {c["id"]: c for c in json.loads(Hp.read_text())["cells"]} if Hp.exists() else {}
    groups = defaultdict(list)
    for c in D["cells"]:
        groups[group_key(c)].append(c)
    existing = {p.stem for p in ENTRIES.glob("*.yaml")}
    n = 0
    for (fam, arm, mode), cs in groups.items():
        eid = gid(fam, arm, mode)
        mine = (ENTRIES / f"{eid}.yaml")
        if eid in existing and not mine.read_text().count("doc: research/HUNT_R92_ORB_TIME.md"):
            raise SystemExit(f"id clash: {eid} already exists and is not R92's")
        best = max(cs, key=lambda c: c["exp"] if c["trades"] >= 30 else -1e18)
        tot_tr = sum(c["trades"] for c in cs)
        pos = [c for c in cs if c["net"] > 0]
        parts = [f"{len(cs)} cells; {len(pos)} with design net > 0; design passers {sum(c['gates']['pass'] for c in cs)}",
                 f"least-bad: {best['label']} {best['trades']} tr, net Rs {best['net']:+,.0f}, Rs {best['exp']:+,.0f}/trade, "
                 f"win {100 * best['win_rate']:.0f}%, q {best['q']:.2f}, twin p {best['twin_p']:.3f}, index "
                 f"{best['pts_mean']:+.1f} pts/trade"]
        worst = min(cs, key=lambda c: c["exp"] if c["trades"] >= 30 else 1e18)
        parts.append(f"worst: {worst['label']} {worst['trades']} tr, Rs {worst['exp']:+,.0f}/trade")
        if H:
            hb = H[best["id"]]
            parts.append(f"holdout (read once) of the least-bad: {hb['trades']} tr, net Rs {hb['net']:+,.0f}, "
                         f"Rs {hb['exp']:+,.0f}/trade, twin p {hb['twin_p']:.3f}")
            hp = sum(H[c["id"]]["net"] > 0 for c in cs)
            parts.append(f"holdout cells net > 0: {hp} of {len(cs)}")
        verdict = "rejected"
        if H and any(c["gates"]["pass"] and H[c["id"]]["gates"]["pass"] for c in cs):
            verdict = "paper-only"
        unds = list(cs[0]["unds"])
        title = f"R92 {NAME[arm]} {FAM[fam]}" + (f" ({mode})" if fam == "T" else "") + ": does a time make the ORB family pay?"
        y = [f"id: {eid}", f"title: {_q(title)}", "family: orb", "date: '2026-10-21'",
             "doc: research/HUNT_R92_ORB_TIME.md", f"hypothesis: {_q(HYP.format(fam=FAM[fam]))}",
             f"data_version: {_q(DATA)}", "period:",
             "  design: 1 Aug 2021 - 31 Mar 2026" + (" (FINNIFTY, MIDCPNIFTY, SENSEX only)" if fam == "H12" else ""),
             "  holdout: 1 Apr - 19 Oct 2026" + (" (read once)" if H else " (not yet read)"),
             "instruments:"] + [f"- {u}" for u in unds] + [
             "features:",
             "- 'signal source: the underlying (index spot 1-minute bars, 5-minute bars built from it); the ATM option is only the instrument; no option chart, no option-chain data'",
             f"- {_q('cells: ' + '; '.join(c['label'] for c in cs))}",
             f"model_or_rule: {_q(f'{NAME[arm]} app rules sliced by {FAM[fam]}')}",
             "settings: research/hunt/r92_orb_time/r92cfg.py, r92engine.py (cells()), PREREG.md",
             "prereg: research/hunt/r92_orb_time/PREREG.md", f"variants_tried: {len(cs)}", "results:",
             f"  summary: {_q('Design (1 lot, real prints, dated charges, R17 fills): ' + str(tot_tr) + ' trades summed over the cells (cells overlap). ' + '. '.join(parts) + '.')}",
             "  costs: obuy/costs.py Costs(dated) (STT 0.1% to 31 Mar 2026, 0.15% after; BSE rates for SENSEX), R17 liq fills (+-5 bps + 1-4 ticks; stops 10 bps, double ticks), 1 lot of the day's contract",
             f"holdout_read: {'true' if H else 'false'}", f"verdict: {verdict}",
             f"notes: {_q('One idea of 20 (257 cells, one BH family m = 257). Per-cell numbers: research/hunt/r92_orb_time/results/report.md.')}"]
        mine.write_text("\n".join(y) + "\n")
        n += 1
    print("wrote", n, "entries")


if __name__ == "__main__":
    main()
