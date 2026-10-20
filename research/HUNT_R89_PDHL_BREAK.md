# R89: Boss's previous-day high / low break on the 1-minute chart (20 Oct 2026)

Boss, 20 Oct: mark yesterday's high and low; on the 1-minute chart wait for a break, then a green (red) candle, enter
when the next candle breaks that candle's high (low), stop at its other end, target the previous swing. Research only:
nothing in `android/**` or `.github/**` changed.

- Plan: `research/hunt/r89_pdhl_break/PREREG.md`, committed (6cd81b9) and pushed before any statistic. Clarifications
  N1 (`NOTES.md`, commit 13affee) before any P&L. Design results and the 20 experiment entries committed (553070d)
  before the one holdout read (`results/FINAL_READ`).
- Code: `research/hunt/r89_pdhl_break/` (`rules.py` setup state machine, swings, exits; `data.py` panels; `price.py`
  option / futures pricing and twins; `study.py`; `arms.py`; `report.py`; `entries.py`; `store.py`). Tests: `tests/`
  (24, incl. the look-ahead guard and a parity check against R53's MCX option simulator).
- Every table: `results/report.md` (design, folds, holdout, pooled, older history, arms). JSON: `design.json`,
  `final.json`, `old_futures.json`, `points_design.json`, `points_final.json`, `arms.json`.
- **Signal source of every rule: the UNDERLYING** (index spot 1-minute OHLC; MCX the near future itself). The trade is
  always buying the 1-ITM call / put on its real minute prints. No rule reads an option's chart; no option-chain data.

## The answer for Boss

**It does not pay after costs, on any instrument or setting. 0 of 180 cells passed design; the holdout (1 Apr - 19 Oct
2026, read once) confirmed it: every reading lost on every index and every MCX book in total. Nothing goes to paper.**

1. **The break-and-candle entry is about as good as a random entry minute.** Across the five indices the base rule
   lost Rs 3.07 lakh in design (2,925 option trades) while random-entry twins with the same stop and target distances
   lost Rs 3.13 lakh (500 twins a cell). Holdout: -Rs 1.28 lakh (1,210 trades) vs twins -Rs 1.65 lakh. The underlying moved only
   +0.4 to +3.5 index points a trade in the rule's favour at the exact levels (no costs), while the 1-ITM option's round
   trip costs 1.1 (NIFTY) to 3.7 (SENSEX) index points. The wall is the same as in R20c / R87: the move is smaller than
   the cost.
2. **Whipsaws are real and the higher-timeframe filter does not fix them.** About a third of base trades (32-36%) are
   stopped within 3 candles (false breaks). With the daily + 15-minute trend filter on, false breaks stay the same or
   rise (NIFTY 32% -> 39%, BANKNIFTY 33% -> 34%) and the win rate falls (NIFTY 36% -> 26%). The filter loses less only
   because it trades less than half as often (indices design -Rs 1.67 lakh on 1,124 trades vs -Rs 3.07 lakh on 2,925).
3. **"Break and go" vs "break and reclaim":** both lose about the same (design indices -Rs 3.07 lakh vs -Rs 3.06 lakh;
   holdout -Rs 1.28 lakh vs -Rs 1.27 lakh). The reclaim reading has fewer false breaks (25% vs 34%), not more profit.
4. **One near-miss, and it failed out of sample.** FINNIFTY, break and go on 3-minute candles: design +Rs 56,447 on 474
   trades, twin p 0.002, 3 of 4 folds positive, but BH q 0.12 (gate 0.10) -> fail. Holdout: **-Rs 6,921 on 190 trades**.
   8 of 10 FINNIFTY "break and go" variants beat their twins in design (p 0.002-0.05); in the holdout only one did
   (C-SR, p 0.016) and it still lost (-Rs 6,834 on its trades).
5. **MCX is worse.** On MCX the 1-ITM GOLDM / SILVERM options print so rarely (median 89 / 22 minutes between prints)
   that half the signals cannot be filled; the rest lost (GOLDM base -Rs 43,715 on 47 trades, holdout -Rs 47,229 on
   88). Crude and natural-gas minis: -Rs 8k to -17k each, 10-16% winners. On close-only 1-minute candles the signal candle
   is one minute's move, so the stop is very close: 74-96% of MCX base trades are stopped (n 39-129 a cell).
6. **Boss's account stop would have tripped.** Running the base rule on all five indices (about 9 trades a day) loses
   more than Rs 13,000 in 12 of 17 design months and 6 of 7 holdout months (worst -Rs 38,297 Jan 2025; -Rs 28,912 Jun 2026).
7. **Different from the arms, not better.** 82-84% of R89 trades fall on days the R72 Breadth Donchian arm also
   trades and 88-90% on ORB-signal days, but only about half on the same side, and the daily P&L correlation with
   Breadth Donchian is low (-0.09 to +0.26). Breadth Donchian made +Rs 2.17 lakh (design, 3,071 trades) and +Rs 1.70 lakh
   (holdout, 1,225) on the same stores; Liquidity BANKNIFTY 15+5 +Rs 1.10 lakh (336) and +Rs 8,211 (136). R89 lost on
   the arms' days and on the other days alike. It would add losses, not diversification.

Plain answer: yesterday's high / low breaks on 1-minute candles are a coin toss once the option's spread, charges and
the one-minute delay are paid. Waiting for a confirming candle and a break of it, or adding the 15-minute and daily
trend, does not change that. Keep it as a note; Jarvis may mention PDH / PDL as context levels only.

## Data

| market | signal | option prints | design | holdout |
|---|---|---|---|---|
| NIFTY, BANKNIFTY, SENSEX, FINNIFTY, MIDCPNIFTY | lake `index_1m` spot 1m OHLC | lake `options_rolling_1m` WEEK, R51 loader (held contracts by strike, Amendment A clean + 18 Oct stale-print step) to 19 Oct 2026; 474 option days each | 20 Nov 2024 - 31 Mar 2026 (336 sessions) | 1 Apr - 19 Oct 2026 (137) |
| GOLDM, SILVERM, CRUDEOIL (mini), NATURALGAS (mini) | near future = median `spot` of the option rows (R53 Market; close-only) | lake MCX MONTH books GOLDM, SILVERM, CRUDEOILM, NATGASMINI (ATM-3..+3, R42 spike flags) | 5 Aug 2025 - 31 Mar 2026 | 1 Apr - 19 Oct 2026 |
| index, older history | lake `index_1m` | none (futures-only, descriptive) | 2 Aug 2021 - 19 Nov 2024 | - |

Copied from Drive `gdrive:IraAlgo-data-lake` on 20 Oct 2026 (nothing committed). The stores reproduce earlier work:
Liquidity BANKNIFTY 15+5 gives 336 design trades and +Rs 1,10,094, R80's B0 to the rupee.

## Rules (PREREG section 3; signal source: the underlying for every rule)

- Levels: previous session's high (PDH) and low (PDL); SR variant adds the nearest 15-minute fractal swing high above /
  low below the day's open (previous 5 sessions).
- **C, "break and go":** a candle closes beyond a level (the break); the first later green (red) candle that closes
  beyond it is the signal candle; the very next candle breaking its high (low) is the entry (1-minute resolution). A close
  back through the level cancels; the signal candle must come within 15 candles.
- **R, "break and reclaim" (the literal "long after a PDL reclaim"):** a candle closes below (above) a level; the first
  green (red) candle back above (below) it is the signal candle; same trigger.
- Stop: the signal candle's other end (on the underlying). Target: the nearest confirmed fractal swing beyond entry with
  reward >= min RR x risk (today + previous 5 sessions); none -> no trade. Session end 15:10 (15:05 on expiry; MCX 10
  minutes before the close). At most 3 trades a day per instrument, one open at a time; entries 09:16-14:45 (MCX to 22:30).
- Option: 1-ITM, nearest expiry, bought at the next minute's open (index: R51 engine, 5 bps + 1-4 ticks by volume,
  dated charges; MCX: R53 rules + R85 strict half-spread); underlying stop / target -> sold the next minute. 1 lot in
  force; a fixed-risk view (Rs 2,000, 1-3 lots) is shown too.
- Variants (one change each from the base): SR levels, wick break, 3m / 5m candles, HTF filter (previous close vs
  20-day average and the last 15m close vs its EMA20), fractal N = 3, min RR 2, no trigger before 09:30 and no expiry
  day, first trade of the day only. 10 x 2 readings = 20 variants x 9 instruments = 180 cells; BH over all 180.
- Gates: >= 40 trades, net > 0, twin p < 0.05, BH q < 0.10, >= 3 of 4 folds positive (MCX 2 of 3), positive without
  the top 5, worst month >= -Rs 13,000.

## Design results (option, 1 lot, after costs)

Base rules per instrument (`report.md` has all 180):

| instrument | reading | trades (a day) | not filled | win | avg win / loss | per trade | net | max DD | worst month | false breaks | twin mean | twin p | q |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | C | 570 (1.70) | 0 | 36% | 740 / -576 | -105 | -60,012 | 66,736 | -12,447 | 32% | -41,576 | 0.7605 | 1.00 |
| NIFTY | R | 466 (1.39) | 0 | 41% | 738 / -647 | -82 | -38,254 | 45,808 | -12,034 | 24% | -48,792 | 0.3234 | 1.00 |
| BANKNIFTY | C | 591 (1.76) | 0 | 36% | 754 / -569 | -88 | -51,868 | 55,432 | -12,329 | 33% | -63,984 | 0.2854 | 1.00 |
| BANKNIFTY | R | 486 (1.45) | 0 | 37% | 678 / -672 | -169 | -82,216 | 85,779 | -14,112 | 24% | -55,381 | 0.9162 | 1.00 |
| SENSEX | C | 575 (1.71) | 0 | 33% | 652 / -465 | -100 | -57,192 | 68,504 | -14,357 | 36% | -39,441 | 0.8024 | 1.00 |
| SENSEX | R | 473 (1.41) | 0 | 41% | 602 / -534 | -68 | -32,326 | 39,936 | -9,510 | 27% | -42,683 | 0.3014 | 1.00 |
| FINNIFTY | C | 561 (1.67) | 9 | 35% | 868 / -468 | -6 | -3,470 | 28,151 | -12,436 | 34% | -62,519 | 0.004 | 0.18 |
| FINNIFTY | R | 441 (1.31) | 10 | 34% | 755 / -553 | -105 | -46,363 | 47,581 | -17,445 | 25% | -64,509 | 0.1597 | 1.00 |
| MIDCPNIFTY | C | 628 (1.87) | 1 | 33% | 982 / -811 | -215 | -134,763 | 141,918 | -18,199 | 33% | -105,911 | 0.8323 | 1.00 |
| MIDCPNIFTY | R | 510 (1.52) | 0 | 38% | 866 / -876 | -210 | -106,937 | 116,903 | -16,464 | 23% | -89,449 | 0.7186 | 1.00 |
| GOLDM | C | 47 (0.31) | 50 | 11% | 412 / -1,090 | -930 | -43,715 | 43,715 | -26,301 | 21% | -19,784 | 1.0 | 1.00 |
| GOLDM | R | 44 (0.29) | 38 | 11% | 951 / -1,476 | -1,200 | -52,807 | 52,807 | -18,331 | 7% | -24,652 | 1.0 | 1.00 |
| SILVERM | C | 42 (0.28) | 73 | 14% | 974 / -1,435 | -1,091 | -45,817 | 45,968 | -27,376 | 17% | -16,429 | 1.0 | 1.00 |
| SILVERM | R | 39 (0.26) | 57 | 21% | 743 / -1,388 | -951 | -37,093 | 37,292 | -19,257 | 15% | -17,788 | 0.994 | 1.00 |
| CRUDEOIL (mini) | C | 129 (0.86) | 4 | 10% | 122 / -125 | -100 | -12,943 | 12,943 | -4,080 | 18% | -10,248 | 1.0 | 1.00 |
| CRUDEOIL (mini) | R | 106 (0.71) | 5 | 12% | 238 / -124 | -79 | -8,411 | 8,411 | -2,533 | 16% | -8,309 | 0.517 | 1.00 |
| NATURALGAS (mini) | C | 93 (0.62) | 5 | 13% | 614 / -213 | -106 | -9,886 | 11,878 | -2,534 | 23% | -10,092 | 0.4511 | 1.00 |
| NATURALGAS (mini) | R | 79 (0.53) | 4 | 11% | 529 / -229 | -142 | -11,238 | 12,108 | -3,174 | 15% | -8,916 | 0.8663 | 1.00 |

Sessions: 336 per index, 149-150 per MCX book (expiry and roll days excluded).

Pooled (not tested): indices C-B -Rs 3,07,305 (2,925 trades; twins -Rs 3,13,431; fixed-risk -Rs 5,55,486); R-B
-Rs 3,06,096 (2,376; twins -Rs 3,00,814). MCX C-B -Rs 1,12,361 (311), R-B -Rs 1,09,549 (268). Best pooled index
variant: C-T5 -Rs 1,57,529 (2,065; twins -Rs 18,702), i.e. worse than random entries.

- Positive design cells: 6 of 180, all small or FINNIFTY (C-T3 +56,447; C-T5 +20,213; C-WK +8,728; C-F1 +8,665; C-HTF
  +1,570) and SENSEX R-F1 +1,547. Lowest BH q 0.12 (FINNIFTY C-SR / C-T3 / C-WK, p 0.002). **0 pass.**
- Selection walk-forward (pick the best variant before each fold): indices -Rs 1,45,078 out of fold vs C-B -Rs 2,08,037;
  MCX -Rs 1,19,286 vs C-B -Rs 1,00,817. Choosing settings does not rescue it.
- Futures-only (descriptive): every cell loses heavily (indices C-B -Rs 23.3 lakh on 2,925 trades) because the
  pre-registered slippage (1 bp in, 2 bp on stops) is about 7 NIFTY points a trade. At the exact levels with no cost the
  underlying made +0.4 (NIFTY, MIDCPNIFTY) to +3.5 (BANKNIFTY, FINNIFTY) points a trade in design, 44-50% winners.
- Older index history 2021-2024 (futures-only, descriptive, `old_futures.json`): the same picture, 1,086-1,462 trades
  per index and reading, gross negative after slippage on all five indices; false breaks 33-38% (C) / 23-29% (R).

## Holdout (1 Apr - 19 Oct 2026, read once)

No design passer, so no paper candidate could arise. Read for all 180 cells, descriptively:

| instrument | C-B trades | C-B net | R-B trades | R-B net | C-HTF net (trades) | false breaks C-B |
|---|---|---|---|---|---|---|
| NIFTY | 241 | -11,229 | 191 | -21,256 | -14,854 (101) | 34% |
| BANKNIFTY | 252 | -24,847 | 208 | -30,636 | -13,247 (93) | 33% |
| SENSEX | 247 | -3,316 | 205 | -21,673 | -6,652 (101) | 34% |
| FINNIFTY | 206 | -33,644 | 177 | -15,634 | -2,477 (78) | 29% |
| MIDCPNIFTY | 264 | -54,886 | 221 | -37,814 | -18,863 (103) | 33% |
| GOLDM | 88 | -47,229 | 64 | -32,495 | | |
| SILVERM | 91 | -62,232 | 74 | -70,691 | | |
| CRUDEOIL (mini) | 135 | -17,480 | 119 | -13,898 | | |
| NATURALGAS (mini) | 118 | -11,328 | 105 | -12,572 | | |

- Pooled indices: C-B -Rs 1,27,922 (1,210 trades; twins -Rs 1,64,924), R-B -Rs 1,27,013 (1,002), C-HTF -Rs 56,093 (476).
- 5 of 180 holdout cells were positive, all design losers (SENSEX C-SR +12,084, FINNIFTY R-T3 +11,933, SENSEX C-F1
  +9,164, FINNIFTY C-T5 +1,889, SENSEX C-WK +1,702); lowest holdout q 0.43.
- Worst day per index (C-B) -Rs 2,869 to -Rs 4,897; worst month -Rs 5,139 to -Rs 17,462 per index.

## Same days as the running arms (`arms.json`)

| | design | holdout |
|---|---|---|
| Breadth Donchian (R72 C1) on the same stores | +Rs 2,16,929 (3,071 trades) | +Rs 1,69,712 (1,225) |
| Liquidity BANKNIFTY 15+5 | +Rs 1,10,094 (336) | +Rs 8,211 (137) |
| ORB signal days (R20 V0, signals only) | 1,659 signals | 690 |
| R89 C-B trades on a Breadth Donchian day (same side / opposite) | 84% (66% / 58%) | 82% (63% / 56%) |
| R89 C-B on an ORB-signal day (same side) | 90% (56%) | 88% (53%) |
| R89 C-B daily correlation with Breadth Donchian, per index | -0.02 to +0.18 | +0.09 to +0.26 |
| R89 C-B net on Breadth Donchian days / other days | -2,59,866 (2,467) / -47,439 (458) | -1,18,283 (989) / -9,639 (221) |

(Same side and opposite can both hold: the arms often trade both ways in a day.) R89 is not the same signal as the arms
(half the time it is on the other side) and it does not make money on either kind of day.

## Honest limits

- The option is bought one minute after the trigger and sold one minute after the stop / target touch; a live
  stop-entry would be about the same on an option (no order rests at an underlying level), but on futures it would fill
  at the level, which the futures-only view does.
- Index futures were proxied by the spot (basis ignored); the futures slippage (1 bp / 2 bp) is pre-registered and
  dominates that view, so futures-only is shown as context, not as a verdict on futures trading.
- MCX: close-only minutes (no true futures OHLC before May 2026) make 1-minute candles thin and stops tight; the wick
  variant is identical to the close variant there (with open = the previous close, a high above a level means a close
  above it). GOLDM / SILVERM 1-ITM options often had no print within 2 minutes (no trade): 50 / 73 of the base design
  signals.
- The holdout overlaps periods other studies read for other ideas; 14-19 Oct is new to all.
- 20 variants is a small, declared slice: other definitions (another swing rule, ATM strikes, premium stops) were not run.

## What would be worth testing next (not done; needs a new pre-registration)

- Nothing here is close enough for paper. If PDH / PDL is revisited, the only lead is FINNIFTY "break and go" beating
  random entries in design (p 0.002-0.05 on 8 of 10 variants) and not in the holdout: a longer FINNIFTY history with real
  options (2021-2024 weeklies) could tell whether that was chance.

---

# R89B: the enhanced versions (20 Oct 2026) - 0 of 45 pass; the one lead failed out of sample

Boss, 20 Oct: "Can we enhance it and try something more with it". Boss chose all four directions offered (bigger candles
+ wider stop, let winners run, breadth confirmation, day-type filters) and the unseen test data, Aug 2021 - Nov 2024
(an exception to the Nov 2024 rule, for this test only). Plan: `hunt/r89_pdhl_break/PREREG_B.md` (commit 867191d) before
any R89B number; amendment A1 (`NOTES_B.md`, 5e8ce94) after the design and before the holdout. Code `rules_b.py`,
`study_b.py`, tests `tests/test_r89b.py` (11, incl. the look-ahead guard across indices). Results `results_b/`.
**Signal source of every rule: the underlying** (each index's spot; breadth reads the other indices' spot). MCX was
dropped: it has no unseen option data.

## The answer for Boss

**The enhancements do not make it pay. 0 of 45 cells passed design (20 Nov 2024 - 19 Oct 2026). The one setting that
looked good, FINNIFTY on 5-minute candles with an ATR stop, let-winners-run exit and breadth confirmation, made
+Rs 78,202 on 314 design trades and then LOST Rs 40,647 on 401 trades in 2021-2024. Nothing goes to paper.**

1. **Bigger candles and wider stops made it worse, not better.** 15-minute candles with the swing target lost
   Rs 5.89 lakh on 1,789 design trades (1m: Rs 4.35 lakh on 4,135). The move grows with the stop, but the edge in points
   does not: at the exact levels the 15m trades made -13.4 (SENSEX) to +3.9 (MIDCPNIFTY) points a trade.
2. **Letting winners run helped a little, not enough.** The breakeven-at-+1R plus 1R trail exit (B2) cut the 15m loss
   from Rs 5.89 to 5.57 lakh in design and lost Rs 2.27 lakh on 3,050 holdout trades. The ATR stop cut false breaks from
   about 33% to 13-15%, but the trades still lost (B6 design -Rs 4.59 lakh, holdout -Rs 3.06 lakh).
3. **Breadth was the most useful filter, but only as a loss-cutter.** Requiring 4 of 5 indices to have broken their own
   level (B3) cut the pooled loss by 62% in design (-Rs 2.10 lakh on 1,387 trades) and 76% in the holdout (-Rs 54,974 on
   1,607), mostly by trading less. It never turned positive overall. Opening-range alignment (B4) and skipping gap days
   (B5) did less; all three together (B8) traded rarely and still lost (-Rs 1.09 lakh design, -Rs 98,761 holdout).
4. **FINNIFTY again looked special in one period and not the other.** FINNIFTY B7 beat hindsight-free random entries in
   design (p 0.002) and passed every other design gate, so it was carried to the holdout (A1). There it lost (-Rs 40,647,
   2 of 3 years negative, after-trigger twin p 0.68). Meanwhile FINNIFTY B3 and SENSEX B2 / B3, which lost in design,
   made money in 2021-2024 (+Rs 47,721 / +24,261 / +29,471): signs of noise, not of a stable edge.
5. **A method lesson (A1):** random-entry twins that keep the trade's side and day see the day's break direction in
   advance; with long holds and trailing exits that hindsight makes random entries look very profitable (pooled B2 twins
   +Rs 8.10 lakh). The hindsight-free "after-trigger" twin fixes it. This can only have hidden an edge, never invented
   one; with the fixed twin nothing passes either.

## Results (option, 1 lot, after costs; pooled over the five indices)

| variant | what | design trades | design net | holdout trades | holdout net | false breaks (design / holdout) |
|---|---|---|---|---|---|---|
| B0 | R89 base, 1m | 4,135 | -4,35,226 | 5,092 | -4,15,915 | 33% / 34% |
| B1 | 15m, candle stop, swing target | 1,789 | -5,88,786 | 2,222 | -3,01,456 | 37% / 35% |
| B2 | 15m, run exit | 2,502 | -5,57,059 | 3,050 | -2,26,973 | 32% / 32% |
| B3 | B2 + breadth | 1,387 | -2,09,829 | 1,607 | -54,974 | 31% / 32% |
| B4 | B2 + opening range | 1,006 | -3,60,063 | 1,309 | -2,70,435 | 32% / 32% |
| B5 | B2 + skip gap days | 1,940 | -4,06,641 | 2,274 | -2,33,278 | 33% / 34% |
| B6 | 5m, ATR stop, run exit | 3,242 | -4,58,569 | 4,042 | -3,05,793 | 14% / 15% |
| B7 | B6 + breadth | 1,719 | -1,46,005 | 2,114 | -2,47,864 | 13% / 15% |
| B8 | B2 + breadth + OR + gap | 467 | -1,09,457 | 504 | -98,761 | 31% / 31% |

Per index, design / holdout (Rs): NIFTY B3 -17,381 / -11,701, B8 +21,657 / -31,762; BANKNIFTY every variant negative in
both; SENSEX B2 -1,22,587 / +24,261, B3 -39,508 / +29,471; FINNIFTY B6 +44,830 / -70,351, B7 +78,202 / -40,647, B3
-31,744 / +47,721; MIDCPNIFTY every variant negative in both. Design option sessions 473 per index; holdout NIFTY 814,
BANKNIFTY 811, FINNIFTY 803, MIDCPNIFTY 444 (options from 2023), SENSEX 375 (from May 2023). Every cell, fold, twin and
holdout block: `results_b/design.json`, `results_b/final.json`.

## Honest limits (R89B)

- The design window had already been seen by R89 (for R89's variants), and R89's descriptive futures view had shown the
  2021-2024 index results of R89's own variants; no R89B rule had been run on either window before its plan.
- The carried lead was chosen after seeing the design (A1, mechanical rule); it failed, so this does not change anything.
- 2021-2024 had weekly expiries on every index and different lot sizes; the costs used are those in force each day.

## What is left

Nothing in R89 / R89B is close enough for paper. The consistent facts are useful for the existing arms, not for a new
one: breadth agreement across indices cuts losses on break trades by 60-75% in both periods (the idea R72 Breadth
Donchian already uses), and an ATR stop halves the false breaks of candle stops. PDH / PDL stay context levels for Jarvis.
