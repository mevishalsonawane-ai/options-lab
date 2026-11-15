"""R177-EMA report: results/report.md (full tables) and results/summary.md (short, for Boss). Called by r177study."""
from __future__ import annotations

import r177cfg as K

MAIN = ("PLAIN", "ADX", "TREND", "ATR", "ALL")
VARIANTS = ("ALL", "ALL-H1B", "ALL-TWAP", "ALL-OR", "ALL-2R", "ALL-ITM")
IDX_NAME = {"NIFTY": "NIFTY", "BANKNIFTY": "BANKNIFTY", "both": "both indices"}


def rs(x: float) -> str:
    s = "-" if x < 0 else ""
    x = abs(x)
    if x >= 100000:
        return f"{s}Rs {x / 100000:.2f} L"
    return f"{s}Rs {x:,.0f}"


def row(s: dict) -> str:
    return (f"{s['trades']} | {s['win']:.0f}% | **{rs(s['net'])}** | {rs(s['avg_win'])} / {rs(s['avg_loss'])} | "
            f"{rs(s['worst_day'])} | {rs(s['max_dd'])} | {rs(s['worst_month'])}")


HEAD = "trades | win % | net | avg win / loss | worst day | max drawdown | worst month"
SEP = "---|---|---|---|---|---|---"


def short(s: dict) -> str:
    return f"{rs(s['net'])} ({s['trades']}, {s['win']:.0f}%)"


def write(cells: dict, nofill: dict, trades: dict) -> None:
    C = cells
    passed = [k for k, c in C.items() if not c["fails"]]
    L = []
    a = L.append
    a("# R177-EMA: the 9/21 EMA crossover with the PDF's three regime filters (report)")
    a("")
    a("Pre-registration: `PREREG.md` (committed before any number). Decision: `decision.json`. Signal source for every "
      "rule: **the index spot** (15-minute and 1-hour bars of NIFTY 50 / NIFTY BANK); the ATM (or 1-ITM) option of the "
      "nearest expiry is bought. VWAP weights: the stored option chain's traded volume (the index and the BANKNIFTY "
      "futures in the reachable data have no volume). All money is after R17 slippage and dated Zerodha charges; index "
      "stops are sold as stop orders (x0.999 minus twice the half-spread).")
    a("")
    a("## Outcome")
    a("")
    if passed:
        a(f"{len(passed)} of {len(C)} tests passed every gate: " + ", ".join("/".join(map(str, k)) for k in passed))
    else:
        a(f"**No test passed the gates: 0 of {len(C)}** (10 rule sets x 3 index scopes x 3 side scopes x 1-3 lots). "
          "The full rule with all three filters is **rejected**.")
    a("")
    a("## Data and its limits")
    a("")
    a("- The repo's `research-data` release (R171's loader): NIFTY and BANKNIFTY index minutes and real 1-minute option "
      "prices (one expiry a day, 20 strikes). The Drive lake is not reachable from this session, so **nothing after 23 Feb "
      "2026 is used** (R171's data-hole rule), and FINNIFTY / SENSEX / MIDCPNIFTY (no options here) are not traded.")
    a(f"- Periods: {K.PERIOD_NAME['full']}; {K.PERIOD_NAME['design']}; {K.PERIOD_NAME['final']}, read once.")
    a("- The index's own expiry days are skipped. Lots: the lot in force on the trade date (NIFTY 25 -> 75 from 28 Nov "
      "2024 -> 65 from 31 Dec 2025; BANKNIFTY 15 -> 30 -> 35 -> 30).")
    nf = ", ".join(f"{r} {sum(v.values())}" for r, v in nofill.items())
    a(f"- Signals with no fill (strike not stored or no trade in 3 minutes), full period: {nf}.")
    a("")
    a("## 1. Does stacking help? Both indices, calls + puts, 1 lot")
    a("")
    for p in ("full", "final"):
        a(f"**{K.PERIOD_NAME[p]}**")
        a("")
        a("| rule | " + HEAD + " |")
        a("|---|" + SEP + "|")
        for r in K.RULES:
            a(f"| {K.RULE_NAME[r]} | " + row(C[(r, 'both', 'both', 1)]["per"][p]) + " |")
        a("")
    a("## 2. Net per index and lot (calls + puts)")
    a("")
    for r in MAIN + VARIANTS[1:]:
        a(f"### {K.RULE_NAME[r]}")
        a("")
        for p in ("full", "final"):
            a(f"*{K.PERIOD_NAME[p]}*")
            a("")
            a("| index | lots | " + HEAD + " |")
            a("|---|---|" + SEP + "|")
            for u in K.IDX_SCOPES:
                for n in K.LOTS:
                    a(f"| {IDX_NAME[u]} | {n} | " + row(C[(r, u, 'both', n)]["per"][p]) + " |")
            a("")
    a("## 3. Calls and puts as separate rows (1 lot)")
    a("")
    a("| rule | index | side | full: net (trades, win %) | last 6 months: net (trades, win %) |")
    a("|---|---|---|---|---|")
    for r in K.RULES:
        for u in K.IDX_SCOPES:
            for s in ("C", "P"):
                c = C[(r, u, s, 1)]["per"]
                a(f"| {r} | {IDX_NAME[u]} | {K.SIDE_NAME[s]} | {short(c['full'])} | {short(c['final'])} |")
    a("")
    a("## 4. Design walk-forward folds (both indices, calls + puts, 1 lot)")
    a("")
    a("Nothing is fitted (every setting is the PDF's), so the walk-forward net is the design net; the four folds are "
      "shown so a single good stretch is visible.")
    a("")
    a("| rule | 6 May - Aug 2024 | Sep - Dec 2024 | Jan - Apr 2025 | May - 22 Aug 2025 | design |")
    a("|---|---|---|---|---|---|")
    for r in K.RULES:
        c = C[(r, "both", "both", 1)]
        a(f"| {r} | " + " | ".join(rs(x) for x in c["folds"]) + f" | {short(c['per']['design'])} |")
    a("")
    a("## 5. Exit mix (full period, both indices, 1 lot)")
    a("")
    a("| rule | opposite cross | index stop | 2R target | 15:10 square-off |")
    a("|---|---|---|---|---|")
    for r in K.RULES:
        e = C[(r, "both", "both", 1)]["per"]["full"]["exits"]
        a(f"| {r} | {e.get('cross', 0)} | {e.get('stop', 0)} | {e.get('target', 0)} | {e.get('squareoff', 0)} |")
    a("")
    a("## 6. Last 6 months, month by month (both indices, calls + puts, 1 lot)")
    a("")
    months = sorted({m for r in K.RULES for m in C[(r, "both", "both", 1)]["per"]["final"]["months"]})
    a("| rule | " + " | ".join(months) + " |")
    a("|---|" + "---|" * len(months))
    for r in K.RULES:
        mm = C[(r, "both", "both", 1)]["per"]["final"]["months"]
        a(f"| {r} | " + " | ".join(rs(mm.get(m, 0)) for m in months) + " |")
    a("")
    a("## 7. Gates (PREREG section 7)")
    a("")
    a(f"Final net > 0; twin p < 0.05 (2,000 random-entry twins, seed 177); BH q <= 0.10 with m = {K.M_TOTAL:,} "
      f"({K.N_PRIOR:,} earlier entries + {len(K.TESTS)} here); 40+ final trades; final net without the top 5 > 0; design "
      "net > 0; max drawdown <= Rs 40,000 and worst month >= -Rs 13,000 in design and final. Below: both indices, calls + "
      "puts; every one of the 270 cells is in `decision.json`.")
    a("")
    a("| rule | lots | final net | twin mean | p (twins) | p (normal) | q (m=4,950) | q (R177 only) | failed gates |")
    a("|---|---|---|---|---|---|---|---|---|")
    for r in K.RULES:
        for n in K.LOTS:
            c = C[(r, "both", "both", n)]
            a(f"| {r} | {n} | {rs(c['per']['final']['net'])} | {rs(c['twin_mean'])} | {c['p_emp']:.4f} | "
              f"{c['p_norm']:.3g} | {c['q_all']:.2f} | {c['q_own']:.2f} | {'; '.join(c['fails']) or 'none'} |")
    a("")
    npos = sum(1 for c in C.values() if c["per"]["final"]["net"] > 0)
    a(f"Cells with a positive final net: {npos} of {len(C)}; lowest q (m = 4,950): "
      f"{min(c['q_all'] for c in C.values()):.2f}; lowest q within R177-EMA alone: "
      f"{min(c['q_own'] for c in C.values()):.2f}.")
    a("")
    a("## 8. Earlier results this agrees or disagrees with")
    a("")
    a("- R44 / R51: EMA 8/21 + ADX(14) > 15 + profit lock (5-minute bars): -Rs 50,947 on 3,129 design trades, "
      "-Rs 32,749 on 1,262 final trades.")
    a("- R104 book-porwal-08 (ADX + EMA + ATR): -Rs 1.23 L on 1,115 trades.")
    a("- H46: VWAP as a filter failed (2,400 variants). R17 / R20: ATR stops did not help. ADX: rejected in 77 of 91 tests.")
    a("")
    a("## 9. Caveats")
    a("")
    a("- About 21 months of data, two indices; the final (last 6 months) is short, and the filtered rules trade rarely, "
      "so several cells have too few trades to judge on their own.")
    a("- The VWAP is weighted by option-chain volume, not futures volume (none is available here); the TWAP variant "
      "shows how much the weighting matters.")
    a("- Crossovers are computed on the continuous 15-minute series (R44's convention), so a cross can come from the "
      "overnight gap and fire on the 09:15 bar (entry 09:30).")
    a("- Twins do not stop overlapping random entries (R44's design). Apr - Oct 2026 could not be read here.")
    a("- The twins enter on the same days as the rule, and a crossover day is a day that moved, so random entries on those "
      "days do well (the plain crossover's twins average about +Rs 1 L over the last 6 months at 1 lot, while random "
      "entries on every final day lost about Rs 100-175 a trade). This makes the twin bar harder to clear for the "
      "unfiltered rules. It does not change the verdict: the 29 cells in profit in both design and final had 1-3 final "
      "trades (the ALL family's BANKNIFTY puts) or under 40 (ADX on BANKNIFTY, 26), or lost once their best 5 trades "
      "are removed (the plain and ATR-stop NIFTY calls, 45 final trades), and the headline cells lose on their own.")
    a("- Lots: Zerodha's flat Rs 20 an order is the same at any size, so 2 and 3 lots lose less per lot than 1 lot.")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    write_summary(C, passed)


def write_summary(C: dict, passed: list) -> None:
    L = []
    a = L.append
    a("# R177-EMA for Boss: 9/21 EMA crossover + all three filters")
    a("")
    allb = C[("ALL", "both", "both", 1)]["per"]
    plb = C[("PLAIN", "both", "both", 1)]["per"]
    if passed:
        a(f"**{len(passed)} of {len(C)} tests passed.** See report.md.")
    else:
        a("**It does not work. No version passed the gates, including all three filters together.**")
    a("")
    a(f"All three together (1 lot, NIFTY + BANKNIFTY, calls + puts): {short(allb['full'])} over the full period and "
      f"{short(allb['final'])} in the last 6 months. The plain crossover: {short(plb['full'])} and {short(plb['final'])}.")
    a("")
    a("Net after real charges and slippage, 1 lot, both indices, calls + puts (trades, win %):")
    a("")
    a("| rule | full period (May 2024 - Feb 2026) | last 6 months (Aug 2025 - Feb 2026) | max drawdown (full) | worst month (full) |")
    a("|---|---|---|---|---|")
    for r in K.RULES:
        c = C[(r, "both", "both", 1)]["per"]
        a(f"| {K.RULE_NAME[r]} | {short(c['full'])} | {short(c['final'])} | {rs(c['full']['max_dd'])} | "
          f"{rs(c['full']['worst_month'])} |")
    a("")
    adx = C[("ADX", "both", "both", 1)]["per"]
    pf, af = plb["full"], allb["full"]
    lowp = min(c["p_emp"] for c in C.values())
    a("In plain words:")
    a("")
    a(f"- **Stacking the filters cut the trades, not the losing.** Trades fell from {pf['trades']} to {af['trades']} and the "
      f"total loss from {rs(pf['net'])} to {rs(af['net'])}, but the loss per trade got worse "
      f"({rs(pf['net'] / max(pf['trades'], 1))} -> {rs(af['net'] / max(af['trades'], 1))}) and the win rate fell "
      f"({pf['win']:.0f}% -> {af['win']:.0f}%). In the last 6 months all three together gave only "
      f"{allb['final']['trades']} trades ({allb['final']['win']:.0f}% won).")
    a(f"- The only version in profit over the whole period was **ADX alone**: {short(adx['full'])}, all of it in the design "
      f"months; in the last 6 months its net was {rs(adx['final']['net'])} on {adx['final']['trades']} trades.")
    a("- The PDF's other choices (1-hour trend as EMA 9 > 21, a plain TWAP, clearing the opening range, a 2R target, a "
      "1-ITM strike) did not change the picture: every one lost over the full period.")
    a(f"- **No version beat random entries** on the same days: the lowest twin p of all {len(C)} tests is {lowp:.2f} (the "
      "bar is 0.05), and the multiple-testing q is 1.00 everywhere.")
    a("- This matches every earlier test here: EMA 8/21 + ADX (R44 / R51: -Rs 50,947 on 3,129 design trades, -Rs 32,749 "
      "on 1,262 final), ADX + EMA + ATR (book-porwal-08: -Rs 1.23 L on 1,115 trades), VWAP filters (H46, 2,400 variants), "
      "ATR stops (R17 / R20) and ADX (rejected in 77 of 91 tests).")
    a("- **Nothing in the app changes.** It should not be added as an arm.")
    a("")
    a("All three together, per index and lots (net (trades, win %)):")
    a("")
    a("| index | lots | full period | last 6 months | worst day (full) | max drawdown (full) |")
    a("|---|---|---|---|---|---|")
    for u in K.IDX_SCOPES:
        for n in K.LOTS:
            c = C[("ALL", u, "both", n)]["per"]
            a(f"| {IDX_NAME[u]} | {n} | {short(c['full'])} | {short(c['final'])} | {rs(c['full']['worst_day'])} | "
              f"{rs(c['full']['max_dd'])} |")
    a("")
    a("Calls vs puts, all three together, 1 lot, both indices: calls "
      f"{short(C[('ALL', 'both', 'C', 1)]['per']['full'])}, puts {short(C[('ALL', 'both', 'P', 1)]['per']['full'])} "
      "(full period).")
    a("")
    a("**How it was tested.** Signals on the index (15-minute bars; the 1-hour trend from completed 1-hour bars); "
      "the ATM option bought at the next minute; out on the opposite crossover, the 2 x ATR index stop or 15:10. Real "
      "option prices, R17 slippage, dated Zerodha charges, stops sold as stop orders. Written down before any number "
      "(PREREG.md); the last 6 months were read once; 2,000 random-entry twins for the p-value; Benjamini-Hochberg over "
      f"{K.M_TOTAL:,} tests. Data: NIFTY and BANKNIFTY only, May 2024 - 23 Feb 2026 (the newer data is on the Drive lake, "
      "not reachable here). VWAP uses option-chain volume as its weight (the index has no volume).")
    a("")
    a("Full tables (every index, side and lot, month by month, gates): `report.md`.")
    (K.RESULTS / "summary.md").write_text("\n".join(L) + "\n")
