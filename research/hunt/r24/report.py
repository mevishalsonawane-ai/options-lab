#!/usr/bin/env python3
"""R24 report: results/report.md from results/step1.json, step2.json, step3_design.json and step3_holdout.json, with the
gates and Benjamini-Hochberg across the R24 rules and the R20-R23 holdout p-values (results/family_p.json). 13 Oct 2026.

    python3 research/hunt/r24/report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
import mine as MN  # noqa: E402
import r24cfg as K  # noqa: E402

R = K.RESULTS
SIDE = {1: "CE", -1: "PE"}
MNY = {3: "ITM3", 2: "ITM2", 1: "ITM1", 0: "ATM", -1: "OTM1", -2: "OTM2", -3: "OTM3"}
TOD = ["09:20-09:59", "10:00-11:29", "11:30-12:59", "13:00-14:45"]
DTE = ["expiry day", "1-2", "3-6", "7+"]


def load(name):
    p = R / name
    return json.loads(p.read_text()) if p.exists() else None


def rs(x):
    return "n/a" if x is None or (isinstance(x, float) and not np.isfinite(x)) else f"{x:,.0f}"


def pct(x, d=1):
    return "n/a" if x is None or (isinstance(x, float) and not np.isfinite(x)) else f"{100 * x:.{d}f}%"


def table(head, rows):
    out = ["| " + " | ".join(head) + " |", "|" + "---|" * len(head)]
    out += ["| " + " | ".join(str(c) for c in r) + " |" for r in rows]
    return out


def step1_md(s1):
    L = [f"## Step 1: the what-if table, design period ({s1['rows']:,} option-minutes, {s1['days']:,} sessions)", "",
         "Mean net Rs per lot after the app's charges and the spread by moneyness; % and minutes are medians.", ""]
    rows = []
    for r in s1["by"]["index_side_mny"]:
        rows.append([r["und"], SIDE[r["side"]], MNY[r["mny"]], f"{r['rows']:,}", rs(r["premium"]), rs(r["net15"]),
                     rs(r["net30"]), rs(r["net60"]), rs(r["net1505"]), rs(r["net1520"]), f"{r['mfe_med']:.0f}%",
                     f"{r['mae_med']:.0f}%", f"{r['t_best_med']:.0f}", rs(r["orb"]), rs(r["sweep"]), rs(r["liq"]),
                     pct(r["orb_win"], 0), pct(r["big_w"]), pct(r["big_l"])])
    L += table(["index", "side", "strike", "rows", "median fill Rs", "+15m", "+30m", "+60m", "15:05", "15:20",
                "MFE", "MAE", "min to best", "orb", "sweep", "liq", "orb win", "big win", "big loss"], rows)
    for key, names, title in (("index_tod", TOD, "entry time"), ("index_dte", DTE, "days to expiry")):
        L += ["", f"By {title}:", ""]
        col = "tod" if key == "index_tod" else "dteb"
        rows = [[r["und"], names[int(r[col])], f"{r['rows']:,}", f"{r['days']:,}", rs(r["net30"]), rs(r["net1505"]),
                 rs(r["orb"]), rs(r["liq"]), pct(r["big_w"]), pct(r["big_l"]), f"{r['mfe_med']:.0f}%"]
                for r in s1["by"][key]]
        L += table(["index", title, "rows", "sessions", "+30m", "15:05", "orb", "liq", "big win", "big loss",
                    "median MFE"], rows)
    return L


def itemset_rows(lst, base_w, base_l):
    return [[x["text"], f"{x['n']:,}", f"{x['days']:,}", pct(x["support"]), pct(x["win"]), f"{x['lift_w']:.2f}",
             pct(x["lose"]), f"{x['lift_l']:.2f}", pct(x["cover_w"]), pct(x["cover_l"]), rs(x["mean_net1505"]),
             rs(x["mean_orb"])] for x in lst]


HEAD = ["pattern (before entry)", "rows", "sessions", "support", "big-win rate", "win lift", "big-loss rate",
        "loss lift", "share of all big wins", "share of all big losses", "mean 15:05 Rs", "mean orb Rs"]


def step2_md(s2):
    bw, bl = s2["base_w"], s2["base_l"]
    L = [f"## Step 2: back-tracking (design only: {s2['rows']:,} option-minutes on the 5-minute grid, "
         f"{s2['days']:,} sessions)", "",
         f"- Base rates: big winner (net ≥ +50% at 15:05) **{pct(bw)}**, big loser (≤ −50%) **{pct(bl)}**; mean net at "
         f"15:05 Rs {rs(s2['base_net1505'])}, under the ORB exits Rs {rs(s2['base_orb'])} per lot.",
         f"- {s2['itemsets']:,} itemsets of 1–3 items met the floors (≥ 0.5% of rows, ≥ 100 sessions). Tables show "
         "itemsets with ≥ 2,000 rows unless stated.", "",
         "### Most common before big winners vs how common overall (raw frequency is not an edge)", ""]
    L += table(HEAD, itemset_rows(s2["common_w_size1"], bw, bl))
    L += ["", "### Highest big-winner lift", ""] + table(HEAD, itemset_rows(s2["top_lift_w"], bw, bl))
    L += ["", "### Highest net expectancy under the ORB exits", ""] + table(HEAD, itemset_rows(s2["top_orb"], bw, bl))
    L += ["", "### Big losers: highest loss lift (what to avoid)", ""] + table(HEAD, itemset_rows(s2["top_lift_l"], bw, bl))
    L += ["", "### Big losers: most common before them", ""] + table(HEAD, itemset_rows(s2["common_l"][:10], bw, bl))
    L += ["", "### Second view: top / bottom decile of ORB-exit net Rs per index (pairs)", "",
          f"Deciles: {json.dumps(s2['orb_deciles'])}", ""]
    L += table(HEAD, itemset_rows(s2["orb_view_top_lift"][:10], s2["orb_top_share"], s2["orb_bottom_share"]))
    L += ["", "Bottom decile:", ""] + table(HEAD, itemset_rows(s2["orb_view_bottom_lift"][:10], 0, 0))
    L += ["", "### Decision tree (depth 4, big winner vs the rest; descriptive only)", ""]
    L += table(["leaf (item codes)", "rows", "big-win rate", "lift", "mean 15:05 Rs", "mean orb Rs", "big-loss rate"],
               [[x["path"], f"{x['n']:,}", pct(x["rate"]), f"{x['lift']:.2f}", rs(x["mean_net1505"]),
                 rs(x["mean_orb"]), pct(x["mean_lose"])] for x in s2["tree"]])
    return L


def gates(rule_d, rule_h, q):
    g1 = sum(b > 0 for b in rule_d["blocks"]) >= 3
    h = rule_h["holdout"]
    g2 = h["trades"] >= K.MIN_HOLD_TRADES and h["net"] > 0
    g3 = q < K.BH_Q
    g4 = rule_h["pattern_twin_p"] < K.TWIN_P
    g5 = rule_h["holdout_x2_net"] > 0
    g6 = h["max_dd"] > -K.ACCOUNT_DD
    return [g1, g2, g3, g4, g5, g6]


def step3_md(s3, hold, fam):
    L = ["## Step 3: rules from the patterns", "",
         f"Design blocks: {', '.join(f'{a}..{b}' for a, b in s3['blocks'])}.", "", "### Walk-forward (design)", ""]
    rows = []
    for f in s3["wf"]:
        for r in f["rules"]:
            t = r["test"]
            rows.append([f["test_block"], r["rule"], rs(r["train_net"]), t["trades"], rs(t["net"]), pct(t["win_rate"], 0)])
        rows.append([f["test_block"], f"**all {len(f['rules'])} rules**", "", "", f"**{rs(f['test_total'])}**", ""])
    L += table(["test block", "rule (selected on the earlier blocks)", "train net", "test trades", "test net Rs",
                "test win"], rows)
    L += ["", "### The final rules (selected once on the whole design period)", ""]
    rows = []
    for r in s3["final"]:
        d = r["design"]
        rows.append([r["id"], r["rule"], f"{r['lift_w']:.2f}", pct(r["support"]), f"{d['trades']}",
                     f"{d['trades_per_day']:.2f}", pct(d["win_rate"], 0), rs(d["avg_win"]), rs(d["avg_loss"]),
                     rs(d["per_day"]), pct(d["green_days"], 0), rs(d["worst_day"]), rs(d["max_dd"]),
                     f"{d['big_cut_short']}/{d['big_reached']}", " / ".join(rs(b) for b in r["blocks"]),
                     rs(r["design_x2_net"]), f"{r['design_twin_p']:.3f}"])
    L += table(["id", "rule", "win lift", "support", "trades", "trades/day", "win", "avg win", "avg loss",
                "Rs/day/lot", "green days", "worst day", "max DD", "big cut short", "blocks", "2x spread",
                "twin p (design)"], rows)
    if not hold:
        L += ["", "_The holdout has not been read._"]
        return L
    ps = [r["entry_twin_p"] for r in hold["rules"]]
    allp = ps + [x["p"] for x in fam]
    q = MN.bh(allp)[:len(ps)] if allp else []
    L += ["", f"### Holdout (read once; sessions per index {hold['sessions']})", ""]
    rows = []
    if [r["id"] for r in hold["rules"]] != [r["id"] for r in s3["final"]]:
        raise SystemExit("step3_design.json no longer matches the rules read on the holdout; do not re-run step3")
    for r, rd, qq in zip(hold["rules"], s3["final"], q):
        h = r["holdout"]
        g = gates(rd, r, qq)
        rows.append([r["id"], h["trades"], f"{h['trades_per_day']:.2f}", pct(h["win_rate"], 0), rs(h["avg_win"]),
                     rs(h["avg_loss"]), rs(h["net"]), rs(h["per_day"]), pct(h["green_days"], 0), rs(h["worst_day"]),
                     rs(h["max_dd"]), f"{h['big_cut_short']}/{h['big_reached']}", rs(r["holdout_x2_net"]),
                     f"{r['entry_twin_p']:.3f} / {qq:.3f}", f"{r['pattern_twin_p']:.3f} ({r['pattern_twins']})",
                     "".join("✓" if x else "✗" for x in g), "**PASS**" if all(g) else "fail"])
    L += table(["id", "trades", "trades/day", "win", "avg win", "avg loss", "net Rs", "Rs/day/lot", "green days",
                "worst day", "max DD", "big cut short", "2x spread", "entry-twin p / BH q", "pattern-twin p (n)",
                "gates 1-6", "verdict"], rows)
    L += ["", f"BH over {len(allp)} p-values: the {len(ps)} R24 rules + {len(fam)} R20–R22 holdout twin p-values "
          "(results/family_p.json; R23 has not read a holdout)."]
    return L


def main():
    s1, s2, s3, hold = load("step1.json"), load("step2.json"), load("step3_design.json"), load("step3_holdout.json")
    fam = load("family_p.json") or []
    L = ["# R24 results (generated by report.py; numbers per lot, net of the app's charges and the spread)", ""]
    if s1:
        L += step1_md(s1) + [""]
        if s1.get("month"):
            L += ["## Step 1, nearest MONTHLY expiry (opt_<UND>_MONTH)", ""] + step1_md(s1["month"])[2:] + [""]
    if s2:
        L += step2_md(s2) + [""]
    if s3:
        L += step3_md(s3, hold, fam) + [""]
    (R / "report.md").write_text("\n".join(L) + "\n")
    print(f"wrote {R / 'report.md'}")


if __name__ == "__main__":
    main()
