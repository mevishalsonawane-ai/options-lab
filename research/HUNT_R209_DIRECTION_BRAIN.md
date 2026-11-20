# R209: the direction brain - everything the data has, three models, one locked holdout (20 Nov 2026)

Boss, 20 Nov: *"If we get correct direction prediction then our all strategies will work more accurate, use your 100%
effort to get your best direction."*

Pre-registered in `research/hunt/r209_direction_brain/PREREG.md` (commit e706174d, before any number; Amendment A in
5c66517f, before any model was fitted). Walk-forward results frozen in 8efb23a2 before the holdout was read once. Every
table: `results/report.md`. Research only: **nothing in the app changed.**

## What was built

- **The question:** will the index be higher or lower 15 minutes from now? Asked every 5 minutes, 09:20 - 15:10, on all
  five indices, 8 Oct 2021 - 5 Oct 2026: **434,520 decision points**.
- **58 inputs, all from the past only** (a test proves it: replacing the future with noise changes none of them):
  - the index's own price action (returns over 1 - 60 minutes, gap, position in the day's range, distance to the day's
    average, volatility, Donchian, Supertrend, EMA trend, time of day, weekday);
  - the other four indices (their moves, how many are up);
  - India VIX (level and changes);
  - the option chain (ATM call and put IV, skew, put / call OI ratio and its changes, call and put OI changes, straddle
    price, put / call volume).
- **Three models:** logistic regression, gradient-boosted trees and a neural network, trained walk-forward (each quarter
  predicted by a model that only saw earlier data).
- **The last six months (1 Apr - 5 Oct 2026) were locked away** and read once at the end.

## Verdict for Boss

**Direction is a little predictable, but not enough to use. 0 of 8 tests pass.**

### 1. A real edge, and the best one found here so far - but tiny

| | walk-forward, 2023 - Mar 2026 (284,000 points) | locked holdout, Apr - Oct 2026 (45,000 points) |
|---|---|---|
| best model, AUC (0.50 = coin) | **0.526** (logistic) | 0.511 - 0.519 (all three) |
| right overall | 51.8% | 50.6 - 51.4% |
| its most confident 20% of calls | right **54.5%** | right 51.2 - 53.0% |

- In the walk-forward the edge is real (p < 0.002), the same on every index, and well ordered: when the model was most
  sure of "up", the index went up 55.7% of the time; most sure of "down", it went up 47.0%.
- Most of it comes from the index's own price action; the other indices, VIX and the option chain add very little
  (trees: own index alone 0.521, with the other indices 0.523, adding VIX 0.516, with everything 0.524).
- **In the locked months it faded:** AUC 0.511 - 0.519 and the most confident calls were right only 51 - 53%. The neural
  network kept the most (0.519, still clearly above a coin, p 0.0005), but nowhere near the bar of 0.53 with 55% on its
  most confident calls.

### 2. It does not make the arms' calls more reliable

On the locked months, signals the model agreed with were right 51.5% (R72, 1,176 signals) against 50.4% for the rest;
R51 50.7% against 49.5%; Liquidity 45.9% against 55.6% (worse). None of it is beyond chance.

### 3. As a filter on the arms it costs money

Using the model to skip trades it disagrees with lowered the arms' money at every threshold tried on the design period
(R72: Rs 2,22,516 at the mildest threshold against Rs 2,32,769 today; stricter thresholds down to Rs 68,337). In the locked
months: R72 Rs 97,587 against Rs 98,609, Liquidity Rs 9,134 against Rs 11,087.

**Why, in plain words:** being right 52% of the time instead of 50% moves the index's average 15-minute move in your
favour by a few hundredths of a percent. The arms earn from a few big moves, cut losers fast and lock winners. A tiny
edge on "which way" does not change that, and skipping trades also skips some of the big winners.

## What this means

This is the eleventh direction study here, and the most thorough. All of them say the same thing: **at 15 - 60 minutes,
the index's direction is close to a coin, even with every input we have.** The arms make money in spite of that, through
their exits, not their direction calls.

So "get the direction right and every strategy works better" is true in principle, but the data says it cannot be done
to a useful degree with the information we have. What is left that could change this:

1. **Data we do not have:** real order flow (bid / ask sizes, trade-by-trade). The phone's tick recordings exist but are
   encrypted with your backup passphrase, and the history is only a few weeks. If you ever want, those could be tested.
2. **Longer horizons** (the rest of the day, or tomorrow): not tested here; R16 found tomorrow's range follows VIX, and
   no daily direction rule (R105B) has worked.
3. **Accept it and steer by risk:** which arms run, lots and account size (R205), where the data does give clear answers.

My recommendation: **stop searching for direction** at this horizon, and keep the arms as they are with R51 stopped live.
If you want one more thing tested, the encrypted tick data is the only genuinely new source.
