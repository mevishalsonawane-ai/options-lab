"""R161 report and experiment entries from results/design.json, final.json and decision.json.

    python research/hunt/r161_donchian_entries/r161report.py && python research/experiments/validate.py

Writes results/report.md (tables; the plain-English summary on top is written by hand in results/summary_head.md and
copied in first) and research/experiments/entries/r161-*.yaml (one per arm x rule x lots x look, failures included).
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r161feat as F  # noqa: E402

ROOT = HERE.parents[2]
RES = HERE / "results"
ENTRIES = ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r161_donchian_entries/results/report.md"
PREREG = "research/hunt/r161_donchian_entries/PREREG.md"
ARMS = ("R72", "R51")
LOTS = (1, 2, 3)
RULES = F.SINGLES + ("C1",)
ARM_NAME = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend (10, 3)"}
ARM_SRC = {"R72": "index 5-min bars of all five indices (Donchian 20, 4 of 5)",
           "R51": "the index's own 5-min bars (Donchian 20, kept when Supertrend (10, 3) agrees)"}
LOOK_NAME = {"base": "on today's rules", "picker": "on top of R151's live picker"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(float(x))
    s = f"{abs(x):,}"
    if abs(x) >= 100000:                     # Indian grouping: 1,23,456
        t = str(abs(x))
        head, tail = t[:-3], t[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts) + "," + tail
    return ("-Rs " if x < 0 else "Rs ") + s


def drs(x) -> str:
    """A change: +Rs / -Rs."""
    return ("+" if round(float(x)) > 0 else "") + rs(x)


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.1f}%"


def pv(x) -> str:
    return "-" if x is None else (f"{x:.3f}" if x >= 0.001 else f"{x:.1e}")


def load():
    d = json.loads((RES / "design.json").read_text())
    f = json.loads((RES / "final.json").read_text())
    dec = json.loads((RES / "decision.json").read_text())
    return d, f, dec


def rec(d, f, a, k, look):
    """(wf stats, final stats, twin dict or None) of one cell for a look."""
    if look == "base":
        return d[a][k]["wf"], f[a][k]["final"], f[a][k].get("twin")
    return d[a][k]["picker"]["wf"], f[a][k]["picker"]["final"], f[a][k]["picker"].get("twin")


def full_row(name, s, tw=None, extra="") -> str:
    return (f"| {name} | {s['trades']} | {pct(s['win'])} | {pct(s['be_win'])} | {rs(s['net'])} | {rs(s['avg_win'])} | "
            f"{rs(s['avg_loss'])} | {rs(s['worst_day'])} | {rs(s['max_dd'])} | {rs(s['net_ex5'])} | "
            f"{pv(tw['p']) if tw else '-'} |{extra}")


HEAD = ("| rule | trades | win | break-even win | net | avg win | avg loss | worst day | max DD | net without 5 best | "
        "random-skip p |")
SEP = "|---|---|---|---|---|---|---|---|---|---|---|"


def tables(d, f, dec) -> list:
    out = []
    for look in ("base", "picker"):
        for a in ARMS:
            out += [f"### {ARM_NAME[a]}, {LOOK_NAME[look]}", ""]
            # summary at 1 lot: win change and net change in WF and final, verdict
            out += ["**Walk-forward year (Apr 2025 - Mar 2026) and final (1 Apr - 13 Oct 2026), 1 lot:**", "",
                    "| rule | what it takes | WF trades | WF win (change) | WF net (change) | final trades | final win "
                    "(change) | final net (change) | p (win / net) | q (m = 2,332) | passes? |",
                    "|---|---|---|---|---|---|---|---|---|---|---|"]
            bw, bf, _ = rec(d, f, a, "B0@1", look)
            out.append(f"| {'B0' if look == 'base' else 'picker'} | today's rules{' + picker' if look == 'picker' else ''} "
                       f"| {bw['trades']} | {pct(bw['win'])} | {rs(bw['net'])} | {bf['trades']} | {pct(bf['win'])} | "
                       f"{rs(bf['net'])} | - | - | - |")
            for r in RULES:
                w, fi, tw = rec(d, f, a, f"{r}@1", look)
                g = dec[f"{look}:{a}:{r}@1"]
                dw = None if w["win"] is None else 100 * (w["win"] - bw["win"])
                dfw = None if fi["win"] is None else 100 * (fi["win"] - bf["win"])
                out.append(
                    f"| {r} {F.NAMES[r]} | {F.PLAIN[r]} | {w['trades']} | {pct(w['win'])} ({dw:+.1f}) | {rs(w['net'])} "
                    f"({drs(w['net'] - bw['net'])}) | {fi['trades']} | {pct(fi['win'])} ({dfw:+.1f}) | {rs(fi['net'])} "
                    f"({drs(fi['net'] - bf['net'])}) | {pv(tw['p_win'])} / {pv(tw['p_net'])} | {pv(g['q_all'])} | "
                    f"{'**yes**' if g['passed'] else 'no'} |")
            out.append("")
            out += ["**Final period, every lot size** (random-skip p = the larger of the win and net p, 1,000 twins):", "",
                    "| lots | " + HEAD[2:], "|---" + SEP]
            for n in LOTS:
                _, bf, _ = rec(d, f, a, f"B0@{n}", look)
                out.append(f"| {n} " + full_row("B0" if look == "base" else "picker", bf))
                for r in RULES:
                    _, fi, tw = rec(d, f, a, f"{r}@{n}", look)
                    out.append(f"| {n} " + full_row(f"{r} {F.NAMES[r]}", fi, tw))
            out.append("")
            out += ["**Walk-forward year, every lot size:**", "", "| lots | " + HEAD[2:], "|---" + SEP]
            for n in LOTS:
                bw, _, _ = rec(d, f, a, f"B0@{n}", look)
                out.append(f"| {n} " + full_row("B0" if look == "base" else "picker", bw))
                for r in RULES:
                    w, _, _ = rec(d, f, a, f"{r}@{n}", look)
                    out.append(f"| {n} " + full_row(f"{r} {F.NAMES[r]}", w))
            out.append("")
    return out


def main():
    d, f, dec = load()
    head = (RES / "summary_head.md").read_text() if (RES / "summary_head.md").exists() else "# R161\n"
    body = [head.rstrip(), "", "## Full tables", "",
            "Design (20 Nov 2024 - 31 Mar 2026), with the four walk-forward quarters, is in `design.json`; the final "
            "(1 Apr - 13 Oct 2026, read once) in `final.json`; every gate in `decision.json`; **all 168 raw p-values in "
            "`raw_pvalues.csv`** (for the joint BH with R162 and R163).", ""]
    body += ["**C1's choices (made on design data only):**", ""]
    for a in ARMS:
        ch = d[a]["c1_choices"]
        body.append(f"- {ARM_NAME[a]}: WF quarters " + ", ".join(f"{k} {' + '.join(v) or 'none'}" for k, v in ch.items()
                                                                  if k != "final")
                    + f"; final: {' + '.join(ch['final']) or 'none'}.")
    body.append("")
    body += ["**How often each filter could not be computed (taken as today), design candidates:**", ""]
    for a in ARMS:
        dc = d[a]["decision_counts"]
        body.append(f"- {a}: " + ", ".join(f"{r} {v['unknown']}" for r, v in dc.items()) +
                    f" (of {d[a]['candidates']} candidates).")
    body.append("")
    body += tables(d, f, dec)
    (RES / "report.md").write_text("\n".join(body) + "\n")

    n = 0
    for look in ("base", "picker"):
        for a in ARMS:
            for r in RULES:
                for lots in LOTS:
                    k = f"{r}@{lots}"
                    g = dec[f"{look}:{a}:{k}"]
                    w, fi, tw = rec(d, f, a, k, look)
                    bw, bf, _ = rec(d, f, a, f"B0@{lots}", look)
                    ref = "today's rules" if look == "base" else "R151's picker"
                    e = dict(
                        id=f"r161-{look}-{a.lower()}-{r.lower()}-{lots}lot",
                        title=(f"R161 {r} {F.NAMES[r]} on {ARM_NAME[a]}, {LOOK_NAME[look]}, {lots} lot"
                               f"{'s' if lots > 1 else ''}: take a break only when {F.PLAIN[r]}"),
                        family="daily-filters",
                        date="2026-11-12",
                        doc=DOC,
                        hypothesis=(f"Boss (12 Nov): \"I want to increase my win rate [of the] Donchian family\". Taking "
                                    f"only the breaks where {F.PLAIN[r]} raises the win rate by >= 5 points with net not "
                                    f"lower than {ref} in the walk-forward year and the final; net > 0 in every period; "
                                    "random-skip twins p = max(p_win, p_net); BH q <= 0.10 with m = 2,332 (R162 / R163 "
                                    "added by the coordinator); >= 40 final trades."),
                        data_version=("Drive lake gdrive:IraAlgo-data-lake (index_1m incl. INDIAVIX, index_daily, "
                                      "options_rolling_1m WEEK) copied 12 Nov 2026; R51 Amendment A clean stores"),
                        period=dict(design="2024-11-20..2026-03-31 (walk-forward window Apr 2025 - Mar 2026)",
                                    holdout="2026-04-01..2026-10-13 read once by R161 (already read by R51-R160)"),
                        instruments=["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"],
                        features=[f"signal source: {ARM_SRC[a]} (unchanged); the entry filter reads the index's 5-min "
                                  "bars (and India VIX minutes for E10, the five indices for E11)"],
                        model_or_rule=f"{ARM_NAME[a]} {LOOK_NAME[look]}, filter {r} {F.NAMES[r]}, {lots} lots",
                        settings="research/hunt/r161_donchian_entries/r161feat.py; PREREG.md section 2",
                        prereg=PREREG,
                        variants_tried=168,
                        results=dict(
                            summary=(f"Walk-forward: {w['trades']} trades, win {pct(w['win'])} ({ref} {pct(bw['win'])}), "
                                     f"net {rs(w['net'])} ({rs(bw['net'])}). Final: {fi['trades']} trades, win "
                                     f"{pct(fi['win'])} ({pct(bf['win'])}), break-even win {pct(fi['be_win'])}, net "
                                     f"{rs(fi['net'])} ({rs(bf['net'])}), worst day {rs(fi['worst_day'])}, max DD "
                                     f"{rs(fi['max_dd'])}, without 5 best {rs(fi['net_ex5'])}; random-skip p_win "
                                     f"{pv(tw['p_win'])}, p_net {pv(tw['p_net'])}, BH q {pv(g['q_all'])}. "
                                     + ("PASS." if g["passed"] else "Fails: " + "; ".join(g["fails"]) + ".")),
                            costs=(f"dated Zerodha charges on the whole {lots}-lot quantity, R17 fills (slippage from the "
                                   "order's size; stops -10 bps and 2x the half-spread, gaps at the open)")),
                        holdout_read=True,
                        verdict="paper-only" if g["passed"] else "rejected",
                        notes="Signal on the underlying; nothing changes in the app or live.",
                    )
                    (ENTRIES / f"{e['id']}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, allow_unicode=True,
                                                                          width=120))
                    n += 1
    print(n, "entries; report", RES / "report.md")


if __name__ == "__main__":
    main()
