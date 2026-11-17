# R183: can R51 / R72 skip "sideways" markets?

**Boss, in short (15 Nov 2026).** No. None of the 16 sideways filters made R72 better, and the one that looked
better on R51 in the earlier year made less money in the test year. **Keep today's rules (picker + breakeven at +10%) as they are.**

You asked *"How could we ignore sideways markets"* after R51 and R72 lost on 9 - 13 Nov (58 paper trades, 24 won =
41%, -Rs 54,729 paper net). The test: skip a new R51 / R72 entry when the index looks sideways, and keep every other rule
as today: the R151 picker plus the breakeven lock at +10% (R179's V4). Every filter is read from **the index the arm
signals on**, from its 1-minute and 5-minute bars strictly before the signal bar. No option price is used. A skipped
entry still holds its side and counts in the day's caps, as the picker does in the app, so a filter can only take trades
away.

**How it was judged (fixed in PREREG.md before any number).** We tried 16 filters, 5 kinds with 2-4 settings each:
ADX below 15 / 20 / 25; the efficiency ratio over 6 or 12 five-minute bars below 0.2 / 0.3; 3 / 5 / 7 or more crossings
of today's average price (the time-weighted average, since the index has no volume); today's range so far below
40% / 60% of the 20-day average range, or below 70% / 85% of the usual range by this time of day; Choppiness above 55 /
61.8. Each was compared with today's rules on the year before (20 Nov 2024 - 19 Oct 2025). A filter qualified only if it
beat today's rules in both halves of that year. For each arm, the qualifying filter with the biggest gain was then tested
once on the last year (20 Oct 2025 - 19 Oct 2026). All figures are at 1 lot, after charges and slippage.

**Earlier year (the selection, 1 lot).** Today's rules made R72 Rs 2,99,254 (1,043 trades, 59.5% won) and R51
Rs 2,24,212 (1,352 trades, 55.0%).
- **R72: nothing qualified.** All 16 filters cut profit, by Rs 31,764 (7+ crossings) to Rs 1,61,628 (efficiency ratio
  over 12 bars below 0.3). No test was run for R72.
- **R51: two qualified**, both "too many crossings of today's average price":
  - 3+ crossings: +Rs 1,639; 956 trades.
  - **7+ crossings: +Rs 24,911**, made up of +Rs 10,098 in the first half and +Rs 14,813 in the second; 1,236 trades,
    116 skipped. **Chosen.**
  - The range, ADX, efficiency and Choppiness filters all lost R51 money, the range ones heavily: below 60% of the
    average range left Rs 42,870.

**The one test: R51 with "skip after 7+ crossings", last year, 1 lot.** It **failed**:

| R51, 1 lot | today's rules (picker + breakeven) | with the 7+ crossings skip |
|---|---|---|
| whole year | 1,367 trades, 55.5% won, **Rs 2,49,903** | 1,232 trades, 55.4%, **Rs 2,18,767** |
| final (Apr - Oct 2026) | 655, 55.1%, Rs 1,44,802 | 595, 54.8%, Rs 1,18,244 |
| early (Oct 2025 - Mar 2026) | 694, 56.1%, Rs 1,13,329 | 620, 56.1%, Rs 1,06,986 |
| last 6 months | 601, 56.1%, Rs 1,49,820 | 552, 56.5%, Rs 1,49,009 |
| worst month / max drawdown (year) | -Rs 20,990 (Jun 2026) / Rs 36,021 | -Rs 29,986 (Jun 2026) / Rs 47,905 |

- It skipped 135 trades and lost Rs 31,136 against today's rules.
- **Random skips did better.** Dropping 135 trades at random averaged Rs 2,24,564 (2,000 random tries, sd Rs 23,236),
  more than the filter's Rs 2,18,767. Random-skip p 0.60, BH q 1.00 (m = 4,376).
- At 3 lots: Rs 7,80,580 vs Rs 8,87,796 for the year.
- Failed gates: net not above today's in early, final and the year; twin p and q; the account stop rule (-Rs 13k month /
  -Rs 40k drawdown) in the final, last 6 months and the year. Today's rules already break that rule there.

**Why sideways filters do not help these arms.** Both arms make their money from a few large breakout winners, and those
often start from quiet, narrow mornings. The filters that skip "quiet" days (low range so far, low ADX, low efficiency)
removed exactly those winners. For example, R51 with "range below 60% of average" fell from Rs 2,49,903 to Rs 12,009
on the last year. The picker already removes many weak breaks. The filters that judge "choppy" days (crossings,
Choppiness) skipped about as well as chance. Only one, Choppiness above 61.8 on R72, edged above today's rules on the
last year (Rs 3,28,969 vs Rs 3,24,629, descriptive), and it had lost Rs 62,156 on the earlier year.

**9 - 13 Nov (descriptive only; the engine's stores end 19 Oct 2026, so those days cannot be replayed).**
- **Coverage.** The filter values were rebuilt from the phone's recordings for 46 of the 58 paper trades: 19 from the
  NIFTY / BANKNIFTY index, 27 from the FINNIFTY / MIDCPNIFTY near future as a stand-in for the index. The 12 SENSEX trades
  could not be checked: SENSEX is not recorded. 10 Nov has no recording and no paper trades.
- **The chosen 7+ crossings skip would have skipped none of R51's 23 checkable trades.** Those trades won 11 and lost
  Rs 16,199 on paper. By this measure those days were not choppy.
- **Range-so-far filters would have skipped mostly winners.** "Below 40% of average" skipped 9 R72 trades, 7 of which
  won (+Rs 8,403).
- **ADX below 20 would have skipped mostly losers here.** R72: 6 skipped, 1 won, -Rs 20,002. But on the full year it cut
  R72 from Rs 3.25 L to Rs 97k. Four trading days and 46 trades cannot overrule a year of 1,000+ trades.

**Bottom line:** no sideways filter is an edge on top of today's R51 / R72 rules, and nothing in the app changes. This
agrees with R15, R80, R101 and R178: day-type and entry filters have not beaten these arms' plain entries.

Honest notes:
- **Baseline.** The baseline is R179's V4, which is what the app runs since 15 Nov (Boss's fifth exception). The 9 - 13
  Nov paper trades had the picker (live since 6 Nov) but not yet the breakeven lock, so their record is not exactly V4's.
- **In-sample periods.** The picker was trained on 20 Nov 2024 - 31 Mar 2026, so it is in-sample in the selection year
  and the early part of the test year. The filters were not.
- **Multiple testing.** 32 selection comparisons and 1 test were counted in BH. The running count was 4,073 after R182,
  plus R177-EMA's 270, so m = 4,376.
- **Parity.** Today's rules reproduced R179's published V4 exactly: R72 604 / Rs 1,67,233 final and 1,069 / Rs 3,24,629
  year; R51 655 / Rs 1,44,802 and 1,367 / Rs 2,49,903.
- R181's "VWAP-chop" reading does not exist in the repo, so it was not added.
