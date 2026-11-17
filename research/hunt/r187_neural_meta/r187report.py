"""R187 report: results/report.md (every table) and results/summary.md (plain English for Boss) from the JSON results.

    python research/hunt/r187_neural_meta/r187report.py

Plain-English notes, 16 Nov 2026.
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r187cfg as K  # noqa: E402

USE_NAME = {"BASE": "base (the app today)", "U1": "U1 network skip", "U2": "U2 network exit choice",
            "U3": "U3 skip + exit choice", "U4": "U4 network picker (no R151 picker)",
            "NOPICK": "base without R151's picker"}
PNAME = {"early": "early (20 Oct 2025 - 31 Mar 2026)", "final": "final (1 Apr - 13 Oct 2026)",
         "six": "last 6 months (20 Apr - 19 Oct 2026)", "year": "year (20 Oct 2025 - 19 Oct 2026)",
         "wf": "walk-forward (Apr 2025 - Mar 2026)", "new": "new month (21 Oct - 13 Nov 2026)"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = round(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.1f}%"


def row(name: str, s: dict) -> str:
    return (f"| {name} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['avg_win'])} | {rs(s['avg_loss'])} | "
            f"{rs(s['worst_month']['net'])} ({s['worst_month']['month']}) | {rs(s['max_dd'])} |")


HEAD = ("| rule | trades | win | net | avg win | avg loss | worst month | max drawdown |\n"
        "|---|---|---|---|---|---|---|---|")


def load(name):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def arm_tables(by_use: dict, period: str | None) -> list:
    out = [HEAD]
    for u in ("BASE", "U1", "U2", "U3", "NOPICK", "U4"):
        if u in by_use:
            s = by_use[u][period] if period else by_use[u]
            out.append(row(USE_NAME[u], s))
    return out


def main():
    yr, dec, wf, nm, fo = (load("year.json"), load("decision.json"), load("wf.json"), load("new_month.json"),
                           load("folds.json"))
    L = ["# R187: one multi-layer network over the lake, used to skip trades and pick exits - full tables", "",
         "16 Nov 2026. Research only: nothing in the app changes. PREREG.md (pushed before any number). 1 lot, dated Zerodha "
         "charges, R17 fills. Signal source: the index (entries unchanged); exits on the option premium; option-chain data "
         "only as network inputs.", ""]
    if fo:
        L += ["## The network in each fold", "", "| fold | training trades | stacked rows | classifier (hidden, alpha) | "
              "regressor (hidden, alpha) |", "|---|---|---|---|---|"]
        for k, v in fo.items():
            c, r = v["clf_setting"], v["reg_setting"]
            L.append(f"| {k} | {v['n_train_trades']:,} | {v['n_rows']:,} | {tuple(c['hidden'])}, {c['alpha']} | "
                     f"{tuple(r['hidden'])}, {r['alpha']} |")
        L += ["", "Choices per fold (theta: skip below this P(win); delta: rupees the chosen exit must beat V5 / B0 by; "
              "None = no skip; inf = never switch):", "", "| fold | arm | theta1 | delta | theta3 | theta4 |",
              "|---|---|---|---|---|---|"]
        for k, v in fo.items():
            for a, c in v["choices"].items():
                L.append(f"| {k} | {K.ARM_NAME[a]} | {c['theta1']} | {c['delta']} | {c['theta3']} | {c.get('theta4', '-')} |")
        L.append("")
    if dec:
        L += [f"## Gate verdicts (BH m = {dec['_m']['total']:,})", "", "| arm | use | passed | twin p | BH q | failed on |",
              "|---|---|---|---|---|---|"]
        for a, u in K.TESTS:
            g = dec[f"{a}:{u}"]
            L.append(f"| {K.ARM_NAME[a]} | {u} | {'YES' if g['passed'] else 'no'} | {g['twin_p']:.3f} | {g['q']:.2f} | "
                     f"{'; '.join(g['fails']) or '-'} |")
        L.append("")
    if yr:
        L += ["## Parity", "", "V5 at 1 lot on the year (R186 Amendment C): " + "; ".join(
            f"{a} {v['got'][0]} trades / {rs(v['got'][1])} (published {v['published'][0]} / {rs(v['published'][1])})"
            for a, v in yr["parity"].items()) + ". ORB store: R144b's design figures and R169's WF B0 reproduced exactly "
            "(ORB 320 / -Rs 41,427; Sweep 454 / -Rs 1,12,523; WF ORB 231 / -Rs 25,965; Sweep 326 / -Rs 98,449).", ""]
        for a in K.ARMS:
            L += [f"## {K.ARM_NAME[a]}", ""]
            for p in K.PERIODS:
                L += [f"**{PNAME[p]}**", ""] + arm_tables(yr["tables"][a], p) + [""]
            tw = {u: yr["twins"].get(f"{a}:{u}") for u in ("U1", "U2", "U3", "U4") if f"{a}:{u}" in yr["twins"]}
            L += ["Twins (year): " + "; ".join(f"{u} p {v['p']:.3f} (twin mean {rs(v['mean'])})" for u, v in tw.items()), ""]
    if wf:
        L += ["## Walk-forward window (Apr 2025 - Mar 2026, folds F1-F4; design data)", "",
              "The ORB rows were run before the final read; the first run crashed on an import before writing its file, so "
              "the R72 / R51 / Liquidity rows were re-run (same frozen models, design data only) while the final ran. "
              "Nothing in the final run depends on them.", ""]
        for a in K.ARMS:
            L += [f"**{K.ARM_NAME[a]}**", ""] + arm_tables(wf[a], None) + [""]
    if nm:
        L += ["## New month (21 Oct - 13 Nov 2026), descriptive, frozen F5 models", ""]
        for a in K.ARMS:
            L += [f"**{K.ARM_NAME[a]}**", ""] + arm_tables(nm[a], None) + [""]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("report.md", len(L), "lines")


if __name__ == "__main__":
    main()
