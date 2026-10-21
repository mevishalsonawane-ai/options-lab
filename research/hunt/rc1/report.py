"""RC1 tables: results/design.json (+ holdout.json once read) -> results/report.md

    python3 research/hunt/rc1/report.py
"""
from __future__ import annotations

import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
RES = os.path.join(HERE, "results")

NAMES = {
    ("a1", "{}"): "a1 buy and hold",
    ("a2", '{"v": "monthly"}'): "a2 DCA 12 monthly buys",
    ("a2", '{"v": "weekly"}'): "a2 DCA 52 weekly buys",
    ("a2", '{"v": "sip"}'): "a2 SIP pot/12 a month (IRR)",
    ("b", '{"v": "monthly"}'): "b 50/50 rebalanced monthly",
    ("b", '{"v": "band"}'): "b 50/50 rebalanced at 40/60",
    ("c", "{}"): "c spot trend (walk-forward rule)",
    ("d", "{}"): "d vol-targeted trend",
    ("g", "{}"): "g 4h breakout, spot",
}


def name(idea, var):
    if (idea, var) in NAMES:
        return NAMES[(idea, var)]
    v = json.loads(var)
    base = {"c-perp": "c-perp trend on perps", "f": "f funding carry", "g-perp": "g-perp 4h breakout on perps"}[idea]
    extra = " monthly reset (post-hoc)" if v.get("reset") else ""
    return f"{base}{extra}, {v['lev']}x, tax {v['reading']}"


def pct(x, d=1):
    return "n/a" if x is None else f"{x * 100:+.{d}f}%"


def rs(x):
    return "n/a" if x is None else f"Rs {x:,.0f}"


def rec(r):
    if r.get("recover_days") is not None:
        return f"{r['recover_days']} d"
    return f"not yet ({r.get('still_down_days')} d)"


def pick(rows, **kw):
    out = [r for r in rows if all(r.get(k) == v for k, v in kw.items())]
    return out


def one(rows, **kw):
    x = pick(rows, **kw)
    return x[0] if x else None


def main_table(rows, window, account, coins):
    lines = [f"| idea | gross CAGR | pre-tax CAGR | after tax+TDS CAGR | final (after) | max DD (after) | worst month | worst 12m "
             f"| green months | orders/yr | recover from worst DD | tax paid | no-tax final | TDS drag |",
             "|" + "---|" * 14]
    seen = []
    for r in rows:
        k = (r["idea"], r["var"])
        if r["window"] != window or r["account"] != account or r["coins"] != coins or k in seen:
            continue
        seen.append(k)
    for idea, var in seen:
        q = dict(window=window, account=account, coins=coins, idea=idea, var=var, case="base")
        g, p, a, n = (one(rows, version=v, **q) for v in ("gross", "pretax", "after", "after_notds"))
        if not a:
            continue
        cg = "cagr" if idea != "a2" or "sip" not in var else "irr"
        lines.append(f"| {name(idea, var)} | {pct(g.get(cg))} | {pct(p.get(cg))} | **{pct(a.get(cg))}** | {rs(a['final'])} "
                     f"| {pct(a['mdd'], 0)} | {pct(a['worst_month'], 0)} | {pct(a.get('worst_12m'), 0)} "
                     f"| {a['green_months'] * 100:.0f}% of {a['months']} | {a['orders_per_year']:.1f} | {rec(a)} "
                     f"| {rs(a['tax_paid'])} | {rs(p['final'])} | {pct(a[cg] - n[cg], 2)} |")
    return "\n".join(lines)


def cost_table(rows, window, account):
    lines = ["| idea (50/50) | low costs | base | high costs |", "|---|---|---|---|"]
    seen = []
    for r in rows:
        k = (r["idea"], r["var"])
        if r["window"] == window and r["account"] == account and r["coins"] == "50/50" and r["version"] == "after" \
                and k not in seen:
            seen.append(k)
    for idea, var in seen:
        q = dict(window=window, account=account, coins="50/50", idea=idea, var=var, version="after")
        cg = "irr" if idea == "a2" and "sip" in var else "cagr"
        vals = [one(rows, case=c, **q) for c in ("low", "base", "high")]
        lines.append(f"| {name(idea, var)} | " + " | ".join(pct(v.get(cg)) if v else "n/a" for v in vals) + " |")
    return "\n".join(lines)


def years_table(years):
    ideas = []
    for r in years:
        k = (r["idea"], r["var"])
        if k not in ideas:
            ideas.append(k)
    ys = []
    for r in years:
        if r["year"] not in ys:
            ys.append(r["year"])
    lines = ["| OOS year (Rs 1L, 50/50, after tax) | " + " | ".join(name(*k) for k in ideas) + " |",
             "|---|" + "---|" * len(ideas)]
    for y in ys:
        cells = []
        for k in ideas:
            r = [x for x in years if x["year"] == y and (x["idea"], x["var"]) == k]
            cells.append(f"{(r[0]['final'] / 100000 - 1) * 100:+.0f}% (DD {r[0]['mdd'] * 100:.0f}%)" if r else "-")
        lines.append(f"| {y} | " + " | ".join(cells) + " |")
    return "\n".join(lines)


def gates_table(g, hold=None):
    lines = ["| idea (primary: 50/50, Rs 1L, base, after tax) | span | entries | after-tax CAGR | max DD | Calmar | hold CAGR / DD / Calmar "
             "| twin p | BH q | gate 1 edge | gate 2 worth it | gate 4 high costs |",
             "|" + "---|" * 12]
    for k, v in g.items():
        lines.append(f"| {k} | {v['start']}..{v['end']} | {v['entries']} | {pct(v['cagr'])} | {pct(v['mdd'], 0)} | {v['calmar']:.2f} "
                     f"| {pct(v['hold_cagr'])} / {pct(v['hold_mdd'], 0)} / {v['hold_calmar']:.2f} | {v['twin_p']:.3f} "
                     f"| {v['bh_q']:.3f} | {'pass' if v['gate1'] else 'FAIL'} | {'pass' if v['gate2'] else 'FAIL'} "
                     f"| {'pass' if v['gate4'] else 'FAIL'} |")
    return "\n".join(lines)


def build():
    with open(os.path.join(RES, "design.json")) as f:
        d = json.load(f)
    out = ["# RC1 results (generated by report.py; do not edit by hand)", ""]
    out += ["## Data", "", "```", json.dumps(d["data"], indent=1), "```", "",
            "## Walk-forward rule picks (idea c / d / c-perp)", "",
            "| OOS year from | rule |", "|---|---|"] + [f"| {s} | {r} |" for s, r in d["rules"]] + [""]
    out += ["## Idea e: day of week (design 17 Aug 2017 - 12 Oct 2025, daily UTC returns)", "",
            "| coin | test | n days | mean bp | other days bp | t | p | BH q |", "|---|---|---|---|---|---|---|---|"]
    for r in d["dow"]:
        out.append(f"| {r['coin']} | {r['test']} | {r['n']} | {r['mean_bp']:+.1f} | {r['rest_bp']:+.1f} | {r['t']:+.2f} "
                   f"| {r['p']:.3f} | {r['bh_q']:.3f} |")
    out += ["", f"Any test passes BH at q = 0.05: **{d['dow_any_pass']}**", ""]
    out += ["## Gates on the walk-forward span", "", gates_table(d["gates"]), ""]
    for window in ("walk-forward", "design-full"):
        for acct in (100000, 25000):
            for coins in ("50/50", "BTC", "ETH"):
                out += [f"## {window}, Rs {acct:,}, {coins}", "", main_table(d["rows"], window, acct, coins), ""]
        out += [f"## Cost sensitivity, {window}, Rs 1,00,000, after tax+TDS CAGR", "", cost_table(d["rows"], window, 100000), ""]
    out += ["## Each walk-forward OOS year on its own (flat start)", "", years_table(d["years"]), ""]
    hp = os.path.join(RES, "holdout.json")
    if os.path.exists(hp):
        with open(hp) as f:
            h = json.load(f)
        out += [f"## HOLDOUT (read once): {h['holdout'][0]} .. {h['holdout'][1]} (perp / carry ideas to {h['holdout'][2]})", ""]
        out += ["| idea | after-tax final (Rs 1L) | twin median | twin p | gate 3 | hold 50/50 final | entries |",
                "|---|---|---|---|---|---|---|"]
        for k, v in h["gates"].items():
            out.append(f"| {k} | {rs(v['final'])} | {rs(v['twin_median'])} | {v['twin_p']:.3f} | {'pass' if v['gate3'] else 'FAIL'} "
                       f"| {rs(v['hold_final'])} | {v['entries']} |")
        out.append("")
        for acct in (100000, 25000):
            for coins in ("50/50", "BTC", "ETH"):
                out += [f"## holdout, Rs {acct:,}, {coins}", "", main_table(h["rows"], "holdout", acct, coins), ""]
        out += ["## Cost sensitivity, holdout, Rs 1,00,000", "", cost_table(h["rows"], "holdout", 100000), ""]
    with open(os.path.join(RES, "report.md"), "w") as f:
        f.write("\n".join(out) + "\n")
    print("wrote results/report.md")


if __name__ == "__main__":
    build()
