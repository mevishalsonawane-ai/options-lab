"""R188 report: results/report.md (full tables) from results/*.json. Plain-English notes, 17 Nov 2026.

    python research/hunt/r188_s5_cisd/r188report.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import r188cfg as C  # noqa: E402

NAMES = {
    "base": "base (the app today)", "BE10": "BE10 breakeven at +10%", "L500": "L500 step lock",
    "LBE1000": "LBE1000 breakeven at +Rs 1,000", "IDX": "IDX index lock", "LK500": "LK500 60% lock from +Rs 500",
    "ST1500": "ST1500 stop -Rs 1,500", "FT3PO": "FT3PO price and OI fell skip", "VIX313": "VIX313 VIX down >3.13% skip",
    "EXP": "EXP expiry-day skip", "ADX20": "ADX20 sideways skip", "NTHR": "NTHR network threshold", "COMBO": "COMBO",
}
PERIODS = (("design", "Design (Jan 2023 - 15 Mar 2026)"), ("final", "Final (1 Apr - 19 Oct 2026, base seen before)"),
           ("new", "New month (21 Oct - 13 Nov 2026, descriptive)"))


def rs(x: float) -> str:
    s = f"{abs(x):,.0f}"
    return f"-Rs {s}" if x < -0.5 else f"Rs {s}"


def row(name: str, s: dict) -> str:
    if not s.get("n"):
        return f"| {name} | 0 | - | - | - | - | - | - | - |"
    acc = "ok" if s["account_ok"] else "BREACHED"
    return (f"| {name} | {s['n']} | {s['win']:.1%} | {rs(s['total'])} | {rs(s['avg_win'])} | {rs(s['avg_loss'])} | "
            f"{rs(s['worst_month'])} ({s['worst_month_name']}) | {rs(s['max_dd'])} | {rs(s['total_x2'])} / {acc} |")


HEAD = ("| rule | trades | win rate | net | avg win | avg loss | worst month | max drawdown | net at 2x slippage / "
        "account stop |\n|---|---|---|---|---|---|---|---|---|")


def arm_tables(arm: str, F: dict) -> list:
    out = []
    names = ["base"] + list(C.LEVERS[arm]) + (["COMBO"] if "design" in F[arm].get("COMBO", {}) else [])
    for p, title in PERIODS:
        out += [f"### {arm}: {title}", "", HEAD]
        for n in names:
            label = NAMES[n]
            if n == "NTHR":
                label += f" (T = {F['_design'][arm]['NTHR_chosen']:+.0f})"
            if n == "COMBO":
                label += " (" + " + ".join(F["_design"][arm]["COMBO_parts"]) + ")"
            out.append(row(label, F[arm][n][p]))
        out.append("")
    return out


def verdicts(F: dict) -> list:
    out = [f"## Gate verdicts (BH m = {F['_m']['total']:,}: {F['_m']['prior']:,} earlier + {F['_m']['tests']} here)", "",
           "| arm | lever | design: net / win / risk | final: net / win / risk | final trades >= 30 | twin p | BH q | "
           "gates 1-3 | passed |", "|---|---|---|---|---|---|---|---|---|"]
    yn = lambda b: "yes" if b else "no"  # noqa: E731
    for arm in ("S5", "CISD"):
        for lv in list(C.LEVERS[arm]) + ["COMBO"]:
            r = F[arm].get(lv, {})
            if r.get("not_formed") or "gates" not in r:
                out.append(f"| {arm} | {lv} | not formed (fewer than 2 levers passed the design gates) | | | | "
                           f"{r.get('q', 1.0):.2f} | no | no |")
                continue
            g = r["gates"]
            dg = " / ".join(yn(g["design"][k]) for k in ("net", "win", "risk"))
            fg = " / ".join(yn(g["final"][k]) for k in ("net", "win", "risk"))
            out.append(f"| {arm} | {lv} | {dg} | {fg} | {yn(g['final_n'])} ({r['final']['n']}) | {r['twin_p']:.3f} | "
                       f"{r['q']:.2f} | {yn(r['passes_1_3'])} | {yn(r['passes'])} |")
    return out + [""]


def parity_lines(P: dict, N: dict) -> list:
    s5, cd = P["s5_r128"], P["cisd_r133"]
    return [
        "## Parity (before any lever)", "",
        "| check | R188 | published / saved | match |", "|---|---|---|---|",
        f"| R123 S5 trade list rebuilt from the saved predictions | {P['s5_r123_list']['rebuilt']} | "
        f"{P['s5_r123_list']['saved']} | {'identical' if P['s5_r123_list']['same'] else 'DIFFERENT'} |",
        f"| S5 fillable picks (used for the new month) vs R123's candidates | {P['s5_picks']['rebuilt']} | "
        f"{P['s5_picks']['r123']} | {'identical' if P['s5_picks']['same'] else 'DIFFERENT'} |",
        f"| R128 T2-S5x1 walk-forward | {s5['wf']['n']} trades, {s5['wf']['win']:.1%}, {rs(s5['wf']['avg_net'])} a "
        f"trade, worst month {rs(s5['wf']['worst_month'])} ({s5['wf']['worst_month_name']}) | 555, 59%, +Rs 240, "
        "-Rs 18.6k | exact |",
        f"| R128 T2-S5x1 holdout | {s5['holdout']['n']} trades, {s5['holdout']['win']:.1%}, "
        f"{rs(s5['holdout']['avg_net'])} | 41, 83%, +Rs 561 | exact |",
        f"| R128 per-trade nets | {s5['matched']} of {s5['r128']} matched, max difference Rs {s5['max_diff']:.2f} | "
        "R128 t2.parquet | exact |",
        f"| R133 S3-V2-E3 design | {cd['design']['n']} kept of {cd['design']['all_setups']}, {cd['design']['win']:.1%}, "
        f"{rs(cd['design']['avg_net'])} a trade | 413 of 3,291, 69%, +Rs 261 | exact |",
        f"| R133 S3-V2-E3 holdout (seen) | {cd['final']['n']} kept, {cd['final']['win']:.1%}, "
        f"{rs(cd['final']['avg_net'])} | 35, +Rs 77 | exact |",
        f"| R133 per-trade nets (all {cd['r133_trades']:,} simulated setups) | {cd['matched']:,} matched, max difference "
        f"Rs {cd['max_diff']:.2f} | R133 trades_*.parquet | exact |",
        f"| R123 N1 rebuilt (R134 nnlive) on 1 - 19 Oct 2026 | {N['matched']:,} rows, max EV difference "
        f"{N['max_ev_diff']:.1e}, sign agreement {N['ev_sign_agree']:.0%} | R123's saved holdout predictions | exact |",
        "",
    ]


def main():
    F = json.loads((C.RESULTS / "final.json").read_text())
    P = json.loads((C.RESULTS / "parity.json").read_text())
    N = json.loads((C.RESULTS / "nn_check.json").read_text())
    Dd = F["_design"]
    lines = [
        "# R188: can S5 Trail and CISD + network make more money and win more often? - full tables", "",
        "17 Nov 2026. Research only: nothing in the app changes. PREREG.md (pushed before any number). 1 lot at R123's "
        "lots, dated Zerodha charges, liquidity-aware fills (Fills('liq')), 2x slippage shown. Signal source: the index "
        "(S5: R123's network on the index, India VIX, breadth and option-chain context; CISD: the index's 5-minute "
        "bars); stops and locks on the option premium, IDX and CISD's stop on the index; FT3PO reads the bought "
        "option's price and OI as context.", "",
        "Base = each arm as the app runs it today: R128 / R133's rules with the app's 15:15 square-off (S5: 15:05 on "
        "expiry day), one trade at a time per index (S5's index busy until its own exit; CISD's refused setups still "
        "hold the index). Every lever is on top of the base; whichever exit comes first wins.", "",
    ]
    lines += parity_lines(P, N)
    lines += verdicts(F)
    lines += ["**Gate 4 cannot be met (said in PREREG.md):** with 500 twins the smallest p is 1/501, so q >= "
              f"{(1 / 501) * C.M_TOTAL / len(C.TESTS):.2f} for every test. Gates 1-3 are what can show a lead.", ""]
    lines += ["## Design choices (design window only)", "",
              f"- Levers passing every design gate: S5 {', '.join(Dd['S5']['design_passing']) or 'none'}; CISD "
              f"{', '.join(Dd['CISD']['design_passing']) or 'none'}.",
              f"- COMBO: S5 {'not formed (one lever)' if not Dd['S5']['COMBO_parts'] else ' + '.join(Dd['S5']['COMBO_parts'])}; "
              f"CISD {' + '.join(Dd['CISD']['COMBO_parts']) or 'not formed'}.",
              f"- NTHR (CISD): T = {Dd['CISD']['NTHR_chosen']:+.0f} (the highest design net among thresholds whose "
              "design win rate beat the base's). The grid on the design window:", "",
              "| T | trades | win rate | net |", "|---|---|---|---|"]
    lines += [f"| {float(t):+.0f} | {g['n']} | {g['win']:.1%} | {rs(g['total'])} |" for t, g in Dd["CISD"]["NTHR_grid"].items()]
    lines += [f"| 0 (base) | {Dd['CISD']['base']['n']} | {Dd['CISD']['base']['win']:.1%} | {rs(Dd['CISD']['base']['total'])} |", ""]
    lines += ["## Tables per arm", ""]
    for arm in ("S5", "CISD"):
        lines += arm_tables(arm, F)
    lines += ["## The new month's base trades (21 Oct - 13 Nov 2026)", "",
              "| arm | index | day | entry minute | side | net | exit |", "|---|---|---|---|---|---|---|"]
    for arm in ("S5", "CISD"):
        for t in F[arm]["new_month_base_trades"]:
            hh, mm = divmod(9 * 60 + 15 + int(t["entry"]), 60)
            lines.append(f"| {arm} | {t['und']} | {t['day']} | {hh:02d}:{mm:02d} | {'call' if t['side'] > 0 else 'put'} "
                         f"| {rs(t['net'])} | {t['why']} |")
    lines += ["", "Not priced in the new month: a CISD setup on a weekly expiry day needs the monthly contract, and the "
              "16 Nov gap files have no monthly series.", ""]
    lines += ["## Exit reasons (design)", "", "| arm | rule | exits |", "|---|---|---|"]
    for arm in ("S5", "CISD"):
        for n in ["base"] + list(C.LEVERS[arm]):
            ex = F[arm][n]["design"].get("exits", {})
            lines.append(f"| {arm} | {n} | " + ", ".join(f"{k} {v}" for k, v in sorted(ex.items(), key=lambda z: -z[1])) + " |")
    lines += ["", "Note (17 Nov): the first final pass compared drawdowns as raw floats, so CISD EXP's final drawdown, "
              "equal to the base's to the paisa, counted as smaller; gates were re-derived from the stored numbers "
              "comparing to the paisa (`r188study.py regate`; no data re-read)."]
    (C.RESULTS / "report.md").write_text("\n".join(lines) + "\n")
    print("report.md written", len(lines), "lines")


if __name__ == "__main__":
    main()
