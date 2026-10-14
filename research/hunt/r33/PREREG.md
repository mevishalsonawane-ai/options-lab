# R33 pre-registration: the backwards study - every indicator variant on the option AND the index before zero-to-hero

Written 14 Oct 2026, BEFORE any R33 flag was computed or compared with an outcome. Committed and pushed on its own.

Boss (14 Oct), "check it backwards":
1. find all the zero-to-hero options;
2. add every indicator, pattern and strategy with all variants (gamma, VWAP, OI, volume, vertical volume, everything),
   in different parameter variants, and find a pattern, all at once;
3. check the last 30 one-minute candles and find a pattern;
4. find the index at the same time;
5. do the same on the index;
6. check the index's last 30 one-minute candles;
then provide the most occurring pattern and how to gain from it.

## Rows and events (R32's, unchanged)

- **Rows:** NIFTY 271 and SENSEX 175 weekly expiries. Every 1-minute bar close 12:00-14:55 × every traded OTM option at
  Rs 1-30: R32's 936,286 rows, with R32's fixed labels.
- **Heroes:**
  - **H10:** the lowest-priced row of each run that later reaches a confirmed 10× (R32's 487 launches);
  - **H5:** the same at 5× (a sensitivity with more events).
- **Background:** every row. Event-vs-background comparisons also use R32's matched controls: same expiry, ±2 minutes,
  same price band, never 2×.

## The pattern library (binary flags; each at row t from rows ≤ t only)

**Computed the same way on (a) the option's own 1-minute bars and (b) the index's 1-minute bars:**

| family | variants |
|---|---|
| EMA crosses | pairs (5,13), (9,21), (13,34), (20,50): fast above slow; fresh cross up within 1 / 3 / 5 bars; fresh cross down within 3 bars |
| price vs EMA | above EMA 9 / 20 / 50 |
| RSI | periods 7 / 14 / 21: < 20, < 30, > 70, > 80, rising over 3 bars |
| MACD | (12,26,9), (5,35,5), (8,17,9): histogram > 0, histogram rising, fresh signal cross up within 3 bars |
| Bollinger | (20, 2), (20, 2.5), (10, 1.5): close below lower, close above upper, squeeze (bandwidth in the lowest third of the day so far) |
| Supertrend | (7,3), (10,2), (10,3), (14,2): green, fresh flip to green within 3 bars |
| ADX / DI | ADX 7 and 14: > 20, > 25, > 40; +DI > −DI |
| Stochastic | (14,3), (5,3): %K < 20, %K > 80, %K crossing above %D within 3 bars |
| CCI | 20: < −100, > +100 |
| Donchian | 10 / 20 / 30 bars: close at a new high, at a new low |
| Keltner | (20, 1.5 ATR): close above the upper, below the lower |
| VWAP | option: own VWAP since its first bar; index: TWAP (no volume). Above, below, > 1% / > 3% above, > 1% / > 3% below |
| candles, 1-bar | doji, hammer, shooting star, green marubozu, red marubozu, big body (> 2× the 10-bar average body) |
| candles, 2-bar | bullish engulfing, bearish engulfing, inside bar, outside bar, bullish harami |
| candles, 3-bar | three white soldiers, three black crows, morning star, evening star |
| runs | 2 / 3 / 4 / 5 green in a row; 2 / 3 / 4 / 5 red in a row; 2 / 3 higher highs; 2 / 3 lower lows |
| moves | 5 / 15 / 30-bar return below −a or above +a. Option: a = 30%, 50%, 70%. Index: a = 0.1%, 0.2%, 0.3% |
| range | 5-bar range / 30-bar range < 0.3; close in the top / bottom 20% of the 30-bar range |

- **Index flags** are read in the option's direction: for a put, "green" means the index fell, a fresh EMA cross up
  means a cross down, and so on. A put's flags are then the mirror of a call's.

**Option only:**
- volume ≥ 1.5 / 2 / 3 / 5 × its 10-bar EMA, and ≥ 2 × its 30-bar EMA; volume < 0.5 × its 10-bar EMA (dry-up); 3 rising
  volume bars;
- **vertical volume** (the option's volume profile since 12:00): the close above / below the point of control; ≥ 20% of
  the session's volume traded within ± 2 ticks of the close;
- strike OI change over 1 / 5 / 15 / 30 bars: rising > 1% or > 5%, falling > 1% or > 5%.

**Chain and pricing** (from R32's features, thresholds fixed here):
- PCR > 1.2, PCR < 0.7, PCR rising over 15 minutes (toward the option);
- the wall within 1 expected move;
- the wall's OI down ≥ 15% from its noon peak;
- GEX_short above its noon value; GEX_net below 0;
- iv_rv ≤ 1.0, iv_rv ≤ 0.8;
- the distance within 1 expected move;
- VIX rising since the open;
- all 3 heavyweights agreeing over 1 minute; all 3 agreeing over 5 minutes.

**30-candle shapes:**
- the last 30 one-minute closes, each ÷ the close at t, for the option and for the index;
- k-means with k = 8, fitted on a sample of training-year rows;
- every row gets an option-shape and an index-shape cluster.
- Descriptive version: one fit on all rows (the shape atlas). Trading version: refit per walk-forward fold on earlier
  years only.

## Analyses

1. **Frequency (descriptive):** for every flag and shape, its share among H10 (and H5) heroes, among matched controls
   and among all rows, plus its lift (hero share ÷ all-row share) and its row-level 10× rate. "Most occurring" is
   ranked by hero share. "Most useful" is ranked by lift, with ≥ 50 heroes.
2. **Combinations (walk-forward):**
   - on the training years only, every pair and triple of flags or shapes present in ≥ 15% of the training heroes;
   - ranked by the row-level 10× rate, with ≥ 2,000 training rows on ≥ 50 expiries;
   - the top combination per fold is carried to the test year.
3. **Trading (walk-forward, test years 2023-2026; Rs 2,000, whole lots, at least 1; R26 fills and charges):**

   | id | rule |
   |---|---|
   | B1 | "the most occurring" pattern: the flag with the highest hero share on the training years; the first matching row each expiry (ties: the lowest price); Boss's 25% trail (TR25) |
   | B2 | B1, held to 15:05 |
   | B3 | the top mined combination (step 2); the first matching row each expiry (ties: lowest price); TR25 |
   | B4 | B3, held to 15:05 |

## Controls, family, gates

- **Random-time twins (1,000 sets):** same expiry and index, a random minute 12:00-14:55, a random eligible row then,
  the same exit.
- **BH family: 82 (R21-R32) + 4 = 86.**
- **Gates (all):** ≥ 30 trades; net > 0; ≥ 3 of 4 test years positive; BH q < 0.05; net > 0 at 2× the half-spread;
  net > 0 without the best day; max drawdown smaller than Rs 40,000.

## If a rule passes

Paper only, after Boss's go-ahead (CLAUDE.md); never live. No app change.
