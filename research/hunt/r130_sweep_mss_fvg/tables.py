"""R130: print the write-up's tables from results/design.json and results/holdout.json (markdown).

    python research/hunt/r130_sweep_mss_fvg/tables.py
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent


def rs(x: float) -> str:
    s = f"{abs(x):,.0f}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x: float) -> str:
    return f"{100 * x:.0f}%"


def main_table(J: dict, holdout: bool = False) -> str:
    head = "| variant | trades | win rate (vs 65%) | avg win / avg loss | avg net | avg net 2x slip | total (1 lot) | max drawdown | index pts / R | target / stop exits | p vs twins |"
    if not holdout:
        head += " BH q |"
    out = [head, "|" + "---|" * (head.count("|") - 1)]
    for v, s in J["variants"].items():
        if not s.get("n"):
            out.append(f"| {v} | 0 | - | - | - | - | - | - | - | - | - |" + (" - |" if not holdout else ""))
            continue
        tv = s["target_vs_stop"]
        row = (f"| {v} {s['label']} | {s['n']:,} | {pct(s['win'])} ({'below' if s['win'] < 0.65 else 'meets'}) | "
               f"{rs(s['avg_win'])} / {rs(s['avg_loss'])} | {rs(s['avg_net'])} | {rs(s['avg_net_x2'])} | "
               f"{rs(s['total'])} | {rs(s['max_dd'])} | {s['avg_pts']:+.1f} / {s['avg_R']:+.2f}R | "
               f"{tv['target']:,} / {tv['stop']:,} | {s['p_twins']:.3f} |")
        if not holdout:
            row += f" {s['q']:.3f} |"
        out.append(row)
    return "\n".join(out)


def by_table(J: dict, key: str) -> str:
    vs = list(J["variants"])
    cols = sorted({k for v in vs for k in J["variants"][v].get(key, {})}, key=str)
    out = ["| variant | " + " | ".join(str(c) for c in cols) + " |", "|---|" + "---|" * len(cols)]
    for v in vs:
        b = J["variants"][v].get(key, {})
        cells = []
        for c in cols:
            x = b.get(c) if c in b else b.get(str(c))
            cells.append("-" if not x or not x.get("n") else f"{rs(x['total'])} ({x['n']}, {pct(x['win'])})")
        out.append(f"| {v} | " + " | ".join(cells) + " |")
    return "\n".join(out)


def misc(J: dict) -> str:
    lines = []
    for v, s in J["variants"].items():
        lines.append(f"- {v}: census {s['census']}; skips {s['skips']}; exits {s.get('exits')}; "
                     f"worst month {rs(s.get('worst_month', 0))} ({s.get('worst_month_name')}); months green "
                     f"{s.get('months_green')}/{s.get('months')}; buys {pct(s.get('buys', 0))}; "
                     f"gates {s.get('design_gates')}")
    lines.append(f"- C3: {J['claim_c3']}")
    return "\n".join(lines)


if __name__ == "__main__":
    for part in ("design", "holdout"):
        p = HERE / "results" / f"{part}.json"
        if not p.exists():
            continue
        J = json.loads(p.read_text())
        print(f"\n## {part} ({J['period']}, {J['sessions']} sessions)\n")
        print(main_table(J, part == "holdout"))
        print("\nby index (total, trades, win rate)\n")
        print(by_table(J, "by_und"))
        print("\nby year\n")
        print(by_table(J, "by_year"))
        if part == "design":
            print("\nby fold\n")
            print(by_table(J, "by_fold"))
        print()
        print(misc(J))
