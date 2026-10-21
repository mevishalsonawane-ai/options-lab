"""R51 report: results/design.json + results/final.json -> results/report.md (a table per strategy: OLD verdict on the
full data, NOW on the Nov 2024+ design, FINAL TEST, and the re-tune), plus the Night holds' paired comparison with the
09:16 sale (B0) on the same nights.

    python research/hunt/r51/report.py
"""
from __future__ import annotations

import json

import numpy as np
import pandas as pd

import r51cfg as K
import study

# The earlier verdicts on the full data (FINDINGS.md and each study's write-up), quoted for the OLD row.
OLD = {
    "LIQ-FIN": "parked 9 Oct after -Rs 8,035 on 8 paper trades; research (LIQUIDITY_FINNIFTY_PLUS) +8.2 / +4.8 index pts a "
               "trade in two years, never gated as a pass",
    "G7-GEX-LIQ-BN": "R28: design +Rs 6/day (799 -> 521 trades), holdout -Rs 17/day; rejected",
    "NIGHT": "R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k",
    "G10-GEX-NIGHT": "R28: design +206 -> +119 Rs/day, holdout +249 -> -18 Rs/day; rejected",
    "M4-SILVERM": "R42: holdout 53 trades +Rs 336/trade (+Rs 17.8k), twin p 0.079; walk-forward -Rs 44.6k (129); paper "
                  "arm switched off 14 Oct",
    "R44": "R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of "
           "112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades",
    "R45-F-a": "R45: -Rs 170 a trade (1,250 trades) on one contract; holdout -Rs 106 (122)",
    "R45-F-b": "R45: -Rs 149 a trade (1,125); holdout -Rs 127 (130)",
    "F1-C1": "F1 holdout 2023-Jun 2024: -Rs 2.99 lakh on 2,256 trades, p 1.000", "F1-C2": "F1 holdout: -Rs 3.33 lakh on 2,224",
    "F1-S2": "F1 holdout: +Rs 15,354 on 228 (q 0.279); lost in design", "F1-S18": "F1 holdout: +Rs 12,319 on 158 (q 0.279); "
             "lost in design",
    "F1": "F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024)",
    "R29-B0": "R29 holdout 2023: -Rs 110 a trade (1,785)", "R29-R6": "R29 holdout 2023: +Rs 21 a trade (264), sign flips "
              "by period", "R29-R10": "R29 holdout 2023: -Rs 111 a trade (1,033)",
    "R29-BOSS": "R29: lost in 2024 in every cell but one (Boss's three ideas)",
    "R29": "R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass",
    "R41-R1": "R41 holdout: -Rs 424/day (2,752 trades)", "R41-R2": "R41 holdout: -Rs 252/day (2,150)",
    "R41-R3": "R41 holdout: -Rs 106/day (1,499)", "R41-R4": "R41 holdout: +Rs 20/day on 172 (too few)",
    "R41-R5": "R41 holdout: -Rs 22/day (56)", "R41-R6": "R41 holdout: -Rs 10/day (21)",
    "R41-R13": "R41 holdout: +Rs 24/day (208 trades, q 0.11)", "R41-ALWAYS-CALL": "R41: +Rs 22/day on the same nights "
               "(baseline, never gated)",
    "B2-G15": "B2 holdout: -Rs 21,397 (527)", "B2-G30": "B2 holdout: -Rs 26,901 (271)", "B2-G60": "B2 holdout: -Rs 16,189 "
              "(106)", "B2-OPT": "B2 holdout: -Rs 23,289 (331)",
}


def old_of(name: str) -> str:
    base = name[:-3] if name.endswith("-RT") else name
    for k in (base, base.rsplit("-", 1)[0]):
        if k in OLD:
            return OLD[k]
    if base.startswith("NIGHT-"):
        return OLD["NIGHT"]
    if base.startswith("R44-"):
        return OLD["R44"]
    if base.startswith("F1-"):
        return OLD["F1"]
    if base.startswith("R29-BOSS"):
        return OLD["R29-BOSS"]
    if base.startswith("R29-"):
        return OLD["R29"]
    return ""


def rs(x):
    if x is None or (isinstance(x, float) and not np.isfinite(x)):
        return "-"
    return f"{x:,.0f}"


def pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def row(label, s, p=None, q=None, verdict=""):
    if not s or not s.get("trades"):
        return f"| {label} | 0 | - | - | - | - | - | - | - | {p if p is not None else '-'} | " \
               f"{q if q is not None else '-'} | {verdict} |"
    return (f"| {label} | {s['trades']} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['per_day'])} | {rs(s['avg_win'])} | "
            f"{rs(s['avg_loss'])} | {rs(s['worst_day'])} | {rs(s['max_dd'])} | {p if p is not None else '-'} | "
            f"{q if q is not None else '-'} | {verdict} |")


HEAD = ("| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |\n"
        "|---|---|---|---|---|---|---|---|---|---|---|---|")


def night_vs_b0(final: dict) -> dict:
    """Per hold variant: final-test net minus B0's on the same nights, and a sign-flip p (2,000 flips)."""
    out = {}
    b0p = study._cache("final_night|B0")
    if not b0p.exists():
        return out
    B0 = pd.read_pickle(b0p).set_index(["und", "day"]).net
    rng = np.random.default_rng(K.SEED)
    for name, f in final.items():
        if not name.startswith("NIGHT-V") and name != "NIGHT-HOLD-RT":
            continue
        p = study._cache(f"final_{f['config']}")
        if not p.exists():
            continue
        V = pd.read_pickle(p).set_index(["und", "day"]).net
        dlt = (V - B0.reindex(V.index)).dropna().values
        if not len(dlt):
            continue
        flips = rng.choice([-1.0, 1.0], size=(2000, len(dlt))) @ dlt
        out[name] = dict(delta=round(float(dlt.sum()), 0), nights=len(dlt),
                         p=round((1 + int((flips >= dlt.sum()).sum())) / 2001, 4))
    return out


def main():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text()) if (K.RESULTS / "final.json").exists() else {}
    nb = night_vs_b0(F) if F else {}
    lines = ["# R51 results (generated by report.py)", ""]
    fams = []
    for n, d in D.items():
        if d["family"] not in fams:
            fams.append(d["family"])
    for fam in fams:
        lines += [f"## {fam}", ""]
        for n, d in D.items():
            if d["family"] != fam or d["kind"] == "retune":
                continue
            f = F.get(n, {})
            lines += [f"### {n}: {d['desc']}", "", f"Signal source: {d['source']}. Kind: {d['kind']}.", "", HEAD,
                      f"| OLD (full data) | | | | | | | | | | | {old_of(n)} |",
                      row("NOW (design)", d["design"], d["design"].get("p"), None,
                          f"walk-forward (folds) {d.get('folds')}"),
                      row("FINAL TEST", f, f.get("p"), f.get("q"), f.get("verdict", "not read"))]
            rt = n + "-RT"
            if rt in D:
                r, fr = D[rt], F.get(rt, {})
                lines += [row(f"RE-TUNE design ({r['final_pick'].replace('|', ' / ')})", r["design"], None, None,
                              f"walk-forward out-of-fold {rs(r['wf_net'])} on {r['wf_trades']} trades"),
                          row("RE-TUNE FINAL", fr, fr.get("p"), fr.get("q"), fr.get("verdict", "not read"))]
            if n in nb:
                lines += ["", f"Against the 09:16 sale on the same final-test nights: {rs(nb[n]['delta'])} Rs over "
                              f"{nb[n]['nights']} nights (sign-flip p {nb[n]['p']})."]
            lines.append("")
    if "NIGHT-HOLD-RT" in nb:
        lines += [f"NIGHT-HOLD-RT against the 09:16 sale: {rs(nb['NIGHT-HOLD-RT']['delta'])} Rs "
                  f"(p {nb['NIGHT-HOLD-RT']['p']}).", ""]
    passed = [n for n, f in F.items() if f.get("pass")]
    lines += ["## Passes", "", ", ".join(passed) if passed else "None.", ""]
    (K.RESULTS / "report.md").write_text("\n".join(lines))
    (K.RESULTS / "night_vs_b0.json").write_text(json.dumps(nb, indent=1))
    print("\n".join(lines[-4:]))


if __name__ == "__main__":
    main()
