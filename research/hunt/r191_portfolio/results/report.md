# R191 report: a risk-capped portfolio of the existing trade streams

Written 17 Nov 2026. Pre-registration: `PREREG.md` (commit debe7a4fc, before any portfolio number). Research only;
nothing in the app changes.

## Question

Boss asked for "very good profitable strategies with good winrate ... great and reliable", then "Try more harder".
R190 found no single rule that passes. R191 asks whether a PORTFOLIO of the streams we already have (Night R3,
Liquidity 15+5, R72 + R151 picker), with one-position limits and loss caps that only use what is known at the time,
can make money in every era AND stay inside the account stop (-Rs 40,000 drawdown, -Rs 13,000 a month; account about
Rs 56,000). No new entry signal was invented.

## Method in short

- Streams: R190's priced trades (1 lot, dated Zerodha charges, R190's slippage, real 1-minute option prices). Signal
  source: Night = index close location + 59-stock breadth (option OI build-up as context); Liquidity = the index;
  R72 = the five indices' 5-minute Donchian breaks; VIX skip = India VIX's previous daily close. Options are bought.
- Eight portfolios fixed before any number (P1-P8, PREREG section 4). Thresholds (month halt -Rs 8,000, drawdown brake
  Rs 25,000, last-20 equity-curve filter) set from the account stop and era-B average losses; nothing fitted.
- Timing (PREREG section 3): a cap reads only trades already closed: Night's P&L from the next 15:20, an intraday
  trade's P&L only after that day's close. A look-ahead guard test confirms that randomising every later trade never
  changes an earlier decision. Money is booked on each trade's close day.
- Twins: 2,000 per variant and era; each calendar month's trades (all components together) get one random sign and the
  rules are re-run. BH across m = 4,770 tests.
- Order: era B first (committed; no variant passed, so no "B pick"), then the flag, then eras C and A read once.

## Outcome

**No portfolio passed: 0 of 8 are RELIABLE**, and none passed era B on its own, so no variant was picked before eras C
and A were opened.

- **Diversifying does not fix the drawdown.** Night + Liquidity (P1) made Rs 1.99 lakh in era B and Rs 4.48 lakh in
  era C at 1 lot each, but its worst month was -Rs 25,323 (B) and -Rs 75,107 (C), and its drawdown Rs 47,794 / Rs 1.05
  lakh. The two streams lose in the same months often enough that combining them adds the losses up.
- **A month halt caps the month, not the drawdown.** Halting at -Rs 8,000 (P4) kept every era-B month above -Rs 13,000
  (worst -Rs 11,887) but era C still had a -Rs 16,178 month (losses land after the halt). The era-B drawdown got WORSE
  (Rs 60,100 vs Rs 34,757 without the halt, P3) because the halt also skips the recovery trades, and era-B profit fell
  from Rs 1.10 lakh to Rs 53,502 and lost its top-5 robustness.
- **The drawdown brake (P5) and the VIX skip (P8)** keep months near the line (P8: worst month -Rs 11,887 in B and
  -Rs 12,454 in C, the only variant inside the month stop in both) but drawdowns stay Rs 53k-73k, and P8's era-B profit
  is only Rs 14,248 and negative once its top 5 trades are removed.
- **The equity-curve filter (P6, P7) hurt.** It switched streams off after losing runs and missed the rebounds: P6 lost
  Rs 1,218 in era B; P7 (with the R72 picker) lost Rs 1.71 lakh in era B while making Rs 5.73 lakh in era C. The R72
  picker is still a recent-regime stream (era B -Rs 1.85 lakh on its own, R190).
- **Era A** (NIFTY Night only) made Rs 18,086 (66 trades) in every variant but P6 / P7 (Rs 10,875, 57 trades); in each
  case that profit rests on its five best nights (without them -Rs 13,803), as R190 already showed. The month-block twin
  p there is 0.23-0.29 (only 10-11 months).
- **Win rates**: 34-49% of trades in eras B and C (Liquidity's 27-42% pulls Night's 49-55% down), 54-58% in era A;
  winning months 20-61% in era B and 44-88% in era C. The average win is about 1.3-2x the average loss.
- **Significance**: era C beats the twins for most variants (p 0.0005-0.0055; P6 0.11) but era B does not (only P1,
  p 0.015) and era A never does. BH q = 1 for all.

## What it means for Boss

- There is still no strategy that is both profitable in every period and safe for a Rs 56,000 account with a
  -Rs 40,000 / -Rs 13,000 stop. At 1 lot, these streams' drawdowns in a bad stretch (Rs 50k-1 lakh) are larger than
  the account stop allows, however they are combined or capped.
- The last two years (era C) look excellent for almost every combination; 2021-24 does not. Betting that era C
  continues is a regime bet, not a proven edge.
- The most useful risk rule found is the month halt with a VIX skip (P8): the only variant inside the -Rs 13,000 month
  in both B and C. It still breaches the -Rs 40,000 drawdown and is NOT reliable, so it is a paper forward test only.

## Caveats

- Eras C and A were seen by R190 at the stream level; only the portfolio rules are new to them. There is no unseen
  option history.
- Intraday exit minutes are not in R190's cache, so intraday P&L is used only from the next session (conservative), and
  no same-day loss cap could be tested.
- Intraday premiums are not in the cache, so the Rs 56,000 fit is checked only for Night. Holding Liquidity and R72
  positions at the same time could need more than Rs 56,000 on some days.
- 1 lot is the smallest size; "halve the size" rules were replaced by halts.
- The R72 picker's era-C numbers are mostly in-sample for the picker (trained Nov 2024 - Mar 2026).
- Months and drawdowns are booked on each trade's close day (R190 used the entry day), so R190's and R191's worst
  months differ slightly for Night.
- The month-block twin has zero mean (stricter than random-entry twins, which lose the charges), but few blocks: era A
  has only 10-11 months, so its p cannot be small.

## Forward test from 17 Nov 2026 (PREREG section 9)

No B pick, so P4 is followed on paper on new sessions from 17 Nov 2026 (1 lot, same rules), with P8 alongside for
comparison: monthly checkpoints of net, worst month and drawdown against the account stop, and a first judgement after
6 months or 150 portfolio trades, whichever is later. A forward test only reports; it changes no rule and nothing in
the app.

**Outcome: NO - 0 of 8 pre-registered portfolios passed the RELIABLE gate.**

B pick (chosen on era B alone, before eras C and A were run): **none (no variant passed era B)**.

Gate (fixed before any number, PREREG.md section 7): in each era A, B, C: 40+ trades, net > 0, net without the top 5 trades > 0, month-block random-side twin p < 0.05; BH q <= 0.10 with m = 4,770; and in B and C max drawdown < Rs 40,000 and every month > -Rs 13,000.

Era A has Night on NIFTY only (no Liquidity / R72 / BANKNIFTY option data), so in era A every variant is its Night rules on NIFTY. Eras C and A were already seen by R190 at the stream level; these portfolio rules were not.

## All variants at a glance

| variant | era | trades | win % | winning months | net | max DD | worst month | verdict |
|---|---|---|---|---|---|---|---|---|
| P1 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P1 | B | 1597 | 38.5% | 61% | Rs 198,731 | Rs 47,794 | -Rs 25,323 |  |
| P1 | C | 1674 | 43.9% | 84% | Rs 448,019 | Rs 104,864 | -Rs 75,107 |  |
| P2 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P2 | B | 4100 | 40.2% | 47% | Rs 14,216 | Rs 132,420 | -Rs 42,140 |  |
| P2 | C | 4079 | 48.6% | 88% | Rs 1,137,658 | Rs 102,300 | -Rs 72,408 |  |
| P3 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P3 | B | 1435 | 36.2% | 53% | Rs 109,679 | Rs 34,757 | -Rs 18,222 |  |
| P3 | C | 1570 | 43.2% | 80% | Rs 361,110 | Rs 84,372 | -Rs 65,867 |  |
| P4 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P4 | B | 1199 | 34.7% | 44% | Rs 53,502 | Rs 60,100 | -Rs 11,887 |  |
| P4 | C | 1238 | 42.0% | 68% | Rs 322,743 | Rs 57,749 | -Rs 16,178 |  |
| P5 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P5 | B | 1046 | 35.0% | 47% | Rs 49,741 | Rs 59,244 | -Rs 11,887 |  |
| P5 | C | 1045 | 41.6% | 58% | Rs 259,358 | Rs 56,902 | -Rs 13,277 |  |
| P6 | A | 57 | 54.4% | 40% | Rs 10,875 | Rs 12,302 | -Rs 7,647 | not reliable |
| P6 | B | 625 | 36.3% | 46% | -Rs 1,218 | Rs 40,816 | -Rs 9,783 |  |
| P6 | C | 756 | 43.9% | 50% | Rs 176,494 | Rs 41,151 | -Rs 12,019 |  |
| P7 | A | 57 | 54.4% | 40% | Rs 10,875 | Rs 12,302 | -Rs 7,647 | not reliable |
| P7 | B | 1195 | 35.6% | 20% | -Rs 170,900 | Rs 170,900 | -Rs 12,933 |  |
| P7 | C | 1712 | 47.4% | 44% | Rs 573,458 | Rs 86,135 | -Rs 23,561 |  |
| P8 | A | 66 | 57.6% | 45% | Rs 18,086 | Rs 15,034 | -Rs 8,348 | not reliable |
| P8 | B | 1063 | 33.8% | 50% | Rs 14,248 | Rs 72,852 | -Rs 11,887 |  |
| P8 | C | 1152 | 41.4% | 72% | Rs 323,014 | Rs 53,328 | -Rs 12,454 |  |

## P1: Night book + Liquidity, 1 lot each, no caps

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2939 |
| B | 1597 | 38.5% | 61% (22/36) | Rs 198,731 | Rs 1,751 / -Rs 895 | Rs 130,462 | Rs 47,794 | -Rs 25,323 | 0.0155 |
| C | 1674 | 43.9% | 84% (21/25) | Rs 448,019 | Rs 2,315 / -Rs 1,335 | Rs 354,914 | Rs 104,864 | -Rs 75,107 | 0.0040 |

**Verdict: NOT RELIABLE.** BH p 0.235, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.294 >= 0.05; era B: max drawdown Rs 47,794 >= Rs 40k; era B: worst month Rs -25,323 <= -Rs 13k; era C: max drawdown Rs 104,864 >= Rs 40k; era C: worst month Rs -75,107 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 498 | 55.0% | Rs 124,990 | -Rs 25,256 | Rs 34,960 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| C | Night (R3) book | 351 | 53.0% | Rs 192,726 | -Rs 37,286 | Rs 80,780 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |

## P2: Night book + Liquidity + R72 picker, no caps

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2714 |
| B | 4100 | 40.2% | 47% (17/36) | Rs 14,216 | Rs 1,263 / -Rs 845 | -Rs 54,984 | Rs 132,420 | -Rs 42,140 | 0.4673 |
| C | 4079 | 48.6% | 88% (22/25) | Rs 1,137,658 | Rs 2,087 / -Rs 1,430 | Rs 1,044,553 | Rs 102,300 | -Rs 72,408 | 0.0005 |

**Verdict: NOT RELIABLE.** BH p 0.46, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.271 >= 0.05; era B: net without the top 5 trades -54,984 <= 0; era B: twin p 0.467 >= 0.05; era B: max drawdown Rs 132,420 >= Rs 40k; era B: worst month Rs -42,140 <= -Rs 13k; era C: max drawdown Rs 102,300 >= Rs 40k; era C: worst month Rs -72,408 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| A | R72 + R151 picker | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 498 | 55.0% | Rs 124,990 | -Rs 25,256 | Rs 34,960 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| B | R72 + R151 picker | 2503 | 41.4% | -Rs 184,515 | -Rs 37,319 | Rs 247,894 |
| C | Night (R3) book | 351 | 53.0% | Rs 192,726 | -Rs 37,286 | Rs 80,780 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |
| C | R72 + R151 picker | 2405 | 51.9% | Rs 689,639 | -Rs 19,993 | Rs 110,062 |

## P3: one Night a night + Liquidity

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2939 |
| B | 1435 | 36.2% | 53% (19/36) | Rs 109,679 | Rs 1,665 / -Rs 824 | Rs 43,715 | Rs 34,757 | -Rs 18,222 | 0.0670 |
| C | 1570 | 43.2% | 80% (20/25) | Rs 361,110 | Rs 2,196 / -Rs 1,264 | Rs 269,268 | Rs 84,372 | -Rs 65,867 | 0.0035 |

**Verdict: NOT RELIABLE.** BH p 0.237, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.294 >= 0.05; era B: twin p 0.067 >= 0.05; era B: worst month Rs -18,222 <= -Rs 13k; era C: max drawdown Rs 84,372 >= Rs 40k; era C: worst month Rs -65,867 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 336 | 53.0% | Rs 35,938 | -Rs 12,897 | Rs 27,456 |
| B | Liquidity 15+5 | 1099 | 31.0% | Rs 73,741 | -Rs 13,213 | Rs 51,118 |
| C | Night (R3) book | 247 | 52.2% | Rs 105,817 | -Rs 28,046 | Rs 60,288 |
| C | Liquidity 15+5 | 1323 | 41.5% | Rs 255,293 | -Rs 37,821 | Rs 61,062 |

## P4: P3 + month halt at -Rs 8k

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2329 |
| B | 1199 | 34.7% | 44% (16/36) | Rs 53,502 | Rs 1,621 / -Rs 793 | -Rs 12,059 | Rs 60,100 | -Rs 11,887 | 0.1174 |
| C | 1238 | 42.0% | 68% (17/25) | Rs 322,743 | Rs 2,313 / -Rs 1,226 | Rs 230,901 | Rs 57,749 | -Rs 16,178 | 0.0055 |

**Verdict: NOT RELIABLE.** BH p 0.207, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.233 >= 0.05; era B: net without the top 5 trades -12,059 <= 0; era B: twin p 0.117 >= 0.05; era B: max drawdown Rs 60,100 >= Rs 40k; era C: max drawdown Rs 57,749 >= Rs 40k; era C: worst month Rs -16,178 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 274 | 52.2% | Rs 15,695 | -Rs 12,897 | Rs 40,140 |
| B | Liquidity 15+5 | 925 | 29.5% | Rs 37,807 | -Rs 10,751 | Rs 67,137 |
| C | Night (R3) book | 195 | 53.3% | Rs 118,872 | -Rs 16,152 | Rs 35,094 |
| C | Liquidity 15+5 | 1043 | 39.9% | Rs 203,870 | -Rs 18,282 | Rs 44,480 |

## P5: P4 + drawdown brake at -Rs 25k

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2269 |
| B | 1046 | 35.0% | 47% (15/32) | Rs 49,741 | Rs 1,602 / -Rs 789 | -Rs 15,821 | Rs 59,244 | -Rs 11,887 | 0.1204 |
| C | 1045 | 41.6% | 58% (14/24) | Rs 259,358 | Rs 2,302 / -Rs 1,216 | Rs 167,516 | Rs 56,902 | -Rs 13,277 | 0.0050 |

**Verdict: NOT RELIABLE.** BH p 0.199, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.227 >= 0.05; era B: net without the top 5 trades -15,821 <= 0; era B: twin p 0.120 >= 0.05; era B: max drawdown Rs 59,244 >= Rs 40k; era C: max drawdown Rs 56,902 >= Rs 40k; era C: worst month Rs -13,277 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 231 | 52.8% | Rs 13,181 | -Rs 12,897 | Rs 25,426 |
| B | Liquidity 15+5 | 815 | 29.9% | Rs 36,561 | -Rs 10,751 | Rs 68,384 |
| C | Night (R3) book | 153 | 51.0% | Rs 66,456 | -Rs 15,153 | Rs 28,049 |
| C | Liquidity 15+5 | 892 | 40.0% | Rs 192,902 | -Rs 18,282 | Rs 54,685 |

## P6: P4 + equity-curve filter (last 20 > 0)

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 57 | 54.4% | 40% (4/10) | Rs 10,875 | Rs 2,232 / -Rs 2,243 | -Rs 18,149 | Rs 12,302 | -Rs 7,647 | 0.2754 |
| B | 625 | 36.3% | 46% (16/35) | -Rs 1,218 | Rs 1,632 / -Rs 934 | -Rs 55,080 | Rs 40,816 | -Rs 9,783 | 0.3458 |
| C | 756 | 43.9% | 50% (12/24) | Rs 176,494 | Rs 2,215 / -Rs 1,318 | Rs 93,648 | Rs 41,151 | -Rs 12,019 | 0.1104 |

**Verdict: NOT RELIABLE.** BH p 0.352, q 1. Failed: era A: net without the top 5 trades -18,149 <= 0; era A: twin p 0.275 >= 0.05; era B: net -1,218 <= 0; era B: net without the top 5 trades -55,080 <= 0; era B: twin p 0.346 >= 0.05; era B: max drawdown Rs 40,816 >= Rs 40k; era C: twin p 0.110 >= 0.05; era C: max drawdown Rs 41,151 >= Rs 40k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 57 | 54.4% | Rs 10,875 | -Rs 7,647 | Rs 12,302 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 189 | 50.8% | -Rs 13,718 | -Rs 9,398 | Rs 31,555 |
| B | Liquidity 15+5 | 436 | 30.1% | Rs 12,500 | -Rs 8,958 | Rs 35,487 |
| C | Night (R3) book | 158 | 52.5% | Rs 41,921 | -Rs 10,218 | Rs 28,525 |
| C | Liquidity 15+5 | 598 | 41.6% | Rs 134,573 | -Rs 11,818 | Rs 44,671 |

## P7: one Night a night + Liquidity + R72 picker + month halt + equity-curve filter

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 57 | 54.4% | 40% (4/10) | Rs 10,875 | Rs 2,232 / -Rs 2,243 | -Rs 18,149 | Rs 12,302 | -Rs 7,647 | 0.2759 |
| B | 1195 | 35.6% | 20% (7/35) | -Rs 170,900 | Rs 1,184 / -Rs 878 | -Rs 216,123 | Rs 170,900 | -Rs 12,933 | 0.9730 |
| C | 1712 | 47.4% | 44% (11/25) | Rs 573,458 | Rs 2,244 / -Rs 1,383 | Rs 488,889 | Rs 86,135 | -Rs 23,561 | 0.0035 |

**Verdict: NOT RELIABLE.** BH p 0.975, q 1. Failed: era A: net without the top 5 trades -18,149 <= 0; era A: twin p 0.276 >= 0.05; era B: net -170,900 <= 0; era B: net without the top 5 trades -216,123 <= 0; era B: twin p 0.973 >= 0.05; era B: max drawdown Rs 170,900 >= Rs 40k; era C: max drawdown Rs 86,135 >= Rs 40k; era C: worst month Rs -23,561 <= -Rs 13k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 57 | 54.4% | Rs 10,875 | -Rs 7,647 | Rs 12,302 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| A | R72 + R151 picker | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 148 | 48.6% | -Rs 41,383 | -Rs 9,398 | Rs 41,619 |
| B | Liquidity 15+5 | 357 | 27.4% | -Rs 21,746 | -Rs 8,358 | Rs 37,774 |
| B | R72 + R151 picker | 690 | 37.1% | -Rs 107,771 | -Rs 13,896 | Rs 120,536 |
| C | Night (R3) book | 135 | 51.9% | Rs 41,495 | -Rs 13,204 | Rs 25,641 |
| C | Liquidity 15+5 | 584 | 41.4% | Rs 133,361 | -Rs 11,919 | Rs 47,751 |
| C | R72 + R151 picker | 993 | 50.2% | Rs 398,602 | -Rs 21,301 | Rs 61,847 |

## P8: P4 + skip Night / Liquidity on high-VIX days

| era | trades | win % | winning months | net | avg win / avg loss | net without top 5 | max drawdown | worst month | twin p |
|---|---|---|---|---|---|---|---|---|---|
| A | 66 | 57.6% | 45% (5/11) | Rs 18,086 | Rs 2,215 / -Rs 2,361 | -Rs 13,803 | Rs 15,034 | -Rs 8,348 | 0.2284 |
| B | 1063 | 33.8% | 50% (18/36) | Rs 14,248 | Rs 1,513 / -Rs 751 | -Rs 44,543 | Rs 72,852 | -Rs 11,887 | 0.2189 |
| C | 1152 | 41.4% | 72% (18/25) | Rs 323,014 | Rs 2,349 / -Rs 1,181 | Rs 231,172 | Rs 53,328 | -Rs 12,454 | 0.0020 |

**Verdict: NOT RELIABLE.** BH p 0.221, q 1. Failed: era A: net without the top 5 trades -13,803 <= 0; era A: twin p 0.228 >= 0.05; era B: net without the top 5 trades -44,543 <= 0; era B: twin p 0.219 >= 0.05; era B: max drawdown Rs 72,852 >= Rs 40k; era C: max drawdown Rs 53,328 >= Rs 40k; BH q 1 > 0.1.

Per component (trades the portfolio took):

| era | component | trades | win % | net | worst month | max drawdown |
|---|---|---|---|---|---|---|
| A | Night (R3) book | 66 | 57.6% | Rs 18,086 | -Rs 8,348 | Rs 15,034 |
| A | Liquidity 15+5 | 0 (no data or none taken) | | | | |
| B | Night (R3) book | 248 | 51.2% | Rs 9,403 | -Rs 8,072 | Rs 40,140 |
| B | Liquidity 15+5 | 815 | 28.5% | Rs 4,845 | -Rs 9,834 | Rs 59,053 |
| C | Night (R3) book | 182 | 54.4% | Rs 138,807 | -Rs 10,600 | Rs 20,618 |
| C | Liquidity 15+5 | 970 | 39.0% | Rs 184,206 | -Rs 18,282 | Rs 41,655 |

## Detail: candidates, skips and twins

| variant | era | candidates | skipped by the rules | twin mean | twin sd | p (empirical) | p (normal) |
|---|---|---|---|---|---|---|---|
| P1 | A | 66 | 0 | Rs 69 | Rs 24,922 | 0.2939 | 0.235 |
| P1 | B | 1597 | 0 | Rs 1,432 | Rs 93,656 | 0.0155 | 0.0176 |
| P1 | C | 1674 | 0 | -Rs 978 | Rs 170,329 | 0.0040 | 0.00419 |
| P2 | A | 66 | 0 | -Rs 1,032 | Rs 24,452 | 0.2714 | 0.217 |
| P2 | B | 4100 | 0 | Rs 3,908 | Rs 101,647 | 0.4673 | 0.46 |
| P2 | C | 4079 | 0 | -Rs 5,061 | Rs 334,257 | 0.0005 | 0.000315 |
| P3 | A | 66 | 0 | Rs 693 | Rs 24,297 | 0.2939 | 0.237 |
| P3 | B | 1435 | 0 | Rs 483 | Rs 73,167 | 0.0670 | 0.0678 |
| P3 | C | 1570 | 0 | -Rs 6,145 | Rs 146,868 | 0.0035 | 0.0062 |
| P4 | A | 66 | 0 | Rs 1,832 | Rs 19,896 | 0.2329 | 0.207 |
| P4 | B | 1435 | 236 | -Rs 31,218 | Rs 70,786 | 0.1174 | 0.116 |
| P4 | C | 1570 | 332 | Rs 64,189 | Rs 107,351 | 0.0055 | 0.00801 |
| P5 | A | 66 | 0 | Rs 1,731 | Rs 19,379 | 0.2269 | 0.199 |
| P5 | B | 1435 | 389 | -Rs 20,440 | Rs 57,618 | 0.1204 | 0.112 |
| P5 | C | 1570 | 525 | Rs 18,892 | Rs 81,929 | 0.0050 | 0.00167 |
| P6 | A | 66 | 9 | -Rs 891 | Rs 18,208 | 0.2754 | 0.259 |
| P6 | B | 1435 | 810 | -Rs 19,745 | Rs 48,868 | 0.3458 | 0.352 |
| P6 | C | 1570 | 814 | Rs 75,777 | Rs 80,692 | 0.1104 | 0.106 |
| P7 | A | 66 | 9 | -Rs 684 | Rs 18,029 | 0.2759 | 0.261 |
| P7 | B | 3938 | 2743 | -Rs 60,678 | Rs 56,412 | 0.9730 | 0.975 |
| P7 | C | 3975 | 2263 | Rs 119,372 | Rs 166,627 | 0.0035 | 0.00321 |
| P8 | A | 66 | 0 | Rs 1,503 | Rs 19,429 | 0.2284 | 0.197 |
| P8 | B | 1241 | 178 | -Rs 29,692 | Rs 57,267 | 0.2189 | 0.221 |
| P8 | C | 1434 | 282 | Rs 37,711 | Rs 99,281 | 0.0020 | 0.00203 |
