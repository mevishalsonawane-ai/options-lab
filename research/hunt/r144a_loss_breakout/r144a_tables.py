"""R144a: print the write-up's tables (markdown) from results/*.json. No lake needed.

    python research/hunt/r144a_loss_breakout/r144a_tables.py > /tmp/r144a_tables.md
"""
from __future__ import annotations

import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
R = HERE / "results"
PERS = ("unseen", "design", "final")


def rs(x) -> str:
    if x is None:
        return "-"
    x = float(x)
    return ("-" if x < 0 else "") + "Rs " + f"{abs(round(x)):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * float(x):.0f}%"


def load():
    d = json.loads((R / "design.json").read_text())["arms"]
    f = json.loads((R / "final.json").read_text())["arms"]
    u = json.loads((R / "unseen.json").read_text())["arms"]
    rep = json.loads((R / "report.json").read_text())
    return d, f, u, rep


def per(arm, d, f, u, key):
    return {"unseen": u[arm][key]["unseen"], "design": d[arm][key]["design"], "final": f[arm][key]["final"]}


def main():
    d, f, u, rep = load()
    import r144a_feat as F
    names = dict(F.NAMES)
    names.update({"A1": "push into the entry", "A2": "day's range so far / ADR", "A3": "stretch from the open",
                  "A4": "beyond yesterday's extreme", "A5": "entry minute", "A6": "signal bar close location",
                  "A7": "signal bar range / ATR", "A8": "20-bar box / ATR", "A9": "15-min trend",
                  "A10": "yesterday's range / ADR", "A11": "earlier fake pokes", "C1": "gap (trade's way)",
                  "C2": "realised volatility", "C3": "VIX 30-min change", "C4": "breadth agreement",
                  "C5": "VIX regime", "C6": "weekday"})
    for arm in ("R72", "R51", "R52"):
        print(f"\n## {arm}\n")
        ov = per(arm, d, f, u, "overview")
        print("| period | trades | losers | loser rate | net | money lost (losing trades) | avg loss |")
        print("|---|---|---|---|---|---|---|")
        for p in PERS:
            o = ov[p]
            print(f"| {p} | {o['n']:,} | {o['losers']:,} | {pct(o['loser_rate'])} | {rs(o['net'])} | "
                  f"{rs(o['sum_losses'])} | {rs(o['avg_loss'])} |")
        for key, title in (("lost_by_exit", "exit"), ("lost_by_peak", "peak")):
            lb = per(arm, d, f, u, key)
            cats = sorted({c for p in PERS for c in lb[p]})
            print(f"\n| money lost by {title} | " + " | ".join(PERS) + " |")
            print("|---|" + "---|" * len(PERS))
            for c in cats:
                cells = []
                for p in PERS:
                    x = lb[p].get(c)
                    cells.append(f"{x['losers']:,} losers, {rs(x['lost'])} ({pct(x['share'])})" if x else "-")
                print(f"| {c} | " + " | ".join(cells) + " |")
        bu = per(arm, d, f, u, "bunching")
        print("\n| bunching | " + " | ".join(PERS) + " |")
        print("|---|" + "---|" * len(PERS))
        for k, lab in (("losing_days", "losing days"), ("days", "days traded"), ("worst_day", "worst day"),
                       ("worst5_days_share_of_losses", "worst 5 days' share of all losing-day money"),
                       ("losers_on_all_loss_index_days", "losers on index-days where every trade lost"),
                       ("longest_losing_trade_streak", "longest losing streak (trades)"),
                       ("longest_losing_day_streak", "longest losing streak (days)")):
            vals = [bu[p][k] for p in PERS]
            fm = rs if k == "worst_day" else (pct if "share" in k or "losers_on" in k else (lambda x: f"{x:,}"))
            print(f"| {lab} | " + " | ".join(fm(v) for v in vals) + " |")
        st = rep["steady"][arm]["steady"]
        print("\nSteady (every period, >= 30 trades each):")
        for s in st:
            print(f"- {s['feature']} {names.get(s['feature'], '')} slice {s['slice']}: " + "; ".join(
                f"{p} {s[p]['n']:,} trades, lose {pct(s[p]['loser_rate'])}, avg {rs(s[p]['avg'])}" for p in PERS))
        print("\nWorst 20 (all periods):")
        w = sorted(u[arm]["worst20"]["unseen"] + d[arm]["worst20"]["design"] + f[arm]["worst20"]["final"],
                   key=lambda x: x["net"])[:20]
        print("| day | index | side | entry | held | exit | peak | net | V1 | Q2 | O4 | C7 | C8 | F2 | F3 |")
        print("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        for x in w:
            print(f"| {x['day']} | {x['und']} | {x['side']} | {x['entry']} | {x['held_min']} | {x['why']} | "
                  f"{pct(x['peak']) if x['peak'] is not None else '-'} | {rs(x['net'])} | {x['V1']} | {x['Q2']} | "
                  f"{x['O4']} | {x['C7']} | {x['C8']} | {x['F2']} | {x['F3']} |")
    print("\n## Tests\n")
    print("| test | arm | skip | period | trades | win | net | losing days | drawdown | worst day | worst month | p |")
    print("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for t, r in rep["tests"].items():
        for p in ("unseen", "final"):
            x = r[p]
            a, b = x["today"], x["rule"]
            print(f"| {t} | {r['arm']} | {r['name']} = slice {r['slice']} | {p} | {a['trades']:,} -> {b['trades']:,} | "
                  f"{pct(a['win'])} -> {pct(b['win'])} | {rs(a['net'])} -> {rs(b['net'])} | {rs(a['loss_sum'])} -> "
                  f"{rs(b['loss_sum'])} | {rs(a['max_dd'])} -> {rs(b['max_dd'])} | {rs(a['worst_day'])} -> "
                  f"{rs(b['worst_day'])} | {rs(a['worst_month'])} -> {rs(b['worst_month'])} | {x['twin']['p']:.3f} |")
        print(f"| {t} | | q {r['q']}, {'PASSES' if r['passed'] else 'fails: ' + '; '.join(r['all_fails'])} |"
              " | | | | | | | | |")


if __name__ == "__main__":
    main()
