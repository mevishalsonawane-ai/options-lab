"""R180 report: results/results.json (+ diag.json) -> results/report.md. Tables only; the plain-English summary at the
top is written from the same numbers (filled in from the JSON, never typed by hand)."""
from __future__ import annotations

import json

import r180cfg as K

NAMES = {"B1": "Book 1 R72-X1 Breakout", "B2": "Book 2 S3-V1-E3 Order-Block Pullback",
         "COMB": "Combined 1-position, -Rs 11k month pause", "COMB_NOSTOP": "Combined 1-position, no month pause",
         "B1_open": "Book 1, repo fill (next-minute open)", "B2_open": "Book 2, repo fill",
         "COMB_open": "Combined (pause), repo fill"}
GATE_TXT = dict(design_pos="design > 0", hold_pos="holdout > 0", slip2_pos="2x slippage > 0",
                acct_dd="max DD better than -Rs 40k", acct_month="no month worse than -Rs 13k",
                twin_p="twin p < 0.05", bh_q="BH q <= 0.10", hold_n=">= 40 holdout trades")


def rs(x):
    if x is None:
        return "-"
    x = round(x)
    s = f"{abs(x):,}"
    return ("-Rs " if x < 0 else "Rs ") + s


def ln(s):
    return f"{s['trades']} / {s['win'] if s['win'] is not None else '-'}% / {rs(s['net'])}"


def claim_ln(c):
    n, w, net = c
    return f"{n} / {w}% / {rs(net)}"


def write():
    r = json.loads((K.RESULTS / "results.json").read_text())
    dg = json.loads((K.RESULTS / "diag.json").read_text()) if (K.RESULTS / "diag.json").exists() else None
    R = r["runs"]
    L = []
    a = lambda s="": L.append(s)  # noqa: E731
    b1, b2, cb = R["B1"]["split"], R["B2"]["split"], R["COMB"]["split"]
    a("# R180: does the outside \"dual-book\" build manual reproduce on our real option prices?")
    a()
    a("15 Nov 2026. Research only: nothing in the app changes. Pre-registration: `../PREREG.md` (committed before any "
      "number). Signal source: the index (5-minute bars of the index spot) for both books; one option-chain filter "
      "(ATM straddle vs 09:20); Book 1's exits on the option premium, Book 2's stop on the index and lock on option P&L.")
    a()
    a("## For Boss, in short")
    a()
    a(f"- **The manual's numbers do not reproduce.** Built exactly as it is written, on our real 1-minute option "
      f"prices with real dated charges and slippage (1 lot, 4 Nov 2024 - 19 Oct 2026):")
    a(f"  - **Book 1** (R72-X1 Breakout): manual {claim_ln(K.CLAIMS['B1']['all'])}; **ours {ln(b1['all'])}** "
      f"(design {ln(b1['design'])}, holdout {ln(b1['hold'])}).")
    a(f"  - **Book 2** (Order-Block Pullback): manual {claim_ln(K.CLAIMS['B2']['all'])}; **ours {ln(b2['all'])}** "
      f"(design {ln(b2['design'])}, holdout {ln(b2['hold'])}).")
    a(f"  - **Combined** one-position account with the -Rs 11k month pause: manual "
      f"{claim_ln(K.CLAIMS['COMB']['all'])}; **ours {ln(cb['all'])}**, max drawdown {rs(cb['all']['max_dd'])}.")
    a(f"- **No win rate near the manual's.** Book 1 wins {b1['all']['win']}% (manual 67.9%), Book 2 "
      f"{b2['all']['win']}% (manual 59.8%), combined {cb['all']['win']}% (manual 63.9%).")
    a(f"- **Book 1 makes a little money, but not because of its timing**; Book 2 loses steadily. "
      f"**Random entries do about as well (Book 2) or better (Book 1).** Re-pricing every trade with a random "
      f"entry minute (same day, index, side and exits, 500 times): Book 1 p {R['B1']['twin_p']:.2f} (random average {rs(R['B1']['twin_mean'])}), "
      f"Book 2 p {R['B2']['twin_p']:.2f} ({rs(R['B2']['twin_mean'])}), combined p {R['COMB']['twin_p']:.2f}. The "
      f"entry rules add nothing over random timing.")
    if dg:
        d1 = dg["B1_nolock"]
        a(f"- **Where the manual's Book 1 trade count probably comes from.** Its \"initial breakout\" rule uses an "
          f"undefined number. Read our way (pre-registered), Book 1 takes only {b1['all']['trades']} trades. Dropping "
          f"that rule (a post-hoc check, not a test) gives {ln(d1['all'])}: close to the manual's 361 trades (design "
          f"{d1['design']['trades']} vs 264, holdout {d1['hold']['trades']} vs 97), but still a {d1['all']['win']}% win "
          f"rate, not 67.9%, and random entries beat it (p {dg['twin_p']:.2f}, random average "
          f"{rs(dg['twin_mean'])}).")
    a("- **The manual is not internally consistent.** Book 2's per-index rows add up to 425 trades (+Rs 44,911), "
      "not the stated 271 (+Rs 43,924); design 327 vs 206, holdout 98 vs 65. It names files and folders this repo has "
      "never had (`derived/r30/labels_*.parquet`, `derived/r132_ict_exits/`, `options_lab/costs.py`, "
      "`real_options_dual_book_engine.py`, a Blueprint .md).")
    a("- **It borrows our study names for different rules.** The repo's X1 (R30) is -30% / keep +Rs 500 after "
      "+Rs 1,000 / 60 min, and R72 buys the 1-ITM option; the manual's X1 is +20% trigger / keep 50% of the peak "
      "gain on ATM. The repo's R132 S3-V1-E3 is a CISD rule (3,122 design trades, 63% win, avg Rs 12, rejected), "
      "not an order-block pullback.")
    a("- **Its side claims:** \"R72 credit spreads -Rs 77,605 on 666 trades\" and \"-Rs 39,372 on 255\" are R173's "
      "real-price results (NIFTY + BANKNIFTY only, Nov 2024 - Feb 2026), quoted out of context. \"R30 C_SPR/P_SPR "
      "-Rs 5,78,235 on 2,538 R72 trades\", \"-Rs 7,74,987 on 2,878 R52 trades\" and \"unfiltered R72 X1 54.0% win, "
      "-Rs 1,88,325 / +Rs 99,515 at 42.9%\" appear nowhere in the repo; R30's SPR is a debit spread, not a credit spread.")
    fails = {n: R[n]["gates"]["fails"] for n in K.TESTS}
    a(f"- **Gates: all three fail** (Book 1: {', '.join(GATE_TXT[f] for f in fails['B1'])}; Book 2: "
      f"{', '.join(GATE_TXT[f] for f in fails['B2'])}; combined: {', '.join(GATE_TXT[f] for f in fails['COMB'])}).")
    a("- **Verdict: do not build it.** Nothing goes to paper or live. The Apr - Oct 2026 \"holdout\" was read by many "
      "earlier studies (not clean), and the lake ends 19 Oct 2026, so no fresh days after the manual's window exist yet.")
    a()
    a("## 1. Manual vs ours (1 lot; trades / win % / net at 1x slippage)")
    a()
    a("| | manual, all | ours, all | manual 2x | ours 2x | manual design | ours design | manual holdout | ours holdout | reproduces? |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for n in ("B1", "B2", "COMB", "COMB_NOSTOP"):
        s, c = R[n]["split"], K.CLAIMS[n]
        rp = R[n].get("reproduces", {})
        miss = [k for k in ("trades", "win", "net") if not rp.get(k)]
        a(f"| {NAMES[n]} | {claim_ln(c['all'])} | **{ln(s['all'])}** | {rs(c['all2x'])} | {rs(s['all']['net2'])} | "
          f"{claim_ln(c['design'])} | {ln(s['design'])} | {claim_ln(c['hold'])} | {ln(s['hold'])} | "
          f"{'yes' if rp.get('all') else 'no (' + ', '.join(miss) + ')'} |")
    a()
    a("Reproduces = trades within +-25%, win rate within 5 points, net of the same sign within +-50% (PREREG section 5).")
    a()
    a("## 2. Gates (PREREG section 5)")
    a()
    a(f"BH over m = {r['m_total']:,} (4,044 earlier tests + R180's 3 gated + 4 sensitivity runs).")
    a()
    a("| run | trades | win | net | 2x | avg win / loss | worst month | max DD | twin p (random mean) | BH q | holdout n | verdict |")
    a("|---|---|---|---|---|---|---|---|---|---|---|---|")
    for n in list(K.TESTS) + list(K.SENS):
        x = R[n]
        s = x["split"]["all"]
        g = x["gates"]
        a(f"| {NAMES[n]} | {s['trades']} | {s['win']}% | {rs(s['net'])} | {rs(s['net2'])} | {rs(s['avg_win'])} / "
          f"{rs(s['avg_loss'])} | {rs(s['worst_month'])} ({s['worst_month_at']}) | {rs(s['max_dd'])} | "
          f"{x['twin_p']:.3f} ({rs(x['twin_mean'])}) | {x['bh_q']:.2f} | {x['split']['hold']['trades']} | "
          f"{'PASS' if g['passed'] else 'fail: ' + ', '.join(GATE_TXT[f] for f in g['fails'])} |")
    a()
    a("Design / holdout of the sensitivity runs (repo fill = buy at the next minute's open, Book 2's stop / lock sold at "
      "the next open):")
    a()
    for n in K.SENS:
        s = R[n]["split"]
        a(f"- {NAMES[n]}: design {ln(s['design'])}, holdout {ln(s['hold'])}.")
    a()
    a("## 3. Per index (manual vs ours, the books on their own)")
    a()
    a("| book | index | manual all | ours all | manual 2x | ours 2x | manual design | ours design | manual holdout | ours holdout |")
    a("|---|---|---|---|---|---|---|---|---|---|")
    for bk in ("B1", "B2"):
        pi = R[bk]["split"]["per_index"]
        for u in (K.UNDS if bk == "B1" else K.B2_UNDS):
            c = K.CLAIMS_IDX[(bk, u)]
            s = pi.get(u)
            if s is None:
                a(f"| {bk} | {u} | {c[0]} / {c[1]}% / {rs(c[2])} | 0 | {rs(c[3])} | - | {c[4]} / {c[5]}% / {rs(c[6])} | - | "
                  f"{c[7]} / {c[8]}% / {rs(c[9])} | - |")
                continue
            a(f"| {bk} | {u} | {c[0]} / {c[1]}% / {rs(c[2])} | {ln(s['all'])} | {rs(c[3])} | {rs(s['all']['net2'])} | "
              f"{c[4]} / {c[5]}% / {rs(c[6])} | {ln(s['design'])} | {c[7]} / {c[8]}% / {rs(c[9])} | {ln(s['hold'])} |")
    a()
    a("Manual's Book 2 per-index rows add to 425 trades, not its stated total of 271.")
    a()
    a("## 4. Combined account by month (-Rs 11k pause): manual vs ours")
    a()
    a("| month | manual trades / win / net | ours trades | ours win | ours net 1x | ours net 2x |")
    a("|---|---|---|---|---|---|")
    for m, v in cb["monthly"].items():
        mm = MANUAL_MONTH.get(m)
        mt = f"{mm[0]} / {mm[1]}% / {rs(mm[2])}" if mm else "-"
        a(f"| {m} | {mt} | {v['n']} | {v['win']}% | {rs(v['net'])} | {rs(v['net2'])} |")
    a()
    a(f"The manual shows 24 months, every one but 6 positive, worst -Rs 11,461 (Dec 2025, after the pause). Ours: worst "
      f"{rs(cb['all']['worst_month'])} ({cb['all']['worst_month_at']}), max drawdown {rs(cb['all']['max_dd'])}; "
      f"the combined account took {r['overlap']['B1']} Book 1 and {r['overlap']['B2']} Book 2 trades.")
    a()
    a("## 5. Funnels and exits")
    a()
    c1, c2 = r["counts"]["B1"], r["counts"]["B2"]
    a(f"- Book 1: {c1['breadth']:,} index breaks on a >= 4-of-5 bar; {c1['not_initial']:,} not the initial break "
      f"(index still locked); {c1['window']:,} outside 09:30 - 14:45; {c1['strength']:,} weaker than 0.5 ATR; "
      f"{c1['straddle']:,} straddle not above 09:20; **{c1['accepted']}** accepted; none over the Rs 56k cap; one "
      f"position at a time leaves {b1['all']['trades']}.")
    a(f"- Book 2: {c2['order_blocks']:,} order blocks; {c2['in_window']:,} alive inside 12:15 - 14:45; "
      f"**{c2['triggered']:,}** triggered with the straddle below -5%; one position leaves {b2['all']['trades']}.")
    for n in ("B1", "B2", "COMB"):
        a(f"- Exits, {NAMES[n]}: " + ", ".join(f"{k} {v}" for k, v in R[n]["split"]["reasons"].items()) + ".")
    for n in ("B1_all", "B2_all"):
        s = R[n]["split"]
        a(f"- Every accepted signal, no one-position rule ({NAMES[n[:2]]}): {ln(s['all'])} (design {ln(s['design'])}, "
          f"holdout {ln(s['hold'])}), max DD {rs(s['all']['max_dd'])}.")
    a()
    if dg:
        d1 = dg["B1_nolock"]
        a("## 6. Post-hoc diagnostic (written after the run; not a test, not gated)")
        a()
        a(f"Book 1 without the per-index \"initial breakout\" state machine (its `idx_stop_ext` is never defined), "
          f"manual fills, one position: **{ln(d1['all'])}**, 2x {rs(d1['all']['net2'])}, design {ln(d1['design'])}, "
          f"holdout {ln(d1['hold'])}, worst month {rs(d1['all']['worst_month'])}, max DD {rs(d1['all']['max_dd'])}. "
          f"Random-entry twin p {dg['twin_p']:.3f} (random mean {rs(dg['twin_mean'])}), BH q {dg['bh_q']:.2f} "
          f"(m = {dg['m_total_with_diag']:,}). Every accepted signal without the one-position rule: "
          f"{ln(dg['B1_nolock_all']['all'])}. The trade count now matches the manual's (361 / 264 / 97) closely; the win "
          f"rate and the design net do not.")
        a()
    a("## 7. Every ambiguity in the manual and the choice made (fixed in PREREG.md before any number)")
    a()
    for x in AMBIG:
        a(f"- {x}")
    a()
    a("## 8. Data, costs, honesty")
    a()
    a("- R51's Amendment A clean stores (code unchanged), built for R180 from 1 Nov 2024 to 19 Oct 2026 (the lake's "
      "first November session is 4 Nov; 1 Nov 2024 was the Diwali special session, not in the lake). Held contracts: a "
      "trade is priced on the strike bought. Lots by date (`r51cfg.LOTS`): NIFTY 25 -> 75 (28 Nov 2024) -> 65 (31 Dec "
      "2025), and so on. After 20 Nov 2024 BANKNIFTY / FINNIFTY / MIDCPNIFTY have monthly expiries only, so their "
      "\"ATM\" option is the monthly contract (R173 measured a median of 14 days to expiry on BANKNIFTY); NIFTY and SENSEX trade the weekly.")
    a("- Costs('dated') on both orders (the rates in force on the date; the manual's Rs 40 brokerage is the same two "
      "Rs 20 orders); R17 liquidity fills (buy +5 bps + 1-4 ticks by volume, stop sale -10 bps - 2x the half-spread); "
      "2x slippage = R51's SLIP2. A premium stop gapped through fills at the minute's open.")
    a("- The holdout (Apr - Oct 2026) was read by R51 - R176 before; it is not clean. No data exists after 19 Oct 2026.")
    a("- Runs: see `FINAL_READ` (first run, then any re-run with its reason). The diagnostic in section 6 was added "
      "after the first run and is labelled as such.")
    (K.RESULTS / "report.md").write_text("\n".join(L) + "\n")
    print("wrote", K.RESULTS / "report.md")


MANUAL_MONTH = {   # the manual's table 4.2, the -Rs 11k stop columns: (trades, win %, net 1x)
    "2024-11": (17, 58.8, 5350), "2024-12": (23, 73.9, 9010), "2025-01": (22, 63.6, 1588), "2025-02": (15, 60.0, 860),
    "2025-03": (14, 85.7, 11332), "2025-04": (16, 56.2, 477), "2025-05": (20, 70.0, -2181), "2025-06": (20, 45.0, -4300),
    "2025-07": (17, 58.8, 4344), "2025-08": (25, 48.0, -2869), "2025-09": (26, 73.1, 11888), "2025-10": (19, 78.9, 10656),
    "2025-11": (21, 71.4, 6016), "2025-12": (8, 25.0, -11461), "2026-01": (20, 65.0, 5360), "2026-02": (27, 63.0, 127),
    "2026-03": (16, 87.5, 15690), "2026-04": (16, 81.2, 10794), "2026-05": (23, 65.2, 1496), "2026-06": (20, 65.0, -2921),
    "2026-07": (21, 52.4, 3693), "2026-08": (13, 69.2, 7416), "2026-09": (14, 42.9, 3101), "2026-10": (7, 42.9, -4018),
}

AMBIG = [
    "straddle_vs_0920 uses the raw rupee straddle (the manual's formula); R30's version divides by spot first. ATM = "
    "the lake's offset-0 strike of that minute; 09:20 = the 09:20 minute's close.",
    "Book 1 Donchian: the prior 20 bars of the continuous multi-day 5-minute series (R72's); \"bar index >= 60\" = of "
    "that series. ATR14 = EWM span 14 (manual), not R72's Wilder ATR.",
    "Book 1 \"initial-breakout state machine\": `idx_stop_ext` undefined -> 0; every >= 4-of-5 break of an index (taken "
    "or not) locks that index until its 1-minute price touches the breakout bar's channel middle (or 15:10), same day. "
    "A later, stronger break while locked is dropped. Section 6 shows the count without it.",
    "Book 1 entry: the manual's `opt_close[em] + slip` (close of the minute after the signal bar) with the R17 buy fill; "
    "levels (-30%, +20%, 50% of the peak gain) on the filled price, as the manual's `entry_px`; the lock reads the PRIOR "
    "minute's peak; time exit at the close of min(em + 60, 15:20).",
    "Book 1 ties on one bar: the highest straddle_vs_0920 (manual). The Rs 56k cap: filled price x lot size (never "
    "binding at 1 lot).",
    "Expiry days: square-off 15:05 for both books (CLAUDE.md; the manual's own guard table says 15:05 on expiry days).",
    "Book 2 swings: continuous series; each swing is broken once (the first close beyond it); the displacement bar must "
    "close in its own direction and be that first break; median body of the 20 bars BEFORE it.",
    "Book 2 order block: the last opposite-colour candle among the 5 bars before the displacement; valid for the 60 "
    "minutes after the displacement bar closes, same day; one trade per order block (the first minute meeting every "
    "condition).",
    "Book 2 trigger: the 1-minute range touches the zone, closes in the direction, not beyond the far edge; the straddle "
    "read at the last completed 5-minute mark; entry at the trigger minute's close (manual's `opt_close[entry]`).",
    "Book 2 stop: the index 1-minute low (high) beyond ref -/+ stop_pts, sold at that minute's option close (manual), as "
    "a stop sale; ATR = the last completed 5-minute bar's EWM-14 ATR. Lock: rupee P&L on closes from the raw entry close.",
    "One position per book (manual: \"1 open lot across account\"); a new entry from the exit minute + 1. Combined: one "
    "position across both books, Book 1 first at the same minute; the -Rs 11k pause counts closed trades of the month.",
    "Sensitivity (the repo's standard fill): buy at the next minute's open; Book 2's stop / lock sold at the next open.",
]
