# R138 pre-registration: the triple-top fake breakout (an M, then a third poke that fails), and its W mirror

Written 31 Oct 2026, before any R138 number. Research only (no app code).

Boss, 31 Oct: *"on 15min timeframe, if there are 2 candles are touching a same resistance (till 5 points is ok), and
creating a M pattern. And if there is a 3 time its touch the same resistance and give a fake breakout, then check what
happens."*

**Signal source: the index spot's 15-minute candles, on wicks (highs and lows) for every level; closes only for "closed
back below".** The trade buys the ATM option. Nothing is read from an option's own chart.

Related work it will be compared with: R135 (sweeps of PDH/PDL, last week/month and the opening range on Indian
indices continue more often than they reverse), R22 (morning-range levels break more than they hold), R61B (trap
checks did not help Solo), R130-R131 (sweep-and-reverse entries lost).

## 1. The pattern (15-minute candles aligned to 09:15; R131's bars)

Written for the **M (triple top)**. The **W (triple bottom)** is the exact mirror (highs <-> lows, above <-> below),
run by R131's mirrored-side code.

- **Tolerance.** NIFTY: **5 points** (Boss's number). Other indices: **0.022% of price** (5 / 22,500), computed on the
  first touch's high. At round levels that is about: BANKNIFTY 50,000 -> 11 points; FINNIFTY 23,000 -> 5 points;
  MIDCPNIFTY 12,500 -> 2.75 points; SENSEX 75,000 -> 16.5 points.
- **The M (two touches).** Candles A then B with:
  - |high(A) - high(B)| <= tolerance;
  - at least **2 candles strictly between** them (B - A >= 3);
  - no candle between them has a high above max(high A, high B) (the level held between the touches);
  - **the pullback:** the lowest low strictly between them (the **neckline**) is at least **1.0 x ATR14** below
    max(high A, high B). ATR14 = the mean range of the 14 fifteen-minute candles before B (across sessions). k = 1.0
    is fixed; it is not tuned.
- **Resistance R:** the higher of the two highs (variant: their average).
- **The third touch and fake breakout (C):** the first candle after B whose high goes above R by at least **1 tick**
  (0.05) (variant: by at least the tolerance). C must be at least 2 candles after B (one candle between B and C), or
  there is no event (C = B + 1 is the same touch).
  - **One-candle fail (primary):** C closes below R. The event is at C's close.
  - **Two-candle fail (variant):** C closes at or above R, and the next candle closes below R. The event is at that
    next candle's close. If it does not close below R, it is a real breakout: no event.
  - **Fake high** = the highest high of the failing candle(s).
- **Sessions.** Primary: A, B and C in one session. Variant "span": A and B may be in the previous session or today;
  C is today.
- **One event per C.** If several (A, B) pairs give the same C, the one with the latest B is used, and for that B the
  latest A. Everything is known at the event's close (look-ahead guard in the tests).
- **Configurations:** level (max / average) x poke (tick / tolerance) x fail (1 / 2 candles) x sessions (same / span)
  = 16, for the M and the W. **Primary = max, tick, 1 candle, same session.**

## 2. What happens next (descriptive, the main question)

From the event's close (the index minute close at the event candle's last minute), on the index:
- the move over the next **15, 30 and 60 minutes** and **to 15:20** (the 15:19 minute's close), in points and %; share
  down vs up; mean and median. A horizon past 15:29 is left out.
- **The race** on minute wicks from the next minute to 15:20: does the index reach the **neckline** first, or go back
  **above the fake high** (by 1 tick) first, or neither? Both in one minute counts as "above the fake high" first.

Controls:
- **C1 time-of-day control:** for each event, the same minute on 20 random other sessions of the same period and index
  (seeded), with the same measures; the race uses the event's neckline and fake-high distances as % of price.
- **C2 plain double tops:** every M (primary rules, same session) measured from the close of the candle after B (if
  that candle did not go above R): the same moves; its race is neckline vs above R.

Reported by index, by year and by time of day (the event's hour).

**Tests (one BH family, design period):** for each of the 16 configurations and each side (M, W), paired against C1
by a session-clustered bootstrap (2,000 draws, two-sided): (a) the 60-minute move, (b) the move to 15:20, (c) the share
reaching the neckline first. Plus, for the primary configuration and each side, against C2: (d) the 60-minute move,
(e) the neckline-first share (unpaired, session-clustered bootstrap). 16 x 2 x 3 + 4 = **100 tests**, BH over all 100.
**The primary question is the M primary configuration.** Moves are signed in the pattern's direction (down for the M).

## 3. The tradable version (signal on the index; R131's option rule and costs)

- **Entry:** at the event, buy the **ATM PUT** (M) or **ATM CALL** (W) at the next minute's open (= the next 15-minute
  candle's open). ATM = the last index close before entry, rounded to the strike step. The option is the nearest
  expiry that does not expire that day (R131-R137); a day when both the weekly and the monthly expire has no such
  contract in the lake, so it is left out (as in R131-R137). So the exit is always 15:20.
- **Stop:** the fake high plus 0.02% (index wicks).
- **Target:** the neckline (variant: 2R from the entry open). If the index is already at or past the neckline at entry,
  there is no trade.
- **Out by 15:20.** Entries 09:15-15:00. One trade at a time per index per variant. 1 lot (today's lots).
- **Costs:** dated charges, liquidity-aware slippage; also 2x slippage.
- **Random-entry twins:** 100 per trade, entry minute uniform from the trade's own entry minute to 15:00, same day, same
  side, same stop and target distances, same exits. p = (1 + twin totals >= the rule's total) / 101.

| variant | side | pattern | target |
|---|---|---|---|
| **T1** (primary) | M, buy put | primary | neckline |
| **T2** | M | primary | 2R |
| **T3** | M | two-candle fail | neckline |
| **T4** | M | span (A, B from the previous session allowed) | neckline |
| **T5** | M | poke by at least the tolerance | neckline |
| **T6** | M | average level | neckline |
| **T7** | W, buy call | primary | neckline |
| **T8** | W | primary | 2R |

**BH over the 8.**

**Gates (R137's), design 1 Aug 2021 - 15 Mar 2026:** >= 300 trades; average net > 0 at 1x and 2x slippage; >= 3 of 4
folds positive (2023, 2024, 2025, 2026 Q1); BH q <= 0.10; max drawdown above -Rs 40,000 and no month below -Rs 13,000
on 1 lot. **Holdout 1 Apr - 19 Oct 2026, read once** for the descriptive part and all 8 variants after the design
results are committed: positive average net on >= 50 trades.

**Win rate against Boss's 65% rule** is reported for every variant. It is not a gate here: Boss, 29 Oct, "65% is only
for new strategies created by Jarvis" (R131 N3), and this is Boss's own pattern.

A variant passing everything is proposed (not built) as a paper arm for Boss to choose. Otherwise it is rejected.

## 4. Outputs

- `research/hunt/r138_triple_top_fake/` (`r138.py`, `test_r138.py`: the pattern on hand-built candles, its variants,
  and the look-ahead guard); `results/design.json`, `results/holdout.json`.
- `research/HUNT_R138_TRIPLE_TOP_FAKE.md`; entries `research/experiments/entries/r138-*.yaml`; RESULT.md.
