"""R27 final: merges results.json (M / F / G / Q, walk-forward) and k_results.json (Boss's K rules, all expiries), runs
BH over the full family (R21 9 + R25 9 + R26 10 + R27 11 = 39), applies the gates and writes results/report.md and
results/final.json.

    python3 research/hunt/r27/r27report.py
"""
from __future__ import annotations

import json

import r27cfg as K27
import r25rules as RU


def f(x):
    return f"{x:+,.0f}" if isinstance(x, (int, float)) else str(x)


def main():
    r = json.loads((K27.RESULTS / "results.json").read_text())
    k = json.loads((K27.RESULTS / "k_results.json").read_text())
    prev = json.loads((K27.ROOT / "research/hunt/r26/results/results.json").read_text())["bh_family"]
    rules = dict(r["rules"]) | k
    fam = {kk: v["p"] for kk, v in prev.items()} | {f"R27-{kk}": v["twins"]["p"] for kk, v in rules.items()}
    qs = dict(zip(fam, RU.bh(list(fam.values()))))
    for rid, v in rules.items():
        v["q"] = round(qs[f"R27-{rid}"], 4)
        m = v["m"]
        if rid in k:
            third = all(v["blocks"][b]["net"] > 0 for b in v["blocks"])
        else:
            third = sum(v["years"][y]["net"] > 0 for y in v["years"]) >= 3
        g = {"1 >= 30 trades": m["trades"] >= 30, "2 net > 0": m["net"] > 0, "3 years / blocks": third,
             "4 BH q < 0.05": v["q"] < 0.05, "5 x2 spread > 0": v["x2_spread"] > 0,
             "6 w/o best day > 0": m["net_without_best_day"] > 0, "7 max DD > -40k": m["max_dd"] > -40000}
        v["gates"], v["passes"] = g, all(g.values())
    out = {"bh_size": len(fam), "bh_min_q": round(min(qs.values()), 4), "bh": {kk: round(q, 4) for kk, q in qs.items()},
           "rules": rules, "desc_ivrv": r["desc_ivrv"], "base_per1000": r["base_per1000"], "rows": r["rows"]}
    (K27.RESULTS / "final.json").write_text(json.dumps(out, indent=1, default=str))
    L = ["# R27 tables: the NIFTY zero-to-hero hunt", "",
         f"Decision rows: {r['rows']:,} on {r['days']} NIFTY expiries (Rs 1-30 OTM options, every 5 minutes 12:00-14:55). "
         f"Walk-forward test expiries 2023-26: {r['test_days']}. K rules: all 271 expiries. BH family {len(fam)}.", "",
         "| rule | trades | hit % | ≥ 2× / 4× / 10× % | net Rs | per Rs 1,000 | worst day | best day | max DD | losing streak | net w/o best day | 2× spread | twin mean | p | q | passes |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for rid, v in rules.items():
        m = v["m"]
        L.append(f"| {rid} | {m['trades']} | {m['hit_pct']} | {m.get('x2_pct', 0)} / {m.get('x4_pct', 0)} / {m.get('x10_pct', 0)} | "
                 f"**{f(m['net'])}** | {f(m['per_1000_risked'])} | {f(m['worst_day'])} | {f(m['best_day'])} | {f(m['max_dd'])} | "
                 f"{m['losing_streak_trades']} | {f(m['net_without_best_day'])} | {f(v['x2_spread'])} | {f(v['twins']['mean'])} | "
                 f"{v['twins']['p']} | {v['q']} | {'yes' if v['passes'] else 'no'} |")
    L += ["", "## Gates", "", "| rule | " + " | ".join(next(iter(rules.values()))["gates"]) + " |",
          "|---|" + "---|" * 7]
    for rid, v in rules.items():
        L.append(f"| {rid} | " + " | ".join("pass" if x else "FAIL" for x in v["gates"].values()) + " |")
    L += ["", "## By year (trades / net Rs)", "", "| rule | " + " | ".join(str(y) for y in range(2021, 2027)) + " |", "|---|" + "---|" * 6]
    for rid, v in rules.items():
        L.append(f"| {rid} | " + " | ".join((f"{v['years'][str(y)]['trades']} / {f(v['years'][str(y)]['net'])}" if str(y) in v["years"] else "—")
                                         for y in range(2021, 2027)) + " |")
    L += ["", "## Walk-forward choices (made on earlier years only)", ""]
    for rid, v in r["rules"].items():
        L.append(f"- {rid}: " + "; ".join(f"{y}: {c}" for y, c in v["choices"].items()))
    L += ["", "## F1's trades by price band (net Rs)", ""] + [f"- {b}: {x['trades']} trades, {f(x['net'])}" for b, x in r["rules"]["F1"]["bands"].items()]
    L += ["", "## Every decision row: mean net per Rs 1,000 by price band × iv_rv (implied vol ÷ forecast realized vol)", "",
          f"All rows: E1 (hold to 15:05) {r['base_per1000']['E1']}, E3 (half at 2× + step lock) {r['base_per1000']['E3']}.", "",
          "| band | iv_rv | rows | E1 per Rs 1,000 | E3 per Rs 1,000 |", "|---|---|---|---|---|"]
    for key, x in r["desc_ivrv"]["E1"].items():
        b, iv = key.split(" | ")
        L.append(f"| {b} | {iv} | {x['n']:,} | {x['per1000']} | {r['desc_ivrv']['E3'][key]['per1000']} |")
    L += ["", "## F1's 5 best and 3 worst trades", ""]
    tl = sorted(r["rules"]["F1"]["trades_list"], key=lambda t: -t["net"])
    for t in tl[:5] + tl[-3:]:
        L.append(f"- {t['day']} {t['side']}E {t['strike']:g} at Rs {t['price']} (row {t['t']}): peak {t['peak']}×, net {f(t['net'])}")
    (K27.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("\n".join(L[:20]))
    print("BH", len(fam), "min q", round(min(qs.values()), 4), "passes", [x for x, v in rules.items() if v["passes"]])


if __name__ == "__main__":
    main()
