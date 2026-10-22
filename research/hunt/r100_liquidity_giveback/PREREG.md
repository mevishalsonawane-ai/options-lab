# R100 pre-registration: give-back protection for Liquidity 15+5

Written 22 Oct 2026, **before any R100 statistic was computed**. Research first; any app change follows CLAUDE.md
(paper only if a variant PASSES; live only in shadow behind an off-by-default switch).

Boss, 22 Oct: *"do something for liquidity strategy, it went up to 4.5k, and settled at -1k, this is not acceptable
for me"*.

## 0. The trade that started it (an illustration, never evidence)

From Boss's diagnostics of 22 Oct 09:48 (pasted in the session; the lake ends 19 Oct, so no minute data exists here
for it): **Liquidity 5m MIDCPNIFTY, a put** (1-ITM, lot 120), bought 09:20:02 @ 172.95 on the 09:15 5-minute bar's
break, sold 09:40:02 @ 164.00 by the **20-minute time stop** (not +5% at 20 minutes), -Rs 1,074 before charges,
-Rs 1,168 after. Boss saw about +Rs 4,500 open profit in between: about +37.5 points a unit, the option near 210
(+21.7%). No premium lock was on for this book (the Liquidity books have none by design; Boss's Rs 500 paper trail
switch is opt-in). The variants below are scored on history only; this trade is re-told with each rule in the
write-up as a story, not as a test.

## 1. What is already known (do not re-test as new)

Every premium lock, ladder and trail tried on Liquidity failed: LIQUIDITY_LOCK / LIQUIDITY_TRAIL (2024-26), R19
(target-relative locks), R19b / R19c (Boss's Rs 500 give-back trail, from +Rs 500 and from +Rs 1,000 a lot), R64 / R70
(L25), R76 (T25-T50 index-progress +5% locks, L35 +5% once +35%), R81 (X3 index chandelier, X5 half at +15%, X11
structure breakeven). The lesson each time: **Liquidity earns on a few runners; a lock that arms early cuts them.**

R100 therefore tests only protections that arm **late, after a big open profit** (the give-back Boss saw), plus the
four kinds Boss's request names: a breakeven-then-share step lock, a premium peak give-back trail, an index-based
trail, and time-based tightening. R76's L35 (+5% once +35%) is the nearest old rule; it changed 10 of 1,238 trades.

## 2. Arm and trades (exactly as R76 / R81)

Liquidity 15+5, `hunt/r51/fam_liq.py make(und, books)` on R51's Amendment A clean stores (R51_CLEAN=1), the Drive
lake copied 22 Oct (options to 13 Oct 2026 in the stores, as before): BANKNIFTY 15+5 (index stop 30 pts), FINNIFTY 30+5
(15 pts), MIDCPNIFTY 15+5 (8 pts); 1-ITM; one position per book; expiry days skipped. **B0 must reproduce R76 / R81
exactly** (Rs 2,57,249 on 881 design trades, Rs 11,155 on 357 final trades), else the study stops.

- **Signal source:** the index (level breaks on 15/30/5-minute index bars). Unchanged. The 1-ITM option is bought.
- **B0 own exits:** -15% premium stop; 20-minute time stop unless +5%; index exits (index stop beyond the broken
  level, next-level target, failed break, new level); square-off 15:10.
- Each B0 trade is priced again under each variant from the same entry row and the same index-exit row on R64's
  held-contract engine (one contract, liquidity slippage, stops on the minute low with gap fills at the worse open,
  locks set at a minute's end and acting from the next minute, dated charges, 1 lot). A variant only ever ADDS an
  earlier exit; every B0 exit stays.

## 3. Variants (B0 + 7 tests). All numbers fixed here; nothing is tuned.

Definitions: `E` = the raw option fill; `peak` = the highest 1-minute option high since the fill (through the minute
just ended); `gain` = peak / E - 1; `BE` = breakeven after charges = real entry + round-trip charges / quantity (R64);
"keep k" = a lock at `E + k x (peak - E)`, never below BE when it applies; `I0` = the index open of the fill minute
(R76). Premium locks are stops on the option premium (allowed by CLAUDE.md); no rule reads an option chart for a
signal.

| id | rule | exit source | kind |
|---|---|---|---|
| **B0** | Liquidity's own exits | premium + index | |
| **G1 BE20** | once gain >= +20%, the stop rises to BE | premium | lock (late breakeven) |
| **G2 BE15** | once gain >= +15%, the stop rises to BE | premium | lock (breakeven) |
| **G3 STEP** | +15% -> BE; +30% -> keep 0.40; +50% -> keep 0.60 (the highest reached counts) | premium | step lock |
| **G4 KEEP50** | once gain >= +20%, keep 0.50 of the best gain (a continuous give-back trail) | premium | peak give-back trail |
| **G5 IDXBE** | once a 1-minute index bar has gone >= 2 index-stop units past I0 in the trade's direction (BANKNIFTY 60, FINNIFTY 30, MIDCPNIFTY 16 points), the index stop moves to I0: from the next minute, a 1-minute index bar back at I0 (call: low <= I0; put: high >= I0) sells at the next minute's open | index | index trail (breakeven) |
| **G6 TIME** | once gain >= +20%, keep 0.25 of the best gain in the first 30 minutes after the fill, 0.50 from minute 30, 0.75 from minute 60 (minutes counted from the fill row) | premium + time | time-based tightening |
| **G7 RUPEE** | once the open profit before charges reaches +Rs 3,000 a lot, keep 0.50 of the best open profit | premium (in rupees) | Boss's money terms |

Reference rows (reported, not tested, outside the BH family): **R19C**, Boss's existing paper switch (+Rs 1,000 a lot
activates, sell Rs 500 a lot under the best), re-priced on this trade set so its cost is on the same footing.

## 4. Gates (a variant PASSES only if all hold)

1. Net (1 lot, after charges and slippage) **>= B0** in design (20 Nov 2024 - 31 Mar 2026), walk-forward (Apr 2025 -
   Mar 2026, the design's last four quarters; nothing is fitted, the numbers above are fixed) and final (1 Apr -
   13 Oct 2026).
2. **Random early-exit twins** (R65 / R76): 500 sets of as many random early exits (a uniform row inside the trade, the
   plain exits still acting first) as the rule changed trades; p = share of twin totals >= the rule's total. Run on the
   final period (as R76) **and** on design. BH across the 7 tests on each; the gate is **q <= 0.10 on both**.
3. **Not carried by a few trades:** in design, the gain over B0 stays >= 0 after removing the 5 trades where the rule
   gained most.
4. **Sample:** at least 30 changed trades in design.

"Harmless" (reported, never acted on): gate 1 only. Failing any gate means the rule is **not applied** to the paper
arms. It may be offered to Boss as a labelled opt-in switch with its measured cost, as R19b's trail was.

## 5. Both sides, measured honestly (descriptive, every variant)

- **Give-back trades:** B0 trades whose option peak reached >= +20% and that closed at a loss after charges (count,
  Rs lost); and the Rs each variant **kept** on them.
- **Winners cut short:** trades where the variant made less than B0 (count, Rs given up), with the worst example.
- Per index (BANKNIFTY / FINNIFTY / MIDCPNIFTY) and per book, design and final; win rate, worst day, max drawdown.
- A one-sided paired day bootstrap of "variant beats B0" (2,000 resamples), as R76.
- **Walk-forward selection (descriptive):** at the start of each walk-forward quarter, pick the variant (or B0) with
  the best net on all design days before it, and apply it to that quarter only; report the stitched total against B0.

## 6. Periods and reading rules

- Design 20 Nov 2024 - 31 Mar 2026; walk-forward Apr 2025 - Mar 2026 (inside design); final 1 Apr - 13 Oct 2026,
  opened only by `r100study.py final`, which writes `results/FINAL_READ` and refuses a second read. **The final period
  was already read for this arm by R51, R63, R70, R76 and R81, so it is weak evidence.**
- The code and the design results are committed before the final read.
- 14 - 19 Oct 2026 (the lake's newest days, not in the stores) is not used: about 5 sessions, too few to judge, and
  building it would change R51's shared stores.

## 7. Look-ahead guard and tests

`tests/test_r100.py`: each rule's decision at minute m reads only bars <= m (a test changes every bar after the
decision minute and checks the exit is unchanged); edge cases: no fill, a gap through the lock, a NaN minute, no index
bar, a lock above the price (sells at the next open), the time stop still acting, a peak exactly at a threshold, and
B0 equal to R64's engine line for line when no rule is set.

## 8. Costs

Dated charges (`hunt/r51/engine.py charges`), R17 liquidity slippage (`E.SLIP`), next-minute fills, 1 lot.
