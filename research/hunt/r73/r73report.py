"""R73 report: results/design.json + results/final.json -> results/report.md (the tables HUNT_R73 quotes). Pure
rendering; `write()` reads and writes the files.
"""
from __future__ import annotations

import json

import r73cfg as R
import r73rules as P

TITLE = "# R73 report: Donchian with a trend filter or a 1-minute candle wait (tables)"
EXL = {"P": "P (paper today: -15%, A4 ladder + L25, 30 min)", "Q": "Q (the same without L25)"}


def rs(x) -> str:
    """Indian grouping: Rs 1,71,366."""
    if x is None:
        return "-"
    x = round(float(x))
    s = str(abs(x))
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        parts = []
        while len(head) > 2:
            parts.insert(0, head[-2:])
            head = head[:-2]
        if head:
            parts.insert(0, head)
        s = ",".join(parts + [tail])
    return ("-Rs " if x < 0 else "Rs ") + s


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


def name(v: str, des: dict) -> str:
    if v == "C":
        p = des["c_pick"]
        return f"C = {p['T']} + {p['W']}"
    return f"{v} {P.NAMES[v]}"


def row(label: str, s: dict, extra: str = "") -> str:
    if not s.get("trades"):
        return f"| {label} | 0 | - | - | - | - | - | - | - |{extra}"
    return (f"| {label} | {s['trades']:,} | {rs(s['net'])} | {pct(s['win'])} | {rs(s['avg_win'])} / {rs(s['avg_loss'])} "
            f"| {rs(s['worst_day'])} | {rs(s['max_dd'])} | {pct(s['green_days'])} | {s['days']} |{extra}")


def render(des: dict, fin: dict) -> str:
    L = [TITLE, ""]
    L.append(f"Final period 1 Apr - {fin['end']} ({fin['sessions']} sessions), read once ({fin['read']}). Design 20 Nov "
             "2024 - 31 Mar 2026. 1 lot per trade, net after dated charges and R17 slippage. Signal source: the index "
             "(Donchian 20 on 5-minute bars, every filter on the index).")
    L.append("")
    L.append(f"C's pick on design (exits P): {des['c_pick']['T']} + {des['c_pick']['W']}; walk-forward quarter picks: "
             + ", ".join(f"{p['T']}+{p['W']}" for p in des["c_wf_picks"]) + ".")
    L.append("")
    for x in R.EXIT_SETS:
        b = fin["base"][x]
        L += [f"## Final period, exits {EXL[x]}", "",
              "| rule | trades | net | win | avg win / loss | worst day | max DD | green days | days | design net "
              "| WF net | twin p (q) | skip p (q) | losers avoided | winners missed (big +30%) | verdict |",
              "|---" * 16 + "|"]
        bd = des["base"][x]
        L.append(row("**Donchian 20 (baseline)**", b,
                     f" {rs(bd['design']['net'])} | {rs(bd['wf_net'])} | - | - | - | - | baseline |"))
        for v in ("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8") + P.WAITS + ("C",):
            t = fin["tests"][f"{v}-{x}"]
            vb = t["vs_base"]
            ex = (f" {rs(t['design_net'])} | {rs(t['wf_net'])} | {t['p']} ({t['q']}) | {t['skip_p']} ({t['skip_q']}) "
                  f"| {vb['losers_avoided']} / {rs(vb['losers_avoided_rs'])} | {vb['winners_missed']} / "
                  f"{rs(vb['winners_missed_rs'])} ({vb['big_missed']} / {rs(vb['big_missed_rs'])}) | "
                  f"{t['verdict']}{': ' + ', '.join(t['fails']) if t['fails'] else ''} |")
            L.append(row(name(v, des), t["final"], ex))
        L.append("")
    L += ["## Cost of waiting (final, signals taken by both, exits P)", "",
          "| wait | signals | index points moved before the entry (mean / median) | option fill paid, Rs per lot "
          "(mean / median) | % (mean / median) | same strike |", "|---|---|---|---|---|---|"]
    for v in P.WAITS + ("C",):
        w = fin["tests"][f"{v}-P"].get("wait_cost")
        if w:
            L.append(f"| {name(v, des)} | {w['n']:,} | {w['points_mean']} / {w['points_median']} | "
                     f"{rs(w['rs_lot_mean'])} / {rs(w['rs_lot_median'])} | {pct(w['pct_mean'])} / "
                     f"{pct(w['pct_median'])} | {pct(w['same_strike'])} |")
    L.append("")
    L += ["## Design period (20 Nov 2024 - 31 Mar 2026)", "",
          "| rule | exits | trades | net | win | worst day | max DD | green days | WF net | skip p (design, info) |",
          "|---|---|---|---|---|---|---|---|---|---|"]
    for x in R.EXIT_SETS:
        b = des["base"][x]
        L.append(f"| Donchian 20 | {x} | {b['design']['trades']:,} | {rs(b['design']['net'])} | {pct(b['design']['win'])} "
                 f"| {rs(b['design']['worst_day'])} | {rs(b['design']['max_dd'])} | {pct(b['design']['green_days'])} "
                 f"| {rs(b['wf_net'])} | - |")
        for v in ("T1", "T2", "T3", "T4", "T5", "T6", "T7", "T8") + P.WAITS + ("C",):
            r = des["variants"][f"{v}-{x}"]
            s = r["design"]
            L.append(f"| {name(v, des)} | {x} | {s.get('trades', 0):,} | {rs(s.get('net'))} | {pct(s.get('win'))} | "
                     f"{rs(s.get('worst_day'))} | {rs(s.get('max_dd'))} | {pct(s.get('green_days'))} | "
                     f"{rs(r['wf_net'])} | {r.get('skip_p_design', '-')} |")
    L.append("")
    d = fin["decision"]
    L += ["## Decision (PREREG section 4)", "",
          f"- Recommended, exits P: {d['recommended']['P'] or 'none (the baseline stays)'}; exits Q: "
          f"{d['recommended']['Q'] or 'none (the baseline stays)'}.",
          f"- Loss-reduction trade-offs (information only): {', '.join(d['tradeoffs']) or 'none'}.", ""]
    e, ed = fin["extra_c1"], des["extra_c1"]
    L += ["## Extra (labelled, not gated): the best design variant on top of R72's C1 Breadth Donchian (exits P)", "",
          "| book | period | trades | net | win | avg win / loss | worst day | max DD | green days | days |",
          "|---|---|---|---|---|---|---|---|---|---|",
          row("C1 Breadth Donchian", ed["c1"]).replace("| C1 Breadth Donchian |", "| C1 Breadth Donchian | design |"),
          row(f"C1 + {e['variant']}", ed["c1_plus"]).replace(f"| C1 + {e['variant']} |", f"| C1 + {e['variant']} | design |"),
          row("C1 Breadth Donchian", e["c1"]).replace("| C1 Breadth Donchian |", "| C1 Breadth Donchian | final |"),
          row(f"C1 + {e['variant']}", e["c1_plus"]).replace(f"| C1 + {e['variant']} |", f"| C1 + {e['variant']} | final |"),
          "", f"Final: C1 + {e['variant']} skipped {e['vs_c1']['skipped']} C1 trades (net {rs(e['vs_c1']['skipped_net'])}); "
          f"random-skip twin p {e['skip_p']}.", ""]
    return "\n".join(L) + "\n"


def write():
    des = json.loads((R.RESULTS / "design.json").read_text())
    fin = json.loads((R.RESULTS / "final.json").read_text())
    (R.RESULTS / "report.md").write_text(render(des, fin))
