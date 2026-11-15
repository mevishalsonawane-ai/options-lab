"""R183 experiment entries: one per filter idea (5 kinds, each with its grid, both arms), plus the one R51 test."""
from __future__ import annotations

import json
from pathlib import Path

import yaml

import r183lib as LB

HERE = Path(__file__).resolve().parent
ENTRIES = HERE.parents[2] / "research" / "experiments" / "entries"
RESULTS = HERE / "results"
KINDS = {
    "adx": ("ADX(14) on 5-minute index bars below 15 / 20 / 25", ("ADX15", "ADX20", "ADX25")),
    "efficiency-ratio": ("Kaufman efficiency ratio over 6 / 12 five-minute bars below 0.20 / 0.30",
                         ("ER6_20", "ER6_30", "ER12_20", "ER12_30")),
    "twap-crossings": ("crossings of today's running TWAP (5-minute closes) >= 3 / 5 / 7", ("TX3", "TX5", "TX7")),
    "range-so-far": ("today's range so far below 40% / 60% of ADR20, or below 70% / 85% of the usual range by this time",
                     ("RF40", "RF60", "RT70", "RT85")),
    "choppiness": ("Choppiness index(14) on 5-minute bars above 55 / 61.8", ("CH55", "CH62")),
}


def rs(x):
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def base(**kw):
    e = dict(
        id=kw.pop("id"),
        title=kw.pop("title"),
        family="daily-filters",
        date="2026-11-15",
        doc="research/hunt/r183_sideways_filter/results/report.md",
        data_version=("Drive lake gdrive:IraAlgo-data-lake copied 15 Nov 2026, ending 19 Oct 2026; R51 Amendment A clean "
                      "stores built to 19 Oct (R106 clean_post)"),
        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
        features=["signal source: the index the arm signals on (1-minute / 5-minute bars strictly before the signal bar); "
                  "no option prices"],
        settings="research/hunt/r183_sideways_filter/r183lib.py; PREREG.md section 3",
        prereg="research/hunt/r183_sideways_filter/PREREG.md",
    )
    e.update(kw)
    return e


def main():
    s = json.loads((RESULTS / "selection.json").read_text())
    t = json.loads((RESULTS / "test.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    n = 0
    for k, (rule, vs) in KINDS.items():
        parts = []
        for a in LB.ARMS:
            parts.append(f"{a} (B {rs(s[a]['B']['net'])}, {s[a]['B']['trades']} trades): " + ", ".join(
                f"{v} {rs(s[a][v]['net'])} ({s[a][v]['trades']} trades; gain H1 {rs(s[a][v]['gain_H1'])} / H2 "
                f"{rs(s[a][v]['gain_H2'])})" for v in vs))
        chosen = [a for a in LB.ARMS if s[a]["chosen"] in vs]
        e = base(
            id=f"r183-sideways-{k}",
            title=f"R183: skip R51 / R72 entries when the index is sideways by {k} (on picker + breakeven, 1 lot)",
            hypothesis=("Boss: 'How could we ignore sideways markets'. Skipping new R51 / R72 entries when the index is "
                        f"sideways ({rule}) improves today's live rules (R179 V4)."),
            period=dict(design="2024-11-20..2025-10-19 (selection; halves to / from 1 May 2025)",
                        holdout=("2025-10-20..2026-10-19 (read once for the chosen variant)" if chosen
                                 else "2025-10-20..2026-10-19 (not tested for this kind: not chosen)")),
            model_or_rule=f"skip when {rule}; a skipped entry still holds its side (post-walk, as the app's picker)",
            variants_tried=len(vs) * len(LB.ARMS),
            results=dict(summary=" | ".join(parts) + ".", costs="dated Zerodha charges on the whole quantity; R17 fills"),
            holdout_read=bool(chosen),
            verdict="rejected",
            notes=(f"BH m = {d['_m']['total']:,} (32 selection comparisons + 1 test). "
                   + ("Chosen for R51 (TX7) and failed the one test; see r183-r51-twap7-test. " if chosen else
                      "No variant of this kind qualified (beat B in both halves). ") + "Nothing in the app changes."),
        )
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
        n += 1
    g, tw = d["R51"], t["twins"]["R51"]
    y, b = t["year"]["S@1"]["R51"], t["year"]["B@1"]["R51"]
    f, bf = t["final"]["S@1"]["R51"], t["final"]["B@1"]["R51"]
    e = base(
        id="r183-r51-twap7-test",
        title="R183 test: R51 skips a new entry after 7+ TWAP crossings today (picker + breakeven, last year, 1 lot)",
        hypothesis="The selection's choice for R51 (TX7) beats today's live rules (V4) and random skips on the last year.",
        period=dict(design="2024-11-20..2025-10-19 (selection: +Rs 24,911, both halves positive)",
                    holdout="2025-10-20..2026-10-19 (read once; final 2026-04-01..2026-10-13)"),
        model_or_rule="skip when >= 7 crossings of today's running TWAP (5-minute closes) before the signal bar",
        variants_tried=1,
        results=dict(summary=(f"Year {y['trades']} trades win {100 * y['win']:.1f}% {rs(y['net'])} vs B {b['trades']} / "
                              f"{100 * b['win']:.1f}% / {rs(b['net'])}; final {f['trades']} / {rs(f['net'])} vs "
                              f"{bf['trades']} / {rs(bf['net'])}; skipped {tw['skipped']}; random-skip twins mean "
                              f"{rs(tw['mean'])} (2,000), p {g['p_emp']}, BH q {g['q_all']}; failed: "
                              + "; ".join(g["fails"]) + "."),
                     costs="dated Zerodha charges on the whole quantity; R17 fills"),
        holdout_read=True,
        verdict="rejected",
        notes=f"BH m = {d['_m']['total']:,}. Parity with R179 V4 exact. Nothing in the app changes.",
    )
    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True, width=120))
    n += 1
    print(n, "entries")


if __name__ == "__main__":
    main()
