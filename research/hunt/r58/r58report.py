"""R58 report tables (results/report.md) from results/design.json + results/final.json and the build's meta files.

    python3 research/hunt/r58/r58study.py report
"""
from __future__ import annotations

import json

import r58cfg as K
import r58rules as RR


def rs(x: float) -> str:
    return f"{x:+,.0f}"


def load(name: str):
    p = K.RESULTS / name
    return json.loads(p.read_text()) if p.exists() else None


def main():
    des, fin = load("design.json"), load("final.json")
    cov, spr, hyg = load("coverage.json"), load("spread_fit.json"), load("hygiene.json")
    rules = RR.rules()
    md = ["# R58 results: ATM stock options on the top 10 NIFTY 50 stocks (20 Nov 2024 - 12 Oct 2026)\n",
          "Rs per 1 lot of the contract's own lot, after dated charges and the fitted spread (PREREG 4). Design 20 Nov 2024 - "
          "31 Mar 2026; final test 1 Apr - 12 Oct 2026" + (f", read once ({fin['read']})" if fin else " (not read yet)") +
          ". p = random-entry twins (500); q = Benjamini-Hochberg over all 38 final p-values. Gates: final net > 0, "
          "p < 0.05, q <= 0.10, 40+ trades, top-5 < 100% of net, design gate (design net > 0 and >= 3 of 5 quarters "
          "positive). Signal source of every rule: the stock (NIFTY for relative strength).\n"]
    if fin:
        passes = [r.id for r in rules if fin["results"][r.id]["x1"]["verdict"].startswith("PASS")]
        md.append(f"**Passes: {len(passes)} of 38.** " + (", ".join(f"`{p}`" for p in passes) or "none") + "\n")

    md.append("## Universe, coverage and data hygiene\n")
    md.append("| stock | design ATM / ±1 coverage | final ATM / ±1 | stray rows dropped | spike rows | lots from volume / bhav "
              "(contract-days) | ex-dates | bad days | fitted half-spread liquid / thin / none |\n|" + "---|" * 9)
    for s in K.UNIVERSE:
        c, h, f = cov[s], hyg[s], spr[s]
        md.append(f"| {s} | {c['design']['atm']:.1%} / {c['design']['pm1']:.1%} | {c['final']['atm']:.1%} / "
                  f"{c['final']['pm1']:.1%} | {h['stray_rows']:,} | {h['spike_rows']:,} | "
                  f"{h['lot_source_contract_days']['volume']:,} / {h['lot_source_contract_days']['bhav']:,} | "
                  f"{', '.join(h['exdates']) or '-'} | {', '.join(h['bad_days']) or '-'} | "
                  f"{f['liquid']['half_used']:.2%} / {f['thin']['half_used']:.2%} / {f['none']['half_used']:.2%} |")

    md.append("\n## Every rule: design and final test (1x costs)\n")
    md.append("| rule | design trades | design net | design net/trade | quarters + | design gate | final trades | win | "
              "net | net/trade | avg win / loss | worst day | max DD | months green | premium x lot (median) | p | q | "
              "verdict |\n|" + "---|" * 18)
    for r in rules:
        d = des["results"][r.id]
        dx = d["x1"]
        qp = sum(dx["quarters"].get(q, [0, 0])[0] > 0 for q in K.QUARTERS)
        row = (f"| `{r.id}` | {dx['trades']:,} | {rs(dx['net'])} | {rs(dx['per_trade'])} | {qp}/5 | "
               f"{'yes' if d['design_gate'] else 'no'} |")
        if fin:
            f = fin["results"][r.id]["x1"]
            row += (f" {f['trades']:,} | {f['win_rate']:.0%} | {rs(f['net'])} | {rs(f['per_trade'])} | "
                    f"{rs(f['avg_win'])} / {rs(f['avg_loss'])} | {rs(f['worst_day'])} | {rs(f['max_dd'])} | "
                    f"{f['months_green']} | {f['capital_median']:,.0f} | {f['twin_p']:.3f} | {f['q']:.2f} | "
                    f"{f['verdict']} |")
        else:
            row += " - |" * 12
        md.append(row)

    md.append("\n## Costs, robustness and the stock instead of the option\n")
    md.append("Cash = the same entry and exit minutes in the stock, lot-many shares (MIS for intraday rules, delivery "
              "long-only for swing rules; shorts held overnight are not possible in cash). Intrinsic = stale / model "
              "exits re-priced at intrinsic value.\n")
    md.append("| rule | design 2x | design cash 1x / 2x | final 2x | final cash 1x / 2x (trades) | cash capital (median) | "
              "final intrinsic | final stale / model exits | final without top 5 | final without best stock |\n|" + "---|" * 10)
    for r in rules:
        d = des["results"][r.id]
        row = (f"| `{r.id}` | {rs(d['x2']['net'])} | {rs(d['cash_x1']['net'])} / {rs(d['cash_x2']['net'])} |")
        if fin:
            f = fin["results"][r.id]
            row += (f" {rs(f['x2']['net'])} | {rs(f['cash_x1']['net'])} / {rs(f['cash_x2']['net'])} "
                    f"({f['cash_x1']['trades']}) | {f['cash_x1']['capital_median']:,.0f} | {rs(f['intrinsic_net_x1'])} | "
                    f"{f['x1']['src']['stale']} / {f['x1']['src']['model']} | {rs(f['x1']['without_top5'])} | "
                    f"{rs(f['x1']['without_best_stock'])} ({f['x1']['best_stock']}) |")
        else:
            row += " - |" * 7
        md.append(row)

    md.append("\n## Per stock (trades / net Rs, 1x)\n")
    md.append("| rule | period | " + " | ".join(K.UNIVERSE) + " |\n|---|---|" + "---|" * len(K.UNIVERSE))
    for r in rules:
        for part, src in (("design", des), ("final", fin)):
            if not src:
                continue
            st = src["results"][r.id]["x1"]["stocks"]
            md.append(f"| `{r.id}` | {part} | " + " | ".join(
                f"{st[s][0]} / {st[s][1]:+,}" if s in st else "-" for s in K.UNIVERSE) + " |")

    md.append("\n## Context runs (never gated)\n")
    md.append("No cut-off = entries also on T-2..T and holds to their own exit (15:05 on expiry day). No band = swing "
              "rules held beyond the ±1 band, priced by Black-Scholes with the contract's last IV where no print exists.\n")
    md.append("| rule | design: primary / no cut-off / no band | final: primary / no cut-off / no band |\n|---|---|---|")
    for r in rules:
        cells = []
        for src in (des, fin):
            if not src:
                cells.append("-")
                continue
            x = src["results"][r.id]
            nb = x.get("ctx_no_band")
            cells.append(f"{rs(x['x1']['net'])} ({x['x1']['trades']}) / {rs(x['ctx_no_cutoff']['net'])} "
                         f"({x['ctx_no_cutoff']['trades']}) / " + (f"{rs(nb['net'])} ({nb['trades']}, model "
                                                                    f"{nb['src']['model']})" if nb else "-"))
        md.append(f"| `{r.id}` | {cells[0]} | {cells[1]} |")

    md.append("\n## Exit reasons (final, 1x)\n")
    if fin:
        md.append("| rule | " + " | ".join(("pstop", "ustop", "utgt", "ptgt", "vwap", "time", "band", "sq")) +
                  " | CE / PE | sessions held (mean) |\n|---|" + "---|" * 10)
        for r in rules:
            f = fin["results"][r.id]["x1"]
            md.append(f"| `{r.id}` | " + " | ".join(str(f["why"][w]) for w in
                                                     ("pstop", "ustop", "utgt", "ptgt", "vwap", "time", "band", "sq")) +
                      f" | {f['sides']['CE']} / {f['sides']['PE']} | {f['held_sessions']:.2f} |")

    md.append("\n## Family walk-forward on design (context)\n")
    md.append("| family | quarter picks (variant, net) | out-of-sample net |\n|---|---|---|")
    for fam, x in des["family_walk_forward"].items():
        md.append(f"| {fam} | " + "; ".join(f"{q}: `{p[0]}` {p[1]:+,}" for q, p in x["picks"].items()) +
                  f" | {x['net']:+,} |")
    (K.RESULTS / "report.md").write_text("\n".join(md) + "\n")
    print("\n".join(md[:4]))


if __name__ == "__main__":
    main()
