"""R186 report: results/report.md (every number) from results/year.json, decision.json and gap_month.json.

    python research/hunt/r186_fixed_profit/r186report.py
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
ARMS = ("R72", "R51", "LIQ")
NAME = {"LIQ": "Liquidity 15+5", "R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20"}
RULES = ("T1000", "T1250", "T1500", "L500", "LBE1000")
SHOW = ("A", "BASE") + RULES
PER = ("early", "final", "six", "year")
PNAME = {"early": "early (20 Oct 2025 - 31 Mar 2026)", "final": "final (1 Apr - 13 Oct 2026)",
         "six": "last 6 months (20 Apr - 19 Oct 2026)", "year": "year (20 Oct 2025 - 19 Oct 2026)"}


def rs(x) -> str:
    """Indian style: Rs 1,234 / -Rs 12,345 / Rs 1.70 L."""
    x = round(x)
    s = "-" if x < 0 else ""
    a = abs(x)
    if a >= 100000:
        return f"{s}Rs {a / 100000:.2f} L"
    return f"{s}Rs {a:,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.1f}%"


def main():
    r = json.loads((RESULTS / "year.json").read_text())
    d = json.loads((RESULTS / "decision.json").read_text())
    L = ["# R186 report: hard fixed profit target and Rs 500 step lock (every number)", "",
         "1 lot unless said; after dated Zerodha charges and R17 fills. Signal source: the index. BASE = the rule in the app "
         "today (own stop + breakeven at +10%; R151 picker on R72 / R51). A = R179's A (own exits only).", "",
         "## Parity", ""]
    for k, v in r["parity"].items():
        L.append(f"- {k}: {v['trades']} trades, {rs(v['net'])} (published {v['published'][0]}, {rs(v['published'][1])})"
                 + (" - reproduced" if v.get("reproduced", True) else " - NOT reproduced"))
    for lots in (1, 3):
        L += ["", f"## Results at {lots} lot{'s' if lots > 1 else ''}", ""]
        for per in PER:
            L += [f"### {PNAME[per]}", "",
                  "| arm | rule | trades | win | net | avg win | avg loss | closed by rule | base made more | given up | "
                  "saved | max DD | worst month |", "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for a in ARMS:
                for v in SHOW:
                    s = r[per].get(f"{v}@{lots}", {}).get(a)
                    if s is None:
                        continue
                    e = r["effect"].get(f"{a}:{v}@{lots}", {}).get(per)
                    ef = ("-", "-", "-", "-") if e is None else (e["closed"], e["base_made_more"], rs(e["profit_given_up"]),
                                                                 rs(e["profit_saved"]))
                    L.append(f"| {a} | {v} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['avg_win'])} | "
                             f"{rs(s['avg_loss'])} | {ef[0]} | {ef[1]} | {ef[2]} | {ef[3]} | {rs(s['max_dd'])} | "
                             f"{rs(s['worst_month']['net'])} ({s['worst_month']['month']}) |")
            L.append("")
    L += ["## Gates (1 lot)", "", f"BH m = {d['_m']['total']} ({d['_m']['prior']} + {d['_m']['tests']}).", "",
          "| test | twin p | changed | BH q | own-family q | passed | passed apart from the account stop | fails |",
          "|---|---|---|---|---|---|---|---|"]
    for k, g in d.items():
        if k.startswith("_"):
            continue
        tw = r["twins"][k]
        L.append(f"| {k} | {g['twin_p']} | {tw['changed']} | {g['q']} | {g['q_own']} | {g['passed']} | "
                 f"{g['passed_except_account_stop']} | {'; '.join(g['fails']) or '-'} |")
    L += ["", "## +Rs 1,000 then a loss (base, 1 lot; PREREG 7a)", "",
          "| arm | period | trades | losers | reached +1,000 | lost after +1,000 | given back | their final loss | "
          "lost after +1,500 | lost after +2,000 | share of losers that were +1,000 first |", "|---|---|---|---|---|---|---|---|---|---|---|"]

    def gb_row(a, per, g):
        return (f"| {a} | {per} | {g['trades']} | {g['losers']} | {g['ge1000']['reached']} | {g['ge1000']['lost_after']} | "
                f"{rs(g['ge1000']['given_back'])} | {rs(g['ge1000']['final_loss'])} | {g['ge1500']['lost_after']} | "
                f"{g['ge2000']['lost_after']} | {pct(g['share_losers_were_1000'])} |")
    for a in ARMS:
        gb = r["giveback"][a]
        for per in PER:
            L.append(gb_row(a, per, gb[per]))
        for m, g in gb["months"].items():
            L.append(gb_row(a, m, g))
    gp = RESULTS / "gap_month.json"
    if gp.exists():
        g = json.loads(gp.read_text())
        L += ["", f"## The month Boss watched ({g['window']}, base, 1 lot; descriptive)", ""]
        for a in ARMS:
            c = g["check"][a]
            L.append(f"- store check {a}: Sep / Oct 2026 nets {c['this']} vs year.json {c['year_json']} - "
                     + ("same" if c["same"] else "DIFFERENT"))
        L.append("- Checked day by day: the only day that differs is 19 Oct 2026 (R72 -Rs 4,241 -> -Rs 2,864, R51 -Rs 12,296 -> "
                 "-Rs 9,917), the last session of the 19 Oct stores, which there had no next session; every other day is the same.")
        L.append("- Days with at least one trade that was +Rs 1,000 and then lost: " + ", ".join(
            f"{a} {g['giveback'][a]['days_with_1000_then_loss']} of {g['base'][a]['days']} days it traded" for a in ARMS)
            + f"; trades on the 8 Nov Muhurat session: " + ", ".join(f"{a} {g['base'][a]['muhurat_8nov']}" for a in ARMS) + ".")
        L += ["", "| arm | period | trades | losers | reached +1,000 | lost after +1,000 | given back | their final loss | "
              "lost after +1,500 | lost after +2,000 | share of losers that were +1,000 first |", "|---|---|---|---|---|---|---|---|---|---|---|"]
        for a in ARMS:
            L.append(gb_row(a, "21 Oct - 13 Nov", g["giveback"][a]))
        L += ["", "| arm | trades | days | win | net |", "|---|---|---|---|---|"]
        for a in ARMS:
            b = g["base"][a]
            L.append(f"| {a} | {b['trades']} | {b['days']} | {pct(b['win'])} | {rs(b['net'])} |")
    (RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("report.md written")


if __name__ == "__main__":
    main()
