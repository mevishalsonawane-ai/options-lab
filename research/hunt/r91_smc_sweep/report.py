"""R91 report: every table from results/*.json into results/report.md.

    python -m research.hunt.r91_smc_sweep.report
"""
from __future__ import annotations

import json

from . import cfg as K


def _f(x, nd=0):
    if x is None:
        return ""
    if isinstance(x, float) and nd:
        return f"{x:,.{nd}f}"
    try:
        return f"{x:,.0f}"
    except (TypeError, ValueError):
        return str(x)


def _pct(x):
    return "" if x is None else f"{100 * x:.0f}%"


def cell_table(cells: list, with_gates: bool) -> list:
    h = ("| instrument | variant | trades (a week) | win | avg win / loss | per trade | net | ex top 5 | max DD | "
         "worst day | worst month | green months | stopped (all / <= 15 min) | twin after (mean, p) | "
         "twin window (mean, p) | q | und pts / trade (win) | risk / reward pts | opt gross / cost pts |"
         + (" gates |" if with_gates else ""))
    out = [h, "|" + "---|" * (h.count("|") - 1)]
    for c in cells:
        o = c["opt"]
        u = c.get("und", {})
        g = c.get("gates", {})
        fails = ",".join(k for k, v in g.items() if k != "pass" and not v)
        q = c.get("q", c.get("q_all"))
        out.append(
            f"| {K.LABEL.get(c['inst'], c['inst'])} | {c['variant']} | {o.get('trades', 0)} ({o.get('per_week', 0):.2f}) | "
            f"{_pct(o.get('win'))} | {_f(o.get('avg_win'))} / {_f(o.get('avg_loss'))} | {_f(o.get('expectancy'))} | "
            f"{_f(o.get('net'))} | {_f(o.get('ex_top5'))} | {_f(o.get('max_dd'))} | {_f(o.get('worst_day'))} | "
            f"{_f(o.get('worst_month'))} | {o.get('green_months', '')} | {_pct(c.get('stopped'))} / "
            f"{_pct(c.get('fast_stop'))} | {_f(o.get('twin_mean'))}, {o.get('twin_p', '')} | "
            f"{_f(o.get('wtwin_mean'))}, {o.get('wtwin_p', '')} | {'' if q is None else f'{q:.2f}'} | "
            f"{_f(u.get('pts_mean'), 2)} ({_pct(u.get('pts_win'))}) | {_f(c.get('avg_risk_pts'), 1)} / "
            f"{_f(c.get('avg_reward_pts'), 1)} | {_f(c.get('opt_gross_pts'), 2)} / {_f(c.get('opt_cost_pts'), 2)} |"
            + ((" PASS |" if g.get("pass") else f" {fails} |") if with_gates else ""))
    return out


def funnel_table(cells: list) -> list:
    keys = ["sweeps", "cancel_pre", "no_candle", "no_target", "rr", "busy", "cap", "order_stop", "order_expired",
            "filled"]
    out = ["| instrument | variant | " + " | ".join(keys) + " | option printed |", "|" + "---|" * (len(keys) + 3)]
    for c in cells:
        F = c["funnel"]
        out.append(f"| {K.LABEL.get(c['inst'], c['inst'])} | {c['variant']} | " +
                   " | ".join(str(F.get(k, 0)) for k in keys) + f" | {c['opt'].get('trades', 0)} |")
    return out


def pooled_table(pooled: dict) -> list:
    out = ["| group | variant | sweeps | cancelled before entry | trades | option net | twin after mean | "
           "twin window mean | underlying Rs (no costs, n) | stopped | stopped <= 15 min |",
           "|---|---|---|---|---|---|---|---|---|---|---|"]
    for k, p in pooled.items():
        g, v = k.split("|")
        out.append(f"| {g} | {v} | {p['sweeps']} | {p['cancel_pre']} | {p['trades']} | {_f(p['net'])} | "
                   f"{_f(p['twin_mean'])} | {_f(p['wtwin_mean'])} | {_f(p['und_rs'])} ({p['und_trades']}) | "
                   f"{_pct(p['stopped'])} | {_pct(p['fast_stop'])} |")
    return out


def book_table(book: dict, grp: str) -> list:
    out = [f"| {grp} book | trades | win | net | max DD | worst day | worst month | green months |",
           "|---|---|---|---|---|---|---|---|"]
    for v, s in book.items():
        out.append(f"| {v} | {s['trades']} | {_pct(s['win'])} | {_f(s['net'])} | {_f(s['max_dd'])} | "
                   f"{_f(s['worst_day'])} | {_f(s['worst_month'])} ({s['worst_month_name']}) | {s['green_months']} |")
    return out


def fold_table(cells: list) -> list:
    out = ["| instrument | variant | folds (trades: net) |", "|---|---|---|"]
    for c in cells:
        out.append(f"| {K.LABEL.get(c['inst'], c['inst'])} | {c['variant']} | " +
                   "; ".join(f"{f['lo'][:7]}: {f['trades']}: {_f(f['net'])}" for f in c["folds"]) + " |")
    return out


def arms_table(A: dict, tag: str) -> list:
    if tag not in A:
        return []
    R = A[tag]
    out = [f"### {tag}", "", "Arms on the same stores: " +
           "; ".join(f"{k} {v['trades']} trades, net {_f(v['net'])}" for k, v in R["arms"].items()), "",
           "| R91 variant | arm | R91 trades | on arm days | same side | opposite | R91 net on arm days (n) | "
           "R91 net other days (n) | daily corr |", "|---|---|---|---|---|---|---|---|---|"]
    for v, per in R["variants"].items():
        for arm, c in per.items():
            if not c:
                continue
            out.append(f"| {v} | {arm} | {c['r89_trades']} | {_pct(c['share_same_day'])} | {_pct(c['share_same_side'])} | "
                       f"{_pct(c['share_opposite'])} | {_f(c['r89_net_arm_days'])} ({c['n_arm_days']}) | "
                       f"{_f(c['r89_net_other_days'])} ({c['n_other']}) | "
                       f"{', '.join(f'{u} {x}' for u, x in c.get('daily_corr', {}).items())} |")
    return out + [""]


def main():
    L = ["# R91 results (generated by report.py)", "",
         "Signal source of every rule: the underlying (index spot 1m; MCX near future, close-only). Trade: BUY the 1-ITM "
         "put after a swept swing high, the call after a swept swing low; 1 lot, real prints and costs.", ""]
    D = json.loads((K.RESULTS / "design.json").read_text())
    L += [f"## Design ({D['n_cells']} cells; passers: {', '.join(D['passers']) or 'none'})", ""]
    L += cell_table(D["cells"], True) + [""]
    L += ["## Design funnel (sweeps -> trades)", ""] + funnel_table(D["cells"]) + [""]
    L += ["## Design pooled (not tested)", ""] + pooled_table(D["pooled"]) + [""]
    L += ["## Design pooled books", ""] + book_table(D["book_indices"], "indices") + [""]
    L += book_table(D["book_mcx"], "MCX") + [""]
    L += ["## Design folds", ""] + fold_table(D["cells"]) + [""]
    L += ["## Selection walk-forward", "", "| instrument | picks (fold: pick, next net) | selection net | B net |",
          "|---|---|---|---|"]
    for inst, w in D["selection_wf"].items():
        L.append(f"| {K.LABEL.get(inst, inst)} | " + "; ".join(f"{p['fold'][:7]}: {p['pick']} {_f(p['next_net'])}"
                                                            for p in w["picks"]) +
                 f" | {_f(w['selection_net'])} | {_f(w['base_net'])} |")
    L.append("")
    fp = K.RESULTS / "final.json"
    if fp.exists():
        F = json.loads(fp.read_text())
        L += ["## Holdout (1 Apr - 19 Oct 2026, read once)", "",
              f"Design passers: {', '.join(F['design_passers']) or 'none'}; verdict: {json.dumps(F['verdict'])}", ""]
        L += cell_table(F["cells"], False) + [""]
        L += ["## Holdout funnel", ""] + funnel_table(F["cells"]) + [""]
        L += ["## Holdout pooled", ""] + pooled_table(F["pooled"]) + [""]
        L += ["## Holdout pooled books", ""] + book_table(F["book_indices"], "indices") + [""]
        L += book_table(F["book_mcx"], "MCX") + [""]
    ap = K.RESULTS / "arms.json"
    if ap.exists():
        A = json.loads(ap.read_text())
        L += ["## Same days as the running arms (indices)", ""] + arms_table(A, "design") + arms_table(A, "final")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    main()
