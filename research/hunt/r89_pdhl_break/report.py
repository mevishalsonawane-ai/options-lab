"""R89 report: results/*.json -> results/report.md (every table of the write-up, with sample sizes).

    python -m research.hunt.r89_pdhl_break.report
"""
from __future__ import annotations

import json

from . import cfg as K


def _f(x, nd=0):
    if x is None:
        return "-"
    if isinstance(x, float) and nd:
        return f"{x:,.{nd}f}"
    try:
        return f"{x:,.0f}"
    except (TypeError, ValueError):
        return str(x)


def _pct(x):
    return "-" if x is None else f"{100 * x:.0f}%"


def cell_rows(cells: list, with_gates: bool) -> list:
    head = ("| instrument | variant | trades (a day) | win | avg win / loss | per trade | net 1 lot | without top 5 | max DD |"
            " worst day | worst month | false breaks | fixed-risk net | futures net | twin mean | p | q |"
            + (" fails |" if with_gates else ""))
    out = [head, "|" + "---|" * (head.count("|") - 1)]
    for c in cells:
        o = c["opt"]
        fut = c.get("fut", {})
        fails = ""
        if with_gates:
            g = c.get("gates", {})
            fails = " " + (",".join(k for k, v in g.items() if k != "pass" and not v) or "PASS") + " |"
        q = c.get("q", c.get("q_all"))
        name = K.LABEL.get(c["inst"], c["inst"])
        out.append(f"| {name} | {c['variant']} | {o.get('trades', 0)} ({o.get('per_day', 0):.2f}) | {_pct(o.get('win'))} |"
                   f" {_f(o.get('avg_win'))} / {_f(o.get('avg_loss'))} | {_f(o.get('expectancy'))} | {_f(o.get('net'))} |"
                   f" {_f(o.get('ex_top5'))} | {_f(o.get('max_dd'))} | {_f(o.get('worst_day'))} |"
                   f" {_f(o.get('worst_month'))} | {_pct(c.get('false_break'))} | {_f(c.get('opt_risk', {}).get('net'))} |"
                   f" {_f(fut.get('net'))} | {_f(o.get('twin_mean'))} | {o.get('twin_p', '-')} |"
                   f" {'-' if q is None else round(q, 3)} |" + fails)
    return out


def write():
    L = ["# R89 report (tables)", ""]
    pD = K.RESULTS / "design.json"
    if pD.exists():
        D = json.loads(pD.read_text())
        L += ["## Design: every cell (option = 1 lot of the 1-ITM option on real prints, after costs)", "",
              f"{D['n_cells']} cells; BH over all of them. Passers: {', '.join(D['passers']) or 'none'}.", ""]
        L += cell_rows(D["cells"], True)
        L += ["", "## Design: walk-forward folds (option net, trades)", "",
              "| instrument | variant | " + " | ".join(f"fold {i + 1}" for i in range(4)) + " |", "|---|---|---|---|---|---|"]
        for c in D["cells"]:
            fs = c["folds"] + [None] * (4 - len(c["folds"]))
            L.append(f"| {K.LABEL.get(c['inst'], c['inst'])} | {c['variant']} | " +
                     " | ".join("-" if f is None else f"{_f(f['net'])} ({f['trades']})" for f in fs) + " |")
        L += ["", "## Selection walk-forward (pick the best variant before each fold)", "",
              "| instrument | picks | selection net | C-B net | R-B net |", "|---|---|---|---|---|"]
        for inst, w in D["selection_wf"].items():
            picks = ", ".join(p["pick"] for p in w["picks"])
            L.append(f"| {K.LABEL.get(inst, inst)} | {picks} | {_f(w['selection_net'])} | {_f(w['cb_net'])} | {_f(w['rb_net'])} |")
        L += ["", "## Pooled (not tested)", "", "| group | variant | trades | option net | twin mean | fixed-risk net |"
              " futures net | false breaks |", "|---|---|---|---|---|---|---|---|"]
        for k, p in D["pooled"].items():
            g, v = k.split("|")
            L.append(f"| {g} | {v} | {p['trades']} | {_f(p['net'])} | {_f(p['twin_mean'])} | {_f(p['risk_net'])} |"
                     f" {_f(p['fut_net'])} | {_pct(p['false_break'])} |")
        L += ["", "## Design: extra counts", "", "| instrument | variant | option trades | not filled | no target |"
              " calls | avg risk / reward (points) | fixed-risk lots | exits |", "|---|---|---|---|---|---|---|---|---|"]
        for c in D["cells"]:
            L.append(f"| {K.LABEL.get(c['inst'], c['inst'])} | {c['variant']} | {c['opt'].get('trades', 0)} | {c.get('unfilled')} |"
                     f" {c.get('no_target')} | {c.get('calls', '-')} | {c.get('avg_risk_pts', '-')} / {c.get('avg_reward_pts', '-')} |"
                     f" {c.get('lots_risk_mean', '-')} | {c.get('why', {})} |")
    pO = K.RESULTS / "old_futures.json"
    if pO.exists():
        O = json.loads(pO.read_text())
        L += ["", "## Older index history, futures-only (Aug 2021 - 19 Nov 2024; descriptive)", "",
              "| index | variant | trades | win (gross) | net | per trade | gross | false breaks | worst month |",
              "|---|---|---|---|---|---|---|---|---|"]
        for c in O["cells"]:
            f = c.get("fut", {})
            L.append(f"| {c['inst']} | {c['variant']} | {f.get('trades', 0)} | {_pct(c.get('win_underlying'))} | {_f(f.get('net'))} |"
                     f" {_f(f.get('expectancy'))} | {_f(c.get('fut_gross'))} | {_pct(c.get('false_break'))} | {_f(f.get('worst_month'))} |")
    pF = K.RESULTS / "final.json"
    if pF.exists():
        F = json.loads(pF.read_text())
        L += ["", "## Holdout (1 Apr - 19 Oct 2026, read once)", "",
              f"Design passers carried: {', '.join(F['design_passers']) or 'none'}. Verdicts: {F['verdict'] or 'none'}.",
              "q here = BH over all 180 holdout cells (descriptive).", ""]
        L += cell_rows(F["cells"], False)
        L += ["", "## Holdout pooled (not tested)", "", "| group | variant | trades | option net | twin mean | fixed-risk net |"
              " futures net | false breaks |", "|---|---|---|---|---|---|---|---|"]
        for k, p in F["pooled"].items():
            g, v = k.split("|")
            L.append(f"| {g} | {v} | {p['trades']} | {_f(p['net'])} | {_f(p['twin_mean'])} | {_f(p['risk_net'])} |"
                     f" {_f(p['fut_net'])} | {_pct(p['false_break'])} |")
    pA = K.RESULTS / "arms.json"
    if pA.exists():
        A = json.loads(pA.read_text())
        for tag, res in A.items():
            L += ["", f"## Same days as the running arms ({tag})", "", "Arms: " + "; ".join(
                f"{k} {v['trades']} trades" + (f", net {_f(v['net'])}" if v.get("net") is not None else "")
                for k, v in res["arms"].items()), "",
                "| variant | arm | R89 trades | same day | same side | opposite side | R89 net on arm days (n) | R89 net other days (n) | daily corr |",
                "|---|---|---|---|---|---|---|---|---|"]
            for v, per in res["variants"].items():
                for arm, c in per.items():
                    if not c:
                        continue
                    L.append(f"| {v} | {arm} | {c['r89_trades']} | {_pct(c['share_same_day'])} | {_pct(c['share_same_side'])} |"
                             f" {_pct(c['share_opposite'])} | {_f(c['r89_net_arm_days'])} ({c['n_arm_days']}) |"
                             f" {_f(c['r89_net_other_days'])} ({c['n_other']}) | {c.get('daily_corr', '-')} |")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")


if __name__ == "__main__":
    write()
