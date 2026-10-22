"""Turns results/*.csv and results/descriptives.json into the markdown tables that research/DEEP_OPTIONS_SELLING.md
quotes (theta table, tail table, distribution, cell tables). Writes results/fragments.json.

    python3 -I research/hunt/r103_selling/write_doc.py
"""
from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
RESULTS = HERE / "results"
NAMES = {"STR": "short straddle", "STG": "short strangle", "IC": "iron condor", "IF": "iron fly",
         "X0": "no stop", "X1": "leg stop 1.5x", "X2": "leg stop 2x", "X3": "combined stop 1x credit",
         "X4": "combined stop 3% margin", "T0": "no target", "T1": "target 50%",
         "B0": "buy, no stop", "B1": "buy, stop 50% / target 100% of debit"}
CELL_COLS = ["und", "struct", "entry", "stop", "target", "dayset", "trades", "win_rate", "net", "avg_win", "avg_loss",
             "worst_day", "max_dd", "rom", "days_below_13k", "months_below_13k", "blocks_positive", "p_zero", "q",
             "twin_p", "modelled_share"]


def rs(x) -> str:
    return f"{x:+,.0f}" if np.isfinite(x) else "-"


def theta_table(desc: dict) -> str:
    out = []
    for und in ("NIFTY", "BANKNIFTY"):
        t = desc["theta_by_hour"][und]
        out.append(f"\n*{und}, Design A: the 09:20 ATM straddle's value as a share of its 09:20 value, median (quartiles), n days*\n")
        out.append("| days to expiry | 10:15 | 11:15 | 12:15 | 13:15 | 14:15 | 15:05 |\n|---|---|---|---|---|---|---|")
        for b in ("3+", "2", "1", "expiry"):
            cells = []
            for hm in ("10:15", "11:15", "12:15", "13:15", "14:15", "15:05"):
                r = t.get(f"{b}|{hm}")
                cells.append(f"{r['median'] * 100:.0f}% ({r['q25'] * 100:.0f}-{r['q75'] * 100:.0f}), n {r['n']}" if r else "-")
            out.append(f"| {b} | " + " | ".join(cells) + " |")
        marks = [f"14:{m:02d}" for m in range(0, 60, 10)] + ["15:00", "15:05", "15:10", "15:15", "15:20", "15:25"]
        out.append(f"\n*{und} expiry days: median share of the 09:20 straddle left in the last 90 minutes*\n")
        out.append("| " + " | ".join(marks) + " |\n|" + "---|" * len(marks))
        out.append("| " + " | ".join(f"{t[f'expiry|{m}']['median'] * 100:.0f}%" if f"expiry|{m}" in t else "-" for m in marks) + " |")
    return "\n".join(out) + "\n"


def tail_table(desc: dict) -> str:
    out = []
    for und in ("NIFTY", "BANKNIFTY"):
        rows = desc["tail_days"][und]
        out.append(f"\n*{und}: the 20 biggest close-to-close moves, 2017 - 14 Jul 2026 (one lot of that date's size)*\n")
        out.append("| date | close-to-close | gap at open | open-to-close | 09:20 straddle credit (pts) | lot | naked, no stop | naked, 2x leg stop |\n|---|---|---|---|---|---|---|---|")
        for r in rows:
            cr = r.get("credit_pts")
            out.append(f"| {r['date']} | {r['close_to_close_pct']:+.2f}% | {r['gap_pct']:+.2f}% | {r['open_to_close_pct']:+.2f}% | "
                       f"{cr if cr is not None else 'no option data'} | {r.get('lot') or '-'} | "
                       f"{rs(r['straddle_0920_X0_net']) if r.get('straddle_0920_X0_net') is not None else '-'} | "
                       f"{rs(r['straddle_0920_X2_net']) if r.get('straddle_0920_X2_net') is not None else '-'} |")
        w = desc["tail_days"][f"{und}_worst_straddle_days_per_unit"]
        out.append(f"\n*{und}: the 10 worst days for a 09:20 naked straddle held to the close (Aug 2021 - 14 Jul 2026), by points lost a unit*\n")
        out.append("| date | expiry day | credit (pts) | loss (pts a unit) | lot | net Rs |\n|---|---|---|---|---|---|")
        for r in w[:10]:
            out.append(f"| {r['date']} | {'yes' if r['expiry'] else 'no'} | {r['credit_pts']} | {r['loss_pts_per_unit']:+.0f} | {r['lot']} | {rs(r['net_rs'])} |")
    return "\n".join(out) + "\n"


def dist_text(desc: dict) -> str:
    out = []
    for und, d in desc["distribution_str_0920_X0_A"].items():
        out.append(f"- **{und}** (naked 09:20 straddle, no stop, {d['days']} days): total {rs(d['total'])}. The best 10% of days made "
                   f"{rs(d['best10pct_days_sum'])} and the worst 2% lost {rs(d['worst2pct_days_sum'])}. Median day {rs(d['median'])}, "
                   f"mean {rs(d['mean'])}, 5th to 95th percentile {rs(d['p5'])} to {rs(d['p95'])}, skew {d['skew']:+.2f}.")
    for und, d in desc["expiry_spikes_A"].items():
        out.append(f"- {und}, {d['expiries']} expiry days: the 09:20 straddle reached 1.5x its credit on {d['reached_1_5x']} of them "
                   f"and 2x on {d['reached_2x']}, at some minute before 15:05.")
    return "\n".join(out) + "\n"


def cells_table(df: pd.DataFrame, cols) -> str:
    head = "| " + " | ".join(cols) + " |\n|" + "---|" * len(cols) + "\n"
    rows = []
    for _, r in df.iterrows():
        v = []
        for c in cols:
            x = r.get(c, np.nan)
            if c in ("struct", "stop", "target", "rule"):
                v.append(NAMES.get(x, x))
            elif c == "entry":
                v.append(str(x).zfill(4))
            elif c in ("win_rate", "rom", "modelled_share"):
                v.append(f"{x * 100:.0f}%" if np.isfinite(x) else "-")
            elif c in ("p_zero", "q", "twin_p"):
                v.append(f"{x:.3f}" if np.isfinite(x) else "-")
            elif isinstance(x, (bool, np.bool_)):
                v.append("yes" if x else "no")
            elif isinstance(x, (float, np.floating)):
                v.append(rs(x))
            else:
                v.append(str(x))
        rows.append("| " + " | ".join(v) + " |")
    return head + "\n".join(rows) + "\n"


def main():
    desc = json.loads((RESULTS / "descriptives.json").read_text(encoding="utf-8"))
    A = pd.read_csv(RESULTS / "cells_A.csv")
    A["entry"] = A.entry.astype(str).str.zfill(4)
    f = {"THETA_TABLE": theta_table(desc), "TAIL_TABLE": tail_table(desc), "DIST": dist_text(desc)}
    h = A[(A.entry == "0920") & (A.dayset == "ALL") & (A.target == "T0") & (A.stop.isin(["X0", "X2", "X3"]))]
    f["HEADLINE"] = cells_table(h.sort_values(["und", "struct", "stop"]), CELL_COLS)
    best = A.sort_values("net", ascending=False).groupby(["und", "struct", "dayset"]).head(1)
    f["BEST_PER_STRUCT"] = cells_table(best.sort_values(["und", "dayset", "net"], ascending=[True, True, False]), CELL_COLS)
    e = A[(A.entry == "0920") & (A.dayset == "EXPIRY") & (A.target == "T0") & (A.stop.isin(["X0", "X2", "X3"]))]
    f["EXPIRY_0920"] = cells_table(e.sort_values(["und", "struct", "stop"]), CELL_COLS)
    buy = pd.read_csv(RESULTS / "buy_A.csv")
    buy["entry"] = buy.entry.astype(str).str.zfill(4)
    f["BUY"] = cells_table(buy[buy.entry == "0920"].sort_values(["und", "dayset", "struct", "rule"]),
                           ["und", "struct", "rule", "dayset", "trades", "win_rate", "net", "avg_win", "avg_loss", "worst_day", "max_dd", "p_zero"])
    nq = [c for c in CELL_COLS if c not in ("q", "twin_p")]
    if (RESULTS / "cells_B.csv").exists():
        B = pd.read_csv(RESULTS / "cells_B.csv")
        B["entry"] = B.entry.astype(str).str.zfill(4)
        hb = B[(B.entry == "0920") & (B.dayset == "ALL") & (B.target == "T0") & (B.stop.isin(["X0", "X2", "X3"]))]
        f["B_HEADLINE"] = cells_table(hb.sort_values(["und", "struct", "stop"]), nq)
    if (RESULTS / "sens_bn_dte5.csv").exists():
        Sx = pd.read_csv(RESULTS / "sens_bn_dte5.csv")
        Sx["entry"] = Sx.entry.astype(str).str.zfill(4)
        hs = Sx[(Sx.entry == "0920") & (Sx.dayset == "ALL") & (Sx.target == "T0") & (Sx.stop.isin(["X0", "X2", "X3"]))]
        f["SENS_BN"] = cells_table(hs.sort_values(["struct", "stop"]), nq)
    f["EVENTS"] = cells_table(pd.DataFrame(desc["event_days_A"]), ["und", "struct", "stop", "event_days", "trades", "net", "worst"])
    summ = [f"cells {len(A)}; net>0 {int((A.net > 0).sum())}; p<=0.05 {int((A.p_zero <= 0.05).sum())}; "
            f"q<=0.10 {int((A.q <= 0.10).sum())}; q<=0.10 and net>0 {int(((A.q <= 0.10) & (A.net > 0)).sum())}; "
            f"passes {int(A.passes.sum())}; stop cells net>0 {int(((A.net > 0) & (A.stop != 'X0')).sum())} of {int((A.stop != 'X0').sum())}"]
    for g, sub in A.groupby(["und", "struct"]):
        summ.append(f"- {g}: net>0 {int((sub.net > 0).sum())}/{len(sub)}, median net {rs(sub.net.median())}, best {rs(sub.net.max())}, "
                    f"worst {rs(sub.net.min())}, q<=0.1 and net>0 {int(((sub.q <= 0.1) & (sub.net > 0)).sum())}, passes {int(sub.passes.sum())}")
    fails = A[(A.net > 0) & (A.stop != "X0")]
    for gate in ("g_trades", "g_p", "g_blocks", "g_account"):
        summ.append(f"- positive stop cells failing {gate}: {int((~fails[gate].astype(bool)).sum())} of {len(fails)}")
    f["SUMMARY"] = "\n".join(summ) + "\n"
    (RESULTS / "fragments.json").write_text(json.dumps(f, indent=1), encoding="utf-8")
    print("fragments written", RESULTS / "fragments.json")


if __name__ == "__main__":
    main()
