"""R106 report: results/*.json -> results/report.md (every table), plus the exact charges / break-even table.

    python research/hunt/r106_small_targets/r106report.py charges   # results/charges.json (design B0 premiums only)
    python research/hunt/r106_small_targets/r106report.py           # results/report.md
"""
from __future__ import annotations

import json
import sys
from datetime import date

import numpy as np

import r106cfg as K

PERIODS = ("design", "wf", "final", "post")


def rs(x) -> str:
    if x is None:
        return "-"
    x = int(round(x))
    return ("-" if x < 0 else "") + "Rs " + f"{abs(x):,}"


def pct(x) -> str:
    return "-" if x is None else f"{100 * x:.0f}%"


# ------------------------------------------------------------------------------------------------ charges table
def charges_table() -> dict:
    """Per index: the median 1-ITM premium the arms paid in DESIGN (B0 trades), the lot in force on 15 Oct 2026, the
    exact round-trip charges (dated, Zerodha) and slippage, the premium move each target needs, the 1:1 stop's
    distance, and the break-even win rates (1:1, and with the arms' -15% stop)."""
    import r106study as S
    S.mods()
    import engine as E
    import r51cfg as R51
    import r106engine as X
    day = date(2026, 10, 15)
    prem = {}
    for arm in K.ARMS:
        tr = S._load(f"{arm}_design")["B0"]
        for t in tr:
            prem.setdefault(t["und"], []).append((t["E"], t["rE"]))
    out = {}
    for und, v in sorted(prem.items()):
        E_ = float(np.median([a for a, _ in v]))
        slip_e = float(np.median([b - a for a, b in v]))
        qty = float(R51.lot(und, day))
        rE = E_ + slip_e
        ch = lambda a, b: float(E.charges(und, np.array([a]), np.array([b]), qty, day)[0])  # noqa: E731
        row = dict(lot=int(qty), premium=round(E_, 1), lot_value=round(E_ * qty), entry_slip=round(slip_e * qty),
                   charges_flat=round(ch(rE, rE)), targets={})
        for T in (500.0, 1000.0, 1500.0):
            x = X.target_price(rE, qty, T, ch)
            s = X.stop_price(rE, qty, T, ch, 2.0, E.SLIP[2], E.SLIP[3])
            loss15 = (rE - (E_ * 0.85 * E.SLIP[2] - 2 * 2.0 * 0.05)) * qty + ch(rE, E_ * 0.85)
            row["targets"][int(T)] = dict(move_pct=round((x / E_ - 1) * 100, 1), gross_needed=round((x - E_) * qty),
                                          stop_pct=(round((1 - s / E_) * 100, 1) if s > 0 else None),
                                          be_1to1=0.5, loss_own15=round(loss15), be_own15=round(loss15 / (T + loss15), 3))
        out[und] = row
    (K.RESULTS / "charges.json").write_text(json.dumps(out, indent=1))
    return out


# ------------------------------------------------------------------------------------------------ tables
def _get(res: dict, arm: str, name: str, period: str):
    if period == "wf":
        return res.get("design", {}).get(arm, {}).get(name, {}).get("wf")
    return res.get(period, {}).get(arm, {}).get(name, {}).get("all")


def arm_table(res: dict, dec: dict, arm: str) -> list:
    L = ["| rule | period | trades | win | break-even win | net | per session | green days | worst day | max DD | "
         "without 5 best | exits (target / stop / other) | exit-twin p | verdict |",
         "|---|---|---|---|---|---|---|---|---|---|---|---|---|---|"]
    for name in ("B0",) + K.RULE_IDS:
        for per in PERIODS:
            s = _get(res, arm, name, per)
            if not s:
                continue
            ex = s.get("exits", {})
            tg, st = ex.get("target", 0), ex.get("stop", 0)
            oth = s["trades"] - tg - st
            twin = res.get("final", {}).get(arm, {}).get(name, {}).get("twin", {}) if per == "final" else {}
            g = dec.get(f"{arm}:{name}", {}) if per == "final" else {}
            verdict = ("**PASS**" if g.get("passed") else "fail: " + "; ".join(g.get("fails", []))) if g else ""
            lab = f"**{name}**" if per == "design" else ""
            L.append(f"| {lab} | {per} | {s['trades']} | {pct(s['win'])} | {pct(s.get('be_win'))} | {rs(s['net'])} | "
                     f"{rs(s.get('per_session'))} | {pct(s.get('green_days'))} | {rs(s.get('worst_day'))} | "
                     f"{rs(-s['max_dd']) if s.get('max_dd') else '-'} | {rs(s.get('ex_top5'))} | {tg} / {st} / {oth} | "
                     f"{twin.get('p', '')} | {verdict} |")
    return L


def report() -> str:
    res = {}
    for ph in ("design", "final", "post"):
        res[ph] = {a: json.loads((K.RESULTS / f"{ph}_{a}.json").read_text()) for a in K.ARMS
                   if (K.RESULTS / f"{ph}_{a}.json").exists()}
    dp = K.RESULTS / "decision.json"
    dec = json.loads(dp.read_text()) if dp.exists() else {}
    L = ["# R106 report: fixed small rupee targets on the paper arms (every table)", "",
         "Net after dated charges and R17 slippage, 1 lot. 'wf' = the walk-forward window Apr 2025 - Mar 2026 (inside "
         "design). Break-even win = average loss / (average win + average loss) of the rule's own trades. Exit-twin p: "
         "final period, 500 random-exit twins. BH q counts R106's tests plus 513 earlier ones.", ""]
    if dec.get("_m"):
        L += [f"BH count: {dec['_m']['r106']} R106 tests + {dec['_m']['prior']} earlier = {dec['_m']['total']}.", ""]
    for arm in K.ARMS:
        if arm not in res["design"]:
            continue
        p = res["design"][arm]["parity"]
        L += [f"## {K.ARM_NAME[arm]}", "", f"B0 parity (design): {p['trades']} trades, {rs(p['net'])}, R101 published "
              f"{p['published']}: {'reproduced' if p.get('reproduced') else 'DIFFERS'}", ""]
        L += arm_table(res, dec, arm) + [""]
        if dec:
            L += ["| rule | twin p | q (R106 only) | q (with earlier) |", "|---|---|---|---|"]
            for r in K.RULE_IDS:
                g = dec.get(f"{arm}:{r}", {})
                L.append(f"| {r} | {g.get('twin_p', '-')} | {g.get('q_r106', '-')} | {g.get('q_all', '-')} |")
            L.append("")
    for ph in ("design", "final", "post"):
        pp = K.RESULTS / f"pooled_{ph}.json"
        if pp.exists():
            P = json.loads(pp.read_text())
            L += [f"## All arms together, {ph}: '5 trades x Rs 1,000' (DAY5K across arms) vs today's rules", "",
                  "| | sessions | traded | median day | mean day | days >= Rs 5,000 | green | red | worst day | best day | 10th pct | 90th pct |",
                  "|---|---|---|---|---|---|---|---|---|---|---|---|"]
            for name, key in (("DAY5K all arms", "day5k_profile"), ("today's rules (B0) all arms", "b0_profile"),
                              ("DAY5K, walk-forward window", "day5k_wf_profile"), ("B0, walk-forward window", "b0_wf_profile")):
                q = P.get(key)
                if not q:
                    continue
                L.append(f"| {name} | {q['sessions']} | {q['traded']} | {rs(q['median_day'])} | {rs(q['mean_day'])} | "
                         f"{pct(q['reach_goal'])} | {pct(q['green'])} | {pct(q['red'])} | {rs(q['worst_day'])} | "
                         f"{rs(q['best_day'])} | {rs(q['p10'])} | {rs(q['p90'])} |")
            L += ["", f"DAY5K trades: {P['day5k']['trades']}, win {pct(P['day5k']['win'])}, net {rs(P['day5k']['net'])}, "
                  f"max DD {rs(-P['day5k']['max_dd'])}; B0 trades: {P['b0']['trades']}, net {rs(P['b0']['net'])}, "
                  f"max DD {rs(-P['b0']['max_dd'])}. By arm: {P['by_arm']}.", ""]
    cp = K.RESULTS / "charges.json"
    if cp.exists():
        C = json.loads(cp.read_text())
        L += ["## Charges and break-even (typical 1-ITM premium the arms paid in design; lots and charges of 15 Oct 2026)", "",
              "| index | lot | premium | lot value | entry slippage | round-trip charges | +500: move / 1:1 stop | "
              "+1,000: move / 1:1 stop | +1,500: move / 1:1 stop | break-even win with -15% stop (500 / 1,000 / 1,500) |",
              "|---|---|---|---|---|---|---|---|---|---|"]
        for und, r in C.items():
            t = r["targets"]
            f = lambda k: f"+{t[k]['move_pct']}% / -{t[k]['stop_pct']}%" if t[k]["stop_pct"] else f"+{t[k]['move_pct']}% / none"  # noqa: E731
            L.append(f"| {und} | {r['lot']} | Rs {r['premium']} | {rs(r['lot_value'])} | {rs(r['entry_slip'])} | "
                     f"{rs(r['charges_flat'])} | {f('500')} | {f('1000')} | {f('1500')} | "
                     f"{pct(t['500']['be_own15'])} / {pct(t['1000']['be_own15'])} / {pct(t['1500']['be_own15'])} |")
        L.append("")
    text = "\n".join(L)
    (K.RESULTS / "report.md").write_text(text)
    return text


if __name__ == "__main__":
    if len(sys.argv) > 1 and sys.argv[1] == "charges":
        print(json.dumps(charges_table(), indent=1))
    else:
        print(report()[:3000])
