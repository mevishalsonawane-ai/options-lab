"""R207 / R208 report (PREREG.md section 5): results/report.md and the 15 experiment log entries, from results/final.json,
index_part1.json, index_fresh.json and decision.json. Plain-English notes, 20 Nov 2026.

    python3 research/hunt/r207_move_gate_late/r207report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r207cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
WNAME = {"design": "design 21 Nov 2024 - 31 Mar 2026", "holdout": "holdout 1 Apr - 5 Oct 2026 (a second look)",
         "last_year": "last year 24 Nov 2025 - 5 Oct 2026 (descriptive)", "total": "total 21 Nov 2024 - 5 Oct 2026"}
SECOND = ("The option holdout is a SECOND LOOK (PREREG section 0): a rule that clears the gate is not \"passed\" until new "
          "days agree.")
DATA = "Options: the dhan-data build to 5 Oct 2026, today's rules (V5), 1 lot, dated charges and slippage. Index: 1-minute bars, 8 Oct 2021 - 5 Oct 2026."


def rs(x) -> str:
    if x is None:
        return "n/a"
    return f"-Rs {abs(x):,.0f}" if x < 0 else f"Rs {x:,.0f}"


def chg(x) -> str:
    return f"{'+' if x >= 0 else '-'}Rs {abs(x):,.0f}"


def pct(x) -> str:
    return "n/a" if x is None else f"{x:.1f}%"


def load() -> tuple:
    return tuple(json.loads((K.RESULTS / f).read_text()) for f in ("final.json", "index_part1.json", "index_fresh.json", "decision.json"))


def report() -> str:
    r, p1, ix, d = load()
    out = ["# R207 / R208 results: the expected-move gate and the late-day break", "",
           f"Pre-registered in `PREREG.md` before any number. {DATA} {SECOND}", "",
           f"**{len(d['clears'])} of {K.N_TESTS} tests clear their gate**" + (": " + ", ".join(d["clears"]) if d["clears"] else "") + ".",
           "", ("The final run's design rows equal `design.json`." if not r["_meta"].get("design_mismatch") else
                "Design rows that differ from `design.json`: " + ", ".join(r["_meta"]["design_mismatch"]) + "."),
           "", "## R207 Part 1: does the expected move say anything? (index-only, the arms' signals, descriptive)", "",
           "Rank correlation of the expected move with the range the index really made in the next 30 minutes, and per third "
           "of the expected move (hundredths of a percent of the index).", "",
           "| signals | era | signals | rank correlation | third | expected | real 30-min range | size of the 15-min move | "
           "15-min move the signal's way | right after 15 min |", "|---|---|---|---|---|---|---|---|---|---|"]
    for src in ("R72", "R51", "LIQ"):
        for era in ("old", "recent"):
            v = p1[era][src]
            for th in ("low", "mid", "high"):
                t = v["thirds"][th]
                out.append(f"| {K.ARM_NAME[src]} | {era.upper()} | {v['signals']:,} | {v['rank_corr']} | {th} | {t['expected_bp']} | "
                           f"{t['real_range_bp']} | {t['abs_move15_bp']} | {t['move15_bp']} | {t['right15'] * 100:.1f}% |")
    for w in ("holdout", "design", "last_year", "total"):
        out += ["", f"## R207: the gate as a skip on today's rules, {WNAME[w]}", "",
                "| arm | rule | trades removed | trades | win % | net (vs B) | average loss | max drawdown (vs B) | worst month (vs B) | "
                "twin p | gate |", "|---|---|---|---|---|---|---|---|---|---|---|"]
        for a in K.ARMS:
            b = r["gate"][a]["B"]["windows"][w]
            out.append(f"| {K.ARM_NAME[a]} | **B: today's rule** | - | {b['trades']:,} | {pct(b['win'])} | {rs(b['net'])} | "
                       f"{rs(b['avg_loss'])} | {rs(b['max_dd'])} | {rs(b['worst_month']['net'])} | - | - |")
            for aa, rule in K.GATE_TESTS:
                if aa != a:
                    continue
                v = r["gate"][a][rule]
                s = v["windows"][w]
                tw = v["twin"].get(w)
                g = d["gate"][f"{a}:{rule}"]
                out.append(f"| {K.ARM_NAME[a]} | {rule}: {K.GATE_LABEL[rule]} | {v['changed'][w]:,} | {s['trades']:,} | {pct(s['win'])} | "
                           f"{rs(s['net'])} ({chg(s['net'] - b['net'])}) | {rs(s['avg_loss'])} | {rs(s['max_dd'])} "
                           f"({chg(s['max_dd'] - b['max_dd'])}) | {rs(s['worst_month']['net'])} "
                           f"({chg(s['worst_month']['net'] - b['worst_month']['net'])}) | "
                           f"{'n/a' if tw is None else format(tw['p'], '.3f')} | "
                           f"{('clears (second look)' if g['passes'] else 'fail') if w == 'holdout' else '-'} |")
    out += ["", "Why each one fails or clears:", ""]
    for a, rule in K.GATE_TESTS:
        g, v = d["gate"][f"{a}:{rule}"], r["gate"][a][rule]
        out.append(f"- **{K.ARM_NAME[a]}, {rule}** ({'clears on a second look' if g['passes'] else 'fail'}): "
                   f"{'every item holds' if g['passes'] else '; '.join(g['fails'])}. The removed trades had made "
                   f"{rs(v['effect']['design']['removed_net'])} in design and {rs(v['effect']['holdout']['removed_net'])} in the "
                   f"holdout. BH q {g['q']:.3g} (m = {d['m_total']:,}; within these 15: {g['q_family']:.3g}); design twin p "
                   f"{g['design_twin_p']:.3f}.")
    out += ["", "## R208 3a: late breaks (15:00 - 15:10) on the index, fresh days 1 Nov 2021 - 31 Mar 2022 (read once)", "",
            "| id | signals | late signals (days) | right after 15 min (95% interval) | bootstrap p | earlier signals right | "
            "average move at 15 min | right to the 15:25 close | verdict |", "|---|---|---|---|---|---|---|---|---|"]
    for h in K.INDEX_TESTS:
        t, g = ix["tests"][h], d["index"][h]
        la, ea = t["late"], t["early"]
        out.append(f"| {h} | {K.ARM_NAME[t['source']]} | {la['n']} ({la['days']}) | {la['rate'] * 100:.1f}% ({la['lo'] * 100:.1f} - "
                   f"{la['hi'] * 100:.1f}%) | {la['p']:.3f} | {ea['rate'] * 100:.1f}% of {ea['n']:,} | {t['late_move15_bp']} hundredths "
                   f"of a percent | {t['late_right_to_1525'] * 100:.1f}% | {g['verdict']} |")
    out += ["", "## R208 3b: the late-day break as a standalone option rule (1 lot, real costs)", "",
            "| cell | window | trades | win % | net | average win / loss | max drawdown | worst month | bootstrap p (mean day > 0) | gate |",
            "|---|---|---|---|---|---|---|---|---|---|"]
    for a, t in K.LATE_TESTS:
        c, g = r["late"][a][t], d["late"][f"{a}:{t}"]
        for w in ("design", "holdout", "last_year"):
            s = c["windows"][w]
            b = c["boot"].get(w)
            out.append(f"| LATE {K.ARM_NAME[a]}, square-off 15:{t} | {WNAME[w]} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} | "
                       f"{rs(s['avg_win'])} / {rs(s['avg_loss'])} | {rs(s['max_dd'])} | {rs(s['worst_month']['net'])} | "
                       f"{'n/a' if b is None else format(b['p'], '.3f')} | {('clears (second look)' if g['passes'] else 'fail') if w == 'holdout' else '-'} |")
    out += ["", "Today's arms' own trades with a late entry (after the picker and the day caps; descriptive): "
            + "; ".join(f"{K.ARM_NAME[a]} " + ", ".join(f"{w} {s['trades']} trades / {rs(s['net'])}" for w, s in r["late"][a]["today"].items())
                        for a in K.LATE_ARMS) + "."]
    return "\n".join(out) + "\n"


def entries():
    import yaml
    r, p1, ix, d = load()
    base = dict(date="2026-11-20", doc="research/hunt/r207_move_gate_late/results/report.md",
                settings="research/hunt/r207_move_gate_late/r207cfg.py; PREREG.md sections 2 - 4",
                prereg="research/hunt/r207_move_gate_late/PREREG.md", variants_tried=1, holdout_read=True)
    opt_data = ("the dhan-data branch of options-lab (Dhan v2, fetched 6 Oct 2026) in the lake's layout (r202data.py); R51 clean "
                "stores 21 Nov 2024 - 5 Oct 2026")
    opt_period = dict(design="2024-11-21..2026-03-31 (four walk-forward quarters Apr 2025 - Mar 2026)",
                      holdout="2026-04-01..2026-10-05, a SECOND LOOK (the pattern was seen in slices of the whole span); "
                              "results/HOLDOUT_READ")
    unds = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]

    def write(e):
        (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))

    for a, rule in K.GATE_TESTS:
        v, b, g = r["gate"][a][rule], r["gate"][a]["B"], d["gate"][f"{a}:{rule}"]

        def line(w):
            s, t = v["windows"][w], b["windows"][w]
            return (f"{w} {s['trades']} tr, {s['win']}% win, Rs {s['net']:,}, DD Rs {s['max_dd']:,}, worst month Rs "
                    f"{s['worst_month']['net']:,} (B: {t['trades']} tr, {t['win']}% win, Rs {t['net']:,}, DD Rs {t['max_dd']:,}, worst "
                    f"month Rs {t['worst_month']['net']:,}); {v['changed'][w]} trades removed")
        write(dict(id=f"r207-{a.lower()}-{rule.lower()}", title=f"R207 {K.ARM_NAME[a]}: {K.GATE_LABEL[rule]}, on top of today's rule (V5)",
                   family="daily-filters", date=base["date"], doc=base["doc"],
                   hypothesis=(f"On {K.ARM_NAME[a]}, '{K.GATE_LABEL[rule]}' on top of today's rule (V5) is better than V5 alone in "
                               "design and holdout, in 3 of 4 walk-forward quarters, with no higher drawdown, better than random "
                               "skips, after BH"),
                   data_version=opt_data, period=opt_period, instruments=unds if a != "LIQ" else ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"],
                   features=["signal source: the index (the expected move and the range ratio read index minutes before the entry minute)",
                             "buy the option; stops, breakevens and locks on the premium"],
                   model_or_rule=f"{rule}: {K.GATE_LABEL[rule]} (r207lib.features, wf_keep / rt_keep)", settings=base["settings"],
                   prereg=base["prereg"], variants_tried=1,
                   results=dict(summary=(line("design") + "; " + line("holdout") + f"; holdout twin p {g['twin_p']:.4f}, BH q {g['q']:.3g} "
                                         f"(m = {d['m_total']:,}). " + ("Clears the gate on a second look (not a pass)." if g["passes"]
                                                                        else "Does not pass: " + "; ".join(g["fails"]))),
                                costs="dated Zerodha charges, R17 fills, 1 lot"),
                   holdout_read=True, verdict="inconclusive" if g["passes"] else "rejected",
                   notes="Research only; nothing in the app changes."))
    for a, t in K.LATE_TESTS:
        c, g = r["late"][a][t], d["late"][f"{a}:{t}"]
        s = "; ".join(f"{w} {c['windows'][w]['trades']} tr, {c['windows'][w]['win']}% win, Rs {c['windows'][w]['net']:,}, DD Rs "
                      f"{c['windows'][w]['max_dd']:,}" for w in ("design", "holdout"))
        write(dict(id=f"r208-late-{a.lower()}-{t}", title=f"R208 LATE: {K.ARM_NAME[a]} breaks at 15:00 - 15:10, square-off 15:{t}, standalone",
                   family="patterns-indicators", date=base["date"], doc=base["doc"],
                   hypothesis=(f"Buying the arm's option on a {K.ARM_NAME[a]} break with an entry minute of 15:00 - 15:10 and squaring "
                               f"off at 15:{t} makes money after costs in design and holdout (a slice seen on the index in R206)"),
                   data_version=opt_data, period=opt_period, instruments=unds,
                   features=["signal source: the index (the arm's own break); buy the 1-ITM option; today's exits on the premium"],
                   model_or_rule=f"LATE-{a[1:]}-{t}: late breaks only, today's exits (V5), square-off 15:{t}, no picker, 1 lot",
                   settings=base["settings"], prereg=base["prereg"], variants_tried=1,
                   results=dict(summary=s + f"; holdout bootstrap p {g['p']:.4f}, BH q {g['q']:.3g} (m = {d['m_total']:,}). "
                                + ("Clears the gate on a second look." if g["passes"] else "Does not pass: " + "; ".join(g["fails"])),
                                costs="dated Zerodha charges, R17 fills, 1 lot"),
                   holdout_read=True, verdict="inconclusive" if g["passes"] else "rejected",
                   notes="Research only; nothing in the app changes. Holding past 15:15 adds exposure and sits near the broker's own square-off."))
    for h in K.INDEX_TESTS:
        t, g = ix["tests"][h], d["index"][h]
        la = t["late"]
        write(dict(id=f"r208-{h.lower()}", title=f"R208 {h}: are {K.ARM_NAME[t['source']]} breaks at 15:00 - 15:10 right more than half the time?",
                   family="market-facts", date=base["date"], doc=base["doc"],
                   hypothesis=(f"{K.ARM_NAME[t['source']]} signals with an entry minute of 15:00 - 15:10 see the index go their way after "
                               "15 minutes more than 50% of the time, on days never measured (1 Nov 2021 - 31 Mar 2022)"),
                   data_version="the dhan-data branch's 1-minute index candles, 8 Oct 2021 - 5 Oct 2026 (r206data.py); no option data",
                   period=dict(design="2022-04-01..2026-10-05 (R206: where the slice was seen)",
                               holdout="2021-11-01..2022-03-31 (fresh, read once; results/FRESH_READ)"),
                   instruments=unds, features=["signal source: the index only"],
                   model_or_rule="share of late signals with a 15-minute index move their way above 0; day-block bootstrap against 50%",
                   settings=base["settings"], prereg=base["prereg"], variants_tried=1,
                   results=dict(summary=(f"{la['n']} late signals on {la['days']} days: right {la['rate'] * 100:.1f}% (95% "
                                         f"{la['lo'] * 100:.1f} - {la['hi'] * 100:.1f}%), bootstrap p {la['p']:.4f}, BH q {g['q']:.3g}; "
                                         f"earlier signals right {t['early']['rate'] * 100:.1f}% of {t['early']['n']}. Verdict: {g['verdict']}"
                                         + ("" if g["holds"] else " (" + "; ".join(g["fails"]) + ")") + "."),
                                costs="none (index direction only)"),
                   holdout_read=True, verdict="inconclusive" if g["holds"] else "rejected",
                   notes="Research only. The same sign as R206's two eras, on too few days to stand alone."))


if __name__ == "__main__":
    (K.RESULTS / "report.md").write_text(report())
    entries()
    print("report.md and entries written")
