"""R158 report: results/report.md (plain summary for Boss first) from results/*.json.

    python research/hunt/r158_beat_breadth_donchian/r158report.py
"""
from __future__ import annotations

import json

import r158cfg as K
from r156report import PN, line, pct, rs

NAME = {"NET-EV": "network's own entries, forecast net > 0", "NET-TOP5": "network's own entries, top 5% of forecasts",
        "NET-TOP1": "network's own entries, top 1% of forecasts", "PICK": "Breadth Donchian's entries the network likes (forecast net > 0)",
        "PICK50": "Breadth Donchian's entries in the network's better half"}


def row(s: dict) -> str:
    if not s or not s.get("trades"):
        return "0 trades"
    return (f"{s['trades']:,} / {pct(s['win'])} / {rs(s['net'])} / {rs(s['per_trade'])} / {rs(s.get('worst_day'))} / "
            f"{rs(-s['max_dd']) if s.get('max_dd') is not None else '-'} / {rs(s.get('ex_top5'))}")


def main():
    d = json.loads((K.RESULTS / "design.json").read_text())
    f = json.loads((K.RESULTS / "final.json").read_text())
    dec = json.loads((K.RESULTS / "decision.json").read_text())
    imp = json.loads((K.RESULTS / "importance.json").read_text())
    b2, b3 = dec["_b0"]["P2"], dec["_b0"]["P3"]
    passed = [t for t in K.TESTS if dec[t]["passed"]]
    both = [t for t in K.TESTS if dec[t]["P3"]["trades"] and dec[t]["P3"]["win"] > b3["win"] and dec[t]["P3"]["net"] > b3["net"]]
    both2 = [t for t in both if dec[t]["P2"]["win"] > b2["win"] and dec[t]["P2"]["net"] > b2["net"]]
    fq = {"O1": d["O1"]["forecast"], "P2": d["P2"]["forecast"], "P3": f["P3"]["forecast"]}
    g = imp["groups"]
    tot = sum(max(v, 0) for v in g.values()) or 1.0
    gs = sorted(g.items(), key=lambda kv: -kv[1])
    r151 = K.R151_PICK
    L = ["# R158: can anything beat Breadth Donchian on win rate and profit?", "",
         "## For Boss (short)", "",
         f"- **Verdict: {'yes: ' + ', '.join(passed) if passed else 'nothing found beats Breadth Donchian on both win rate and profit in every period'}.** "
         f"{len(passed)} of {len(K.TESTS)} rules passed the gates.",
         f"- **The bar (Breadth Donchian, 1 lot, 5 indices):** Apr 2025 - Mar 2026: {line(b2)}; Apr - Oct 2026: {line(b3)}. "
         f"With R151's picker (already in the app): {r151['trades']} trades, win {pct(r151['win'], 1)}, {rs(r151['net'])} in Apr - Oct 2026.",
         "- **What was tried:** a network trained on the result of Breadth Donchian's own exit (-15% stop, profit ladder, 30 "
         "minutes unless in profit) at every 5-minute moment since Aug 2021, reading the last hour of all five indices. "
         "It was asked for its own entries, and to pick among Breadth Donchian's entries."]
    for t in K.TESTS:
        x = dec[t]
        o1 = f"2023 - Mar 2025: {line(x['O1'])}; " if "O1" in x else ""
        L.append(f"- **{t} ({NAME[t]}):** {o1}Apr 2025 - Mar 2026: {line(x['P2'])}; Apr - Oct 2026: {line(x['P3'])}. "
                 + ("PASS." if x["passed"] else "Fails: " + "; ".join(x["fails"]) + "."))
    L += [f"- **Above Breadth Donchian on both win rate and net in the final period:** {', '.join(both) or 'none'}; in both "
          f"the walk-forward year and the final: {', '.join(both2) or 'none'}.",
          f"- **Can the network forecast this exit's result?** Score (0.5 = coin): {fq['O1']['auc']} / {fq['P2']['auc']} / "
          f"{fq['P3']['auc']} (O1 / P2 / P3). An average moment bought with this exit: win {pct(fq['P3']['base']['win'])}, "
          f"needed {pct(fq['P3']['base']['be_win'])}, {rs(fq['P3']['base']['per_trade'])} a trade (final period); its top tenth "
          f"by forecast net: win {pct(fq['P3']['tenths_by_net'][-1]['win'])}, needed {pct(fq['P3']['tenths_by_net'][-1]['be_win'])}, "
          f"{rs(fq['P3']['tenths_by_net'][-1]['per_trade'])} a trade.",
          "- **What it leans on:** " + "; ".join(f"{k} {100 * max(v, 0) / tot:.0f}%" for k, v in gs[:4]) + ".",
          "- Nothing changes in the app. Every rule is logged in `research/experiments/entries/r158-*.yaml`.", "",
          "Signal source: the index and the other four indices (1-minute bars), index-derived context; option-chain OI and "
          "India VIX as context; the 1-ITM option is bought and managed with Breadth Donchian's premium exits. **Breadth "
          "Donchian's own trades in Apr - Oct 2026 were read by R72 - R153: weak evidence.**", "",
          "## How it was tested", "",
          "Pre-registered in `PREREG.md` before any label or model. Examples: R156 / R157's 733,260 moments (every 5-minute "
          "close 09:19 - 14:44, both sides, 5 indices, Aug 2021 - 13 Oct 2026), expiry days without a label (the arm never "
          "trades them). Labels are R106's engine with exits XL, equal to the arm's own pricing (tested). Network: R157's "
          "(13 sequence channels + the 43-number table), one output pair, 3 seeds averaged, expanding walk-forward (2023, "
          "2024, 2025, Jan - Mar 2026, final). Standalone rules use R72's taking rule (one open a side, 2 fills a side a day "
          f"per index). R72's own results reproduce first: design {d['parity'][0]:,} trades / {rs(d['parity'][1])}, final "
          f"{f['parity'][0]:,} / {rs(f['parity'][1])}. Gates: net > 0 in P2 and P3 (standalone also O1); a higher win rate AND "
          "a higher net than R72 in P2 and P3; twins (random entries for standalone rules, random subsets of R72's trades "
          f"for the picks) with BH q <= 0.10 (m = {dec['_m']['total']:,}); >= 40 final trades.", "",
          "## Every rule against Breadth Donchian (1 lot)", "",
          "Cells: trades / win / net / a trade / worst day / max drawdown / net without the 5 best trades.", "",
          "| rule | O1 (2023 - Mar 2025) | P2 (Apr 2025 - Mar 2026) | P3 final (Apr - 13 Oct 2026) | twins' mean | p | q |",
          "|---|---|---|---|---|---|---|",
          f"| **Breadth Donchian (B0)** | (arm starts 20 Nov 2024) | {row(b2)} | {row(b3)} | | | |"]
    for t in K.TESTS:
        x = dec[t]
        L.append(f"| {t} | {row(x['O1']) if 'O1' in x else '-'} | {row(x['P2'])} | {row(x['P3'])} | {rs(x['twin_mean'])} | {x['twin_p']} | {x['q']} |")
    L += ["", "## The network's own entries: where they are (final period)", "",
          "| rule | by index (trades / net) | calls | by hour (trades / net) | also Breadth Donchian entries |", "|---|---|---|---|---|"]
    for t in K.STANDALONE:
        r = f["P3"]["rules"][t]
        bu = "; ".join(f"{u} {s['trades']} / {rs(s['net'])}" for u, s in r["by_und"].items())
        bh = "; ".join(f"{h}h {v[0]} / {rs(v[1])}" for h, v in sorted(r["by_hour"].items(), key=lambda kv: int(kv[0])))
        L.append(f"| {t} | {bu} | {pct(r['calls'])} | {bh} | {r['also_r72']} of {r['all']['trades']} |")
    L += ["", "Breadth Donchian by index, final: " + "; ".join(f"{u} {s['trades']} / {rs(s['net'])}" for u, s in f["B0"]["P3"]["by_und"].items()) + ".",
          "", "## The forecast in tenths (by forecast net), every labelled moment", ""]
    for p in ("P2", "P3"):
        L += [f"**{PN[p]}** (score {fq[p]['auc']}):", "", "| tenth (1 = lowest) | moments | real win | win needed | net a trade |", "|---|---|---|---|---|"]
        L += [f"| {c['bin']} | {c['n']:,} | {pct(c['win'])} | {pct(c['be_win'])} | {rs(c['per_trade'])} |" for c in fq[p]["tenths_by_net"]]
        L.append("")
    L += ["## What the network leans on (shuffle test, Apr - Dec 2025)", "", "| input group | loss increase | share |", "|---|---|---|"]
    L += [f"| {k} | {v} | {100 * max(v, 0) / tot:.0f}% |" for k, v in gs]
    L += ["", "## Post window (14-19 Oct 2026, never gated)", "",
          "; ".join(f"{t}: {line(f['P4'][t])}" for t in K.STANDALONE) + "."]
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("report written;", len(passed), "passed")


if __name__ == "__main__":
    main()
