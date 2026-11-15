"""R179 report: results/report.md (summary, gates, tables)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent / "r176_step_trail_500"))
import r176report as RP  # noqa: E402

RESULTS = HERE / "results"
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend"}
VARS = ("A", "P", "BE", "V4", "SC", "V1", "V2", "V3")
LAB = {"A": "today's rules", "P": "today + picker", "BE": "today + breakeven lock", "V4": "today + picker + breakeven",
       "SC": "Rs 1,500 rule", "V1": "Rs 1,500 + picker", "V2": "Rs 1,500 + breakeven", "V3": "Rs 1,500 + picker + breakeven"}
PER = {"year": "whole year (20 Oct 2025 - 19 Oct 2026)", "early": "early (to 31 Mar 2026; picker in-sample)",
       "final": "final (1 Apr - 13 Oct 2026)", "six": "last 6 months"}


def build() -> str:
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    out = [(RESULTS / "summary.md").read_text().rstrip() + "\n", f"## Gates (1 lot; BH m = {d['_m']['total']:,})\n",
           "| arm : variant | passed | passes except BH and account stop | win year / final | net year (base) | twin p | "
           "failed gates |\n|---|---|---|---|---|---|---|"]
    for k, g in d.items():
        if k == "_m":
            continue
        core = not [f for f in g["fails"] if "account stop" not in f and "BH" not in f]
        out.append(f"| {k} | {'yes' if g['passed'] else '**no**'} | {'**yes**' if core else 'no'} | {RP.pct(g['win_year'])} / "
                   f"{RP.pct(g['win_final'])} | {RP.rs(g['net_year'])} ({RP.rs(g['base_net_year'])}) | {g['twin_p']} | "
                   f"{'; '.join(g['fails'])} |")
    out.append("\nParity: today's rules on the final = R172's check (" + "; ".join(
        f"{a} {p['trades']} / {RP.rs(p['net'])}" for a, p in r["parity"].items()) + "). Breakeven lock on the final vs R162's "
        "BE10 (13 Oct stores): " + "; ".join(f"{a} {c['trades']} / {RP.rs(c['net'])} vs {c['r162_13oct'][0]} / "
                                             f"{RP.rs(c['r162_13oct'][1])}" for a, c in r["be_check"].items())
        + " (the gap is NIFTY's 13 Oct expiry day, which the 13 Oct stores could not see).\n")
    for lots in (1, 3):
        for per in ("year", "final", "early", "six"):
            if lots == 3 and per != "year":
                continue
            out += [f"### {PER[per]}, {lots} lot{'s' if lots > 1 else ''}\n",
                    "| arm | variant | trades | win | net | avg win / avg loss | worst month | max drawdown |",
                    "|---|---|---|---|---|---|---|---|"]
            for a in ("LIQ", "R72", "R51"):
                for v in VARS:
                    s = r[per].get(f"{v}@{lots}", {}).get(a)
                    if not s:
                        continue
                    out.append(f"| {NAME[a]} | {LAB[v]} | {s['trades']} | {RP.pct(s['win'])} | **{RP.rs(s['net'])}** | "
                               f"{RP.rs(s['avg_win'])} / {RP.rs(-s['avg_loss'] if s['avg_loss'] else 0)} | "
                               f"{RP.rs(s['worst_month']['net'])} | {RP.rs(-s['max_dd'] if s['max_dd'] else 0)} |")
            out.append("")
    ya = RESULTS / "year_a.json"
    if ya.exists():
        ra = json.loads(ya.read_text())
        da = json.loads((RESULTS / "decision_a.json").read_text())
        out.append(f"## Amendment A: Rs 1,000 maximum loss (BH m = {da['_m']['total']:,})\n")
        for k, g in da.items():
            if k != "_m":
                out.append(f"- {k}: win {RP.pct(g['win_year'])} / final {RP.pct(g['win_final'])}, net {RP.rs(g['net_year'])} "
                           f"(today {RP.rs(g['base_net_year'])}), twin p {g['twin_p']}; failed: {'; '.join(g['fails'])}")
        out.append("\nCap exits and losses over Rs 1,000: " + "; ".join(
            f"{k} M1 {v['M1']['cap_exits']} cap exits, {v['M1']['over_1000']} trades over Rs 1,000 (worst {RP.rs(v['M1']['worst'])})"
            + ("" if k.endswith("@1") else f", M2 {v['M2']['cap_exits']} / {v['M2']['over_1000']}")
            for k, v in ra["loss"].items()) + ".\n")
        lab = {"A": "today's rules", "V4": "picker + breakeven (-15%)", "M1": "+ Rs 1,000 a lot cap", "M2": "+ Rs 1,000 a trade cap"}
        for lots in (1, 2, 3):
            for per in ("year", "final"):
                out += [f"### Amendment A, {per}, {lots} lot{'s' if lots > 1 else ''}\n",
                        "| arm | variant | trades | win | net | avg win / avg loss | biggest loss | worst month | max drawdown |",
                        "|---|---|---|---|---|---|---|---|---|"]
                for a_ in ("LIQ", "R72", "R51"):
                    for v in ("A", "V4", "M1", "M2"):
                        if v == "M2" and lots == 1:
                            continue
                        s_ = ra[per][f"{v}@{lots}"][a_]
                        out.append(f"| {NAME[a_]} | {lab[v] if not (a_ == 'LIQ' and v == 'V4') else 'breakeven (-15%)'} | "
                                   f"{s_['trades']} | {RP.pct(s_['win'])} | **{RP.rs(s_['net'])}** | {RP.rs(s_['avg_win'])} / "
                                   f"{RP.rs(-s_['avg_loss'] if s_['avg_loss'] else 0)} | {RP.rs(s_['largest_loss'])} | "
                                   f"{RP.rs(s_['worst_month']['net'])} | {RP.rs(-s_['max_dd'] if s_['max_dd'] else 0)} |")
                out.append("")
    out.append("Signal source: the index (entries; the picker's features). Costs: dated Zerodha charges, R17 slippage.")
    return "\n".join(out) + "\n"


if __name__ == "__main__":
    (RESULTS / "report.md").write_text(build())
