# R51: every other cancelled, paused or failed index-option strategy, on the Nov 2024+ market only (15 Oct 2026)

Boss, 15 Oct: "add the Nov 2024 onwards view. Don't test any data before that, and now find a hero-zero, ORB, and all
the strategies which we have cancelled". The ORB family is R49 and the zero-to-hero rules are R50; R51 is everything
else. Pre-registered in `research/hunt/r51/PREREG.md` (commit 11fbca1d, before any profit or loss was computed). Code:
`research/hunt/r51/`. Research only: **nothing here changes the app.**

## The answer in one page

- **Tested:** 127 variants: 68 original rules, 57 small pre-registered re-tunes, and 2 reference baselines (the Night
  arm's 09:16 sale and the live Liquidity BANKNIFTY books), which are not gated. Data: 21 Nov 2024 - 13 Oct 2026 only; every trade on ONE held option contract with real
  dated charges, the R17 liquidity fill model, next-minute fills and gap fills at the worse price. Design 21 Nov 2024 - 31 Mar 2026
  (walk-forward); final test 1 Apr - 13 Oct 2026, read once.
- **1 of 125 gated variants passed every gate: Boss's Donchian 20 sweep rule with re-tuned exits (R44-donchian20-RT).**
  Final test +Rs 48,689 on 1,775 trades (+Rs 27 a trade, +Rs 366 a session), twin p 0.002, BH q 0.025, 6 of 7 months
  green. **It is thin.** At twice the spread and slippage it is about zero in the final test (-Rs 670) and -Rs 85,770 in
  design. It lost on NIFTY and MIDCPNIFTY in both periods, and its put trades lost in both periods. Its whole-design net
  was only +Rs 33,681 (worst stretch Nov 2024 - Mar 2025), and without its 5 best design trades it was negative.
  - It can go on paper as a **forward test only**, with the exact settings below, if Boss wants it. The research gates
    allow paper.
  - It is not an edge to trust with money.
- **Near misses (fail one gate, stay off):**
  - M4 SILVERM morning call: final +Rs 1,00,402 on 93 trades, p 0.026, but q 0.14. Its design twin p was 0.66.
  - EMA 9/21 + ADX20 and EMA 9/26 + ADX20 (R44): final +Rs 75,769 and +Rs 78,014, but both lost in design (-Rs 98,038
    and -Rs 1,38,489), q 0.11 / 0.18.
  - F1-C1: final +Rs 11,618, q 0.103, lost in design.
  - R29-R3 / R6 / R8 fades: final positive with q 0.025-0.10, but each lost in design, and without its top 5 trades each
    is negative.
- **The Night arm's 09:16 sale stays.** All 8 of R27's holds and the re-tune made less than selling at 09:16 on the
  same 86 final-test nights (Rs -4,992 to -18,884; sign-flip p 0.74-0.99), as R27 found on the old data.
- **The parked Liquidity FINNIFTY books stay parked.** Design +Rs 99,350 (252 trades, twin p 0.094); final only
  +Rs 4,861 (94 trades, p 0.49), all of it from its top 5 trades.
- **Everything else loses in the final test, or does no better than random entries:**
  - R45's premium scalp (option-chart signal);
  - 19 of F1's 20 rules (S7 is a near miss: final +Rs 15,028, q 0.125, lost in design);
  - R29's EMA 9 x 21 cross and Boss's three ribbon ideas;
  - all of R41's neural-net rules (R13 overnight -Rs 76,961 on 72 trades; "always the call" overnight -Rs 5.36 lakh
    on 520);
  - all four B2 rules;
  - both GEX filters.
- **Honesty:** earlier studies already looked at parts of 2025-26 (F1 design, R29 2025/26, R41 folds, B2, R44, R45, R27,
  R42 holdouts), so the final test is **not pristine**. Only paper from 15 Oct 2026 on is truly unseen.

## Amendment A: foreign-rows and stray-print re-pricing (registered 15 Oct 2026; NOT YET RUN)

**Status: no cleaned number exists yet. Until it does, read the passing result above as possibly inflated by bad data.**

R47 found rows of another underlying in the lake's rolling option files (their own spot more than 3% from the index)
and stray prints; R51 applied neither filter. Amendment A (PREREG.md, committed before any cleaned result) re-prices
R44-donchian20-RT and the 26 index-option near-misses with final q <= 0.20 on cleaned data: same rules, same spans,
same gates, the same twins and BH over the same 125 variants; the final test is re-priced, not re-selected. It also
reports the drop counts per index, how many of the passing rule's original trades touched a removed row, and the
passing rule at twice the spread and slippage.

Code: `research/hunt/lakeclean.py` (shared with R52), `hunt/r51/amend.py`, cleaned stores via `R51_CLEAN=1
r51data.py build`; tests in `hunt/r51/test_amend.py`. The run needs the lake copied from `gdrive:IraAlgo-data-lake`,
which this session was not allowed to do; the steps are in the `amend.py` docstring.

## What would come back on PAPER under the Nov-2024+ view, and what stays off

| strategy | decision | exact settings |
|---|---|---|
| **R44 Donchian 20, re-tuned** | **May go on paper as a forward test (Boss decides; it passed the gates, but it is thin and cost-sensitive).** Logged, labelled "R51 Donchian 20 re-tune", 1 lot, paper only; never carries over to live by itself. | Signal on the INDEX's 5-minute bars (NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX): a close above the highest high of the previous 20 bars buys the call, a close below the lowest low of the previous 20 bars buys the put. Buy the **1-ITM** option of the nearest expiry at the next bar's open, entries 09:20-15:10. Exits: stop -15%, target +30%, out after 30 minutes, square-off 15:15. One position a side, at most 2 entries a day a side; no trades on expiry days. Suggested review: after 40 paper trades, stop it if net < 0 or below the cost of twice the spread. Keep all 5 indices as tested (the FINNIFTY-only profit was found after the read; trading FINNIFTY alone would be a new, untested rule) and report FINNIFTY separately. |
| M4 SILVERM morning call | stays off (q 0.14; design p 0.66) | - |
| Night R3 holds V1-V8 + re-tune | stay off; keep the 09:16 sale | - |
| Liquidity FINNIFTY 30+5 | stays parked | - |
| G7 GEX filter on Liquidity BANKNIFTY, G10 GEX filter on Night | stay off (no filter added) | - |
| R44 EMA8/21+ADX15 (with and without the lock), EMA9/21, EMA9/26, RSI fade, RSI momentum, Donchian 20 (original exits) | stay off | - |
| R45 premium scalp F-a / F-b (option-chart signal, non-default) | stays off | - |
| F1 C1, C2, S1-S18 | stay off | - |
| R29 B0, Boss's ribbon ideas (P9_5, P11_5, P5_15, P8_1), R1-R10 | stay off | - |
| R41 R1-R6, R13, "always the call" | stay off | - |
| B2 G15 / G30 / G60 / OPT | stay off | - |

Under CLAUDE.md's PAPER rule an idea that has passed the gates may act on paper arms straight away. Every such change is
logged in the Autopilot change timeline, undoable in one tap, and shown to Boss. R51 makes **no** app change; adding the
Donchian forward test is a separate, Boss-approved step.

## Review after the final read (separate reviewer, 15 Oct)

A separate reviewer read the whole diff, and three things came up. The final test was not re-read (it is read once),
so each was measured on the cached trades of the passing rule:
- **Expiry flag on the last session.** The expiry rule needs the next session, so 13 Oct 2026, the lake's last
  session and a NIFTY weekly expiry, was not flagged. The rule took 2 NIFTY trades that day (+Rs 1,126 net). Without
  them the final net is +Rs 47,563 and the net without its top 5 trades is +Rs 4,719, so it still passes.
- **Spread at the time stop.** The time-stop exit sized its half-spread from the volume of the 5 minutes up to and
  including its own minute, not the minutes before it. With the earlier minutes the final net changes by +Rs 81 and
  the design net by -Rs 60.
- **One index carries it.** The pass depends on FINNIFTY (above). 30 of its 356 FINNIFTY final trades had no rolling
  ATM strike at the signal minute (the strike was taken from the index close), a sign of thin contracts.
- The reviewer found no look-ahead, no final-test leakage and no engine or statistics error that could create the
  profit; every number in this write-up matched the result files.
- LIQ-FIN-RT picked the original setting, so its p enters the BH family twice. That is conservative.

The code is left exactly as it produced these results. Both issues are noted where they sit (`r51data.expiry_days`,
`engine.simulate`).

## Method (as pre-registered)

- **Data.** Drive lake `gdrive:IraAlgo-data-lake`: index_1m and options_rolling_1m WEEK files (BANKNIFTY / FINNIFTY /
  MIDCPNIFTY WEEK = MONTH in this window), equity_1m for Night's breadth, SILVERM monthly options from 5 Aug 2025. Brain
  cube and features rebuilt from the lake (Jun 2023 on, as warm-up only).
  - Index bars from 1 Sep 2024 (2023 for R29's 60-session ribbon warm-up) feed indicators only.
  - No trade is taken or reported before 21 Nov 2024 (20 Nov 2024 was a market holiday).
- **One held contract.** `r51data.py` rebuilds every contract (strike x side, nearest expiry) from every offset row
  that carried it. A trade is priced on the strike it bought, minute by minute (`engine.py`), never on the rolling ATM series.
- **Fills and costs.** Entry at the open of the minute after the signal bar (up to 2 minutes later, else no fill).
  - Stops trigger on the minute's low; a gap through fills at the open (the worse price); the stop wins a tie.
  - Targets are limits at their price. Locks apply from the next minute.
  - R17 "liq" model: +-5 bps, stops -10 bps, a half-spread of 1 / 2 / 4 ticks by the volume of the previous 5 minutes,
    doubled for stops.
  - `Costs('dated')`: STT 0.1% on option sells to 31 Mar 2026, 0.15% from 1 Apr 2026. Lots in force on each date.
  - Night uses a half-spread of max(2 ticks, 0.16%), R27's measured spread. R45 uses 2 ticks; its 5-minute bars carry no
    volume.
  - M4 uses MCX charges and SILVERM's Rs 0.5 tick.
- **Gates (never relaxed):**
  - final-test net > 0;
  - random-entry twin p < 0.05 (500 twins: same days, entries a day and sides; random entry minutes in the rule's
    window; same strike rule and option exits);
  - BH q <= 0.10 over all 125 gated final-test p values;
  - 40+ final-test trades;
  - net without its top 5 trades > 0;
  - design walk-forward net > 0. For an original, that is its design net. For a re-tune, it is the sum of its four
    out-of-fold quarters, Apr 2025 - Mar 2026: each quarter traded with the grid point that was best before it.
- **Twins by rule type.** Night holds and R41's overnight rules draw random nights (random side). The GEX filters draw
  random subsets of the unfiltered trades of the same size. M4 draws random morning minutes. R45 uses R45's own twin.
  Liquidity twins use only the option exits (the index exits need the rule's own levels).
- **Deviations stated in PREREG.md:**
  - R41 uses scikit-learn's MLP (64-32, 3 seeds) instead of torch's MLP / TCN / GRU, without the raw 60-minute sequence.
  - B2 uses the brain's base features without its 261 extra sweep inputs.
- **Fixes made during design, before the final read:**
  - B2 drops columns that are all-NaN or constant on the training rows (sklearn's binning crashed on them).
  - R41-R13 trained with at least 300 rows (one row a night; the first run's 2,000-row minimum skipped every fold).
  - R13's re-tune grid is confidence {20%, 10%, 5%}; an overnight rule has no stop to tune.

## Notes by family

- **Liquidity FINNIFTY (paused arm).** Positive in all four design quarters (+Rs 51,934 / +587 / +2,902 / +30,991),
  but the twin p was only 0.094 in design. The final test gave +Rs 4,861 on 94 trades (p 0.49), and without its top 5
  trades it is negative. The re-tune picked the original books (30 + 5, room 1). The live BANKNIFTY books, for
  reference, made +Rs 1,18,828 in design but only +Rs 10,978 in the final test (131 trades, p 0.70).
- **G7.** Skipping BANKNIFTY Liquidity trades when gex_norm_20d > +1 kept 90 of 131 final trades for +Rs 4,653, against
  +Rs 10,978 unfiltered. It does not help.
- **Night.** B0, the arm as it runs, made +Rs 98,273 in design (245 trades) and +Rs 47,352 in the final test (86
  trades; twin p 0.042 against random nights). Every hold lost money relative to it. G10 (only nights with S1 GEX < 0)
  kept 40 of 86 nights for +Rs 74,364, more than B0. But it lost to random subsets in design (p 0.75; +Rs 13,665 on
  83 nights) and fails in the final test (p 0.13).
- **M4 SILVERM.** Design (5 Aug 2025 - 31 Mar 2026) +Rs 50,119 on 81 trades with twin p 0.66, which is no better than
  a random morning minute. The final test was +Rs 1,00,402 on 93 trades (p 0.026, q 0.14). One period beats random and
  one does not, and the BH gate fails, so it stays off as R42 decided. Its contracts are thin: 23 of 81 design exits and 8 of 93
  final exits found no trade in the 30 minutes before the 240-minute mark and are priced at the last trade (`missing`).
- **R44 (Boss's 7).**
  - All 7 originals lost in design, 5 of them heavily. RSI fade lost -Rs 7.39 lakh on 2,216 trades.
  - In the final test, EMA9/21 and EMA9/26 made +Rs 75-78k, EMA8/21 +Rs 20k and Donchian +Rs 56k, but none
    beats random entries after BH, and all lost in design.
  - Every R44 re-tune picked exit B (-15% / +30% / 30 min) on 1-ITM, except RSI fade (exit B, ATM) and RSI momentum
    (exit C, 1-ITM). Only Donchian's held up out of sample.
- **R45 (option-chart signal, non-default).** Still loses on the held contract: F-a -Rs 74,813 in design (368 trades)
  and -Rs 8,544 in the final test (142); F-b -Rs 33,042 and -Rs 9,281.
- **F1.**
  - C2 (put writing + VWAP) lost -Rs 2.35 lakh in design and -Rs 1.34 lakh in the final test.
  - The volatility rules S1-S7, S16 and S17 do not survive: only S7 made money in the final test (+Rs 15,028, q 0.125),
    and it lost in design.
  - S13 and S14 are the same rule, as F1 noted.
  - The re-tune kept the original threshold and exit for 18 of 20 rules.
- **R29.**
  - The EMA 9 x 21 cross lost -Rs 3.54 lakh in design (2,846 trades) and -Rs 46,926 in the final test.
  - Boss's MTF alignment (P8_1) lost -Rs 2.47 lakh in the final test.
  - Three fades (R3, R6, R8) made money in the final test and beat random entries. But all three lost in design, and
    without their top 5 trades each is negative. This is the sign-flipping R29 already reported.
- **R41.** Every direction rule lost in both periods (-Rs 25k to -3.5 lakh). Small twin p values here mean "lost
  less than random entries", not an edge. The overnight R13 lost -Rs 1.03 lakh in design and -Rs 76,961 in the final
  test. Always buying the call overnight lost -Rs 7.44 lakh and -Rs 5.36 lakh: overnight decay plus the falling market of
  early 2026.
- **B2.** All four rules lost in both periods. The stricter 95th-percentile re-tunes lose less but still lose.

## Full tables (generated by `research/hunt/r51/report.py`)

Per strategy:
- OLD = the earlier verdict on the full data;
- NOW = Nov 2024+ design;
- FINAL TEST = 1 Apr - 13 Oct 2026, read once;
- RE-TUNE = the pre-registered small grid, with its design result for the chosen setting and its walk-forward.

Net and money columns are in rupees for 1 lot. net/day = net over the sessions of the period. p = random-entry twin p.
q = BH over the 125 gated final-test p values.

### Liquidity 15+5 (paused arm)

#### LIQ-FIN: Liquidity FINNIFTY 30m + 5m books, 1-ITM, the app's exits

Signal source: index (30-min + 5-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | parked 9 Oct after -Rs 8,035 on 8 paper trades; research (LIQUIDITY_FINNIFTY_PLUS) +8.2 / +4.8 index pts a trade in two years, never gated as a pass |
| NOW (design) | 252 | 45% | 99,350 | 295 | 1,948 | -868 | -3,766 | 10,077 | 0.0938 | - | walk-forward (folds) [51934.0, 587.0, 2902.0, 30991.0] |
| FINAL TEST | 94 | 38% | 4,861 | 37 | 1,462 | -824 | -4,362 | 12,371 | 0.489 | 0.767 | p >= 0.05; q > 0.10; top-5 trades >= net |
| RE-TUNE design (liq / FINNIFTY / 30+5 / room1.0) | 252 | 45% | 99,350 | 295 | 1,948 | -868 | -3,766 | 10,077 | - | - | walk-forward out-of-fold 61,882 on 177 trades |
| RE-TUNE FINAL | 94 | 38% | 4,861 | 37 | 1,462 | -824 | -4,362 | 12,371 | 0.489 | 0.767 | p >= 0.05; q > 0.10; top-5 trades >= net |

### Liquidity 15+5 (live arm)

#### LIQ-BN-base: Liquidity BANKNIFTY 15+5 unfiltered (reference)

Signal source: index (15-min + 5-min bars). Kind: ref.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | |  |
| NOW (design) | 336 | 42% | 118,828 | 353 | 2,009 | -858 | -6,164 | 12,691 | 0.0279 | - | walk-forward (folds) [16712.0, 12134.0, 692.0, 43190.0] |
| FINAL TEST | 131 | 37% | 10,978 | 83 | 1,982 | -1,014 | -6,910 | 24,873 | 0.6986 | - | reference (not gated) |

### R28 GEX filter

#### G7-GEX-LIQ-BN: Liquidity BANKNIFTY 15+5, skip when gex_norm_20d > +1 at the signal minute

Signal source: index bars + option-chain GEX filter. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R28: design +Rs 6/day (799 -> 521 trades), holdout -Rs 17/day; rejected |
| NOW (design) | 239 | 46% | 116,438 | 346 | 2,081 | -849 | -5,806 | 10,547 | 0.008 | - | walk-forward (folds) [18267.0, 11507.0, -3220.0, 51251.0] |
| FINAL TEST | 90 | 36% | 4,653 | 35 | 1,837 | -933 | -4,825 | 12,201 | 0.5928 | 0.8719 | p >= 0.05; q > 0.10; top-5 trades >= net |

#### G10-GEX-NIGHT: Night R3 (09:16 sale) only when S1 net GEX < 0 at 15:15

Signal source: index + breadth + OI; GEX filter. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R28: design +206 -> +119 Rs/day, holdout +249 -> -18 Rs/day; rejected |
| NOW (design) | 83 | 52% | 13,665 | 41 | 3,094 | -2,985 | -15,106 | 34,821 | 0.7545 | - | walk-forward (folds) [4579.0, 2074.0, 5291.0, -3623.0] |
| FINAL TEST | 40 | 62% | 74,364 | 563 | 5,112 | -3,563 | -9,036 | 29,611 | 0.1277 | 0.3326 | p >= 0.05; q > 0.10 |

### Night (R3) arm

#### NIGHT-B0: Night R3, sold at the 09:16 open (the arm as it runs; reference)

Signal source: index close + breadth + OI build-up. Kind: ref.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 53% | 98,273 | 293 | 3,144 | -2,649 | -15,106 | 53,128 | 0.002 | - | walk-forward (folds) [10783.0, 864.0, 5154.0, -10093.0] |
| FINAL TEST | 86 | 50% | 47,352 | 359 | 4,670 | -3,569 | -14,081 | 43,635 | 0.0419 | - | reference (not gated) |

### R27 Night holds

#### NIGHT-V1_H1_I20s: Night R3 held past 09:16: V1_H1_I20s (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 51% | 109,845 | 328 | 3,460 | -2,689 | -16,166 | 55,442 | 0.004 | - | walk-forward (folds) [23303.0, -19039.0, 15085.0, -9386.0] |
| FINAL TEST | 86 | 53% | 30,232 | 229 | 4,288 | -4,175 | -14,971 | 45,895 | 0.0838 | 0.2608 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -17,120 Rs over 86 nights (sign-flip p 0.9685).

#### NIGHT-V2_H1_I30s: Night R3 held past 09:16: V2_H1_I30s (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 48% | 75,833 | 226 | 3,597 | -2,745 | -16,254 | 64,324 | 0.006 | - | walk-forward (folds) [19724.0, -26344.0, 7567.0, -17061.0] |
| FINAL TEST | 86 | 53% | 31,214 | 236 | 4,273 | -4,134 | -14,945 | 45,207 | 0.0918 | 0.2608 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -16,139 Rs over 86 nights (sign-flip p 0.9145).

#### NIGHT-V3_H1_I20r: Night R3 held past 09:16: V3_H1_I20r (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 52% | 119,650 | 357 | 3,460 | -2,710 | -16,166 | 55,442 | 0.002 | - | walk-forward (folds) [23282.0, -10287.0, 17009.0, -11602.0] |
| FINAL TEST | 86 | 53% | 29,853 | 226 | 4,259 | -4,151 | -14,971 | 45,895 | 0.0818 | 0.2608 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -17,499 Rs over 86 nights (sign-flip p 0.977).

#### NIGHT-V4_H1_I30r: Night R3 held past 09:16: V4_H1_I30r (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 50% | 101,750 | 304 | 3,569 | -2,713 | -16,166 | 55,442 | 0.004 | - | walk-forward (folds) [18263.0, -9337.0, 15346.0, -15247.0] |
| FINAL TEST | 86 | 55% | 30,919 | 234 | 4,201 | -4,270 | -14,945 | 45,207 | 0.0898 | 0.2608 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -16,433 Rs over 86 nights (sign-flip p 0.9475).

#### NIGHT-V5_H1_P20: Night R3 held past 09:16: V5_H1_P20 (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 52% | 89,507 | 267 | 3,368 | -2,866 | -16,466 | 58,192 | 0.002 | - | walk-forward (folds) [14222.0, -14145.0, 10892.0, -18459.0] |
| FINAL TEST | 86 | 51% | 38,425 | 291 | 4,687 | -3,996 | -15,345 | 48,046 | 0.0659 | 0.2358 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -8,927 Rs over 86 nights (sign-flip p 0.8466).

#### NIGHT-V6_H1_P30: Night R3 held past 09:16: V6_H1_P30 (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 47% | 64,936 | 194 | 3,757 | -2,824 | -16,011 | 66,928 | 0.008 | - | walk-forward (folds) [3196.0, -22830.0, 3900.0, -16885.0] |
| FINAL TEST | 86 | 50% | 29,711 | 225 | 4,682 | -3,991 | -15,644 | 46,474 | 0.0878 | 0.2608 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -17,642 Rs over 86 nights (sign-flip p 0.927).

#### NIGHT-V7_H2_I30s: Night R3 held past 09:16: V7_H2_I30s (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 52% | 91,869 | 274 | 3,136 | -2,645 | -15,106 | 52,589 | 0.002 | - | walk-forward (folds) [18278.0, -7926.0, 6579.0, -15445.0] |
| FINAL TEST | 86 | 50% | 42,361 | 321 | 4,545 | -3,560 | -14,081 | 43,635 | 0.0679 | 0.2358 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -4,992 Rs over 86 nights (sign-flip p 0.7356).

#### NIGHT-V8_H3_LOCK: Night R3 held past 09:16: V8_H3_LOCK (10:30 cap)

Signal source: index close + breadth + OI build-up. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R27: 0 of 8 holds passed; in the locked year (173 trades) all 8 lost to the 09:16 sale by Rs 4.6k-25.9k |
| NOW (design) | 245 | 58% | 87,912 | 262 | 3,113 | -3,502 | -16,565 | 57,998 | 0.006 | - | walk-forward (folds) [19299.0, -15547.0, 5218.0, -18176.0] |
| FINAL TEST | 86 | 53% | 28,468 | 216 | 4,458 | -4,415 | -15,743 | 51,745 | 0.1078 | 0.2994 | p >= 0.05; q > 0.10; top-5 trades >= net |

Against the 09:16 sale on the same final-test nights: -18,884 Rs over 86 nights (sign-flip p 0.984).

### MCX paper arm

#### M4-SILVERM: SILVERM call 2 strikes OTM, 09:15-13:45 grid, 11-20 days to expiry, 240-min hold

Signal source: clock (15-min grid), strike from the future. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R42: holdout 53 trades +Rs 336/trade (+Rs 17.8k), twin p 0.079; walk-forward -Rs 44.6k (129); paper arm switched off 14 Oct |
| NOW (design) | 81 | 48% | 50,119 | 300 | 3,061 | -1,649 | -23,940 | 24,646 | 0.6567 | - | walk-forward (folds) [0.0, -3146.0, 24524.0, 28740.0] |
| FINAL TEST | 93 | 51% | 100,402 | 733 | 3,656 | -1,553 | -5,160 | 10,153 | 0.0259 | 0.1408 | q > 0.10 |
| RE-TUNE design (m4 / otm2 / h120) | 118 | 40% | 77,542 | 464 | 3,173 | -1,008 | -28,069 | 29,036 | - | - | walk-forward out-of-fold 17,470 on 105 trades |
| RE-TUNE FINAL | 138 | 45% | 68,216 | 498 | 2,686 | -1,294 | -6,305 | 12,626 | 0.2754 | 0.5216 | p >= 0.05; q > 0.10 |

### R45 premium scalp (non-default)

#### R45-F-a: Bollinger lower-band cross on the 5-min rolling ATM call; trade the strike ATM at entry

Signal source: OPTION'S OWN PRICE CHART (non-default; Boss only). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R45: -Rs 170 a trade (1,250 trades) on one contract; holdout -Rs 106 (122) |
| NOW (design) | 368 | 33% | -74,813 | -222 | 786 | -694 | -3,918 | 75,038 | 0.98 | - | walk-forward (folds) [-22453.0, -791.0, -15938.0, -22340.0] |
| FINAL TEST | 142 | 43% | -8,544 | -64 | 685 | -621 | -2,866 | 14,003 | 0.2974 | 0.5549 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r45 / F-a / h6 / s0.2) | 353 | 41% | -61,497 | -182 | 1,082 | -1,040 | -5,471 | 62,336 | - | - | walk-forward out-of-fold -51,343 on 254 trades |
| RE-TUNE FINAL | 140 | 48% | -6,576 | -49 | 953 | -965 | -5,496 | 20,399 | 0.3653 | 0.6523 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R45-F-b: the same on the 09:15 ATM strike all session

Signal source: OPTION'S OWN PRICE CHART (non-default; Boss only). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R45: -Rs 149 a trade (1,125); holdout -Rs 127 (130) |
| NOW (design) | 304 | 40% | -33,042 | -98 | 610 | -584 | -2,527 | 41,993 | 0.0399 | - | walk-forward (folds) [-7098.0, -8167.0, -5671.0, -7397.0] |
| FINAL TEST | 145 | 42% | -9,281 | -70 | 582 | -533 | -2,551 | 11,564 | 0.1916 | 0.4129 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

### R29 EMA ribbons

#### R29-B0: B0: EMA 9 x 21 cross on 5-min bars, follow; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 110 a trade (1,785) |
| NOW (design) | 2846 | 55% | -354,483 | -1,052 | 973 | -1,481 | -33,420 | 359,633 | 0.6707 | - | walk-forward (folds) [-69083.0, -80293.0, -52004.0, -50953.0] |
| FINAL TEST | 1029 | 54% | -46,926 | -353 | 1,091 | -1,398 | -15,718 | 97,462 | 0.2495 | 0.4873 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / B0 / None / follow / X1t30 / m0) | 3052 | 51% | -278,895 | -828 | 909 | -1,117 | -23,078 | 280,463 | - | - | walk-forward out-of-fold -208,251 on 2192 trades |
| RE-TUNE FINAL | 1107 | 50% | -59,259 | -446 | 970 | -1,072 | -19,925 | 70,366 | 0.1677 | 0.3721 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-BOSS-P9_5: Boss idea 1: 15-min stack + 5-min pullback; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29: lost in 2024 in every cell but one (Boss's three ideas) |
| NOW (design) | 1389 | 53% | -230,501 | -684 | 887 | -1,374 | -31,599 | 249,058 | 1.0 | - | walk-forward (folds) [-37386.0, -29181.0, -34081.0, -84305.0] |
| FINAL TEST | 412 | 51% | -60,102 | -452 | 867 | -1,209 | -10,002 | 80,153 | 0.984 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P9_5 / None / follow / S20T40 / m1) | 1280 | 41% | -202,387 | -601 | 1,663 | -1,437 | -16,182 | 274,575 | - | - | walk-forward out-of-fold -210,175 on 1022 trades |
| RE-TUNE FINAL | 397 | 38% | -82,695 | -622 | 1,492 | -1,252 | -16,791 | 93,421 | 0.996 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-BOSS-P11_5: Boss idea 2: squeeze then fan-out (5-min, 15-min order >= 5/7); ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29: lost in 2024 in every cell but one (Boss's three ideas) |
| NOW (design) | 576 | 59% | -76,821 | -228 | 876 | -1,609 | -17,025 | 113,870 | 0.9022 | - | walk-forward (folds) [-8016.0, -15805.0, -45383.0, 31107.0] |
| FINAL TEST | 194 | 53% | -23,971 | -180 | 1,061 | -1,437 | -21,990 | 35,431 | 0.1337 | 0.3343 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P11_5 / None / follow / S20T40 / m1) | 569 | 44% | -69,885 | -207 | 1,878 | -1,679 | -26,819 | 98,977 | - | - | walk-forward out-of-fold -43,105 on 415 trades |
| RE-TUNE FINAL | 194 | 45% | -15,444 | -116 | 1,617 | -1,459 | -20,609 | 47,710 | 0.0339 | 0.163 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-BOSS-P5_15: Boss idea 2: squeeze-break onset on 15-min bars; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29: lost in 2024 in every cell but one (Boss's three ideas) |
| NOW (design) | 393 | 56% | -82,765 | -246 | 896 | -1,618 | -20,251 | 98,080 | 0.998 | - | walk-forward (folds) [-14645.0, -7773.0, -10163.0, -15499.0] |
| FINAL TEST | 168 | 48% | -44,645 | -336 | 810 | -1,244 | -6,358 | 45,479 | 0.994 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P5_15 / None / follow / X1t30 / m1) | 399 | 53% | -50,896 | -151 | 987 | -1,366 | -16,915 | 70,358 | - | - | walk-forward out-of-fold -36,565 on 293 trades |
| RE-TUNE FINAL | 176 | 47% | -31,102 | -234 | 858 | -1,101 | -7,972 | 41,296 | 0.8962 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-BOSS-P8_1: Boss idea 3: 1/5/15-min alignment onset; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29: lost in 2024 in every cell but one (Boss's three ideas) |
| NOW (design) | 2652 | 56% | -273,394 | -811 | 935 | -1,424 | -22,400 | 357,554 | 1.0 | - | walk-forward (folds) [-71686.0, -49350.0, -55394.0, 36403.0] |
| FINAL TEST | 969 | 49% | -246,650 | -1,854 | 904 | -1,364 | -27,107 | 253,420 | 1.0 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P8_1 / None / follow / X1t30 / m0) | 2750 | 51% | -248,822 | -738 | 858 | -1,081 | -21,273 | 347,231 | - | - | walk-forward out-of-fold -119,296 on 1950 trades |
| RE-TUNE FINAL | 1019 | 45% | -182,272 | -1,370 | 808 | -975 | -13,218 | 190,949 | 0.998 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R1: R1: P3_3 bear event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 346 | 51% | -116,581 | -346 | 972 | -1,676 | -19,817 | 131,964 | 0.0359 | - | walk-forward (folds) [-32017.0, -39768.0, -13785.0, -27089.0] |
| FINAL TEST | 142 | 48% | -33,763 | -254 | 903 | -1,286 | -8,707 | 46,675 | 0.0679 | 0.2358 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P3_3 / -1 / fade / S20T40 / m0) | 346 | 41% | -50,132 | -149 | 1,792 | -1,509 | -20,260 | 75,040 | - | - | walk-forward out-of-fold -72,034 on 252 trades |
| RE-TUNE FINAL | 142 | 38% | -38,785 | -292 | 1,208 | -1,182 | -7,974 | 49,366 | 0.0778 | 0.2559 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R2: R2: P3_5 bull event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 224 | 58% | 8,089 | 24 | 859 | -1,081 | -6,971 | 21,328 | 0.002 | - | walk-forward (folds) [5462.0, 4677.0, -6916.0, -11663.0] |
| FINAL TEST | 103 | 51% | -7,069 | -53 | 945 | -1,143 | -12,111 | 20,018 | 0.1577 | 0.363 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net |
| RE-TUNE design (r29 / P3_5 / 1 / fade / X1 / m0) | 224 | 58% | 8,089 | 24 | 859 | -1,081 | -6,971 | 21,328 | - | - | walk-forward out-of-fold -20,084 on 159 trades |
| RE-TUNE FINAL | 103 | 51% | -7,069 | -53 | 945 | -1,143 | -12,111 | 20,018 | 0.1577 | 0.363 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R3: R3: P5_15 bull event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 200 | 58% | -5,826 | -17 | 900 | -1,312 | -11,549 | 25,866 | 0.008 | - | walk-forward (folds) [-19481.0, 6382.0, 3549.0, -6000.0] |
| FINAL TEST | 84 | 52% | 8,575 | 64 | 903 | -779 | -4,215 | 9,922 | 0.002 | 0.025 | top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P5_15 / 1 / fade / S20T40 / m0) | 198 | 43% | 7,435 | 22 | 1,718 | -1,226 | -11,391 | 31,939 | - | - | walk-forward out-of-fold -8,271 on 149 trades |
| RE-TUNE FINAL | 82 | 41% | 2,328 | 18 | 1,234 | -825 | -5,114 | 11,922 | 0.002 | 0.025 | top-5 trades >= net; design walk-forward <= 0 |

#### R29-R4: R4: P2_15 bear event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 363 | 56% | -9,583 | -28 | 963 | -1,267 | -9,281 | 50,583 | 0.002 | - | walk-forward (folds) [-6444.0, -17306.0, -12029.0, -4941.0] |
| FINAL TEST | 165 | 53% | -21,125 | -159 | 956 | -1,367 | -14,467 | 35,004 | 0.0858 | 0.2608 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P2_15 / -1 / fade / S20T40 / m0) | 362 | 41% | 10,247 | 30 | 1,955 | -1,289 | -15,893 | 58,788 | - | - | walk-forward out-of-fold -42,461 on 244 trades |
| RE-TUNE FINAL | 165 | 38% | -34,498 | -259 | 1,583 | -1,288 | -16,440 | 50,328 | 0.3253 | 0.598 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R5: R5: P5_3 bull event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 870 | 52% | -154,555 | -459 | 894 | -1,358 | -22,504 | 170,021 | 0.0399 | - | walk-forward (folds) [-70970.0, 11990.0, -17353.0, -2230.0] |
| FINAL TEST | 343 | 54% | -56,477 | -425 | 943 | -1,447 | -19,063 | 68,304 | 0.0699 | 0.2361 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P5_3 / 1 / fade / X1t30 / m0) | 894 | 50% | -131,726 | -391 | 786 | -1,064 | -18,228 | 146,013 | - | - | walk-forward out-of-fold -85,105 on 632 trades |
| RE-TUNE FINAL | 347 | 49% | -34,526 | -260 | 868 | -1,039 | -15,414 | 47,886 | 0.018 | 0.1125 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R6: R6: P9_5 bear event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: +Rs 21 a trade (264), sign flips by period |
| NOW (design) | 638 | 57% | -104,620 | -310 | 907 | -1,569 | -18,661 | 104,620 | 0.008 | - | walk-forward (folds) [9607.0, -11592.0, -1723.0, -65721.0] |
| FINAL TEST | 194 | 52% | 2,437 | 18 | 1,063 | -1,105 | -10,642 | 15,522 | 0.01 | 0.0962 | top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P9_5 / -1 / fade / S20T40 / m0) | 592 | 40% | -76,240 | -226 | 1,874 | -1,466 | -20,577 | 80,134 | - | - | walk-forward out-of-fold -82,453 on 462 trades |
| RE-TUNE FINAL | 183 | 40% | 21,916 | 165 | 1,872 | -1,043 | -10,442 | 20,771 | 0.002 | 0.025 | top-5 trades >= net; design walk-forward <= 0 |

#### R29-R7: R7: P3_3 bull event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 326 | 55% | -58,391 | -173 | 834 | -1,397 | -10,650 | 68,220 | 0.018 | - | walk-forward (folds) [-16622.0, -5358.0, -17197.0, -7859.0] |
| FINAL TEST | 141 | 55% | -7,453 | -56 | 803 | -1,112 | -6,684 | 17,295 | 0.0479 | 0.2065 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P3_3 / 1 / fade / S20T40 / m1) | 325 | 41% | -56,630 | -168 | 1,677 | -1,441 | -10,053 | 66,825 | - | - | walk-forward out-of-fold -55,942 on 236 trades |
| RE-TUNE FINAL | 139 | 45% | -18,731 | -141 | 1,324 | -1,309 | -7,473 | 26,943 | 0.1158 | 0.308 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R8: R8: P10_1 bear event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 987 | 58% | -188,223 | -558 | 876 | -1,693 | -34,706 | 207,293 | 0.002 | - | walk-forward (folds) [31632.0, -59019.0, 8400.0, -138525.0] |
| FINAL TEST | 376 | 53% | 12,421 | 93 | 1,106 | -1,160 | -20,686 | 40,064 | 0.002 | 0.025 | top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P10_1 / -1 / fade / S20T40 / m0) | 949 | 41% | -128,443 | -381 | 1,775 | -1,480 | -29,141 | 209,216 | - | - | walk-forward out-of-fold -182,970 on 656 trades |
| RE-TUNE FINAL | 367 | 41% | 26,292 | 198 | 1,798 | -1,121 | -18,002 | 51,521 | 0.002 | 0.025 | top-5 trades >= net; design walk-forward <= 0 |

#### R29-R9: R9: P2_5 bear event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 43 to -223 a trade; 0 of 55 rule x index tests pass |
| NOW (design) | 1013 | 52% | -334,173 | -992 | 854 | -1,629 | -34,919 | 338,649 | 0.3992 | - | walk-forward (folds) [-30177.0, -81390.0, -76687.0, -107327.0] |
| FINAL TEST | 340 | 52% | -58,297 | -438 | 921 | -1,371 | -12,257 | 73,442 | 0.2395 | 0.4752 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P2_5 / -1 / fade / S20T40 / m0) | 991 | 39% | -173,585 | -515 | 1,873 | -1,482 | -16,442 | 204,743 | - | - | walk-forward out-of-fold -174,552 on 714 trades |
| RE-TUNE FINAL | 330 | 42% | -49,830 | -375 | 1,596 | -1,391 | -14,738 | 67,530 | 0.0679 | 0.2358 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R29-R10: R10: P1_5 bull event, fade; ATM, X1

Signal source: index (EMA ribbons on 1/3/5/15-min bars). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R29 holdout 2023: -Rs 111 a trade (1,033) |
| NOW (design) | 1483 | 52% | -159,466 | -473 | 831 | -1,105 | -20,968 | 170,426 | 0.002 | - | walk-forward (folds) [-38785.0, -13760.0, -54340.0, -24857.0] |
| FINAL TEST | 587 | 54% | -51,627 | -388 | 848 | -1,203 | -17,717 | 81,771 | 0.002 | 0.025 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r29 / P1_5 / 1 / fade / X1t30 / m0) | 1556 | 48% | -125,673 | -373 | 701 | -805 | -14,920 | 138,427 | - | - | walk-forward out-of-fold -159,687 on 1116 trades |
| RE-TUNE FINAL | 617 | 51% | -59,464 | -447 | 712 | -939 | -9,838 | 76,812 | 0.004 | 0.0417 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |

### R44 sweep

#### R44-ema821_adx15: ema821_adx15: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 3066 | 42% | -220,400 | -654 | 1,958 | -1,556 | -39,602 | 247,613 | 0.2036 | - | walk-forward (folds) [14869.0, -28877.0, -41031.0, -12659.0] |
| FINAL TEST | 1231 | 42% | 19,511 | 147 | 2,066 | -1,474 | -28,823 | 99,145 | 0.0319 | 0.1595 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (ema821_adx15 / B / m1) | 3129 | 43% | -52,117 | -155 | 1,603 | -1,254 | -26,517 | 97,246 | - | - | walk-forward out-of-fold -41,279 on 2308 trades |
| RE-TUNE FINAL | 1262 | 45% | -34,495 | -259 | 1,422 | -1,191 | -22,019 | 91,648 | 0.014 | 0.1029 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R44-ema821_adx15_plock: ema821_adx15_plock: 5-min index signal, ATM, stop -25% / target +50% / 60 min, stepped lock

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 3066 | 41% | -255,364 | -758 | 1,983 | -1,494 | -39,602 | 268,369 | 0.2675 | - | walk-forward (folds) [17599.0, -34297.0, -44629.0, -28159.0] |
| FINAL TEST | 1231 | 42% | 21,480 | 162 | 2,079 | -1,456 | -28,823 | 95,939 | 0.0319 | 0.1595 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (ema821_adx15_plock / B / m1) | 3129 | 43% | -50,947 | -151 | 1,606 | -1,244 | -25,438 | 94,098 | - | - | walk-forward out-of-fold -38,882 on 2308 trades |
| RE-TUNE FINAL | 1262 | 44% | -32,749 | -246 | 1,427 | -1,185 | -22,019 | 90,436 | 0.014 | 0.1029 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R44-ema921_adx20: ema921_adx20: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 1824 | 43% | -98,038 | -291 | 2,103 | -1,683 | -35,420 | 162,354 | 0.1457 | - | walk-forward (folds) [25611.0, 6561.0, -56678.0, -19112.0] |
| FINAL TEST | 774 | 42% | 75,769 | 570 | 2,196 | -1,397 | -20,212 | 76,789 | 0.018 | 0.1125 | q > 0.10; design walk-forward <= 0 |
| RE-TUNE design (ema921_adx20 / B / m1) | 1847 | 43% | 66,321 | 197 | 1,804 | -1,321 | -23,293 | 53,843 | - | - | walk-forward out-of-fold 15,277 on 1364 trades |
| RE-TUNE FINAL | 792 | 43% | -47,054 | -354 | 1,447 | -1,211 | -21,475 | 83,919 | 0.1597 | 0.363 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net |

#### R44-ema926_adx20: ema926_adx20: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 1574 | 42% | -138,489 | -411 | 2,138 | -1,725 | -30,564 | 163,757 | 0.5569 | - | walk-forward (folds) [39326.0, -17821.0, -26375.0, -24471.0] |
| FINAL TEST | 653 | 42% | 78,014 | 587 | 2,340 | -1,506 | -21,169 | 65,270 | 0.0399 | 0.1781 | q > 0.10; design walk-forward <= 0 |
| RE-TUNE design (ema926_adx20 / B / m1) | 1597 | 45% | 65,695 | 195 | 1,757 | -1,336 | -28,109 | 72,014 | - | - | walk-forward out-of-fold 64,465 on 1175 trades |
| RE-TUNE FINAL | 664 | 45% | -23,143 | -174 | 1,516 | -1,282 | -16,151 | 76,796 | 0.1337 | 0.3343 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net |

#### R44-rsi_fade: rsi_fade: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 2216 | 39% | -739,021 | -2,193 | 1,877 | -1,738 | -40,582 | 747,008 | 0.002 | - | walk-forward (folds) [-210685.0, -142813.0, -211983.0, -61213.0] |
| FINAL TEST | 893 | 39% | -427,108 | -3,211 | 1,550 | -1,755 | -32,985 | 434,843 | 0.525 | 0.7907 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (rsi_fade / B / m0) | 2353 | 40% | -545,885 | -1,620 | 1,373 | -1,310 | -24,090 | 570,698 | - | - | walk-forward out-of-fold -447,528 on 1740 trades |
| RE-TUNE FINAL | 955 | 38% | -393,390 | -2,958 | 1,150 | -1,374 | -23,877 | 394,836 | 0.988 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R44-rsi_momo: rsi_momo: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 4906 | 43% | -271,344 | -805 | 2,007 | -1,608 | -55,961 | 283,625 | 0.7146 | - | walk-forward (folds) [88710.0, -163438.0, 9979.0, -32203.0] |
| FINAL TEST | 1898 | 44% | -17,961 | -135 | 1,947 | -1,527 | -34,899 | 154,894 | 0.1437 | 0.3522 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (rsi_momo / C / m1) | 4584 | 45% | -152,890 | -454 | 2,674 | -2,218 | -53,287 | 427,952 | - | - | walk-forward out-of-fold -184,271 on 3653 trades |
| RE-TUNE FINAL | 1778 | 43% | -5,438 | -41 | 2,775 | -2,101 | -39,042 | 134,990 | 0.8323 | 0.9458 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R44-donchian20: donchian20: 5-min index signal, ATM, stop -25% / target +50% / 60 min

Signal source: index (5-min). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R44 (2021 - Oct 2026, 5 indices + CRUDEOIL): out of sample 0 of 14 strategy-side combinations positive, 0 of 112 cells pass; EMA8/21+ADX15 alone -Rs 8.3 lakh on 9,455 NSE trades |
| NOW (design) | 4333 | 44% | -152,250 | -452 | 2,050 | -1,674 | -67,140 | 342,533 | 1.0 | - | walk-forward (folds) [5392.0, -7338.0, 56115.0, 81708.0] |
| FINAL TEST | 1718 | 45% | 56,300 | 423 | 1,997 | -1,551 | -46,837 | 171,548 | 0.485 | 0.767 | p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (donchian20 / B / m1) | 4517 | 45% | 33,681 | 100 | 1,704 | -1,368 | -54,846 | 207,301 | - | - | walk-forward out-of-fold 181,820 on 3274 trades |
| RE-TUNE FINAL | 1775 | 44% | 48,689 | 366 | 1,567 | -1,188 | -23,012 | 92,452 | 0.002 | 0.025 | PASS |

### F1 factory

#### F1-C1: Trend stack: 5-min EMA9>EMA21, 5-min Supertrend up, index above option-volume VWAP (mirror for puts); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1 holdout 2023-Jun 2024: -Rs 2.99 lakh on 2,256 trades, p 1.000 |
| NOW (design) | 2195 | 55% | -45,134 | -134 | 993 | -1,277 | -21,097 | 65,573 | 0.0319 | - | walk-forward (folds) [-24524.0, 21486.0, -27256.0, -13795.0] |
| FINAL TEST | 849 | 53% | 11,618 | 87 | 987 | -1,089 | -13,795 | 56,001 | 0.014 | 0.1029 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / C1 / q0.2 / x1) | 2195 | 55% | -45,134 | -134 | 993 | -1,277 | -21,097 | 65,573 | - | - | walk-forward out-of-fold -44,089 on 1594 trades |
| RE-TUNE FINAL | 849 | 53% | 11,618 | 87 | 987 | -1,089 | -13,795 | 56,001 | 0.014 | 0.1029 | q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-C2: OI + VWAP: put-writing bias > 0 and index above option-volume VWAP (mirror for puts); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1 holdout: -Rs 3.33 lakh on 2,224 |
| NOW (design) | 2586 | 54% | -234,802 | -697 | 971 | -1,330 | -18,582 | 257,030 | 0.1856 | - | walk-forward (folds) [6231.0, -5920.0, -83416.0, -43958.0] |
| FINAL TEST | 994 | 51% | -133,833 | -1,006 | 897 | -1,196 | -10,625 | 150,436 | 0.6467 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / C2 / q0.2 / x1) | 2586 | 54% | -234,802 | -697 | 971 | -1,330 | -18,582 | 257,030 | - | - | walk-forward out-of-fold -169,336 on 1902 trades |
| RE-TUNE FINAL | 994 | 51% | -133,833 | -1,006 | 897 | -1,196 | -10,625 | 150,436 | 0.6467 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S1: atr_14m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 279 | 62% | -56,545 | -168 | 931 | -2,053 | -5,858 | 60,193 | 0.7106 | - | walk-forward (folds) [4647.0, 4427.0, -10139.0, -31789.0] |
| FINAL TEST | 123 | 55% | -24,971 | -189 | 937 | -1,613 | -5,068 | 28,608 | 0.7485 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S1 / q0.2 / x1) | 279 | 62% | -56,545 | -168 | 931 | -2,053 | -5,858 | 60,193 | - | - | walk-forward out-of-fold -32,853 on 197 trades |
| RE-TUNE FINAL | 123 | 55% | -24,971 | -189 | 937 | -1,613 | -5,068 | 28,608 | 0.7485 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S2: rv_30m top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1 holdout: +Rs 15,354 on 228 (q 0.279); lost in design |
| NOW (design) | 233 | 61% | -35,794 | -106 | 827 | -1,712 | -5,353 | 35,794 | 0.5329 | - | walk-forward (folds) [-10879.0, 8561.0, 14815.0, -30606.0] |
| FINAL TEST | 119 | 56% | -14,901 | -113 | 778 | -1,289 | -4,033 | 20,210 | 0.4331 | 0.7416 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S2 / q0.2 / x1) | 233 | 61% | -35,794 | -106 | 827 | -1,712 | -5,353 | 35,794 | - | - | walk-forward out-of-fold -18,109 on 156 trades |
| RE-TUNE FINAL | 119 | 56% | -14,901 | -113 | 778 | -1,289 | -4,033 | 20,210 | 0.4331 | 0.7416 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S3: atr14_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 268 | 62% | -48,639 | -144 | 929 | -2,018 | -5,858 | 50,848 | 0.6886 | - | walk-forward (folds) [6507.0, 2543.0, -2398.0, -32111.0] |
| FINAL TEST | 120 | 55% | -25,793 | -195 | 921 | -1,603 | -5,068 | 29,431 | 0.7445 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S3 / q0.2 / x1) | 268 | 62% | -48,639 | -144 | 929 | -2,018 | -5,858 | 50,848 | - | - | walk-forward out-of-fold -25,459 on 186 trades |
| RE-TUNE FINAL | 120 | 55% | -25,793 | -195 | 921 | -1,603 | -5,068 | 29,431 | 0.7445 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S4: range_15m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 308 | 64% | -37,645 | -112 | 887 | -1,913 | -5,858 | 41,989 | 0.3273 | - | walk-forward (folds) [13149.0, 2445.0, 407.0, -29346.0] |
| FINAL TEST | 130 | 52% | -38,659 | -293 | 860 | -1,567 | -5,068 | 42,296 | 0.8982 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S4 / q0.2 / x1) | 308 | 64% | -37,645 | -112 | 887 | -1,913 | -5,858 | 41,989 | - | - | walk-forward out-of-fold -13,344 on 221 trades |
| RE-TUNE FINAL | 130 | 52% | -38,659 | -293 | 860 | -1,567 | -5,068 | 42,296 | 0.8982 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S5: donchian20_width_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 316 | 63% | -49,111 | -146 | 917 | -2,005 | -5,858 | 54,732 | 0.493 | - | walk-forward (folds) [5075.0, -3613.0, -9935.0, -28982.0] |
| FINAL TEST | 132 | 54% | -27,403 | -208 | 934 | -1,537 | -5,068 | 31,040 | 0.7725 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S5 / q0.2 / x1) | 316 | 63% | -49,111 | -146 | 917 | -2,005 | -5,858 | 54,732 | - | - | walk-forward out-of-fold -37,454 on 230 trades |
| RE-TUNE FINAL | 132 | 54% | -27,403 | -208 | 934 | -1,537 | -5,068 | 31,040 | 0.7725 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S6: range_30m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 271 | 61% | -48,019 | -142 | 819 | -1,752 | -5,353 | 52,373 | 0.6128 | - | walk-forward (folds) [-16316.0, -11963.0, 4775.0, -18174.0] |
| FINAL TEST | 117 | 55% | -21,075 | -160 | 822 | -1,390 | -4,033 | 24,506 | 0.6826 | 0.9077 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S6 / q0.2 / x2) | 271 | 74% | -32,159 | -95 | 930 | -3,130 | -6,382 | 49,187 | - | - | walk-forward out-of-fold -28,243 on 191 trades |
| RE-TUNE FINAL | 117 | 74% | -13,755 | -104 | 871 | -2,861 | -6,185 | 29,549 | 0.517 | 0.7881 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S7: range_60m_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 212 | 62% | -17,481 | -52 | 857 | -1,633 | -4,894 | 31,159 | 0.1836 | - | walk-forward (folds) [-14068.0, -9786.0, 15018.0, -8368.0] |
| FINAL TEST | 98 | 64% | 15,028 | 114 | 892 | -1,176 | -3,708 | 10,906 | 0.022 | 0.125 | q > 0.10; design walk-forward <= 0 |
| RE-TUNE design (f1 / S7 / q0.2 / x1) | 212 | 62% | -17,481 | -52 | 857 | -1,633 | -4,894 | 31,159 | - | - | walk-forward out-of-fold -17,204 on 147 trades |
| RE-TUNE FINAL | 98 | 64% | 15,028 | 114 | 892 | -1,176 | -3,708 | 10,906 | 0.022 | 0.125 | q > 0.10; design walk-forward <= 0 |

#### F1-S8: gex_near_share + bu_pe_long_unwinding_share top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 204 | 63% | -53,650 | -159 | 843 | -2,126 | -6,357 | 55,501 | 0.7824 | - | walk-forward (folds) [-7808.0, -4407.0, 1018.0, -40059.0] |
| FINAL TEST | 98 | 55% | -18,791 | -142 | 896 | -1,526 | -4,939 | 26,963 | 0.6407 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S8 / q0.2 / x1) | 204 | 63% | -53,650 | -159 | 843 | -2,126 | -6,357 | 55,501 | - | - | walk-forward out-of-fold -51,256 on 144 trades |
| RE-TUNE FINAL | 98 | 55% | -18,791 | -142 | 896 | -1,526 | -4,939 | 26,963 | 0.6407 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S9: gex_near_share + day_range_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 125 | 62% | -34,838 | -103 | 739 | -1,911 | -6,357 | 35,893 | 0.6846 | - | walk-forward (folds) [-11518.0, -2276.0, 14082.0, -27143.0] |
| FINAL TEST | 78 | 54% | -24,845 | -188 | 766 | -1,584 | -4,939 | 32,278 | 0.8703 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S9 / q0.2 / x1) | 125 | 62% | -34,838 | -103 | 739 | -1,911 | -6,357 | 35,893 | - | - | walk-forward out-of-fold -26,854 on 83 trades |
| RE-TUNE FINAL | 78 | 54% | -24,845 | -188 | 766 | -1,584 | -4,939 | 32,278 | 0.8703 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S10: bu_pe_long_unwinding_share + day_range_pct top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 131 | 59% | -28,861 | -86 | 823 | -1,708 | -5,372 | 31,809 | 0.5908 | - | walk-forward (folds) [-1881.0, -4163.0, 4694.0, -21646.0] |
| FINAL TEST | 82 | 54% | -16,378 | -124 | 849 | -1,414 | -4,368 | 20,714 | 0.6487 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S10 / q0.2 / x1) | 131 | 59% | -28,861 | -86 | 823 | -1,708 | -5,372 | 31,809 | - | - | walk-forward out-of-fold -23,755 on 88 trades |
| RE-TUNE FINAL | 82 | 54% | -16,378 | -124 | 849 | -1,414 | -4,368 | 20,714 | 0.6487 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S11: iv_skew_3 + ema20 top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 293 | 58% | -30,429 | -90 | 1,087 | -1,750 | -21,726 | 73,745 | 0.2415 | - | walk-forward (folds) [6185.0, -3329.0, 408.0, 2650.0] |
| FINAL TEST | 231 | 52% | -11,556 | -87 | 956 | -1,137 | -5,466 | 28,150 | 0.2335 | 0.4708 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S11 / q0.2 / x1) | 293 | 58% | -30,429 | -90 | 1,087 | -1,750 | -21,726 | 73,745 | - | - | walk-forward out-of-fold 1,272 on 86 trades |
| RE-TUNE FINAL | 231 | 52% | -11,556 | -87 | 956 | -1,137 | -5,466 | 28,150 | 0.2335 | 0.4708 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net |

#### F1-S12: iv_skew_3 + ema50 top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 295 | 57% | -36,133 | -107 | 1,080 | -1,713 | -21,726 | 74,711 | 0.3533 | - | walk-forward (folds) [5307.0, -3329.0, 408.0, 2320.0] |
| FINAL TEST | 232 | 52% | -11,727 | -88 | 952 | -1,144 | -5,466 | 29,046 | 0.2255 | 0.4698 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S12 / q0.2 / x1) | 295 | 57% | -36,133 | -107 | 1,080 | -1,713 | -21,726 | 74,711 | - | - | walk-forward out-of-fold -283 on 88 trades |
| RE-TUNE FINAL | 232 | 52% | -11,727 | -88 | 952 | -1,144 | -5,466 | 29,046 | 0.2255 | 0.4698 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S13: greek_atmm2_delta_c top quintile (low side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 127 | 59% | -59,474 | -176 | 797 | -2,293 | -5,372 | 61,016 | 0.986 | - | walk-forward (folds) [-8794.0, 0.0, 0.0, -34454.0] |
| FINAL TEST | 77 | 62% | -9,320 | -71 | 926 | -1,854 | -5,068 | 12,957 | 0.497 | 0.767 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S13 / q0.2 / x1) | 127 | 59% | -59,474 | -176 | 797 | -2,293 | -5,372 | 61,016 | - | - | walk-forward out-of-fold -43,248 on 69 trades |
| RE-TUNE FINAL | 77 | 62% | -9,320 | -71 | 926 | -1,854 | -5,068 | 12,957 | 0.497 | 0.767 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S14: greek_atmm2_delta_p top quintile (low side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 127 | 59% | -59,474 | -176 | 797 | -2,293 | -5,372 | 61,016 | 0.986 | - | walk-forward (folds) [-8794.0, 0.0, 0.0, -34454.0] |
| FINAL TEST | 77 | 62% | -9,320 | -71 | 926 | -1,854 | -5,068 | 12,957 | 0.497 | 0.767 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S14 / q0.2 / x1) | 127 | 59% | -59,474 | -176 | 797 | -2,293 | -5,372 | 61,016 | - | - | walk-forward out-of-fold -43,248 on 69 trades |
| RE-TUNE FINAL | 77 | 62% | -9,320 | -71 | 926 | -1,854 | -5,068 | 12,957 | 0.497 | 0.767 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S15: lvl_near_count top quintile (low side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 178 | 60% | -32,588 | -97 | 951 | -1,853 | -5,372 | 44,468 | 0.5649 | - | walk-forward (folds) [-7493.0, -3863.0, 14467.0, -31905.0] |
| FINAL TEST | 73 | 49% | -19,763 | -150 | 911 | -1,420 | -5,068 | 25,553 | 0.8024 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S15 / q0.2 / x2) | 178 | 73% | -22,571 | -67 | 1,016 | -3,223 | -6,403 | 39,532 | - | - | walk-forward out-of-fold -19,896 on 118 trades |
| RE-TUNE FINAL | 73 | 66% | -29,808 | -226 | 852 | -2,829 | -8,379 | 30,274 | 0.8583 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S16: tod_logrange_60m top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 179 | 62% | -32,004 | -95 | 1,032 | -2,155 | -5,858 | 36,036 | 0.6607 | - | walk-forward (folds) [5093.0, 0.0, -4463.0, -12158.0] |
| FINAL TEST | 80 | 54% | -19,862 | -150 | 922 | -1,608 | -5,068 | 23,315 | 0.7884 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S16 / q0.2 / x1) | 179 | 62% | -32,004 | -95 | 1,032 | -2,155 | -5,858 | 36,036 | - | - | walk-forward out-of-fold -11,528 on 91 trades |
| RE-TUNE FINAL | 80 | 54% | -19,862 | -150 | 922 | -1,608 | -5,068 | 23,315 | 0.7884 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S17: range_30m_pct top quintile (high side) -> PE (net_pe_atm_x1, x1); 1-ITM, x1

Signal source: underlying features. Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1: 0 of 20 pass; this rule lost in the holdout (2023 - Jun 2024) |
| NOW (design) | 271 | 62% | 10,233 | 30 | 882 | -1,319 | -4,020 | 30,215 | 0.018 | - | walk-forward (folds) [10609.0, 5163.0, -5857.0, -9624.0] |
| FINAL TEST | 117 | 50% | -30,120 | -228 | 905 | -1,400 | -4,512 | 33,185 | 0.7545 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net |
| RE-TUNE design (f1 / S17 / q0.2 / x1) | 271 | 62% | 10,233 | 30 | 882 | -1,319 | -4,020 | 30,215 | - | - | walk-forward out-of-fold -13,607 on 191 trades |
| RE-TUNE FINAL | 117 | 50% | -30,120 | -228 | 905 | -1,400 | -4,512 | 33,185 | 0.7545 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### F1-S18: iv_pct_252 top quintile (high side) -> CE (net_ce_atm_x1, x1); 1-ITM, x1

Signal source: underlying features + option-chain features (filters). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | F1 holdout: +Rs 12,319 on 158 (q 0.279); lost in design |
| NOW (design) | 101 | 63% | -30,412 | -90 | 885 | -2,352 | -5,372 | 32,622 | 0.7385 | - | walk-forward (folds) [1882.0, 681.0, 4844.0, -24020.0] |
| FINAL TEST | 95 | 53% | -24,748 | -188 | 955 | -1,611 | -5,068 | 28,386 | 0.7944 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (f1 / S18 / q0.2 / x1) | 101 | 63% | -30,412 | -90 | 885 | -2,352 | -5,372 | 32,622 | - | - | walk-forward out-of-fold -30,680 on 64 trades |
| RE-TUNE FINAL | 95 | 53% | -24,748 | -188 | 955 | -1,611 | -5,068 | 28,386 | 0.7944 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

### B2 all-at-once

#### B2-G15: B2 G15: most confident 10% of a GBM, ATM, x1

Signal source: underlying features (one GBM). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | B2 holdout: -Rs 21,397 (527) |
| NOW (design) | 1125 | 47% | -103,570 | -419 | 824 | -917 | -12,608 | 142,843 | 0.018 | - | walk-forward (folds) [-45639.0, -25825.0, -32325.0, 219.0] |
| FINAL TEST | 706 | 42% | -90,597 | -681 | 836 | -841 | -8,892 | 100,670 | 0.2535 | 0.4875 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (b2 / G15 / p0.95 / x1) | 729 | 47% | -65,760 | -266 | 831 | -923 | -8,157 | 114,369 | - | - | walk-forward out-of-fold -77,887 on 1017 trades |
| RE-TUNE FINAL | 447 | 42% | -77,573 | -583 | 818 | -880 | -8,623 | 84,537 | 0.3713 | 0.6537 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### B2-G30: B2 G30: most confident 10% of a GBM, ATM, x1

Signal source: underlying features (one GBM). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | B2 holdout: -Rs 26,901 (271) |
| NOW (design) | 819 | 47% | -122,385 | -496 | 824 | -1,013 | -14,201 | 124,301 | 0.1876 | - | walk-forward (folds) [-72760.0, -9411.0, -34681.0, -5534.0] |
| FINAL TEST | 532 | 47% | -67,316 | -506 | 832 | -970 | -11,062 | 73,514 | 0.1697 | 0.3721 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (b2 / G30 / p0.95 / x1) | 481 | 52% | -19,558 | -79 | 819 | -971 | -12,352 | 34,439 | - | - | walk-forward out-of-fold -61,378 on 544 trades |
| RE-TUNE FINAL | 318 | 47% | -36,797 | -277 | 842 | -982 | -8,532 | 40,056 | 0.0579 | 0.2335 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### B2-G60: B2 G60: most confident 10% of a GBM, ATM, x1

Signal source: underlying features (one GBM). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | B2 holdout: -Rs 16,189 (106) |
| NOW (design) | 460 | 51% | -17,253 | -70 | 981 | -1,092 | -16,557 | 44,941 | 0.002 | - | walk-forward (folds) [-27631.0, 12763.0, -18246.0, 15861.0] |
| FINAL TEST | 363 | 52% | -38,033 | -286 | 773 | -1,069 | -14,205 | 47,891 | 0.0599 | 0.234 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (b2 / G60 / p0.95 / x1) | 228 | 53% | 16,967 | 69 | 1,002 | -956 | -5,288 | 16,755 | - | - | walk-forward out-of-fold -16,144 on 264 trades |
| RE-TUNE FINAL | 211 | 57% | -11,494 | -86 | 703 | -1,073 | -9,153 | 28,579 | 0.018 | 0.1125 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### B2-OPT: B2 OPT: most confident 10% of a GBM, ATM, x1

Signal source: underlying features (one GBM). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | B2 holdout: -Rs 23,289 (331) |
| NOW (design) | 622 | 54% | -54,260 | -220 | 884 | -1,228 | -18,349 | 78,333 | 0.022 | - | walk-forward (folds) [-21716.0, -8390.0, -4743.0, -19411.0] |
| FINAL TEST | 490 | 53% | -58,128 | -437 | 911 | -1,264 | -13,608 | 58,128 | 0.1138 | 0.308 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (b2 / OPT / p0.95 / x1) | 386 | 56% | -16,841 | -68 | 889 | -1,217 | -9,509 | 56,363 | - | - | walk-forward out-of-fold -18,862 on 508 trades |
| RE-TUNE FINAL | 322 | 51% | -70,721 | -532 | 849 | -1,329 | -10,144 | 70,721 | 0.6108 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

### R41 neural nets

#### R41-R1: R1: direction 15 min, top 10% confidence, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: -Rs 424/day (2,752 trades) |
| NOW (design) | 3531 | 42% | -349,964 | -1,417 | 872 | -810 | -41,648 | 369,435 | 0.002 | - | walk-forward (folds) [-121519.0, -84168.0, -36724.0, -107552.0] |
| FINAL TEST | 1452 | 43% | -176,861 | -1,330 | 861 | -854 | -24,719 | 186,327 | 0.0379 | 0.1755 | final net <= 0; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R15 / size0 / conf5 / close30) | 1983 | 43% | -186,814 | -756 | 905 | -840 | -38,086 | 202,425 | - | - | walk-forward out-of-fold -285,890 on 2381 trades |
| RE-TUNE FINAL | 716 | 42% | -115,087 | -865 | 906 | -948 | -23,780 | 121,014 | 0.1477 | 0.355 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R2: R2: direction 30 min, top 10% confidence, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: -Rs 252/day (2,150) |
| NOW (design) | 2485 | 42% | -194,214 | -786 | 1,242 | -1,038 | -23,299 | 251,983 | 0.002 | - | walk-forward (folds) [-79877.0, -119548.0, 15884.0, -10674.0] |
| FINAL TEST | 1233 | 41% | -170,068 | -1,279 | 1,113 | -1,012 | -23,523 | 199,557 | 0.002 | 0.025 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R30 / size0 / conf5 / close30) | 1406 | 43% | -39,192 | -159 | 1,318 | -1,047 | -16,615 | 121,962 | - | - | walk-forward out-of-fold -87,690 on 1633 trades |
| RE-TUNE FINAL | 636 | 42% | -70,546 | -530 | 1,092 | -965 | -20,266 | 81,629 | 0.002 | 0.025 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R3: R3: direction 60 min, top 10% confidence, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: -Rs 106/day (1,499) |
| NOW (design) | 1596 | 38% | -156,741 | -635 | 1,922 | -1,325 | -30,790 | 200,449 | 0.02 | - | walk-forward (folds) [-110355.0, -20514.0, -8563.0, -17309.0] |
| FINAL TEST | 873 | 39% | -324,674 | -2,441 | 1,674 | -1,690 | -156,549 | 343,538 | 0.6028 | 0.8719 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R60 / size0 / conf5 / close30) | 941 | 38% | -114,126 | -462 | 1,877 | -1,371 | -24,592 | 116,536 | - | - | walk-forward out-of-fold -188,041 on 1095 trades |
| RE-TUNE FINAL | 505 | 37% | -282,373 | -2,123 | 1,705 | -1,880 | -157,525 | 296,249 | 0.8822 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R4: R4: direction 15 min, top 10% confidence, size forecast >= median, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: +Rs 20/day on 172 (too few) |
| NOW (design) | 2204 | 44% | -205,486 | -832 | 1,046 | -973 | -41,648 | 228,687 | 0.006 | - | walk-forward (folds) [-54986.0, -12968.0, -36497.0, -101035.0] |
| FINAL TEST | 879 | 42% | -158,267 | -1,190 | 957 | -1,018 | -25,302 | 167,734 | 0.3333 | 0.6038 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R15 / size1 / conf5 / close30) | 1290 | 44% | -119,012 | -482 | 1,059 | -1,007 | -38,086 | 138,756 | - | - | walk-forward out-of-fold -178,367 on 1520 trades |
| RE-TUNE FINAL | 473 | 42% | -104,757 | -788 | 960 | -1,095 | -24,363 | 110,388 | 0.4571 | 0.767 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R5: R5: direction 30 min, top 10% confidence, size forecast >= median, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: -Rs 22/day (56) |
| NOW (design) | 1541 | 44% | -58,947 | -239 | 1,479 | -1,240 | -18,661 | 107,808 | 0.002 | - | walk-forward (folds) [-19931.0, -51308.0, 16334.0, -4043.0] |
| FINAL TEST | 689 | 42% | -110,333 | -830 | 1,342 | -1,226 | -24,311 | 140,475 | 0.004 | 0.0417 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R30 / size1 / conf5 / close30) | 905 | 45% | -1,670 | -7 | 1,533 | -1,251 | -16,615 | 62,066 | - | - | walk-forward out-of-fold -26,208 on 1158 trades |
| RE-TUNE FINAL | 358 | 44% | -41,256 | -310 | 1,300 | -1,221 | -20,785 | 47,651 | 0.002 | 0.025 | final net <= 0; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R6: R6: direction 60 min, top 10% confidence, size forecast >= median, ATM, exit at t+h or -30% close stop

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: -Rs 10/day (21) |
| NOW (design) | 960 | 40% | -25,364 | -103 | 2,394 | -1,640 | -30,790 | 110,870 | 0.018 | - | walk-forward (folds) [-90072.0, 44292.0, 24208.0, -3791.0] |
| FINAL TEST | 537 | 39% | -281,471 | -2,116 | 1,870 | -2,050 | -158,915 | 299,729 | 0.8064 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R60 / size1 / conf / stop15) | 1020 | 36% | -22,532 | -91 | 2,484 | -1,413 | -29,262 | 110,042 | - | - | walk-forward out-of-fold -88,824 on 685 trades |
| RE-TUNE FINAL | 565 | 36% | -257,062 | -1,933 | 2,022 | -1,855 | -157,699 | 277,405 | 0.7944 | 0.9248 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

#### R41-R13: R13: next-morning gap from 15:19, top 20% confidence, 1-ITM 15:20 -> next 09:16

Signal source: index (brain features of the underlying; MLP). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41 holdout: +Rs 24/day (208 trades, q 0.11) |
| NOW (design) | 246 | 46% | -102,504 | -415 | 2,884 | -3,267 | -36,013 | 152,590 | 0.3114 | - | walk-forward (folds) [5516.0, 22734.0, -46258.0, -84497.0] |
| FINAL TEST | 72 | 50% | -76,961 | -579 | 2,810 | -4,947 | -16,917 | 96,824 | 0.9022 | 0.9557 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |
| RE-TUNE design (r41 / R13 / conf5) | 93 | 40% | -44,125 | -179 | 3,953 | -3,400 | -31,174 | 74,572 | - | - | walk-forward out-of-fold -102,504 on 246 trades |
| RE-TUNE FINAL | 6 | 50% | -8,707 | -66 | 2,555 | -5,457 | -11,049 | 12,672 | 0.6467 | 0.8719 | too few trades to judge |

#### R41-ALWAYS-CALL: the 1-ITM call bought 15:20, sold next 09:16, every night

Signal source: the clock (every non-expiry night). Kind: orig.

| | trades | win | net Rs | net/day | avg win | avg loss | worst day | max DD | p | q | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| OLD (full data) | | | | | | | | | | | R41: +Rs 22/day on the same nights (baseline, never gated) |
| NOW (design) | 1441 | 43% | -744,207 | -2,208 | 2,630 | -2,906 | -44,859 | 783,972 | 1.0 | - | walk-forward (folds) [18300.0, -141197.0, -70455.0, -351316.0] |
| FINAL TEST | 520 | 43% | -536,420 | -4,033 | 2,953 | -4,095 | -47,545 | 561,519 | 1.0 | 1.0 | final net <= 0; p >= 0.05; q > 0.10; top-5 trades >= net; design walk-forward <= 0 |

NIGHT-HOLD-RT against the 09:16 sale: -12,713 Rs (p 0.995).

### Passes

R44-donchian20-RT
