"""R193 report (PREREG.md section 7): results/summary.md, results/report.md and the experiment log entries, from
results/repro.json, span.json and decision.json.

    python3 research/hunt/r193_arms_nov24/r193report.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r193cfg as K  # noqa: E402

ENTRIES = K.ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r193_arms_nov24/results/report.md"
PREREG = "research/hunt/r193_arms_nov24/PREREG.md"
DATA = ("Drive lake copy of 17 Nov 2026 (R190's flat folder ~/.cache/iraalgo/r190/lake: index_1m, options_rolling_1m "
        "WEEK + BANKNIFTY MONTH, with the 20 Oct - 13 Nov 2026 fill); R51 clean stores 3 Aug 2020 - 13 Nov 2026 (R190)")
FAMILY = {"R72": "patterns-indicators", "R51": "patterns-indicators", "LIQ": "liquidity"}
INSTR = {"R72": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
         "R51": ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
         "LIQ": ["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"]}


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    s = f"{abs(x):,.0f}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{x:.1f}%"


def row(name: str, s: dict, verdict: str = "", fails: str = "") -> str:
    wm = s["worst_month"]
    acc = "" if s["account_stop_ok"] else " (breach)"
    return (f"| {name} | {s['trades']:,} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
            f"{rs(s['max_dd'])}{acc} | {rs(wm['net'])} ({wm['month']}) | {rs(s['net_wo_top5'])} | {verdict} | {fails} |")


HEAD = ("| variant | trades | win % | net | avg win / loss | max DD | worst month | net w/o top 5 | verdict | failed checks |\n"
        "|---|---:|---:|---:|---|---:|---|---:|---|---|")


def tables(r: dict, dec: dict, key: str = "windows") -> list:
    out = []
    for arm in K.ARMS:
        out.append(f"\n### {K.ARM_NAME[arm]}\n")
        for w in K.WINDOWS:
            out.append(f"\n**{K.WINDOW_NAME[w]}**\n\n{HEAD}")
            out.append(row("A (own exits)", r["A:" + arm][key][w]))
            for v in K.TESTS:
                if K.VARIANTS[v][0] != arm:
                    continue
                d = dec["variants"][v]
                verdict = ("HELPS" if d["helps"] else "no") if w == "total" else ""
                fails = "; ".join(d["fails"]) if w == "total" else ""
                out.append(row(f"{v}: {K.LABEL[v]}", r[v][key][w], verdict, fails))
    return out


def repro_lines(rp: dict) -> list:
    out = ["| check | trades | net | published | gap | reproduced |", "|---|---:|---:|---|---|---|"]
    for k, v in rp.items():
        if not isinstance(v, dict) or "published" not in v:
            continue
        out.append(f"| {k} | {v['trades']:,} | {rs(v['net'])} | {v['published'][0]:,} / {rs(v['published'][1])} | "
                   f"{v['gap_trades']:+d} trades, {v['gap_net']:+,} | {'yes' if v['reproduced'] else 'NO'} |")
    return out


def main():
    rp = json.loads((K.RESULTS / "repro.json").read_text())
    r = json.loads((K.RESULTS / "span.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    helps = dec["helps"]
    outcome = [f"- **{K.ARM_NAME[a]}**: " + (("HELPS: " + ", ".join(f"{v} ({K.LABEL[v]})" for v in helps[a]))
                                              if helps[a] else "nothing passes the gate") for a in K.ARMS]
    twin = ["| variant | twin p (2,000) | normal p | BH q (m = 4,765) | trades the exit changed | trades removed |",
            "|---|---:|---:|---:|---:|---:|"]
    for v in K.TESTS:
        t, d = r[v]["twin"], dec["variants"][v]
        twin.append(f"| {v} | {t['p']:.4f} | {t['p_normal']:.3g} | {d['q']:.3g} | {t['changed']:,} | {t['removed']:,} |")
    rem = ["| variant | W1 picker / A10 removed | W2 picker / A10 removed |", "|---|---|---|"]
    for v in K.TESTS:
        x = r[v]["removed"]
        rem.append(f"| {v} | {x['W1']['picker']:,} / {x['W1']['a10']:,} | {x['W2']['picker']:,} / {x['W2']['a10']:,} |")
    marg = ["| comparison | W1 net | W2 net | W1 max DD | W2 max DD |", "|---|---:|---:|---:|---:|"]
    for a in ("R72", "R51"):
        for x, y in ((f"{a}-V5A10", f"{a}-V5"), (f"{a}-V5", f"{a}-V4"), (f"{a}-V4", f"{a}-P")):
            wx, wy = r[x]["windows"], r[y]["windows"]
            marg.append(f"| {x} minus {y} | {rs(wx['W1']['net'] - wy['W1']['net'])} | {rs(wx['W2']['net'] - wy['W2']['net'])} | "
                        f"{rs(wx['W1']['max_dd'] - wy['W1']['max_dd'])} | {rs(wx['W2']['max_dd'] - wy['W2']['max_dd'])} |")
    wx, wy = r["LIQ-V5"]["windows"], r["LIQ-V4"]["windows"]
    marg.append(f"| LIQ-V5 minus LIQ-V4 | {rs(wx['W1']['net'] - wy['W1']['net'])} | {rs(wx['W2']['net'] - wy['W2']['net'])} | "
                f"{rs(wx['W1']['max_dd'] - wy['W1']['max_dd'])} | {rs(wx['W2']['max_dd'] - wy['W2']['max_dd'])} |")
    summary = ["# R193 summary: the live arms from Nov 2024 (current exits, picker, FB-R72-A10)", "",
               "Research only; nothing in the app changes. Signal on the index; options bought; 1 lot; dated Zerodha "
               "charges and R17 slippage. Gate (PREREG section 6): vs the arm's own exits, higher net AND lower max "
               "drawdown in both W1 and W2, 40+ trades in each, net without the top 5 above A's net, twin p < 0.05, "
               "BH q <= 0.10 (m = 4,765).", "", "## Outcome", "", *outcome, "",
               "## Reproduction first", "", *repro_lines(rp), "",
               "## Per variant and window (1 lot)", *tables(r, dec), "",
               "## Twins and BH", "", *twin, ""]
    (K.RESULTS / "summary.md").write_text("\n".join(summary) + "\n")
    report = ["# R193 report: does recent research help R72, R51 and Liquidity 15+5 from Nov 2024?", "",
              "Boss asked whether recent research helps the live arms, and chose *\"Check only from nov 24\"*. "
              "Pre-registration: `PREREG.md` (committed before any number). Signal source of every rule: the index.", "",
              "## Outcome", "", *outcome, "",
              "## 1. Reproduction (before any new number)", "", *repro_lines(rp), "",
              "## 2. Results at 1 lot", *tables(r, dec), "",
              "## 3. Twins and BH", "", *twin, "",
              "## 4. What the filters removed", "", *rem, "",
              "## 5. Each piece's own effect (descriptive, not gated)", "", *marg, "",
              "## 6. Funded lots (Rs 56,000 a trade, cap 3; descriptive, never gated)", "",
              "Each trade on its own: positions that overlap are not made to share the Rs 56k, so this flatters.",
              *tables(r, dec, "funded"), "",
              "## 7. Honesty", "",
              "- W1 (Nov 2024 - Mar 2026) is in-sample for R151's pickers (trained 20 Nov 2024 - 31 Mar 2026) and for "
              "FB-R72-A10's cut (chosen in R140a on 20 Nov 2024 - 31 Mar 2026).",
              "- W2 (Apr - 13 Nov 2026) is out-of-sample for the pickers but not fresh: Apr - Oct 2026 was read by R151, "
              "R152, R176, R179, R186 and R190. For A10, Apr - 13 Oct 2026 was R140a's final; only 14 Oct - 13 Nov is "
              "unseen by its choice.",
              "- V4's breakeven (R162 / R179) and V5's locks (R186) were designed on Oct 2025 - Oct 2026, inside this span.",
              "- Nov 2024 onward is the regime where R72 and R51 made all their money (R190: R72 lost Rs 4.07 lakh in "
              "Dec 2021 - Oct 2024). Nothing here speaks for older years.",
              "- The funded table sizes each trade alone; R72 can hold several indices at once.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(report) + "\n")
    for v in K.TESTS:
        arm = K.VARIANTS[v][0]
        d = dec["variants"][v]
        w = r[v]["windows"]
        b = r["A:" + arm]["windows"]

        def ln(s):
            return f"{s['trades']} tr, {s['win']:.1f}% win, {rs(s['net'])}, DD {rs(s['max_dd'])}, worst month {rs(s['worst_month']['net'])}"
        e = dict(
            id=f"r193-{v.lower()}", title=f"R193 arms from Nov 2024: {K.LABEL[v]} vs the arm's own exits",
            family=FAMILY[arm], date="2026-11-17", doc=DOC,
            hypothesis=f"{K.LABEL[v]} beats {K.ARM_NAME[arm]}'s own exits (no picker) on 1 Nov 2024 - 13 Nov 2026: higher "
                       "net and lower drawdown in W1 and W2, robust to the top 5, better than random twins, after BH",
            data_version=DATA,
            period=dict(design="W1 2024-11-01..2026-03-31 (picker and FB-R72-A10 in-sample; nothing fitted here)",
                        holdout="W2 2026-04-01..2026-11-13 (picker out-of-sample but read by R151-R190; A10 unseen only "
                                "from 2026-10-14); read once (results/SPAN_READ)"),
            instruments=INSTR[arm],
            features=["signal source: the index (the arm's own 5-minute breaks; picker / A10 read index features at or "
                      "before the decision minute)", "buy the option; stops, breakevens and locks on the premium"],
            model_or_rule=f"{K.LABEL[v]} (r186engine.price; r152picker; R140a A10 >= 1.3206)",
            settings="research/hunt/r193_arms_nov24/r193cfg.py; PREREG.md section 3", prereg=PREREG, variants_tried=1,
            results=dict(summary=f"W1 {ln(w['W1'])} (A: {ln(b['W1'])}); W2 {ln(w['W2'])} (A: {ln(b['W2'])}); total "
                                 f"{ln(w['total'])} (A: {ln(b['total'])}); twin p {d['twin_p']:.4f}, BH q {d['q']:.3g} "
                                 f"(m = {K.M_TOTAL:,}). " + ("HELPS." if d["helps"] else "Does not help: " + "; ".join(d["fails"])),
                         costs="dated Zerodha charges, R17 fills, 1 lot (funded-lots table descriptive)"),
            holdout_read=True, verdict="paper-only" if d["helps"] else "rejected",
            notes="Research only; nothing in the app changes. The live rules are Boss's own exceptions (CLAUDE.md); this "
                  "entry only records whether the data show they help.")
        (ENTRIES / f"r193-{v.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    print("summary, report and entries written")


if __name__ == "__main__":
    main()
