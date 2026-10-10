# HUNT h37: Fibonacci, Gann, harmonics, Elliott, Renko, Point & Figure, Market Profile and Wyckoff as option-BUYING triggers

Code: `research/hunt/h37/`. Logs and CSVs: `scratchpad/hunt/h37/`. Pre-registration: `research/hunt/h37/PREREG.md`,
written before any P&L. Everything here is option BUYING, 1 lot, 1-ITM nearest expiry.

## Verdict

**NO. None of these methods is a usable trigger for buying options. None works as a pre-registered filter on
Liquidity 15+5 either.**

- I tested **18,170 trigger variants** (8 methods, 42 signals, 5 indices, 5m / 15m / 60m / daily candles plus 1-minute
  Renko and P&F, all exits) and **864 filters**. Total: **19,034**.
- Before costs, the average trade **loses Rs 27**. After app charges and the real spread, it **loses Rs 139**.
- Against a random entry at the **same time of day** (same day, within 15 minutes, coin-flip side, same exit), the
  signals are better by only **Rs +5 a trade**. That is noise.
- **White's Reality Check p = 1.00. Hansen SPA p = 1.00** (0.998) over all 18,170 variants. Nothing beats doing nothing.
- **Walk-forward** (pick the best index / timeframe / exit on earlier years only): **0 of 35 families passed.**
- **3 variants passed the variant gate.** They were tiny: Rs +20 to +51 a day pre-holdout. In the locked holdout
  (1 Oct 2025 – 6 Oct 2026), **2 of 3 lost money**. The one that won made **Rs +16 a day**.
- **Top 10 by pre-holdout t (info only): 8 of 10 lost money in the holdout.**
- **Rs 5,000 a day with Rs 1 lakh: not reachable.** The best holdout result (+Rs 16/day on 1 NIFTY lot) would need
  about **310 lots**. Rs 1 lakh buys about **10**.
- **Stick with Liquidity 15+5 unfiltered** (h17 / h24).

| | gross / trade | net / trade (real spread) | net at 1.5x spread | random at same time | signal minus random |
|---|---|---|---|---|---|
| all 8.08 million pre-holdout trades | **−27** | **−139** | −160 | −143 | **+5** |

## What was tested (each coded objectively, no hindsight)

Swings: a ZigZag on bar highs / lows. Reversal = 2 or 4 × ATR14. A pivot counts only from the bar that confirms it.

| method | signals | candles |
|---|---|---|
| **Fibonacci** | bounce off 38.2 / 50 / 61.8 / 78.6% of the last swing (trade the swing); break of that level (trade the pullback). Extensions 127.2 / 161.8% as INDEX TARGETS (stop at the swing start) on the bounces | 5, 15, 60, D |
| **Gann square of 9** | levels (√ref ± j/4)², j = 1..4 (45°–180°), ref = today's open or previous close; break or bounce | 5, 15, 60 |
| **Gann 1x1 angle** | line from the last swing low / high at 0.25 or 0.5 × ATR per bar; first close through it | 5, 15, 60 |
| **Gann time cycles** | bars since the last pivot = 9, 18, … 90 or squares 4, 9, … 81; daily: 30 / 45 / 60 / 90 / 120 / 144 / 180 / 270 / 360 calendar days. Trade the reversal | 5, 15, 60, D |
| **Harmonics** | Gartley, Bat, Butterfly, Crab, ABCD (standard ratio bands, 5% tolerance); entry when price reaches D | 5, 15, 60, D |
| **Elliott** | wave 3 (break of wave-1 top after a 38–79% wave 2); wave-5 exhaustion (wave 5 = wave 1); wave C (break of A's low after a 38–89% B) | 5, 15, 60, D |
| **Renko** | reversal brick after 3+ bricks; breakout of the last 10 bricks. Box 0.1 / 0.2 / 0.3% or 0.5 / 1 × ATR(15m) | 1-min closes |
| **Point & Figure** | double-top / bottom and triple-top / bottom breakouts, 3-box reversal, same boxes | 1-min closes |
| **Market Profile (TPO)** | prior day's POC / value area (70%) from 30-minute TPOs; initial-balance break; 80% rule; open outside value and go; value-area break; value-area edge fade; POC cross | 5, 15 |
| **Volume profile** (check) | same levels from the rupee volume of NIFTY 50 / 12 bank constituents (from Oct 2024 only) | 5, 15 |
| **Wyckoff** | spring / upthrust: false break of a 20-bar range (≤ 4 ATR wide), reclaimed the same or next bar | 5, 15, 60, D |

- **Trade:** buy the 1-ITM nearest-expiry CE (bull) or PE (bear) at the next minute's open after the signal candle
  closes. Daily signals enter at 09:16 the next day. Expiry days skipped. One position at a time, at most 5 a day.
- **Costs:** app fills and app charges, plus the real half-spread on both legs: BANKNIFTY 0.16%, NIFTY 0.16%,
  MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20%. Stress: 1.5×.
- **Exits (fixed before results; all square off 15:10):** premium targets +15 / +20 / +25 / +30 points × stops
  −10 / −15 / −20 points (12); the Liquidity arm's exits; the profit-lock ladder (−40 / +40 points, R = 40); time stops
  15 / 30 / 60 minutes. Fibonacci bounces also got the two extension-target exits.
- **Engine check:** the lookup table matches a direct obuy engine run trade for trade (`check.py`: 4 of 4 identical).

### How exits were checked (Boss's question)

- Every premium target and stop is checked minute by minute on the option's own **1-minute HIGH and LOW**, also when
  the signal comes from 15-minute, 60-minute or daily candles.
- If the target and stop are both inside the same minute, the **stop is taken first**.
- Ties: **42,106 of 8.08 million trades (0.52%)**. By index, BANKNIFTY 1.0%, SENSEX 1.6%, NIFTY / FINNIFTY / MIDCP
  under 0.06%. They are most common on the tight +15 / −10 exit. Too few to change any verdict.

## The bar every method had to clear: random entries lose Rs 95–290 a trade

Pre-holdout, any minute, both sides, real spread:

| index | gross / trade (best–worst exit) | net / trade (best–worst exit) |
|---|---|---|
| NIFTY | −16 to −59 | −104 to −146 |
| BANKNIFTY | −19 to −46 | −123 to −150 |
| FINNIFTY | −19 to −65 | −165 to −211 |
| MIDCPNIFTY | −35 to −153 | −171 to −290 |
| SENSEX | −11 to −30 | −95 to −114 |

## Results by method (pre-holdout, all variants pooled)

"Edge" = net minus a random entry at the same time of day with the same exit. Rupees per trade, 1 lot.

| method | variants | trades | gross | net | edge vs random | share of variants with net > 0 |
|---|---|---|---|---|---|---|
| Elliott | 2,040 | 160,708 | +15 | −96 | +30 | 26% |
| Point & Figure | 850 | 100,194 | +10 | −111 | +10 | 19% |
| Market Profile (TPO + VP) | 1,360 | 382,675 | 0 | −112 | +28 | 9% |
| Fibonacci | 5,760 | 2,451,466 | −14 | −125 | +15 | 13% |
| Renko | 850 | 607,769 | −16 | −134 | +2 | 5% |
| Gann | 3,230 | 4,082,128 | −39 | −150 | −2 | 2% |
| Wyckoff | 680 | 187,607 | −52 | −160 | −11 | 12% |
| Harmonics | 3,400 | 107,151 | −80 | −191 | −52 | 18% |

What stands out (none of it is tradeable):
- **Gross** is near zero or negative for every method. The few with a small positive gross (Elliott wave 3, Fibonacci
  78.6% break, P&F, Market Profile "open outside value") lose it all and more to charges and spread.
- **Harmonic patterns are the worst**: Rs −52 a trade worse than random. Crab and Bat lose about Rs 120–136 more than
  random. Most harmonic setups are rare (a few per year per index).
- **Gann** square-of-9 levels, angles and time cycles are exactly as good as random minutes. Time cycles lost the most
  in the walk-forward (Rs −262k and −362k).
- **Fibonacci bounces** (the classic "buy the 61.8%") are slightly WORSE than random (−2 to −12 a trade). Breaks of the
  deeper levels do slightly better than random, but still lose money.
- **Extension targets (127.2 / 161.8%)** made Fibonacci bounces worse: Rs −182 and −193 a trade net.
- **Volume profile vs TPO:** the constituent-volume POC sits inside the TPO value area on 82–83% of days, and the value
  areas overlap about 75%. So the volume check mostly repeats the TPO levels. Its trades (Oct 2024 – Sep 2025 only,
  NIFTY / BANKNIFTY) lost Rs 16–301 a trade.

Timeframes did not help either. Per trade net: 5m −142, 15m −134, 60m −137, daily −150, 1-minute Renko / P&F −131.

## Gates

### Variant gate (net > 0 at 1.5× spread, BH q < 0.05 vs the time-matched random, > half the years positive, ≥ 100 trades)

3 of 18,170 passed. All three beat random mainly because random loses so much. Their own t-stats were 0.5–0.9.

| variant | pre trades | pre gross | pre net | pre Rs/day | holdout trades | holdout gross | holdout net | holdout Rs/day | holdout max DD |
|---|---|---|---|---|---|---|---|---|---|
| NIFTY 5m Fibonacci 78.6% break (k4), ladder exit | 997 | +114,353 | +25,024 | +20 | 199 | +25,494 | **+4,017** | **+16** | 23,255 |
| MIDCP 5m Elliott wave 3 (k2), +15/−20 | 574 | +100,755 | +19,565 | +28 | 294 | +61,254 | **−4,223** | −17 | 39,359 |
| MIDCP 5m Fibonacci 78.6% break (k4), +20/−20 | 497 | +106,829 | +35,201 | +51 | 264 | +41,708 | **−17,239** | −69 | 57,674 |

- The NIFTY winner: Rs +16 a day at 1 lot. At 1.5× spread it made only Rs 552 in the whole year. Its drawdown
  (Rs 23,255) is 6 times its yearly profit.
- Rs 5,000 a day would need about **310 NIFTY lots**. Rs 1 lakh carries about **10** (≈ Rs 10,000 premium a lot).

### Walk-forward family gate (net > 0, Holm p < 0.05, > half the test years positive)

**0 of 35 passed.** The best four families made money out of sample but failed Holm:

| family | test years positive | trades | net | Holm p |
|---|---|---|---|---|
| Market Profile: open outside value, go | 2 of 4 | 426 | +22,950 | 0.31 |
| Gann daily time cycle | 2 of 3 | 41 | +13,886 | 0.97 |
| Fibonacci 78.6% break | 3 of 4 | 258 | +12,861 | 1.00 |
| Elliott wave 3 | 2 of 4 | 698 | +11,454 (−9,169 at 1.5× spread) | 0.61 |

All the other 31 families lost money in the walk-forward.

### Top 10 by pre-holdout t (information only, nothing chosen from it)

Pre-holdout they made Rs +42 to +134 a day. **In the holdout, 8 of 10 lost money.** The two winners made Rs +1 and
Rs +10 a day (MIDCP 5m Elliott wave 3 with a 60-minute exit; SENSEX 1-minute P&F double-top with a 60-minute exit).

## As FILTERS on Liquidity 15+5

- **Base:** Liquidity 15+5 on 5 indices (h25's trade file), arm exits, app costs plus the real spread.
  Pre-holdout: 2,786 trades, Rs +68,590 (BANKNIFTY alone: 750 trades, Rs +60,697).
- **Filters:** keep only the trades the method agrees with, or drop the ones it opposes; bases all-5-pooled and
  BANKNIFTY alone. **864 filters, 0 passed** (lowest BH q = 0.003, but that filter kept only 15 trades and failed the
  other conditions).
- So nothing went to the holdout under the plan.

**POST-HOC, information only (one holdout look, not a result):** the three lowest-p filters with ≥ 100 kept trades.

| filter (pooled, keep if agrees) | pre: kept / all | pre: kept net vs base | holdout: kept / all | holdout: kept net vs base |
|---|---|---|---|---|
| 5m Fibonacci 61.8% break (k2) agrees with the trade in the last 15 min | 786 / 2,786 | +145,991 vs +68,590 | 260 / 922 | **+62,699 vs +60,982** |
| Renko 0.1% reversal agrees, last 15 min | 123 / 2,786 | +65,962 vs +68,590 | 37 / 922 | −10,809 vs +60,982 |
| Renko 0.5×ATR reversal agrees, last 15 min | 142 / 2,786 | +67,843 vs +68,590 | 44 / 922 | +52,898 vs +60,982 |

- The Fibonacci 61.8%-break filter kept 28% of the trades and the same profit, in both periods. Pre-holdout q = 0.13,
  so it **failed the pre-registered gate**. The holdout is now used up for it.
- If the Boss wants it, it needs a fresh forward test (paper trades from today) before anyone trusts it. It does not
  change the Rs 5,000/day answer: it cuts trades, it does not add profit.

## Honest count of tries

| what | count |
|---|---|
| Trigger variants (17 exits on every signal × params × index × timeframe, + 2 extension exits on Fibonacci bounces) | 18,170 |
| Filter variants | 864 |
| **Total variants** | **19,034** |
| Walk-forward families | 35 |
| Holdout looks, triggers | 13 (3 gate passers + top 10 info) |
| Holdout looks, filters | 0 planned; 3 POST-HOC info |

- Nothing was chosen from a holdout result.
- 42 signal names: 8 Fibonacci, 9 Gann (4 square-of-9, 2 angle, 3 time cycle), 5 harmonic, 3 Elliott, 2 Renko,
  2 P&F, 6 Market Profile (TPO), 5 volume profile, 2 Wyckoff. 35 of them had enough trades for a walk-forward pick.

## Limits

- The spreads come from one snapshot on one calm day (h24). The 1.5× stress is shown in the tables.
- Swing size, Gann angle scale, box sizes and ratio tolerances are each one or two fixed choices. Other choices exist.
  With 19,034 tries and RC/SPA p = 1.00, more choices would only add more noise.
- The filter look-back stays within the same day (a slight simplification of "the last 3 bars").
- The volume profile uses constituent stock volume from Oct 2024 only (the index itself has no volume).
- Daily signals enter at 09:16, not 09:15 (the outcome table starts with the 09:15 signal minute).
- MIDCP and FINNIFTY are thin (h10); any MIDCP result would also hit capacity limits.

## Files

- `research/hunt/h37/PREREG.md`: the plan, written before any P&L.
- `outcomes.py`: the outcome table (every minute, both sides, 17 exits, tie flags). Deleted after use (≈ 360 MB).
- `sigs.py`: all signals. `vp.py`: constituent volume. `vpcheck.py`: VP vs TPO agreement.
- `evaluate.py`: lookups, the time-matched random, SPA / RC. `fibx.py`: the extension-target exits (obuy engine).
- `analyze.py`: BH, gates, walk-forward. `final.py`: the holdout look and the random bar. `filt.py`: filters.
  `check.py`: lookup vs engine.
- Logs: `scratchpad/hunt/h37/summary.log`, `final.log`, `baseline.log`, `filt_screen.log`, `filt_hold.log`,
  `vpcheck.log`, `wf.csv`, `by_*.csv`.
