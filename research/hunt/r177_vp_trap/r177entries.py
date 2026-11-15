"""R177 experiment entries: one YAML per rule (3), each covering the three arms.

    python research/hunt/r177_vp_trap/r177entries.py && python research/experiments/validate.py
"""
from __future__ import annotations

import json
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72", "R51": "R51"}
TITLE = {"X1": "value-area trap exit (Cases 1 / 2)", "X2": "day-range trap exit (Case 5)",
         "K1": "skip after a failed value-area break"}


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    for rule in ("X1", "X2", "K1"):
        parts = []
        for a in ("LIQ", "R72", "R51"):
            A, S = r["year"]["A@1"][a], r["year"][f"{rule}@1"][a]
            g = d[f"{a}:{rule}"]
            parts.append(f"{NAME[a]}: {A['trades']} trades {rs(A['net'])} -> {S['trades']} trades {rs(S['net'])} "
                         f"(final {rs(r['final']['A@1'][a]['net'])} -> {rs(r['final'][f'{rule}@1'][a]['net'])}); twin p "
                         f"{g['twin_p']}, BH q {g['q_all']}; failed: {'; '.join(g['fails'])}")
        e = dict(
            id=f"r177-{rule.lower()}",
            title=f"R177 {rule}: {TITLE[rule]} from Boss's volume-profile PDF, on Liquidity 15+5, R72, R51 (last year, 1 lot)",
            family="daily-filters" if rule == "K1" else "trade-management",
            date="2026-11-15",
            doc="research/hunt/r177_vp_trap/results/report.md",
            hypothesis=("Boss's PDF (Volume Profile & Order Flow Setups, Cases 1, 2, 5): a break beyond yesterday's value area "
                        "(or day range) that closes back inside is a trap; " + TITLE[rule] + " should raise profit by "
                        "avoiding wrong trades. Footprint / delta / CVD not tested (no tick data for a year)."),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                          "stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none fitted; early part 2025-10-20..2026-03-31",
                        holdout="2026-04-01..2026-10-13 final (read by many studies); whole year 2025-10-20..2026-10-19"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the traded index (yesterday's TPO value area / high / low from index 1-minute bars; "
                      "today's index closes); the arms' entries unchanged"],
            model_or_rule=f"{TITLE[rule]}; PREREG.md section 2",
            settings="research/hunt/r177_vp_trap/r177lib.py, r177study.py; PREREG.md",
            prereg="research/hunt/r177_vp_trap/PREREG.md",
            variants_tried=3,
            results=dict(summary=" | ".join(parts) + ".",
                         costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops, gaps at the open)"),
            holdout_read=True,
            verdict="rejected",
            notes=(f"BH m = {d['_m']['total']}. " + ("Liquidity K1 beat today's rules in every period (twin p 0.014, q within "
                   "R177 0.126) but failed BH and the account-stop gate; a lead for a forward test, not adopted. "
                   if rule == "K1" else "") + "Nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("3 entries")




def main_a():
    """Amendment A's entry: K1 + the Rs 1,000 stop rule (one entry for the three arms)."""
    r = json.loads((RESULTS / "year_a.json").read_text())
    d = json.loads((RESULTS / "decision_a.json").read_text())
    parts = []
    for a in ("LIQ", "R72", "R51"):
        A, S1, K = (r["year"][f"{v}@1"][a] for v in ("A", "S1k", "K1S"))
        g = d[a]
        parts.append(f"{NAME[a]}: today {rs(A['net'])}, stop rule {rs(S1['net'])}, K1 + stop rule {K['trades']} trades "
                     f"{rs(K['net'])}; twin p {g['twin_p']}, BH q {g['q_all']}; failed: {'; '.join(g['fails'])}")
    e = dict(
        id="r177a-k1-stop1000-step",
        title="R177 Amendment A: K1 skip + Rs 1,000 a lot stop + Rs 500 step trail on Liquidity 15+5, R72, R51 (last year)",
        family="trade-management",
        date="2026-11-15",
        doc="research/hunt/r177_vp_trap/results/report.md",
        hypothesis=("Boss (15 Nov): K1's skip after a failed value-area break, with R176 Amendment B's stop rule on top "
                    "(Rs 1,000 a lot entry stop; from +Rs 1,000 a lot the stop Rs 500 a lot under each Rs 500 step)."),
        data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                      "stores built to 19 Oct (R106 clean_post)"),
        period=dict(design="none fitted; early part 2025-10-20..2026-03-31",
                    holdout="2026-04-01..2026-10-13 final (read again, data-led); whole year 2025-10-20..2026-10-19"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: the traded index (yesterday's TPO value area, today's index closes); stops on the premium"],
        model_or_rule="K1 + Rs 1,000 a lot entry stop + Rs 500 step trail; PREREG.md Amendment A",
        settings="research/hunt/r177_vp_trap/r177study.py run_a; PREREG.md Amendment A",
        prereg="research/hunt/r177_vp_trap/PREREG.md",
        variants_tried=1,
        results=dict(summary=" | ".join(parts) + ".",
                     costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops, gaps at the open)"),
        holdout_read=True,
        verdict="rejected",
        notes=f"BH m = {d['_m']['total']}. Chosen after seeing R176 / R177 on the same year. Nothing in the app changes.",
    )
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print("1 entry (Amendment A)")



def main_b(c: bool = False):
    """Amendment B's entries: SG (Rs 1,000 gap under each step) and K1SG (K1 + SG); c=True: Amendment C's SC / K1SC
    (Rs 1,500 initial stop, up Rs 500 per Rs 1,000 gain)."""
    tag = "c" if c else "b"
    r = json.loads((RESULTS / f"year_{tag}.json").read_text())
    d = json.loads((RESULTS / f"decision_{tag}.json").read_text())
    rules = ((("S1k", "SC", "Rs 1,500 a lot initial stop, up Rs 500 a lot per Rs 1,000 a lot of gain"),
              ("K1S", "K1SC", "K1 skip + Rs 1,500 a lot initial stop, up Rs 500 a lot per Rs 1,000 a lot of gain")) if c else
             (("S1k", "SG", "Rs 1,000 a lot stop + Rs 1,000 gap under each Rs 500 step"),
              ("K1S", "K1SG", "K1 skip + Rs 1,000 a lot stop + Rs 1,000 gap under each Rs 500 step")))
    for key, name, title in rules:
        parts = []
        for a in ("LIQ", "R72", "R51"):
            A, S = r["year"]["A@1"][a], r["year"][f"{key}@1"][a]
            g = d[f"{a}:{name}"]
            parts.append(f"{NAME[a]}: today {rs(A['net'])} -> {S['trades']} trades {rs(S['net'])} (win "
                         f"{100 * S['win']:.0f}%); twin p {g['twin_p']}, BH q {g['q_all']}; failed: {'; '.join(g['fails'])}")
        e = dict(
            id=f"r177{tag}-{name.lower()}",
            title=f"R177 Amendment {tag.upper()} {name}: {title}, on Liquidity 15+5, R72, R51 (last year)",
            family="trade-management",
            date="2026-11-15",
            doc="research/hunt/r177_vp_trap/results/report.md",
            hypothesis=(("Boss (15 Nov): 'initial stoploss at 1500 and increase it by 500 on every 1000 gain' - -Rs 1,000 at "
                         "+Rs 1,000 a lot, -Rs 500 at +Rs 2,000, cost at +Rs 3,000, ..." if c else
                         "Boss (15 Nov): 'Test with 1000 gap on every step' - the step trail with the stop Rs 1,000 a lot "
                         "under each step (cost at +Rs 1,000 a lot, +Rs 500 at +Rs 1,500, ...)") +
                        (", on the trades K1 keeps." if name.startswith("K1") else ".")),
            data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                          "stores built to 19 Oct (R106 clean_post)"),
            period=dict(design="none fitted; early part 2025-10-20..2026-03-31",
                        holdout="2026-04-01..2026-10-13 final (read again, data-led); whole year 2025-10-20..2026-10-19"),
            instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
            features=["signal source: the traded index (entries unchanged" + ("; K1 reads yesterday's TPO value area" if
                      name.startswith("K1") else "") + "); stops on the premium"],
            model_or_rule=f"{title}; PREREG.md Amendment {tag.upper()}",
            settings=f"research/hunt/r177_vp_trap/r177study.py run_a({'c' if c else 'b'}=True); PREREG.md Amendment {tag.upper()}",
            prereg="research/hunt/r177_vp_trap/PREREG.md",
            variants_tried=2,
            results=dict(summary=" | ".join(parts) + ".",
                         costs="dated Zerodha charges on the whole quantity; R17 fills (stops sold as stops, gaps at the open)"),
            holdout_read=True,
            verdict="rejected",
            notes=f"BH m = {d['_m']['total']}. Chosen after seeing R176 / R177 on the same year. Nothing in the app changes.",
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    print(f"2 entries (Amendment {tag.upper()})")


if __name__ == "__main__":
    main()
    if (RESULTS / "decision_a.json").exists():
        main_a()
    if (RESULTS / "decision_b.json").exists():
        main_b()
    if (RESULTS / "decision_c.json").exists():
        main_b(c=True)
