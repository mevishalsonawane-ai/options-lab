"""R109 tables from results/design.json, final.json, decision.json -> results/report.md.

    python research/hunt/r109_irathinker/r109report.py
"""
from __future__ import annotations

import json

import r109cfg as K

LABEL = {"m60-bn": "mom60 BANKNIFTY (monthly)", "m60-fin": "mom60 FINNIFTY (monthly)",
         "m60-sx": "mom60 SENSEX (monthly)", "m60-nf": "mom60 NIFTY (monthly, out of family)",
         "m60-mid": "mom60 MIDCPNIFTY (monthly, out of family)", "m60-nf-w": "mom60 NIFTY (weekly variant)",
         "m60-sx-w": "mom60 SENSEX (weekly variant)", "strangle-bn": "BANKNIFTY Monday short strangle"}
PER = (("history", "history (to 19 Nov 2024)"), ("design", "design"), ("wf", "walk-forward (Apr 2025 - Mar 2026)"),
       ("final", "final (1 Apr - 13 Oct 2026)"), ("post", "post-13 Oct (14-19 Oct)"))


def rs(x):
    if x is None:
        return "-"
    s = f"{abs(x):,.0f}"
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x, d=1):
    return "-" if x is None else f"{x * 100:.{d}f}%"


def pp(x, d=2):
    return "-" if x is None else f"{x:+.{d}f}%"


def p3(x):
    return "-" if x is None else (f"{x:.2g}" if x < 0.001 else f"{x:.3f}")


def load():
    D = json.loads((K.RESULTS / "design.json").read_text())
    F = json.loads((K.RESULTS / "final.json").read_text()) if (K.RESULTS / "final.json").exists() else {}
    C = json.loads((K.RESULTS / "decision.json").read_text()) if (K.RESULTS / "decision.json").exists() else {}
    return D, F, C


def period(D, F, name, p):
    src = D if p in ("history", "design", "wf") else F
    return src.get(name, {}).get("periods", {}).get(p)


def main_table(D, F) -> list:
    out = ["| test | period | trades | win | net (1 lot) | per trade | gross % of premium / trade | worst day | worst week "
           "| worst month | max drawdown | months <= -Rs 13k | twin p | IraThinker's BS tape, % / trade |",
           "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for n in K.TESTS:
        for p, lab in PER:
            s = period(D, F, n, p)
            if not s or not s.get("n"):
                out.append(f"| {LABEL[n]} | {lab} | 0 | - | - | - | - | - | - | - | - | - | - | - |")
                continue
            g = s.get("real_gross_pct") if "real_gross_pct" in s else s.get("gross_pct_mean")
            out.append(f"| {LABEL[n]} | {lab} | {s['n']:,} | {pct(s['win'])} | {rs(s['net'])} | {rs(s['per_trade'])} | "
                       f"{pp(g)} | {rs(s['worst_day'])} | {rs(s['worst_week'])} | {rs(s['worst_month'])} | "
                       f"{rs(s['max_dd'])} | {s['months_le_13k']} | {p3(s.get('twin_p'))} | {pp(s.get('bs_mean_pct'))} |")
    return out


def decision_table(C) -> list:
    if not C:
        return ["(decision not run yet)"]
    out = [f"BH over m = {C['m']:,} tests (R109's 8 + {C['n_prior']:,} earlier).", "",
           "| test | verdict | gating p (largest) | BH q (all earlier tests) | q (R109 alone) | q (every logged variant) "
           "| first failed gates |", "|---|---|---|---|---|---|---|"]
    for n, r in C["tests"].items():
        out.append(f"| {LABEL[n]} | **{r['verdict']}** | {p3(r['p'])} | {p3(r['q'])} | {p3(r['q_r109'])} | "
                   f"{p3(r['q_all_variants'])} | {'; '.join(r['fails'][:4])} |")
    return out


def extra(D, F) -> list:
    out = ["## mom60 detail (n per cell)", "",
           "| test | period | calls / trades | stops | DTE 0-2: n, net, gross % | DTE 3-7: n, net, gross % | exits after 15:10 "
           "| stale exits | entries skipped (no print) | real gross - 0.7% (IraThinker's haircut) | real net % of premium |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for n, t in K.TESTS.items():
        if t["kind"] != "mom60":
            continue
        for p, lab in PER:
            s = period(D, F, n, p)
            if not s or not s.get("n"):
                continue
            a, b = s["dte_0_2"], s["dte_3_7"]
            src = D if p in ("history", "design", "wf") else F
            out.append(f"| {LABEL[n]} | {lab} | {s['calls']} / {s['n']} | {s['stops']} | {a['n']}, {rs(a['net'])}, "
                       f"{pp(a['gross_pct'])} | {b['n']}, {rs(b['net'])}, {pp(b['gross_pct'])} | {s['after_1510']} | "
                       f"{s['stale']} | {src[n].get('skipped')} | {pp(s['real_gross_minus_07'])} | {pp(s['real_net_pct'])} |")
    out += ["", "## Strangle detail", "",
            "| period | weeks | expiry weeks | net % of premium (pooled) | gross % (mean) | IraThinker BS tape net % (mean) "
            "| margin (est., mean) | net / margin | weeks <= -Rs 13k | weeks <= -Rs 40k | stale legs repriced "
            "| 5 worst weeks (Monday, Rs, % of premium) | design bootstrap p |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for p, lab in PER:
        s = period(D, F, "strangle-bn", p)
        if not s or not s.get("n"):
            continue
        nm = s["net"] / s["margin_mean"] if s.get("margin_mean") else None
        w5 = "; ".join(f"{d} {rs(r)} ({x:+.0f}%)" for d, r, x in s["worst5"])
        out.append(f"| {lab} | {s['n']} | {s['exp_weeks']} | {pp(s['pooled_net_pct'], 1)} | {pp(s['gross_pct_mean'], 1)} "
                   f"| {pp(s['bs_mean_pct'], 1)} | {rs(s['margin_mean'])} | {pct(nm)} | {s['weeks_le_13k']} | "
                   f"{s['weeks_le_40k']} | {s['stale_legs']} | {w5} | {p3(s.get('boot_p'))} |")
    out += ["", "## Overlap with our paper arms' signals (same index, same day, same direction)", "",
            "| test | period | mom60 trades | R72 same day | R72 within 60 min / hold | R51 same day | R51 within 60 min / hold |",
            "|---|---|---|---|---|---|---|"]
    for n, t in K.TESTS.items():
        if t["kind"] != "mom60":
            continue
        for p, lab in PER:
            src = D if p in ("history", "design") else F
            o = src.get(n, {}).get("overlap", {}).get(p)
            if not o or not o["R72"]["n"]:
                continue
            a, b = o["R72"], o["R51"]
            out.append(f"| {LABEL[n]} | {lab} | {a['n']} | {a['same_day_same_way']} ({a['same_day_same_way'] / a['n']:.0%}) | "
                       f"{a['within_60m_or_hold']} ({a['within_60m_or_hold'] / a['n']:.0%}) | {b['same_day_same_way']} "
                       f"({b['same_day_same_way'] / b['n']:.0%}) | {b['within_60m_or_hold']} "
                       f"({b['within_60m_or_hold'] / b['n']:.0%}) |")
    return out


def main():
    D, F, C = load()
    md = ["# R109 results (generated by r109report.py)", "",
          f"Thresholds (|ret60| %): BANKNIFTY {K.THRESH['BANKNIFTY']}, FINNIFTY {K.THRESH['FINNIFTY']}, SENSEX "
          f"{K.THRESH['SENSEX']}; history q90 NIFTY {D['_meta']['hist_q90']['NIFTY'][0]:.4f} "
          f"(n = {D['_meta']['hist_q90']['NIFTY'][1]:,} bars), MIDCPNIFTY {D['_meta']['hist_q90']['MIDCPNIFTY'][0]:.4f} "
          f"(n = {D['_meta']['hist_q90']['MIDCPNIFTY'][1]:,} bars).", "",
          "## Decision", ""] + decision_table(C) + ["", "## Every test and period (1 lot, after dated charges and "
                                                    "liquidity slippage)", ""] + main_table(D, F) + [""] + extra(D, F)
    (K.RESULTS / "report.md").write_text("\n".join(md) + "\n")
    print("\n".join(md))


if __name__ == "__main__":
    main()
