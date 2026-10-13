# R23: which morning range (if any) helps the ORB

Written 13 Oct 2026 for Boss: "Check the candles, do a deep analysis, and find the best way to find a range which could
help [the ORB]." Research only, no app code.

Pre-registration `research/hunt/r23/PREREG.md`:
- part A (data, the anatomy protocol, all 22 range configurations, costs, the selection rule, the fresh-lake gates) was
  committed in **ddc9e8a** before any number was computed;
- part B (the two quality-filter features, picked from Step 1 by a rule fixed in part A) in **5c342af** before any
  trading test;
- part C (the 3 frozen finalists and the one lake command) in **a9a21ed**, before any lake read.

Code: `research/hunt/r23/` (`r23ranges.py`, `r23features.py`, `r23data.py`, `r23anatomy.py`, `r23trades.py`, tests
`test_r23.py`). Tables: `results/anatomy.md|json`, `results/release.md|json`, `results/finalists.json`.

## Verdict for Boss (read this first)

1. **The final answer is not in yet. It waits for the 6-year Dhan lake.** The 2-year release data has been studied
   by R16, R18, R20, R20b, R20c and R22, holdout included, so nothing measured on it can be trusted as a fresh test. R23
   used all of it as design data only.
   - The honest test is the lake's fresh holdout: sessions from 27 Apr 2026 that no study has seen.
   - The lake is not published yet. Its catalog says `release_published: false`, the release tag does not exist, and
     only the NSE bhavcopy is on Drive (the Dhan rebuild hit DH-905 errors).
   - So R23 froze its finalists and left one command: `R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake`.
2. **Good-break mornings vs fakeout mornings.**
   - The data: 1,242 breaks of today's ORB range on BANKNIFTY, NIFTY and FINNIFTY (1,455 sessions). 23% ran at least
     0.5 x ATR14 further ("RUN"). 36% closed back inside within 30 minutes and never went far ("FAKE"). The other 41%
     drifted.
   - Boss's example of 1.5 x ATR almost never happens: 9 breaks of 1,242 (under 1%).
   - What a good-break morning looks like at the moment of the break:
     - price is already well above the morning's average price (TWAP) for an up-break, or well below it for a
       down-break: RUN median 0.26 x ATR away, FAKE 0.20;
     - the break candle closes clearly beyond the edge rather than just poking through;
     - the break comes early. Breaks after about 11:05 were RUN only 16% of the time and FAKE 44%;
     - the edge was not tested again and again first. With 3 or more earlier tests: RUN 17%, FAKE 45%;
     - the opening range is wider (vs ATR and vs yesterday's range);
     - the break goes the same way as yesterday's candle, and away from the pivot.
   - These held in both halves of the data, on BANKNIFTY and on NIFTY, and on a second (30-minute) range.
   - What made **no difference**: option volume in the first 15-45 minutes, the change in option OI, VIX and its
     change, the size of the gap, the shape and colour of the first 5-, 15- or 30-minute candle, inside or outside day,
     and expiry day. All of these had AUC 0.48-0.53, which is a coin toss. VIX change since 09:15 leaned towards good
     breaks (AUC 0.55) but did not pass Benjamini-Hochberg (q 0.13; 185 RUN vs 295 FAKE).
3. **Which range is best?** None of the 18 plain range definitions makes money after costs on the release. Every one
   of them loses: time windows of 15 to 75 minutes, the range's own 15- or 30-minute candles, volatility-sized ranges,
   the first hour's value area, the range stretched to yesterday's high and low, and the range stretched to include the
   gap.
   - The least bad plain range is R22's: the first three 15-minute candles, traded on a 15-minute close (Rs -17,455
     over 515 days).
   - Today's ORB lost Rs -44,720 on the same days. Shorter ranges on 5-minute breaks lost more.
4. **The only positive results were in-sample.** They come from adding the Step 1 "momentum" filter to R22's range:
   - `r23-r22-f2`: Rs +16,158 on 390 trades;
   - `r23-r22-f1`: Rs +15,593 on 472 trades.

   They are not good enough to trade:
   - each was positive in only 2 of 4 blocks (it lost from Mar 2024 to Mar 2025 and made money from Mar 2025 to Apr 2026);
   - the filter was chosen on the same data;
   - **the same filter on mirrored, "wrong" ranges made even more** (Rs +26,318 / +20,408), so the range adds nothing
     and the money comes from "price already running";
   - when the selection rule was tested walk-forward (pick on earlier blocks, trade the next one), its picks lost
     Rs -17,791. That is worse than simply using R22's plain range (Rs -8,535);
   - nothing survives Benjamini-Hochberg: the best q is 0.32 over 80 tests with R20-R22.
5. **No app change is proposed**, on paper or live. The ORB stays paused.
   - The three frozen finalists will be read once on the lake's fresh holdout: `r23-r22-f2`, `r23-r22-f1` and
     `r23-orb-f2`. All three are flagged "carried, not promising".
   - If any of them passes every gate, its exact paper-only rule is in "If a finalist passes" below.

Plain answer: no way of drawing the morning range turns the ORB into a winner after costs. Neither the length of the
window, the candle size, a volatility-sized range, the value area nor yesterday's levels does it. What separates good
breaks from fake ones is not the range but how strongly price is already moving when it breaks. Even that edge sits in
the "momentum" part, which lines drawn in the wrong place share. The final word waits for the lake. Expect it to say no.

## Data and periods

| source | what | span | used for |
|---|---|---|---|
| `research-data` release via `research/hunt/r17/data.py` | BANKNIFTY and NIFTY 1-minute index + about 20 strikes of the nearest expiry AFTER the day (minute OHLC, volume, OI) | BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026 (499) | anatomy, trades |
| release `finnifty_index.parquet` | FINNIFTY 1-minute index (no options) | 12 Feb 2024 - 24 Feb 2026 | anatomy only |
| release `extra_series.parquet` | India VIX minutes (to 27 Feb 2026); Yahoo daily ^INDIAVIX for the previous close after that | 2024-2026 | VIX features |

- MIDCPNIFTY is not in the release. FINNIFTY has no options in it. So the trading test is BANKNIFTY + NIFTY only. The
  lake holds options for FINNIFTY and MIDCPNIFTY and SENSEX, so the lake read can add them if they load through R17's
  adapter.
- Sessions start after 15 warm-up sessions for ATR14. Anatomy: BANKNIFTY 483, NIFTY 484, FINNIFTY 488 sessions.
  Trading: 515 calendar days, 5 Mar 2024 - 13 Apr 2026, all design.
- Design blocks are 4 consecutive equal blocks of calendar days: 5 Mar - 13 Sep 2024, 16 Sep 2024 - 21 Mar 2025, 24 Mar -
  29 Sep 2025, 30 Sep 2025 - 13 Apr 2026.
- Checks before trusting the engine:
  - R22's baselines reproduce exactly on this machine: today's ORB design 685 trades / Rs -15,603, `r22-brk15`
    665 / Rs -8,573;
  - R23's generic engine gives `r22-brk15` trade for trade (894 trades, Rs -17,455 over the whole release);
  - "first signal per side" on today's ORB range gives the same 923 trades as R20's V0.

## Step 1: the anatomy of the morning (design data only)

**Method.** For each session, take the first 5-minute close beyond today's ORB range (09:15-10:04, decision bars
10:05-13:55). Do the same for the first-30-minute range as a check. Measure from that close to 14:59, in ATR14:
- **RUN**: the best move in the break's direction reached at least 0.5 x ATR14;
- **FAKE**: a 5-minute close back inside within 30 minutes, and the best move stayed under 0.25 x ATR14;
- **CHOP**: everything else.

28 features were computed. Every one is known at the break's close (`test_r23.py` replaces every later minute with noise
and checks that nothing changes). AUC = the chance that a RUN break's value is higher than a FAKE break's value. 0.5 means
the feature says nothing.

### A1. How breaks end (ORB range; R30 range in brackets)

| index | sessions | no break | breaks | RUN | FAKE | CHOP | >= 1.0 ATR | >= 1.5 ATR |
|---|---|---|---|---|---|---|---|---|
| BANKNIFTY | 483 | 70 [47] | 413 [436] | 92 = 22% [104] | 156 = 38% [159] | 165 [173] | 18 | 3 |
| NIFTY | 484 | 61 [35] | 423 [449] | 97 = 23% [111] | 144 = 34% [160] | 182 [178] | 20 | 3 |
| FINNIFTY | 488 | 82 [45] | 406 [443] | 95 = 23% [110] | 152 = 37% [168] | 159 [165] | 21 | 3 |

### A2. What separates RUN from FAKE (ORB range: 284 RUN vs 452 FAKE; flow features 189 vs 300)

| feature (at the break close) | RUN median | FAKE median | AUC | BH q | AUC halves 1 / 2 | AUC BANKNIFTY / NIFTY / FINNIFTY | AUC on R30 | qualifies |
|---|---|---|---|---|---|---|---|---|
| distance from the morning TWAP, in the break direction (ATR) `twap_al` | 0.259 | 0.204 | **0.653** | <0.001 | 0.667 / 0.639 | 0.661 / 0.684 / 0.618 | 0.662 | yes |
| how far the break candle closed beyond the edge (ATR) `brk_str` | 0.041 | 0.026 | **0.631** | <0.001 | 0.657 / 0.595 | 0.647 / 0.657 / 0.589 | 0.634 | yes |
| minutes from 09:15 to the break `t_break` | 75 | 90 | **0.396** | <0.001 | 0.395 / 0.405 | 0.361 / 0.407 / 0.423 | 0.434 | yes |
| earlier tests of the edge `tests` | 1 | 2 | **0.397** | <0.001 | 0.421 / 0.376 | 0.362 / 0.399 / 0.429 | 0.407 | yes |
| range width / ATR14 `w_atr` | 0.526 | 0.480 | 0.582 | 0.001 | 0.624 / 0.535 | 0.565 / 0.647 / 0.544 | 0.582 | yes |
| break close vs the floor pivot (ATR) `piv_al` | 0.413 | 0.296 | 0.576 | 0.003 | 0.590 / 0.562 | 0.540 / 0.620 / 0.562 | 0.582 | yes |
| yesterday's candle in the break direction (ATR) `pd_dir_al` | +0.046 | -0.069 | 0.554 | 0.048 | 0.525 / 0.586 | 0.537 / 0.578 / 0.545 | 0.573 | yes |
| range width / yesterday's range `w_prev` | 0.576 | 0.545 | 0.553 | 0.048 | 0.559 / 0.549 | 0.553 / 0.590 / 0.520 | 0.565 | yes |
| break close beyond yesterday's high / low (share) | 50% | 42% | 0.542 | 0.072 | 0.544 / 0.540 | 0.504 / 0.577 / 0.544 | 0.550 | no (abs(AUC - 0.5) = 0.042 < 0.05) |
| VIX change since 09:15 | +0.4% | -0.2% | 0.553 | 0.129 | 0.577 / 0.516 | 0.540 / 0.566 / n/a | 0.541 | no |
| gap in the break direction (ATR) | -0.010 | -0.013 | 0.531 | 0.37 | | | 0.519 | no |
| option volume, first 45 min / its 20-day median | 0.995 | 1.040 | 0.485 | 0.87 | 0.452 / 0.518 | 0.529 / 0.435 / n/a | 0.488 | no |
| option volume, first 15 min / median | 0.988 | 0.979 | 0.493 | 0.91 | | | 0.492 | no |
| put-minus-call OI change in the break direction | 0.054 | 0.044 | 0.512 | 0.89 | 0.538 / 0.473 | | 0.502 | no |
| total OI change | 0.317 | 0.331 | 0.489 | 0.89 | | | 0.500 | no |
| VIX previous close | 13.43 | 13.55 | 0.497 | 0.95 | 0.511 / 0.470 | | 0.457 | no |
| first 5 / 15 / 30-min candle body share | | | 0.498 / 0.488 / 0.500 | >= 0.87 | | | | no |
| first 5 / 15 / 30-min candle colour in the break direction | | | 0.478 / 0.494 / 0.490 | >= 0.54 | | | | no |
| range inside yesterday's range | | | 0.486 | 0.76 | | | | no |
| expiry day | | | 0.502 | 0.95 | | | | no |

All 28 features with their counts: `results/anatomy.md`.

### A3. RUN / FAKE share by tercile (ORB range, all 1,242 breaks; 414 per tercile)

| feature | low third: RUN / FAKE | middle | high third |
|---|---|---|---|
| `twap_al` (cuts 0.188, 0.264 ATR) | 16% / 44% | 20% / 38% | **33% / 28%** |
| `brk_str` (cuts 0.021, 0.055 ATR) | 20% / 45% | 19% / 41% | **29% / 23%** |
| `w_atr` (cuts 0.419, 0.569) | 17% / 41% | 23% / 34% | 29% / 35% |
| `t_break` (before 10:20 / 10:20-11:05 / after 11:05; n 319 / 499 / 424) | 26% / 30% | 26% / 35% | **16% / 44%** |
| `tests` (0 / 1-2 / 3+; n 353 / 464 / 425) | 28% / 31% | 25% / 33% | **17% / 45%** |
| option volume first 45 min (n 279 / 278 / 279) | 23% / 36% | 25% / 34% | 20% / 38% |
| VIX previous close | 24% / 35% | 23% / 42% | 22% / 31% |

Read with care:
- The two strongest features, `twap_al` and `brk_str`, both measure how far price has already gone at the decision.
- `brk_str` is partly tied to the FAKE definition by construction: a close far beyond the edge needs a bigger reversal to
  close back inside.
- An early break has more of the day left to run in (`t_break`).
- So "RUN vs FAKE" overstates what these are worth in money. The trading test below is what counts.
- Range width (`w_atr`) also looked good in R22's design. It then reversed in R22's holdout, so it is not trusted on its own.

## Step 2: the 22 range configurations

Every configuration uses the ORB arm's trade:
- the first close beyond each edge (closing by 14:00), at most 1 trade a side per index per day;
- ATM of the 09:20 close, next expiry, entry at the next minute's open;
- exits: -40 / +40 (NIFTY -20 / +20), the profit-lock ladder, out at 15:10;
- R17's costs (paper fills +-5 bps plus a 1-4 tick half-spread, stops x2, the app's charges, the lot in force).

Definitions are in PREREG.md part A3. The (f) filters (part B):
- f1 = `twap_al` > 0.21982;
- f2 = f1 and `brk_str` > 0.03538. Both cuts are the design medians.

### T1. Release, 515 calendar days, BANKNIFTY + NIFTY, Rs after costs (ALL design data; p in-sample)

| id | what | trades | net | per day | green trade days | worst day | max DD | 4 blocks | BANKNIFTY / NIFTY |
|---|---|---|---|---|---|---|---|---|---|
| today's ORB | 09:15-10:04, 5-min break | 923 | -44,720 | -87 | 44% | -4,295 | -48,437 | -2,133 / -26,791 / +9,753 / -25,549 | -25,737 / -18,984 |
| `r22-brk15` | 09:15-09:59, 15-min break | 894 | -17,455 | -34 | 46% | -4,564 | -36,906 | -8,921 / -18,042 / +12,154 / -2,646 | -18,897 / +1,441 |
| `r23-t15-5m` | (a) first 15 min | 1,171 | -71,624 | -139 | 47% | -3,389 | -73,676 | 0 of 4 positive | -47,677 / -23,947 |
| `r23-t30-5m` | (a) first 30 min | 1,037 | -85,744 | -166 | 42% | -4,295 | -87,585 | 0 of 4 | -47,600 / -38,145 |
| `r23-t45-5m` | (a) first 45 min | 952 | -49,273 | -96 | 45% | -4,295 | -53,690 | 1 of 4 | -22,798 / -26,475 |
| `r23-t60-5m` | (a) first hour | 883 | -38,205 | -74 | 43% | -4,297 | -40,741 | 1 of 4 | -25,366 / -12,840 |
| `r23-t75-5m` | (a) first 75 min | 822 | -81,799 | -159 | 38% | -4,391 | -84,988 | 0 of 4 | -44,358 / -37,441 |
| `r23-t15-15m` | (b) 1 x 15-min candle, 15-min break | 1,095 | -18,996 | -37 | 43% | -3,663 | -41,807 | 1 of 4 | -9,643 / -9,353 |
| `r23-t30-15m` | (b) 2 x 15-min | 966 | -38,787 | -75 | 45% | -4,564 | -50,695 | 1 of 4 | -34,644 / -4,144 |
| `r23-t60-15m` | (b) 4 x 15-min | 829 | -32,531 | -63 | 44% | -3,152 | -43,560 | 1 of 4 | -28,059 / -4,472 |
| `r23-t75-15m` | (b) 5 x 15-min | 755 | -48,987 | -95 | 40% | -4,675 | -50,202 | 1 of 4 | -33,899 / -15,088 |
| `r23-t30-30m` | (b) 1 x 30-min, 30-min break | 885 | -52,248 | -101 | 43% | -4,605 | -55,191 | 0 of 4 | -40,426 / -11,821 |
| `r23-t60-30m` | (b) 2 x 30-min | 753 | -75,284 | -146 | 39% | -4,753 | -75,739 | 0 of 4 | -44,682 / -30,602 |
| `r23-vol30` | (c) closes at 0.30 ATR | 1,104 | -82,123 | -159 | 44% | -3,520 | -83,524 | 0 of 4 | -57,580 / -24,543 |
| `r23-vol40` | (c) at 0.40 ATR | 1,036 | -64,186 | -125 | 45% | -4,164 | -66,629 | 0 of 4 | -53,016 / -11,170 |
| `r23-vol50` | (c) at 0.50 ATR | 972 | -50,673 | -98 | 46% | -4,297 | -52,661 | 1 of 4 | -23,635 / -27,038 |
| `r23-va60-5m` | (d) first-hour value area, 5-min | 1,255 | -75,333 | -146 | 46% | -6,250 | -75,333 | 0 of 4 | -53,933 / -21,400 |
| `r23-va60-15m` | (d) value area, 15-min | 1,174 | -41,670 | -81 | 46% | -3,216 | -49,277 | 0 of 4 | -27,152 / -14,518 |
| `r23-pd-or45` | (e) 45-min range + yesterday's high / low | 562 | -18,082 | -35 | 45% | -3,203 | -29,172 | 2 of 4 | -1,636 / -16,446 |
| `r23-gap-or30` | (e) 30-min range + previous close | 938 | -67,239 | -131 | 41% | -4,295 | -68,539 | 0 of 4 | -46,676 / -20,563 |
| `r23-orb-f1` | (f) ORB + f1 | 497 | -12,454 | -24 | 46% | -3,226 | -21,366 | 2 of 4 | -11,070 / -1,384 |
| `r23-orb-f2` | (f) ORB + f2 | 349 | -12,461 | -24 | 48% | -3,084 | -18,197 | 2 of 4 | -8,071 / -4,389 |
| `r23-r22-f1` | (f) R22 range + f1 | 472 | **+15,593** | +30 | 44% | -2,996 | -21,645 | -3,666 / -4,059 / +11,579 / +11,739 | +1,166 / +14,427 |
| `r23-r22-f2` | (f) R22 range + f2 | 390 | **+16,158** | +31 | 45% | -2,996 | -24,489 | -953 / -3,653 / +10,601 / +10,164 | +3,737 / +12,421 |

Per-block numbers for every row: `results/release.md`.

What the table says:
- **(a) Window length.** Every window from 15 to 75 minutes loses. Shorter windows (15 / 30 min) trade more and lose
  most. The first hour (Rs -38,205) is the least bad on 5-minute breaks. It is still worse than R22.
- **(g) The break candle.** A 15-minute close loses less than a 5-minute one for the same window:
  - 15 min: -19k vs -72k;
  - 30 min: -39k vs -86k;
  - 60 min: -33k vs -38k;
  - 75 min: -49k vs -82k.

  R20c and R22 saw the same. A 30-minute close is not better again.
- **(c) Volatility-sized ranges** lose (Rs -50k to -82k). Closing the range at a fixed share of ATR does not help.
- **(d) The value area** of the first hour loses more than the plain first-hour range on 5-minute breaks.
- **(e) Yesterday's high / low.** Breaks beyond both the range and yesterday's high / low halve the trades and lose less
  (Rs -18,082). That is about R22's number, not better. Adding the previous close (the gap) loses more.
- **(f) The momentum filter** cuts trades by about half on both bases. On R22's range it turns a loss into a small
  profit, but only in the last two blocks (24 Mar 2025 - 13 Apr 2026).

### T2. Finalists (pre-stated rule) and their random twins on the release (descriptive; 1,000 twins each)

Rule: rank the configurations that beat today's ORB by:
- positive blocks;
- then the worst block;
- then the total.

| id | flag | random-level twin: mean / p | the range mirrored on EVERY day | random-time twin: mean / p | 2x spread ticks | big winners / cut short / Rs left |
|---|---|---|---|---|---|---|
| today's ORB | baseline | n/a | n/a | -15,122 / 0.92 | -48,911 | 457 / 416 / 21.8 lakh |
| `r22-brk15` | baseline | -17,705 / 0.50 | -17,731 | -5,144 / 0.73 | -22,333 | 430 / 373 / 19.7 lakh |
| 1. `r23-r22-f2` | carried, not promising | **+21,275 / 0.72** | **+26,318** | +5,455 / 0.22 | +15,218 | 215 / 178 / 9.9 lakh |
| 2. `r23-r22-f1` | carried, not promising | **+17,897 / 0.59** | **+20,408** | +11,130 / 0.39 | +14,388 | 250 / 208 / 11.3 lakh |
| 3. `r23-orb-f2` | carried, not promising | -5,333 / 0.81 | +1,788 | -409 / 0.83 | -13,616 | 203 / 180 / 9.8 lakh |

What this means:
- The mirrored ranges, with the same filter, made more than the real ranges. The edge is the filter ("price is already
  running away from its morning average"), not where the range is drawn.
- Big winners cut short: as in R22, 83-91% of trades whose option later doubled the target were sold first by the
  +40 target or the ladder. This is a hindsight ceiling, not money a rule could have kept: R22's 1x-extension target
  lost far more.

### T3. Nested walk-forward of the selection itself (pick on the earlier blocks, trade the next block)

| test block | picked (top 3 on the earlier blocks) | top-1 on the test block | top-3 mean | today's ORB | `r22-brk15` | all 22 mean | configurations > 0 |
|---|---|---|---|---|---|---|---|
| 2 | pd-or45, orb-f2, orb-f1 | -11,284 | -10,809 | -26,791 | -18,042 | -23,121 | 0 of 22 |
| 3 | orb-f2, pd-or45, r22-f2 | +2,764 | +6,805 | +9,753 | +12,154 | -464 | 12 of 22 |
| 4 | orb-f2, pd-or45, orb-f1 | -9,271 | -13,415 | -25,549 | -2,646 | -12,627 | 2 of 22 |
| **blocks 2-4** | | **-17,791** | | -42,587 | **-8,535** | -36,212 | |

The selection rule beats today's ORB out of sample, as any rule that trades less would. It loses to simply taking R22's
plain 15-minute range. The configuration that looks best changes from block to block. This is what a search over noise
looks like.

### T4. Multiple testing

BH at q 0.10 over m = 80 p-values:
- the 58 holdout p-values of R20 / R20b / R20c / R22;
- R23's 22 release t-tests.

Rejected: none. The smallest q is 0.32 (R20c `piv5n2` G5). R23's best is `r23-r22-f2` (p 0.17, q 1.00). Every R23 p is
in-sample, so this can only confirm "nothing". The lake read adds the finalists' 9 fresh p-values (m = 89).

## Step 3 summary: BANKNIFTY vs NIFTY, and other indices

- NIFTY carried both positive configurations: +14,427 / +12,421, against BANKNIFTY +1,166 / +3,737. On 15 of the 18
  plain ranges BANKNIFTY lost more than NIFTY (not on `t45-5m`, `vol50`, `pd-or45`). This matches R20c ("NIFTY +0.036 ATR, BANKNIFTY about 0").
- FINNIFTY: anatomy only, and it agrees with the other two (A1, A2). MIDCPNIFTY: not in the release. Both have options
  in the lake.

## Step 4: the fresh lake holdout (NOT run: the lake is not published)

Frozen in `results/finalists.json` (part C, commit a9a21ed):
- the finalists `r23-r22-f2`, `r23-r22-f1`, `r23-orb-f2`;
- the filter cuts 0.21982 / 0.03538;
- the baselines (today's ORB, `r22-brk15`);
- the 22 release p-values for BH.

The one read:

```
python research/data/fetch_release.py --dest ~/.cache/iraalgo/lake      # once the data-20261012 release (or the Drive copy) exists
R23_SOURCE=lake python3 research/hunt/r23/r23anatomy.py                  # optional, descriptive: Step 1 on the lake's design years
R23_SOURCE=lake python3 research/hunt/r23/r23trades.py lake              # ONCE -> results/lake/lake_holdout.json
```

How the read works:
- Lake design = up to 13 Apr 2026, in 4 blocks. Embargo = 14-26 Apr 2026. Holdout = 27 Apr 2026 onwards. It needs at
  least 100 sessions per index.
- It refuses a second read (flag file), and refuses if `finalists.json` is not committed unchanged.
- Gates, all required for a finalist to pass:
  - L1: holdout net > 0 after costs;
  - L2: beats today's ORB on the holdout;
  - L3 / L4: random-level / random-time twins;
  - L5: BH q < 0.10 on its t-test, L3 and L4 over m = 89;
  - L6: at least 3 of 4 lake design blocks positive.
- Caveat: the lake's BANKNIFTY options are monthly after Nov 2024 (weekly contracts ended). The release's "nearest
  expiry after the day" rule then trades the monthly. The adapter must be checked on the lake before the read; that
  check is part of the lake session's own work.

`lake()` writes BANKNIFTY and NIFTY only; FINNIFTY / MIDCPNIFTY rows and the 13 Oct forward example would need a short add-on after the read (descriptive only, no gate).

**The 13 Oct 2026 session** cannot be replayed here. Its minutes are in the private data repository, not in this
study's data. It is a forward example only and was not used to choose anything. R20 noted that on 13 Oct BANKNIFTY stayed
inside today's ORB range until 14:00, so today's ORB would not have traded it.

## If a finalist passes (paper only; do NOT build from this study)

Only if the lake read says PASS for it. The parent session decides. CLAUDE.md applies: paper only, logged in the
Autopilot change timeline, undoable in one tap, never live by itself.

For `r23-r22-f2` (the others differ only as noted):
- **Range**: the high and low of the 1-minute bars 09:15-09:59 (the three 15-minute candles). Unlike today's ORB, the
  10:00 bar is NOT in it.
- **Signal**: the first 15-minute candle (10:00, 10:15, ..., 13:45; it must close by 14:00) that closes above the high
  (call) or below the low (put). Only the first signal of each side per day counts.
- **Filter** at that close; both must hold, otherwise that side is not traded that day:
  - `side x (close - mean of the 1-minute closes from 09:15 to now) / ATR14 > 0.21982`;
  - `side x (close - broken edge) / ATR14 > 0.03538`.

  ATR14 = the mean true range of the 14 sessions before today (ShadowRules.atr14).
- **Trade**: the ORB arm's own contract, entry and exits:
  - ATM of the 09:20 close, held all day;
  - next minute's open;
  - -40 / +40 premium points (NIFTY -20 / +20), the profit-lock ladder, out at 15:10. Every tested trade used the expiry AFTER the day; trading today's contract on expiry day (15:05 square-off) is untested.
- `r23-r22-f1`: the first filter only. `r23-orb-f2`: today's ORB range (09:15-10:04) and a 5-minute close (10:05-13:55)
  with both filters.

## Limits (stated, not fixed)

- Two years of release data, all of it seen before. The finalists, the filter features and the cut values were all chosen
  on it. Only the lake can test them.
- Options are the release's nearest expiry AFTER the day. On expiry days the rules trade next week's contract; the app
  trades today's.
- The index has no volume, so "VWAP" is a time-weighted average of the 1-minute closes (TWAP). Option volume / OI cover
  only the ~20 strikes in the release.
- The FAKE label and `brk_str` are linked by construction (A3 note). The trading test is the honest check, and its
  positive results sit in the momentum part that mirrored ranges share.
- The (f) feature choice used all the release data. The nested walk-forward refits only the cut values, not the choice
  of feature, so even its numbers are slightly optimistic.

## Tests and checks

- `python -m pytest research/hunt/r23/test_r23.py -q` (98 tests). They cover:
  - ranges on hand-made days, the value area, windows, breaks, every feature group and the outcome classes;
  - the 22 configurations, the filter, the selection rule and block splits;
  - lake refusals;
  - the look-ahead guards: 40 random days each, where every minute after the range end, or after the decision, is
    replaced by noise and nothing may change.
- Experiment log: 23 new entries (22 configurations + the anatomy); the two baselines are already logged (R20's `r20` ORB entries and `r22-brk15`).
- Engine checks: R22's numbers reproduce exactly; the generic engine equals `r22-brk15` trade for trade.
