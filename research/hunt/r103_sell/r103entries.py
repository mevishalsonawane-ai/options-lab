"""Writes research/experiments/entries/r103-*.yaml: one entry per R103 test (24), failures included, from
results/design.json and results/holdout.json.

    python research/hunt/r103_sell/r103entries.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RES = HERE / "results"
ENT = ROOT / "research" / "experiments" / "entries"
SNAME = {"IF": "iron fly, wings 1.0 x the 09:20 straddle", "IFW": "wide iron fly, wings 1.5 x the straddle",
         "IC": "iron condor, shorts 0.5 x and wings 1.0 x the straddle beyond"}
XNAME = {"X1": "stop at a loss of 50% of the credit, else the square-off",
         "X2": "the 50% stop + a lock armed at +40% that exits at +20% + a +60% target"}
TNAME = {"E": "the index's expiry day", "N": "every other trading day"}


def rs(x):
    return "-" if x is None else f"Rs {x:,.0f}".replace("Rs -", "-Rs ")


def summ(s: dict) -> str:
    if not s or not s.get("n"):
        return "0 trades"
    return (f"{s['n']} trades, win {100 * s['win']:.0f}%, net {rs(s['net'])} ({rs(s['mean'])} a trade), avg win {rs(s['avg_win'])} / "
            f"loss {rs(s['avg_loss'])}, worst day {rs(s['worst_day'])}, worst month {rs(s['worst_month'])}, max DD {rs(s['max_dd'])}")


def entry_id(tid: str) -> str:
    return "r103-" + tid.lower()


def main() -> int:
    d = json.loads((RES / "design.json").read_text())
    h = json.loads((RES / "holdout.json").read_text()) if (RES / "holdout.json").exists() else {"tests": []}
    hm = {r["id"]: r for r in h.get("tests", [])}
    sd = json.loads((RES / "stale_design.json").read_text()) if (RES / "stale_design.json").exists() else {}
    sh = json.loads((RES / "stale_holdout.json").read_text()) if (RES / "stale_holdout.json").exists() else {}
    for old in ENT.glob("r103-*.yaml"):
        old.unlink()
    for r in d["tests"]:
        u, t, s, x = r["id"].split("-")
        ds = r["design"]
        failed = [k for k, v in r["gates"].items() if k != "pass" and not v]
        b = r.get("buy", {})
        text = (f"Design (Aug 2021 - 14 Jul 2026): {summ(ds)}; boot p {ds.get('boot_p', 1):.3f}, BH q {r['q_bh']:.3f} (24 tests); "
                f"2x slippage {rs(r['slip2x_net'])}; years 2022-2025 {', '.join(rs(ds.get('by_year', {}).get(str(y), ds.get('by_year', {}).get(y))) for y in (2022, 2023, 2024, 2025)) if ds.get('n') else '-'}; "
                f"after 20 Nov 2024 {rs(ds.get('post_regime_net'))} on {ds.get('post_regime_n', 0)}; days <= -Rs 13k {ds.get('days_below_stop', 0)}; "
                f"margin estimate {rs(ds.get('mean_margin'))}, net / margin a year {100 * (ds.get('rom_per_year') or 0):+.1f}%. "
                f"Gates failed: {', '.join(failed) or 'none'}. BUY mirror (same legs bought): {summ(b)}.")
        if r["id"] in sd:
            v = sd[r["id"]]
            text += (f" Stale-leg check (Amendment 2, gate 8): {v['trades_with_stale_leg']} trades had an exit leg with no trade "
                     f"within 5 minutes; repriced, design net {rs(v['net_repriced'])}, win {100 * v['win_repriced']:.0f}%.")
        if r["id"] in hm:
            hr = hm[r["id"]]
            v = sh.get(r["id"], {})
            ok = hr["confirmed"] and v.get("net_repriced", -1) > 0 and sd.get(r["id"], {}).get("net_repriced", -1) > 0
            text += (f" Holdout (15 Jul - 19 Oct 2026, read once): {summ(hr['holdout'])}; pre-registered gate "
                     f"{'met' if hr['confirmed'] else 'not met (win rate moved more than 15 points from design)'}; repriced "
                     f"holdout net {rs(v.get('net_repriced'))}. {'Confirmed' if ok else 'Not confirmed'}.")
            verdict = "paper-only" if ok else "rejected"
            hread = True
        else:
            text += " Holdout not read for this test (it failed design)."
            verdict = "rejected" if ds.get("n") else "inconclusive"
            hread = False
        e = dict(id=entry_id(r["id"]), title=f"R103 {u} {TNAME[t]}: short {SNAME[s]}; {XNAME[x]}", family="option-structure",
                 date="2026-10-22", doc="research/HUNT_R103_OPTIONS_SELLING.md",
                 hypothesis=("Pre-registered (PREREG.md): selling the defined-risk structure at the 09:21 open, centred on the "
                             "index's 09:20 ATM, makes money after real charges and slippage on held contracts: mean net > 0 with "
                             "day-bootstrap p < 0.05, BH q <= 0.10 over 24, 3 of 4 years 2022-2025 and after 20 Nov 2024 > 0, 40+ "
                             "trades, > 0 without the 5 best days, worst month >= -Rs 13,000, > 0 at 2x slippage; then the holdout."),
                 data_version="Drive lake gdrive:IraAlgo-data-lake copied 22 Oct 2026 (index_1m, options_rolling_1m WEEK files, "
                              "participants/bhav_index), R51 held-contract stores with Amendment A cleaning",
                 period=dict(design="first option day (Aug 2021) - 2026-07-14", holdout="2026-07-15 - 2026-10-19, read once"),
                 instruments=[u],
                 features=["signal source: none directional; the structure is centred on the index's 09:20 ATM strike (underlying)",
                           "09:20 ATM straddle (C + P close) sets the strike distances", "exits on the position's premium"],
                 model_or_rule=f"sell {SNAME[s]} on {TNAME[t]}; {XNAME[x]}; square-off 15:10 (15:05 on expiry)",
                 settings="research/hunt/r103_sell/r103engine.py (STRUCTS, EXITS), r103study.py",
                 prereg="research/hunt/r103_sell/PREREG.md", variants_tried=1,
                 results=dict(summary=text,
                              costs="dated charges (STT on every sell, 0.15% from 1 Apr 2026; exchange, SEBI, stamp, GST, Rs 20 an "
                                    "order, 8 orders a trade), 'liq' slippage 5 bps + 1-4 ticks by 5-minute volume (stops 2x), "
                                    "next-minute open fills, the lot in force"),
                 holdout_read=hread, verdict=verdict,
                 notes="Amendment 1 (dated, before any number) added the BUY mirror, margin estimate, return on margin and the "
                       "one-day tail count as report columns only; no gate changed.")
        (ENT / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=False, width=120))
    print(len(d["tests"]), "entries")
    return len(d["tests"])


if __name__ == "__main__":
    main()
