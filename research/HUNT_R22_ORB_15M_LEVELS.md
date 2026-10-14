# R22: the morning's three 15-minute candles as support and resistance through the day

Written 13 Oct 2026 for Boss: "Test the morning's three 15-minute candles, the high and the low of that, then make support
and resistance from there and check the ORB." Research only, no app code.

Pre-registration `research/hunt/r22/PREREG.md`: part A (levels, event-study protocol, trading framework, gates, twins,
BH family) committed in **19a8660** before any number was computed; part B (the 6 rules, picked from the design event
study) committed in **7dee09e** before any trading test; the design table was frozen and the experiment entries logged in
**9b27602** before the one holdout read. Code: `research/hunt/r22/` (`r22levels.py`, `r22events.py`, `r22study.py`,
`r22trades.py`, tests `test_r22.py`). Tables: `results/events_design.md|json`, `events_holdout.md|json`, `design.json`,
`holdout.json`.

## Verdict for Boss (read this first)

1. **The morning range's high and low do NOT act as real support and resistance later in the day.** On the design
   period (BANKNIFTY, NIFTY, FINNIFTY; 1,121 sessions) a touch of the high or low was turned back (3 candles closing
   inside) **29%** of the time (347 of 1,211 touches). Lines at the same distance from the 10:00 price, but in the wrong
   place (the "mirror twin"), were turned back just as often: **29%** (358 of 1,256). On the holdout the real edges did
   worse than the twin (22% vs 30%, 371 touches each).
2. **They break more often than they hold.** The other 71% of touches were not turned back. After a 5-minute close
   beyond, the break held (3 closes beyond) 63% of the time (688 of 1,099; twin 57%). But holding did not mean going
   on. From the hold, price was further on at 15:29 only **47%** of the time, and the average move to the close was
   -0.008 x ATR14 (about -10 points).
3. **Break-and-retest is not better than a plain break.** After a held break, price came back within a tenth of the range
   82% of the time, and the retest candle closed beyond the edge 78% of the time (442 events). From there price went on to
   the close only **45%** of the time (-0.020 ATR, about -15 points). A plain break gave 49% (+0.007 ATR). In money, the
   retest rule lost **Rs 20,547** in design (295 trades) and **Rs 6,095** in the holdout (100 trades).
4. **The 1x range extension is reached after 31% of breaks** (17% before price closes back inside). As a target it lost
   heavily: **-Rs 1,17,565** in design and **-Rs 53,564** in the holdout. The -40 stop is hit long before the index gets
   there.
5. **No rule passes.** 0 of 6. Every rule lost money in the holdout, and nothing survives Benjamini-Hochberg over the 58
   p-values (R22's 18 plus R20's, R20b's and R20c's 40). The best q is 0.23, and that belongs to an older R20c test; R22's
   own best q is 1.00. **One small plus:** the 15-minute close break of the 09:15-09:59 range (`r22-brk15`) lost less
   than today's ORB on the same sessions. Design: Rs -8,573 vs -15,603. Holdout: Rs -8,882 vs -29,117. Its two width
   filters and the mid stop also lost less than the ORB in both periods. All of them still lose after costs, and the
   mirrored "wrong" levels made more than the real ones in the holdout (`r22-brk15` Rs +16,040). **No app change is
   proposed**, paper or live. The ORB stays paused.
6. **Would the 6-year lake change it?** It could only firm up the "no". Every number here is close to zero, and the
   real levels track their random twins in both periods. The one split that looked useful (range width) flipped sign
   from design to holdout. Re-run with `R22_SOURCE=lake` once the lake is published (commands below).

Plain answer: the high and low of the first three 15-minute candles are not special lines. Price touches them, sometimes
bounces and sometimes breaks, but no more often than it does at any line the same distance away. Waiting for a 15-minute
close, a retest, a mid stop or an extension target does not turn the ORB into a winner after costs.

## Data

| source | what | span | used for |
|---|---|---|---|
| `research-data` release via `research/hunt/r17/data.py` | BANKNIFTY and NIFTY 1-minute index + options of the nearest expiry after the day (minute OHLC, volume) | BANKNIFTY 13 Feb 2024 - 23 Feb 2026 (498 sessions), NIFTY 1 Apr 2024 - 13 Apr 2026 (499) | events, trades |
| release `finnifty_index.parquet` | FINNIFTY 1-minute index (no options) | 12 Feb 2024 - 24 Feb 2026 | events only |

MIDCPNIFTY is not in the release (skipped). Design sessions (after 15 for ATR14): BANKNIFTY 361 (5 Mar 2024 - 22 Aug
2025), NIFTY 364 (24 Apr 2024 - 10 Oct 2025), FINNIFTY 396. Holdout: BANKNIFTY 122 (from 25 Aug 2025), NIFTY 120 (from 13
Oct 2025), FINNIFTY 92. Trading tests: BANKNIFTY + NIFTY, 395 design and 153 holdout calendar days. Median ATR14 in
design: BANKNIFTY 661, NIFTY 249, FINNIFTY 292 points. The median range of the three 15-minute candles is 0.45-0.49 x ATR14
(about 320 BANKNIFTY / 110 NIFTY points).

## Method (short; the full rules are in PREREG.md)

- **Levels**, from the minutes 09:15-09:59 only (the 15-minute candles 09:15, 09:30, 09:45): OH / OL (range high / low),
  OM (mid), each candle's own high and low (the "inner" ones not equal to OH / OL), extensions OH + 0.5R / + 1R and OL -
  0.5R / - 1R. Unlike the ORB arm, the 10:00 5-minute bar is NOT in the range.
- **Events** on 5-minute candles from 10:00 to 15:00 (breaks also on 15-minute candles), the first of each kind per edge
  and day: touch, REJECT (the touch candle and the next 2 close inside), BREAK (first close beyond), HOLD (3 closes
  beyond), FAIL (back inside within 2 candles), RETEST-HOLD / -FAIL (after a hold, the first candle back within 0.10R of
  the edge closes beyond / inside), extension reach, mid and inner-level rejects.
- **Measured** from the decision candle's close: moves after 15 / 30 / 60 minutes and to 15:29 in ATR14 (and points),
  best / worst in 60 minutes, and "excess" over the same-minute average move of the index's design sessions. **Mirror twin**: every level mirrored around
  the 10:00 price (twin OH = 2 x P10 - OL), so each twin edge sits at the other real edge's distance.
- **Look-ahead guard** (`test_r22.py`): the levels are identical when every minute from 10:00 on is replaced by noise.
  Every event and trading signal decided by a cut minute is identical when every later minute is noise (60 random days
  and cuts).
- **Trading**: ORB contract (ATM of the 09:20 bar's close, held all day; next expiry; entry at the next minute's open;
  print within 3 minutes; premium > 40 / 20) and ORB exits (-40 / +40 premium, NIFTY -20 / +20, profit-lock ladder,
  15:10) unless the rule changes them. 1 trade per side per index per day. Costs: R17's (paper fills +-5 bps plus 1-4 ticks
  half-spread by volume, stops x2; app charges at today's rates; the lot in force). Baseline = today's ORB (R20's V0),
  which reproduces R20's BANKNIFTY design result exactly (337 trades, -Rs 14,772).

## Step 1: event study (design period; holdout after the one read)

### T1. Shares at the OR edges (5-minute candles; both edges, all 3 indices)

| | real (design) | mirror twin (design) | real (holdout) | twin (holdout) |
|---|---|---|---|---|
| touches of OH / OL | 1,211 | 1,256 | 371 | 371 |
| rejected (3 closes inside) | 347 = 29% | 358 = 29% | 83 = 22% | 111 = 30% |
| 5-min breaks | 1,099 | 1,109 | 342 | 340 |
| held (3 closes beyond) | 688 = 63% | 635 = 57% | 202 = 59% | 213 = 63% |
| held breaks retested within 0.10R | 565 = 82% | 521 = 82% | 175 = 87% | 183 = 86% |
| retest closed beyond (RETEST-HOLD) | 442 = 78% | 391 = 75% | 143 = 82% | 147 = 80% |
| 0.5x / 1x extension reached by 15:29 after a break | 61% / 31% | 56% / 33% | 61% / 30% | 58% / 29% |
| ... before a close back inside | 35% / 17% | 31% / 17% | 31% / 15% | 33% / 15% |
| 15-min breaks / held | 1,030 / 61% | 992 / 56% | 323 / 60% | 313 / 62% |
| mid: touches / rejected | 803 / 28% | 786 / 28% | 239 / 28% | 241 / 26% |
| inner candle levels: touches / rejected | 3,252 / 27% | 3,267 / 29% | 1,010 / 26% | 1,013 / 30% |

### T2. Moves after each event (ATR14; to the close also as "further on" share; design / holdout)

| event | design n | mv30 | to close | further on at 15:29 | twin to close | holdout n | to close | further on |
|---|---|---|---|---|---|---|---|---|
| REJECT (back into the range) | 347 | -0.000 | +0.019 | 52% | -0.009 | 83 | +0.027 | 46% |
| BREAK 5-min | 1,099 | +0.007 | +0.007 | 49% | -0.012 | 342 | +0.000 | 55% |
| BREAK 15-min | 1,030 | +0.000 | +0.001 | 49% | +0.001 | 323 | -0.001 | 55% |
| HOLD 5-min | 688 | -0.001 | -0.008 | 47% | +0.000 | 202 | +0.010 | 57% |
| HOLD 15-min | 626 | +0.005 | -0.001 | 49% | +0.000 | 194 | -0.001 | 57% |
| FAIL 5-min (back inside) | 406 | -0.016 | -0.025 | 48% | +0.023 | 135 | +0.023 | 51% |
| RETEST-HOLD | 442 | -0.006 | -0.020 | 45% | -0.001 | 143 | +0.027 | 57% |
| RETEST-FAIL | 123 | -0.024 | -0.062 | 50% | +0.021 | 32 | -0.060 | 44% |
| MID-REJECT | 223 | +0.003 | +0.003 | 52% | -0.019 | 68 | -0.047 | 47% |
| INNER-REJECT | 882 | -0.014 | -0.026 | 47% | -0.004 | 266 | +0.049 | 56% |

0.01 ATR14 is about 7 BANKNIFTY or 2.5 NIFTY points. No event kind moves more than a few points on average, the sign
changes between design and holdout for most, and the twins sit in the same band. The best 60-minute excursion after any
event (+0.13 to +0.18 ATR) is about the same size as the worst (-0.14 to -0.21).

### T3. Splits (design, real levels; 5-minute BREAK / HOLD / RETEST-HOLD, move to the close in ATR14, n)

| split | BREAK | HOLD | RETEST-HOLD |
|---|---|---|---|
| narrow range (W < 0.407) | -0.020 (391) | -0.056 (237) | -0.082 (153) |
| normal (0.407-0.563) | -0.012 (379) | -0.016 (251) | -0.035 (153) |
| wide (W >= 0.563) | +0.060 (329) | +0.058 (200) | +0.068 (136) |
| holdout narrow / wide | +0.045 (98) / -0.050 (111) | +0.043 (54) / -0.059 (71) | +0.039 (38) / -0.006 (52) |
| BANKNIFTY / NIFTY / FINNIFTY | -0.010 / +0.020 / +0.010 (355 / 367 / 377) | -0.049 / +0.025 / -0.003 (222 / 233 / 233) | -0.051 / -0.008 / +0.001 (150 / 153 / 139) |
| gap down / flat / up (+-0.15 ATR) | +0.007 / +0.011 / +0.002 (285 / 432 / 382) | -0.024 / +0.007 / -0.013 | -0.037 / -0.021 / -0.006 |
| VIX < 13 / 13-16 / >= 16 | +0.055 / -0.022 / +0.016 (307 / 573 / 219) | +0.055 / -0.048 / +0.002 | +0.065 / -0.061 / -0.043 |
| expiry day / other | -0.016 (114) / +0.009 (608) | +0.016 (81) / -0.017 (374) | +0.055 (56) / -0.048 (247) |
| 10:00-11:00 / 11:00-12:30 / 12:30-15:00 | +0.019 / -0.018 / -0.005 (690 / 267 / 142) | -0.002 / -0.033 / +0.004 | -0.005 / -0.058 / +0.010 |

The width split was the only clear one in design (wide days carried, narrow days faded), and it **reversed in the
holdout**. Low VIX (< 13) looked better for breaks, holds and retests, which R20c also saw; it is not tested as a rule here (R20c's
low-VIX rule failed its holdout). Full tables, with t-values and the twins for every split: `results/events_design.md`.

## Step 2: the six rules and today's ORB (BANKNIFTY + NIFTY, 1 lot, after costs)

### T4. Design (395 calendar days) and holdout (153), Rs

| rule | period | trades | trades / day | net | per day | green trade days | worst day | max drawdown | BANKNIFTY / NIFTY | 4 design blocks |
|---|---|---|---|---|---|---|---|---|---|---|
| today's ORB (V0) | design | 685 | 1.73 | -15,603 | -40 | 48% | -4,295 | -37,236 | -14,772 / -831 | +851 / -10,341 / -19,509 / +13,395 |
| | holdout | 238 | 1.56 | -29,117 | -190 | 33% | -3,226 | -29,605 | -10,965 / -18,152 | |
| `r22-brk15` (15-min close break) | design | 665 | 1.68 | -8,573 | -22 | 46% | -4,564 | -36,906 | -17,344 / +8,770 | -4,745 / +2,019 / -29,618 / +23,770 |
| | holdout | 229 | 1.50 | -8,882 | -58 | 42% | -2,918 | -14,877 | -1,553 / -7,329 | |
| `r22-retest` (break-and-retest, stop back inside) | design | 295 | 0.75 | -20,547 | -52 | 36% | -2,986 | -23,058 | -8,903 / -11,644 | -2,925 / -6,501 / -7,669 / -3,453 |
| | holdout | 100 | 0.65 | -6,095 | -40 | 33% | -2,776 | -10,188 | -243 / -5,853 | |
| `r22-brk15-x1` (target 1x extension) | design | 665 | 1.68 | -1,17,565 | -298 | 29% | -5,958 | -1,33,358 | -1,11,579 / -5,986 | -7,872 / -27,861 / -60,827 / -21,006 |
| | holdout | 229 | 1.50 | -53,564 | -350 | 29% | -6,279 | -69,430 | -40,863 / -12,702 | |
| `r22-brk15-mid` (stop at the OR mid) | design | 665 | 1.68 | +21,472 | +54 | 55% | -9,973 | -31,075 | -7,015 / +28,487 | +3,445 / -7,198 / -9,364 / +34,590 |
| | holdout | 229 | 1.50 | -27,859 | -182 | 49% | -5,697 | -37,519 | -2,264 / -25,594 | |
| `r22-brk15-nowide` (skip wide days, as asked) | design | 465 | 1.18 | -7,785 | -20 | 45% | -4,564 | -31,448 | -8,605 / +820 | -1,457 / -3,742 / -18,387 / +15,801 |
| | holdout | 152 | 0.99 | -7,529 | -49 | 39% | -2,918 | -11,674 | -2,217 / -5,312 | |
| `r22-brk15-nonarrow` (skip narrow days) | design | 417 | 1.06 | -2,751 | -7 | 40% | -3,717 | -22,513 | -8,899 / +6,149 | -3,225 / +3,999 / -18,344 / +14,820 |
| | holdout | 163 | 1.07 | -9,915 | -65 | 39% | -2,480 | -12,422 | -1,800 / -8,114 | |

### T5. Gates (holdout p-values, BH q over the 58-test family)

| rule | G1 design > 0 | G2 >= 3 of 4 blocks | G3 t-test p (q) | G4 random-level twin: mean, p (q) | G5 random-time twin: mean, p (q) | G6 2x ticks | G7 beats ORB both periods | PASS |
|---|---|---|---|---|---|---|---|---|
| `r22-brk15` | no | no (2) | 0.75 (1.00) | +3,380, 0.92 (1.00) | +1,547, 0.81 (1.00) | -10,095 | yes | no |
| `r22-retest` | no | no (0) | 0.79 (1.00) | -9,445, 0.26 (1.00) | -6,054, 0.52 (1.00) | -6,814 | no | no |
| `r22-brk15-x1` | no | no (0) | 0.93 (1.00) | -33,287, 0.84 (1.00) | +70,392, 1.00 (1.00) | -54,779 | no | no |
| `r22-brk15-mid` | yes | no (2) | 0.90 (1.00) | -4,184, 0.96 (1.00) | -4,030, 0.95 (1.00) | -29,050 | yes (holdout by Rs 1,258) | no |
| `r22-brk15-nowide` | no | no (1) | 0.77 (1.00) | -5,542, 0.63 (1.00) | +2,402, 0.86 (1.00) | -8,130 | yes | no |
| `r22-brk15-nonarrow` | no | no (2) | 0.81 (1.00) | +2,487, 0.94 (1.00) | +953, 0.86 (1.00) | -10,876 | yes | no |

Lowest q in the whole family: 0.232 (R20c `piv5n2` G5); nothing rejected. With the levels mirrored on EVERY holdout
day, `r22-brk15` would have made +16,040 and `r22-brk15-mid` +20,164: the real levels did worse than wrong ones.
The x1 rule's random-time twin is far above it because the twin keeps the day's side and holds to 15:10 with no premium
target (it carries the day's direction; PREREG says this twin is hard to beat).

### T6. Big winners cut short (option high after entry >= entry + 2 x target, but sold below entry + target)

| rule | design: big / cut short / Rs left between exit and that high | holdout |
|---|---|---|
| today's ORB | 345 / 308 / 15.2 lakh | 112 / 108 / 6.6 lakh |
| `r22-brk15` | 323 / 276 / 13.7 lakh | 107 / 97 / 6.0 lakh |
| `r22-retest` | 122 / 114 / 5.2 lakh | 52 / 46 / 2.8 lakh |
| `r22-brk15-x1` | 323 / 201 / 11.2 lakh | 107 / 61 / 4.1 lakh |
| `r22-brk15-mid` | 323 / 269 / 12.7 lakh | 107 / 94 / 5.7 lakh |

Read with care: "the high after entry" includes highs reached after a stop, often on the other side of a reversal, so
it is a hindsight ceiling and not money that a rule could have kept. Half the ORB-type trades saw their option reach
+80 / +40 points at some time before 15:10, but the +40 target, the ladder or the stop had sold almost all of them
first. Removing the cap (x1: hold for the 1x extension) cut fewer winners short (201 of 323) and lost far more money,
because the -40 stop was hit on 486 of 665 trades. Letting winners run does not fix this entry.

## The 13 Oct 2026 session (forward example only)

The release ends in Feb / Apr 2026, and the phone's 13 Oct recording is in the private data repository, not in this
study's data. So this study cannot replay 13 Oct. R20 noted that on 13 Oct BANKNIFTY stayed inside the ORB range
(09:15-10:00 5-minute bars) until 14:00 and only then broke out. The R22 range leaves out the 10:00 bar, so it can be
narrower and could have been broken earlier. Whether any R22 rule would have traded that day is therefore unknown here. It was not used to
choose anything.

## What this adds to R20, R20b and R20c

- R20 (adaptive range), R20b (levels as the range), R20c (2-close breaks; the opening range was among the weakest) and
  now R22 (the 15-minute range's edges, mid, candle extremes and extensions as support and resistance, with touches,
  holds, retests, extension targets and width filters) all reach the same answer: the morning range has no edge on
  these indices after costs, and random lines do as well.
- The only R22 result that agrees with earlier work: a 15-minute close is a calmer break signal than a 5-minute one.
  It loses less than today's ORB with about 3% fewer trades, but it still loses. The difference also falls inside the
  noise: the mirrored levels did better still.

## Re-run on the 6-year Dhan lake

```
R22_SOURCE=lake python3 research/hunt/r22/r22study.py events        # design event study -> results/lake/
R22_SOURCE=lake python3 research/hunt/r22/r22trades.py design       # rules on the lake's design years
R22_SOURCE=lake python3 research/hunt/r22/r22trades.py holdout      # once: the lake's last 12 months
```

The rules, gates and costs stay the same. The lake has its own holdout flag, and its holdout is each index's last 12
months. One caveat: that window overlaps the release's holdout. Treat a lake pass as a new pre-registered test, not as a
rescue of these results.

## Tests and checks

`python -m pytest research/hunt/r22/test_r22.py -q` (23 tests): levels on hand-made days, every event kind, the window
edge, the mirror twin, the 15-minute break, extension reach, the index stop / target exits, the ORB exits identical to
R17's, the baseline identical to R20's V0 trade for trade, refusals (no contract, no print), BH and the scores, and the
look-ahead guards.
