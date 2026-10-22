# R103: defined-risk option selling on NIFTY and BANKNIFTY (22 Oct 2026)

Boss (22 Oct 2026): "understand deeply how options selling work", after "find strategies with best win rate and more
profit". The understanding is `research/DEEP_OPTIONS_SELLING.md`; this is the pre-registered test behind it.

- **Pre-registration:** `research/hunt/r103_sell/PREREG.md` (24 tests, gates, periods), committed before any R103
  profit or loss existed. Two dated amendments: Amendment 1 (report columns only: the BUY mirror, a margin estimate, a
  one-day tail count, the tail table) before any number; Amendment 2 (a stricter stale-leg gate) after the design
  results were committed and before the holdout read.
- **Code, tests, tables:** `research/hunt/r103_sell/` (`r103engine.py` the four-leg simulator, `r103study.py` design and
  the one holdout read, `r103stale.py` the stale-leg check, `r103tail.py` the tail table, `r103report.py`,
  `r103entries.py`; 19 tests in `tests/test_r103.py`, including the look-ahead guard and both holdout locks).
  `results/report.md` has every column.
- **Data:** the Drive lake copied 22 Oct 2026 (index_1m, options_rolling_1m WEEK files, the bhavcopy expiry calendar),
  rebuilt into held-contract stores (R51's builder, Amendment A cleaning): NIFTY 1,290 option days, BANKNIFTY 1,287,
  2 Aug 2021 - 19 Oct 2026.
- **Signal source:** none directional. Every structure is centred on the index's 09:20 ATM strike (the underlying);
  stops, locks and targets are on the position's premium.
- **Research only.** Nothing in `android/**` or `.github/**` changed; nothing goes to paper or live.

## Verdict

**No selling rule passes. 0 of 24 tests are confirmed, and the six that passed the design gates were a data artefact.**

- The six design passers were all BANKNIFTY on non-expiry days (iron fly, wide iron fly, iron condor; each with both
  exits). As pre-registered they made +Rs 1.9 to +Rs 5.4 lakh on about 1,000 trades with 62-70% wins, BH q 0.000,
  positive at 2x slippage.
- **That profit came from wings priced at stale prices.** The lake keeps only ATM +-10 strikes at each minute. When
  the index moves, the far wing leaves that window and stops printing, and the engine (as pre-registered) marks and
  sells it at its last close, often 30+ minutes old. A long wing frozen high overstates the seller's profit. On these
  six tests 43-63% of design trades had such a leg at exit (NIFTY non-expiry 11-27%).
- **Repriced** (Amendment 2: Black-Scholes at the leg's last implied volatility, the index at exit, the time left),
  every one of the six loses: -Rs 2.87 to -Rs 3.31 lakh in design, -Rs 18.6k to -Rs 21.0k in the holdout (64 trades).
  The repricing model, checked on legs that did print, runs 1-5 points HIGH, so if anything it flatters the seller.
- The holdout would have rejected them anyway: as pre-registered they made +Rs 34k to +Rs 54k on 64 trades, but the win
  rate (81-86%) moved more than the allowed 15 points from design (62-70%).
- **NIFTY non-expiry: all 6 lose** in design (-Rs 35k to -Rs 1.16 lakh as pre-registered; -Rs 2.1 lakh repriced).
  **Expiry days: all 12 fail.** Two are slightly positive, NIFTY's iron condor with stop (+Rs 28k, 249 trades) and
  BANKNIFTY's wide iron fly with stop (+Rs 15k, 175 trades), but with bootstrap p 0.18 / 0.36 and negative without
  their 5 best days.
- **Before costs the structures roughly break even; the costs make them losers.** Eight orders a trade cost Rs 228-256
  in charges plus about Rs 40 of modelled slippage on one lot (repriced, the BANKNIFTY fly's
  gross was about -Rs 20 a trade and NIFTY's about +Rs 40, against Rs 260-300 of costs). The BUY mirror (the same legs bought) loses about as much
  more, which is what "costs on both sides" looks like.
- **What does make money, and why it is not a rule for us:** the naked 09:20 short straddle held to 15:10 (descriptive,
  no stop, whole lake, stale legs repriced) made +Rs 64k on NIFTY (1,272 days, 62% win) and +Rs 1.51 lakh on BANKNIFTY
  (1,234 days, 65% win), with worst days of -Rs 28,876 and -Rs 29,257 on one lot: more than two months of Boss's
  -Rs 13k stop in one afternoon. The same days with 1-straddle wings lose -Rs 3.0 to -Rs 3.8 lakh: the wings cost more
  than they save, after costs.

## What was tested (24 = 2 indices x 2 day types x 3 structures x 2 exits)

Decision on the 09:20 minute's close; K0 = the ATM strike; S = the ATM call + put close. Orders fill at the 09:21 open
of each leg, after 5 bps + 1-4 ticks of slippage (by the leg's traded volume) and the dated charges of the day.

| structure | sell | buy |
|---|---|---|
| IF iron fly | C(K0), P(K0) | C(K0 + S), P(K0 - S) |
| IFW wide iron fly | C(K0), P(K0) | C(K0 + 1.5 S), P(K0 - 1.5 S) |
| IC iron condor | C(K0 + 0.5 S), P(K0 - 0.5 S) | 1.0 S beyond each short |

Distances are rounded to the strike step and capped at 10 steps. Exits are checked on each minute's close and filled
at the next open: **X1** stop at a loss of 50% of the credit; **X2** that stop + a lock armed at +40% that exits at +20%
+ a target at +60%; otherwise the square-off at 15:10 (15:05 on expiry day). One lot, the lot in force on the day.

Gates (all on design): mean net > 0 with day-bootstrap p < 0.05; BH q <= 0.10 over the 24; net > 0 in 3 of 4 years
2022-2025 and after 20 Nov 2024; 40+ trades; > 0 without the 5 best days; worst month >= -Rs 13,000; > 0 at 2x
slippage; and (Amendment 2) > 0 with stale legs repriced. Holdout (passers only): net > 0, worst month >= -Rs 13,000,
win rate within 15 points of design, and (Amendment 2) > 0 repriced.

## Design results (2 Aug 2021 - 14 Jul 2026), 1 lot, after slippage and charges

Gate numbers in the last-but-one column: 1 bootstrap, 2 BH, 3 years and regime, 5 without the best 5 days, 6 account
stop, 7 2x slippage. Gate 8 (repriced > 0) is the bold column. Max DD is the deepest fall of the cumulative net.
Margin is the stated estimate (2.5% of the short legs' notional, 4.5% on expiry day from 20 Nov 2024).

| index | days | structure | exit | trades | win | net (pre-registered) | net, stale legs repriced | win, repriced | worst day | worst month | max DD | days <= -Rs 13k | margin (est.) | net / margin a year | boot p | BH q | gates failed (pre-registered) | BUY mirror: win / net |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| NIFTY | expiry | iron fly | stop | 249 | 37% | -Rs 58,984 | **-Rs 59,429** | 37% | -Rs 5,989 | -Rs 9,230 | -Rs 62,617 | 0 | Rs 80,226 | -14.9% | 0.966 | 1.000 | 1, 2, 3, 5, 7 | 54% / -Rs 52,765 |
| NIFTY | expiry | iron fly | stop+lock+target | 249 | 43% | -Rs 54,575 | **-Rs 55,325** | 43% | -Rs 5,989 | -Rs 9,230 | -Rs 63,660 | 0 | Rs 80,226 | -13.8% | 0.964 | 1.000 | 1, 2, 3, 5, 7 | 59% / -Rs 53,329 |
| NIFTY | expiry | wide iron fly | stop | 249 | 41% | -Rs 36,518 | **-Rs 36,882** | 41% | -Rs 8,422 | -Rs 11,069 | -Rs 48,164 | 0 | Rs 80,226 | -9.2% | 0.793 | 1.000 | 1, 2, 3, 5, 7 | 44% / -Rs 62,640 |
| NIFTY | expiry | wide iron fly | stop+lock+target | 249 | 47% | -Rs 53,770 | **-Rs 54,160** | 47% | -Rs 8,422 | -Rs 11,069 | -Rs 64,694 | 0 | Rs 80,226 | -13.6% | 0.908 | 1.000 | 1, 2, 3, 5, 7 | 51% / -Rs 88,478 |
| NIFTY | expiry | iron condor | stop | 249 | 43% | Rs 28,347 | **Rs 28,178** | 43% | -Rs 3,676 | -Rs 5,284 | -Rs 18,222 | 0 | Rs 80,226 | +7.1% | 0.183 | 0.627 | 1, 2, 3, 5 | 33% / -Rs 46,659 |
| NIFTY | expiry | iron condor | stop+lock+target | 249 | 52% | -Rs 7,064 | **-Rs 7,237** | 52% | -Rs 3,676 | -Rs 5,314 | -Rs 20,307 | 0 | Rs 80,226 | -1.8% | 0.628 | 1.000 | 1, 2, 3, 5, 7 | 47% / -Rs 78,290 |
| NIFTY | other days | iron fly | stop | 959 | 45% | -Rs 115,087 | **-Rs 207,035** | 43% | -Rs 3,584 | -Rs 11,422 | -Rs 132,656 | 0 | Rs 57,819 | -40.3% | 1.000 | 1.000 | 1, 2, 3, 5, 7 | 24% / -Rs 385,091 |
| NIFTY | other days | iron fly | stop+lock+target | 959 | 45% | -Rs 116,301 | **-Rs 205,547** | 43% | -Rs 3,584 | -Rs 11,422 | -Rs 133,870 | 0 | Rs 57,819 | -40.7% | 1.000 | 1.000 | 1, 2, 3, 5, 7 | 24% / -Rs 381,452 |
| NIFTY | other days | wide iron fly | stop | 958 | 56% | -Rs 35,377 | **-Rs 209,159** | 52% | -Rs 9,293 | -Rs 19,352 | -Rs 104,311 | 0 | Rs 57,790 | -12.4% | 0.782 | 1.000 | 1, 2, 3, 5, 6, 7 | 27% / -Rs 462,387 |
| NIFTY | other days | wide iron fly | stop+lock+target | 958 | 56% | -Rs 36,592 | **-Rs 207,672** | 52% | -Rs 9,293 | -Rs 19,352 | -Rs 105,525 | 0 | Rs 57,790 | -12.8% | 0.788 | 1.000 | 1, 2, 3, 5, 6, 7 | 27% / -Rs 466,928 |
| NIFTY | other days | iron condor | stop | 958 | 54% | -Rs 59,562 | **-Rs 215,487** | 49% | -Rs 5,313 | -Rs 15,352 | -Rs 101,937 | 0 | Rs 57,790 | -20.8% | 0.948 | 1.000 | 1, 2, 3, 5, 6, 7 | 26% / -Rs 413,648 |
| NIFTY | other days | iron condor | stop+lock+target | 958 | 54% | -Rs 55,147 | **-Rs 209,326** | 49% | -Rs 5,313 | -Rs 15,352 | -Rs 97,577 | 0 | Rs 57,790 | -19.3% | 0.930 | 1.000 | 1, 2, 3, 5, 6, 7 | 27% / -Rs 412,350 |
| BANKNIFTY | expiry | iron fly | stop | 175 | 40% | -Rs 3,475 | **-Rs 1,207** | 40% | -Rs 5,040 | -Rs 10,542 | -Rs 41,999 | 0 | Rs 55,806 | -1.3% | 0.545 | 1.000 | 1, 2, 3, 5, 7 | 50% / -Rs 50,445 |
| BANKNIFTY | expiry | iron fly | stop+lock+target | 175 | 46% | -Rs 17,761 | **-Rs 18,817** | 46% | -Rs 5,040 | -Rs 9,602 | -Rs 44,663 | 0 | Rs 55,806 | -6.8% | 0.742 | 1.000 | 1, 2, 3, 5, 7 | 55% / -Rs 55,877 |
| BANKNIFTY | expiry | wide iron fly | stop | 175 | 46% | Rs 15,097 | **Rs 13,657** | 46% | -Rs 7,839 | -Rs 13,782 | -Rs 43,635 | 0 | Rs 55,806 | +5.7% | 0.363 | 1.000 | 1, 2, 3, 5, 6 | 45% / -Rs 52,255 |
| BANKNIFTY | expiry | wide iron fly | stop+lock+target | 175 | 51% | -Rs 12,454 | **-Rs 16,360** | 50% | -Rs 7,839 | -Rs 13,782 | -Rs 54,376 | 0 | Rs 55,806 | -4.7% | 0.627 | 1.000 | 1, 2, 3, 5, 6, 7 | 53% / -Rs 55,930 |
| BANKNIFTY | expiry | iron condor | stop | 175 | 41% | -Rs 1,528 | **-Rs 4,513** | 41% | -Rs 4,520 | -Rs 7,044 | -Rs 23,377 | 0 | Rs 55,806 | -0.6% | 0.527 | 1.000 | 1, 2, 3, 5, 7 | 34% / -Rs 56,741 |
| BANKNIFTY | expiry | iron condor | stop+lock+target | 175 | 50% | -Rs 18,670 | **-Rs 20,687** | 51% | -Rs 3,167 | -Rs 6,168 | -Rs 32,534 | 0 | Rs 55,806 | -7.1% | 0.834 | 1.000 | 1, 2, 3, 5, 7 | 53% / -Rs 42,158 |
| BANKNIFTY | other days | iron fly | stop | 998 | 62% | Rs 439,888 | **-Rs 317,955** | 42% | -Rs 3,374 | -Rs 9,240 | -Rs 64,610 | 0 | Rs 60,157 | +151.3% | 0.000 | 0.000 | none | 16% / -Rs 1,035,334 |
| BANKNIFTY | other days | iron fly | stop+lock+target | 998 | 62% | Rs 434,383 | **-Rs 325,651** | 42% | -Rs 3,374 | -Rs 9,240 | -Rs 68,598 | 0 | Rs 60,157 | +149.4% | 0.000 | 0.000 | none | 16% / -Rs 1,037,059 |
| BANKNIFTY | other days | wide iron fly | stop | 996 | 70% | Rs 542,746 | **-Rs 320,777** | 47% | -Rs 6,156 | -Rs 8,982 | -Rs 43,614 | 0 | Rs 60,198 | +186.6% | 0.000 | 0.000 | none | 17% / -Rs 1,131,789 |
| BANKNIFTY | other days | wide iron fly | stop+lock+target | 996 | 70% | Rs 532,885 | **-Rs 330,842** | 47% | -Rs 6,156 | -Rs 8,982 | -Rs 48,506 | 0 | Rs 60,198 | +183.2% | 0.000 | 0.000 | none | 17% / -Rs 1,133,203 |
| BANKNIFTY | other days | iron condor | stop | 942 | 66% | Rs 192,142 | **-Rs 292,571** | 44% | -Rs 4,338 | -Rs 5,927 | -Rs 37,140 | 0 | Rs 58,953 | +67.5% | 0.000 | 0.000 | none | 16% / -Rs 724,648 |
| BANKNIFTY | other days | iron condor | stop+lock+target | 942 | 66% | Rs 188,928 | **-Rs 286,978** | 44% | -Rs 4,338 | -Rs 5,927 | -Rs 39,417 | 0 | Rs 58,953 | +66.3% | 0.000 | 0.000 | none | 18% / -Rs 724,760 |


How to read it:

- **Win rate and profit are different things.** The BANKNIFTY non-expiry fly won 62% of days and lost -Rs 3.2 lakh
  repriced; NIFTY's expiry-day iron condor with the target won 52% and lost. Sellers win often by construction.
- **The stop could rarely fire on a normal day.** An iron fly's max loss (wing width minus credit) was a mean Rs 5,308 on
  BANKNIFTY against a credit of Rs 13,968, so "a loss of 50% of the credit" (Rs 6,984) was beyond the worst case on most
  days: 992 of 998 non-expiry trades ran to the square-off. On expiry day the credit is small and the stop fired on 29-57%
  of trades. A stop on a hedged structure must be set inside its max loss to mean anything.
- **Timing twins** (the same structure entered at a random minute 09:21-13:00, 200 draws): entering at 09:21 beat every
  twin on the non-expiry tests (twin p 0.00) on both indices, and the twin was itself worse for NIFTY. Later entries
  collect less of the day's decay. On expiry day it was mixed (twin p 0.00-0.83): only NIFTY's iron condor beat its twins.
- **BUY mirror:** buying the same four legs loses on every test (win 16-59%), so neither side of these structures is an
  edge after costs.
- **Return on margin:** even the as-run (artefact) BANKNIFTY figures, +66% to +187% a year on a Rs 59-60k margin
  estimate, are an artefact; repriced they are about -100% a year (-Rs 3.2 lakh over 4.9 years on Rs 60k).

## Holdout (15 Jul - 19 Oct 2026, read once, the six design passers only)

| test | trades | win | net (pre-registered) | net, stale legs repriced | win, repriced | worst day | worst month | pre-registered holdout gate | BUY mirror: win / net |
|---|---|---|---|---|---|---|---|---|---|
| BANKNIFTY-N-IC-X1 | 64 | 83% | Rs 37,363 | **-Rs 20,954** | 33% | -Rs 2,704 | Rs 5,301 | not met | 2% / -Rs 82,787 |
| BANKNIFTY-N-IC-X2 | 64 | 81% | Rs 34,254 | **-Rs 19,652** | 33% | -Rs 2,704 | Rs 5,665 | not met | 2% / -Rs 82,787 |
| BANKNIFTY-N-IF-X1 | 64 | 86% | Rs 48,969 | **-Rs 18,785** | 39% | -Rs 1,209 | Rs 7,457 | not met | 2% / -Rs 97,537 |
| BANKNIFTY-N-IF-X2 | 64 | 86% | Rs 48,969 | **-Rs 18,785** | 39% | -Rs 1,209 | Rs 7,457 | not met | 2% / -Rs 97,537 |
| BANKNIFTY-N-IFW-X1 | 64 | 86% | Rs 53,623 | **-Rs 18,607** | 38% | -Rs 1,941 | Rs 7,436 | not met | 2% / -Rs 102,038 |
| BANKNIFTY-N-IFW-X2 | 64 | 86% | Rs 53,623 | **-Rs 18,607** | 38% | -Rs 1,941 | Rs 7,436 | not met | 2% / -Rs 102,038 |


`results/HOLDOUT_READ` records the one read (22 Oct 2026). The quarter was quiet and drifted down (NIFTY -4.9%, India VIX
10.4-15.3; R102), the best kind of quarter for a seller, and the repriced structures still lost.

## The stale-leg artefact, in numbers

| check | BANKNIFTY non-expiry passers | NIFTY non-expiry |
|---|---|---|
| exit legs with no print in the 5 minutes before the exit | 11-18% | 3% |
| ... none in the 30 minutes before | 9-15% | 2% |
| trades with at least one such leg (design) | 429-627 of 942-998 | 104-260 of 958 |
| the repricing model on legs that did print, last 10 / 30 / 60 minutes hidden: model minus the real open | +1.0 / +1.8 / +3.4 points (long legs, median 74) | +0.3 / +0.5 / +0.9 (median 14) |
| ... the frozen last close minus the real open, same legs | +0.1 / +0.9 / -0.5 | +0.1 / +0.1 / -0.2 |
| mean change on a repriced leg | -Rs 711 to -Rs 1,698 (24-57 points x 30) | -Rs 877 |

For a leg that keeps printing, the frozen price is close to the truth; the legs that stop printing are the ones the
index moved away from, so their real value fell while the lake kept the old price. The trades with no stale leg at
all (362-569 design trades) are positive (+Rs 12k to +Rs 1.26 lakh, 56-72% wins), but that is selection: they are the
days the index stayed near its 09:20 level, which nobody knows at 09:20.

This also means **every earlier study that held far-OTM strikes from the rolling lake may carry the same bias**:
long far wings or cheap options held while the index moved away. R51's store keeps every print a contract had while it
was within ATM +-10, so a cheap option that drifted out is marked at its last close. Buying studies were hurt by it
(a long option frozen high looks better than it was); selling studies were flattered when the stale leg was long.
Worth a check before any future study holds a leg more than about 6 strikes from ATM.

## Tail risk (descriptive; whole lake, after the holdout read)

The naked 09:20 ATM short straddle and the IF, both held to the square-off with no stop, one lot, stale exit legs
repriced and floored at intrinsic value:


Exit legs that did not trade near the square-off (they had left the lake's ATM +-10 window) are repriced with Black-Scholes at their last print's IV, floored at intrinsic (PREREG Amendment 2).

| index | day | expiry | move 09:20 -> square-off | move / straddle | 09:20 straddle (pts) | naked short straddle net | iron fly (no stop) net | IF wing (pts) | legs repriced |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY | 2024-06-04 |  | -3.78% | 1.17 | 736.8 | -10,846 | -3,828 | 500 | 5 |
| NIFTY | 2024-06-05 |  | +3.41% | 1.58 | 473.4 | -7,575 | -4,206 | 450 | 5 |
| NIFTY | 2022-06-16 | E | -3.00% | 4.24 | 112.05 | -18,617 | -1,641 | 100 | 1 |
| NIFTY | 2022-02-24 | E | -2.63% | 2.08 | 210.65 | -11,592 | -3,116 | 200 | 1 |
| NIFTY | 2026-04-02 |  | +2.33% | 0.85 | 606.25 | -6,958 | -3,348 | 500 | 1 |
| NIFTY | 2022-01-25 |  | +2.27% | 1.09 | 353.15 | -3,280 | -2,976 | 350 | 1 |
| NIFTY | 2022-01-24 |  | -2.26% | 1.4 | 283.0 | -9,109 | -2,535 | 300 | 1 |
| NIFTY | 2024-01-23 |  | -2.26% | 2.33 | 210.5 | -18,125 | -3,124 | 200 | 1 |
| NIFTY | 2022-05-04 |  | -2.25% | 1.83 | 209.4 | -9,425 | -2,680 | 200 | 1 |
| NIFTY | 2024-11-22 |  | +2.18% | 1.38 | 369.25 | -6,598 | -1,828 | 350 | 1 |
| NIFTY | all 1,272 days | | | | | net 63,792, win 62%, worst -28,876 | net -304,449, win 42%, worst -6,280 | | |
| BANKNIFTY | 2024-06-04 |  | -6.46% | 2.19 | 1477.85 | -29,257 | -2,889 | 1000 | 6 |
| BANKNIFTY | 2024-06-05 | E | +4.52% | 2.53 | 839.9 | -19,395 | -3,705 | 800 | 6 |
| BANKNIFTY | 2022-06-16 | E | -3.04% | 4.02 | 254.75 | -19,500 | -3,010 | 300 | 1 |
| BANKNIFTY | 2022-02-24 | E | -3.02% | 1.97 | 559.45 | -13,311 | -7,553 | 600 | 1 |
| BANKNIFTY | 2022-01-25 |  | +2.96% | 1.2 | 903.2 | -7,117 | -4,514 | 900 | 5 |
| BANKNIFTY | 2024-01-23 |  | -2.91% | 2.28 | 593.6 | -12,089 | -1,100 | 600 | 5 |
| BANKNIFTY | 2022-02-15 |  | +2.89% | 1.23 | 868.95 | -9,209 | -4,007 | 900 | 5 |
| BANKNIFTY | 2022-09-30 |  | +2.78% | 0.91 | 1144.7 | -6,732 | -3,086 | 1000 | 1 |
| BANKNIFTY | 2022-05-04 |  | -2.75% | 1.96 | 509.15 | -15,871 | -4,026 | 500 | 1 |
| BANKNIFTY | 2021-10-28 | E | -2.61% | 3.13 | 338.55 | -18,926 | -3,129 | 300 | 5 |
| BANKNIFTY | all 1,234 days | | | | | net 150,543, win 65%, worst -29,257 | net -377,930, win 41%, worst -7,553 | | |


The largest daily moves since 2019 (index_daily). Before Aug 2021 there is no option data; the loss is an estimate from
the open-to-close move minus the median 09:20 straddle (0.96% / 1.49%), which overstates it in a crash, when the
straddle was far dearer. A seller holding overnight would also have taken the gap (-9.1% on NIFTY, -9.9% on BANKNIFTY
on 23 Mar 2020).


| index | day | close to close | gap | open to close | lot | option lake? | naked straddle loss estimate (pre-lake only) |
|---|---|---|---|---|---|---|---|
| NIFTY | 2020-03-23 | -12.98% | -9.14% | -4.22% | 75 | no | about Rs 18,617 |
| NIFTY | 2020-04-07 | +8.76% | +4.48% | +4.10% | 75 | no | about Rs 20,675 |
| NIFTY | 2020-03-12 | -8.30% | -4.00% | -4.48% | 75 | no | about Rs 25,319 |
| NIFTY | 2020-03-16 | -7.61% | -3.69% | -4.07% | 75 | no | about Rs 21,466 |
| NIFTY | 2020-03-25 | +6.62% | -0.84% | +7.53% | 75 | no | about Rs 41,006 |
| NIFTY | 2024-06-04 | -5.93% | -0.36% | -5.59% | 25 | yes |  |
| NIFTY | 2020-03-20 | +5.83% | +0.25% | +5.56% | 75 | no | about Rs 30,202 |
| NIFTY | 2020-05-04 | -5.74% | -3.31% | -2.52% | 75 | no | about Rs 10,856 |
| NIFTY | 2020-03-18 | -5.56% | +1.35% | -6.82% | 75 | no | about Rs 37,208 |
| NIFTY | 2019-09-20 | +5.32% | +0.39% | +4.91% | 75 | no | about Rs 33,379 |
| BANKNIFTY | 2020-03-23 | -16.73% | -9.87% | -7.61% | 25 | no | about Rs 25,888 |
| BANKNIFTY | 2020-04-07 | +10.51% | +6.99% | +3.29% | 25 | no | about Rs 8,599 |
| BANKNIFTY | 2020-03-12 | -9.50% | -4.95% | -4.79% | 25 | no | about Rs 19,748 |
| BANKNIFTY | 2020-05-04 | -8.32% | -4.74% | -3.76% | 25 | no | about Rs 11,196 |
| BANKNIFTY | 2019-09-20 | +8.31% | +0.45% | +7.82% | 25 | no | about Rs 45,890 |
| BANKNIFTY | 2021-02-01 | +8.26% | +1.34% | +6.82% | 25 | no | about Rs 44,094 |
| BANKNIFTY | 2020-03-16 | -8.21% | -4.39% | -3.99% | 25 | no | about Rs 14,448 |
| BANKNIFTY | 2020-03-25 | +8.03% | -2.03% | +10.27% | 25 | no | about Rs 40,562 |
| BANKNIFTY | 2024-06-04 | -7.95% | -0.61% | -7.38% | 15 | yes |  |
| BANKNIFTY | 2020-05-27 | +7.28% | +0.93% | +6.29% | 25 | no | about Rs 22,450 |

## What a selling arm would need in the app before any paper trade

Nothing passed, so nothing is proposed. If a later pre-registered test passes, `DEEP_OPTIONS_SELLING.md` section 8
lists the work: a basket margin check with the hedge benefit and the expiry-day ELM, long wings first on entry, a
credit-based basket stop set inside the structure's max loss, broker-resting stops on the short legs, an event and VIX
blackout, expiry-day rules (no new short after 14:30), four-leg restart recovery, and a credit-position P&L view.

## Method, limits, honesty

- 24 tests, one family (`option-structure`), BH over all 24; nothing was added after seeing results except the
  stricter gate of Amendment 2, which can only reject.
- Fills: next-minute opens, modelled slippage (the lake has no bid/ask). Charges: the dated model (STT 0.15% of every
  sell from 1 Apr 2026). Margin: a stated estimate.
- The repricing holds the engine's exit minute; with fresh marks some stops would have fired at other minutes. It also
  holds the leg's IV at its last print; the intraday IV fall (R102 3.2) would make the wings cheaper still.
- The tail table reads the whole lake (after the holdout read) and is descriptive.
- The NONDIRECTIONAL.md hedged results (every leg at its worst minute price) were too pessimistic; R103's pre-registered
  pricing was too optimistic for the far wings; the repriced numbers sit between, and they are negative.
