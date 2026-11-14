# R172: the last 6 months with the new +Rs 2,000 / -Rs 1,000 rule

**Boss, in short (14 Nov 2026).** I replayed **20 Apr - 19 Oct 2026** (126 trading days; the data ends on 19 Oct) on
Liquidity 15+5, R72 Breadth Donchian and R51 Donchian 20 + Supertrend. I ran each at 1, 2 and 3 lots, with and without the
new rule, and with and without the R151 picker. All money is after real Zerodha charges and slippage.

**The new rule would have lost money on every arm, at every lot size, with or without the picker.** Without it, all three
arms made money.

Net over the 6 months (today's rules -> with the new rule):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| Liquidity 15+5 | Rs 28k -> **-Rs 38k** | Rs 73k -> **-Rs 62k** | Rs 1.16 L -> **-Rs 1.12 L** |
| R72 Breadth Donchian | Rs 1.90 L -> **-Rs 16k** | Rs 4.32 L -> **-Rs 1.99 L** | Rs 6.73 L -> **-Rs 3.98 L** |
| R51 Donchian 20 | Rs 1.48 L -> **-Rs 51k** | Rs 3.70 L -> **-Rs 2.34 L** | Rs 5.91 L -> **-Rs 4.10 L** |
| **All three** | **Rs 3.67 L -> -Rs 1.05 L** | **Rs 8.74 L -> -Rs 4.95 L** | **Rs 13.80 L -> -Rs 9.20 L** |

With the R151 picker on R72 and R51, as live today (Liquidity has no picker):

| | 1 lot | 2 lots | 3 lots |
|---|---|---|---|
| R72 + picker | Rs 1.82 L -> **Rs 25k** | Rs 3.88 L -> **-Rs 95k** | Rs 5.94 L -> **-Rs 1.63 L** |
| R51 + picker | Rs 1.48 L -> **-Rs 3k** | Rs 3.23 L -> **-Rs 1.04 L** | Rs 4.98 L -> **-Rs 1.59 L** |
| **All three** | **Rs 3.58 L -> -Rs 15k** | **Rs 7.84 L -> -Rs 2.61 L** | **Rs 12.09 L -> -Rs 4.34 L** |

**Why it loses.**
- The Rs 1,000 stop is nearer than the arm's -15% stop on 95-100% of trades. Its premium distance is half the Rs 2,000
  target's, so it is hit about twice as often: at 3 lots, 2,083 stops against 1,071 targets, all arms together.
- One target hit nets about Rs 1,800-1,900. One stop hit costs about Rs 1,150-1,390, because a stop sells below its price
  (slippage) and the charges come on top.
- The win rate falls from 45-46% to 34-40%, below the 42-43% this payoff needs to break even.
- It also caps the big winners the Donchian arms live on. At 3 lots the average winning trade drops from about
  Rs 5,000 to Rs 1,800.
- More lots make it worse: Rs 1,000 for 3 lots is only a Rs 333 move a lot, inside normal noise.

**What it does do.**
- **Smaller single bad days.** Worst day for all three arms at 1 lot: -Rs 54k -> -Rs 31k. At 3 lots: -Rs 1.59 L ->
  -Rs 42k.
- **No big single-trade losses**, except gaps. In thin FINNIFTY / MIDCPNIFTY books, a price can jump past the stop. The
  worst such trade was -Rs 11,250 at 3 lots (FINNIFTY, 3 Sep).
- **But the losses keep adding up, so the drawdown is larger.**
  - All three arms at 1 lot: Rs 1.39 L -> Rs 1.71 L.
  - At 3 lots: Rs 3.30 L -> Rs 9.48 L. That is far past the account stop rule of -Rs 40k drawdown / -Rs 13k a month.
- Red months with the new rule, all three arms together (7 months, Apr and Oct part months):
  - 1 lot: 5 red (4 with the picker);
  - 2 lots: all 7 red (5 with the picker);
  - 3 lots: all 7 red, with or without the picker.
  - Today's rules at the same lots: only June red without the picker; May and June red with it.

**Honest notes.**
- This is a report of the rule you chose, not a gate test, so no pass / fail or multiple-testing step.
- These months overlap the period that many earlier studies have read. R106 and R152 already found that rupee targets and
  stops hurt these arms there.
- It agrees with them.
- Nothing in the app changes from this report.

## Window, data and checks

- Window: **2026-04-20 to 2026-10-19**, 126 sessions (by month: Apr* 9, May 19, Jun 21, Jul 23, Aug 21, Sep 21, Oct* 12). The lake (copied 14 Nov 2026) ends on 19 Oct 2026; * = part month.
- R151 picker: R72 2139 candidate breaks, 1235 scored below 0.4656; R51 4529 candidates, 3507 below 0.4469 (skipped, but each still holds its side and counts in the day's caps, as in the app).
- Engine check, today's rules at 1 lot, 1 Apr - 13 Oct 2026, on the stores built to 19 Oct: Liquidity 15+5 357 / Rs 11,155 (published 357 / Rs 11,155); R72 Breadth Donchian 1200 / Rs 170,336 (published 1202 / Rs 171,366), picker 651 / Rs 220,118 (published 652 / Rs 219,057); R51 Donchian 20 + Supertrend 1668 / Rs 131,546 (published 1670 / Rs 134,743), picker 692 / Rs 160,101 (published 693 / Rs 159,040). On the stores built to 13 Oct every published figure reproduces exactly. The only difference between the stores is NIFTY on Tue 13 Oct 2026, NIFTY's weekly expiry. The stores built to 13 Oct cannot see it is an expiry day (found from the next day's contract roll), so the published finals took 2 NIFTY trades that day (R72 -1,061 and +2,091 = +1,030; R51 -1,061 and +4,258 = +3,197; the pickers 1 trade each). The arms never trade an index on its own expiry day, so the stores built to 19 Oct (expiry known) are the correct ones and are used for R172.
- Signal source: the index for every arm (Liquidity: the index's liquidity levels; R72: Donchian 20 breaks on 4 of the 5 indices' 5-minute bars; R51: Donchian 20 on the index's 5-minute bars, kept when Supertrend (10, 3) agrees). Only the option's exit changes (a rupee target / stop on the premium).
- Costs: dated Zerodha charges on the whole quantity, R17 slippage (from the 5-minute volume per position; stops -10 bps and twice the half-spread; a gap through a stop fills at the minute's open); limits fill at their price.
- Not a gate test: this window overlaps the final period (1 Apr - 13 Oct 2026) that R51-R171 read many times, and R106 / R152 already read rupee targets and stops on these arms there. No twin, no Benjamini-Hochberg: nothing here is being selected. Variants: A = today's rules; B = A + R151 picker (R72 / R51 only; Liquidity has none, so its B = A); +2k1k = with Boss's +Rs 2,000 / -Rs 1,000 (before charges, whole position).

## 1 lot

Net is after charges and slippage. avg loss is the average losing trade (net <= 0). 'other' exits: the arm's own exits (-15% stop shown in brackets, ladder / L25 locks, time stops, index exits, square-off).

| arm | variant | trades | win | net | avg win / avg loss | exits: Rs 2k target / Rs 1k stop / other | largest loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 38% | **Rs 28,392** | Rs 1,750 / -Rs 934 | 0 / 0 / 346 (13 at -15%) | -Rs 4,362 | -Rs 11,405 | Oct* -Rs 15,384 | -Rs 38,009 |
| Liquidity 15+5 | A+2k1k | 347 | 37% | **-Rs 37,713** | Rs 1,102 / -Rs 834 | 49 / 109 / 189 (0 at -15%) | -Rs 2,715 | -Rs 6,659 | May -Rs 17,020 | -Rs 47,088 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 189,980** | Rs 1,717 / -Rs 1,179 | 0 / 0 / 1116 (165 at -15%) | -Rs 6,245 | -Rs 22,571 | Jun -Rs 40,908 | -Rs 61,884 |
| R72 Breadth Donchian | A+2k1k | 1160 | 41% | **-Rs 16,367** | Rs 1,379 / -Rs 984 | 273 / 515 / 372 (9 at -15%) | -Rs 4,513 | -Rs 15,954 | Jun -Rs 32,639 | -Rs 75,121 |
| R72 Breadth Donchian | B | 537 | 50% | **Rs 181,614** | Rs 1,862 / -Rs 1,214 | 0 / 0 / 537 (68 at -15%) | -Rs 6,245 | -Rs 17,394 | May -Rs 20,632 | -Rs 31,620 |
| R72 Breadth Donchian | B+2k1k | 557 | 42% | **Rs 25,485** | Rs 1,473 / -Rs 1,004 | 152 / 254 / 151 (5 at -15%) | -Rs 3,781 | -Rs 9,043 | May -Rs 34,164 | -Rs 36,409 |
| R51 Donchian 20 + Supertrend | A | 1584 | 45% | **Rs 148,408** | Rs 1,609 / -Rs 1,166 | 0 / 0 / 1584 (214 at -15%) | -Rs 5,430 | -Rs 21,578 | Jun -Rs 54,587 | -Rs 81,683 |
| R51 Donchian 20 + Supertrend | A+2k1k | 1640 | 40% | **-Rs 50,643** | Rs 1,355 / -Rs 962 | 368 / 714 / 558 (13 at -15%) | -Rs 4,513 | -Rs 13,595 | Jun -Rs 33,907 | -Rs 86,005 |
| R51 Donchian 20 + Supertrend | B | 598 | 51% | **Rs 147,723** | Rs 1,662 / -Rs 1,236 | 0 / 0 / 598 (79 at -15%) | -Rs 4,718 | -Rs 14,642 | Jun -Rs 22,978 | -Rs 38,799 |
| R51 Donchian 20 + Supertrend | B+2k1k | 643 | 41% | **-Rs 2,785** | Rs 1,410 / -Rs 1,002 | 159 / 290 / 194 (9 at -15%) | -Rs 3,781 | -Rs 10,254 | Sep -Rs 28,058 | -Rs 47,135 |
| All three arms | A | 3046 | 45% | **Rs 366,780** | Rs 1,664 / -Rs 1,141 | 0 / 0 / 3046 (392 at -15%) | -Rs 6,245 | -Rs 53,710 | Jun -Rs 96,444 | -Rs 139,134 |
| All three arms | A+2k1k | 3147 | 40% | **-Rs 104,724** | Rs 1,338 / -Rs 955 | 690 / 1338 / 1119 (22 at -15%) | -Rs 4,513 | -Rs 30,650 | Jun -Rs 75,457 | -Rs 170,880 |
| All three arms | B | 1481 | 48% | **Rs 357,730** | Rs 1,755 / -Rs 1,145 | 0 / 0 / 1481 (160 at -15%) | -Rs 6,245 | -Rs 39,870 | Jun -Rs 21,248 | -Rs 59,893 |
| All three arms | B+2k1k | 1547 | 41% | **-Rs 15,014** | Rs 1,370 / -Rs 963 | 360 / 653 / 534 (14 at -15%) | -Rs 3,781 | -Rs 19,515 | May -Rs 51,156 | -Rs 81,170 |

Month by month (1 lot):

| arm | variant | Apr* | May | Jun | Jul | Aug | Sep | Oct* | total |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 13,478 | -Rs 8,832 | -Rs 949 | Rs 33,022 | -Rs 1,605 | Rs 35,620 | -Rs 15,384 | Rs 28,392 |
| Liquidity 15+5 | A+2k1k | -Rs 9,137 | -Rs 17,020 | -Rs 8,911 | -Rs 133 | -Rs 5,610 | Rs 11,141 | -Rs 8,043 | -Rs 37,713 |
| R72 Breadth Donchian | A | Rs 5,022 | Rs 39,929 | -Rs 40,908 | Rs 64,441 | Rs 21,113 | Rs 29,606 | Rs 70,777 | Rs 189,980 |
| R72 Breadth Donchian | A+2k1k | -Rs 1,581 | -Rs 28,320 | -Rs 32,639 | Rs 18,178 | Rs 7,821 | Rs 5,888 | Rs 14,285 | -Rs 16,367 |
| R72 Breadth Donchian | B | Rs 10,749 | -Rs 20,632 | Rs 2,678 | Rs 91,938 | -Rs 2,750 | Rs 38,435 | Rs 61,196 | Rs 181,614 |
| R72 Breadth Donchian | B+2k1k | Rs 6,483 | -Rs 34,164 | Rs 9,945 | Rs 38,664 | -Rs 3,833 | -Rs 2,101 | Rs 10,490 | Rs 25,485 |
| R51 Donchian 20 + Supertrend | A | Rs 29,623 | Rs 29,306 | -Rs 54,587 | Rs 68,207 | Rs 18,751 | -Rs 2,459 | Rs 59,567 | Rs 148,408 |
| R51 Donchian 20 + Supertrend | A+2k1k | Rs 20,408 | -Rs 5,037 | -Rs 33,907 | Rs 11,687 | -Rs 6,255 | -Rs 24,613 | -Rs 12,925 | -Rs 50,643 |
| R51 Donchian 20 + Supertrend | B | Rs 18,266 | Rs 18,363 | -Rs 22,978 | Rs 64,996 | Rs 18,152 | Rs 3,509 | Rs 47,415 | Rs 147,723 |
| R51 Donchian 20 + Supertrend | B+2k1k | Rs 1,462 | Rs 27 | -Rs 568 | Rs 25,105 | -Rs 572 | -Rs 28,058 | -Rs 182 | -Rs 2,785 |
| All three arms | A | Rs 21,166 | Rs 60,403 | -Rs 96,444 | Rs 165,669 | Rs 38,260 | Rs 62,766 | Rs 114,960 | Rs 366,780 |
| All three arms | A+2k1k | Rs 9,690 | -Rs 50,377 | -Rs 75,457 | Rs 29,732 | -Rs 4,044 | -Rs 7,584 | -Rs 6,684 | -Rs 104,724 |
| All three arms | B | Rs 15,537 | -Rs 11,101 | -Rs 21,248 | Rs 189,955 | Rs 13,797 | Rs 77,564 | Rs 93,227 | Rs 357,730 |
| All three arms | B+2k1k | -Rs 1,192 | -Rs 51,156 | Rs 466 | Rs 63,636 | -Rs 10,014 | -Rs 19,018 | Rs 2,264 | -Rs 15,014 |

How often the Rs 1,000 stop was the tighter one (so it replaced -15%): Liquidity 15+5 100%, R72 Breadth Donchian 96%, R51 Donchian 20 + Supertrend 95%. Average net of one exit (after charges and slippage): Liquidity 15+5: Rs 2k target Rs 1,905, Rs 1k stop -Rs 1,143, R72 Breadth Donchian: Rs 2k target Rs 1,906, Rs 1k stop -Rs 1,171, R51 Donchian 20 + Supertrend: Rs 2k target Rs 1,904, Rs 1k stop -Rs 1,176. Rs 1,000-stop exits that lost more than Rs 1,000 before charges (stop slippage or a gap): Liquidity 15+5 109 of 109 (worst net -Rs 1,675), R72 Breadth Donchian 515 of 515 (worst net -Rs 4,513), R51 Donchian 20 + Supertrend 714 of 714 (worst net -Rs 4,513).

## 2 lots

Net is after charges and slippage. avg loss is the average losing trade (net <= 0). 'other' exits: the arm's own exits (-15% stop shown in brackets, ladder / L25 locks, time stops, index exits, square-off).

| arm | variant | trades | win | net | avg win / avg loss | exits: Rs 2k target / Rs 1k stop / other | largest loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 39% | **Rs 72,613** | Rs 3,442 / -Rs 1,858 | 0 / 0 / 346 (13 at -15%) | -Rs 8,678 | -Rs 22,450 | Oct* -Rs 28,488 | -Rs 73,050 |
| Liquidity 15+5 | A+2k1k | 347 | 35% | **-Rs 61,740** | Rs 1,645 / -Rs 1,166 | 94 / 190 / 63 (0 at -15%) | -Rs 2,635 | -Rs 8,787 | May -Rs 19,347 | -Rs 61,740 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 431,603** | Rs 3,448 / -Rs 2,333 | 0 / 0 / 1116 (165 at -15%) | -Rs 12,468 | -Rs 44,447 | Jun -Rs 72,639 | -Rs 111,087 |
| R72 Breadth Donchian | A+2k1k | 1189 | 35% | **-Rs 199,168** | Rs 1,760 / -Rs 1,221 | 381 / 725 / 83 (0 at -15%) | -Rs 7,516 | -Rs 19,535 | Jun -Rs 54,872 | -Rs 214,496 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 388,024** | Rs 3,757 / -Rs 2,392 | 0 / 0 / 537 (68 at -15%) | -Rs 12,468 | -Rs 33,927 | May -Rs 34,292 | -Rs 60,657 |
| R72 Breadth Donchian | B+2k1k | 576 | 35% | **-Rs 94,607** | Rs 1,790 / -Rs 1,220 | 190 / 358 / 28 (0 at -15%) | -Rs 7,516 | -Rs 18,000 | May -Rs 46,939 | -Rs 101,710 |
| R51 Donchian 20 + Supertrend | A | 1584 | 46% | **Rs 369,927** | Rs 3,216 / -Rs 2,316 | 0 / 0 / 1584 (214 at -15%) | -Rs 10,825 | -Rs 42,690 | Jun -Rs 96,531 | -Rs 145,608 |
| R51 Donchian 20 + Supertrend | A+2k1k | 1689 | 36% | **-Rs 233,914** | Rs 1,759 / -Rs 1,225 | 561 / 1017 / 111 (0 at -15%) | -Rs 7,516 | -Rs 15,890 | Sep -Rs 83,395 | -Rs 257,748 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 322,970** | Rs 3,327 / -Rs 2,460 | 0 / 0 / 598 (79 at -15%) | -Rs 9,389 | -Rs 28,984 | Jun -Rs 42,877 | -Rs 73,811 |
| R51 Donchian 20 + Supertrend | B+2k1k | 676 | 37% | **-Rs 104,158** | Rs 1,765 / -Rs 1,259 | 228 / 419 / 29 (0 at -15%) | -Rs 7,516 | -Rs 11,941 | Sep -Rs 55,246 | -Rs 120,366 |
| All three arms | A | 3046 | 46% | **Rs 874,143** | Rs 3,326 / -Rs 2,263 | 0 / 0 / 3046 (392 at -15%) | -Rs 12,468 | -Rs 106,160 | Jun -Rs 168,769 | -Rs 233,756 |
| All three arms | A+2k1k | 3225 | 36% | **-Rs 494,822** | Rs 1,747 / -Rs 1,217 | 1036 / 1932 / 257 (0 at -15%) | -Rs 7,516 | -Rs 30,464 | Jun -Rs 131,885 | -Rs 513,338 |
| All three arms | B | 1481 | 48% | **Rs 783,606** | Rs 3,512 / -Rs 2,270 | 0 / 0 / 1481 (160 at -15%) | -Rs 12,468 | -Rs 78,828 | Jun -Rs 33,503 | -Rs 101,301 |
| All three arms | B+2k1k | 1599 | 36% | **-Rs 260,505** | Rs 1,748 / -Rs 1,225 | 512 / 967 / 120 (0 at -15%) | -Rs 7,516 | -Rs 28,282 | May -Rs 75,230 | -Rs 266,304 |

Month by month (2 lots):

| arm | variant | Apr* | May | Jun | Jul | Aug | Sep | Oct* | total |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 25,677 | -Rs 15,458 | Rs 402 | Rs 69,093 | -Rs 799 | Rs 73,539 | -Rs 28,488 | Rs 72,613 |
| Liquidity 15+5 | A+2k1k | -Rs 17,499 | -Rs 19,347 | Rs 4,588 | -Rs 8,179 | -Rs 10,605 | Rs 6,820 | -Rs 17,518 | -Rs 61,740 |
| R72 Breadth Donchian | A | Rs 14,155 | Rs 89,496 | -Rs 72,639 | Rs 137,605 | Rs 48,822 | Rs 67,730 | Rs 146,436 | Rs 431,603 |
| R72 Breadth Donchian | A+2k1k | -Rs 20,265 | -Rs 53,182 | -Rs 54,872 | -Rs 1,453 | -Rs 2,913 | -Rs 35,068 | -Rs 31,416 | -Rs 199,168 |
| R72 Breadth Donchian | B | Rs 23,651 | -Rs 34,292 | Rs 8,972 | Rs 187,816 | -Rs 3,419 | Rs 80,922 | Rs 124,372 | Rs 388,024 |
| R72 Breadth Donchian | B+2k1k | -Rs 3,899 | -Rs 46,939 | -Rs 4,811 | Rs 20,783 | -Rs 12,736 | -Rs 26,569 | -Rs 20,437 | -Rs 94,607 |
| R51 Donchian 20 + Supertrend | A | Rs 64,519 | Rs 70,546 | -Rs 96,531 | Rs 149,137 | Rs 49,428 | Rs 7,005 | Rs 125,823 | Rs 369,927 |
| R51 Donchian 20 + Supertrend | A+2k1k | Rs 13,657 | -Rs 29,037 | -Rs 81,600 | -Rs 11,937 | Rs 5,917 | -Rs 83,395 | -Rs 47,518 | -Rs 233,914 |
| R51 Donchian 20 + Supertrend | B | Rs 38,436 | Rs 41,474 | -Rs 42,877 | Rs 134,014 | Rs 41,347 | Rs 13,109 | Rs 97,467 | Rs 322,970 |
| R51 Donchian 20 + Supertrend | B+2k1k | Rs 3,599 | -Rs 8,944 | Rs 5,130 | -Rs 12,408 | -Rs 6,585 | -Rs 55,246 | -Rs 29,703 | -Rs 104,158 |
| All three arms | A | Rs 52,997 | Rs 144,584 | -Rs 168,769 | Rs 355,834 | Rs 97,451 | Rs 148,274 | Rs 243,771 | Rs 874,143 |
| All three arms | A+2k1k | -Rs 24,107 | -Rs 101,566 | -Rs 131,885 | -Rs 21,569 | -Rs 7,601 | -Rs 111,643 | -Rs 96,451 | -Rs 494,822 |
| All three arms | B | Rs 36,410 | -Rs 8,275 | -Rs 33,503 | Rs 390,922 | Rs 37,129 | Rs 167,571 | Rs 193,352 | Rs 783,606 |
| All three arms | B+2k1k | -Rs 17,799 | -Rs 75,230 | Rs 4,906 | Rs 197 | -Rs 29,926 | -Rs 74,996 | -Rs 67,658 | -Rs 260,505 |

How often the Rs 1,000 stop was the tighter one (so it replaced -15%): Liquidity 15+5 100%, R72 Breadth Donchian 100%, R51 Donchian 20 + Supertrend 100%. Average net of one exit (after charges and slippage): Liquidity 15+5: Rs 2k target Rs 1,855, Rs 1k stop -Rs 1,269, R72 Breadth Donchian: Rs 2k target Rs 1,868, Rs 1k stop -Rs 1,268, R51 Donchian 20 + Supertrend: Rs 2k target Rs 1,864, Rs 1k stop -Rs 1,273. Rs 1,000-stop exits that lost more than Rs 1,000 before charges (stop slippage or a gap): Liquidity 15+5 190 of 190 (worst net -Rs 2,635), R72 Breadth Donchian 725 of 725 (worst net -Rs 7,516), R51 Donchian 20 + Supertrend 1017 of 1017 (worst net -Rs 7,516).

## 3 lots

Net is after charges and slippage. avg loss is the average losing trade (net <= 0). 'other' exits: the arm's own exits (-15% stop shown in brackets, ladder / L25 locks, time stops, index exits, square-off).

| arm | variant | trades | win | net | avg win / avg loss | exits: Rs 2k target / Rs 1k stop / other | largest loss | worst day | worst month | max drawdown |
|---|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | 346 | 40% | **Rs 116,365** | Rs 5,109 / -Rs 2,792 | 0 / 0 / 346 (13 at -15%) | -Rs 12,993 | -Rs 33,486 | Oct* -Rs 41,618 | -Rs 108,209 |
| Liquidity 15+5 | A+2k1k | 347 | 33% | **-Rs 112,310** | Rs 1,778 / -Rs 1,339 | 102 / 214 / 31 (0 at -15%) | -Rs 3,928 | -Rs 10,412 | Apr* -Rs 22,371 | -Rs 112,310 |
| R72 Breadth Donchian | A | 1116 | 47% | **Rs 672,861** | Rs 5,165 / -Rs 3,494 | 0 / 0 / 1116 (165 at -15%) | -Rs 18,678 | -Rs 66,325 | Jun -Rs 104,488 | -Rs 160,424 |
| R72 Breadth Donchian | A+2k1k | 1200 | 32% | **-Rs 397,874** | Rs 1,799 / -Rs 1,342 | 378 / 794 / 28 (0 at -15%) | -Rs 11,250 | -Rs 19,692 | Jun -Rs 78,826 | -Rs 414,570 |
| R72 Breadth Donchian | B | 537 | 51% | **Rs 594,241** | Rs 5,616 / -Rs 3,592 | 0 / 0 / 537 (68 at -15%) | -Rs 18,678 | -Rs 50,460 | May -Rs 48,023 | -Rs 89,763 |
| R72 Breadth Donchian | B+2k1k | 580 | 34% | **-Rs 162,786** | Rs 1,804 / -Rs 1,337 | 192 / 377 / 11 (0 at -15%) | -Rs 11,250 | -Rs 19,692 | May -Rs 37,792 | -Rs 181,495 |
| R51 Donchian 20 + Supertrend | A | 1584 | 47% | **Rs 590,807** | Rs 4,801 / -Rs 3,480 | 0 / 0 / 1584 (214 at -15%) | -Rs 16,213 | -Rs 63,776 | Jun -Rs 138,454 | -Rs 209,599 |
| R51 Donchian 20 + Supertrend | A+2k1k | 1699 | 35% | **-Rs 409,891** | Rs 1,804 / -Rs 1,364 | 591 / 1075 / 33 (0 at -15%) | -Rs 11,250 | -Rs 21,131 | Sep -Rs 119,768 | -Rs 429,953 |
| R51 Donchian 20 + Supertrend | B | 598 | 52% | **Rs 497,903** | Rs 4,997 / -Rs 3,680 | 0 / 0 / 598 (79 at -15%) | -Rs 14,059 | -Rs 43,311 | Jun -Rs 62,761 | -Rs 108,826 |
| R51 Donchian 20 + Supertrend | B+2k1k | 686 | 36% | **-Rs 159,349** | Rs 1,808 / -Rs 1,373 | 242 / 437 / 7 (0 at -15%) | -Rs 11,250 | -Rs 18,095 | Sep -Rs 78,209 | -Rs 172,478 |
| All three arms | A | 3046 | 46% | **Rs 1,380,033** | Rs 4,968 / -Rs 3,397 | 0 / 0 / 3046 (392 at -15%) | -Rs 18,678 | -Rs 158,555 | Jun -Rs 241,234 | -Rs 330,026 |
| All three arms | A+2k1k | 3246 | 34% | **-Rs 920,074** | Rs 1,799 / -Rs 1,353 | 1071 / 2083 / 92 (0 at -15%) | -Rs 11,250 | -Rs 42,182 | Sep -Rs 190,882 | -Rs 948,103 |
| All three arms | B | 1481 | 49% | **Rs 1,208,509** | Rs 5,253 / -Rs 3,405 | 0 / 0 / 1481 (160 at -15%) | -Rs 18,678 | -Rs 117,746 | Jun -Rs 45,890 | -Rs 143,087 |
| All three arms | B+2k1k | 1613 | 34% | **-Rs 434,445** | Rs 1,800 / -Rs 1,352 | 536 / 1028 / 49 (0 at -15%) | -Rs 11,250 | -Rs 38,480 | Sep -Rs 116,525 | -Rs 453,875 |

Month by month (3 lots):

| arm | variant | Apr* | May | Jun | Jul | Aug | Sep | Oct* | total |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 | A | -Rs 37,899 | -Rs 22,176 | Rs 1,707 | Rs 105,053 | -Rs 56 | Rs 111,354 | -Rs 41,618 | Rs 116,365 |
| Liquidity 15+5 | A+2k1k | -Rs 22,371 | -Rs 19,049 | -Rs 11,709 | -Rs 16,873 | -Rs 20,343 | -Rs 3,628 | -Rs 18,335 | -Rs 112,310 |
| R72 Breadth Donchian | A | Rs 23,315 | Rs 138,976 | -Rs 104,488 | Rs 210,691 | Rs 76,503 | Rs 105,793 | Rs 222,071 | Rs 672,861 |
| R72 Breadth Donchian | A+2k1k | -Rs 18,798 | -Rs 53,924 | -Rs 78,826 | -Rs 67,768 | -Rs 43,231 | -Rs 67,487 | -Rs 67,841 | -Rs 397,874 |
| R72 Breadth Donchian | B | Rs 36,568 | -Rs 48,023 | Rs 15,164 | Rs 283,676 | -Rs 4,111 | Rs 123,380 | Rs 187,588 | Rs 594,241 |
| R72 Breadth Donchian | B+2k1k | -Rs 5,488 | -Rs 37,792 | -Rs 11,657 | -Rs 27,342 | -Rs 16,249 | -Rs 34,687 | -Rs 29,572 | -Rs 162,786 |
| R51 Donchian 20 + Supertrend | A | Rs 99,405 | Rs 111,528 | -Rs 138,454 | Rs 229,910 | Rs 80,084 | Rs 16,311 | Rs 192,022 | Rs 590,807 |
| R51 Donchian 20 + Supertrend | A+2k1k | Rs 10,929 | -Rs 47,902 | -Rs 83,047 | -Rs 56,039 | -Rs 37,212 | -Rs 119,768 | -Rs 76,851 | -Rs 409,891 |
| R51 Donchian 20 + Supertrend | B | Rs 58,600 | Rs 64,402 | -Rs 62,761 | Rs 203,002 | Rs 64,482 | Rs 22,659 | Rs 147,519 | Rs 497,903 |
| R51 Donchian 20 + Supertrend | B+2k1k | Rs 5,394 | -Rs 17,222 | Rs 14,325 | -Rs 33,774 | -Rs 11,215 | -Rs 78,209 | -Rs 38,648 | -Rs 159,349 |
| All three arms | A | Rs 84,820 | Rs 228,328 | -Rs 241,234 | Rs 545,654 | Rs 156,531 | Rs 233,458 | Rs 372,475 | Rs 1,380,033 |
| All three arms | A+2k1k | -Rs 30,240 | -Rs 120,875 | -Rs 173,583 | -Rs 140,680 | -Rs 100,786 | -Rs 190,882 | -Rs 163,027 | -Rs 920,074 |
| All three arms | B | Rs 57,269 | -Rs 5,797 | -Rs 45,890 | Rs 591,731 | Rs 60,315 | Rs 257,392 | Rs 293,489 | Rs 1,208,509 |
| All three arms | B+2k1k | -Rs 22,465 | -Rs 74,063 | -Rs 9,041 | -Rs 77,989 | -Rs 47,808 | -Rs 116,525 | -Rs 86,555 | -Rs 434,445 |

How often the Rs 1,000 stop was the tighter one (so it replaced -15%): Liquidity 15+5 100%, R72 Breadth Donchian 100%, R51 Donchian 20 + Supertrend 100%. Average net of one exit (after charges and slippage): Liquidity 15+5: Rs 2k target Rs 1,799, Rs 1k stop -Rs 1,386, R72 Breadth Donchian: Rs 2k target Rs 1,823, Rs 1k stop -Rs 1,366, R51 Donchian 20 + Supertrend: Rs 2k target Rs 1,823, Rs 1k stop -Rs 1,384. Rs 1,000-stop exits that lost more than Rs 1,000 before charges (stop slippage or a gap): Liquidity 15+5 214 of 214 (worst net -Rs 3,928), R72 Breadth Donchian 794 of 794 (worst net -Rs 11,250), R51 Donchian 20 + Supertrend 1075 of 1075 (worst net -Rs 11,250).

