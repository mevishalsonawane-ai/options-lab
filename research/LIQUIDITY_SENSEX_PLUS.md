# Liquidity 15+5 on SENSEX: enhancements with a Year A / Year B hold-out (research/liquidity_sensex_plus.py)

**Every rupee figure for SENSEX below is an ESTIMATE.** There is no SENSEX option price history: the simulation records SENSEX index points only and the option P&L comes from a model calibrated on BANKNIFTY real ATM option trades. 1 lot = 20, ATM strike (100 apart), 0.5 slippage a side, Rs 40 a round trip.

Year A = 2024-02-12 .. 2025-02-14 (251 days); Year B = 2025-02-17 .. 2026-02-24 (252 days).

## 1. Option model (calibrated once, on BANKNIFTY)

Same rules (both tools, lookback 20, confirm 10, failed-break stop, 15+5 books, no premium stop) run on the BANKNIFTY files with real ATM option minute prices (lot 30, strike step 100). Least squares on the 497 trades taken in a weekly-contract regime (<= 7 days to expiry; SENSEX has weekly options throughout) out of 1040:

- premium change (pts) = **+5.45** + **0.638** x index pts in our favour - **0.184** x minutes held (R^2 0.83); on all 1040 trades (incl. monthly contracts): +3.22 / 0.597 / 0.133.
- The constant C: real fills beat delta x points by, on average, +2.8 pts for holds (-1, 5], +9.6 pts for holds (5, 30], +6.0 pts for holds (30, 400] minutes - mostly on the quick trades (option minute prices lag the index at a break). It may not carry over to SENSEX options (thinner, wider spreads), so a **strict** model with C = 0 is also shown (refitted: delta 0.635, theta 0.145 pts/min on BANKNIFTY).
- BANKNIFTY median ATM premium paid (weekly regime) 352; median daily range 537 pts.
- SENSEX median daily range 665 pts -> scale factor 1.24: SENSEX theta **0.228 pts/min**, C **+6.74** pts (strict: theta 0.180), typical ATM premium **436** pts (so the 15% stop = 65 premium pts, about 103 index pts against us before theta).
- Break-even per trade: (Rs 40 / 20 + 2 x 0.5 - C) / delta = **-5.9 index pts** in our favour plus theta / delta for the time held (strict model: 4.7 pts plus theta).
- Premium stop: approximated, NOT simulated: the modelled premium (delta x adverse index move on the minute's low/high - theta so far, C not included) touching X% of the typical premium below the price paid. Profit locks use the same model.

Model vs real on BANKNIFTY (same trades, the model's own calibration data, so an optimistic check; year B is mostly monthly contracts after Nov 2024, where the weekly theta overstates the decay):

| BANKNIFTY year | trades | index pts/trade | real Rs, no prem. stop | model Rs (strict), no prem. stop | real Rs, 15% stop | modelled Rs (strict), modelled 15% stop | stop hits real / modelled |
|---|---|---|---|---|---|---|---|
| A | 495 | +8.1 | +58,396 | +55,362 (-11,693) | +55,574 | +86,639 (+13,717) | 18% / 24% |
| B | 545 | +9.1 | +31,710 | +77,426 (+2,246) | +21,637 | +46,281 (-33,319) | 6% / 14% |

## 2. Baseline reproduced

Index points, arm rule without a premium stop (as in LIQUIDITY_INDICES.md): Year A +4.5 pts a trade (510 trades), Year B -0.4 (473 trades). Estimated option P&L without the stop: Year A Rs +9,348, Year B Rs -19,638 (ESTIMATE).

### Year A

| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |
|---|---|---|---|---|---|---|---|---|---|
| baseline, no premium stop | 510 (2.0/day) | 54% | +4.5 (t 0.69) | +18 | 0.23 | +9,348 | 6/13 | -41,167 | -47,346 |
| baseline (15% modelled stop), 15-min book only | 135 (0.5/day) | 50% | +5.3 (t 0.46) | -20 | -0.14 | -2,712 | 6/13 | -15,920 | -17,620 |
| baseline (15% modelled stop), 5-min book only | 375 (1.5/day) | 50% | +5.9 (t 0.88) | +72 | 0.86 | +26,993 | 7/13 | -16,486 | -19,725 |
| **baseline: 15+5, 15% modelled stop** | 510 (2.0/day) | 50% | +5.7 (t 0.99) | +48 | 0.66 | +24,280 | 7/13 | -27,239 | -37,345 |

Exits: next liquidity 45%, premium stop 25%, failed break 25%, 15:10 3%, new liquidity 2%; median hold 4 min; mean hold 18 min (theta ~ 4.1 premium pts a trade).

### Year B

| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |
|---|---|---|---|---|---|---|---|---|---|
| baseline, no premium stop | 473 (1.9/day) | 59% | -0.4 (t -0.07) | -42 | -0.58 | -19,638 | 5/13 | -42,170 | -72,289 |
| baseline (15% modelled stop), 15-min book only | 109 (0.4/day) | 50% | +2.0 (t 0.15) | -75 | -0.46 | -8,199 | 4/13 | -25,678 | -20,672 |
| baseline (15% modelled stop), 5-min book only | 364 (1.4/day) | 58% | +0.6 (t 0.15) | +5 | 0.10 | +1,928 | 9/13 | -22,407 | -42,406 |
| **baseline: 15+5, 15% modelled stop** | 473 (1.9/day) | 56% | +1.0 (t 0.21) | -13 | -0.23 | -6,271 | 6/13 | -31,944 | -63,078 |

Exits: next liquidity 51%, failed break 23%, premium stop 20%, new liquidity 5%, 15:10 2%; median hold 4 min; mean hold 19 min (theta ~ 4.4 premium pts a trade).

## 3. Variants (one change at a time from the baseline, then a greedy combination)

Variants evaluated: **29** (1 baseline + 26 single changes + 2 combinations). Each is run on both years; the choice is made on one year only.

### Year A (all rupee figures ESTIMATES)

| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |
|---|---|---|---|---|---|---|---|---|---|
| **BASELINE (arm rule, modelled 15% stop)** | 510 (2.0/day) | 50% | +5.7 (t 0.99) | +48 | 0.66 | +24,280 | 7/13 | -27,239 | -37,345 |
| books 15 only | 135 (0.5/day) | 50% | +5.3 (t 0.46) | -20 | -0.14 | -2,712 | 6/13 | -15,920 | -17,620 |
| books 5 only | 375 (1.5/day) | 50% | +5.9 (t 0.88) | +72 | 0.86 | +26,993 | 7/13 | -16,486 | -19,725 |
| books 30+15 | 193 (0.8/day) | 47% | -1.3 (t -0.14) | -121 | -1.03 | -23,447 | 5/13 | -32,720 | -44,214 |
| books 10+5 | 563 (2.2/day) | 51% | +8.1 (t 1.43) | +84 | 1.20 | +47,211 | 8/13 | -25,292 | -21,259 |
| books 15+3 | 637 (2.5/day) | 51% | +3.6 (t 0.94) | +47 | 0.99 | +29,986 | 7/13 | -26,820 | -48,398 |
| swing lookback 10 | 721 (2.9/day) | 51% | +6.5 (t 1.41) | +51 | 0.90 | +36,739 | 7/13 | -30,187 | -50,209 |
| swing lookback 15 | 603 (2.4/day) | 49% | +6.5 (t 1.22) | +48 | 0.72 | +28,963 | 7/13 | -34,799 | -43,939 |
| swing lookback 30 | 376 (1.5/day) | 51% | +16.9 (t 2.35) | +172 | 1.95 | +64,584 | 9/13 | -23,035 | +20,171 |
| pool confirm 5 | 543 (2.2/day) | 49% | +2.9 (t 0.52) | +17 | 0.25 | +9,433 | 6/13 | -30,806 | -56,689 |
| pool confirm 15 | 462 (1.8/day) | 53% | +12.8 (t 1.92) | +124 | 1.52 | +57,447 | 7/13 | -21,988 | +2,254 |
| no failed-break stop | 509 (2.0/day) | 57% | +5.0 (t 0.82) | +4 | 0.05 | +2,029 | 5/13 | -34,880 | -57,873 |
| premium stop none (modelled) | 510 (2.0/day) | 54% | +4.5 (t 0.69) | +18 | 0.23 | +9,348 | 6/13 | -41,167 | -47,346 |
| premium stop 10% (modelled) | 510 (2.0/day) | 46% | +7.3 (t 1.32) | +66 | 0.97 | +33,827 | 8/13 | -22,445 | -29,274 |
| premium stop 20% (modelled) | 510 (2.0/day) | 52% | +2.7 (t 0.44) | +12 | 0.16 | +6,114 | 7/13 | -30,081 | -54,310 |
| premium stop 25% (modelled) | 510 (2.0/day) | 53% | +3.9 (t 0.63) | +19 | 0.24 | +9,504 | 7/13 | -33,079 | -50,588 |
| profit lock: +20% -> exit at cost | 510 (2.0/day) | 51% | +2.1 (t 0.39) | +7 | 0.11 | +3,629 | 6/13 | -29,038 | -59,763 |
| profit lock: +30% -> keep +10% | 510 (2.0/day) | 51% | +0.6 (t 0.12) | -15 | -0.24 | -7,678 | 7/13 | -27,479 | -70,097 |
| entries 09:30-14:30 | 442 (1.8/day) | 51% | +4.4 (t 0.80) | +35 | 0.52 | +15,610 | 7/13 | -23,236 | -37,553 |
| entries 09:20-13:30 | 419 (1.7/day) | 50% | +4.8 (t 0.72) | +30 | 0.36 | +12,388 | 5/13 | -24,002 | -37,962 |
| entries 10:15-14:30 | 346 (1.4/day) | 52% | +6.9 (t 1.13) | +80 | 1.04 | +27,521 | 7/13 | -19,546 | -14,576 |
| trend: with the day's TWAP side | 477 (1.9/day) | 49% | +6.7 (t 1.09) | +59 | 0.77 | +28,042 | 6/13 | -25,130 | -29,569 |
| trend: 20-bar EMA slope | 506 (2.0/day) | 50% | +5.8 (t 0.99) | +48 | 0.67 | +24,508 | 7/13 | -26,005 | -36,579 |
| vol: 1st-hour range >= 0.8x 20d median (from 10:15) | 236 (0.9/day) | 54% | +14.5 (t 1.72) | +173 | 1.65 | +40,750 | 9/12 | -16,898 | +12,064 |
| vol: 1st-hour range >= 1.0x 20d median (from 10:15) | 177 (0.7/day) | 55% | +18.2 (t 1.70) | +213 | 1.60 | +37,698 | 10/12 | -13,007 | +16,282 |
| skip expiry day | 398 (1.6/day) | 51% | +3.7 (t 0.57) | +23 | 0.28 | +8,978 | 7/13 | -26,620 | -38,745 |
| max 2 entries / book / day | 416 (1.7/day) | 49% | +1.6 (t 0.24) | -6 | -0.07 | -2,427 | 7/13 | -26,799 | -52,861 |
| **COMBO chosen on Year A: swing lookback 30 + pool confirm 15** | 351 (1.4/day) | 54% | +23.8 (t 3.03) | +244 | 2.57 | +85,796 | 9/13 | -21,198 | +45,014 |
| **COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15)** | 335 (1.3/day) | 50% | +7.9 (t 1.41) | +107 | 1.55 | +35,890 | 7/13 | -17,574 | -5,527 |

### Year B (all rupee figures ESTIMATES)

| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |
|---|---|---|---|---|---|---|---|---|---|
| **BASELINE (arm rule, modelled 15% stop)** | 473 (1.9/day) | 56% | +1.0 (t 0.21) | -13 | -0.23 | -6,271 | 6/13 | -31,944 | -63,078 |
| books 15 only | 109 (0.4/day) | 50% | +2.0 (t 0.15) | -75 | -0.46 | -8,199 | 4/13 | -25,678 | -20,672 |
| books 5 only | 364 (1.4/day) | 58% | +0.6 (t 0.15) | +5 | 0.10 | +1,928 | 9/13 | -22,407 | -42,406 |
| books 30+15 | 156 (0.6/day) | 50% | +8.5 (t 0.66) | -22 | -0.15 | -3,457 | 4/13 | -26,021 | -20,245 |
| books 10+5 | 533 (2.1/day) | 57% | +2.2 (t 0.51) | +3 | 0.05 | +1,408 | 7/13 | -33,597 | -62,554 |
| books 15+3 | 615 (2.4/day) | 51% | +1.9 (t 0.53) | +28 | 0.65 | +17,272 | 6/13 | -29,048 | -59,149 |
| swing lookback 10 | 695 (2.8/day) | 57% | +0.4 (t 0.13) | -10 | -0.24 | -6,761 | 6/13 | -42,272 | -91,231 |
| swing lookback 15 | 562 (2.2/day) | 55% | +1.1 (t 0.25) | -18 | -0.34 | -9,835 | 7/13 | -35,635 | -76,669 |
| swing lookback 30 | 374 (1.5/day) | 56% | +1.5 (t 0.27) | -8 | -0.12 | -3,162 | 6/13 | -31,321 | -47,590 |
| pool confirm 5 | 514 (2.0/day) | 54% | +0.6 (t 0.13) | -17 | -0.31 | -8,601 | 3/13 | -34,674 | -70,355 |
| pool confirm 15 | 443 (1.8/day) | 54% | -0.2 (t -0.04) | -35 | -0.58 | -15,451 | 7/13 | -36,348 | -68,194 |
| no failed-break stop | 473 (1.9/day) | 63% | +2.5 (t 0.50) | -35 | -0.57 | -16,404 | 5/13 | -35,209 | -71,813 |
| premium stop none (modelled) | 473 (1.9/day) | 59% | -0.4 (t -0.07) | -42 | -0.58 | -19,638 | 5/13 | -42,170 | -72,289 |
| premium stop 10% (modelled) | 473 (1.9/day) | 53% | +1.8 (t 0.41) | +4 | 0.07 | +1,826 | 7/13 | -26,446 | -56,302 |
| premium stop 20% (modelled) | 473 (1.9/day) | 58% | +1.6 (t 0.34) | -8 | -0.13 | -3,705 | 7/13 | -34,666 | -59,448 |
| premium stop 25% (modelled) | 473 (1.9/day) | 58% | +0.4 (t 0.08) | -26 | -0.42 | -12,423 | 7/13 | -33,934 | -67,130 |
| profit lock: +20% -> exit at cost | 473 (1.9/day) | 58% | +2.2 (t 0.48) | +5 | 0.10 | +2,556 | 6/13 | -27,402 | -54,861 |
| profit lock: +30% -> keep +10% | 473 (1.9/day) | 57% | +0.0 (t 0.00) | -20 | -0.37 | -9,414 | 5/13 | -28,454 | -67,325 |
| entries 09:30-14:30 | 395 (1.6/day) | 57% | +2.1 (t 0.44) | +8 | 0.13 | +2,987 | 6/13 | -26,701 | -44,821 |
| entries 09:20-13:30 | 402 (1.6/day) | 55% | +0.4 (t 0.08) | -27 | -0.43 | -11,046 | 5/13 | -31,695 | -58,886 |
| entries 10:15-14:30 | 294 (1.2/day) | 60% | +4.3 (t 1.01) | +55 | 1.02 | +16,068 | 6/13 | -12,698 | -20,194 |
| trend: with the day's TWAP side | 432 (1.7/day) | 57% | +2.4 (t 0.48) | +7 | 0.12 | +2,957 | 6/13 | -27,162 | -48,901 |
| trend: 20-bar EMA slope | 469 (1.9/day) | 56% | +1.2 (t 0.24) | -11 | -0.20 | -5,347 | 6/13 | -32,504 | -61,618 |
| vol: 1st-hour range >= 0.8x 20d median (from 10:15) | 221 (0.9/day) | 59% | +5.2 (t 0.94) | +62 | 0.91 | +13,779 | 7/13 | -13,298 | -13,501 |
| vol: 1st-hour range >= 1.0x 20d median (from 10:15) | 139 (0.6/day) | 58% | +5.0 (t 0.70) | +57 | 0.65 | +7,871 | 5/12 | -7,502 | -9,283 |
| skip expiry day | 364 (1.4/day) | 57% | +4.9 (t 0.85) | +39 | 0.56 | +14,162 | 6/13 | -26,087 | -29,523 |
| max 2 entries / book / day | 383 (1.5/day) | 56% | +2.0 (t 0.37) | -6 | -0.09 | -2,221 | 7/13 | -28,267 | -48,145 |
| **COMBO chosen on Year A: swing lookback 30 + pool confirm 15** | 352 (1.4/day) | 55% | +1.1 (t 0.18) | -21 | -0.29 | -7,347 | 6/13 | -33,365 | -48,836 |
| **COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15)** | 333 (1.3/day) | 52% | +4.7 (t 1.04) | +74 | 1.31 | +24,660 | 7/13 | -21,473 | -17,564 |

## 4. Hold-out check

Selection rule: highest estimated net Rs on the fitting year (at least 100 trades). A setting is *recommended* only if it is positive after (estimated) costs in BOTH years and beats the baseline in the held-out year.

| chosen on | setting | fit year net (est.) | held-out year net (est.) | baseline held-out (est.) | t held-out | both years > 0 | beats baseline held-out |
|---|---|---|---|---|---|---|---|
| Year A | COMBO chosen on Year A: swing lookback 30 + pool confirm 15 | +85,796 | -7,347 | -6,271 | -0.29 | no | no |
| Year B | COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15) | +24,660 | +35,890 | +24,280 | 1.55 | yes | yes |

Settings positive (estimated) in both years: books 5 only, books 10+5, books 15+3, premium stop 10% (modelled), profit lock: +20% -> exit at cost, entries 09:30-14:30, entries 10:15-14:30, trend: with the day's TWAP side, vol: 1st-hour range >= 0.8x 20d median (from 10:15), vol: 1st-hour range >= 1.0x 20d median (from 10:15), skip expiry day, COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15).

### Sensitivity of the chosen settings (not used for selection)

The modelled 15% stop flatters the BANKNIFTY check (section 1), so each chosen setting is also shown with no premium stop, and the strict model (C = 0) is in the last column.

| variant | trades | win (est.) | index pts/trade (t) | est. Rs/trade | t (est. Rs) | est. net Rs / lot / yr | green months (est.) | est. max DD | strict model net (C = 0) |
|---|---|---|---|---|---|---|---|---|---|
| COMBO chosen on Year A: swing lookback 30 + pool confirm 15, NO premium stop, Year A | 351 (1.4/day) | 58% | +26.5 (t 3.13) | +269 | 2.61 | +94,433 | 8/13 | -21,053 | +57,150 |
| COMBO chosen on Year A: swing lookback 30 + pool confirm 15, NO premium stop, Year B | 352 (1.4/day) | 58% | +2.6 (t 0.36) | -20 | -0.23 | -7,116 | 6/13 | -31,821 | -45,140 |
| COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15), NO premium stop, Year A | 335 (1.3/day) | 54% | +16.9 (t 2.67) | +214 | 2.72 | +71,635 | 9/13 | -19,956 | +31,457 |
| COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15), NO premium stop, Year B | 333 (1.3/day) | 53% | +6.1 (t 1.26) | +97 | 1.65 | +32,350 | 7/13 | -24,814 | -8,822 |
| baseline, NO premium stop, Year A | 510 (2.0/day) | 54% | +4.5 (t 0.69) | +18 | 0.23 | +9,348 | 6/13 | -41,167 | -47,346 |
| baseline, NO premium stop, Year B | 473 (1.9/day) | 59% | -0.4 (t -0.07) | -42 | -0.58 | -19,638 | 5/13 | -42,170 | -72,289 |

## 5. Verdict

Passes the hold-out rule on the central estimate: COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15).

- COMBO chosen on Year B: books 15+3 + entries 10:15-14:30 + vol: 1st-hour range >= 0.8x 20d median (from 10:15): Year A 335 trades (1.3/day), est. Rs +107 a trade, t 1.55, est. net Rs +35,890; Year B 333 trades (1.3/day), est. Rs +74, t 1.31, est. net Rs +24,660. Strict model (C = 0): Rs -5,527 / -17,564.

## 6. Reading it (caveats)

- **All SENSEX rupee figures are estimates.** The simulation uses SENSEX index minutes; option P&L = the BANKNIFTY-calibrated model above. The central model includes the constant C, which is what makes it match BANKNIFTY's real P&L (Year A real Rs +58,396 vs model +55,362); without C (strict column) almost every SENSEX setting is negative. Whether SENSEX option fills behave like BANKNIFTY's is the single biggest unknown.
- The premium stop is approximated from the index. On BANKNIFTY the modelled 15% stop looks better than the real one (Year A modelled Rs +86,639 vs real +55,574), so gains from tighter modelled stops (e.g. the 10% variant) are not trustworthy; the chosen settings are therefore also shown with no premium stop.
- The selection is unstable: the setting chosen on Year A (slower levels: swing 30, pool confirm 15) collapsed in Year B, while the setting chosen on Year B held up in Year A. One direction out of two passing, with t-stats below 2 on the estimate, is weak evidence.
- The ingredients that help in both years are the ones that cut trading in the quiet part of the day: no entries before 10:15, and trading only when the first hour moved at least ~0.8x its usual range; the 3-minute book replacing the 5-minute book adds trades with similar edge. The 15-minute book on its own and the 30-minute book lose on the estimate.
- SENSEX expiry days are computed from the calendar (Friday to 2024-12-31, Tuesday 2025-01-01 .. 2025-08-31, Thursday after; previous trading day on a holiday), not from exchange data. SENSEX has no volume in this file, so the 'VWAP' filter is a TWAP of the typical price.
- Strike choice (1 ITM) could not be tested: there are no SENSEX option prices and the model only has an ATM delta.

Intermediate trade lists: scratchpad sensex_plus_trades.parquet; calibration: sensex_plus_cal.json.