# R176: the Rs 500 step trail (per lot), last year of data

## Update: Rs 1,000 a lot as the initial stop (Amendment B, Boss 15 Nov)

You asked: *"What about 1000 as initial stoploss"*. Same year, same costs, same step trail; only the entry stop is
-Rs 1,000 a lot (used where it is tighter than -15%, which was 95% of trades).

**Much better than Rs 500, but still well below today's rules, and it failed every gate.**

Net over the year (today's rules / Rs 500 stop / **Rs 1,000 stop**):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 1.18 L / -Rs 15k / **-Rs 3k** | Rs 2.67 L / Rs 2k / **Rs 27k** | Rs 4.16 L / Rs 18k / **Rs 55k** |
| R72 Breadth Donchian | Rs 2.98 L / -Rs 1.32 L / **-Rs 31k** | Rs 7.02 L / -Rs 1.53 L / **Rs 47k** | Rs 11.05 L / -Rs 1.80 L / **Rs 1.23 L** |
| R51 Donchian 20 | Rs 2.21 L / -Rs 1.76 L / **-Rs 61k** | Rs 5.85 L / -Rs 2.03 L / **Rs 26k** | Rs 9.47 L / -Rs 2.35 L / **Rs 1.11 L** |
| **All three** | **Rs 6.37 L / -Rs 3.23 L / -Rs 95k** | **Rs 15.54 L / -Rs 3.54 L / Rs 1.00 L** | **Rs 24.67 L / -Rs 3.97 L / Rs 2.89 L** |

With the R151 picker (R72 + R51; Liquidity has none), today's / **Rs 1,000 stop**:

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| R72 + picker | Rs 3.72 L / **Rs 1.26 L** | Rs 7.92 L / **Rs 3.03 L** | Rs 12.13 L / **Rs 4.77 L** |
| R51 + picker | Rs 2.93 L / **Rs 24k** | Rs 6.49 L / **Rs 1.16 L** | Rs 10.04 L / **Rs 2.05 L** |
| **All three** | **Rs 7.83 L / Rs 1.48 L** | **Rs 17.09 L / Rs 4.45 L** | **Rs 26.33 L / Rs 7.38 L** |

- Win rate goes back up to 48% (Rs 500 stop: 35%; today: 45%), 6,281 trades at 1 lot.
- The entry stop now closes 37% of trades (Rs 500: 60%); the step trail closes 39%.
- But wins stay small (average Rs 1,026 at 1 lot vs Rs 1,744 today): the step trail still sells winners on the first
  Rs 500 a lot pull-back. That is probably where most of the gap to today's rules comes from now (not tested
  separately).
- Worst day, all three arms at 1 lot: -Rs 54k today -> -Rs 34k. Max drawdown: Rs 1.59 L today -> Rs 2.52 L.
- Why 1 lot loses but 3 lots make money with the same per-lot rule: Zerodha's brokerage is a flat Rs 20 an order, so
  about Rs 2.5 L of the year's costs (6,281 trades x 2 orders) is the same at any lot size. Today's rules show the same
  effect (Rs 6.37 L at 1 lot, but Rs 24.67 L, not Rs 19 L, at 3 lots).
- Gates: failed on every arm and lot (net below today's rules in every period; random-exit twins p = 1.0; BH q = 1.0 with
  3,988 tests; drawdown and month limits broken). This test was chosen after seeing the Rs 500 results, so it is weaker
  evidence, and the year's final months were read a second time.
- My guess from these numbers: a wider gap under each step might hurt less. That is a new idea to pre-register and
  test, not something this study shows.

## The first test: Rs 500 a lot initial stop (Amendment A)
**Boss, in short (15 Nov 2026).** I replayed the last year the data covers, **20 Oct 2025 - 19 Oct 2026** (247 trading
days), on Liquidity 15+5, R72 Breadth Donchian and R51 Donchian 20 + Supertrend. I ran each at 1, 2 and 3 lots, with
and without your new stop rule, and with and without the R151 picker. All money is after real Zerodha charges and
slippage; every stop is sold as a real stop order, below its level, not at it.

The rule: a stop at -Rs 500 a lot at entry; once the trade is +Rs 1,000 a lot, the stop sits Rs 500 a lot under each
Rs 500-a-lot step of the best profit; every existing exit stays.

**It failed every gate on every arm at every lot size.** Today's rules made money on all three arms; with the new rule,
R72 and R51 lose money, and Liquidity makes almost nothing.

Net over the year (today's rules -> with the new rule):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 1.18 L -> **-Rs 15k** | Rs 2.67 L -> **Rs 2k** | Rs 4.16 L -> **Rs 18k** |
| R72 Breadth Donchian | Rs 2.98 L -> **-Rs 1.32 L** | Rs 7.02 L -> **-Rs 1.53 L** | Rs 11.05 L -> **-Rs 1.80 L** |
| R51 Donchian 20 | Rs 2.21 L -> **-Rs 1.76 L** | Rs 5.85 L -> **-Rs 2.03 L** | Rs 9.47 L -> **-Rs 2.35 L** |
| **All three** | **Rs 6.37 L -> -Rs 3.23 L** | **Rs 15.54 L -> -Rs 3.54 L** | **Rs 24.67 L -> -Rs 3.97 L** |

With the R151 picker on R72 and R51, as live today (Liquidity has no picker):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| R72 + picker | Rs 3.72 L -> **Rs 13k** | Rs 7.92 L -> **Rs 76k** | Rs 12.13 L -> **Rs 1.39 L** |
| R51 + picker | Rs 2.93 L -> **-Rs 62k** | Rs 6.49 L -> **-Rs 56k** | Rs 10.04 L -> **-Rs 51k** |
| **All three** | **Rs 7.83 L -> -Rs 65k** | **Rs 17.09 L -> Rs 22k** | **Rs 26.33 L -> Rs 1.07 L** |

**Why it loses.**
- The Rs 500 entry stop is only about 7-8 premium points. It was the exit on 60% of all trades (3,838 of 6,348, all arms,
  1 lot), against today's -15% stop, which is about twice as far away.
- The win rate falls from 45-46% to 34-36%.
- Losses get smaller (average Rs 1,230 -> Rs 637 at 1 lot), but wins shrink about as much (Rs 1,744 -> Rs 1,041),
  because the step trail sells good trades on the first Rs 500 pull-back, and there are far fewer wins.
- The same per-lot rule scales with lots, so more lots do not fix it: R72 and R51 lose at 1, 2 and 3 lots.

**What it does do.**
- **Smaller single bad days.** Worst day for all three arms: 1 lot -Rs 54k -> -Rs 24k; 3 lots -Rs 1.59 L -> -Rs 69k.
- **But the losses keep adding up.** Max drawdown, all three arms at 1 lot: Rs 1.59 L -> **Rs 3.50 L**; at 3 lots
  Rs 4.33 L -> Rs 6.85 L. Far past the account stop rule (-Rs 40k drawdown / -Rs 13k a month).
- Note: today's rules also break the account stop rule at these lots on this year (e.g. all three at 1 lot, worst month
  -Rs 1.29 L), so that gate alone would fail them too.

**Honest notes.**
- You asked for the last year only (Amendment A, written before any number). One year is a short sample, and Apr - Oct
  2026 has been read by many studies already.
- The random-exit twins (500 per test) all beat the rule (p = 1.0 everywhere; BH q = 1.0 with 3,979 tests counted). In
  this harness the twins re-sell only the trades the rule cut early, mostly losing trades, so they do well; R152's
  stop-only rule showed the same.
- This rule is being built anyway, by your choice. Nothing in the app depends on this study. If you want to keep it,
  one option is to use it only with the R72 picker, the one place it stayed positive at every lot size this year
  (still far below today's rules).

## Window, data and checks

- Window (Amendment A, Boss 15 Nov: "Test on only last year of data"): **20 Oct 2025 - 19 Oct 2026**, 247 sessions; early part 110, final 133, last 6 months 126. The lake ends on 19 Oct 2026.
- Parity, today's rules at 1 lot, 1 Apr - 13 Oct 2026: Liquidity 15+5 357 / Rs 11,155 (R172 357 / Rs 11,155); R72 Breadth Donchian 1200 / Rs 170,336 (R172 1200 / Rs 170,336), picker 651 / Rs 220,118 (R172 651 / Rs 220,118); R51 Donchian 20 + Supertrend 1668 / Rs 131,546 (R172 1668 / Rs 131,546), picker 692 / Rs 160,101 (R172 692 / Rs 160,101). The engine with the rule off priced every candidate exactly as R101.
- Signal source: the index for every arm (Liquidity: the index's liquidity levels; R72: Donchian 20 breaks on 4 of the 5 indices' 5-minute bars; R51: Donchian 20 on the index's 5-minute bars, kept when Supertrend (10, 3) agrees). Only the option's stop changes.
- Costs: dated Zerodha charges on the whole quantity; R17 slippage (from the 5-minute volume per position). Every stop of the rule is sold as a stop (x0.999 minus twice the half-spread; a gap fills at the minute's open), not at its level.
- Honesty: Apr - Oct 2026 has been read by many studies (R51 - R173); the early part (Oct 2025 - Mar 2026) lies inside the years R51's design used. One year is a short sample. The pickers were fitted on trades up to Mar 2026, so picker numbers in the early part are in-sample.
- Variants: A = today's rules; S = + Rs 500 step trail (per lot); B = A + R151 picker; B+S = picker + step trail (Liquidity has no picker).

## Gates (Amendment A; S vs A at the same lots)

BH counts m = 3979 tests (3970 earlier + 9 R176).

| arm @ lots | passed | twin p | BH q (m = 3,979) | q within R176 | failed gates |
|---|---|---|---|---|---|
| Liquidity 15+5 @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in final, six, year |
| Liquidity 15+5 @ 2 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| Liquidity 15+5 @ 3 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 2 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R72 Breadth Donchian @ 3 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 1 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 2 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |
| R51 Donchian 20 + Supertrend @ 3 | **no** | 1.0 | 1.0 | 1.0 | not positive in every period; net not above today's rules (early, final, year); exit-twin BH q 1.00 > 0.10; outside the account stop rule (-Rs 40k drawdown / -Rs 13k month) in early, final, six, year |

Twins changed (trades the rule closed earlier than A): LIQ@1 497 (twin mean Rs 237,345), LIQ@2 497 (twin mean Rs 509,163), LIQ@3 497 (twin mean Rs 779,623), R72@1 2176 (twin mean Rs 460,708), R72@2 2176 (twin mean Rs 1,031,367), R72@3 2176 (twin mean Rs 1,605,826), R51@1 2949 (twin mean Rs 498,964), R51@2 2949 (twin mean Rs 1,139,417), R51@3 2949 (twin mean Rs 1,797,498).

## 1 lot

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 42% | **Rs 113,873** | Rs 2,142 / -Rs 935 | 0 / 0 / 316 (6) | -Rs 5,535 | Dec 25 -Rs 4,980 | -Rs 16,913 |
| Liquidity 15+5 | S | 319 | 39% | **Rs 34,035** | Rs 1,220 / -Rs 611 | 157 / 71 / 91 (0) | -Rs 4,191 | Mar 26 -Rs 7,921 | -Rs 16,292 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 128,541** | Rs 1,792 / -Rs 1,341 | 0 / 0 / 1056 (166) | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S | 1125 | 35% | **-Rs 98,614** | Rs 941 / -Rs 651 | 688 / 369 / 68 (2) | -Rs 11,651 | Mar 26 -Rs 55,863 | -Rs 110,257 |
| R72 Breadth Donchian | B | 452 | 55% | **Rs 200,162** | Rs 2,153 / -Rs 1,673 | 0 / 0 / 452 (70) | -Rs 25,837 | Dec 25 -Rs 1,721 | -Rs 34,940 |
| R72 Breadth Donchian | B+S | 466 | 39% | **Rs 575** | Rs 1,029 / -Rs 652 | 273 / 181 / 12 (0) | -Rs 6,646 | Mar 26 -Rs 37,924 | -Rs 39,818 |
| R51 Donchian 20 + Supertrend | A | 1370 | 45% | **Rs 86,280** | Rs 1,744 / -Rs 1,326 | 0 / 0 / 1370 (222) | -Rs 28,942 | Dec 25 -Rs 51,699 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S | 1450 | 36% | **-Rs 93,267** | Rs 957 / -Rs 644 | 888 / 474 / 88 (2) | -Rs 12,227 | Dec 25 -Rs 51,659 | -Rs 103,757 |
| R51 Donchian 20 + Supertrend | B | 691 | 51% | **Rs 156,237** | Rs 1,905 / -Rs 1,537 | 0 / 0 / 691 (112) | -Rs 24,757 | Dec 25 -Rs 904 | -Rs 29,931 |
| R51 Donchian 20 + Supertrend | B+S | 752 | 38% | **-Rs 12,953** | Rs 1,019 / -Rs 661 | 448 / 270 / 34 (0) | -Rs 10,390 | Mar 26 -Rs 36,957 | -Rs 39,771 |
| All three arms | A | 2742 | 45% | **Rs 328,694** | Rs 1,805 / -Rs 1,284 | 0 / 0 / 2742 (394) | -Rs 51,650 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three arms | S | 2894 | 36% | **-Rs 157,845** | Rs 982 / -Rs 643 | 1733 / 914 / 247 (4) | -Rs 23,967 | Mar 26 -Rs 109,865 | -Rs 185,109 |
| All three arms | B | 1459 | 51% | **Rs 470,271** | Rs 2,032 / -Rs 1,423 | 0 / 0 / 1459 (188) | -Rs 43,996 | Dec 25 -Rs 7,604 | -Rs 60,566 |
| All three arms | B+S | 1537 | 39% | **Rs 21,658** | Rs 1,065 / -Rs 648 | 878 / 522 / 137 (0) | -Rs 14,562 | Mar 26 -Rs 82,803 | -Rs 89,450 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 37% | **Rs 11,155** | Rs 1,762 / -Rs 997 | 0 / 0 / 357 (12) | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S | 358 | 32% | **-Rs 44,706** | Rs 864 / -Rs 599 | 199 / 70 / 89 (0) | -Rs 4,550 | Apr 26 -Rs 18,018 | -Rs 48,135 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 170,336** | Rs 1,748 / -Rs 1,217 | 0 / 0 / 1200 (173) | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S | 1249 | 34% | **-Rs 26,830** | Rs 1,197 / -Rs 637 | 774 / 349 / 126 (0) | -Rs 10,638 | Apr 26 -Rs 33,471 | -Rs 58,560 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 170,735** | Rs 1,920 / -Rs 1,299 | 0 / 0 / 602 (78) | -Rs 21,301 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S | 636 | 33% | **Rs 14,083** | Rs 1,343 / -Rs 638 | 398 / 188 / 50 (0) | -Rs 9,265 | Apr 26 -Rs 12,955 | -Rs 30,611 |
| R51 Donchian 20 + Supertrend | A | 1668 | 45% | **Rs 131,546** | Rs 1,648 / -Rs 1,197 | 0 / 0 / 1668 (218) | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S | 1751 | 34% | **-Rs 74,054** | Rs 1,089 / -Rs 635 | 1069 / 503 / 179 (0) | -Rs 11,097 | Sep 26 -Rs 37,989 | -Rs 91,736 |
| R51 Donchian 20 + Supertrend | B | 652 | 50% | **Rs 143,945** | Rs 1,733 / -Rs 1,320 | 0 / 0 / 652 (85) | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 710 | 35% | **-Rs 39,803** | Rs 1,049 / -Rs 657 | 444 / 218 / 48 (0) | -Rs 8,275 | Sep 26 -Rs 27,648 | -Rs 55,846 |
| All three arms | A | 3225 | 44% | **Rs 313,037** | Rs 1,697 / -Rs 1,179 | 0 / 0 / 3225 (403) | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three arms | S | 3358 | 34% | **-Rs 145,590** | Rs 1,106 / -Rs 632 | 2042 / 922 / 394 (0) | -Rs 19,404 | Apr 26 -Rs 61,234 | -Rs 164,306 |
| All three arms | B | 1611 | 47% | **Rs 325,835** | Rs 1,811 / -Rs 1,228 | 0 / 0 / 1611 (175) | -Rs 39,870 | Apr 26 -Rs 29,430 | -Rs 68,921 |
| All three arms | B+S | 1704 | 34% | **-Rs 70,426** | Rs 1,120 / -Rs 637 | 1041 / 476 / 187 (0) | -Rs 17,224 | Apr 26 -Rs 52,485 | -Rs 95,671 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | 0 / 0 / 346 (13) | -Rs 11,405 | Oct 26 -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | S | 347 | 34% | **-Rs 40,776** | Rs 823 / -Rs 596 | 190 / 72 / 85 (0) | -Rs 4,550 | Oct 26 -Rs 13,820 | -Rs 40,776 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | 0 / 0 / 1116 (165) | -Rs 22,571 | Jun 26 -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | S | 1164 | 34% | **-Rs 9,586** | Rs 1,184 / -Rs 630 | 712 / 330 / 122 (0) | -Rs 10,638 | Jun 26 -Rs 20,739 | -Rs 34,845 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | 0 / 0 / 537 (68) | -Rs 17,394 | May 26 -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+S | 561 | 34% | **Rs 21,655** | Rs 1,358 / -Rs 632 | 349 / 167 / 45 (0) | -Rs 9,265 | Aug 26 -Rs 7,665 | -Rs 28,931 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | 0 / 0 / 1584 (214) | -Rs 21,578 | Jun 26 -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | S | 1672 | 35% | **-Rs 62,603** | Rs 1,076 / -Rs 630 | 1017 / 482 / 173 (0) | -Rs 8,796 | Sep 26 -Rs 37,989 | -Rs 93,913 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | 0 / 0 / 598 (79) | -Rs 14,642 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 657 | 35% | **-Rs 27,669** | Rs 1,060 / -Rs 648 | 410 / 201 / 46 (0) | -Rs 6,980 | Sep 26 -Rs 27,648 | -Rs 58,541 |
| All three arms | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | 0 / 0 / 3046 (392) | -Rs 53,710 | Jun 26 -Rs 96,444 | -Rs 139,134 |
| All three arms | S | 3183 | 34% | **-Rs 112,964** | Rs 1,088 / -Rs 626 | 1919 / 884 / 380 (0) | -Rs 16,530 | Jun 26 -Rs 60,942 | -Rs 127,296 |
| All three arms | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | 0 / 0 / 1481 (160) | -Rs 39,870 | Jun 26 -Rs 21,248 | -Rs 59,893 |
| All three arms | B+S | 1565 | 34% | **-Rs 46,789** | Rs 1,113 / -Rs 630 | 949 / 440 / 176 (0) | -Rs 14,942 | Sep 26 -Rs 27,440 | -Rs 95,671 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 39% | **Rs 117,923** | Rs 1,935 / -Rs 968 | 0 / 0 / 688 (20) | -Rs 11,405 | Apr 26 -Rs 37,821 | -Rs 61,062 |
| Liquidity 15+5 | S | 692 | 36% | **-Rs 14,723** | Rs 1,034 / -Rs 603 | 365 / 146 / 181 (0) | -Rs 4,550 | Apr 26 -Rs 18,018 | -Rs 64,009 |
| R72 Breadth Donchian | A | 2281 | 46% | **Rs 298,253** | Rs 1,763 / -Rs 1,279 | 0 / 0 / 2281 (345) | -Rs 25,837 | Dec 25 -Rs 72,605 | -Rs 86,529 |
| R72 Breadth Donchian | S | 2403 | 34% | **-Rs 132,155** | Rs 1,065 / -Rs 643 | 1481 / 727 / 195 (2) | -Rs 11,651 | Mar 26 -Rs 55,863 | -Rs 168,817 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 371,717** | Rs 2,019 / -Rs 1,449 | 0 / 0 / 1062 (150) | -Rs 25,837 | May 26 -Rs 20,632 | -Rs 34,940 |
| R72 Breadth Donchian | B+S | 1110 | 36% | **Rs 12,516** | Rs 1,196 / -Rs 643 | 677 / 371 / 62 (0) | -Rs 9,265 | Mar 26 -Rs 37,924 | -Rs 70,430 |
| R51 Donchian 20 + Supertrend | A | 3084 | 45% | **Rs 221,238** | Rs 1,692 / -Rs 1,259 | 0 / 0 / 3084 (452) | -Rs 28,942 | Jun 26 -Rs 54,587 | -Rs 94,176 |
| R51 Donchian 20 + Supertrend | S | 3253 | 35% | **-Rs 175,977** | Rs 1,024 / -Rs 639 | 1992 / 993 / 268 (2) | -Rs 12,227 | Dec 25 -Rs 51,659 | -Rs 186,467 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 293,395** | Rs 1,814 / -Rs 1,438 | 0 / 0 / 1361 (204) | -Rs 24,757 | Jun 26 -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+S | 1483 | 36% | **-Rs 62,292** | Rs 1,032 / -Rs 659 | 910 / 491 / 82 (0) | -Rs 10,390 | Mar 26 -Rs 36,957 | -Rs 89,111 |
| All three arms | A | 6053 | 45% | **Rs 637,414** | Rs 1,744 / -Rs 1,230 | 0 / 0 / 6053 (817) | -Rs 53,710 | Dec 25 -Rs 129,283 | -Rs 159,077 |
| All three arms | S | 6348 | 35% | **-Rs 322,855** | Rs 1,041 / -Rs 637 | 3838 / 1866 / 644 (4) | -Rs 23,967 | Mar 26 -Rs 109,865 | -Rs 350,119 |
| All three arms | B | 3111 | 49% | **Rs 783,034** | Rs 1,910 / -Rs 1,318 | 0 / 0 / 3111 (374) | -Rs 43,996 | Apr 26 -Rs 29,430 | -Rs 76,847 |
| All three arms | B+S | 3285 | 36% | **-Rs 64,499** | Rs 1,087 / -Rs 642 | 1952 / 1008 / 325 (0) | -Rs 17,224 | Mar 26 -Rs 82,803 | -Rs 182,768 |

### Month by month, the whole year (1 lot)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 611 | Rs 2,638 | -Rs 4,980 | Rs 45,771 | Rs 43,708 | Rs 27,346 | -Rs 37,821 | -Rs 8,832 | -Rs 949 | Rs 33,022 | -Rs 1,605 | Rs 35,620 | -Rs 15,384 | Rs 117,923 |
| Liquidity 15+5 | S | Rs 2,436 | Rs 4,391 | Rs 843 | Rs 3,525 | Rs 30,762 | -Rs 7,921 | -Rs 18,018 | -Rs 12,704 | -Rs 4,737 | Rs 32 | -Rs 5,865 | Rs 6,353 | -Rs 13,820 | -Rs 14,723 |
| R72 Breadth Donchian | A | Rs 22,318 | Rs 8,007 | -Rs 72,605 | Rs 57,857 | Rs 27,329 | Rs 85,635 | -Rs 15,245 | Rs 39,929 | -Rs 40,908 | Rs 64,441 | Rs 21,113 | Rs 29,606 | Rs 70,777 | Rs 298,253 |
| R72 Breadth Donchian | S | Rs 6,556 | -Rs 4,455 | -Rs 49,174 | Rs 19,002 | -Rs 14,679 | -Rs 55,863 | -Rs 33,471 | -Rs 294 | -Rs 20,739 | Rs 24,291 | Rs 9,755 | -Rs 9,792 | -Rs 3,290 | -Rs 132,155 |
| R72 Breadth Donchian | B | Rs 18,257 | Rs 12,398 | -Rs 1,721 | Rs 62,216 | Rs 29,854 | Rs 79,157 | Rs 690 | -Rs 20,632 | Rs 2,678 | Rs 91,938 | -Rs 2,750 | Rs 38,435 | Rs 61,196 | Rs 371,717 |
| R72 Breadth Donchian | B+S | Rs 3,146 | Rs 7,392 | -Rs 3,211 | Rs 24,026 | Rs 7,146 | -Rs 37,924 | -Rs 12,955 | -Rs 4,864 | Rs 1,133 | Rs 39,565 | -Rs 7,665 | -Rs 6,144 | Rs 2,871 | Rs 12,516 |
| R51 Donchian 20 + Supertrend | A | Rs 1,267 | Rs 16,638 | -Rs 51,699 | Rs 75,958 | -Rs 28,345 | Rs 72,461 | Rs 16,172 | Rs 29,306 | -Rs 54,587 | Rs 68,207 | Rs 18,751 | -Rs 2,459 | Rs 59,567 | Rs 221,238 |
| R51 Donchian 20 + Supertrend | S | Rs 519 | -Rs 6,574 | -Rs 51,659 | Rs 31,786 | -Rs 21,259 | -Rs 46,080 | -Rs 9,745 | Rs 10,262 | -Rs 35,466 | Rs 14,050 | -Rs 86 | -Rs 37,989 | -Rs 23,737 | -Rs 175,977 |
| R51 Donchian 20 + Supertrend | B | Rs 372 | Rs 10,322 | -Rs 904 | Rs 69,877 | Rs 41,666 | Rs 34,904 | Rs 7,701 | Rs 18,363 | -Rs 22,978 | Rs 64,996 | Rs 18,152 | Rs 3,509 | Rs 47,415 | Rs 293,395 |
| R51 Donchian 20 + Supertrend | B+S | Rs 2,442 | -Rs 2,792 | -Rs 17,344 | Rs 31,087 | Rs 10,611 | -Rs 36,957 | -Rs 21,512 | Rs 7,485 | Rs 2,596 | Rs 12,495 | -Rs 9,601 | -Rs 27,648 | -Rs 13,153 | -Rs 62,292 |
| All three arms | A | Rs 22,974 | Rs 27,283 | -Rs 129,283 | Rs 179,585 | Rs 42,693 | Rs 185,442 | -Rs 36,894 | Rs 60,403 | -Rs 96,444 | Rs 165,669 | Rs 38,260 | Rs 62,766 | Rs 114,960 | Rs 637,414 |
| All three arms | S | Rs 9,511 | -Rs 6,638 | -Rs 99,991 | Rs 54,313 | -Rs 5,176 | -Rs 109,865 | -Rs 61,234 | -Rs 2,736 | -Rs 60,942 | Rs 38,372 | Rs 3,804 | -Rs 41,428 | -Rs 40,847 | -Rs 322,855 |
| All three arms | B | Rs 18,018 | Rs 25,358 | -Rs 7,604 | Rs 177,864 | Rs 115,229 | Rs 141,407 | -Rs 29,430 | -Rs 11,101 | -Rs 21,248 | Rs 189,955 | Rs 13,797 | Rs 77,564 | Rs 93,227 | Rs 783,034 |
| All three arms | B+S | Rs 8,024 | Rs 8,991 | -Rs 19,712 | Rs 58,638 | Rs 48,520 | -Rs 82,803 | -Rs 52,485 | -Rs 10,083 | -Rs 1,008 | Rs 52,093 | -Rs 23,132 | -Rs 27,440 | -Rs 24,103 | -Rs 64,499 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 100%, R51 Donchian 20 + Supertrend 100%.

## 2 lots

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 43% | **Rs 242,161** | Rs 4,236 / -Rs 1,855 | 0 / 0 / 316 (6) | -Rs 10,759 | Dec 25 -Rs 7,052 | -Rs 31,338 |
| Liquidity 15+5 | S | 319 | 40% | **Rs 82,659** | Rs 2,467 / -Rs 1,182 | 157 / 71 / 91 (0) | -Rs 8,123 | Mar 26 -Rs 13,809 | -Rs 28,217 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 306,013** | Rs 3,602 / -Rs 2,655 | 0 / 0 / 1056 (166) | -Rs 51,084 | Dec 25 -Rs 135,116 | -Rs 164,515 |
| R72 Breadth Donchian | S | 1125 | 35% | **-Rs 145,079** | Rs 1,928 / -Rs 1,255 | 688 / 369 / 68 (2) | -Rs 22,861 | Mar 26 -Rs 101,648 | -Rs 175,574 |
| R72 Breadth Donchian | B | 452 | 56% | **Rs 421,187** | Rs 4,317 / -Rs 3,334 | 0 / 0 / 452 (70) | -Rs 51,084 | Dec 25 -Rs 1,952 | -Rs 67,680 |
| R72 Breadth Donchian | B+S | 466 | 39% | **Rs 22,726** | Rs 2,105 / -Rs 1,257 | 273 / 181 / 12 (0) | -Rs 12,885 | Mar 26 -Rs 68,005 | -Rs 72,913 |
| R51 Donchian 20 + Supertrend | A | 1370 | 46% | **Rs 235,755** | Rs 3,494 / -Rs 2,632 | 0 / 0 / 1370 (222) | -Rs 57,260 | Dec 25 -Rs 90,502 | -Rs 179,011 |
| R51 Donchian 20 + Supertrend | S | 1450 | 36% | **-Rs 119,430** | Rs 1,961 / -Rs 1,242 | 888 / 474 / 88 (2) | -Rs 23,828 | Dec 25 -Rs 89,837 | -Rs 151,430 |
| R51 Donchian 20 + Supertrend | B | 691 | 52% | **Rs 344,237** | Rs 3,834 / -Rs 3,047 | 0 / 0 / 691 (112) | -Rs 49,156 | Dec 25 Rs 3,585 | -Rs 58,519 |
| R51 Donchian 20 + Supertrend | B+S | 752 | 38% | **Rs 8,930** | Rs 2,085 / -Rs 1,275 | 448 / 270 / 34 (0) | -Rs 20,522 | Mar 26 -Rs 66,441 | -Rs 72,967 |
| All three arms | A | 2742 | 46% | **Rs 783,929** | Rs 3,617 / -Rs 2,546 | 0 / 0 / 2742 (394) | -Rs 101,804 | Dec 25 -Rs 232,669 | -Rs 295,760 |
| All three arms | S | 2894 | 36% | **-Rs 181,850** | Rs 2,009 / -Rs 1,241 | 1733 / 914 / 247 (4) | -Rs 46,661 | Mar 26 -Rs 196,199 | -Rs 263,486 |
| All three arms | B | 1459 | 51% | **Rs 1,007,585** | Rs 4,071 / -Rs 2,827 | 0 / 0 / 1459 (188) | -Rs 87,161 | Dec 25 -Rs 5,419 | -Rs 116,132 |
| All three arms | B+S | 1537 | 39% | **Rs 114,315** | Rs 2,172 / -Rs 1,251 | 878 / 522 / 137 (0) | -Rs 28,103 | Mar 26 -Rs 148,254 | -Rs 163,661 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 38% | **Rs 38,639** | Rs 3,467 / -Rs 1,983 | 0 / 0 / 357 (12) | -Rs 22,450 | Apr 26 -Rs 73,218 | -Rs 117,966 |
| Liquidity 15+5 | S | 358 | 33% | **-Rs 73,095** | Rs 1,744 / -Rs 1,162 | 199 / 70 / 89 (0) | -Rs 8,787 | Apr 26 -Rs 33,571 | -Rs 82,919 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 396,251** | Rs 3,511 / -Rs 2,406 | 0 / 0 / 1200 (173) | -Rs 44,447 | Jun 26 -Rs 72,639 | -Rs 111,087 |
| R72 Breadth Donchian | S | 1249 | 34% | **Rs 4,022** | Rs 2,435 / -Rs 1,229 | 774 / 349 / 126 (0) | -Rs 20,404 | Apr 26 -Rs 57,369 | -Rs 91,386 |
| R72 Breadth Donchian | B | 602 | 49% | **Rs 369,298** | Rs 3,873 / -Rs 2,561 | 0 / 0 / 602 (78) | -Rs 41,948 | May 26 -Rs 34,292 | -Rs 60,657 |
| R72 Breadth Donchian | B+S | 636 | 33% | **Rs 57,538** | Rs 2,733 / -Rs 1,231 | 398 / 188 / 50 (0) | -Rs 17,752 | Apr 26 -Rs 19,895 | -Rs 53,474 |
| R51 Donchian 20 + Supertrend | A | 1668 | 46% | **Rs 340,059** | Rs 3,294 / -Rs 2,376 | 0 / 0 / 1668 (218) | -Rs 42,690 | Jun 26 -Rs 96,531 | -Rs 145,608 |
| R51 Donchian 20 + Supertrend | S | 1751 | 34% | **-Rs 68,340** | Rs 2,219 / -Rs 1,225 | 1069 / 503 / 179 (0) | -Rs 21,469 | Sep 26 -Rs 63,150 | -Rs 132,116 |
| R51 Donchian 20 + Supertrend | B | 652 | 51% | **Rs 317,879** | Rs 3,471 / -Rs 2,627 | 0 / 0 / 652 (85) | -Rs 28,984 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 710 | 35% | **-Rs 46,915** | Rs 2,145 / -Rs 1,268 | 444 / 218 / 48 (0) | -Rs 15,990 | Sep 26 -Rs 48,438 | -Rs 99,383 |
| All three arms | A | 3225 | 45% | **Rs 774,949** | Rs 3,393 / -Rs 2,338 | 0 / 0 / 3225 (403) | -Rs 106,160 | Jun 26 -Rs 168,769 | -Rs 233,756 |
| All three arms | S | 3358 | 34% | **-Rs 137,413** | Rs 2,250 / -Rs 1,220 | 2042 / 922 / 394 (0) | -Rs 37,251 | Apr 26 -Rs 98,692 | -Rs 223,982 |
| All three arms | B | 1611 | 48% | **Rs 725,815** | Rs 3,626 / -Rs 2,435 | 0 / 0 / 1611 (175) | -Rs 78,828 | Apr 26 -Rs 45,679 | -Rs 122,299 |
| All three arms | B+S | 1704 | 34% | **-Rs 62,472** | Rs 2,278 / -Rs 1,231 | 1041 / 476 / 187 (0) | -Rs 33,185 | Apr 26 -Rs 90,856 | -Rs 171,536 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 39% | **Rs 72,613** | Rs 3,442 / -Rs 1,858 | 0 / 0 / 346 (13) | -Rs 22,450 | Oct 26 -Rs 28,488 | -Rs 73,050 |
| Liquidity 15+5 | S | 347 | 34% | **-Rs 65,742** | Rs 1,663 / -Rs 1,157 | 190 / 72 / 85 (0) | -Rs 8,787 | Oct 26 -Rs 25,347 | -Rs 68,120 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 431,603** | Rs 3,448 / -Rs 2,333 | 0 / 0 / 1116 (165) | -Rs 44,447 | Jun 26 -Rs 72,639 | -Rs 111,087 |
| R72 Breadth Donchian | S | 1164 | 34% | **Rs 34,535** | Rs 2,409 / -Rs 1,216 | 712 / 330 / 122 (0) | -Rs 20,404 | Jun 26 -Rs 32,322 | -Rs 59,025 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 388,024** | Rs 3,757 / -Rs 2,392 | 0 / 0 / 537 (68) | -Rs 33,927 | May 26 -Rs 34,292 | -Rs 60,657 |
| R72 Breadth Donchian | B+S | 561 | 34% | **Rs 69,185** | Rs 2,762 / -Rs 1,217 | 349 / 167 / 45 (0) | -Rs 17,752 | Aug 26 -Rs 13,243 | -Rs 53,474 |
| R51 Donchian 20 + Supertrend | A | 1584 | 46% | **Rs 369,927** | Rs 3,216 / -Rs 2,316 | 0 / 0 / 1584 (214) | -Rs 42,690 | Jun 26 -Rs 96,531 | -Rs 145,608 |
| R51 Donchian 20 + Supertrend | S | 1672 | 35% | **-Rs 49,064** | Rs 2,193 / -Rs 1,216 | 1017 / 482 / 173 (0) | -Rs 16,748 | Sep 26 -Rs 63,150 | -Rs 132,116 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 322,970** | Rs 3,327 / -Rs 2,460 | 0 / 0 / 598 (79) | -Rs 28,984 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 657 | 35% | **-Rs 25,071** | Rs 2,167 / -Rs 1,250 | 410 / 201 / 46 (0) | -Rs 13,187 | Sep 26 -Rs 48,438 | -Rs 100,090 |
| All three arms | A | 3046 | 46% | **Rs 874,143** | Rs 3,326 / -Rs 2,263 | 0 / 0 / 3046 (392) | -Rs 106,160 | Jun 26 -Rs 168,769 | -Rs 233,756 |
| All three arms | S | 3183 | 35% | **-Rs 80,271** | Rs 2,214 / -Rs 1,209 | 1919 / 884 / 380 (0) | -Rs 31,574 | Jun 26 -Rs 97,560 | -Rs 184,416 |
| All three arms | B | 1481 | 48% | **Rs 783,606** | Rs 3,512 / -Rs 2,270 | 0 / 0 / 1481 (160) | -Rs 78,828 | Jun 26 -Rs 33,503 | -Rs 101,301 |
| All three arms | B+S | 1565 | 35% | **-Rs 21,628** | Rs 2,264 / -Rs 1,217 | 949 / 440 / 176 (0) | -Rs 28,587 | Sep 26 -Rs 41,444 | -Rs 171,536 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 40% | **Rs 267,232** | Rs 3,817 / -Rs 1,922 | 0 / 0 / 688 (20) | -Rs 22,450 | Apr 26 -Rs 73,218 | -Rs 117,966 |
| Liquidity 15+5 | S | 692 | 36% | **Rs 2,118** | Rs 2,089 / -Rs 1,169 | 365 / 146 / 181 (0) | -Rs 8,787 | Apr 26 -Rs 33,571 | -Rs 108,959 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 702,143** | Rs 3,542 / -Rs 2,530 | 0 / 0 / 2281 (345) | -Rs 51,084 | Dec 25 -Rs 135,116 | -Rs 164,515 |
| R72 Breadth Donchian | S | 2403 | 34% | **-Rs 153,139** | Rs 2,174 / -Rs 1,240 | 1481 / 727 / 195 (2) | -Rs 22,861 | Mar 26 -Rs 101,648 | -Rs 266,960 |
| R72 Breadth Donchian | B | 1062 | 52% | **Rs 792,484** | Rs 4,062 / -Rs 2,870 | 0 / 0 / 1062 (150) | -Rs 51,084 | May 26 -Rs 34,292 | -Rs 67,680 |
| R72 Breadth Donchian | B+S | 1110 | 36% | **Rs 76,356** | Rs 2,439 / -Rs 1,241 | 677 / 371 / 62 (0) | -Rs 17,752 | Mar 26 -Rs 68,005 | -Rs 126,014 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 584,762** | Rs 3,386 / -Rs 2,499 | 0 / 0 / 3084 (452) | -Rs 57,260 | Jun 26 -Rs 96,531 | -Rs 179,011 |
| R51 Donchian 20 + Supertrend | S | 3253 | 35% | **-Rs 202,688** | Rs 2,093 / -Rs 1,233 | 1992 / 993 / 268 (2) | -Rs 23,828 | Dec 25 -Rs 89,837 | -Rs 235,787 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 649,385** | Rs 3,642 / -Rs 2,855 | 0 / 0 / 1361 (204) | -Rs 49,156 | Jun 26 -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+S | 1483 | 36% | **-Rs 56,066** | Rs 2,110 / -Rs 1,271 | 910 / 491 / 82 (0) | -Rs 20,522 | Mar 26 -Rs 66,441 | -Rs 137,963 |
| All three arms | A | 6053 | 45% | **Rs 1,554,136** | Rs 3,490 / -Rs 2,439 | 0 / 0 / 6053 (817) | -Rs 106,160 | Dec 25 -Rs 232,669 | -Rs 295,760 |
| All three arms | S | 6348 | 35% | **-Rs 353,709** | Rs 2,123 / -Rs 1,229 | 3838 / 1866 / 644 (4) | -Rs 46,661 | Mar 26 -Rs 196,199 | -Rs 487,468 |
| All three arms | B | 3111 | 49% | **Rs 1,709,101** | Rs 3,826 / -Rs 2,618 | 0 / 0 / 3111 (374) | -Rs 87,161 | Apr 26 -Rs 45,679 | -Rs 137,066 |
| All three arms | B+S | 3285 | 36% | **Rs 22,409** | Rs 2,215 / -Rs 1,239 | 1952 / 1008 / 325 (0) | -Rs 33,185 | Mar 26 -Rs 148,254 | -Rs 302,505 |

### Month by month, the whole year (2 lots)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 345 | Rs 7,446 | -Rs 7,052 | Rs 94,856 | Rs 90,547 | Rs 56,708 | -Rs 73,218 | -Rs 15,458 | Rs 402 | Rs 69,093 | -Rs 799 | Rs 73,539 | -Rs 28,488 | Rs 267,232 |
| Liquidity 15+5 | S | Rs 5,761 | Rs 10,980 | Rs 4,586 | Rs 10,382 | Rs 64,759 | -Rs 13,809 | -Rs 33,571 | -Rs 23,250 | -Rs 7,186 | Rs 3,166 | -Rs 9,378 | Rs 15,024 | -Rs 25,347 | Rs 2,118 |
| R72 Breadth Donchian | A | Rs 47,974 | Rs 24,516 | -Rs 135,116 | Rs 125,335 | Rs 62,710 | Rs 180,594 | -Rs 21,319 | Rs 89,496 | -Rs 72,639 | Rs 137,605 | Rs 48,822 | Rs 67,730 | Rs 146,436 | Rs 702,143 |
| R72 Breadth Donchian | S | Rs 16,566 | -Rs 21 | -Rs 88,070 | Rs 48,291 | -Rs 20,198 | -Rs 101,648 | -Rs 57,369 | Rs 9,592 | -Rs 32,322 | Rs 57,836 | Rs 26,335 | -Rs 10,690 | -Rs 1,442 | -Rs 153,139 |
| R72 Breadth Donchian | B | Rs 38,389 | Rs 28,901 | -Rs 1,952 | Rs 128,065 | Rs 61,678 | Rs 166,105 | Rs 6,924 | -Rs 34,292 | Rs 8,972 | Rs 187,816 | -Rs 3,419 | Rs 80,922 | Rs 124,372 | Rs 792,484 |
| R72 Breadth Donchian | B+S | Rs 8,296 | Rs 18,979 | -Rs 4,912 | Rs 51,880 | Rs 16,487 | -Rs 68,005 | -Rs 19,895 | -Rs 2,131 | Rs 5,780 | Rs 83,378 | -Rs 13,243 | -Rs 8,030 | Rs 7,770 | Rs 76,356 |
| R51 Donchian 20 + Supertrend | A | Rs 7,117 | Rs 44,467 | -Rs 90,502 | Rs 163,865 | -Rs 44,481 | Rs 155,290 | Rs 43,598 | Rs 70,546 | -Rs 96,531 | Rs 149,137 | Rs 49,428 | Rs 7,005 | Rs 125,823 | Rs 584,762 |
| R51 Donchian 20 + Supertrend | S | Rs 5,786 | -Rs 1,520 | -Rs 89,837 | Rs 76,106 | -Rs 29,224 | -Rs 80,742 | -Rs 7,752 | Rs 32,113 | -Rs 58,053 | Rs 41,382 | Rs 12,674 | -Rs 63,150 | -Rs 40,473 | -Rs 202,688 |
| R51 Donchian 20 + Supertrend | B | Rs 3,925 | Rs 28,422 | Rs 3,585 | Rs 145,322 | Rs 86,393 | Rs 76,591 | Rs 20,615 | Rs 41,474 | -Rs 42,877 | Rs 134,014 | Rs 41,347 | Rs 13,109 | Rs 97,467 | Rs 649,385 |
| R51 Donchian 20 + Supertrend | B+S | Rs 8,181 | Rs 2,971 | -Rs 28,772 | Rs 68,420 | Rs 24,572 | -Rs 66,441 | -Rs 37,391 | Rs 20,053 | Rs 8,423 | Rs 29,321 | -Rs 13,594 | -Rs 48,438 | -Rs 23,369 | -Rs 56,066 |
| All three arms | A | Rs 54,746 | Rs 76,429 | -Rs 232,669 | Rs 384,057 | Rs 108,775 | Rs 392,592 | -Rs 50,940 | Rs 144,584 | -Rs 168,769 | Rs 355,834 | Rs 97,451 | Rs 148,274 | Rs 243,771 | Rs 1,554,136 |
| All three arms | S | Rs 28,114 | Rs 9,439 | -Rs 173,321 | Rs 134,780 | Rs 15,336 | -Rs 196,199 | -Rs 98,692 | Rs 18,456 | -Rs 97,560 | Rs 102,384 | Rs 29,631 | -Rs 58,815 | -Rs 67,262 | -Rs 353,709 |
| All three arms | B | Rs 41,969 | Rs 64,769 | -Rs 5,419 | Rs 368,243 | Rs 238,618 | Rs 299,404 | -Rs 45,679 | -Rs 8,275 | -Rs 33,503 | Rs 390,922 | Rs 37,129 | Rs 167,571 | Rs 193,352 | Rs 1,709,101 |
| All three arms | B+S | Rs 22,238 | Rs 32,930 | -Rs 29,099 | Rs 130,682 | Rs 105,818 | -Rs 148,254 | -Rs 90,856 | -Rs 5,328 | Rs 7,017 | Rs 115,864 | -Rs 36,215 | -Rs 41,444 | -Rs 40,946 | Rs 22,409 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 100%, R51 Donchian 20 + Supertrend 100%.

## 3 lots

### early (20 Oct 2025 - 31 Mar 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 316 | 44% | **Rs 370,179** | Rs 6,282 / -Rs 2,791 | 0 / 0 / 316 (6) | -Rs 15,991 | Dec 25 -Rs 9,170 | -Rs 45,836 |
| Liquidity 15+5 | S | 319 | 40% | **Rs 131,022** | Rs 3,692 / -Rs 1,760 | 157 / 71 / 91 (0) | -Rs 12,043 | Mar 26 -Rs 19,741 | -Rs 40,190 |
| R72 Breadth Donchian | A | 1056 | 47% | **Rs 482,662** | Rs 5,403 / -Rs 3,974 | 0 / 0 / 1056 (166) | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S | 1125 | 36% | **-Rs 192,056** | Rs 2,901 / -Rs 1,865 | 688 / 369 / 68 (2) | -Rs 34,073 | Mar 26 -Rs 147,546 | -Rs 241,356 |
| R72 Breadth Donchian | B | 452 | 56% | **Rs 641,854** | Rs 6,447 / -Rs 5,029 | 0 / 0 / 452 (70) | -Rs 76,355 | Dec 25 -Rs 2,168 | -Rs 100,468 |
| R72 Breadth Donchian | B+S | 466 | 39% | **Rs 44,684** | Rs 3,163 / -Rs 1,870 | 273 / 181 / 12 (0) | -Rs 19,115 | Mar 26 -Rs 98,184 | -Rs 106,100 |
| R51 Donchian 20 + Supertrend | A | 1370 | 46% | **Rs 384,217** | Rs 5,256 / -Rs 3,931 | 0 / 0 / 1370 (222) | -Rs 85,577 | Dec 25 -Rs 129,380 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S | 1450 | 36% | **-Rs 146,385** | Rs 2,953 / -Rs 1,844 | 888 / 474 / 88 (2) | -Rs 35,430 | Dec 25 -Rs 128,183 | -Rs 199,666 |
| R51 Donchian 20 + Supertrend | B | 691 | 52% | **Rs 531,615** | Rs 5,758 / -Rs 4,563 | 0 / 0 / 691 (112) | -Rs 73,545 | Oct 25 Rs 7,448 | -Rs 87,097 |
| R51 Donchian 20 + Supertrend | B+S | 752 | 38% | **Rs 30,165** | Rs 3,139 / -Rs 1,894 | 448 / 270 / 34 (0) | -Rs 30,641 | Mar 26 -Rs 96,132 | -Rs 106,332 |
| All three arms | A | 2742 | 46% | **Rs 1,237,057** | Rs 5,426 / -Rs 3,810 | 0 / 0 / 2742 (394) | -Rs 151,955 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three arms | S | 2894 | 36% | **-Rs 207,419** | Rs 3,022 / -Rs 1,844 | 1733 / 914 / 247 (4) | -Rs 69,349 | Mar 26 -Rs 282,913 | -Rs 373,501 |
| All three arms | B | 1459 | 51% | **Rs 1,543,648** | Rs 6,088 / -Rs 4,249 | 0 / 0 / 1459 (188) | -Rs 130,335 | Dec 25 -Rs 3,317 | -Rs 171,782 |
| All three arms | B+S | 1537 | 39% | **Rs 205,871** | Rs 3,264 / -Rs 1,859 | 878 / 522 / 137 (0) | -Rs 41,636 | Mar 26 -Rs 214,056 | -Rs 238,180 |

### final (1 Apr - 13 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 357 | 39% | **Rs 65,610** | Rs 5,147 / -Rs 2,981 | 0 / 0 / 357 (12) | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S | 358 | 33% | **-Rs 101,976** | Rs 2,615 / -Rs 1,729 | 199 / 70 / 89 (0) | -Rs 13,015 | Apr 26 -Rs 49,171 | -Rs 118,129 |
| R72 Breadth Donchian | A | 1200 | 46% | **Rs 621,770** | Rs 5,260 / -Rs 3,603 | 0 / 0 / 1200 (173) | -Rs 66,325 | Jun 26 -Rs 104,488 | -Rs 160,424 |
| R72 Breadth Donchian | S | 1249 | 34% | **Rs 29,897** | Rs 3,670 / -Rs 1,823 | 775 / 348 / 126 (0) | -Rs 30,193 | Apr 26 -Rs 81,394 | -Rs 128,729 |
| R72 Breadth Donchian | B | 602 | 50% | **Rs 567,659** | Rs 5,794 / -Rs 3,844 | 0 / 0 / 602 (78) | -Rs 62,591 | May 26 -Rs 48,023 | -Rs 89,763 |
| R72 Breadth Donchian | B+S | 636 | 33% | **Rs 100,310** | Rs 4,101 / -Rs 1,828 | 398 / 188 / 50 (0) | -Rs 26,262 | Apr 26 -Rs 26,981 | -Rs 78,173 |
| R51 Donchian 20 + Supertrend | A | 1668 | 46% | **Rs 547,916** | Rs 4,911 / -Rs 3,573 | 0 / 0 / 1668 (218) | -Rs 63,776 | Jun 26 -Rs 138,454 | -Rs 209,599 |
| R51 Donchian 20 + Supertrend | S | 1751 | 35% | **-Rs 67,563** | Rs 3,331 / -Rs 1,822 | 1070 / 502 / 179 (0) | -Rs 31,825 | Sep 26 -Rs 88,640 | -Rs 190,306 |
| R51 Donchian 20 + Supertrend | B | 652 | 51% | **Rs 491,513** | Rs 5,213 / -Rs 3,930 | 0 / 0 / 652 (85) | -Rs 43,311 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 710 | 35% | **-Rs 54,534** | Rs 3,215 / -Rs 1,888 | 444 / 218 / 48 (0) | -Rs 23,702 | Sep 26 -Rs 69,401 | -Rs 143,142 |
| All three arms | A | 3225 | 45% | **Rs 1,235,296** | Rs 5,067 / -Rs 3,511 | 0 / 0 / 3225 (403) | -Rs 158,555 | Jun 26 -Rs 241,234 | -Rs 330,026 |
| All three arms | S | 3358 | 34% | **-Rs 139,642** | Rs 3,381 / -Rs 1,812 | 2044 / 920 / 394 (0) | -Rs 55,092 | Apr 26 -Rs 136,505 | -Rs 311,624 |
| All three arms | B | 1611 | 48% | **Rs 1,124,783** | Rs 5,426 / -Rs 3,652 | 0 / 0 / 1611 (175) | -Rs 117,746 | Apr 26 -Rs 61,992 | -Rs 178,236 |
| All three arms | B+S | 1704 | 34% | **-Rs 56,200** | Rs 3,416 / -Rs 1,831 | 1041 / 476 / 187 (0) | -Rs 49,159 | Apr 26 -Rs 129,467 | -Rs 247,881 |

### last 6 months (20 Apr - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 40% | **Rs 116,365** | Rs 5,109 / -Rs 2,792 | 0 / 0 / 346 (13) | -Rs 33,486 | Oct 26 -Rs 41,618 | -Rs 108,209 |
| Liquidity 15+5 | S | 347 | 35% | **-Rs 91,242** | Rs 2,495 / -Rs 1,721 | 190 / 72 / 85 (0) | -Rs 13,015 | Oct 26 -Rs 36,943 | -Rs 96,508 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 672,861** | Rs 5,165 / -Rs 3,494 | 0 / 0 / 1116 (165) | -Rs 66,325 | Jun 26 -Rs 104,488 | -Rs 160,424 |
| R72 Breadth Donchian | S | 1164 | 34% | **Rs 73,688** | Rs 3,630 / -Rs 1,804 | 713 / 329 / 122 (0) | -Rs 30,193 | Jun 26 -Rs 43,976 | -Rs 85,201 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 594,241** | Rs 5,616 / -Rs 3,592 | 0 / 0 / 537 (68) | -Rs 50,460 | May 26 -Rs 48,023 | -Rs 89,763 |
| R72 Breadth Donchian | B+S | 561 | 34% | **Rs 116,109** | Rs 4,142 / -Rs 1,808 | 349 / 167 / 45 (0) | -Rs 26,262 | Aug 26 -Rs 18,910 | -Rs 78,173 |
| R51 Donchian 20 + Supertrend | A | 1584 | 47% | **Rs 590,807** | Rs 4,801 / -Rs 3,480 | 0 / 0 / 1584 (214) | -Rs 63,776 | Jun 26 -Rs 138,454 | -Rs 209,599 |
| R51 Donchian 20 + Supertrend | S | 1672 | 35% | **-Rs 40,496** | Rs 3,291 / -Rs 1,808 | 1018 / 481 / 173 (0) | -Rs 24,733 | Sep 26 -Rs 88,640 | -Rs 190,306 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 497,903** | Rs 4,997 / -Rs 3,680 | 0 / 0 / 598 (79) | -Rs 43,311 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 657 | 36% | **-Rs 23,027** | Rs 3,244 / -Rs 1,861 | 410 / 201 / 46 (0) | -Rs 19,380 | Sep 26 -Rs 69,401 | -Rs 143,142 |
| All three arms | A | 3046 | 46% | **Rs 1,380,033** | Rs 4,968 / -Rs 3,397 | 0 / 0 / 3046 (392) | -Rs 158,555 | Jun 26 -Rs 241,234 | -Rs 330,026 |
| All three arms | S | 3183 | 35% | **-Rs 58,050** | Rs 3,327 / -Rs 1,797 | 1921 / 882 / 380 (0) | -Rs 46,677 | Jun 26 -Rs 134,469 | -Rs 266,017 |
| All three arms | B | 1481 | 49% | **Rs 1,208,509** | Rs 5,253 / -Rs 3,405 | 0 / 0 / 1481 (160) | -Rs 117,746 | Jun 26 -Rs 45,890 | -Rs 143,087 |
| All three arms | B+S | 1565 | 35% | **Rs 1,840** | Rs 3,392 / -Rs 1,811 | 949 / 440 / 176 (0) | -Rs 42,268 | Oct 26 -Rs 57,960 | -Rs 247,881 |

### whole year (20 Oct 2025 - 19 Oct 2026)

| arm | variant | trades | win | net | avg win / avg loss | exits: entry stop / step trail / other (-15%) | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 688 | 41% | **Rs 415,755** | Rs 5,645 / -Rs 2,897 | 0 / 0 / 688 (20) | -Rs 33,486 | Apr 26 -Rs 108,688 | -Rs 175,017 |
| Liquidity 15+5 | S | 692 | 36% | **Rs 18,159** | Rs 3,130 / -Rs 1,740 | 365 / 146 / 181 (0) | -Rs 13,015 | Apr 26 -Rs 49,171 | -Rs 155,005 |
| R72 Breadth Donchian | A | 2281 | 47% | **Rs 1,104,831** | Rs 5,312 / -Rs 3,788 | 0 / 0 / 2281 (345) | -Rs 76,355 | Dec 25 -Rs 197,761 | -Rs 242,618 |
| R72 Breadth Donchian | S | 2403 | 35% | **-Rs 179,688** | Rs 3,273 / -Rs 1,841 | 1482 / 726 / 195 (2) | -Rs 34,073 | Mar 26 -Rs 147,546 | -Rs 370,085 |
| R72 Breadth Donchian | B | 1062 | 53% | **Rs 1,212,701** | Rs 6,072 / -Rs 4,316 | 0 / 0 / 1062 (150) | -Rs 76,355 | May 26 -Rs 48,023 | -Rs 100,468 |
| R72 Breadth Donchian | B+S | 1110 | 36% | **Rs 139,321** | Rs 3,662 / -Rs 1,844 | 677 / 371 / 62 (0) | -Rs 26,262 | Mar 26 -Rs 98,184 | -Rs 181,860 |
| R51 Donchian 20 + Supertrend | A | 3084 | 46% | **Rs 946,603** | Rs 5,069 / -Rs 3,746 | 0 / 0 / 3084 (452) | -Rs 85,577 | Jun 26 -Rs 138,454 | -Rs 263,933 |
| R51 Donchian 20 + Supertrend | S | 3253 | 35% | **-Rs 235,179** | Rs 3,146 / -Rs 1,832 | 1993 / 992 / 268 (2) | -Rs 35,430 | Dec 25 -Rs 128,183 | -Rs 308,427 |
| R51 Donchian 20 + Supertrend | B | 1361 | 51% | **Rs 1,004,440** | Rs 5,470 / -Rs 4,274 | 0 / 0 / 1361 (204) | -Rs 73,545 | Jun 26 -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+S | 1483 | 37% | **-Rs 51,012** | Rs 3,169 / -Rs 1,891 | 910 / 491 / 82 (0) | -Rs 30,641 | Mar 26 -Rs 96,132 | -Rs 188,699 |
| All three arms | A | 6053 | 46% | **Rs 2,467,188** | Rs 5,221 / -Rs 3,657 | 0 / 0 / 6053 (817) | -Rs 158,555 | Dec 25 -Rs 336,310 | -Rs 432,666 |
| All three arms | S | 6348 | 35% | **-Rs 396,708** | Rs 3,191 / -Rs 1,826 | 3840 / 1864 / 644 (4) | -Rs 69,349 | Mar 26 -Rs 282,913 | -Rs 685,125 |
| All three arms | B | 3111 | 50% | **Rs 2,632,895** | Rs 5,720 / -Rs 3,932 | 0 / 0 / 3111 (374) | -Rs 130,335 | Apr 26 -Rs 61,992 | -Rs 199,852 |
| All three arms | B+S | 3285 | 36% | **Rs 106,468** | Rs 3,325 / -Rs 1,843 | 1952 / 1008 / 325 (0) | -Rs 49,159 | Mar 26 -Rs 214,056 | -Rs 437,744 |

### Month by month, the whole year (3 lots)

| arm | variant | Oct 25 | Nov 25 | Dec 25 | Jan 26 | Feb 26 | Mar 26 | Apr 26 | May 26 | Jun 26 | Jul 26 | Aug 26 | Sep 26 | Oct 26 | total |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 98 | Rs 12,178 | -Rs 9,170 | Rs 143,894 | Rs 137,377 | Rs 85,998 | -Rs 108,688 | -Rs 22,176 | Rs 1,707 | Rs 105,053 | -Rs 56 | Rs 111,354 | -Rs 41,618 | Rs 415,755 |
| Liquidity 15+5 | S | Rs 9,042 | Rs 17,479 | Rs 8,266 | Rs 17,219 | Rs 98,756 | -Rs 19,741 | -Rs 49,171 | -Rs 33,856 | -Rs 9,791 | Rs 6,213 | -Rs 12,905 | Rs 23,590 | -Rs 36,943 | Rs 18,159 |
| R72 Breadth Donchian | A | Rs 73,588 | Rs 40,861 | -Rs 197,761 | Rs 192,576 | Rs 98,081 | Rs 275,316 | -Rs 27,377 | Rs 138,976 | -Rs 104,488 | Rs 210,691 | Rs 76,503 | Rs 105,793 | Rs 222,071 | Rs 1,104,831 |
| R72 Breadth Donchian | S | Rs 26,576 | Rs 4,282 | -Rs 127,052 | Rs 77,485 | -Rs 25,801 | -Rs 147,546 | -Rs 81,394 | Rs 15,137 | -Rs 43,976 | Rs 91,231 | Rs 42,809 | -Rs 11,725 | Rs 286 | -Rs 179,688 |
| R72 Breadth Donchian | B | Rs 58,498 | Rs 45,276 | -Rs 2,168 | Rs 193,848 | Rs 93,532 | Rs 252,868 | Rs 13,173 | -Rs 48,023 | Rs 15,164 | Rs 283,676 | -Rs 4,111 | Rs 123,380 | Rs 187,588 | Rs 1,212,701 |
| R72 Breadth Donchian | B+S | Rs 13,440 | Rs 30,460 | -Rs 6,609 | Rs 79,755 | Rs 25,822 | -Rs 98,184 | -Rs 26,981 | Rs 464 | Rs 10,314 | Rs 127,107 | -Rs 18,910 | -Rs 9,987 | Rs 12,630 | Rs 139,321 |
| R51 Donchian 20 + Supertrend | A | Rs 12,923 | Rs 72,094 | -Rs 129,380 | Rs 251,504 | -Rs 60,702 | Rs 237,778 | Rs 70,983 | Rs 111,528 | -Rs 138,454 | Rs 229,910 | Rs 80,084 | Rs 16,311 | Rs 192,022 | Rs 946,603 |
| R51 Donchian 20 + Supertrend | S | Rs 10,979 | Rs 3,334 | -Rs 128,183 | Rs 120,396 | -Rs 37,285 | -Rs 115,626 | -Rs 5,940 | Rs 50,020 | -Rs 80,703 | Rs 68,550 | Rs 25,275 | -Rs 88,640 | -Rs 57,356 | -Rs 235,179 |
| R51 Donchian 20 + Supertrend | B | Rs 7,448 | Rs 46,362 | Rs 8,021 | Rs 220,635 | Rs 131,126 | Rs 118,023 | Rs 33,522 | Rs 64,402 | -Rs 62,761 | Rs 203,002 | Rs 64,482 | Rs 22,659 | Rs 147,519 | Rs 1,004,440 |
| R51 Donchian 20 + Supertrend | B+S | Rs 13,859 | Rs 8,550 | -Rs 40,334 | Rs 105,716 | Rs 38,505 | -Rs 96,132 | -Rs 53,315 | Rs 32,537 | Rs 14,220 | Rs 46,103 | -Rs 17,674 | -Rs 69,401 | -Rs 33,647 | -Rs 51,012 |
| All three arms | A | Rs 86,413 | Rs 125,133 | -Rs 336,310 | Rs 587,974 | Rs 174,756 | Rs 599,092 | -Rs 65,082 | Rs 228,328 | -Rs 241,234 | Rs 545,654 | Rs 156,531 | Rs 233,458 | Rs 372,475 | Rs 2,467,188 |
| All three arms | S | Rs 46,597 | Rs 25,095 | -Rs 246,969 | Rs 215,101 | Rs 35,670 | -Rs 282,913 | -Rs 136,505 | Rs 31,302 | -Rs 134,469 | Rs 165,994 | Rs 55,179 | -Rs 76,775 | -Rs 94,013 | -Rs 396,708 |
| All three arms | B | Rs 65,848 | Rs 103,816 | -Rs 3,317 | Rs 558,377 | Rs 362,035 | Rs 456,889 | -Rs 61,992 | -Rs 5,797 | -Rs 45,890 | Rs 591,731 | Rs 60,315 | Rs 257,392 | Rs 293,489 | Rs 2,632,895 |
| All three arms | B+S | Rs 36,340 | Rs 56,489 | -Rs 38,677 | Rs 202,691 | Rs 163,084 | -Rs 214,056 | -Rs 129,467 | -Rs 855 | Rs 14,742 | Rs 179,423 | -Rs 49,489 | -Rs 55,798 | -Rs 57,960 | Rs 106,468 |

Share of trades where the Rs 500 entry stop was tighter than -15% (so it replaced it): Liquidity 15+5 100%, R72 Breadth Donchian 100%, R51 Donchian 20 + Supertrend 100%.

