# R130 Amendment 1: wick-only versions (no candle opens or closes in any signal)

Written 29 Oct 2026, after R130's design and holdout results (all 6 versions lost), before any wick-only number was
computed. Boss asked: *"You tested by wick to wick right? ... Not opening and closing candles."*

R130's levels, sweep, FVG, pullback, stop and target were already wick-based. Two steps were not:
- the **MSS** used a 5-minute **close** beyond the recent high (low);
- the **engulfing** candle compared **bodies** (open to close).

V6 also used a close back inside, but V6 is not carried into this amendment.

**Honest label.** This amendment is added after the results were seen, and after the holdout (Apr - Oct 2026) was read
for V1-V6. Its holdout read is therefore not as clean as a first read. Every number from it is reported as
"Amendment 1, added after results". The verdict bar is unchanged.

## 1. What changes (everything else is PREREG.md, unchanged)

- **Wick MSS:** the first bar within 12 bars after the sweep whose **high** goes at least 1 tick (0.05) above the
  recent high known at the sweep. Bearish: a **low** at least 1 tick below the recent low.
- **Wick engulfing (the confirmation):** a bar at or after the first pullback bar, within the 12 bars, whose **high is
  above the previous bar's high and whose low is at or below the previous bar's low**. Its wicks engulf the previous
  candle's wicks, and it takes out the previous high (the turn up). Its low must not reach the stop. Bearish: the
  mirror (a low below the previous low, a high at or above the previous high).
- **What stays:**
  - **Entry:** the option is bought at the next minute's traded open (that is the fill price, not a signal). The
    index price in that minute sets the 2R distance and the "room" check, as in PREREG.md.
  - **Pricing:** the option is sold at its last traded price at the exit minute.
  - **Stops:** a stop gapped through fills at the minute's open (a fill, not a signal).
  - **No candle open or close is used to decide anything.**

## 2. Versions (4, their own BH family)

| version | chart | MSS | confirmation | target |
|---|---|---|---|---|
| **W1, the rule as stated, wick-only** | 5-min | wick break | wick engulfing | equal highs / lows, else the next swing |
| W2 | 5-min | wick break | none (the first 1-minute touch of the FVG) | equal highs / lows |
| W3 | **1-min** | wick break | wick engulfing | equal highs / lows |
| W4 | 5-min | wick break | wick engulfing | **fixed 2R** |

Swings 3 bars each side; sweep by wick only (no close back inside).

## 3. Checks, periods, gates

- **Periods:** as PREREG.md.
  - Design first, committed.
  - Then the holdout is read once for W1-W4, behind its own lock file (`HOLDOUT_READ_WICK.json`, refused once
    `results/holdout_wick.json` exists).
- **Twins:** random-entry twins as before (200 per trade).
- **Gates:** as PREREG.md, including Boss's 65% holdout win rate, with BH over the 4 W versions.
- **C3, wick version:** every first wick break of a swing with an FVG in its leg, split by a sweep in the 12 bars
  before or not. The 1:1 race starts at the minute after the break, from the broken level + 1 tick, with d = that level
  minus the leg's lowest low (wicks only).
- **Outputs:**
  - results: `results/design_wick.json`, `results/holdout_wick.json`;
  - write-up: a new section in `research/HUNT_R130_SWEEP_MSS_FVG.md`;
  - entries: `r130-wick-w1..w4`;
  - an update to RESULT.md.
