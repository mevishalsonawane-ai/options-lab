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

# Full tables

Signal source of every filter: the index the arm signals on (1-minute and 5-minute bars strictly before the signal bar). Baseline B = R179's V4 (today's rules + R151 picker + breakeven at +10%), 1 lot, after dated Zerodha charges and R17 fills (slippage). A skipped entry still holds its side (as the app's picker).

## Parity (asserted before anything else was read)

| arm : period | trades | net | R179 published | ok |
|---|---|---|---|---|
| R72:final | 604 | Rs 167,233 | 604 / Rs 167,233 | yes |
| R72:year | 1,069 | Rs 324,629 | 1,069 / Rs 324,629 | yes |
| R51:final | 655 | Rs 144,802 | 655 / Rs 144,802 | yes |
| R51:year | 1,367 | Rs 249,903 | 1,367 / Rs 249,903 | yes |

## Selection: 20 Nov 2024 - 19 Oct 2025 (1 lot; H1 to 30 Apr 2025, H2 from 1 May 2025)

### R72 Breadth Donchian: chosen **none (no variant beat B in both halves)**

B: 1,043 trades, 59.5% won, Rs 299,254 (H1 Rs 82,661, H2 Rs 216,593), worst month -Rs 15,785 (2025-03), max drawdown Rs 94,500.

| variant | rule (skip when) | trades | skipped | win | net | gain H1 | gain H2 | gain whole | worst month | max DD | eligible |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ADX15 | ADX(14) 5-min < 15 | 906 | 137 | 60.0% | Rs 259,531 | -Rs 15,388 | -Rs 24,335 | -Rs 39,724 | -Rs 25,916 | Rs 103,331 | no |
| ADX20 | ADX(14) 5-min < 20 | 698 | 345 | 62.2% | Rs 238,215 | -Rs 16,372 | -Rs 44,667 | -Rs 61,039 | -Rs 36,645 | Rs 86,712 | no |
| ADX25 | ADX(14) 5-min < 25 | 459 | 584 | 61.9% | Rs 153,809 | Rs 842 | -Rs 146,287 | -Rs 145,445 | -Rs 37,810 | Rs 71,200 | no |
| ER6_20 | efficiency ratio, 6 x 5-min < 0.20 | 801 | 242 | 59.7% | Rs 239,828 | -Rs 27,983 | -Rs 31,443 | -Rs 59,426 | -Rs 19,042 | Rs 86,851 | no |
| ER6_30 | efficiency ratio, 6 x 5-min < 0.30 | 686 | 357 | 59.8% | Rs 209,125 | -Rs 28,674 | -Rs 61,455 | -Rs 90,129 | -Rs 12,990 | Rs 80,312 | no |
| ER12_20 | efficiency ratio, 12 x 5-min < 0.20 | 713 | 330 | 59.3% | Rs 200,229 | -Rs 16,432 | -Rs 82,593 | -Rs 99,025 | -Rs 27,321 | Rs 74,078 | no |
| ER12_30 | efficiency ratio, 12 x 5-min < 0.30 | 554 | 489 | 59.0% | Rs 137,626 | -Rs 40,951 | -Rs 120,677 | -Rs 161,628 | -Rs 21,759 | Rs 49,736 | no |
| TX3 | >= 3 TWAP crossings today | 623 | 420 | 60.8% | Rs 195,960 | -Rs 47,805 | -Rs 55,489 | -Rs 103,294 | -Rs 29,881 | Rs 82,867 | no |
| TX5 | >= 5 TWAP crossings today | 806 | 237 | 59.6% | Rs 223,841 | -Rs 31,005 | -Rs 44,408 | -Rs 75,414 | -Rs 25,424 | Rs 95,096 | no |
| TX7 | >= 7 TWAP crossings today | 904 | 139 | 60.3% | Rs 267,490 | -Rs 4,126 | -Rs 27,639 | -Rs 31,764 | -Rs 23,877 | Rs 91,374 | no |
| RF40 | range so far < 40% of the 20-day average range | 792 | 251 | 58.7% | Rs 197,408 | -Rs 42,113 | -Rs 59,733 | -Rs 101,846 | -Rs 34,592 | Rs 79,262 | no |
| RF60 | range so far < 60% of the 20-day average range | 517 | 526 | 60.9% | Rs 168,056 | -Rs 6,013 | -Rs 125,185 | -Rs 131,198 | -Rs 23,513 | Rs 49,432 | no |
| RT70 | range so far < 70% of the usual range by this time | 863 | 180 | 60.7% | Rs 249,465 | -Rs 9,514 | -Rs 40,276 | -Rs 49,789 | -Rs 16,984 | Rs 70,039 | no |
| RT85 | range so far < 85% of the usual range by this time | 686 | 357 | 63.0% | Rs 247,607 | -Rs 1,845 | -Rs 49,802 | -Rs 51,647 | -Rs 15,410 | Rs 55,802 | no |
| CH55 | Choppiness(14) 5-min > 55 | 733 | 310 | 60.4% | Rs 250,221 | -Rs 6,461 | -Rs 42,572 | -Rs 49,033 | -Rs 8,061 | Rs 61,137 | no |
| CH62 | Choppiness(14) 5-min > 61.8 | 916 | 127 | 59.2% | Rs 237,098 | -Rs 39,077 | -Rs 23,078 | -Rs 62,156 | -Rs 22,357 | Rs 102,685 | no |

### R51 Donchian 20 + Supertrend: chosen **TX7**

B: 1,352 trades, 55.0% won, Rs 224,212 (H1 Rs 77,360, H2 Rs 146,852), worst month -Rs 37,319 (2025-06), max drawdown Rs 67,819.

| variant | rule (skip when) | trades | skipped | win | net | gain H1 | gain H2 | gain whole | worst month | max DD | eligible |
|---|---|---|---|---|---|---|---|---|---|---|---|
| ADX15 | ADX(14) 5-min < 15 | 1,161 | 191 | 54.1% | Rs 132,569 | -Rs 39,458 | -Rs 52,185 | -Rs 91,643 | -Rs 42,556 | Rs 71,761 | no |
| ADX20 | ADX(14) 5-min < 20 | 856 | 496 | 56.3% | Rs 166,108 | -Rs 21,162 | -Rs 36,943 | -Rs 58,105 | -Rs 20,625 | Rs 50,993 | no |
| ADX25 | ADX(14) 5-min < 25 | 536 | 816 | 56.9% | Rs 157,179 | Rs 10,113 | -Rs 77,146 | -Rs 67,033 | -Rs 22,798 | Rs 44,809 | no |
| ER6_20 | efficiency ratio, 6 x 5-min < 0.20 | 996 | 356 | 54.6% | Rs 172,508 | -Rs 64,319 | Rs 12,614 | -Rs 51,705 | -Rs 27,080 | Rs 52,851 | no |
| ER6_30 | efficiency ratio, 6 x 5-min < 0.30 | 821 | 531 | 55.5% | Rs 164,377 | -Rs 72,815 | Rs 12,980 | -Rs 59,835 | -Rs 24,394 | Rs 50,521 | no |
| ER12_20 | efficiency ratio, 12 x 5-min < 0.20 | 831 | 521 | 54.0% | Rs 119,838 | -Rs 52,997 | -Rs 51,378 | -Rs 104,375 | -Rs 15,125 | Rs 51,242 | no |
| ER12_30 | efficiency ratio, 12 x 5-min < 0.30 | 597 | 755 | 55.4% | Rs 110,773 | -Rs 57,147 | -Rs 56,293 | -Rs 113,440 | -Rs 15,336 | Rs 29,292 | no |
| TX3 | >= 3 TWAP crossings today | 956 | 396 | 57.2% | Rs 225,852 | Rs 1,479 | Rs 160 | Rs 1,639 | -Rs 34,983 | Rs 64,607 | yes |
| TX5 | >= 5 TWAP crossings today | 1,119 | 233 | 56.2% | Rs 234,473 | -Rs 7,633 | Rs 17,894 | Rs 10,261 | -Rs 35,226 | Rs 70,085 | no |
| TX7 | >= 7 TWAP crossings today | 1,236 | 116 | 56.1% | Rs 249,124 | Rs 10,098 | Rs 14,813 | Rs 24,911 | -Rs 30,418 | Rs 68,272 | yes |
| RF40 | range so far < 40% of the 20-day average range | 861 | 491 | 53.4% | Rs 64,985 | -Rs 103,088 | -Rs 56,140 | -Rs 159,228 | -Rs 16,119 | Rs 86,561 | no |
| RF60 | range so far < 60% of the 20-day average range | 413 | 939 | 54.2% | Rs 42,870 | -Rs 79,729 | -Rs 101,613 | -Rs 181,342 | -Rs 33,125 | Rs 63,003 | no |
| RT70 | range so far < 70% of the usual range by this time | 1,046 | 306 | 55.6% | Rs 173,294 | -Rs 43,956 | -Rs 6,962 | -Rs 50,918 | -Rs 31,084 | Rs 50,824 | no |
| RT85 | range so far < 85% of the usual range by this time | 804 | 548 | 57.6% | Rs 183,848 | -Rs 30,905 | -Rs 9,459 | -Rs 40,364 | -Rs 23,220 | Rs 40,699 | no |
| CH55 | Choppiness(14) 5-min > 55 | 943 | 409 | 56.3% | Rs 212,050 | -Rs 17,244 | Rs 5,081 | -Rs 12,163 | -Rs 22,134 | Rs 56,050 | no |
| CH62 | Choppiness(14) 5-min > 61.8 | 1,191 | 161 | 54.7% | Rs 147,200 | -Rs 53,113 | -Rs 23,899 | -Rs 77,012 | -Rs 35,684 | Rs 74,748 | no |

## The one test: the last year (1 lot), BH m = 4,376 (4,343 earlier + 32 selection comparisons + 1 test)

- R72 Breadth Donchian: nothing chosen, so no test.
- R51 Donchian 20 + Supertrend, **TX7** (>= 7 TWAP crossings today): passed **no**. Skipped 135 of B's year trades; net Rs 218,767 vs the 2,000 random-skip twins' mean Rs 224,564 (sd Rs 23,236); empirical p 0.6032, normal p 0.599, BH q 1.0 (q within R183 0.5985). Failed: net not above the baseline (V4) (early, final, year); random-skip twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year; empirical twin p 0.6032 >= 0.05.

### B vs the chosen filter, 1 lot

| arm | period | B trades / win / net | filter trades / win / net | B worst month / max DD | filter worst month / max DD |
|---|---|---|---|---|---|
| R51 | early (20 Oct 2025 - 31 Mar 2026; picker in-sample) | 694 / 56.0% / Rs 113,329 | 620 / 56.1% / Rs 106,986 | -Rs 3,546 / Rs 35,297 | -Rs 6,729 / Rs 35,952 |
| R51 | final (1 Apr - 13 Oct 2026) | 655 / 55.1% / Rs 144,802 | 595 / 54.8% / Rs 118,244 | -Rs 20,990 / Rs 36,021 | -Rs 29,986 / Rs 44,356 |
| R51 | last 6 months (20 Apr - 19 Oct 2026) | 601 / 56.1% / Rs 149,820 | 552 / 56.5% / Rs 149,009 | -Rs 20,990 / Rs 36,021 | -Rs 29,986 / Rs 44,356 |
| R51 | whole year (20 Oct 2025 - 19 Oct 2026) | 1,367 / 55.5% / Rs 249,903 | 1,232 / 55.4% / Rs 218,767 | -Rs 20,990 / Rs 36,021 | -Rs 29,986 / Rs 47,905 |

### B vs the chosen filter, 3 lots

| arm | period | B trades / win / net | filter trades / win / net | B worst month / max DD | filter worst month / max DD |
|---|---|---|---|---|---|
| R51 | early (20 Oct 2025 - 31 Mar 2026; picker in-sample) | 693 / 56.3% / Rs 422,182 | 619 / 56.5% / Rs 394,194 | Rs 7,210 / Rs 100,557 | Rs 3,678 / Rs 103,242 |
| R51 | final (1 Apr - 13 Oct 2026) | 655 / 55.1% / Rs 488,815 | 595 / 55.0% / Rs 404,387 | -Rs 57,548 / Rs 101,336 | -Rs 85,006 / Rs 126,908 |
| R51 | last 6 months (20 Apr - 19 Oct 2026) | 601 / 56.1% / Rs 498,835 | 552 / 56.7% / Rs 492,523 | -Rs 57,548 / Rs 101,336 | -Rs 85,006 / Rs 126,908 |
| R51 | whole year (20 Oct 2025 - 19 Oct 2026) | 1,366 / 55.6% / Rs 887,796 | 1,231 / 55.6% / Rs 780,580 | -Rs 57,548 / Rs 101,336 | -Rs 85,006 / Rs 135,827 |

## Every variant on the last year (descriptive only; read after the one test, never used to choose)

| variant | R72 year: trades / win / net (skipped) | R72 final net | R51 year: trades / win / net (skipped) | R51 final net |
|---|---|---|---|---|
| B | 1,069 / 56.6% / Rs 324,629 | Rs 167,233 | 1,367 / 55.5% / Rs 249,903 | Rs 144,802 |
| ADX15 | 939 / 56.8% / Rs 274,693 (130) | Rs 162,882 | 1,197 / 55.5% / Rs 202,599 (170) | Rs 138,106 |
| ADX20 | 695 / 53.5% / Rs 97,102 (374) | Rs 79,831 | 867 / 53.4% / Rs 38,538 (500) | Rs 60,789 |
| ADX25 | 450 / 52.2% / Rs 50,328 (619) | Rs 52,191 | 545 / 52.3% / Rs 13,750 (822) | Rs 46,691 |
| ER6_20 | 857 / 56.2% / Rs 219,971 (212) | Rs 112,578 | 1,095 / 56.4% / Rs 198,689 (272) | Rs 123,915 |
| ER6_30 | 744 / 55.4% / Rs 131,553 (325) | Rs 84,632 | 923 / 56.5% / Rs 144,813 (444) | Rs 94,365 |
| ER12_20 | 718 / 53.2% / Rs 94,756 (351) | Rs 13,028 | 931 / 51.9% / Rs 1,112 (436) | Rs 19,773 |
| ER12_30 | 553 / 49.9% / -Rs 1,624 (516) | -Rs 2,934 | 673 / 50.1% / -Rs 42,836 (694) | Rs 17,798 |
| TX3 | 605 / 60.3% / Rs 227,525 (464) | Rs 118,659 | 898 / 58.4% / Rs 224,350 (469) | Rs 135,959 |
| TX5 | 758 / 58.1% / Rs 232,683 (311) | Rs 116,255 | 1,081 / 56.5% / Rs 202,540 (286) | Rs 128,785 |
| TX7 | 889 / 57.4% / Rs 264,026 (180) | Rs 111,602 | 1,232 / 55.4% / Rs 218,767 (135) | Rs 118,244 |
| RF40 | 820 / 55.9% / Rs 214,883 (249) | Rs 119,361 | 980 / 53.3% / Rs 62,034 (387) | Rs 49,838 |
| RF60 | 549 / 53.4% / Rs 97,675 (520) | Rs 59,176 | 538 / 51.7% / Rs 12,009 (829) | Rs 18,517 |
| RT70 | 937 / 57.1% / Rs 286,665 (132) | Rs 160,025 | 1,157 / 55.8% / Rs 192,061 (210) | Rs 128,965 |
| RT85 | 766 / 58.0% / Rs 242,398 (303) | Rs 141,864 | 939 / 56.1% / Rs 122,257 (428) | Rs 74,638 |
| CH55 | 778 / 55.7% / Rs 184,186 (291) | Rs 126,336 | 1,012 / 54.5% / Rs 107,884 (355) | Rs 94,492 |
| CH62 | 967 / 57.8% / Rs 328,969 (102) | Rs 181,665 | 1,255 / 55.6% / Rs 214,904 (112) | Rs 139,331 |

## 9 - 13 Nov 2026 paper trades (descriptive; the engine's stores end 19 Oct 2026)

58 R51 / R72 paper trades rebuilt from the phone's diagnostics (24 won, -Rs 54,729 paper net; no paper trades on 10 Nov, which was not recorded). Filter values computable for 19 (NIFTY / BANKNIFTY index) + 27 (FINNIFTY / MIDCPNIFTY near future, a proxy); 12 SENSEX trades not computable. Weekdays without a recording since 15 Oct: 2026-10-20, 2026-11-10.

| arm : variant | computable | skipped (won) | skipped paper net | kept (won) | kept paper net |
|---|---|---|---|---|---|
| R72:ADX15 | 23 | 1 (0) | -Rs 4,486 | 22 (10) | -Rs 18,666 |
| R51:ADX15 | 23 | 2 (0) | -Rs 6,769 | 21 (11) | -Rs 9,430 |
| R72:ADX20 | 23 | 6 (1) | -Rs 20,002 | 17 (9) | -Rs 3,150 |
| R51:ADX20 | 23 | 7 (2) | -Rs 16,891 | 16 (9) | Rs 692 |
| R72:ADX25 | 23 | 9 (2) | -Rs 22,520 | 14 (8) | -Rs 632 |
| R51:ADX25 | 23 | 8 (2) | -Rs 18,598 | 15 (9) | Rs 2,399 |
| R72:ER6_20 | 23 | 3 (1) | -Rs 11,959 | 20 (9) | -Rs 11,193 |
| R51:ER6_20 | 23 | 5 (2) | -Rs 10,663 | 18 (9) | -Rs 5,536 |
| R72:ER6_30 | 23 | 4 (1) | -Rs 13,734 | 19 (9) | -Rs 9,418 |
| R51:ER6_30 | 23 | 5 (2) | -Rs 10,663 | 18 (9) | -Rs 5,536 |
| R72:ER12_20 | 23 | 7 (2) | -Rs 19,114 | 16 (8) | -Rs 4,038 |
| R51:ER12_20 | 23 | 9 (3) | -Rs 18,585 | 14 (8) | Rs 2,386 |
| R72:ER12_30 | 23 | 10 (3) | -Rs 21,633 | 13 (7) | -Rs 1,519 |
| R51:ER12_30 | 23 | 11 (4) | -Rs 18,183 | 12 (7) | Rs 1,984 |
| R72:TX3 | 23 | 8 (4) | -Rs 5,922 | 15 (6) | -Rs 17,230 |
| R51:TX3 | 23 | 6 (4) | Rs 920 | 17 (7) | -Rs 17,119 |
| R72:TX5 | 23 | 6 (2) | -Rs 6,861 | 17 (8) | -Rs 16,291 |
| R51:TX5 | 23 | 3 (1) | -Rs 3,245 | 20 (10) | -Rs 12,954 |
| R72:TX7 | 23 | 3 (1) | -Rs 2,868 | 20 (9) | -Rs 20,284 |
| R51:TX7 | 23 | 0 (0) | Rs 0 | 23 (11) | -Rs 16,199 |
| R72:RF40 | 23 | 9 (7) | Rs 8,403 | 14 (3) | -Rs 31,555 |
| R51:RF40 | 23 | 9 (7) | Rs 8,994 | 14 (4) | -Rs 25,193 |
| R72:RF60 | 23 | 11 (8) | Rs 6,940 | 12 (2) | -Rs 30,092 |
| R51:RF60 | 23 | 12 (8) | Rs 6,331 | 11 (3) | -Rs 22,530 |
| R72:RT70 | 23 | 8 (6) | Rs 7,124 | 15 (4) | -Rs 30,276 |
| R51:RT70 | 23 | 9 (6) | Rs 5,924 | 14 (5) | -Rs 22,123 |
| R72:RT85 | 23 | 9 (6) | Rs 5,067 | 14 (4) | -Rs 28,219 |
| R51:RT85 | 23 | 9 (6) | Rs 5,924 | 14 (5) | -Rs 22,123 |
| R72:CH55 | 23 | 4 (3) | -Rs 189 | 19 (7) | -Rs 22,963 |
| R51:CH55 | 23 | 2 (2) | Rs 1,451 | 21 (9) | -Rs 17,650 |
| R72:CH62 | 23 | 2 (1) | -Rs 612 | 21 (9) | -Rs 22,540 |
| R51:CH62 | 23 | 1 (1) | Rs 1,163 | 22 (10) | -Rs 17,362 |

