"""R103 report: results/design.json (+ holdout.json) -> results/report.md (markdown tables only)."""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
RES = HERE / "results"
NAMES = {"IF": "iron fly (wings 1.0 x straddle)", "IFW": "wide iron fly (wings 1.5 x)", "IC": "iron condor (shorts 0.5 x, wings 1.0 x beyond)"}
XN = {"X1": "stop 50% of credit", "X2": "stop + lock 40/20 + target 60%"}
TN = {"E": "expiry day", "N": "other days"}


def rs(x) -> str:
    return "" if x is None else f"Rs {x:,.0f}".replace("Rs -", "-Rs ")


def write() -> str:
    d = json.loads((RES / "design.json").read_text())
    h = json.loads((RES / "holdout.json").read_text()) if (RES / "holdout.json").exists() else None
    hmap = {r["id"]: r for r in (h or {}).get("tests", [])}
    out = ["# R103 report: defined-risk option selling (tables)", "",
           f"Design to {d['design_end']}; 1 lot; net after slippage and dated charges. Passed design: {d['passed'] or 'none'}.", "",
           "| test | trades | win | net | per trade | avg win / loss | PF | worst day | worst month | max DD | days <= -13k | margin (est.) | net / margin, a year | 2022 / 2023 / 2024 / 2025 | after 20 Nov 2024 | ex best 5 | 2x slip | boot p | BH q | timing twin p | gates failed | BUY mirror: win / net |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for r in d["tests"]:
        s = r["design"]
        b = r.get("buy", {})
        if not s.get("n"):
            out.append(f"| {r['id']} | 0 | | | | | | | | | | | | | | | | | | | no trades | |")
            continue
        buy = f"{b['win'] * 100:.0f}% / {rs(b['net'])}" if b.get("n") else ""
        yrs = " / ".join(rs(s["by_year"].get(str(y), s["by_year"].get(y, 0))) for y in (2022, 2023, 2024, 2025))
        failed = ", ".join(k for k, v in r["gates"].items() if k != "pass" and not v) or "none"
        tw = r.get("timing_twin", {}).get("p_twin_ge_real")
        rom = f"{s['rom_per_year'] * 100:+.1f}%" if s.get("rom_per_year") is not None else ""
        out.append(f"| {r['id']} | {s['n']:,} | {s['win'] * 100:.0f}% | {rs(s['net'])} | {rs(s['mean'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
                   f"{s['pf'] if s['pf'] is not None else ''} | {rs(s['worst_day'])} | {rs(s['worst_month'])} | {rs(s['max_dd'])} | "
                   f"{s.get('days_below_stop', 0)} | {rs(s.get('mean_margin'))} | {rom} | {yrs} | "
                   f"{rs(s['post_regime_net'])} ({s['post_regime_n']}) | {rs(s['ex_best5'])} | {rs(r['slip2x_net'])} | {s.get('boot_p', 1):.3f} | {r['q_bh']:.3f} | "
                   f"{'' if tw is None else f'{tw:.2f}'} | {failed} | {buy} |")
    if h is not None:
        out += ["", "## Holdout (15 Jul - 19 Oct 2026, read once)", ""]
        if not h.get("read"):
            out.append(h.get("note", "not read"))
        else:
            out += ["| test | trades | win | net | worst day | worst month | confirmed | BUY mirror: win / net |", "|---|---|---|---|---|---|---|---|"]
            for r in h["tests"]:
                s = r["holdout"]
                b = r.get("buy", {})
                buy = f"{b['win'] * 100:.0f}% / {rs(b['net'])}" if b.get("n") else ""
                out.append(f"| {r['id']} | {s.get('n', 0)} | {s.get('win', 0) * 100:.0f}% | {rs(s.get('net'))} | {rs(s.get('worst_day'))} | "
                           f"{rs(s.get('worst_month'))} | {'yes' if r['confirmed'] else 'no'} | {buy} |")
    for part, title in (("design", "design"), ("holdout", "holdout")):         # Amendment 2: the stale-leg check
        f = RES / f"stale_{part}.json"
        if not f.exists():
            continue
        st = json.loads(f.read_text())
        out += ["", f"## Stale-leg check, {title} (PREREG Amendment 2: exit legs with no trade within 5 minutes repriced with "
                "Black-Scholes at their last print's IV)", "",
                "| test | trades | net as pre-registered | net repriced | win repriced | after 20 Nov 2024, repriced | trades with a stale exit leg | "
                "fresh-only trades: n / net / win |", "|---|---|---|---|---|---|---|---|"]
        for tid in [r["id"] for r in d["tests"]]:
            v = st.get(tid)
            if not v:
                continue
            fw = "" if v["fresh_only_win"] is None else f"{v['fresh_only_win'] * 100:.0f}%"
            out.append(f"| {tid} | {v['n']:,} | {rs(v['net_as_run'])} | {rs(v['net_repriced'])} | {v['win_repriced'] * 100:.0f}% | "
                       f"{rs(v['post_regime_repriced'])} | {v['trades_with_stale_leg']:,} | {v['fresh_only_n']:,} / {rs(v['fresh_only_net'])} / {fw} |")
    text = "\n".join(out) + "\n"
    (RES / "report.md").write_text(text)
    return text


if __name__ == "__main__":
    print(write())
