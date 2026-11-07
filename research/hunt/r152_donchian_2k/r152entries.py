"""R152 experiment entries: one YAML per arm x rule x lots (failures included) from results/*.json and decision.json.

    python research/hunt/r152_donchian_2k/r152entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r152cfg as K
from r152report import rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r152_donchian_2k/results/report.md"
PREREG = "research/hunt/r152_donchian_2k/PREREG.md"
UNDS5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
ARM_TXT = {
    "R72": ("R72 Breadth Donchian (4 of 5 indices break)", "index 5-min bars of all five indices (Donchian 20, 4 of 5)"),
    "R51": ("R51 Donchian 20 + Supertrend (10, 3)", "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"),
}


def line(s: dict | None) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {100 * s['win']:.0f}% (break-even {100 * (s.get('be_win') or 0):.0f}%), net "
            f"{rs(s['net'])}, {rs(s.get('per_session'))} a session, worst day {rs(s['worst_day'])}, max DD "
            f"{rs(s['max_dd'])}")


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for arm in K.ARMS:
        d = json.loads((K.RESULTS / f"design_{arm}.json").read_text())
        f = json.loads((K.RESULTS / f"final_{arm}.json").read_text())
        title, src = ARM_TXT[arm]
        for r in K.RULE_IDS:
            for lots in K.LOTS:
                k, b = f"{r}@{lots}", f"B0@{lots}"
                g = dec.get(f"{arm}:{k}", {})
                e = dict(
                    id=f"r152-{arm.lower()}-{r.lower()}-{lots}lot",
                    title=f"R152 {r} on {title}, {lots} lot{'s' if lots > 1 else ''}: {K.LABEL[r]} (whole position)",
                    family="trade-management",
                    date="2026-11-07",
                    doc=DOC,
                    hypothesis=(f"Boss (7 Nov): \"sell at max target i.e. 2k and max loss at 2k\" on {title}. This rule: "
                                f"{K.LABEL[r]}, for the whole {lots}-lot position, net of charges; the arm's other exits "
                                "stay. Gates (R106's): net > 0 in design, walk-forward and final; above today's rules at "
                                "the same lots in walk-forward and final (or within 5% with worst day and drawdown >= 20% "
                                f"smaller); random-exit twin BH q <= 0.10 with m = {dec['_m']['total']}; >= 40 final trades."),
                    data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) "
                                  "copied 7 Nov 2026; R51 Amendment A clean stores (post: R106's stores to 19 Oct)"),
                    period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                                holdout="2026-04-01..2026-10-13 read once by R152 (already read by R51-R151); post 14-19 Oct "
                                        "reported"),
                    instruments=UNDS5,
                    features=[f"signal source: {src} (unchanged); only the option's exit (rupee target / stop on the "
                              "premium) differs"],
                    model_or_rule=f"{title} with {K.LABEL[r]}, {lots} lot{'s' if lots > 1 else ''}",
                    settings="research/hunt/r152_donchian_2k/r152cfg.py RULES, LOTS; PREREG.md section 2",
                    prereg=PREREG,
                    variants_tried=len(K.TESTS),
                    results=dict(
                        summary=(f"Design: {line(d[k]['all'])} (today's rules {rs(d[b]['all']['net'])}). Walk-forward: "
                                 f"{line(d[k]['wf'])} (today {rs(d[b]['wf']['net'])}). Final: {line(f[k]['all'])} "
                                 f"(today {rs(f[b]['all']['net'])}); exit-twin p {g.get('twin_p')}, BH q {g.get('q_all')} "
                                 f"(R152 alone {g.get('q_r152')}). "
                                 + ("PASS." if g.get("passed") else "Fails: " + "; ".join(g.get("fails", [])) + ".")),
                        costs=(f"dated Zerodha charges on the whole {lots}-lot quantity, R17 fills (slippage from the "
                               "5-minute volume per position; stops -10 bps and 2x the half-spread, gaps at the open), "
                               "limit targets at their price")),
                    holdout_read=True,
                    verdict="paper-only" if g.get("passed") else "rejected",
                    notes="Signal on the underlying; nothing changes live.",
                )
                (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
