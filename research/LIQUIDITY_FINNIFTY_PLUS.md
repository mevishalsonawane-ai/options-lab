## Liquidity 15+5 "plus" on FINNIFTY (research/liquidity_finnifty_plus.py)

Research only; baseline files untouched. BUYING options only (ATM CE on an upside break, ATM PE on a downside one).
FINNIFTY lot 65, strike step 50, 0.5 slippage a side, Rs 40 a round trip.

Run: `python research/liquidity_finnifty_plus.py <scratch_dir> [out.md]` (about 1 minute; caches the upload's day
layout in the scratch dir).

### Verdict (short)

- Baseline reproduced exactly: index **+5.8 pts/trade (494 trades) in Year A, +3.6 (475) in Year B**; real options on
  the real-index upload days **-Rs 6,026 per lot over 83 trades** (15% stop).
- 36 variants in all: 28 on the index (24 single changes + 4 combos built on a training year) and 8 on real options only.
- Choose-on-A / test-on-B picked **5-min + 30-min books, pool confirm 15** (everything else baseline). It passes the
  rule on paper (positive both years, beats the baseline held out), **but the held-out gain is small**:
  Year B +4.1 vs +3.6 pts a trade, est. Rs +35.7k vs +34.2k per lot. On real options (real-index days) it made
  +Rs 4.0k over 59 trades vs the baseline's -Rs 6.0k over 83 (t 0.3: noise).
- Choose-on-B / test-on-A (10-min + 15-min books, lookback 10, skip days whose range so far is < 50% of the 20-day
  median range) **fails**: Year A est. Rs +61k vs the baseline's +73k.
- Nothing is a strong, clean improvement. The one thing that held up in both years as a single change is **replacing
  the 15-min book with a 30-min book** (5+30: +8.2 / +4.8 pts a trade, t 2.65 / 2.37, vs 5.8 / 3.6); the 30-min book on
  its own made +24 / +20 pts a trade over ~53 trades a year. Pool confirm 15 helped Year A only.
- Premium stop: 15% (baseline) was the least bad of none/10/15/20/25% on the real days; profit locks and 1 strike ITM
  did not show a usable edge (ITM also loses trades where the ITM strike was missing from the upload).

Calibration on the baseline's 83 real-option trades (real-index days): Rs per lot = -48 + 33.6 x index pts (b/lot = 0.52, an effective delta).
Expiry days (approx. calendar) in the index data: 57.

### Stage 1: index, 24 single changes (Year A 251 days, Year B 252 days)

Estimated Rs = calibration above applied per trade (an ESTIMATE, not option prices).

| setting | A trades | A pts/trade (t) | A est. Rs | B trades | B pts/trade (t) | B est. Rs |
|---|---|---|---|---|---|---|
| baseline | 494 | +5.8 (1.98) | +73,326 | 475 | +3.6 (1.78) | +34,193 |
| books=(5,) | 375 | +6.0 (1.98) | +57,144 | 365 | +2.5 (1.29) | +13,252 |
| books=(15,) | 119 | +5.5 (0.70) | +16,182 | 110 | +7.1 (1.23) | +20,941 |
| books=(3, 15) | 673 | +1.7 (0.89) | +5,316 | 646 | +2.4 (1.69) | +21,035 |
| books=(5, 30) | 427 | +8.2 (2.65) | +97,267 | 420 | +4.8 (2.37) | +48,174 |
| books=(10, 15) | 300 | +4.7 (1.09) | +32,767 | 282 | +7.8 (2.44) | +60,787 |
| L=10 | 722 | +2.0 (0.93) | +13,546 | 707 | +4.1 (2.66) | +63,937 |
| L=15 | 591 | +3.8 (1.52) | +46,860 | 575 | +3.1 (1.76) | +32,139 |
| L=30 | 347 | +4.3 (1.30) | +33,399 | 359 | +4.9 (2.04) | +41,899 |
| conf=5 | 539 | +5.6 (2.13) | +75,975 | 525 | +3.7 (2.11) | +39,993 |
| conf=15 | 459 | +7.9 (2.51) | +100,476 | 440 | +3.6 (1.70) | +31,416 |
| win=(5, 255) | 408 | +6.9 (1.95) | +74,708 | 417 | +3.0 (1.34) | +21,626 |
| win=(20, 315) | 386 | +2.9 (1.09) | +18,754 | 354 | +4.2 (2.02) | +33,307 |
| win=(45, 315) | 339 | +1.1 (0.38) | -4,159 | 300 | +5.6 (2.46) | +42,526 |
| trend=ema | 494 | +5.8 (1.98) | +73,326 | 475 | +3.6 (1.78) | +34,193 |
| trend=open | 408 | +6.3 (1.84) | +67,455 | 405 | +4.2 (1.91) | +37,076 |
| trend=twap | 465 | +3.1 (1.23) | +26,279 | 453 | +4.1 (2.00) | +40,070 |
| vol=0.3 | 460 | +6.5 (2.08) | +78,682 | 463 | +4.0 (1.96) | +39,641 |
| vol=0.5 | 380 | +4.7 (1.36) | +42,182 | 367 | +6.1 (2.63) | +57,539 |
| maxday=1 | 277 | +9.5 (2.15) | +74,869 | 269 | +3.8 (1.33) | +21,033 |
| maxday=2 | 404 | +7.0 (2.19) | +76,297 | 388 | +2.2 (1.03) | +10,258 |
| noexp=True | 408 | +2.0 (0.86) | +8,158 | 458 | +3.6 (1.78) | +34,166 |
| be=20 | 494 | +3.4 (1.31) | +32,971 | 475 | +1.9 (1.22) | +7,193 |
| be=40 | 494 | +3.4 (1.24) | +32,207 | 475 | +2.7 (1.54) | +20,828 |

Combos built on each training year: [books=(5, 30), conf=15] A +109,274 / B +35,697; [books=(5, 30), conf=15, vol=0.3] A +108,673 / B +37,352; [books=(10, 15), L=10] A +49,736 / B +58,881; [books=(10, 15), L=10, vol=0.5] A +61,373 / B +75,891
Total index variants run: 28 (incl. baseline).

### Out of sample

**Chosen on Year A: books=(5, 30), conf=15** -> held-out Year B:

| setting | trades | win | index pts/trade | t (pts) | est. Rs per trade | t (est. Rs) | net est. Rs (1 lot) | green months | max DD |
|---|---|---|---|---|---|---|---|---|---|
| baseline, Year A | 494 (2.0/day) | 58% | +5.8 | 1.98 | +148 | 1.50 | +73,326 | 9/13 | 20,111 |
| chosen, Year A (in-sample) | 404 (1.6/day) | 60% | +9.5 | 2.91 | +270 | 2.47 | +109,274 | 9/13 | 17,421 |
| baseline, Year B | 475 (1.9/day) | 59% | +3.6 | 1.78 | +72 | 1.07 | +34,193 | 8/13 | 25,082 |
| **chosen, Year B (held out)** | 396 (1.6/day) | 59% | +4.1 | 2.02 | +90 | 1.32 | +35,697 | 10/13 | 20,844 |

Passes (positive both years, beats baseline held out): YES

**Chosen on Year B: books=(10, 15), L=10, vol=0.5** -> held-out Year A:

| setting | trades | win | index pts/trade | t (pts) | est. Rs per trade | t (est. Rs) | net est. Rs (1 lot) | green months | max DD |
|---|---|---|---|---|---|---|---|---|---|
| baseline, Year B | 475 (1.9/day) | 59% | +3.6 | 1.78 | +72 | 1.07 | +34,193 | 8/13 | 25,082 |
| chosen, Year B (in-sample) | 340 (1.3/day) | 64% | +8.1 | 2.77 | +223 | 2.28 | +75,891 | 9/13 | 26,028 |
| baseline, Year A | 494 (2.0/day) | 58% | +5.8 | 1.98 | +148 | 1.50 | +73,326 | 9/13 | 20,111 |
| **chosen, Year A (held out)** | 351 (1.4/day) | 59% | +6.6 | 1.73 | +175 | 1.36 | +61,373 | 7/13 | 31,142 |

Passes (positive both years, beats baseline held out): NO

Cost sensitivity (est. Rs with the intercept forced to Rs -150 a trade, same slope):

| setting | Year A est. Rs | Year B est. Rs |
|---|---|---|
| baseline | +22,887 | -14,306 |
| chosen on A: books=(5, 30), conf=15 | +68,024 | -4,736 |
| chosen on B: books=(10, 15), L=10, vol=0.5 | +25,535 | +41,176 |
| books=(5, 30) alone | +53,669 | +5,291 |

### Stage 2: real option prices (upload days with the real index)

39 real-index chain days (2024-10-14 .. 2025-10-27); March 2026 (18 days, synthetic index) shown apart.

| setting | trades | win | index pts/trade | t (pts) | Rs per trade | t (Rs) | net Rs (1 lot) | green months | max DD |
|---|---|---|---|---|---|---|---|---|---|
| baseline (15% stop) | 83 (2.1/day) | 40% | -0.7 | -0.16 | -73 | -0.44 | -6,026 | 1/4 | 20,366 | A +8,486 (11) / B -14,512 (72)
| chosen on Year A: books=(5, 30), conf=15 | 59 (1.5/day) | 36% | +3.8 | 0.65 | +68 | 0.32 | +4,014 | 2/4 | 12,771 | A +9,688 (7) / B -5,674 (52)
| chosen on Year B: books=(10, 15), L=10, vol=0.5 | 64 (1.6/day) | 50% | +3.8 | 0.56 | +158 | 0.61 | +10,124 | 3/4 | 17,355 | A +7,664 (9) / B +2,461 (55)
| books=(5, 30) alone (index single change, not the protocol pick) | 67 (1.7/day) | 39% | +2.7 | 0.51 | +7 | 0.04 | +467 | 2/4 | 14,975 | A +9,558 (7) / B -9,091 (60)
| premium stop None | 83 (2.1/day) | 40% | -3.1 | -0.60 | -130 | -0.74 | -10,823 | 1/4 | 24,461 | A +4,391 (11) / B -15,214 (72)
| premium stop 0.1 | 83 (2.1/day) | 37% | -1.4 | -0.32 | -127 | -0.77 | -10,540 | 1/4 | 20,371 | A +5,054 (11) / B -15,594 (72)
| premium stop 0.2 | 83 (2.1/day) | 40% | -1.4 | -0.28 | -89 | -0.53 | -7,398 | 1/4 | 21,348 | A +7,504 (11) / B -14,902 (72)
| premium stop 0.25 | 83 (2.1/day) | 40% | -2.5 | -0.48 | -116 | -0.66 | -9,608 | 1/4 | 22,798 | A +6,054 (11) / B -15,662 (72)
| 1 strike ITM | 66 (1.7/day) | 44% | +3.4 | 0.66 | +63 | 0.30 | +4,185 | 1/3 | 11,594 | A +9,230 (11) / B -5,045 (55)
| profit lock: after +30% lock +10% | 83 (2.1/day) | 40% | -1.4 | -0.30 | -95 | -0.58 | -7,875 | 1/4 | 20,366 | A +6,637 (11) / B -14,512 (72)
| profit lock: after +50% lock breakeven | 83 (2.1/day) | 40% | -0.7 | -0.16 | -73 | -0.44 | -6,026 | 1/4 | 20,366 | A +8,486 (11) / B -14,512 (72)

March 2026 only (synthetic index; unreliable):

| setting | trades | win | index pts/trade | t (pts) | Rs per trade | t (Rs) | net Rs (1 lot) | green months | max DD |
|---|---|---|---|---|---|---|---|---|---|
| baseline (15% stop) | 21 (1.2/day) | 48% | +32.9 | 1.23 | +1,429 | 1.41 | +29,999 | 1/1 | 3,016 |
| chosen on Year A: books=(5, 30), conf=15 | 20 (1.1/day) | 55% | +5.3 | 0.11 | +2,005 | 1.65 | +40,108 | 1/1 | 13,678 |
| chosen on Year B: books=(10, 15), L=10, vol=0.5 | 21 (1.2/day) | 52% | +19.4 | 1.30 | +646 | 1.49 | +13,567 | 1/1 | 3,461 |
| books=(5, 30) alone (index single change, not the protocol pick) | 19 (1.1/day) | 53% | +10.5 | 0.22 | +2,272 | 1.85 | +43,167 | 1/1 | 10,558 |
| premium stop None | 21 (1.2/day) | 48% | +32.9 | 1.23 | +1,429 | 1.41 | +29,999 | 1/1 | 3,016 |
| premium stop 0.1 | 21 (1.2/day) | 48% | +32.0 | 1.19 | +1,261 | 1.20 | +26,476 | 1/1 | 5,503 |
| premium stop 0.2 | 21 (1.2/day) | 48% | +32.9 | 1.23 | +1,429 | 1.41 | +29,999 | 1/1 | 3,016 |
| premium stop 0.25 | 21 (1.2/day) | 48% | +32.9 | 1.23 | +1,429 | 1.41 | +29,999 | 1/1 | 3,016 |
| 1 strike ITM | 10 (0.6/day) | 60% | -1.7 | -0.10 | +456 | 0.57 | +4,556 | 1/1 | 5,439 |
| profit lock: after +30% lock +10% | 21 (1.2/day) | 52% | +39.0 | 1.48 | +1,575 | 1.57 | +33,067 | 1/1 | 3,016 |
| profit lock: after +50% lock breakeven | 21 (1.2/day) | 48% | +32.9 | 1.23 | +1,429 | 1.41 | +29,999 | 1/1 | 3,016 |

Option-only variants: 7. Exits (baseline, real days): next liquidity 51%, failed break 41%, premium stop 6%, 15:10 2%
### How the variants were chosen and scored

- Index data: finnifty_index.parquet (2024-02-12 .. 2026-02-24), Year A < 2025-02-15 (251 days), Year B from
  2025-02-15 (252 days), each year simulated on its own chart (as liquidity_indices.py does, which is why the baseline
  matches exactly).
- The score is an ESTIMATED rupee P&L per lot: Rs = -48 + 33.6 x index pts per trade, an OLS fit on the baseline's 83
  real-option trades on real-index days (slope / lot = 0.52, i.e. an ATM delta; the intercept is the per-trade cost
  and decay). The intercept is noisy (83 trades), so the "cost sensitivity" table repeats the key rows with -Rs 150 a
  trade: the baseline then loses in Year B and the Year-A pick still loses a little in Year B (-4.7k).
  Cross-check against BANKNIFTY (LIQUIDITY_STOP.md, 15% stop, lot 30): +9.3 pts -> Rs +112 a trade (Year A),
  +8.1 -> Rs +40 (Year B); with the same delta that is an intercept of about -Rs 33 / -86 at lot 30, the same order.
- Single changes tried: books 5 / 15 / 3+15 / 5+30 / 10+15; swing lookback 10/15/30; pool confirm 5/15; entries until
  13:30, from 09:35, from 10:00; trend filter (EMA20 slope of the chart, side of the day's open, side of the day's TWAP
  - the index has no volume, so TWAP stands in for VWAP); a range filter (day's range so far >= 30% / 50% of the 20-day
  median day range); max 1 / 2 trades a day per book; skip expiry days (approximate FINNIFTY calendar: weekly
  Tuesdays to Nov 2024, then monthly); breakeven stop on the index after +20 / +40 pts.
- Combos: on each training year the top single changes (one per knob, up to 3) that beat the baseline were stacked
  (top 2, top 3); the pick is the best single-or-combo on the training year's est. Rs.
- Option-only knobs (premium stop none/10/20/25%, 1 strike ITM, two profit locks) can only be tested on the upload's
  39 real-index days (11 trades in Year A, ~72 in Year B for the baseline), too few for an A/B selection; reported as-is.

### Notes and caveats

- EMA-slope filter = baseline exactly: the breaking close already turns the EMA the trade's way, so it never filters.
- Skipping expiry days cut Year A from est. +73k to +8k: the 2024 weekly-expiry Tuesdays carried much of Year A.
  Do not add an expiry-day skip.
- Breakeven stops on the index (after +20 / +40 pts) hurt in both years: winners need room.
- Real-option days are only 4 calendar months, all weak for this rule (the baseline loses there); every Rs figure on
  them has t < 1. The rupee numbers for the 2 years are ESTIMATES from the calibration, not option prices.
- March 2026 (synthetic index from put-call parity) is shown apart and not used: the rebuilt chart moves signals a lot
  (see LIQUIDITY_FINNIFTY.md); e.g. the Year-A pick's index pts there fall from +32.9 (baseline) to +5.3 while its Rs rise.
- A 30-min book has only ~1 trade every 5 days; its per-trade edge rests on ~53 trades a year.

### Settings for the app arm (if the lead wants to try the Year-A pick)

Liquidity "both" rule unchanged (pool break where a same-side active swing zone overlaps), swing lookback 20, area
full, pools 2 contacts / gap 5 / **confirm 15**, failed-break stop, 15% premium stop, entries 09:20-14:30, exits at next
liquidity / new liquidity / failed break / 15% stop / 15:10; **two books: 5-min and 30-min** (one position each; the
30-min book replaces the 15-min one). ATM strike (50 apart), 1 lot of 65. Lower-risk alternative: keep confirm 10 and
only swap 15-min for 30-min (better than baseline in both years on the index; not the protocol's pick).
