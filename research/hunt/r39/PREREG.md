# R39 pre-registration: the 1-minute anatomy of zero-to-hero launches, with every signal family at once

Written 14 Oct 2026, BEFORE any R39 label or outcome was computed. Committed and pushed on its own.

Boss (14 Oct):
- "check when at the entry of zero [to hero] candles, check last 30 candles and find all the zero [to hero] and find if
  there is any pattern for one minute";
- "add oi, vwap, ema, all the patterns like 2, 3, 4 [candle patterns] ... and all the other things like gamma exposure,
  find the relation between them, find the pattern, do a deep study";
- "add them all at once and then find the pattern".

## Data

- NIFTY 271 and SENSEX 175 weekly expiries (R26 day files; R25 loader), plus `equity_1m` HDFCBANK / RELIANCE /
  ICICIBANK and India VIX.
- **No unseen holdout exists** (R25, R26 and R34-R38 read these expiries). So everything below is an **anchored walk-forward by
  year**: test years 2023, 2024, 2025, 2026, each fitted and chosen on earlier expiries only. The truly clean test is
  a forward shadow log.

## Rows, labels, events

- **Row:** every 1-minute bar close from 12:00 to 14:55, and every OTM option on that index's stored chain whose bar at t
  traded with a close of Rs 1-30.
- **Labels** (hindsight, never features):
  - `y10`: the highest confirmed price from t+1 to 15:04 is ≥ 10× the row's close (confirmed = the bar's high only up
    to max(its close, the next open));
  - `y4` and `y2` the same at 4× and 2×.
- **Launch (the event):** for each (expiry, option) with any `y10` row, the `y10` row with the lowest close: the "zero"
  moment of that run.
- **Controls:** rows on the same expiry and index, within ± 2 minutes of the launch and in the same price band (Rs 1-3,
  3-5, 5-10, 10-15, 15-30), that never reached 2× (`y2` false).

## Features (all point-in-time: rows ≤ t of the day, plus earlier expiries for the vol seasonality)

**Option's own 1-minute bars (the "30 candles"):**
- returns over 1, 2, 3, 5, 10, 15 and 30 bars;
- the 2-, 3- and 4-candle patterns:
  - green-candle counts in the last 2, 3 and 4;
  - higher-high and higher-low streaks;
  - an inside bar and a bullish engulfing at t;
  - three rising closes;
- the body / range of bar t;
- the range of the last 5 bars ÷ the range of the last 30 (compression);
- the close's position in the 30-bar range;
- volume: bar t ÷ the 10-bar EMA of earlier bars, the last 5 ÷ the last 30, and volume since 12:00;
- OI: the strike's OI change over 1, 5, 15 and 30 bars;
- the vendor IV's change over 5 and 15 bars;
- EMA 9 − EMA 21 of the premium (÷ price), and the bars since the last EMA cross;
- Supertrend (7, 3) and (10, 2) states and the bars since each flip;
- the option's own VWAP since its first bar of the day (price × volume), and the distance to it.

**Index:**
- returns over 1, 5, 15 and 30 bars, signed toward the option;
- EMA 9 − 21 (signed) and ADX(14), both on 1-minute bars;
- the distance to the TWAP since the open (signed); the index has no volume;
- realized vol over 15 and 60 minutes, and its ratio;
- bars since a new day high (calls) or low (puts);
- the position in the day's range;
- the return since the open and the first-30-minute return (signed);
- the gap.

**Chain** (stored strikes, forward-filled OI):
- PCR and its 15-minute change (signed);
- the call wall / put wall distance in expected moves (signed toward the option);
- the wall's OI change from its noon peak;
- the max-pain distance (signed);
- GEX_short and GEX_net at that minute (R36's definition, at the ATM IV), each ÷ its own value at 12:00 of the same
  day;
- the ATM IV, and the ATM straddle's 15-minute change.

**Pricing:**
- `iv_rv`: our BS IV ÷ the forecast realized vol (R34's method);
- the distance in expected moves;
- the minutes left;
- VIX and its change since the open.

**Heavyweights:** 1-minute and 5-minute returns of HDFCBANK, RELIANCE and ICICIBANK, signed toward the option, and how
many of the three agree.

## Analyses

1. **Event study (descriptive):**
   - every launch vs its controls: the standardized mean difference of every feature and the rate of every candle
     pattern (lift);
   - the average normalized 30-bar paths of price and volume before launch vs controls.
   - Launch rows are picked with hindsight (the lowest `y10` row), so this part only describes; it chooses nothing.
2. **Point-in-time prediction (walk-forward):**
   - on every row, a HistGradientBoosting classifier for `y10`: fixed settings (max_iter 300, lr 0.05, 31 leaves, min
     leaf 500, l2 1.0, seed 32), trained on earlier years;
   - test-year AUC, and the `y10` rate in the top 1% / 0.1% of scores vs the base rate;
   - the permutation importance of the feature families on the last fold.
3. **Mined patterns:**
   - on the training years only, every 2- and 3-feature conjunction of tercile bins (cuts from the training years);
   - the conjunction with the highest `y10` lift, with ≥ 2,000 training rows on ≥ 50 expiries, is carried to the test
     year.
4. **Trading rules** (one trade per index per expiry; Rs 2,000, whole lots, at least 1; R26's fills and charges):

   | id | rule |
   |---|---|
   | P1 | at each minute, the row with the highest model score; the first minute whose top score exceeds τ, held with Boss's 25% trail (R38's TR25: stop = 75% of the confirmed peak, from entry; else 15:05) |
   | P2 | P1's entries held to 15:05 |
   | P3 | the first row matching the mined pattern (ties: highest model score), with TR25 |
   | P4 | P3's entries held to 15:05 |

   - τ is chosen on the last training year from the training-fold model's scores: the top-score quantile
     q ∈ {0.999, 0.9995, 0.9999} of all rows that maximizes net per Rs 1,000 with ≥ 15 trades; else 0.9999.

## Controls, family, gates

- **Random-time twins (1,000 sets):** same expiry and index; a uniformly random minute 12:00-14:55 and a random
  eligible row then; the same size and exit.
- **BH family: 78 (R21-R26 and R34-R38) + 4 = 82.**
- **Gates (all):** ≥ 30 trades over the test years; net > 0; ≥ 3 of the 4 test years positive; BH q < 0.05; net > 0 at
  2× the half-spread; net > 0 without the best day; max drawdown smaller than Rs 40,000.
- Also reported: AUC and lift by year; the strongest single features and pairs (with their test-year lift).

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md); never live by itself. No app change in R39.

## Amendment 1 (14 Oct 2026, after the first run; P1 / P2 only)

The first run gave **P1 and P2 zero trades**: τ was the q-quantile of the outer model's scores on its OWN training rows,
which are in-sample and far higher than any score the model gives an unseen year (0.988-0.994 on training rows against
a test-year maximum of 0.51-0.88). That made the rule untestable, not wrong in a way the data could show. The fix
keeps everything else:
- τ is now the q-quantile of the INNER model's out-of-sample scores on year y−1, the same scores used to choose q;
- that τ is applied to the outer model's test-year scores.
No P1 / P2 outcome had been seen (there were no trades). P3 / P4, the event study and the model report are unchanged.
