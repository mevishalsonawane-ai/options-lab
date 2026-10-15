# R54: the Rs 1,000 hero ticket at 14:30 instead of 12:30, and exits at 15:10 / 15:15 (20 Nov 2024 - 13 Oct 2026)

Written 15 Oct 2026, for Boss: "What if we enter at 2:30 pm and exit at 3:10/3:15?"

- **Pre-registration:** `research/hunt/r54/PREREG.md`, commit `04437bbf`, pushed before any P&L.
  - Amendment 1 (R47's stray-print guard can refuse a genuine fill just before a jump) was written after the design run
    and before the final test was read. It was pushed with the code and design results (`015aea30`).
- **Code, tests and tables:** `research/hunt/r54/`. `results/report.md` holds every table; the other results files are
  `design.json`, `final.json` and `liquidity.json`. Trade lists stay on the cache, not in git.
- **Experiment entries:** `research/experiments/entries/r54-*.yaml`.
- **Research only:** nothing in the app changes. The 15:05 square-off stays as it is.

## Verdict for Boss

**14:30 is not better than 12:30, and exiting at 15:10 / 15:15 adds nothing you can rely on. No cell passes.**

- **With your 25% trail, 14:30 is worse than 12:30.**
  - Design (142 expiry days): 12:30 lost Rs 14,507 on 248 tickets (-Rs 91 per Rs 1,000 staked). 14:30 lost Rs 37,353 on
    208 tickets (-Rs 338 per Rs 1,000). Only 5% of the 14:30 tickets made any profit.
  - Final test (55 expiry days, read once): 12:30 made +Rs 1,559 (107 tickets) and 14:30 lost Rs 6,029 (98 tickets).
  - Day by day, 14:30 minus 12:30 is -Rs 161 a day in design (95% CI -307 to -56) and -Rs 138 in the final test.
    The pre-registered reading is "worse".
  - Why: by 14:30 a cheap expiry option swings 18-25% inside a single minute (median bar range, design data). A 25%
    trail on minute closes then fires almost at once. Half of the 14:30 tickets were out within 4 minutes in design
    (136 of 208 within 5 minutes), most at a loss.
- **Without the trail (hold to the end), 14:30 and 12:30 lose about the same.** Design: -Rs 45,193 vs -Rs 48,733 at 15:05.
  Final: -Rs 12,284 vs -Rs 12,331. Paired reading: no clear difference. Both lose Rs 190-410 of every Rs 1,000 staked.
- **15:10 / 15:15 instead of 15:05:**
  - **With the trail it makes no difference, because the trail is almost always out before 15:05.** In design, 0 of 940
    trail tickets were still held at 15:05; the latest exit was 14:53. In the final test, 5 of 417 were still held (1
    from 13:30, 1 from 14:00, 3 from 14:30). The end time only matters if you hold without a trail.
  - **Held without a trail, the later exits move the result by small amounts that are not reliable.** Per expiry day,
    15:15 minus 15:05 is -Rs 8 to +Rs 39 in design and +Rs 46 to +Rs 76 in the final test. Every 95% CI includes 0
    (reading: "no clear difference" in all 16 comparisons). They also remain losing tickets: -Rs 9,545 to -Rs 21,616
    in the final test at 15:15.
  - These numbers already carry a wider spread for the last minutes (below). Changing the 15:05 rule is your call. The
    data gives no reason to change it for the hero ticket.
- **Nothing beats random timing after BH, and nothing passes the paper gate.**
  - The best final cell is 12:30 + trail: +Rs 1,559, p 0.024, but q 0.21.
  - That cell is 582% its top ticket and 832% its top 5. Without its top 5 it is -Rs 11,411, and it lost in design.
  - 14:30 + trail scores p 1.00 in design: every random afternoon minute did better.

## The two days you asked about

| day | ticket | 12:30 entry | 14:30 entry |
|---|---|---|---|
| **2 Apr 2026, SENSEX** | CE | 72,500 CE at Rs 24.91 went 29x. Held: +Rs 13,958 at 15:05, +Rs 17,261 at 15:15 (35.8x). **The 25% trail sold it at Rs 41.38 (2.7x peak at that point), +Rs 280.** | The rally had already happened. The 73,500 CE at Rs 27.96 peaked 1.3x: held -Rs 584 (15:05) / -Rs 504 (15:15); trail -Rs 382 |
| | PE | 71,200 PE at Rs 27.71 expired worthless: held -Rs 599; trail -Rs 205 | 72,700 PE at Rs 23.86: held -Rs 504; trail -Rs 257 |
| **13 Oct 2026, NIFTY** | CE | 22,650 CE at Rs 9.55 went 9.1x. Held: +Rs 2,719 (15:05), +Rs 3,259 (15:15). **The trail sold it at Rs 8.15 before the move, -Rs 140.** | 22,650 CE at Rs 13.41 went 6.5x. **Trail +Rs 2,633**; held +Rs 2,468 (15:05), +Rs 3,008 (15:15) |
| | PE | 22,450 PE at Rs 10.16: held -Rs 679; trail -Rs 186 | 22,550 PE at Rs 7.80: held -Rs 480; trail -Rs 301 |

- So 13 Oct is a day where 14:30 + trail wins.
- 2 Apr is a day where only a 12:30 entry held without a trail wins big.
- Either way it is one ticket a day, and it does not repeat often enough to carry the rest.

## What was tested

- **Window:** NIFTY and SENSEX own expiry days, 20 Nov 2024 - 13 Oct 2026 only (Boss: nothing before Nov 2024).
  - The list is R50's: 100 NIFTY and 97 SENSEX days. It was checked against `r50/results/expiry_days.json` and matches
    exactly.
  - DESIGN = 20 Nov 2024 - 31 Mar 2026: 142 days (72 NIFTY, 70 SENSEX).
  - FINAL TEST = 1 Apr - 13 Oct 2026: 55 days (28 NIFTY, 27 SENSEX).
  - **Honest note:** the final test is not pristine. R43, R44, R45, R47 and R50 already read these months. R50 found
    the 2 Apr 2026 SENSEX call and the 13 Oct 2026 call. Only live paper from 15 Oct 2026 is unseen.
- **The ticket:**
  - Signal source: none (time only, both sides). It is a fixed-time basket; the strike rule reads option prices.
  - At the entry time, buy 1 lot of a CE and 1 lot of a PE. Each is the nearest-the-money eligible OTM strike priced
    Rs 5 to min(30, 1,000 / lot). That is R50's "cheap" band capped at Rs 1,000 a lot: NIFTY Rs 5-13.33 (lot 75) or
    Rs 5-15.38 (lot 65), SENSEX Rs 5-30 (lot 20).
  - A ticket whose fill x lot is over Rs 1,000 is skipped.
- **Cells (24):** entry {12:30, 13:30, 14:00, 14:30} x end {15:05, 15:10, 15:15} x exit {25% trail, hold}.
  - Trail = R50's tr25. A minute close <= 75% of the best close since the fill sells everything at the next minute's
    open. The best close starts at the fill, so it is also a 25% stop.
  - Whatever is still held sells at the open of the end-time bar.
- **Measurement (never relaxed):**
  - One held contract per ticket, never the rolling ATM series.
  - Next-minute fills, capped at ref x 1.2 + 0.10 for 3 minutes; never chasing. Gaps fill at the worse open.
  - R17 liquidity spread (5 bps plus 1 / 2 / 4 ticks by 5-minute volume), with a 2x stress.
  - Charges in force on the date (STT 0.15% from 1 Apr 2026; BSE rates for SENSEX) and lots in force on the date.
  - R47's foreign-rows filter: rows whose own spot is more than 3% off the index are dropped. It dropped nothing
    inside the window (20 rows on a 2024 SENSEX day).
  - R47's stray-print guard (see Amendment 1 below).
- **Last-minute liquidity (measured on design days before any P&L).** The lake has no bid / ask. On OTM contracts
  priced Rs 0.05-30, the median 1-minute bar range grows through the afternoon:

| time | median range of a 1-minute bar | minutes with no trade | spread multiplier used |
|---|---|---|---|
| 14:00-14:59 (base) | 14-26% (median 18.3%) | 3.2-3.7% | 1 |
| 15:00-15:09 | 40% | 4.2-4.3% | 2.25 |
| 15:10-15:29 | 50% | 3.2-3.7% | 2.75 |

  - Trading does not dry up (no-trade minutes stay at 3-4%), but the bars get much wider.
  - Part of that is composition: many contracts are worth under Rs 1 by then, where one tick is a large share of the
    price. So the multiplier is, if anything, too harsh.
  - It is applied to every fill from 15:00 on: x2.25 to 15:09, x2.75 from 15:10. That includes the 15:05 exit, so all
    three end times are treated alike.
  - With that, the 2x-spread stress moves the results only by a few hundred rupees.
- **Exchange timing on expiry day.** NSE and BSE index options trade until 15:30. Both settle in cash on the index
  close, which is the average of its last 30 minutes (15:00-15:30). Exiting at 15:10 / 15:15 is allowed. The 15:05
  square-off is the app's own rule (CLAUDE.md).
- **Statistics:**
  - Twins: 500 random-twin sets per cell. Each ticket is re-entered at a random minute of 12:00-14:45 (same day, side,
    strike rule, 1-lot rule, end time and exit). So p asks "is this entry time better than a random afternoon minute".
  - BH runs over all 24 cells, in each period.
  - Paired day-by-day differences use a 95% bootstrap CI (5,000 resamples).
  - The pre-registered reading: "better" or "worse" only when the sign agrees in design and final AND the design CI
    excludes 0.

## Results (main cells; all 24 in `hunt/r54/results/report.md`)

"per 1,000" = net per Rs 1,000 staked (premium + buy charges). "top-1 / top-5" = their share of a positive net.

### Final test (1 Apr - 13 Oct 2026, 55 expiry days, read once)

| entry | end | exit | tickets | hit | reached 2x / 5x / 10x | net | per 1,000 | worst day | max DD | top-1 / top-5 | without top 5 | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 12:30 | 15:05 | trail | 107 | 22% | 12 / 2 / 1 | +1,559 | +24 | -557 | 5,359 | 582% / 832% | -11,411 | 0.024 | 0.21 |
| 14:30 | 15:05 | trail | 98 | 24% | 10 / 2 / 0 | -6,029 | -108 | -706 | 8,361 | - | -13,775 | 0.463 | 0.84 |
| 14:30 | 15:10 | trail | 98 | 22% | 10 / 2 / 0 | -6,232 | -111 | -706 | 8,564 | - | -13,880 | 0.501 | 0.84 |
| 14:30 | 15:15 | trail | 98 | 24% | 10 / 2 / 0 | -5,822 | -104 | -706 | 8,154 | - | -13,481 | 0.439 | 0.84 |
| 13:30 | 15:05 | trail | 107 | 15% | 12 / 0 / 0 | -10,350 | -158 | -648 | 11,990 | - | -15,027 | 0.802 | 0.84 |
| 14:00 | 15:05 | trail | 105 | 19% | 13 / 0 / 0 | -9,659 | -156 | -600 | 10,344 | - | -14,820 | 0.735 | 0.84 |
| 12:30 | 15:05 | hold | 107 | 15% | 32 / 11 / 3 | -12,331 | -190 | -1,752 | 27,730 | - | -41,971 | 0.176 | 0.84 |
| 12:30 | 15:15 | hold | 107 | 13% | 32 / 11 / 3 | -9,545 | -147 | -1,739 | 28,806 | - | -43,191 | 0.228 | 0.84 |
| 14:30 | 15:05 | hold | 98 | 20% | 29 / 7 / 0 | -12,284 | -219 | -1,521 | 14,272 | - | -26,169 | 0.391 | 0.84 |
| 14:30 | 15:10 | hold | 98 | 21% | 29 / 9 / 0 | -8,443 | -151 | -1,619 | 10,952 | - | -25,316 | 0.383 | 0.84 |
| 14:30 | 15:15 | hold | 98 | 20% | 31 / 9 / 1 | -9,779 | -174 | -1,632 | 12,278 | - | -27,034 | 0.455 | 0.84 |

12:30 + trail at 15:10 / 15:15 is identical to 15:05 (no trail ticket was open at 15:05).

### Design (20 Nov 2024 - 31 Mar 2026, 142 expiry days)

| entry | end | exit | tickets | hit | reached 2x / 5x / 10x | net | per 1,000 | worst day | max DD | without top 5 | p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| 12:30 | any | trail | 248 | 16% | 19 / 4 / 1 | -14,507 | -91 | -549 | 18,386 | -28,596 | 0.108 | 0.43 |
| 13:30 | any | trail | 245 | 20% | 34 / 1 / 0 | -13,475 | -92 | -642 | 13,732 | -21,980 | 0.086 | 0.43 |
| 14:00 | any | trail | 239 | 16% | 26 / 1 / 1 | -25,260 | -183 | -761 | 26,219 | -31,687 | 0.731 | 0.88 |
| 14:30 | any | trail | 208 | 5% | 7 / 1 / 0 | -37,353 | -338 | -832 | 37,353 | -40,264 | 1.000 | 1.00 |
| 12:30 | 15:05 | hold | 248 | 10% | 73 / 25 / 8 | -48,733 | -305 | -1,999 | 53,096 | -91,172 | 0.257 | 0.62 |
| 12:30 | 15:15 | hold | 248 | 10% | 73 / 25 / 9 | -49,945 | -313 | -2,032 | 53,851 | -92,392 | 0.339 | 0.74 |
| 14:30 | 15:05 | hold | 208 | 12% | 46 / 16 / 3 | -45,193 | -409 | -1,820 | 47,546 | -64,581 | 0.180 | 0.50 |
| 14:30 | 15:10 | hold | 208 | 14% | 51 / 16 / 4 | -41,454 | -375 | -1,892 | 44,730 | -65,391 | 0.188 | 0.50 |
| 14:30 | 15:15 | hold | 208 | 14% | 51 / 18 / 6 | -39,673 | -359 | -1,907 | 40,655 | -64,761 | 0.188 | 0.50 |

- No design cell made money. "Any" end: the trail cells are identical at 15:05, 15:10 and 15:15.
- Held tickets do reach big multiples: 25 of 248 tickets at 12:30 touched 5x and 8 touched 10x in design. They are still
  far too rare to pay for the rest.

### The questions, as paired day-by-day differences (per index-expiry-day)

| comparison | design mean [95% CI] | final mean [95% CI] | reading |
|---|---|---|---|
| 14:30 minus 12:30, trail (any end) | -161 [-307, -56] | -134 to -142 [about -490, +118] | **worse** |
| 14:30 minus 12:30, hold to 15:05 | +25 [-370, +373] | +1 [-715, +563] | no clear difference |
| 14:30 minus 12:30, hold to 15:15 | +72 [-317, +416] | -4 [-879, +653] | no clear difference |
| 15:10 minus 15:05, trail (any entry) | 0 | -4 to +5 | no clear difference (the trail is out before 15:05) |
| 15:10 minus 15:05, hold (by entry) | -31 to +36 | +36 to +70 | no clear difference (every CI includes 0) |
| 15:15 minus 15:05, hold (by entry) | -12 to +39 | +46 to +76 | no clear difference (every CI includes 0) |

### Paper gate (all needed: final net > 0, p < 0.05, q <= 0.10, 40+ tickets, without top 5 > 0, design net > 0)

**0 of 24 pass.**

- 12:30 + trail (any end) passes net > 0, p and 40+ tickets. It fails q (0.21), "without top 5" and "design net".
- Every other cell loses in the final test.

## Honest notes

- **Amendment 1, the stray-print guard.** R47's guard compares a fill's open with the median of the closes before, at
  and after it, so it reads the next bar.
  - On a cheap option that jumps more than 20% in the next minute, it treats a genuine open as a stray print. Then it
    takes the worse price, or skips the ticket.
  - Example: NIFTY 13 Jan 2026, 25,650 CE. It opened at 6.90 on heavy volume and closed 24.70 a minute later; the guard
    refused the fill.
  - The guard stayed primary, as pre-registered. Each cell also shows the net without it (informational only).
  - It does not change any answer. Final 12:30 + trail: +Rs 3,047 without the guard vs +Rs 1,559 with it. Final
    14:30 + trail: -Rs 3,638 vs -Rs 6,029. Design cells are -Rs 10,618 to -Rs 61,471 without it.
- **The 25% trail on 1-minute closes is very tight for expiry options after 14:00.** Their minute-to-minute swings are
  as big as the trail. Median time held: 12 minutes at 12:30 and 4 minutes at 14:30 (design).
  - The trail does cut losses (12:30: -Rs 91 per Rs 1,000 with it vs -Rs 305 held, design).
  - It also sells the rare runner early: 2 Apr SENSEX was sold at 2.7x, not 29x.
  - A wider trail or a trail on 5-minute closes was not tested here. That would be a new pre-registered study.
- **Not every side gets a ticket.** A side is skipped when no eligible strike is in the band, when there is no fill
  within 3 minutes, or when the fill x lot is over Rs 1,000. In design, NIFTY got 135 of 144 possible tickets at 12:30
  and 96 of 144 at 14:30 (Rs 5-13 options are scarcer late). SENSEX got 113 and 112 of 140. A missing ticket counts 0 in
  the paired comparisons, and the twins re-enter only tickets that traded.
- **Small samples.** 55 final-test days: one or two tickets can swing a cell by Rs 3,000-17,000.

## Checks run

- `python -m pytest research/hunt/r54/test_r54.py -q`: 40 tests. They cover:
  - entry rows for each entry time, next-minute fills and no chasing;
  - the end-time sell at the bar that starts at 15:05 / 15:10 / 15:15, including a missing end bar and no bar left;
  - the trail: the 25% stop, the trail from the best close, closes read only up to end - 1 minute, gap fills, and
    "hold" having no trail;
  - the late multiplier: only from 15:00, never on the 14:30 entry, its measurement and rounding, and the JSON load;
  - the 2x stress, the 1-lot Rs 1,000 rule and the band per lot, and the strike pick;
  - a worthless exit paying no sell charges;
  - the stray-print guard (sells, buys, highs, and the off switch);
  - the foreign-rows filter;
  - metrics, paired CIs and readings, twins and p, the gates and the window split;
  - the look-ahead guard on real days (masked and poisoned futures pick the same strike).
- Brain tests and the catalog check, the R50 tests, `python research/experiments/validate.py`, and the knowledge pack
  rebuild with `--check`.
