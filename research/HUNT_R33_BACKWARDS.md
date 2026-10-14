# R33: the backwards study - every indicator variant on the option and the index before zero-to-hero

Written 14 Oct 2026, for Boss: "check it backwards":
- find all the zero-to-hero options;
- add every indicator, pattern and strategy, in all variants (gamma, VWAP, OI, volume, vertical volume and everything
  else), all at once, and find a pattern;
- check the last 30 one-minute candles;
- do the same for the index at the same time;
- give the most occurring pattern and how to gain from it.

- **Pre-registration:** `research/hunt/r33/PREREG.md`, commit `59d5524`, pushed before any flag was computed.
- **Code, tests and tables:** `research/hunt/r33/` (`results/results.json` has every pattern's numbers). The flags and
  shapes are on the cache, not in git.
- **Experiment entries:** `research/experiments/entries/r33-*.yaml` (4 rules, all rejected, plus the frequency scan).
- **Research only:** nothing in the app changes.

## Verdict for Boss

**1. The zero-to-heroes.** 487 options at Rs 1-30 on NIFTY and SENSEX expiries (2021-2026) later reached a confirmed
10× before 15:05; 1,234 reached 5×. Each one is taken at its lowest point, the "zero".

**2-3. The option's own patterns and last 30 candles: the most occurring pattern is "the option has crashed and is
oversold".**

| pattern at the zero (option's own 1-minute bars) | heroes | ordinary options at the same minute and price | all rows |
|---|---|---|---|
| below its own VWAP | 98.6% | 92.5% | 93.5% |
| ADX(7) above 20 (a strong move, here a fall) | 94.5% | 89.5% | 90.1% |
| close in the bottom 20% of its 30-minute range | 94.0% | 52.9% | 51.0% |
| below its volume point of control ("vertical volume") | 94.0% | 63.2% | 69.5% |
| Stochastic(14) below 20 | 93.0% | 40.9% | 39.9% |
| down more than 30% in 30 minutes | 78.0% | 42.4% | 38.8% |
| RSI(7) below 30 | 76.6% | 22.2% | 21.6% |
| CCI below −100 | 74.9% | 29.7% | 29.9% |
| 2 red candles in a row | 66.3% | 25.7% | 23.2% |
| at a 10-minute low (Donchian) | 65.5% | 15.4% | 15.2% |

- **The 30-candle shape** tells the same story. The heroes' most common shapes are steady falls:
  - from 2.2× to the zero (29% of heroes, 12% of all rows);
  - from 3.3× (18% vs 5%);
  - from 5.2× (11% vs 1.4%).
  - The flat "already dead" shape is rare at a hero (10% vs 40%).
- **Momentum patterns are almost absent at the zero.**
  - Option RSI(7) rising: 12% vs 49%.
  - Option MACD rising: 21% vs 53%.
  - +DI above −DI: 11% vs 33%.
  - Bollinger squeeze: 11% vs 24%.

**4-6. The index at the same time.**
- The index is usually quiet and slightly against the option: above its EMA9 in the option's direction 18% vs 49%;
  making higher highs 11% vs 26%.
- The index's most common 30-minute shape is flat, at 33% of heroes vs 18% of all rows: a slight dip against the
  option, then level.
- The strongest index shape is a 0.5% move against the option that is flattening: 3.3% of heroes vs 1.0% of rows.
- **Chain / pricing flags:**
  - option cheap against the move (iv_rv ≤ 1): 33% vs 19%;
  - gamma exposure above its noon value: 77% vs 76%, so no information;
  - OI walls, PCR and VIX: about no difference.

**So the most occurring zero-to-hero picture is:** a cheap option that has just crashed 30-70% in minutes, oversold on
every oscillator, below its VWAP and volume point of control, while the index pauses after moving against it.

**How to gain from it: in this data, you can't.**
- **The pattern is real but not selective.** About 4 in 10 options at any minute look crashed and oversold, and nearly
  all of them keep dying.
- **The best forward rate any single pattern reaches is 2.4%,** for an option down 50% in 5 minutes, against 1.7% for
  any option. A hero is still about 1 in 40.
- **Part of the effect is hindsight.** Each hero is marked at its lowest point, so "crashed and oversold" looks stronger
  in the frequency table than it can be in real time.
- **The trading tests** (walk-forward 2023-26: chosen on earlier years, traded on later ones, real costs):

  | rule | trades | hit % | net Rs | random entries, same days | p |
  |---|---|---|---|---|---|
  | B1: the most occurring pattern (option below VWAP / its point of control), 25% trail | 365 | 11.5 | −1,34,282 | −90,228 | 0.98 |
  | B2: B1 held to 15:05 | 365 | 2.7 | −5,84,260 | −3,75,434 | 1.00 |
  | B3: the best mined 2-3-pattern combination, 25% trail | 127 | 18.9 | **−26,271** | −30,713 | 0.26 |
  | B4: B3 held to 15:05 | 127 | 7.1 | −86,111 | −99,447 | 0.33 |

  - The mined combinations hit 10× 7-10% of the time on the training years, but only 0-3.7% on the next year:
    - 2023: wall near, PCR rising, index EMA down-cross; 1.2% in test;
    - 2024: wall near, option cheap vs move, VIX rising; 3.7% in test (the base was 2.2%);
    - 2025 and 2026: option down 70% in 30 minutes, PCR below 0.7, plus one index condition; 1.0% and 0% in test.
  - **0 of 4 pass. 0 of 86 across R21-R33.** The smallest BH q is 0.172.
- **What it means for trading:**
  - **The "zero" is not a buy signal.** Buying cheap options after they crash (catching the knife) loses about as much
    as buying at random. The losers look exactly like the winners at that moment.
  - **What separates them is what happens next:** the index turns. And nothing measured at that minute, on the option or
    the index, predicts the turn well enough to pay.
  - **The practical takeaways stay the same:**
    1. If Boss plays heroes, use a hard trail (R31). It cut losses by about 60%.
    2. Prefer options priced cheaply against the move (R27 / R32), the only consistent tilt in R25-R33.
    3. Treat the stake as money you expect to lose.

## Method (PREREG.md has every detail)

- **Rows:** R32's 936,286 one-minute rows (12:00-14:55, traded OTM options at Rs 1-30, NIFTY 271 and SENSEX 175
  expiries). Labels use R32's confirmed-price rule.
- **339 patterns:**
  - **310 indicator variants,** on the option's own bars and on the index (read in the option's direction):
    - EMA crosses (4 pairs, fresh within 1 / 3 / 5 bars), price vs EMA 9 / 20 / 50;
    - RSI 7 / 14 / 21 at 4 levels and rising; MACD in 3 settings;
    - Bollinger in 3 settings (below, above, squeeze); Supertrend in 4 settings (green, fresh flip);
    - ADX 7 / 14 at 3 levels and DI; Stochastic in 2 settings; CCI; Donchian 10 / 20 / 30; Keltner;
    - VWAP (option's own; index TWAP) in 6 states;
    - 1-, 2- and 3-candle patterns (doji, hammer, shooting star, marubozu, big body, engulfing, inside, outside,
      harami, three soldiers / crows, morning / evening star);
    - green / red runs of 2-5, higher highs / lower lows;
    - 5 / 15 / 30-bar moves at 3 sizes; range compression and range position;
    - option-only: volume spikes (×1.5 / 2 / 3 / 5), dry-up, rising volume, the vertical-volume profile (point of
      control, volume near price), and strike OI changes over 1 / 5 / 15 / 30 bars at 1% and 5%.
  - **13 chain and pricing flags:** PCR levels and change, walls, gamma exposure, iv_rv, distance, VIX, heavyweights.
  - **16 shape clusters** (k-means on the last 30 closes ÷ the close now): 8 for the option and 8 for the index.
- **Frequency:** each pattern's share among heroes, among matched controls (same expiry, ±2 minutes, same price band,
  never 2×) and among all rows, and its forward 10× rate when present.
- **Walk-forward:** test years 2023-26. Each test year's choices are made on earlier years only:
  - the most occurring pattern;
  - the best pair or triple among patterns present in ≥ 15% of training heroes (≥ 2,000 rows on ≥ 50 expiries);
  - shape clusters refit on the training years.
- **Trades:** the first matching minute each expiry (ties: the lowest price), Rs 2,000, R26 fills and charges, Boss's
  25% trail or hold to 15:05. Random-time twins: 1,000 sets.

## Honest notes

- **Heroes are marked at their lowest row**, which favours crashed and oversold readings. The forward 10× rate column is
  the fair measure, and it never exceeds 2.5% (base 1.7%).
- **"Heavyweights agree over 5 minutes"** was registered as "all 3 agree over 5 minutes", but only the three stocks'
  average 5-minute move is stored. The flag uses the average (disclosed in the code). It is one of 339 patterns and
  changes no conclusion.
- **The first full run hit the 30-minute background limit** during the random-entry comparisons. It was re-run
  unchanged with a 2-hour limit; the published numbers are from the complete run.
- **No unseen holdout exists.** The walk-forward is the out-of-sample test; a forward shadow log is the only clean one
  left.

## Checks run

- `python -m pytest research/hunt/r33/test_r33.py -q` passes 6 tests. They cover:
  - every indicator flag reading the past only (future set to absurd values);
  - the candle patterns;
  - index flags for puts being the mirror of calls;
  - moves signed toward the option;
  - the volume profile and OI flags;
  - a whole day's flags unchanged when the future is poisoned.
- R26-R32 tests, the exit audit, the brain tests and the catalog check still pass. `python research/experiments/validate.py`,
  and `python3 research/knowledge/build_pack.py` then `--check`.
