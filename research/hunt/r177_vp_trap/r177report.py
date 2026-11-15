"""R177 report: results/report.md from results/year.json and results/decision.json, with the hand-written summary on top.

    python research/hunt/r177_vp_trap/r177report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r176_step_trail_500"))
sys.path.insert(0, str(HERE))
import r176report as RP  # noqa: E402  (rs, pct, month names)

RESULTS = HERE / "results"
ARMS = ("LIQ", "R72", "R51", "ALL")
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "ALL": "All three"}
VARS = ("A", "X1", "X2", "K1", "B", "B+X1", "B+X2", "B+K1")
PER = {"early": "early (20 Oct 2025 - 31 Mar 2026)", "final": "final (1 Apr - 13 Oct 2026)",
       "six": "last 6 months (20 Apr - 19 Oct 2026)", "year": "whole year (20 Oct 2025 - 19 Oct 2026)"}


def table(r: dict, per: str, lots: int, vars_: tuple = VARS) -> list:
    out = [f"### {PER[per]}, {lots} lot{'s' if lots > 1 else ''} ({r[per]['sessions']} sessions)\n",
           "| arm | variant | trades | win | net | avg win / avg loss | worst day | worst month | max drawdown |",
           "|---|---|---|---|---|---|---|---|---|"]
    for a in ARMS:
        for v in vars_:
            if a == "LIQ" and v.startswith("B"):
                continue
            s = r[per][f"{v}@{lots}"][a]
            wm = s["worst_month"]
            out.append(f"| {NAME[a]} | {v} | {s['trades']} | {RP.pct(s['win'])} | **{RP.rs(s['net'])}** | "
                       f"{RP.rs(s['avg_win'])} / {RP.rs(-s['avg_loss'] if s['avg_loss'] else 0)} | {RP.rs(s['worst_day'])} | "
                       f"{RP.mname(wm['month'])} {RP.rs(wm['net'])} | {RP.rs(-s['max_dd'] if s['max_dd'] else 0)} |")
    return out + [""]


def build() -> str:
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    out = [(RESULTS / "summary.md").read_text().rstrip() + "\n", "## Data, checks and method\n"]
    out.append("- Parity, today's rules at 1 lot, 1 Apr - 13 Oct 2026: " + "; ".join(
        f"{NAME[a]} {r['parity'][a]['trades']} / {RP.rs(r['parity'][a]['net'])} (R172 {r['parity'][a]['published'][0]} / "
        f"{RP.rs(r['parity'][a]['published'][1])})" for a in ("LIQ", "R72", "R51")) + "; the engine with no rule priced "
        "every candidate exactly as R101.")
    out.append("- Signal source: the index for every arm and every filter (yesterday's TPO profile and today's 1-minute "
               "closes of the traded index). Costs: dated Zerodha charges, R17 slippage, stops sold as stops.")
    out.append("- How often each rule acted (1 lot): " + "; ".join(
        f"{k} {v['candidates_changed']} candidates" + (f" ({v['trades_trapped']} trades exited by the trap)" if v["trades_trapped"]
                                                         else "") for k, v in r["changed"].items()) + ".")
    out.append("- Honesty: Apr - Oct 2026 has been read by many studies; one year is a short sample; the pickers were fitted "
               "on trades up to Mar 2026 (picker numbers in the early part are in-sample). Variants: A today's rules; B = A "
               "+ R151 picker (R72 / R51; Liquidity has none); B+X = picker + rule.\n")
    out.append(f"## Gates (1 lot; BH m = {d['_m']['total']:,})\n")
    out.append("| arm : rule | passed | passes except the account-stop gate | twin p | BH q | q within R177 | failed gates |"
               "\n|---|---|---|---|---|---|---|")
    for k, g in d.items():
        if k == "_m":
            continue
        out.append(f"| {k} | {'yes' if g['passed'] else '**no**'} | {'yes' if g['passed_except_account_stop'] else 'no'} | "
                   f"{g['twin_p']} | {g['q_all']} | {g['q_r177']} | {'; '.join(g['fails'])} |")
    out.append("\nTwins: " + ", ".join(f"{k} p {v['p']} (twin mean {RP.rs(v['mean'])}, {v['changed']} changed)"
                                       for k, v in r["twins"].items()) + ".\n")
    out.append("## Tables\n")
    for per in ("year", "early", "final", "six"):
        out += table(r, per, 1)
    out += table(r, "year", 3)
    out.append("### Month by month, whole year, 1 lot\n")
    months = sorted(r["year"]["A@1"]["ALL"]["months"])
    out.append("| arm | variant | " + " | ".join(RP.mname(m) for m in months) + " | total |")
    out.append("|---|---|" + "---|" * (len(months) + 1))
    for a in ARMS:
        for v in ("A", "X1", "X2", "K1"):
            s = r["year"][f"{v}@1"][a]
            out.append(f"| {NAME[a]} | {v} | " + " | ".join(RP.rs(s["months"][m]) for m in months) + f" | {RP.rs(s['net'])} |")
    ya = RESULTS / "year_a.json"
    if ya.exists():
        ra = json.loads(ya.read_text())
        da = json.loads((RESULTS / "decision_a.json").read_text())
        va = ("A", "S1k", "K1S", "B", "B+S1k", "B+K1S")
        out.append("\n## Amendment A: K1 + Rs 1,000 a lot stop + Rs 500 step trail (K1S)\n")
        out.append("S1k = R176 Amendment B's stop rule alone (reproduced exactly here: " + "; ".join(
            f"{k} {v['trades']} / {RP.rs(v['net'])}" for k, v in ra["check_s1k"].items() if k.endswith("@1")) + "). "
            f"K1S = K1's skip plus that stop rule. BH m = {da['_m']['total']:,}.\n")
        out.append("| arm | passed | passes except account stop | twin p (K1 on top of S1k vs random skips) | BH q | q within "
                   "the 3 | failed gates |\n|---|---|---|---|---|---|---|")
        for a in ("LIQ", "R72", "R51"):
            g = da[a]
            out.append(f"| {NAME[a]} | {'yes' if g['passed'] else '**no**'} | {'yes' if g['passed_except_account_stop'] else 'no'} | "
                       f"{g['twin_p']} | {g['q_all']} | {g['q_own']} | {'; '.join(g['fails'])} |")
        out.append("")
        for per in ("year", "early", "final", "six"):
            out += table(ra, per, 1, va)
        out += table(ra, "year", 3, va)
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (RESULTS / "report.md").write_text(build())
    print((RESULTS / "report.md").read_text()[:1500])
