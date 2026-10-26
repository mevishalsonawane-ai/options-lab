"""R106 experiment entries: one YAML per rule x arm (failures included) from results/*.json and decision.json.

    python research/hunt/r106_small_targets/r106entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r106cfg as K
from r106report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/HUNT_R106_SMALL_TARGETS.md"
PREREG = "research/hunt/r106_small_targets/PREREG.md"
UNDS5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
ARM_TXT = {
    "LIQ": ("Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5)", ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"],
            "index 15/30-min + 5-min bars (liquidity pool breaks)"),
    "R72": ("R72 Breadth Donchian (4 of 5 indices break)", UNDS5, "index 5-min bars of all five indices (Donchian 20)"),
    "R51": ("R51 Donchian 20", UNDS5, "index 5-min bars (Donchian 20)"),
    "R52": ("R52 Open outside value", UNDS5, "index first 5-min bar + previous day's value area"),
    "SOLO": ("Solo 2.0 (R57 A2 picks)", ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"], "index setups S1-S4 as R57"),
}


def line(s: dict | None) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {100 * s['win']:.0f}% (break-even {100 * (s.get('be_win') or 0):.0f}%), net "
            f"{rs(s['net'])}, {rs(s.get('per_session'))} a session, green days {100 * (s.get('green_days') or 0):.0f}%, "
            f"worst day {rs(s['worst_day'])}, max DD {rs(s['max_dd'])}")


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    arms = [a for a in K.ARMS + ("SOLO",) if (K.RESULTS / f"final_{a}.json").exists()]
    n_tests = dec["_m"]["r106"]
    n = 0
    for arm in arms:
        d = json.loads((K.RESULTS / f"design_{arm}.json").read_text())
        f = json.loads((K.RESULTS / f"final_{arm}.json").read_text())
        title, unds, src = ARM_TXT[arm]
        b0d, b0f = d["B0"]["all"], f["B0"]["all"]
        for r in K.RULE_IDS:
            g = dec.get(f"{arm}:{r}", {})
            e = dict(
                id=f"r106-{arm.lower()}-{r.lower()}",
                title=f"R106 {r} on {title}: {K.LABEL[r]}",
                family="trade-management",
                date="2026-10-26",
                doc=DOC,
                hypothesis=(f"Boss (26 Oct): small fixed profits (\"5 orders each give me Rs 1,000\") work on {title}. This "
                            f"rule: {K.LABEL[r]} (net of charges, per lot). Gates: net > 0 in design, walk-forward and "
                            "final; above today's rules in walk-forward and final (or within 5% with worst day and "
                            "drawdown >= 20% smaller); random-exit twin BH q <= 0.10 counted with 513 earlier "
                            "target / lock / filter tests; >= 40 final trades."),
                data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) copied "
                              "26 Oct; R51 Amendment A clean stores (R57 stores for Solo 2.0)"),
                period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026; Solo Jul 2025 - Mar 2026)",
                            holdout="2026-04-01..2026-10-13 read once (already read by R51-R104); post 14-19 Oct reported"),
                instruments=unds,
                features=[f"signal source: {src} (unchanged); only the option's exit (premium target / stop) differs"],
                model_or_rule=f"{title} with {K.LABEL[r]}",
                settings="research/hunt/r106_small_targets/r106cfg.py RULES; PREREG.md section 2",
                prereg=PREREG,
                variants_tried=n_tests,
                results=dict(
                    summary=(f"Design: {line(d[r]['all'])} (today's rules {rs(b0d['net'])}). Walk-forward: "
                             f"{line(d[r].get('wf'))} (today {rs(d['B0']['wf']['net'])}). Final: {line(f[r]['all'])} "
                             f"(today {rs(b0f['net'])}); exit-twin p {g.get('twin_p')}, BH q {g.get('q_all')} "
                             f"(R106 alone {g.get('q_r106')}). "
                             + ("PASS." if g.get("passed") else "Fails: " + "; ".join(g.get("fails", [])) + ".")),
                    costs="dated Zerodha charges, R17 fills (stops -10 bps and 2x the half-spread, gaps at the open), "
                          "limit targets at their price, 1 lot"),
                holdout_read=True,
                verdict="paper-only" if g.get("passed") else "rejected",
                notes="Signal on the underlying; nothing changes live.",
            )
            (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
            n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
