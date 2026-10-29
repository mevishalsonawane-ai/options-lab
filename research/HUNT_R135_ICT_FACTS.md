# R135: do the ICT / SMC books' claims hold on Indian indices? Mostly not: here a sweep is more often a breakout

29 Oct 2026. Research only, descriptive: no trades, no options, no costs. These are the books' claims (study:
`research/ICT_BOOKS_STUDY.md`) measured on index minutes. The data is the five indices over the design period only
(Aug 2021 - 15 Mar 2026, 1,144 sessions).

## The answer

**The books' core idea, "price sweeps liquidity and then reverses", is wrong on NIFTY, BANKNIFTY, FINNIFTY,
MIDCPNIFTY and SENSEX.** A poke past yesterday's, last week's or last month's high or low, or past the opening range,
is followed by **more continuation than normal**, not a reversal. This is why every ICT entry in R130-R133 lost.
Three things the books say do hold, but they are not the reversal edge.

**How to read the table.**
- **The race:** after the event, does price move 0.25% in the predicted direction before it moves 0.25% against it?
- **The control:** the same race at the same minute on 20 other days.
- 50% means no information.
- **q** is the BH-adjusted p over the 12 primary tests.

| claim (book) | events (n) | predicted move first | control / comparison | p | q | verdict |
|---|---|---|---|---|---|---|
| Sweep of **PDH / PDL** reverses (all books) | 2,798 | **47.4%** | 49.6% | 0.022 | 0.071 | **wrong: it continues more** |
| ...the same with displacement (the "algo candle") | 129 | 53.5% | 48.5% | 0.27 | 0.36 | too rare to tell (5% of sweeps) |
| Sweep of **last week's** high/low reverses | 977 | 48.5% | 50.9% | 0.14 | 0.21 | no |
| ...with displacement | 59 | 44.1% | 44.0% | 0.99 | 0.99 | no |
| Sweep of **last month's** high/low reverses | 430 | **46.3%** | 51.4% | 0.040 | 0.080 | **wrong: it continues more** |
| ...with displacement | 26 | 53.8% | 51.4% | 0.81 | 0.88 | too rare to tell |
| **Opening range** (09:15-09:44) swept 09:45-11:00, then reverses (the "Asia range" stand-in) | 4,446 | **48.6%** | 50.4% | 0.024 | 0.071 | **wrong: it continues more** |
| The day's high or low is made **in the first 75 minutes** (the "London makes the high or low" stand-in) | 5,541 sessions | **78.3%** | 59.4% if the minutes were shuffled | < 0.001 | < 0.001 | **true** |
| The week's high or low is made **Monday or Tuesday** | 864 weeks | 78.7% | 80.9% if the days were shuffled | 0.099 | 0.17 | no |
| Breaking a **strong** swing continues more than breaking a **weak** one | 1,169 vs 14,330 | **55.8%** | 52.6% (weak) | 0.037 | 0.080 | **true, small** |
| A pullback that retraced **past 50%** holds more often than a shallow one | 5,910 vs 2,743 | 55.6% held | **63.5% (shallow)** | < 0.001 | < 0.001 | **wrong: shallow pullbacks hold more** |
| A **fully filled FVG** reverses | 16,283 | 49.6% | 49.9% | 0.34 | 0.41 | no |

**Reported, not tested:**
- **FVGs do get filled.** Of 23,756 fifteen-minute gaps, 60% are half-filled the same session and 90% within 5
  sessions.
- **After an opening-range sweep,** the opposite end of the range is reached by the close only 36% of the time
  (4,673 days).
- **20 sessions after a sweep of last month's high** (with daily displacement), price was lower 47% of the time
  (233 events). **After a sweep of last month's low,** it was higher 56% of the time (167 events). Both are noise.

## What this means for the next steps

1. **On Indian indices the edge, if any, is in continuation, not reversal.** A break of a major level, the opening
   range or a strong swing tends to keep going.
   - This matches what already works in the app: the Liquidity arm trades the **break** of a level, not the fade
     (`LiquidityRules`, on paper).
   - It also matches R124: the 30-point moves are momentum.
2. **What the books get right, and can still be used:**
   - the day's high or low usually comes early;
   - a strong swing's break is more reliable than a weak one's;
   - gaps get filled, so an open FVG is a reasonable target.
3. **What should not be used here:** fading sweeps, the 50% "valid pullback" rule (it is backwards here), "fill then
   reverse", and the Monday/Tuesday week.
4. **For the network (R136),** these concepts go in as inputs anyway, as Boss asked: strong/weak state, level-sweep
   flags with displacement, open FVGs, the opening-range state, premium/discount. The network can then weigh them on
   its own, and the R135 facts say which way they point.
5. **For the strategy (R137),** the facts point to a **continuation** design, not a reversal one:
   - the break of a strong swing or a major level;
   - toward an open FVG or the next major level;
   - early in the day.
   - This is the opposite of what the books teach. It has to be pre-registered and tested like everything else.

## Notes

- **The three "true" findings are about where moves happen, not a free edge.**
  - The early-extreme effect is partly that the open is the most volatile part of the day.
  - The strong-swing effect is +3.2 points of percentage.
- **Displacement after a sweep is rare** (5% of sweeps), so the books' "algo candle" cannot be judged on this data.
  In the few cases there are, the reversal rate is about 53-54% for daily and monthly levels (n = 129 and 26), but
  only 44% for weekly (n = 59).
- **A code bug was fixed after the first run, before these numbers** (PREREG N1). The bearish half of two tests raced
  in the wrong direction. Nothing else changed.
- **Code and tests:** `research/hunt/r135_ict_facts/` (`r135.py`, `test_r135.py`). Results: `results/facts.json`.
