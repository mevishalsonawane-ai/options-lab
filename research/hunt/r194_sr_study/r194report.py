"""R194 report (PREREG.md section 9): results/summary.md and the experiment log entries, from results/repro_*.json,
span_*.json and decision.json. The plain-English answer is results/answer.md (written by hand after the numbers, and
included at the top of the summary).

    python3 research/hunt/r194_sr_study/r194report.py && python3 research/experiments/validate.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import yaml

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import r194cfg as K  # noqa: E402

ROOT = HERE.parents[2]
ENTRIES = ROOT / "research" / "experiments" / "entries"
DOC = "research/hunt/r194_sr_study/results/summary.md"
PREREG = "research/hunt/r194_sr_study/PREREG.md"
DATA = ("Drive lake copy of 17 Nov 2026 (R190's flat folder ~/.cache/iraalgo/r190/lake, with the 20 Oct - 13 Nov 2026 "
        "fill); R51 clean stores 3 Aug 2020 - 13 Nov 2026 (R190), as R193")
FAMILY = {"A": "ml", "B": "ml", "C": "patterns-indicators", "D": "trade-management"}
INSTR5 = ["NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY", "SENSEX"]
WNAME = {"W1": "W1 1 Nov 2024 - 31 Mar 2026 (pickers in-sample)",
         "W2": "W2 1 Apr - 13 Nov 2026 (pickers out-of-sample; read by many studies, not fresh)",
         "total": "total 1 Nov 2024 - 13 Nov 2026"}


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    return ("-Rs " if x < 0 else "Rs ") + f"{abs(x):,.0f}"


def pct(x) -> str:
    return "-" if x is None else f"{x:.1f}%"


def load(name: str):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def label(x: str) -> str:
    parts = x.split("+")
    return " + ".join(K.LABEL.get(p, p) for p in parts)


def cell(s: dict) -> str:
    return f"{s['trades']:,} / {pct(s['win'])} / {rs(s['net'])} / {rs(s['max_dd'])}"


HEAD = ("| test | row | W1 trades / win / net / max DD | W2 trades / win / net / max DD | total net | total net w/o top 5 | "
        "twin p | BH q | verdict |\n|---|---|---|---|---:|---:|---:|---:|---|")


def rows_for(tid: str, v: dict, b: dict, d: dict) -> list:
    fam, arm, var, base = K.TESTS[tid]
    vw, bw = v["windows"], b["windows"]
    verdict = "HELPS" if d["helps"] else "no: " + "; ".join(d["fails"])
    return [f"| {tid} | baseline: {label(base)} | {cell(bw['W1'])} | {cell(bw['W2'])} | {rs(bw['total']['net'])} | "
            f"{rs(bw['total']['net_wo_top5'])} | | | |",
            f"| {tid} | variant: {label(var)} | {cell(vw['W1'])} | {cell(vw['W2'])} | {rs(vw['total']['net'])} | "
            f"{rs(vw['total']['net_wo_top5'])} | {v['twin']['p']:.4f} | {d['q']:.3g} | {verdict} |"]


def find(r: dict, tid: str):
    fam, arm, _, b = K.TESTS[tid]
    if arm == "LIQ":
        x = r.get("span_liq") or {}
        return x.get(tid), x.get("base:L1")
    if fam == "B":
        x = r.get(f"span_b_{arm}") or {}
        return x.get(tid), x.get("base:P-noSR")
    x = r.get(f"span_sr_{arm}") or {}
    return x.get(tid), x.get(f"base:{b}")


def main():
    r = {f: load(f + ".json") for f in ("span_sr_R72", "span_sr_R51", "span_b_R72", "span_b_R51", "span_liq",
                                        "span_bfit_R72", "span_bfit_R51", "repro_R72", "repro_R51", "repro_LIQ")}
    dec = load("decision.json")
    ans = (K.RESULTS / "answer.md").read_text() if (K.RESULTS / "answer.md").exists() else ""
    out = ["# R194 summary: does support / resistance help R72, R51 and Liquidity 15+5?", "",
           "Research only; nothing in the app changes. Signal on the index; options bought; 1 lot; dated Zerodha charges "
           "and R17 slippage; 1 Nov 2024 - 13 Nov 2026 (R193's span, stores and engine). Gate (PREREG section 8): vs the "
           "baseline, higher net AND lower max drawdown in both W1 and W2, 40+ trades in each, net without the top 5 above "
           "the baseline's net, twin p < 0.05, BH q <= 0.10 (m = 4,808). Each cell: trades / win % / net / max drawdown.", "",
           ans, "", "## Reproduction first", ""]
    out.append("| check | trades | net | R193 | gap | reproduced |")
    out.append("|---|---:|---:|---|---|---|")
    for arm in ("R72", "R51"):
        rp = r[f"repro_{arm}"]
        for b, x in (rp or {}).get("baselines", {}).items():
            out.append(f"| {arm} {b} | {x['trades']:,} | {rs(x['net'])} | {x['r193'][0]:,} / {rs(x['r193'][1])} | "
                       f"{x['gap_trades']:+d}, {x['gap_net']:+,} | {'yes' if x['reproduced'] else 'NO'} |")
    x = (r["repro_LIQ"] or {}).get("L1")
    if x:
        out.append(f"| LIQ L1 (R194's signal copy) | {x['trades']:,} | {rs(x['net'])} | {x['r193'][0]:,} / {rs(x['r193'][1])} | "
                   f"{x['gap_trades']:+d}, {x['gap_net']:+,} | {'yes' if x['reproduced'] else 'NO'} |")
    out += ["", "**R151's model (family B)**, refit in R151's store window and compared with the app's numbers:", "",
            "| arm | design trades | max abs diff (median / mean / scale / coef / intercept) | reproduced | rule threshold of the refit full model | no-S/R threshold (rule) |",
            "|---|---:|---|---|---|---|"]
    for arm in ("R72", "R51"):
        f = r[f"span_bfit_{arm}"]
        if not f:
            continue
        dd = f["repro"]["max_abs_diff"]
        ns = f.get("noSR") or {}
        out.append(f"| {arm} | {f['_meta']['design_trades']:,} | " + " / ".join(f"{dd[k]:.1e}" for k in
                   ("median", "mean", "scale", "coef", "intercept")) + f" | {'yes' if f['repro']['reproduced'] else 'NO'} | "
                   f"{f['full_curve_rule']['threshold']} ({f['full_curve_rule']['rule']}) | "
                   f"{ns.get('threshold', '-')} ({ns.get('rule', f.get('stopped', '-'))}) |")
    for fam, title in (("A", "A. Picker ablation (S/R features at their training medians)"),
                       ("B", "B. Picker retrained without the S/R features"),
                       ("C", "C. Explicit S/R entry filters"), ("D", "D. S/R exits and Liquidity's room check")):
        out += ["", f"## {title}", "", HEAD]
        for tid in K.TESTS:
            if K.TESTS[tid][0] != fam:
                continue
            v, b = find(r, tid)
            d = (dec or {}).get("tests", {}).get(tid)
            if v is None or b is None or d is None:
                out.append(f"| {tid} | not run | | | | | | | {'; '.join((d or {}).get('fails', []))} |")
                continue
            out += rows_for(tid, v, b, d)
    # extras
    out += ["", "## Extras (descriptive)", ""]
    for arm in ("R72", "R51"):
        s = r[f"span_sr_{arm}"]
        if s and f"A-{arm}" in s:
            a = s[f"A-{arm}"]
            fl = a["flips_taken"]
            out.append(f"- {arm} ablation flips: {a['flips_candidates']:,} of {a['candidates']:,} candidates change side of "
                       f"the threshold; taken trades only the real picker keeps / only the ablated keeps: W1 "
                       f"{fl['W1']['p_only']} / {fl['W1']['abl_only']}, W2 {fl['W2']['p_only']} / {fl['W2']['abl_only']}.")
        for b in ("P", "V5"):
            t = (s or {}).get(f"D-TGT-{arm}-{b}")
            if t:
                out.append(f"- D-TGT-{arm}-{b}: {t['target_levels']:,} candidates had a 3+-touch level ahead, "
                           f"{t['target_rows']:,} reached it before the square-off; exits: {t['exits']}; trades the target "
                           f"closed >= 2 minutes earlier: {t['twin']['changed']:,}.")
    liq = r["span_liq"]
    if liq:
        out.append(f"- Liquidity signals in the span by room: {liq['_meta']['signals']}.")
    out += ["", "## Twins and p", "", "| test | twin p | tail | normal p | twin mean | twin sd | stat |",
            "|---|---:|---|---:|---:|---:|---:|"]
    for tid in K.TESTS:
        v, _ = find(r, tid)
        if v:
            t = v["twin"]
            out.append(f"| {tid} | {t['p']:.4f} | {t['tail']} | {t['p_normal']:.3g} | {rs(t['mean'])} | {rs(t['sd'])} | {rs(t['stat'])} |")
    (K.RESULTS / "summary.md").write_text("\n".join(out) + "\n")
    if not dec:
        return

    def ln(s):
        return f"{s['trades']} tr, {s['win']:.1f}% win, {rs(s['net'])}, DD {rs(s['max_dd'])}"
    for tid, (fam, arm, var, base) in K.TESTS.items():
        v, b = find(r, tid)
        d = dec["tests"][tid]
        if v is None:
            summ = "Not run: " + "; ".join(d["fails"])
        else:
            vw, bw = v["windows"], b["windows"]
            summ = (f"W1 {ln(vw['W1'])} (baseline {ln(bw['W1'])}); W2 {ln(vw['W2'])} (baseline {ln(bw['W2'])}); total "
                    f"{ln(vw['total'])} (baseline {ln(bw['total'])}); twin p {v['twin']['p']:.4f}, BH q {d['q']:.3g} "
                    f"(m = {K.M_TOTAL:,}). " + ("HELPS." if d["helps"] else "Does not help: " + "; ".join(d["fails"])))
        arm_name = {"R72": "R72 Breadth Donchian", "R51": "R51 Donchian 20 + Supertrend", "LIQ": "Liquidity 15+5"}[arm]
        e = dict(
            id=f"r194-{tid.lower()}", title=f"R194 S/R on {arm_name}: {label(var)} vs {label(base)}",
            family="liquidity" if arm == "LIQ" else FAMILY[fam], date="2026-11-18", doc=DOC,
            hypothesis=f"{label(var)} beats {label(base)} on {arm_name}, 1 Nov 2024 - 13 Nov 2026: higher net and lower "
                       "drawdown in W1 and W2, robust to the top 5, better than random twins, after BH",
            data_version=DATA,
            period=dict(design="W1 2024-11-01..2026-03-31 (R151's pickers in-sample; nothing new fitted except family B's "
                               "retrain on R151's own design period 2024-11-20..2026-03-31)",
                        holdout="W2 2026-04-01..2026-11-13 (out-of-sample for the pickers but read by R151-R193: not fresh); "
                                "read once per family (results/READ_*)"),
            instruments=["BANKNIFTY", "FINNIFTY", "MIDCPNIFTY"] if arm == "LIQ" else INSTR5,
            features=["signal source: the index (5-minute index pivots grouped into 3+-touch levels, R150 / R151; Liquidity's "
                      "swing / pool zones on 15 / 5-minute index bars)", "buy the option; the S/R target is an index touch, "
                      "sold at the next minute's open"],
            model_or_rule=f"{label(var)} (baseline: {label(base)})",
            settings="research/hunt/r194_sr_study/r194cfg.py; PREREG.md section 6", prereg=PREREG, variants_tried=1,
            results=dict(summary=summ, costs="dated Zerodha charges, R17 fills, 1 lot"),
            holdout_read=True, verdict="paper-only" if d["helps"] else "rejected",
            notes="Research only; nothing in the app changes.")
        (ENTRIES / f"r194-{tid.lower()}.yaml").write_text(yaml.safe_dump(e, sort_keys=False, width=120, allow_unicode=False))
    print("summary and entries written")


if __name__ == "__main__":
    main()
