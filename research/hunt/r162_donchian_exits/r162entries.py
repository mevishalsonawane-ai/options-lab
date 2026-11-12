"""R162 experiment entries: one YAML per arm x rule x lots (failures included), the next-week-expiry note (not a test)
and the release item (descriptive), from results/*.json.

    python research/hunt/r162_donchian_exits/r162entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json

import yaml

import r162cfg as K
from r162report import pct, rs

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r162_donchian_exits/results/report.md"
PREREG = "research/hunt/r162_donchian_exits/PREREG.md"
UNDS5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
DATA = ("Drive lake gdrive:IraAlgo-data-lake (index_1m, index_daily, options_rolling_1m WEEK) copied 12 Nov 2026; R51 "
        "Amendment A clean stores")
PERIOD = dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
              holdout="2026-04-01..2026-10-13 read once by R162 (already read by R51-R160: weak evidence)")
ARM_TXT = {
    "R72": ("R72 Breadth Donchian (4 of 5 indices break)", "index 5-min bars of all five indices (Donchian 20, 4 of 5)"),
    "R51": ("R51 Donchian 20 + Supertrend (10, 3)", "index 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"),
}
COSTS = ("dated Zerodha charges on the whole quantity (scale-out: one buy order, one sell order per part), R17 fills "
         "(slippage from the 5-minute volume per position; stops -10 bps and 2x the half-spread, gaps at the open), limit "
         "targets at their price")


def line(s: dict | None) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']} trades, win {pct(s['win'])} (break-even {pct(s.get('be_win'))}), net {rs(s['net'])}, worst day "
            f"{rs(s['worst_day'])}, max DD {rs(s['max_dd'])}")


def signal(rule: str, src: str) -> str:
    k = K.RULES[rule]["kind"]
    if k == "ix":
        return f"signal source: {src} (unchanged); the stop is read from the index (the signal 5-min bar's far end)"
    if k == "strike":
        return f"signal source: {src} (unchanged); only the strike bought changes (chosen at the minute before entry)"
    return f"signal source: {src} (unchanged); only the option's exit on the premium changes"


def main():
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    n = 0
    for arm in K.ARMS:
        d = json.loads((K.RESULTS / f"design_{arm}.json").read_text())
        f = json.loads((K.RESULTS / f"final_{arm}.json").read_text())
        title, src = ARM_TXT[arm]
        for r in K.RULE_IDS:
            for lots in K.lots_of(r):
                k, b = f"{r}@{lots}", f"B0@{lots}"
                g = dec[f"{arm}:{k}"]
                e = dict(
                    id=f"r162-{arm.lower()}-{r.lower()}-{lots}lot",
                    title=f"R162 {r} on {title}, {lots} lot{'s' if lots > 1 else ''}: {K.LABEL[r]}",
                    family="option-structure" if K.RULES[r]["kind"] == "strike" else "trade-management",
                    date="2026-11-12",
                    doc=DOC,
                    hypothesis=(f"Boss (12 Nov): raise the Donchian arms' win rate without capping big winners. This rule "
                                f"({K.FAMILY[K.RULES[r]['kind']]}): {K.LABEL[r]}; entries and other exits as today. Gates: "
                                "win rate up >= 5 points and net not lower than today's at the same lots in walk-forward and "
                                "final; net > 0 in design, walk-forward and final; twin p (larger of net and win) BH q <= "
                                f"0.10 with m = {dec['_m']['total']}; >= 40 final trades."),
                    data_version=DATA,
                    period=PERIOD,
                    instruments=UNDS5,
                    features=[signal(r, src)],
                    model_or_rule=f"{title} with {K.LABEL[r]}, {lots} lot{'s' if lots > 1 else ''}",
                    settings="research/hunt/r162_donchian_exits/r162cfg.py RULES, LOTS; PREREG.md section 2",
                    prereg=PREREG,
                    variants_tried=len(K.TESTS),
                    results=dict(
                        summary=(f"Design: {line(d[k]['all'])} (today {pct(d[b]['all']['win'])}, {rs(d[b]['all']['net'])}). "
                                 f"Walk-forward: {line(d[k]['wf'])} (today {pct(d[b]['wf']['win'])}, {rs(d[b]['wf']['net'])}). "
                                 f"Final: {line(f[k]['all'])} (today {pct(f[b]['all']['win'])}, {rs(f[b]['all']['net'])}); "
                                 f"twin p net {g['p_net']}, win {g['p_win']}, BH q {g['q_all']} (R162 alone {g['q_r162']}). "
                                 + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                        costs=COSTS),
                    holdout_read=True,
                    verdict="paper-only" if g["passed"] else "rejected",
                    notes="Signal on the underlying; nothing changes in the app.",
                )
                (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
                n += 1
    # G: next-week expiry - not testable (PREREG section 2 G)
    e = dict(id="r162-nextweek-expiry", title="R162 G: next-week expiry on expiry / near-expiry days (not testable)",
             family="option-structure", date="2026-11-12", doc=DOC,
             hypothesis="Buy the next-week expiry instead of the nearest on expiry / near-expiry days (Boss, 12 Nov).",
             data_version=DATA, period=dict(design="2024-11-20..2026-03-31"), instruments=UNDS5,
             features=["signal source: the index (unchanged)"], model_or_rule="next-week contract on (near-)expiry days",
             settings="PREREG.md section 2 G", prereg=PREREG, variants_tried=None,
             results=dict(summary=("Not run: the lake holds only the nearest (WEEK) and monthly (MONTH) series, so there is "
                                   "no next-week contract, and the arms never trade the index's own expiry day."),
                          costs="none (not run)"),
             holdout_read=False, verdict="not-a-test", notes="Needs a next-week option series in the lake.")
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    n += 1
    pk = K.RESULTS / "picker.json"
    if pk.exists():
        p = json.loads(pk.read_text())
        parts = []
        for arm in K.ARMS:
            parts.append(arm + ": " + "; ".join(f"{name} {s['trades']} trades, win {pct(s['win'])}, net {rs(s['net'])}"
                                                for name, s in p[arm]["release"].items()))
        e = dict(id="r162-picker-release", title="R162 separate item: when a picker-skipped Donchian break releases its side",
                 family="sizing-execution", date="2026-11-12", doc=DOC,
                 hypothesis=("R152's side note: in the app a picker-skipped break holds its side until its would-be exit. "
                             "Compare release at its exit (the app), after 10 / 5 minutes, at once (still counted in the cap), "
                             "and R151's removal before the walk. Descriptive, not gated, not in BH."),
                 data_version=DATA, period=dict(design="none (the pickers were trained on the design years)",
                                                holdout="2026-04-01..2026-10-13 (second look)"),
                 instruments=UNDS5, features=["signal source: the index (unchanged); R151's app pickers' scores"],
                 model_or_rule="release rules P-APP / P-N10 / P-N5 / P-IMM / P-R151, today's exits, 1 lot",
                 settings="r162cfg.py RELEASE; PREREG.md section 7", prereg=PREREG, variants_tried=len(K.RELEASE),
                 results=dict(summary=" | ".join(parts), costs=COSTS), holdout_read=True, verdict="inconclusive",
                 notes="Paper data only; descriptive. Nothing changes in the app.")
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
