# HUNT R4: "magic numbers", Gann, Fibonacci and harmonic patterns, studied deeper for an option buyer

Written 9 Oct 2026. The frame is fixed:
- option BUYING only, Rs 1,00,000 capital, FIXED 1 lot (NIFTY 65, BANKNIFTY 30, FINNIFTY 60), no compounding;
- 1-ITM strike of the nearest expiry, bought at the next minute's open;
- exits checked on the option's own 1-minute high/low (the wicks), with the stop counted first on a tie;
- costs are the app's charges plus the real h24 half-spread on each side (NIFTY 0.16%, BANKNIFTY 0.16%, FINNIFTY 0.42%).
  The app's own ±5 bps fill is not added on top (X3 #2).

The tests are the usual ones: 10 random-entry twins per trade, BH correction across all versions, a White Reality
Check, and the locked year (1 Oct 2025 - 6 Oct 2026), opened once after the picks were frozen.

- Plan, written before any result: `research/hunt/r4/PREREG.md`.
- Code: `research/hunt/r4/`:
  - `lib4.py`: the R1 engine plus FINNIFTY, twins and gates;
  - `signals4.py`: all rules;
  - `design.py`: design and holdout;
  - `placebo.py`: the level test;
  - `xau.py`: the gold check;
  - `posthoc_time.py` and `hindsight.py`: POST-HOC checks, labelled as such;
  - `tables.py`.
- Small result files: `research/hunt/r4/results/`. `all_variants.csv` holds every variant, with design and holdout
  columns.
- Logs and trade files: `scratchpad/hunt/r4/` (about 27 MB).
- Data: local Dhan minute data only. The new Dhan token was not needed.

## Verdict (plain English)

**No. For an option buyer with Rs 1 lakh and 1 fixed lot, none of the four topics makes money after costs. In the
cleanest test, a placebo, their "special" levels attract price no more than random levels with the same spacing.**

| topic | does it make money (1 lot, after costs)? | do its levels/times matter more than random ones? |
|---|---|---|
| **1. "Magic numbers"** (Square-of-9 "buy above / sell below" calculators, round "00 & 50" levels, Gann point numbers from the open, digital root 9, the magic 15-min candle with Fibonacci extensions) | **No.** 93 versions; only 1 positive in design (+Rs 22/day). The Square-of-9 calculator lost Rs 167-284/day in the locked year on all three indices | **No.** Square-of-9 levels reverse the price exactly as often as randomly shifted grids. Round numbers are not magnets, and in design they were **broken more often** than random levels (NIFTY / BANKNIFTY), not reversed. Digital-root-9 levels behave exactly like random numbers |
| **2. Gann** (HiLo activator, swing chart, 50% / quarters rule, 1x1 / 2x1 angles from the opening range, minute cycles 45/90/180 and 90/144, seasonal dates) | **No.** 105 versions, 2 positive in design. The best design version (NIFTY 2x1 angle, +Rs 75/day) made **-Rs 240/day** in the locked year | **No.** The 50% / 25% / 75% levels of yesterday's range, the "Gann point" offsets and the Gann minute marks show no extra reversals and no extra turning points |
| **3. Fibonacci** (Fibonacci pivots, previous-day-range retracements, golden pocket, Upstox 3-min 61.8% rule, time zones, extensions, round-number confluence) | **No.** 99 versions, 3 positive in design. The best (BANKNIFTY Fibonacci-pivot fade, +Rs 63/day, beat its twins at p 0.007) made **-Rs 153/day** in the locked year | **No.** Swing retracements (38.2-78.6%, 127.2 / 161.8%) reverse **49.0% vs 49.1%** of the time against shifted ratios (NIFTY, 25,000+ touches). Fibonacci time zones hit swing turns no more often than shifted times (POST-HOC fair version: 30.6% vs 30.8%) |
| **4. Harmonic patterns** (Gartley, Bat, Alt Bat, Butterfly, Crab, Deep Crab, Shark, Cypher, 5-0, ABCD on 3/5/15/60-min bars, PRZ entry with confirmation) | **No.** They look attractive: many win 50-70% to the first 0.382 target. But they are rare: 1 to 13 trades a year per pattern per timeframe. The design profits were Rs 5-70/day on 30-150 trades. In the locked year the picks made between -Rs 160 and +Rs 59/day on **4-30 trades each** | Not testable as a level placebo. Pooled over all harmonics, the trades did Rs 6 a trade WORSE than their random twins |

The numbers behind the verdict:
- **660 trading versions** were tested in design (NIFTY, BANKNIFTY, FINNIFTY; 3 exits each).
  - **0 passed.** The smallest BH q was 1.00 (against the random twins: 0.46).
  - The best White Reality Check p was 0.985.
  - 118 of 660 were positive in design. Most of those were rare harmonics with fewer than 30 trades.
- **Locked year (opened once, 75 frozen picks, one per rule family per index): 10 positive, 65 negative.**
  - The median pick made **-Rs 88/day**. The best made +Rs 59/day on 13 trades.
  - Every design favourite flipped to a loss.
- **Placebo-level test: 105 tests in design, 1 survived BH**: FINNIFTY Square-of-9 0.125 grid, +1.2 percentage points
  more reversals.
  - In the locked year that effect was **+0.0 points (p 0.50)**, so it is not confirmed.
  - **0 of 35 level/time families are confirmed.**
- **Rs 5,000/day: not reachable.** Every rule loses money, so no number of lots gets there.
- **Paper-only bot worth adding: none.** Details at the end.

Compared with h37 (19,034 variants, "equal to random"), R4 adds:
- the exact Indian retail formulas;
- the newer harmonic patterns;
- FINNIFTY;
- the placebo test of the levels themselves.

All four point the same way.

## Why traders still believe in them (evidence from these tests)

1. **There is always a level near the turn.** The table below (POST-HOC, descriptive, design period, `hindsight.py`)
   shows how often the day's high or low lands within 0.05% of a level. Random grids with the same spacing do it just
   as often.
   - Square-of-9 0.125 grid: **86%** of days. Its randomly shifted twin: **86%**.
   - Gann point numbers from the open: 57% (placebo 58%).
   - Round 50s: 64% (65%).
   - Digital-root-9 levels: **99.6%** (99.6%).
   - So with 5-35 levels inside a normal day's range, the chart always "proves" the method in hindsight.

| level set | levels inside the day's range (avg) | day's high or low within 0.05% of one: claimed | same, randomly shifted grid |
|---|---|---|---|
| Square-of-9, 0.125 step | 7.4 | 85.9% | 86.4% |
| Square-of-9, 0.25 step | 3.7 | 53.1% | 54.9% |
| Gann points from open | 4.2 | 56.7% | 57.5% |
| Round 50 (BN 100) | 4.7 | 64.1% | 65.0% |
| Round 100 (BN 500) | 1.8 | 28.9% | 30.5% |
| Fibonacci pivots | 2.1 | 36.5% | 37.5% |
| Prev-day range Fibonacci | 2.6 | 41.9% | 41.6% |
| Gann 25/50/75% | 1.5 | 26.0% | 26.9% |
| Digital root 9 (multiples of 9) | 33.9 | 99.6% | 99.6% |

2. **Touching a level is not a reaction.** A level within 0.5% of the open gets touched about 55-68% of the time,
   whatever it is. Claimed and placebo touch rates match to within about 1 point. After the touch, the price reverses
   about 45-50% of the time and breaks through the rest. That is a coin flip, and it is the same coin for the "magic"
   level and for a random one.
3. **Round numbers are real, but backwards for a fader.** In design:
   - Round levels on NIFTY and BANKNIFTY were broken through more often than random levels: reversal 42-47% vs
     47-48%, p 0.92-1.00.
   - Gold (XAUUSD 2015-2022) shows the same, more strongly: 10 / 50 / 100-dollar levels reversed 3-19 points less
     often than shifted grids.
   - This matches the order-flow research: take-profits cluster AT round numbers, and stops cluster just BEYOND them
     (Osler 2003). A move into a round number therefore tends to run through it.
   - Traders remember the round number as "important", which is true. But "it reverses there" is the wrong half of
     the story.
   - In the locked year the index effect was weaker and not significant.
4. **Harmonics win often but rarely, and the wins are small.** For example:
   - BANKNIFTY 3-min Cypher with the 0.382 target: 72% winners in design.
   - 3-min 5-0: 64%.
   - The 0.382-AD first target is close and the pattern stop is far, so the win rate is high by construction.
   - With 5-15 trades a year, a few lucky months look like a system.
   - In the locked year the same picks traded 4-30 times. Their average was negative.
5. **Self-fulfilling at the margin only.** Square-of-9 calculators are very popular in India, and they showed no
   extra reaction in 9 of 10 index tests (the 10th, FINNIFTY, vanished in the locked year). If they were
   self-fulfilling at all, the effect was too small to measure in 1,000+ touches a year.
6. **Costs and decay do the rest.** Pooled over all 369k design trades:
   - Rs -62 a trade before charges and spread (gross);
   - Rs -179 net;
   - a random entry at the same time loses Rs -162.
   So the methods are Rs 17 a trade **worse** than random entry. Even a neutral signal loses about Rs 160 a trade
   for an option buyer.

## The placebo-level test (the main scientific test)

**Method.**
- **Levels.** Each day the claimed levels are built exactly as the sources say, from data known before the session.
  The placebo levels use the same construction with a random shift inside the same spacing, 20 draws a day. For
  example, the Square-of-9 grid becomes (√pc + 0.125 i + φ)² with φ uniform in [0, 0.125). Ratio sets get a common
  shift of 1.5-12 percentage points.
- **Event.** The first touch of each level after 09:20.
- **REVERSAL.** Within 60 minutes the price comes back d (0.10% or 0.25%) from the level before it moves d through it.
- **Statistic.** Reversal rate (claimed) minus reversal rate (placebo). p comes from a day-cluster bootstrap, 1,000
  draws, one-sided.
- **Confirmed.** Requires BH q < 0.05 in design AND p < 0.05 in the locked year, with the same sign.
- **Time claims.** A time mark counts as a hit when it falls within 5 minutes of a 5-min ZigZag (2 × ATR) turning bar.
  The placebo shifts the mark by ±10-30 minutes.

**Result: no level or time family is confirmed.** The only design survivor did not hold in the locked year: FINNIFTY
Square-of-9, 0.10%, +1.2 points, q < 0.001. Its locked-year result was +0.0 points, p 0.50.

POST-HOC note on the time test:
- In the pre-registered version, Fibonacci time zones scored worse than random (-3 points).
- That is a flaw in the placebo, not a finding: a mark shifted back 10-30 minutes can land on its own anchor pivot.
- With the anchor pivot excluded (`posthoc_time.py`), FTZ is simply random: -0.3 / -0.0 / -0.2 points in design.
  In the locked year it was -0.2 / -0.2 / +1.0 points (FINNIFTY p 0.03, a single test).

**MCX / gold (information only).** On XAUUSD minutes 2015-2022, the stand-in for MCX gold:
- round numbers reversed **less** often than shifted grids (-3 to -19 points, p ≥ 0.998);
- Square-of-9 levels: no effect;
- previous-day Fibonacci: +1.4 points at d 0.10% (p 0.015).

In 2023, previous-day Fibonacci was -1.9 points and nothing was significant. Crude: no long continuous minute
series is stored locally, so it was not tested. MCX option costs are higher still (m3), so none of this could pay a
buyer anyway.

### Placebo results, every level and time family

Reversal rate = the share of first touches where the price comes back d before going d through. "pp" = percentage
points. p = one-sided (claimed > placebo). BH q is over all 105 design placebo tests.

| level family | index | d | design: claimed vs placebo reversal | diff (pp) | p | BH q | holdout: claimed vs placebo | diff (pp) | p | confirmed? |
|---|---|---|---|---|---|---|---|---|---|---|
| Gann SQ9 grid, 0.125 step (22.5°) | BANKNIFTY | 0.10% | 46.7% vs 47.0% | -0.3 | 0.841 | 1.00 | 46.8% vs 47.1% | -0.4 | 0.74 | no |
| Gann SQ9 grid, 0.125 step (22.5°) | BANKNIFTY | 0.25% | 47.5% vs 47.4% | +0.0 | 0.451 | 1.00 | 46.8% vs 47.6% | -0.8 | 0.94 | no |
| Gann SQ9 grid, 0.125 step (22.5°) | FINNIFTY | 0.10% | 46.9% vs 45.6% | +1.2 | 0.000 | 0.00 | 46.7% vs 46.7% | +0.0 | 0.50 | no |
| Gann SQ9 grid, 0.125 step (22.5°) | FINNIFTY | 0.25% | 45.8% vs 45.2% | +0.5 | 0.081 | 0.73 | 47.5% vs 47.1% | +0.3 | 0.32 | no |
| Gann SQ9 grid, 0.125 step (22.5°) | NIFTY | 0.10% | 48.0% vs 47.5% | +0.4 | 0.211 | 0.89 | 48.6% vs 48.5% | +0.1 | 0.46 | no |
| Gann SQ9 grid, 0.125 step (22.5°) | NIFTY | 0.25% | 48.3% vs 47.6% | +0.8 | 0.035 | 0.53 | 50.6% vs 51.0% | -0.4 | 0.62 | no |
| Gann SQ9 grid, 0.25 step (45°) | BANKNIFTY | 0.10% | 45.0% vs 47.2% | -2.2 | 1.000 | 1.00 | 46.4% vs 47.3% | -0.9 | 0.77 | no |
| Gann SQ9 grid, 0.25 step (45°) | BANKNIFTY | 0.25% | 46.9% vs 47.5% | -0.6 | 0.894 | 1.00 | 46.2% vs 47.4% | -1.2 | 0.86 | no |
| Gann SQ9 grid, 0.25 step (45°) | FINNIFTY | 0.10% | 47.3% vs 45.8% | +1.4 | 0.048 | 0.56 | 49.1% vs 47.0% | +2.1 | 0.09 | no |
| Gann SQ9 grid, 0.25 step (45°) | FINNIFTY | 0.25% | 46.0% vs 45.1% | +0.9 | 0.143 | 0.73 | 47.8% vs 47.1% | +0.7 | 0.33 | no |
| Gann SQ9 grid, 0.25 step (45°) | NIFTY | 0.10% | 47.8% vs 47.3% | +0.5 | 0.278 | 0.94 | 47.2% vs 48.5% | -1.3 | 0.75 | no |
| Gann SQ9 grid, 0.25 step (45°) | NIFTY | 0.25% | 48.7% vs 47.8% | +0.9 | 0.144 | 0.73 | 51.1% vs 51.2% | -0.1 | 0.52 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | BANKNIFTY | 0.10% | 47.3% vs 47.1% | +0.2 | 0.350 | 0.97 | 47.2% vs 46.4% | +0.8 | 0.10 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | BANKNIFTY | 0.25% | 48.7% vs 48.1% | +0.6 | 0.020 | 0.53 | 48.9% vs 47.9% | +1.0 | 0.05 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | FINNIFTY | 0.10% | 45.0% vs 46.8% | -1.8 | 0.997 | 1.00 | 43.6% vs 46.9% | -3.2 | 0.99 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | FINNIFTY | 0.25% | 46.2% vs 46.4% | -0.2 | 0.634 | 1.00 | 47.8% vs 48.2% | -0.4 | 0.61 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | NIFTY | 0.10% | 48.3% vs 48.3% | +0.1 | 0.489 | 1.00 | 51.1% vs 49.9% | +1.1 | 0.20 | no |
| Gann numbers from open (±45/90/144/180/360 pts) | NIFTY | 0.25% | 47.2% vs 47.9% | -0.8 | 0.888 | 1.00 | 53.1% vs 51.9% | +1.1 | 0.22 | no |
| Digital root 9 (multiples of 9) | BANKNIFTY | 0.10% | 47.4% vs 47.3% | +0.1 | 0.123 | 0.73 | 47.2% vs 47.2% | +0.0 | 0.40 | no |
| Digital root 9 (multiples of 9) | BANKNIFTY | 0.25% | 47.6% vs 47.5% | +0.1 | 0.008 | 0.42 | 47.6% vs 47.4% | +0.1 | 0.07 | no |
| Digital root 9 (multiples of 9) | FINNIFTY | 0.10% | 46.3% vs 46.2% | +0.1 | 0.254 | 0.94 | 46.9% vs 46.7% | +0.2 | 0.22 | no |
| Digital root 9 (multiples of 9) | FINNIFTY | 0.25% | 45.8% vs 45.6% | +0.2 | 0.047 | 0.56 | 47.8% vs 47.7% | +0.1 | 0.35 | no |
| Digital root 9 (multiples of 9) | NIFTY | 0.10% | 48.0% vs 47.8% | +0.1 | 0.146 | 0.73 | 49.1% vs 48.6% | +0.5 | 0.04 | no |
| Digital root 9 (multiples of 9) | NIFTY | 0.25% | 48.0% vs 48.0% | +0.0 | 0.451 | 1.00 | 50.4% vs 50.1% | +0.4 | 0.09 | no |
| Round 50 (BN: 100) | BANKNIFTY | 0.10% | 44.8% vs 46.8% | -2.0 | 1.000 | 1.00 | 47.2% vs 47.1% | +0.1 | 0.48 | no |
| Round 50 (BN: 100) | BANKNIFTY | 0.25% | 46.6% vs 47.2% | -0.6 | 0.936 | 1.00 | 48.7% vs 47.7% | +1.0 | 0.15 | no |
| Round 50 (BN: 100) | FINNIFTY | 0.10% | 45.5% vs 45.9% | -0.4 | 0.704 | 1.00 | 47.2% vs 46.0% | +1.2 | 0.13 | no |
| Round 50 (BN: 100) | FINNIFTY | 0.25% | 45.6% vs 45.2% | +0.4 | 0.234 | 0.94 | 47.2% vs 47.0% | +0.2 | 0.40 | no |
| Round 50 (BN: 100) | NIFTY | 0.10% | 45.7% vs 47.3% | -1.6 | 0.988 | 1.00 | 47.9% vs 48.3% | -0.4 | 0.61 | no |
| Round 50 (BN: 100) | NIFTY | 0.25% | 46.9% vs 47.9% | -0.9 | 0.929 | 1.00 | 51.1% vs 50.6% | +0.4 | 0.39 | no |
| Round 100 (BN: 500) | BANKNIFTY | 0.10% | 42.7% vs 47.6% | -4.9 | 0.999 | 1.00 | 46.5% vs 46.7% | -0.2 | 0.55 | no |
| Round 100 (BN: 500) | BANKNIFTY | 0.25% | 41.8% vs 47.9% | -6.1 | 1.000 | 1.00 | 46.5% vs 47.9% | -1.4 | 0.68 | no |
| Round 100 (BN: 500) | FINNIFTY | 0.10% | 44.3% vs 45.7% | -1.4 | 0.908 | 1.00 | 48.0% vs 46.4% | +1.6 | 0.23 | no |
| Round 100 (BN: 500) | FINNIFTY | 0.25% | 43.7% vs 45.2% | -1.6 | 0.937 | 1.00 | 48.3% vs 46.5% | +1.8 | 0.17 | no |
| Round 100 (BN: 500) | NIFTY | 0.10% | 43.7% vs 47.3% | -3.6 | 0.998 | 1.00 | 48.9% vs 48.2% | +0.7 | 0.39 | no |
| Round 100 (BN: 500) | NIFTY | 0.25% | 45.4% vs 47.7% | -2.2 | 0.977 | 1.00 | 48.3% vs 49.8% | -1.5 | 0.72 | no |
| Round 500 (BN: 1000) | BANKNIFTY | 0.10% | 42.9% vs 45.9% | -2.9 | 0.905 | 1.00 | 41.2% vs 47.3% | -6.2 | 0.93 | no |
| Round 500 (BN: 1000) | BANKNIFTY | 0.25% | 42.5% vs 46.3% | -3.7 | 0.942 | 1.00 | 41.1% vs 46.8% | -5.7 | 0.89 | no |
| Round 500 (BN: 1000) | FINNIFTY | 0.10% | 46.9% vs 45.7% | +1.1 | 0.346 | 0.97 | 47.2% vs 45.3% | +2.0 | 0.33 | no |
| Round 500 (BN: 1000) | FINNIFTY | 0.25% | 46.5% vs 45.1% | +1.4 | 0.318 | 0.97 | 47.4% vs 45.9% | +1.5 | 0.40 | no |
| Round 500 (BN: 1000) | NIFTY | 0.10% | 42.7% vs 47.6% | -4.9 | 0.979 | 1.00 | 48.1% vs 47.9% | +0.2 | 0.47 | no |
| Round 500 (BN: 1000) | NIFTY | 0.25% | 43.1% vs 47.7% | -4.5 | 0.922 | 1.00 | 42.6% vs 52.7% | -10.2 | 0.93 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | BANKNIFTY | 0.10% | 45.1% vs 46.6% | -1.5 | 0.929 | 1.00 | 45.7% vs 49.3% | -3.7 | 0.93 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | BANKNIFTY | 0.25% | 43.7% vs 45.9% | -2.3 | 0.980 | 1.00 | 50.9% vs 47.8% | +3.1 | 0.11 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | FINNIFTY | 0.10% | 45.4% vs 46.0% | -0.6 | 0.721 | 1.00 | 49.9% vs 45.5% | +4.4 | 0.03 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | FINNIFTY | 0.25% | 44.4% vs 43.8% | +0.6 | 0.275 | 0.94 | 44.4% vs 44.3% | +0.1 | 0.51 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | NIFTY | 0.10% | 46.9% vs 47.6% | -0.7 | 0.782 | 1.00 | 47.0% vs 48.6% | -1.6 | 0.75 | no |
| Fibonacci pivots (P ± .382/.618/1 R) | NIFTY | 0.25% | 44.7% vs 46.4% | -1.7 | 0.965 | 1.00 | 45.0% vs 48.0% | -3.0 | 0.91 | no |
| Prev-day range .236-.786 | BANKNIFTY | 0.10% | 49.3% vs 49.3% | -0.0 | 0.491 | 1.00 | 46.8% vs 45.7% | +1.0 | 0.25 | no |
| Prev-day range .236-.786 | BANKNIFTY | 0.25% | 48.9% vs 48.5% | +0.4 | 0.298 | 0.97 | 46.4% vs 47.0% | -0.6 | 0.67 | no |
| Prev-day range .236-.786 | FINNIFTY | 0.10% | 47.5% vs 48.4% | -0.9 | 0.829 | 1.00 | 44.4% vs 47.3% | -2.9 | 0.94 | no |
| Prev-day range .236-.786 | FINNIFTY | 0.25% | 48.9% vs 47.4% | +1.5 | 0.032 | 0.53 | 51.1% vs 50.2% | +0.9 | 0.25 | no |
| Prev-day range .236-.786 | NIFTY | 0.10% | 49.4% vs 49.2% | +0.2 | 0.374 | 0.97 | 48.6% vs 49.2% | -0.6 | 0.63 | no |
| Prev-day range .236-.786 | NIFTY | 0.25% | 49.6% vs 48.8% | +0.8 | 0.125 | 0.73 | 51.4% vs 52.9% | -1.6 | 0.80 | no |
| Gann 25/50/75% of prev range | BANKNIFTY | 0.10% | 48.0% vs 49.6% | -1.6 | 0.893 | 1.00 | 45.4% vs 46.4% | -0.9 | 0.63 | no |
| Gann 25/50/75% of prev range | BANKNIFTY | 0.25% | 49.2% vs 48.6% | +0.6 | 0.325 | 0.97 | 47.6% vs 46.8% | +0.8 | 0.37 | no |
| Gann 25/50/75% of prev range | FINNIFTY | 0.10% | 50.1% vs 47.3% | +2.8 | 0.022 | 0.53 | 40.7% vs 49.4% | -8.8 | 1.00 | no |
| Gann 25/50/75% of prev range | FINNIFTY | 0.25% | 48.6% vs 46.2% | +2.3 | 0.055 | 0.58 | 49.8% vs 49.2% | +0.6 | 0.43 | no |
| Gann 25/50/75% of prev range | NIFTY | 0.10% | 48.8% vs 49.2% | -0.4 | 0.627 | 1.00 | 51.3% vs 47.5% | +3.8 | 0.11 | no |
| Gann 25/50/75% of prev range | NIFTY | 0.25% | 47.9% vs 48.4% | -0.4 | 0.643 | 1.00 | 51.1% vs 51.7% | -0.7 | 0.59 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | BANKNIFTY | 0.10% | 49.8% vs 49.9% | -0.1 | 0.731 | 1.00 | 50.1% vs 49.9% | +0.3 | 0.28 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | BANKNIFTY | 0.25% | 48.1% vs 48.2% | -0.1 | 0.622 | 1.00 | 48.4% vs 49.1% | -0.7 | 0.93 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | FINNIFTY | 0.10% | 49.6% vs 49.4% | +0.2 | 0.176 | 0.84 | 48.5% vs 48.6% | -0.1 | 0.55 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | FINNIFTY | 0.25% | 46.5% vs 46.7% | -0.2 | 0.858 | 1.00 | 47.7% vs 47.6% | +0.1 | 0.41 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | NIFTY | 0.10% | 49.0% vs 49.1% | -0.1 | 0.703 | 1.00 | 48.5% vs 47.9% | +0.6 | 0.06 | no |
| Swing Fibonacci .382-.786 + 1.272/1.618 | NIFTY | 0.25% | 48.0% vs 47.9% | +0.1 | 0.377 | 0.97 | 48.7% vs 48.5% | +0.2 | 0.34 | no |

| family | index | design touch rate (levels within 0.5% of the open): claimed vs placebo | p | holdout claimed vs placebo | p |
|---|---|---|---|---|---|
| Gann SQ9 grid, 0.125 step (22.5°) | BANKNIFTY | 65.1% vs 65.1% | 0.57 | 59.8% vs 59.6% | 0.24 |
| Gann SQ9 grid, 0.125 step (22.5°) | FINNIFTY | 63.3% vs 63.0% | 0.14 | 61.1% vs 61.4% | 0.84 |
| Gann SQ9 grid, 0.125 step (22.5°) | NIFTY | 61.3% vs 61.0% | 0.10 | 57.7% vs 58.3% | 0.93 |
| Gann SQ9 grid, 0.25 step (45°) | BANKNIFTY | 65.1% vs 65.0% | 0.38 | 58.4% vs 59.9% | 1.00 |
| Gann SQ9 grid, 0.25 step (45°) | FINNIFTY | 63.5% vs 63.2% | 0.21 | 61.1% vs 61.1% | 0.51 |
| Gann SQ9 grid, 0.25 step (45°) | NIFTY | 61.4% vs 60.9% | 0.12 | 57.9% vs 58.3% | 0.69 |
| Gann numbers from open (±45/90/144/180/360 pts) | BANKNIFTY | 66.0% vs 65.8% | 0.09 | 64.7% vs 64.5% | 0.22 |
| Gann numbers from open (±45/90/144/180/360 pts) | FINNIFTY | 58.5% vs 60.0% | 1.00 | 61.4% vs 60.0% | 0.00 |
| Gann numbers from open (±45/90/144/180/360 pts) | NIFTY | 55.4% vs 56.2% | 1.00 | 56.0% vs 55.8% | 0.30 |
| Digital root 9 (multiples of 9) | BANKNIFTY | 64.9% vs 64.9% | 0.35 | 59.9% vs 59.9% | 0.21 |
| Digital root 9 (multiples of 9) | FINNIFTY | 62.7% vs 62.7% | 0.40 | 61.0% vs 61.1% | 0.66 |
| Digital root 9 (multiples of 9) | NIFTY | 60.7% vs 60.7% | 0.46 | 58.2% vs 57.9% | 0.01 |
| Round 50 (BN: 100) | BANKNIFTY | 64.5% vs 64.9% | 0.92 | 59.5% vs 59.9% | 0.80 |
| Round 50 (BN: 100) | FINNIFTY | 62.6% vs 62.9% | 0.86 | 61.3% vs 60.9% | 0.15 |
| Round 50 (BN: 100) | NIFTY | 60.2% vs 60.7% | 0.95 | 57.3% vs 58.1% | 0.92 |
| Round 100 (BN: 500) | BANKNIFTY | 66.6% vs 65.7% | 0.27 | 58.5% vs 60.0% | 0.72 |
| Round 100 (BN: 500) | FINNIFTY | 62.9% vs 62.6% | 0.27 | 61.6% vs 60.8% | 0.14 |
| Round 100 (BN: 500) | NIFTY | 60.0% vs 60.6% | 0.84 | 57.1% vs 58.5% | 0.87 |
| Round 500 (BN: 1000) | BANKNIFTY | 67.5% vs 65.8% | 0.21 | 56.8% vs 59.4% | 0.74 |
| Round 500 (BN: 1000) | FINNIFTY | 62.2% vs 63.1% | 0.64 | 61.2% vs 61.4% | 0.51 |
| Round 500 (BN: 1000) | NIFTY | 59.1% vs 61.3% | 0.82 | 50.8% vs 57.3% | 0.91 |
| Fibonacci pivots (P ± .382/.618/1 R) | BANKNIFTY | 62.5% vs 62.5% | 0.48 | 57.9% vs 57.5% | 0.34 |
| Fibonacci pivots (P ± .382/.618/1 R) | FINNIFTY | 60.5% vs 61.4% | 0.95 | 60.0% vs 60.4% | 0.70 |
| Fibonacci pivots (P ± .382/.618/1 R) | NIFTY | 59.3% vs 59.4% | 0.63 | 56.3% vs 57.5% | 0.94 |
| Prev-day range .236-.786 | BANKNIFTY | 68.2% vs 67.5% | 0.03 | 62.8% vs 62.8% | 0.51 |
| Prev-day range .236-.786 | FINNIFTY | 64.1% vs 63.9% | 0.35 | 58.5% vs 58.0% | 0.22 |
| Prev-day range .236-.786 | NIFTY | 65.3% vs 64.9% | 0.09 | 55.2% vs 55.6% | 0.74 |
| Gann 25/50/75% of prev range | BANKNIFTY | 67.7% vs 67.9% | 0.66 | 62.8% vs 62.6% | 0.46 |
| Gann 25/50/75% of prev range | FINNIFTY | 63.6% vs 64.2% | 0.84 | 57.0% vs 58.8% | 0.95 |
| Gann 25/50/75% of prev range | NIFTY | 64.8% vs 65.3% | 0.84 | 56.0% vs 55.8% | 0.47 |

| time family | index | design: marks within 5 min of a 5-min swing turn, claimed vs shifted | p | holdout | p |
|---|---|---|---|---|---|
| Fibonacci time zones | BANKNIFTY | 29.8% vs 32.7% | 1.00 | 28.6% vs 31.9% | 1.00 |
| Fibonacci time zones | FINNIFTY | 29.5% vs 32.7% | 1.00 | 29.4% vs 31.5% | 1.00 |
| Fibonacci time zones | NIFTY | 30.6% vs 33.9% | 1.00 | 31.7% vs 34.9% | 1.00 |
| Gann minutes 45/90/180 from open | BANKNIFTY | 24.9% vs 26.0% | 0.87 | 24.6% vs 26.0% | 0.79 |
| Gann minutes 45/90/180 from open | FINNIFTY | 23.8% vs 25.2% | 0.92 | 23.0% vs 25.5% | 0.92 |
| Gann minutes 45/90/180 from open | NIFTY | 24.8% vs 25.7% | 0.87 | 22.3% vs 27.4% | 1.00 |
| Gann 90/144 min from morning extreme | BANKNIFTY | 23.4% vs 23.8% | 0.62 | 26.4% vs 25.4% | 0.35 |
| Gann 90/144 min from morning extreme | FINNIFTY | 22.5% vs 22.8% | 0.60 | 26.2% vs 23.4% | 0.10 |
| Gann 90/144 min from morning extreme | NIFTY | 24.6% vs 24.9% | 0.60 | 27.0% vs 25.9% | 0.32 |

## What h37 already tested, and what R4 adds

h37 (`research/HUNT_H37.md`, `research/hunt/h37/PREREG.md`) tested 18,170 trigger variants and 864 filters. The
verdict was "equal to random", with RC / SPA p = 1.00. Exactly what it covered:
- **Swings.** ZigZag on bar highs/lows, reversal 2 or 4 × ATR14, confirmed pivots only, on 5 / 15 / 60-min and
  daily bars.
- **Fibonacci.** Bounce off, or break of, 38.2 / 50 / 61.8 / 78.6% of the last swing. Extensions 127.2 / 161.8% as
  index targets on bounces, with the stop at the swing start.
- **Gann Square of 9.** Levels (√ref ± j/4)², j = 1..4 (45-180°), ref = today's open or previous close; break or
  bounce on 5 / 15 / 60-min.
- **Gann 1x1.** A line from the last swing pivot at 0.25 or 0.5 × ATR per bar; first close through it.
- **Gann time.** Bars since the last pivot = 9, 18 … 90 or squares 4 … 81. Daily: 30 … 360 calendar days. Trade
  the reversal.
- **Harmonics.** Gartley, Bat, Butterfly, Crab and ABCD (5% tolerance), entered on the TOUCH of D, on 5 / 15 / 60 /
  daily bars.
- **Also:** Elliott, Renko, P&F, Market Profile and Wyckoff.
- **Exits.** 17 generic ones (premium points +15..+30 / -10..-20, the Liquidity arm, the ladder, time stops).

h35 tested round numbers (500 / 1,000) and classic pivots (break / retest / bounce). OBUY_GA OR-01/02 and R1's
N12 / N11 tested the 15-min and 9:20 candles.

**What h37 missed, and R4 adds:**
1. **The placebo test of the levels themselves.** h37 only compared trades with random entry minutes, never the
   level with a random level.
2. **The exact Indian retail "magic level" formulas:**
   - the unofficed / Strike.money Square-of-9 "buy above / sell below" calculators (0.125 and 0.25 √-steps, ref =
     09:25 LTP / open / previous close), with their own targets (next level × 0.9995) and stops;
   - Fibonacci pivots;
   - previous-day-range Fibonacci levels;
   - Gann 50% / quarters;
   - Gann point numbers;
   - "00 & 50" levels as magnets and as fades;
   - digital-root-9 numerology;
   - the opening range with 1.272 / 1.618 targets.
3. **Gann tools h37 did not have:**
   - the HiLo activator;
   - the 2-bar swing chart;
   - intraday angles from the opening range with the "day range per session" scale;
   - minute cycles (45 / 90 / 180 from the open, 90 / 144 from the morning extreme);
   - seasonal (equinox / solstice / cross-quarter) dates.
4. **Fibonacci tools h37 did not have:**
   - the golden pocket (0.618-0.65) with a confirmation close, stop at 78.6%, target the swing end or 127.2%;
   - the Upstox 3-min 61.8% + reversal-candle 1:1 rule;
   - Fibonacci time zones;
   - Fibonacci + round-number confluence.
5. **Harmonics:**
   - Alt Bat, Deep Crab, Shark, Cypher and 5-0;
   - 3-min bars;
   - PRZ entry on a CONFIRMATION close back out of D (not the touch);
   - the patterns' own stops (X, 1.13 / 1.27 / 1.414 / 2.0 XA);
   - the standard 0.382 / 0.618 AD targets.
6. **FINNIFTY**, at its real 0.42% spread.

Each R4 signal also got the pre-registered exits:
- NAT: the source's own index stop / target;
- OPT: a standard option exit, -25% / +50% premium, else 15:10;
- T30 (or T60): a time stop.

Point-based premium exits (+15..+30 / -10..-20 points) were h37's grid and were not repeated.

## Results of the trading versions

### All 660 design versions, by rule family

Design = NIFTY Aug 2020 - Sep 2025, BANKNIFTY and FINNIFTY Aug 2021 - Sep 2025. "Random twin" = the same exit from a
random minute within ±30 minutes of the signal on the same day, with a random side. "Lowest BH q (twin)" is over all
660 p-values against the twins.

| topic | family | variants (3 indices) | positive | trades | gross Rs/trade | net Rs/trade | random twin Rs/trade | best Rs/day | median Rs/day | lowest p twin | lowest BH q (twin) |
|---|---|---|---|---|---|---|---|---|---|---|---|
| Fibonacci | F1_FIBPIV | 18 | 3 | 13,788 | -39 | -156 | -170 | +63 | -118 | 0.004 | 0.65 |
| Fibonacci | F2_PDRFIB | 18 | 0 | 20,078 | -66 | -182 | -154 | -62 | -188 | 0.153 | 1.00 |
| Fibonacci | F3_GP | 36 | 0 | 48,227 | -62 | -177 | -177 | -56 | -221 | 0.064 | 1.00 |
| Fibonacci | F4_UPX61 | 9 | 0 | 12,414 | -80 | -194 | -158 | -157 | -245 | 0.339 | 1.00 |
| Fibonacci | F6_FTZ | 9 | 0 | 25,133 | -39 | -155 | -158 | -196 | -389 | 0.011 | 0.77 |
| Fibonacci | F7_CONF | 9 | 0 | 6,265 | -96 | -215 | -157 | -56 | -180 | 0.222 | 1.00 |
| Gann | G2_HILO | 27 | 0 | 49,577 | -68 | -183 | -154 | -110 | -357 | 0.067 | 1.00 |
| Gann | G3_SWING | 18 | 0 | 6,227 | -53 | -172 | -111 | -2 | -59 | 0.044 | 1.00 |
| Gann | G4_MID50 | 18 | 0 | 12,428 | -66 | -181 | -163 | -47 | -126 | 0.138 | 1.00 |
| Gann | G5_ANG | 18 | 2 | 18,339 | +5 | -113 | -187 | +75 | -91 | 0.006 | 0.65 |
| Gann | G6_TEXT | 9 | 0 | 6,042 | -98 | -216 | -110 | -85 | -130 | 0.384 | 1.00 |
| Gann | G6_TOPEN | 9 | 0 | 7,650 | -64 | -183 | -143 | -78 | -134 | 0.343 | 1.00 |
| Gann | G7_SEAS | 6 | 0 | 200 | -360 | -479 | +118 | -3 | -11 | 0.684 | 1.00 |
| Gann SQ9 ('magic levels') | M1_SQ9I | 36 | 0 | 36,476 | -70 | -189 | -165 | -4 | -166 | 0.017 | 1.00 |
| Gann SQ9 ('magic levels') | M2_SQ9H | 18 | 0 | 18,216 | -83 | -203 | -184 | -62 | -172 | 0.053 | 1.00 |
| Harmonic | H_ABCD | 36 | 5 | 10,927 | -111 | -227 | -180 | +70 | -67 | 0.041 | 1.00 |
| Harmonic | H_ALL | 36 | 4 | 19,013 | -73 | -187 | -175 | +45 | -101 | 0.028 | 1.00 |
| Harmonic | H_ALTBAT | 15 | 6 | 24 | +831 | +679 | +0 | +13 | -1 | 1.000 | 1.00 |
| Harmonic | H_BAT | 36 | 19 | 245 | +279 | +159 | -204 | +20 | +1 | 1.000 | 1.00 |
| Harmonic | H_BUTTERFLY | 36 | 15 | 679 | -4 | -116 | -207 | +24 | -2 | 0.057 | 1.00 |
| Harmonic | H_CRAB | 24 | 8 | 93 | -98 | -212 | -160 | +13 | -2 | 1.000 | 1.00 |
| Harmonic | H_CYPHER | 36 | 15 | 880 | +54 | -59 | -231 | +31 | -2 | 0.006 | 0.65 |
| Harmonic | H_DEEPCRAB | 36 | 9 | 504 | -147 | -261 | -247 | +18 | -3 | 0.045 | 1.00 |
| Harmonic | H_FIVE0 | 36 | 12 | 796 | -56 | -166 | -208 | +19 | -4 | 0.001 | 0.46 |
| Harmonic | H_GARTLEY | 36 | 15 | 1,128 | +26 | -87 | -118 | +14 | -1 | 0.022 | 1.00 |
| Harmonic | H_SHARK | 36 | 4 | 5,634 | -40 | -155 | -174 | +10 | -16 | 0.043 | 1.00 |
| Magic | M3_RMAG | 18 | 1 | 18,964 | -45 | -166 | -127 | +22 | -198 | 0.074 | 1.00 |
| Magic | M4_RFADE | 9 | 0 | 17,052 | -79 | -201 | -159 | -256 | -359 | 0.095 | 1.00 |
| Magic | M9_ORFIB | 12 | 0 | 11,984 | -57 | -176 | -154 | -44 | -150 | 0.021 | 1.00 |

| topic | variants | positive design Rs/day | trades | gross Rs/trade | net Rs/trade | random twin Rs/trade | net minus twin |
|---|---|---|---|---|---|---|---|
| Fibonacci | 99 | 3 | 125,905 | -59 | -175 | -166 | -9 |
| Gann | 105 | 2 | 100,463 | -56 | -172 | -155 | -18 |
| Gann SQ9 ('magic levels') | 54 | 0 | 54,692 | -74 | -194 | -172 | -22 |
| Harmonic | 363 | 112 | 39,923 | -70 | -185 | -178 | -6 |
| Magic | 39 | 1 | 48,000 | -60 | -181 | -145 | -36 |
| **all** | 660 | 118 | 368,983 | -62 | -179 | -162 | -17 |

### The 75 frozen picks, design and locked year (opened once)

Pick = the design version with the highest daily t, per family per index (≥ 30 design trades). PASS needed all of:
- design Rs/day > 0;
- BH q < 0.10;
- p against the twins < 0.05;
- RC p < 0.10;
- locked-year Rs/day > 0;
- locked-year p against the twins < 0.10.

Rs/day is over all sessions (0 on days with no trade). Worst DD is the peak-to-trough of daily equity, in rupees.

| topic | index | variant | design trades | design Rs/day | win | worst DD | p twin | BH q | RC p | holdout trades | holdout Rs/day | holdout win | holdout DD | holdout p twin | green months | pass |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| Fibonacci | BANKNIFTY | `F1_FIBPIV/bnc/OPT` | 702 | 63 | 40% | 95,804 | 0.01 | 1.00 | 1.00 | 155 | **-153** | 38% | 74,392 | 0.25 | 5/13 | FAIL |
| Fibonacci | FINNIFTY | `F1_FIBPIV/bnc/OPT` | 566 | -103 | 36% | 139,612 | 0.19 | 1.00 | 1.00 | 115 | **-395** | 31% | 98,252 | 0.91 | 4/13 | FAIL |
| Fibonacci | NIFTY | `F1_FIBPIV/bnc/T30` | 1144 | -7 | 44% | 79,736 | 0.03 | 1.00 | 1.00 | 206 | **-238** | 38% | 68,676 | 0.95 | 2/13 | FAIL |
| Fibonacci | BANKNIFTY | `F2_PDRFIB/brk/T30` | 1114 | -62 | 43% | 85,648 | 0.22 | 1.00 | 1.00 | 271 | **-6** | 47% | 27,444 | 0.08 | 6/13 | FAIL |
| Fibonacci | FINNIFTY | `F2_PDRFIB/brk/OPT` | 693 | -196 | 35% | 202,951 | 0.46 | 1.00 | 1.00 | 116 | **-159** | 40% | 52,575 | 0.60 | 3/13 | FAIL |
| Fibonacci | NIFTY | `F2_PDRFIB/bnc/OPT` | 1136 | -140 | 36% | 208,991 | 0.62 | 1.00 | 1.00 | 180 | **-61** | 38% | 31,891 | 0.50 | 5/13 | FAIL |
| Fibonacci | BANKNIFTY | `F3_GP/tf5/OPT` | 1152 | -159 | 38% | 183,705 | 0.21 | 1.00 | 1.00 | 238 | **-88** | 39% | 61,275 | 0.22 | 6/13 | FAIL |
| Fibonacci | FINNIFTY | `F3_GP/tf15/NAT127` | 386 | -94 | 34% | 93,687 | 0.17 | 1.00 | 1.00 | 91 | **-140** | 32% | 49,855 | 0.56 | 5/13 | FAIL |
| Fibonacci | NIFTY | `F3_GP/tf15/NAT` | 653 | -56 | 37% | 104,717 | 0.17 | 1.00 | 1.00 | 108 | **-17** | 38% | 27,805 | 0.34 | 7/13 | FAIL |
| Fibonacci | BANKNIFTY | `F4_UPX61/tf3/OPT` | 1115 | -190 | 37% | 223,014 | 0.34 | 1.00 | 1.00 | 230 | **17** | 44% | 44,045 | 0.10 | 6/13 | FAIL |
| Fibonacci | FINNIFTY | `F4_UPX61/tf3/OPT` | 860 | -203 | 35% | 198,945 | 0.35 | 1.00 | 1.00 | 179 | **-398** | 33% | 99,717 | 0.78 | 2/13 | FAIL |
| Fibonacci | NIFTY | `F4_UPX61/tf3/OPT` | 1382 | -269 | 34% | 348,013 | 0.91 | 1.00 | 1.00 | 268 | **-329** | 36% | 80,155 | 0.73 | 1/13 | FAIL |
| Fibonacci | BANKNIFTY | `F6_FTZ/tf5/OPT` | 2062 | -196 | 38% | 236,643 | 0.12 | 1.00 | 1.00 | 351 | **-312** | 37% | 158,142 | 0.40 | 6/13 | FAIL |
| Fibonacci | FINNIFTY | `F6_FTZ/tf5/OPT` | 1726 | -438 | 35% | 429,986 | 0.45 | 1.00 | 1.00 | 334 | **-232** | 40% | 117,395 | 0.33 | 5/13 | FAIL |
| Fibonacci | NIFTY | `F6_FTZ/tf5/T60` | 3774 | -387 | 42% | 509,268 | 0.39 | 1.00 | 1.00 | 710 | **-204** | 41% | 69,245 | 0.28 | 5/13 | FAIL |
| Fibonacci | BANKNIFTY | `F7_CONF/rnd100/OPT` | 809 | -102 | 37% | 170,165 | 0.22 | 1.00 | 1.00 | 189 | **-161** | 36% | 83,156 | 0.28 | 4/13 | FAIL |
| Fibonacci | FINNIFTY | `F7_CONF/rnd100/OPT` | 390 | -185 | 33% | 177,625 | 0.97 | 1.00 | 1.00 | 88 | **-302** | 39% | 78,126 | 0.94 | 3/13 | FAIL |
| Fibonacci | NIFTY | `F7_CONF/rnd100/NAT` | 629 | -56 | 52% | 98,334 | 0.30 | 1.00 | 1.00 | 130 | **-1** | 57% | 18,547 | 0.24 | 7/13 | FAIL |
| Gann | BANKNIFTY | `G2_HILO/tf60/NAT` | 592 | -268 | 34% | 286,147 | 0.92 | 1.00 | 1.00 | 154 | **-475** | 34% | 118,016 | 0.88 | 3/13 | FAIL |
| Gann | FINNIFTY | `G2_HILO/tf15/NAT` | 1405 | -160 | 33% | 218,138 | 0.14 | 1.00 | 1.00 | 378 | **-521** | 32% | 133,115 | 0.44 | 3/13 | FAIL |
| Gann | NIFTY | `G2_HILO/tf60/NAT` | 774 | -110 | 40% | 226,102 | 0.33 | 1.00 | 1.00 | 151 | **-71** | 39% | 51,091 | 0.25 | 5/13 | FAIL |
| Gann | BANKNIFTY | `G3_SWING/tf15/NAT` | 518 | -47 | 37% | 104,815 | 0.30 | 1.00 | 1.00 | 136 | **-186** | 45% | 65,733 | 0.48 | 4/13 | FAIL |
| Gann | FINNIFTY | `G3_SWING/tf60/NAT` | 98 | -2 | 48% | 33,331 | 0.04 | 1.00 | 1.00 | 39 | **-44** | 46% | 20,100 | 0.20 | 5/13 | FAIL |
| Gann | NIFTY | `G3_SWING/tf15/NAT` | 679 | -77 | 39% | 195,824 | 0.71 | 1.00 | 1.00 | 113 | **42** | 50% | 24,258 | 0.24 | 6/13 | FAIL |
| Gann | BANKNIFTY | `G4_MID50/bnc/NAT` | 626 | -57 | 58% | 105,924 | 0.14 | 1.00 | 1.00 | 154 | **-373** | 48% | 99,160 | 0.97 | 1/13 | FAIL |
| Gann | FINNIFTY | `G4_MID50/brk/OPT` | 485 | -47 | 38% | 52,648 | 0.47 | 1.00 | 1.00 | 92 | **-129** | 38% | 52,310 | 0.64 | 5/13 | FAIL |
| Gann | NIFTY | `G4_MID50/brk/T30` | 1030 | -66 | 41% | 106,155 | 0.41 | 1.00 | 1.00 | 149 | **-118** | 42% | 34,438 | 0.78 | 4/13 | FAIL |
| Gann | BANKNIFTY | `G5_ANG/2x1/NAT` | 991 | -97 | 30% | 221,031 | 0.15 | 1.00 | 1.00 | 247 | **-496** | 28% | 127,117 | 0.87 | 5/13 | FAIL |
| Gann | FINNIFTY | `G5_ANG/2x1/T30` | 794 | -85 | 41% | 116,106 | 0.20 | 1.00 | 1.00 | 186 | **-87** | 42% | 42,594 | 0.09 | 6/13 | FAIL |
| Gann | NIFTY | `G5_ANG/2x1/NAT` | 1270 | 75 | 34% | 148,481 | 0.01 | 1.00 | 1.00 | 239 | **-240** | 31% | 116,607 | 0.62 | 6/13 | FAIL |
| Gann | BANKNIFTY | `G6_TEXT/90-144/OPT` | 712 | -85 | 37% | 133,767 | 0.50 | 1.00 | 1.00 | 166 | **-112** | 40% | 61,382 | 0.44 | 5/13 | FAIL |
| Gann | FINNIFTY | `G6_TEXT/90-144/T60` | 503 | -155 | 39% | 161,507 | 0.96 | 1.00 | 1.00 | 109 | **-119** | 39% | 35,939 | 0.60 | 3/13 | FAIL |
| Gann | NIFTY | `G6_TEXT/90-144/T60` | 799 | -86 | 42% | 112,378 | 0.71 | 1.00 | 1.00 | 144 | **-65** | 44% | 36,579 | 0.58 | 6/13 | FAIL |
| Gann | BANKNIFTY | `G6_TOPEN/45-90-180/T60` | 876 | -78 | 42% | 113,773 | 0.52 | 1.00 | 1.00 | 211 | **-152** | 43% | 43,420 | 0.49 | 5/13 | FAIL |
| Gann | FINNIFTY | `G6_TOPEN/45-90-180/OPT` | 641 | -134 | 37% | 126,136 | 0.42 | 1.00 | 1.00 | 143 | **-68** | 44% | 51,669 | 0.28 | 5/13 | FAIL |
| Gann | NIFTY | `G6_TOPEN/45-90-180/T60` | 1033 | -124 | 42% | 178,905 | 0.64 | 1.00 | 1.00 | 188 | **-51** | 41% | 41,304 | 0.07 | 5/13 | FAIL |
| Gann | BANKNIFTY | `G7_SEAS/fade5d/OPT` | 33 | -3 | 36% | 14,911 | 0.68 | 1.00 | 1.00 | 8 | **42** | 50% | 14,568 | 1.00 | 4/13 | FAIL |
| Gann | NIFTY | `G7_SEAS/fade5d/OPT` | 41 | -5 | 32% | 17,756 | 0.78 | 1.00 | 1.00 | 7 | **-39** | 29% | 15,379 | 1.00 | 2/13 | FAIL |
| Gann SQ9 ('magic levels') | BANKNIFTY | `M1_SQ9I/r0925/NATk3` | 992 | -60 | 38% | 101,923 | 0.09 | 1.00 | 1.00 | 248 | **-171** | 36% | 61,635 | 0.29 | 5/13 | FAIL |
| Gann SQ9 ('magic levels') | FINNIFTY | `M1_SQ9I/r0925/NATk3` | 772 | -78 | 33% | 127,832 | 0.07 | 1.00 | 1.00 | 184 | **-284** | 30% | 75,645 | 0.43 | 2/13 | FAIL |
| Gann SQ9 ('magic levels') | NIFTY | `M1_SQ9I/r0925/NATk3` | 1276 | -4 | 35% | 83,765 | 0.02 | 1.00 | 1.00 | 239 | **-167** | 34% | 79,958 | 0.46 | 4/13 | FAIL |
| Gann SQ9 ('magic levels') | BANKNIFTY | `M2_SQ9H/r0925/T30` | 992 | -171 | 43% | 179,582 | 0.66 | 1.00 | 1.00 | 248 | **-368** | 44% | 95,480 | 0.93 | 2/13 | FAIL |
| Gann SQ9 ('magic levels') | FINNIFTY | `M2_SQ9H/r0925/T30` | 771 | -62 | 43% | 106,737 | 0.16 | 1.00 | 1.00 | 192 | **-121** | 44% | 34,712 | 0.18 | 5/13 | FAIL |
| Gann SQ9 ('magic levels') | NIFTY | `M2_SQ9H/r0925/T30` | 1268 | -66 | 45% | 112,773 | 0.21 | 1.00 | 1.00 | 239 | **-280** | 36% | 75,012 | 0.94 | 3/13 | FAIL |
| Harmonic | BANKNIFTY | `H_ABCD/tf15/OPT` | 147 | 70 | 45% | 42,296 | 0.04 | 1.00 | 1.00 | 30 | **-160** | 37% | 40,407 | 0.97 | 2/13 | FAIL |
| Harmonic | FINNIFTY | `H_ABCD/tf60/OPT` | 36 | -17 | 42% | 28,944 | 0.66 | 1.00 | 1.00 | 7 | **-8** | 29% | 7,252 | 1.00 | 2/13 | FAIL |
| Harmonic | NIFTY | `H_ABCD/tf60/NAT1` | 48 | 10 | 48% | 23,489 | 0.09 | 1.00 | 1.00 | 8 | **57** | 62% | 7,569 | 1.00 | 4/13 | FAIL |
| Harmonic | BANKNIFTY | `H_ALL/tf60/NAT1` | 80 | 45 | 49% | 25,372 | 0.03 | 1.00 | 1.00 | 13 | **59** | 62% | 10,584 | 1.00 | 4/13 | FAIL |
| Harmonic | FINNIFTY | `H_ALL/tf60/OPT` | 78 | -33 | 41% | 51,748 | 0.56 | 1.00 | 1.00 | 17 | **-62** | 35% | 18,461 | 1.00 | 2/13 | FAIL |
| Harmonic | NIFTY | `H_ALL/tf60/NAT1` | 104 | 8 | 49% | 27,211 | 0.18 | 1.00 | 1.00 | 15 | **57** | 40% | 8,160 | 1.00 | 5/13 | FAIL |
| Harmonic | BANKNIFTY | `H_BUTTERFLY/tf3/OPT` | 39 | 24 | 46% | 13,360 | 0.23 | 1.00 | 1.00 | 10 | **5** | 40% | 20,230 | 1.00 | 3/13 | FAIL |
| Harmonic | NIFTY | `H_BUTTERFLY/tf3/OPT` | 58 | -8 | 34% | 27,341 | 0.34 | 1.00 | 1.00 | 10 | **-1** | 30% | 4,119 | 1.00 | 3/13 | FAIL |
| Harmonic | BANKNIFTY | `H_CYPHER/tf3/NAT1` | 54 | 19 | 72% | 9,189 | 0.01 | 1.00 | 0.99 | 12 | **-1** | 50% | 4,041 | 1.00 | 5/13 | FAIL |
| Harmonic | FINNIFTY | `H_CYPHER/tf3/OPT` | 35 | -20 | 31% | 19,802 | 0.43 | 1.00 | 1.00 | 4 | **-6** | 50% | 2,204 | 1.00 | 2/13 | FAIL |
| Harmonic | NIFTY | `H_CYPHER/tf5/NAT1` | 43 | 2 | 60% | 12,220 | 0.21 | 1.00 | 1.00 | 5 | **-23** | 0% | 5,590 | 1.00 | 0/13 | FAIL |
| Harmonic | BANKNIFTY | `H_DEEPCRAB/tf3/OPT` | 32 | 18 | 53% | 7,217 | 0.16 | 1.00 | 1.00 | 8 | **-31** | 50% | 11,955 | 1.00 | 3/13 | FAIL |
| Harmonic | NIFTY | `H_DEEPCRAB/tf3/NAT1` | 36 | 3 | 56% | 6,014 | 0.14 | 1.00 | 1.00 | 12 | **-30** | 33% | 9,612 | 1.00 | 2/13 | FAIL |
| Harmonic | BANKNIFTY | `H_FIVE0/tf3/NAT1` | 47 | 7 | 64% | 3,906 | 0.00 | 1.00 | 1.00 | 9 | **1** | 67% | 2,013 | 1.00 | 5/13 | FAIL |
| Harmonic | FINNIFTY | `H_FIVE0/tf3/NAT1` | 36 | 1 | 61% | 3,544 | 0.03 | 1.00 | 1.00 | 8 | **-5** | 38% | 1,918 | 1.00 | 3/13 | FAIL |
| Harmonic | NIFTY | `H_FIVE0/tf3/OPT` | 68 | 19 | 50% | 10,047 | 0.03 | 1.00 | 1.00 | 10 | **18** | 50% | 6,365 | 1.00 | 3/13 | FAIL |
| Harmonic | BANKNIFTY | `H_GARTLEY/tf3/NAT1` | 61 | 14 | 67% | 5,395 | 0.02 | 1.00 | 1.00 | 20 | **-28** | 40% | 10,511 | 1.00 | 5/13 | FAIL |
| Harmonic | FINNIFTY | `H_GARTLEY/tf3/NAT1` | 52 | -18 | 50% | 17,135 | 0.83 | 1.00 | 1.00 | 5 | **-27** | 0% | 6,688 | 1.00 | 0/13 | FAIL |
| Harmonic | NIFTY | `H_GARTLEY/tf5/OPT` | 57 | 9 | 40% | 11,934 | 0.15 | 1.00 | 1.00 | 12 | **-21** | 33% | 8,380 | 1.00 | 3/13 | FAIL |
| Harmonic | BANKNIFTY | `H_SHARK/tf15/OPT` | 76 | 10 | 45% | 14,587 | 0.22 | 1.00 | 1.00 | 23 | **-84** | 30% | 24,482 | 1.00 | 3/13 | FAIL |
| Harmonic | FINNIFTY | `H_SHARK/tf5/NAT1` | 129 | -2 | 50% | 13,049 | 0.08 | 1.00 | 1.00 | 26 | **5** | 58% | 4,591 | 1.00 | 4/13 | FAIL |
| Harmonic | NIFTY | `H_SHARK/tf15/NAT1` | 106 | 10 | 52% | 12,071 | 0.04 | 1.00 | 1.00 | 20 | **-60** | 30% | 16,828 | 1.00 | 3/13 | FAIL |
| Magic | BANKNIFTY | `M3_RMAG/minor/T30` | 1117 | -79 | 43% | 152,060 | 0.56 | 1.00 | 1.00 | 322 | **-178** | 43% | 81,879 | 0.32 | 6/13 | FAIL |
| Magic | FINNIFTY | `M3_RMAG/major/T30` | 387 | -66 | 42% | 66,115 | 0.48 | 1.00 | 1.00 | 115 | **-7** | 47% | 14,095 | 0.42 | 4/13 | FAIL |
| Magic | NIFTY | `M3_RMAG/major/OPT` | 506 | 22 | 39% | 47,531 | 0.14 | 1.00 | 1.00 | 117 | **-55** | 38% | 38,153 | 0.26 | 5/13 | FAIL |
| Magic | BANKNIFTY | `M4_RFADE/minor/OPT` | 1100 | -368 | 34% | 395,666 | 0.92 | 1.00 | 1.00 | 246 | **-370** | 41% | 109,257 | 0.25 | 4/13 | FAIL |
| Magic | FINNIFTY | `M4_RFADE/minor/OPT` | 1370 | -279 | 36% | 316,441 | 0.21 | 1.00 | 1.00 | 298 | **-358** | 41% | 99,896 | 0.30 | 3/13 | FAIL |
| Magic | NIFTY | `M4_RFADE/minor/OPT` | 2134 | -484 | 34% | 621,046 | 0.96 | 1.00 | 1.00 | 422 | **-191** | 35% | 69,916 | 0.32 | 5/13 | FAIL |
| Magic | BANKNIFTY | `M9_ORFIB/or15/NATk162` | 970 | -224 | 53% | 233,613 | 0.60 | 1.00 | 1.00 | 236 | **-150** | 52% | 65,895 | 0.20 | 5/13 | FAIL |
| Magic | FINNIFTY | `M9_ORFIB/or15/T30` | 768 | -61 | 43% | 95,830 | 0.14 | 1.00 | 1.00 | 203 | **-317** | 39% | 82,174 | 0.86 | 3/13 | FAIL |
| Magic | NIFTY | `M9_ORFIB/or15/OPT` | 1258 | -44 | 38% | 100,652 | 0.23 | 1.00 | 1.00 | 237 | **-332** | 35% | 101,524 | 0.77 | 3/13 | FAIL |

**0 of 75 passed.** 10 were positive in the locked year:
- 6 of those had 8-26 trades. They are harmonics and the seasonal-date rule, so the sample is too small to mean
  anything.
- **NIFTY Gann swing chart, 15-min** made +Rs 42/day. It had lost Rs 77/day in design.
- **BANKNIFTY Upstox 61.8% rule** made +Rs 17/day. It had lost Rs 190/day in design.

Neither is an edge. The design favourites all flipped:

| design favourite | design Rs/day | locked year Rs/day |
|---|---|---|
| BANKNIFTY Fibonacci-pivot fade | +63 | -153 |
| NIFTY Gann 2x1 angle | +75 | -240 |
| BANKNIFTY ABCD 15-min | +70 | -160 |
| NIFTY Square-of-9, 09:25, 3rd-level target | -4 (beat its twins, p 0.02) | -167 |

## Paper-only bot: none recommended

No rule passes, and no rule is positive in both periods with enough trades. I do **not** recommend adding a paper
bot for any of these.

The Boss may want to watch the most popular one lose in real time, as a teaching tool and not as a strategy. If so,
the honest candidate is the exact Indian Square-of-9 calculator as tested (M1, ref 09:25):
- **Levels:** at 09:25 take the index close (LTP). Build the grid (floor(√LTP) - 2 + 0.125 i)².
  - buy_above = the first grid level above LTP;
  - sell_below = the grid level just below it.
- **Entry:** from 09:26 to 14:45, at the first 1-minute close ≥ buy_above, buy the 1-ITM nearest-expiry CE. At the
  first close ≤ sell_below, buy the PE instead. One trade a day.
- **Exits (CE):** stop when the index trades back to sell_below. Target = the third grid level above buy_above ×
  0.9995. Otherwise out at 15:10. Mirror for the PE.
- **Expect:** about 250 trades a year at 1 lot.
  - Design: NIFTY -Rs 4/day, BANKNIFTY -Rs 60/day, FINNIFTY -Rs 78/day.
  - Locked year: -Rs 167 / -171 / -284 per day, with drawdowns of Rs 60-80k.

## Catalog: 60 concrete variants found on the internet, and where each was tested

| # | name | exact rule (as tested or as published) | testable? | tested in | source |
|---|---|---|---|---|---|
| 1 | Square-of-9 intraday calculator (22.5°) | ref = 09:25 LTP (or pre-open VWAP / open / prev close); grid (√ref steps of 0.125)²; buy above the next level, sell below the previous; targets = next levels × 0.9995 / 1.0005; stop = the other trigger; out 15:10 | yes | **R4 M1** + placebo SQ9_125 | [unofficed strategy](https://unofficed.com/courses/backtesting-with-python-gann-square-of-9-intraday-strategy/lessons/gann-square-of-9-intraday-strategy/), [algorithm](https://unofficed.com/courses/backtesting-with-python-gann-square-of-9-intraday-strategy/lessons/defining-the-gann-square-of-9-intraday-strategys-algorithm/), [code](https://unofficed.com/courses/backtesting-with-python-gann-square-of-9-intraday-strategy/lessons/building-a-python-function-for-gann-square-of-9-calculator/) |
| 2 | Square-of-9 calculator (45°, 0.25 step) | same with 0.25 √-steps | yes | **R4 M2** + placebo SQ9_25 | [Strike.money calculator](https://www.strike.money/free-tools/gann-square-of-9-calculator) |
| 3 | SQ9 input choice (prev close / open / LTP) | the same calculator from different refs | yes | **R4 M1** (3 refs) | [TradingQnA poll](https://tradingqna.com/t/gann-square-of-9-for-intraday-which-input-value-is-preferred/41872) |
| 4 | SQ9 levels ±45°…±720° around the close (RVC script) | (√c ± k/4)² | yes | h37 SQ9 (to 180°) + placebo SQ9_25 | [TradingView RVC pivots](https://cn.tradingview.com/script/idNigv6O-RVC-Daily-Pivots) |
| 5 | SQ9 angle meanings (22.5° minor … 180° major) | rotation increments 0.125 / 0.25 / 0.5 / 1 | yes | placebo (0.125 and 0.25 grids) | [LuxAlgo SQ9](https://www.luxalgo.com/library/concept/gann-square-of-9.md) |
| 6 | Gann BankNifty level ladder: "buy only above R1 after the first 15 min" | R/S ladder from Gann angles, trade the first break after 09:30 | yes (≈ #1 with a 09:25-09:30 ref) | R4 M1 | [TradingView BN Gann levels](https://in.tradingview.com/chart/BANKNIFTY1!/5mWWKhdk-Intraday-Gann-Levels-for-BankNifty-for-2nd-July-2021) |
| 7 | Gann "magic numbers" as points from the open (45 / 90 / 144 / 180 / 360) | levels open ± n | yes | **R4 placebo GANNPTS** | [Gann theory guide](https://forexop.com/the-complete-guide-to-gann-trading-theory/) |
| 8 | Gann magic numbers as bar counts (9, 18 … 90; squares) | reversal after n bars from a pivot | yes | h37 GTC | h37 |
| 9 | Gann calendar cycles 30-360 days | reversal at n days from a daily pivot | yes | h37 GTC_D | [Traders.com Gann time](https://technical.traders.com/tradersonline/display.asp?art=6415) |
| 10 | Digital root / "Tesla 369" levels | levels where the price's digit sum reduces to 9 (= multiples of 9) or 3/6/9 | yes | **R4 placebo NUM9** | [MQL5 Tesla 369](https://www.mql5.com/en/market/product/149270) |
| 11 | Round 00 levels (100s) | support / resistance at 00 | yes | **R4 placebo RND_A**, M4 fade | [LuxAlgo round numbers](https://www.luxalgo.com/library/concept/round-numbers/) |
| 12 | "00 & 50" strikes | the same at 50s | yes | **R4 placebo RND_B** | LuxAlgo (as #11) |
| 13 | Major round levels (500 / 1,000) | break / retest / bounce | yes | h35 + R4 placebo RND_MAJ | h35 |
| 14 | Round number as a magnet | trade toward a round level 0.05-0.15% away | yes | **R4 M3** | LuxAlgo (as #11); [Osler 2003, J. Finance](https://doi.org/10.1111/1540-6261.00588) |
| 15 | Stops just beyond round numbers (Osler) | breaks of round numbers accelerate | yes | **R4 placebo** (more breaks than random in design) | [Osler summary](https://academicnewsletter.sufe.edu.cn/info/361290) |
| 16 | Fibonacci pivot points | P = (H+L+C)/3; R/S = P ± 0.382 / 0.618 / 1.0 × range | yes | **R4 F1** + placebo FIBPIV | [WealthCharts formula](https://www.wealthcharts.com/kb/category/charts/indicator-formulas/Pivot-Points-Fibonacci-Indicator-Formula), [backtrader](https://backtrader.readthedocs.io/en/latest/api/indicators/backtrader.indicators.pivotpoint.html) |
| 17 | .382 / .618 of the previous day's range | levels L + r(H - L) as support / resistance | yes | **R4 F2** + placebo PDRFIB | [LuxAlgo PD Fibonacci](https://www.luxalgo.com/library/indicator/BydYkkEj-pd-fibonacci-retrace/) |
| 18 | "Magic 15-minute candle" | 09:15-09:30 range breakout | yes | OBUY_GA OR-01, h19-h21 | [onetradejournal 15-min ORB](https://onetradejournal.com/strategies/fifteen-minute-orb-strategy) |
| 19 | 15-min candle + Fibonacci extension targets | OR break, target 1.272 / 1.618 × OR, stop mid | yes | **R4 M9** | [Fib ORB script](https://in.tradingview.com/script/Y9a3PMq6-Chews-Opening-Range-Breakout-Fibonacci), [ProRealCode FOR](https://www.prorealcode.com/prorealtime-indicators/fibonacci-opening-range-for/?pnum=2) |
| 20 | "Magic 9:20" first 5-min candle | first 5-min candle direction / break | yes | OR-02, R1 N12 / N11 | R1 |
| 21 | 15-min open vs previous high / low fade | open above PDH -> short at the 15-min close | yes | ≈ R1 N7 turtle soup / OR-07 gap fade | [TradingView GAP script](https://ru.tradingview.com/script/a5A1T5UA-GAP-by-Rishabh4) |
| 22 | "Magical levels" fixed offsets from the open on gap days | six offsets from the open | partly (offsets not published) | approximated by GANNPTS | [TradingView Nifty magical levels](https://in.tradingview.com/chart/NIFTY/2MMvu1HD-NIFTY-50-INDEX-MAGICAL-LEVELS-FOR-27-09-23) |
| 23 | "(PDH + PDL) / PDC" magic reference | formula garbled in the source | no | not testable | [TradingView Aug 2025](https://www.tradingview.com/chart/NIFTY/yJn36hFD-NIFTY-Analysis-11-AUGUST-2025-Morning-update-at-9-am) |
| 24 | Volatility-projected high / low + Fibonacci | close ± close·σ, then retracements | yes | ≈ h35 VIX band | [Scribd note](https://www.scribd.com/doc/50370679/0501) |
| 25 | Camarilla "magic" 1.1/12 multipliers | H/L/C pivots × 1.1/12 etc. | yes | OBUY_GB MR-04 / LV-03 | catalog |
| 26 | Gann HiLo activator (3-SMA high / low) | flip of the state line -> follow | yes | **R4 G2** | [LuxAlgo HiLo](https://www.luxalgo.com/library/concept/gann-hilo-activator/), [ProRealCode](https://prorealcode.com/prorealtime-indicators/gann-hilo-activator) |
| 27 | Gann 2-bar swing chart | two higher highs = up-swing; a break of the swing top = trend up | yes | **R4 G3** | [Gehtsoft Gann swing](https://docs.gehtsoftusa.com/fxcodebase-backup/backup/custom-indicators/g/gann-swing/) |
| 28 | Gann 50% "balancing point" | the midpoint of the previous range = support / resistance | yes | **R4 G4** + placebo GQTR | [Traders.com 1998](https://traders.com/Documentation/FEEDbk_docs/1998/03/Abstracts_new/Marisch/Marisch.html) |
| 29 | Gann quarters / eighths of a range | 25 / 50 / 75% levels | yes | **R4 placebo GQTR** | as #28 |
| 30 | Gann 1x1 from swing pivots, ATR scale | first close through the line | yes | h37 G1X1 | h37 |
| 31 | Gann 1x1 / 2x1 from the opening range, "one day's range per session" scale | close through the rising line from the OR low -> PE (mirror) | yes | **R4 G5** | [onetradejournal Gann fan](https://onetradejournal.com/indicators/gann-fan), [Wikipedia](https://en.wikipedia.org/wiki/Gann_angles) |
| 32 | Gann fan, daily, 50 pts/day on NIFTY | hold above 1x1 = long; a daily close below = exit | yes (daily, positional) | not tested (multi-day hold) | onetradejournal (as #31) |
| 33 | Gann minute cycles 45 / 90 / 180 from the open | reversal at those minutes | yes | **R4 G6 TOPEN** + time placebo | [Traders.com](https://technical.traders.com/tradersonline/display.asp?art=6415) |
| 34 | Gann 90 / 144 minutes from the morning extreme | reversal | yes | **R4 G6 TEXT** + time placebo | as #33 |
| 35 | Gann seasonal dates (Feb 4, Mar 21, May 6, Jun 21, Aug 8, Sep 23, Nov 7, Dec 21) | trend turns near these dates | yes (rare) | **R4 G7** | [TradingView Gann seasonal](https://in.tradingview.com/script/MXzoVr6b-Gann-Seasonal-Dates-CE), [Gann solar dates NIFTY](https://it.tradingview.com/chart/NIFTY/vIag1RTe-Gann-Solar-Dates-in-Action) |
| 36 | Square-of-9 in time (degrees -> days) | turns at 90° / 180° from a pivot date | yes | ≈ h37 GTC_D | LuxAlgo SQ9 |
| 37 | Gann astro / planetary | no objective rule | no | not testable | [TradingView Gann astro](https://www.tradingview.com/chart/GOLD/6bRDMBsY-Gann-Astro-Trading-Why-Time-is-More-Important-than-Price/) |
| 38 | Fibonacci retracement bounce of the last swing | 38.2-78.6% bounce | yes | h37 FIB_B + R4 placebo SWFIB | [Wikipedia](https://en.wikipedia.org/wiki/Fibonacci_retracement) |
| 39 | Fibonacci retracement break | close through a level | yes | h37 FIB_K | h37 |
| 40 | Extensions 127.2 / 161.8 as targets | index targets | yes | h37 FX + **R4 F3 NAT127**, M9 | [LuxAlgo fib extension](https://www.luxalgo.com/library/concept/fib-extension/) |
| 41 | Golden pocket 0.618-0.65 | enter the pocket, close back out, stop 0.786, target the swing end | yes | **R4 F3** (3 / 5 / 15-min) | [LuxAlgo golden pocket](https://www.luxalgo.com/library/concept/golden-pocket/), [Backtrex OTE](https://backtrex.com/en/blog/ict-fibonacci-golden-pocket-ote-setup) |
| 42 | ICT OTE 0.62-0.79 | entry inside the OTE zone | yes (≈ #41) | R4 F3 | Backtrex (as #41) |
| 43 | Upstox 3-min 61.8% + reversal candle | 3-min swing, 61.8% touch + reversal candle, stop just beyond, 1:1 | yes | **R4 F4** | [Upstox](https://upstox.com/market-talk/intraday-3-mins-fibonacci-retracement-short-strategy/) |
| 44 | Fibonacci time zones | 1, 2, 3, 5, 8, 13 … bars from a pivot = turn | yes | **R4 F6** + time placebo | [Babypips](https://www.babypips.com/forexpedia/fibonacci-time-zones) |
| 45 | Fibonacci + round-number confluence | a fib level within 0.05% of a 100 | yes | **R4 F7** | [Upstox confluence notes](https://upstox.com/market-talk/intraday-3-mins-fibonacci-retracement-short-strategy/) |
| 46 | Fibonacci + VWAP / OI-strike confluence | a fib level at VWAP or at a max-OI strike | partly (the index has no volume; OI strikes are round) | ≈ F7; h26 / h35 OI | as #45 |
| 47 | Previous-day Fibonacci with "15-min candle close above the level" | buy above / sell below with a 15-min close | yes | ≈ R4 F2 brk (5-min close) | [TradingView Nifty levels](https://in.tradingview.com/chart/NIFTY/qxNTltL7-NIFTY-INTRADAY-LEVELS-FOR-12-09-2023) |
| 48 | Elliott waves (Fibonacci ratios) | wave 3 / 5 / C | yes | h37 | h37 |
| 49 | Gartley | B 0.618 XA, C 0.382-0.886, D 0.786 XA, stop X | yes | **R4** (+ h37 touch) | [LuxAlgo Gartley](https://www.luxalgo.com/library/concept/gartley.md), [Harmonic Trader](https://harmonictrader.com/harmonic-patterns/) |
| 50 | Bat | B 0.382-0.50, D 0.886, stop 1.13 XA | yes | **R4** (+ h37) | [capmint glossary](https://www.capmint.com/learn/glossary/harmonic-patterns) |
| 51 | Alternate Bat | B ≤ 0.382, D 1.13, stop 1.27 XA | yes | **R4** | [TradingView auto harmonics (stops)](https://in.tradingview.com/script/7JnPdE8I) |
| 52 | Butterfly | B 0.786, D 1.272, stop 1.414 XA | yes | **R4** (+ h37) | [WinWorld harmonics](https://www.tradingview.com/script/1lVVJ22e-Harmonic-Patterns-WinWorld) |
| 53 | Crab | B 0.382-0.618, D 1.618, stop 2.0 XA | yes | **R4** (+ h37) | TradingView auto harmonics |
| 54 | Deep Crab | B 0.886, D 1.618, stop 2.0 XA | yes | **R4** | TradingView auto harmonics |
| 55 | Shark (0XABC) | AB 1.13-1.618 XA beyond X; C at 0.886-1.13 of 0X; quick 0.382-0.618 targets | yes | **R4** | [LuxAlgo Shark](https://www.luxalgo.com/library/concept/shark/) |
| 56 | Cypher | B 0.382-0.618, C 1.272-1.414 XA, D 0.786 XC, targets 0.382 / 0.618 CD | yes | **R4** | [LuxAlgo Cypher](https://www.luxalgo.com/library/concept/cypher/), [TradingView Oglesbee note](https://my.tradingview.com/chart/CADJPY/GuMCK5Ss-Cypher-The-Cypher-was-discovered-by-Darren-Oglesbee-and-though-i) |
| 57 | 5-0 | AB 1.13-1.618, BC 1.618-2.24, D = 50% of BC | yes | **R4** | [TradeNation](https://tradenation.com/articles/harmonic-patterns), [Forex.com cheat sheet](https://www.forex.com/en-au/news-and-analysis/harmonic-patterns/) |
| 58 | AB = CD | BC 0.382-0.886, CD = AB | yes | **R4** (+ h37) | as #57 |
| 59 | Harmonic + RSI / candle confirmation | PRZ + an indicator | partly | R4 uses a close-back confirmation; RSI combos are h25-type | [TradingView harmonics primer](https://in.tradingview.com/chart/XAUUSD/7SmAAfAi-What-are-Harmonic-Price-Patterns) |
| 60 | Three Drives / reciprocal ABCD | three symmetric pushes | yes | **not tested** (not coded) | Harmonic Trader |

Of the 60:
- 56 are covered by R4 or earlier studies. #22 and #46 are covered only approximately.
- 2 have no objective rule: #23 and #37.
- 2 were left out: #32 holds for days, and #60 was not coded.

Published evidence. Nobody publishes a placebo test of these levels. The few independent checks agree with ours:
- Fibonacci levels are hit no more often than other retracement values ([Wikipedia
  summary](https://en.wikipedia.org/wiki/Fibonacci_retracement)).
- A study of 40,243 corrections explains the levels by chance ([ForexOp](https://forexop.com/strategy/fibonacci-fact-or-fiction/)).
- No published harmonic or Gann backtest with costs and a random baseline was found.

## Honesty notes

- **Count of tries:**
  - 660 trading versions;
  - 105 placebo tests in design + 105 in the locked year;
  - 20 gold (XAU) tests;
  - 6 POST-HOC time tests;
  - 1 POST-HOC descriptive table (hindsight).
  The locked year was opened once, for 75 frozen picks and the placebo confirmation. Nothing was re-tuned after.
  The locked calendar year had been opened before by h35 / h37 / R1 for OTHER rules.
- **Deviations from PREREG:**
  - The golden pocket accepts any bar that reaches the pocket or deeper (but not past 78.6%) and closes back above
    61.8%.
  - Harmonics: no CD/BC check beyond what PREREG lists. Rare patterns (< 30 trades) get p = 1.
  - The pre-registered time placebo was biased. It is reported as run, and a POST-HOC fair version is shown beside it.
- **Limits:**
  - Spreads come from the h24 snapshot.
  - The OPT exit uses % premium (-25 / +50%), not points. h37 already ran the point grid.
  - FINNIFTY options are thin. Expiry days were included (as in R1).
  - Gann angle scale, swing size (2 × ATR) and harmonic tolerance (5%) are one choice each.
    - h37 used 2 and 4 × ATR, so other choices are covered there, with the same answer.
  - MCX crude has no long local minute series. Gold used XAUUSD as a stand-in for MCX gold.

### Appendix: every design version (all FAIL)

Holdout Rs/day is shown only for the 75 frozen picks. Columns: index, version, trades, design Rs/day, win %, worst
drawdown, p against the twins, BH q.

| index | variant | trades | design Rs/day | win | worst DD | p twin | BH q | holdout Rs/day | result |
|---|---|---|---|---|---|---|---|---|---|
| BANK | `F1_FIBPIV/bnc/NAT` | 722 | +2 | 46% | 103k | 0.02 | 1.00 |  | FAIL |
| FINN | `F1_FIBPIV/bnc/NAT` | 575 | -133 | 46% | 136k | 0.42 | 1.00 |  | FAIL |
| NIFT | `F1_FIBPIV/bnc/NAT` | 890 | -45 | 46% | 72k | 0.00 | 1.00 |  | FAIL |
| BANK | `F1_FIBPIV/bnc/OPT` | 702 | +63 | 40% | 96k | 0.01 | 1.00 | -153 | FAIL |
| FINN | `F1_FIBPIV/bnc/OPT` | 566 | -103 | 36% | 140k | 0.19 | 1.00 | -395 | FAIL |
| NIFT | `F1_FIBPIV/bnc/OPT` | 881 | -21 | 38% | 68k | 0.05 | 1.00 |  | FAIL |
| BANK | `F1_FIBPIV/bnc/T30` | 963 | +5 | 46% | 77k | 0.06 | 1.00 |  | FAIL |
| FINN | `F1_FIBPIV/bnc/T30` | 748 | -166 | 39% | 167k | 0.55 | 1.00 |  | FAIL |
| NIFT | `F1_FIBPIV/bnc/T30` | 1144 | -7 | 44% | 80k | 0.03 | 1.00 | -238 | FAIL |
| BANK | `F1_FIBPIV/brk/NAT` | 728 | -295 | 59% | 296k | 0.87 | 1.00 |  | FAIL |
| FINN | `F1_FIBPIV/brk/NAT` | 577 | -257 | 54% | 245k | 0.94 | 1.00 |  | FAIL |
| NIFT | `F1_FIBPIV/brk/NAT` | 906 | -180 | 61% | 243k | 0.78 | 1.00 |  | FAIL |
| BANK | `F1_FIBPIV/brk/OPT` | 720 | -235 | 36% | 253k | 0.86 | 1.00 |  | FAIL |
| FINN | `F1_FIBPIV/brk/OPT` | 569 | -192 | 34% | 200k | 0.89 | 1.00 |  | FAIL |
| NIFT | `F1_FIBPIV/brk/OPT` | 904 | -174 | 34% | 237k | 0.96 | 1.00 |  | FAIL |
| BANK | `F1_FIBPIV/brk/T30` | 725 | -89 | 43% | 140k | 0.84 | 1.00 |  | FAIL |
| FINN | `F1_FIBPIV/brk/T30` | 570 | -86 | 43% | 125k | 0.72 | 1.00 |  | FAIL |
| NIFT | `F1_FIBPIV/brk/T30` | 898 | -149 | 42% | 209k | 0.99 | 1.00 |  | FAIL |
| BANK | `F2_PDRFIB/bnc/NAT` | 1332 | -242 | 52% | 251k | 0.87 | 1.00 |  | FAIL |
| FINN | `F2_PDRFIB/bnc/NAT` | 1054 | -213 | 51% | 204k | 0.43 | 1.00 |  | FAIL |
| NIFT | `F2_PDRFIB/bnc/NAT` | 1589 | -115 | 54% | 151k | 0.15 | 1.00 |  | FAIL |
| BANK | `F2_PDRFIB/bnc/OPT` | 901 | -354 | 32% | 394k | 0.86 | 1.00 |  | FAIL |
| FINN | `F2_PDRFIB/bnc/OPT` | 717 | -293 | 33% | 281k | 0.95 | 1.00 |  | FAIL |
| NIFT | `F2_PDRFIB/bnc/OPT` | 1136 | -140 | 36% | 209k | 0.62 | 1.00 | -61 | FAIL |
| BANK | `F2_PDRFIB/bnc/T30` | 1263 | -289 | 41% | 296k | 0.97 | 1.00 |  | FAIL |
| FINN | `F2_PDRFIB/bnc/T30` | 1003 | -259 | 39% | 257k | 0.95 | 1.00 |  | FAIL |
| NIFT | `F2_PDRFIB/bnc/T30` | 1521 | -164 | 43% | 224k | 0.77 | 1.00 |  | FAIL |
| BANK | `F2_PDRFIB/brk/NAT` | 1150 | -166 | 52% | 177k | 0.31 | 1.00 |  | FAIL |
| FINN | `F2_PDRFIB/brk/NAT` | 909 | -181 | 48% | 168k | 0.46 | 1.00 |  | FAIL |
| NIFT | `F2_PDRFIB/brk/NAT` | 1415 | -109 | 53% | 150k | 0.27 | 1.00 |  | FAIL |
| BANK | `F2_PDRFIB/brk/OPT` | 864 | -137 | 37% | 160k | 0.24 | 1.00 |  | FAIL |
| FINN | `F2_PDRFIB/brk/OPT` | 693 | -196 | 35% | 203k | 0.46 | 1.00 | -159 | FAIL |
| NIFT | `F2_PDRFIB/brk/OPT` | 1148 | -197 | 34% | 267k | 0.82 | 1.00 |  | FAIL |
| BANK | `F2_PDRFIB/brk/T30` | 1114 | -62 | 43% | 86k | 0.22 | 1.00 | -6 | FAIL |
| FINN | `F2_PDRFIB/brk/T30` | 886 | -188 | 40% | 183k | 0.82 | 1.00 |  | FAIL |
| NIFT | `F2_PDRFIB/brk/T30` | 1383 | -188 | 42% | 250k | 0.91 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf15/NAT` | 504 | -132 | 36% | 143k | 0.61 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf15/NAT` | 394 | -89 | 39% | 86k | 0.33 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf15/NAT` | 653 | -56 | 37% | 105k | 0.17 | 1.00 | -17 | FAIL |
| BANK | `F3_GP/tf15/NAT127` | 497 | -123 | 33% | 132k | 0.40 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf15/NAT127` | 386 | -94 | 34% | 94k | 0.17 | 1.00 | -140 | FAIL |
| NIFT | `F3_GP/tf15/NAT127` | 640 | -63 | 33% | 105k | 0.27 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf15/OPT` | 497 | -149 | 36% | 161k | 0.88 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf15/OPT` | 391 | -124 | 38% | 126k | 0.64 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf15/OPT` | 641 | -85 | 36% | 119k | 0.36 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf15/T30` | 552 | -95 | 44% | 114k | 0.67 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf15/T30` | 412 | -123 | 38% | 121k | 0.90 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf15/T30` | 712 | -110 | 40% | 145k | 0.95 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf3/NAT` | 2270 | -338 | 34% | 342k | 0.36 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf3/NAT` | 1741 | -433 | 33% | 407k | 0.81 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf3/NAT` | 2853 | -304 | 34% | 397k | 0.53 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf3/NAT127` | 2182 | -299 | 28% | 302k | 0.15 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf3/NAT127` | 1660 | -416 | 27% | 392k | 0.72 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf3/NAT127` | 2741 | -226 | 29% | 308k | 0.18 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf3/OPT` | 1560 | -229 | 37% | 285k | 0.33 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf3/OPT` | 1195 | -314 | 35% | 324k | 0.46 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf3/OPT` | 2054 | -334 | 35% | 431k | 0.85 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf3/T30` | 2357 | -456 | 42% | 456k | 0.70 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf3/T30` | 1804 | -458 | 40% | 434k | 0.93 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf3/T30` | 2873 | -364 | 42% | 514k | 0.94 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf5/NAT` | 1411 | -281 | 35% | 291k | 0.52 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf5/NAT` | 1110 | -274 | 35% | 258k | 0.34 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf5/NAT` | 1802 | -184 | 35% | 248k | 0.22 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf5/NAT127` | 1347 | -264 | 29% | 272k | 0.30 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf5/NAT127` | 1065 | -197 | 30% | 204k | 0.06 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf5/NAT127` | 1734 | -151 | 30% | 207k | 0.12 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf5/OPT` | 1152 | -159 | 38% | 184k | 0.21 | 1.00 | -88 | FAIL |
| FINN | `F3_GP/tf5/OPT` | 871 | -262 | 34% | 257k | 0.52 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf5/OPT` | 1478 | -215 | 36% | 305k | 0.80 | 1.00 |  | FAIL |
| BANK | `F3_GP/tf5/T30` | 1554 | -254 | 44% | 263k | 0.71 | 1.00 |  | FAIL |
| FINN | `F3_GP/tf5/T30` | 1202 | -275 | 41% | 257k | 0.42 | 1.00 |  | FAIL |
| NIFT | `F3_GP/tf5/T30` | 1932 | -201 | 43% | 294k | 0.77 | 1.00 |  | FAIL |
| BANK | `F4_UPX61/tf3/NAT` | 1555 | -234 | 43% | 234k | 0.61 | 1.00 |  | FAIL |
| FINN | `F4_UPX61/tf3/NAT` | 1199 | -245 | 39% | 235k | 0.84 | 1.00 |  | FAIL |
| NIFT | `F4_UPX61/tf3/NAT` | 1877 | -157 | 45% | 204k | 0.48 | 1.00 |  | FAIL |
| BANK | `F4_UPX61/tf3/OPT` | 1115 | -190 | 37% | 223k | 0.34 | 1.00 | 17 | FAIL |
| FINN | `F4_UPX61/tf3/OPT` | 860 | -203 | 35% | 199k | 0.35 | 1.00 | -398 | FAIL |
| NIFT | `F4_UPX61/tf3/OPT` | 1382 | -269 | 34% | 348k | 0.91 | 1.00 | -329 | FAIL |
| BANK | `F4_UPX61/tf3/T30` | 1499 | -377 | 39% | 375k | 1.00 | 1.00 |  | FAIL |
| FINN | `F4_UPX61/tf3/T30` | 1155 | -309 | 39% | 287k | 0.94 | 1.00 |  | FAIL |
| NIFT | `F4_UPX61/tf3/T30` | 1772 | -289 | 41% | 368k | 0.98 | 1.00 |  | FAIL |
| BANK | `F6_FTZ/tf5/OPT` | 2062 | -196 | 38% | 237k | 0.12 | 1.00 | -312 | FAIL |
| FINN | `F6_FTZ/tf5/OPT` | 1726 | -438 | 35% | 430k | 0.45 | 1.00 | -232 | FAIL |
| NIFT | `F6_FTZ/tf5/OPT` | 2835 | -481 | 35% | 628k | 0.90 | 1.00 |  | FAIL |
| BANK | `F6_FTZ/tf5/T30` | 2983 | -204 | 44% | 235k | 0.01 | 1.00 |  | FAIL |
| FINN | `F6_FTZ/tf5/T30` | 2510 | -562 | 40% | 524k | 0.62 | 1.00 |  | FAIL |
| NIFT | `F6_FTZ/tf5/T30` | 3805 | -370 | 41% | 476k | 0.64 | 1.00 |  | FAIL |
| BANK | `F6_FTZ/tf5/T60` | 2960 | -389 | 42% | 400k | 0.47 | 1.00 |  | FAIL |
| FINN | `F6_FTZ/tf5/T60` | 2478 | -646 | 38% | 604k | 0.94 | 1.00 |  | FAIL |
| NIFT | `F6_FTZ/tf5/T60` | 3774 | -387 | 42% | 509k | 0.39 | 1.00 | -204 | FAIL |
| BANK | `F7_CONF/rnd100/NAT` | 1050 | -202 | 53% | 238k | 0.68 | 1.00 |  | FAIL |
| FINN | `F7_CONF/rnd100/NAT` | 483 | -180 | 49% | 181k | 0.83 | 1.00 |  | FAIL |
| NIFT | `F7_CONF/rnd100/NAT` | 629 | -56 | 52% | 98k | 0.30 | 1.00 | -1 | FAIL |
| BANK | `F7_CONF/rnd100/OPT` | 809 | -102 | 37% | 170k | 0.22 | 1.00 | -161 | FAIL |
| FINN | `F7_CONF/rnd100/OPT` | 390 | -185 | 33% | 178k | 0.97 | 1.00 | -302 | FAIL |
| NIFT | `F7_CONF/rnd100/OPT` | 520 | -104 | 36% | 139k | 0.87 | 1.00 |  | FAIL |
| BANK | `F7_CONF/rnd100/T30` | 1160 | -224 | 42% | 253k | 0.93 | 1.00 |  | FAIL |
| FINN | `F7_CONF/rnd100/T30` | 528 | -198 | 37% | 191k | 0.98 | 1.00 |  | FAIL |
| NIFT | `F7_CONF/rnd100/T30` | 696 | -72 | 44% | 108k | 0.78 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf15/NAT` | 1758 | -386 | 31% | 408k | 0.94 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf15/NAT` | 1405 | -160 | 33% | 218k | 0.14 | 1.00 | -521 | FAIL |
| NIFT | `G2_HILO/tf15/NAT` | 2248 | -322 | 32% | 450k | 0.90 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf15/OPT` | 1714 | -650 | 32% | 670k | 0.98 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf15/OPT` | 1326 | -302 | 36% | 306k | 0.35 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf15/OPT` | 2274 | -379 | 34% | 503k | 0.96 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf15/T30` | 2375 | -466 | 40% | 467k | 0.99 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf15/T30` | 1902 | -302 | 40% | 301k | 0.27 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf15/T30` | 3090 | -357 | 41% | 472k | 0.95 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf5/NAT` | 2967 | -503 | 29% | 515k | 0.90 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf5/NAT` | 2474 | -373 | 30% | 450k | 0.24 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf5/NAT` | 3803 | -325 | 30% | 437k | 0.78 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf5/OPT` | 2090 | -382 | 36% | 432k | 0.19 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf5/OPT` | 1721 | -364 | 36% | 400k | 0.15 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf5/OPT` | 2832 | -374 | 36% | 482k | 0.24 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf5/T30` | 2985 | -406 | 41% | 426k | 0.33 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf5/T30` | 2500 | -435 | 40% | 448k | 0.07 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf5/T30` | 3820 | -415 | 41% | 537k | 0.77 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf60/NAT` | 592 | -268 | 34% | 286k | 0.92 | 1.00 | -475 | FAIL |
| FINN | `G2_HILO/tf60/NAT` | 487 | -177 | 35% | 183k | 0.68 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf60/NAT` | 774 | -110 | 40% | 226k | 0.33 | 1.00 | -71 | FAIL |
| BANK | `G2_HILO/tf60/OPT` | 703 | -357 | 31% | 375k | 0.99 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf60/OPT` | 566 | -178 | 35% | 192k | 0.74 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf60/OPT` | 924 | -137 | 35% | 199k | 0.89 | 1.00 |  | FAIL |
| BANK | `G2_HILO/tf60/T30` | 723 | -186 | 38% | 200k | 0.94 | 1.00 |  | FAIL |
| FINN | `G2_HILO/tf60/T30` | 584 | -142 | 38% | 140k | 0.68 | 1.00 |  | FAIL |
| NIFT | `G2_HILO/tf60/T30` | 940 | -113 | 40% | 152k | 0.88 | 1.00 |  | FAIL |
| BANK | `G3_SWING/tf15/NAT` | 518 | -47 | 37% | 105k | 0.30 | 1.00 | -186 | FAIL |
| FINN | `G3_SWING/tf15/NAT` | 416 | -12 | 41% | 72k | 0.48 | 1.00 |  | FAIL |
| NIFT | `G3_SWING/tf15/NAT` | 679 | -77 | 39% | 196k | 0.71 | 1.00 | 42 | FAIL |
| BANK | `G3_SWING/tf15/OPT` | 532 | -126 | 35% | 179k | 0.93 | 1.00 |  | FAIL |
| FINN | `G3_SWING/tf15/OPT` | 436 | -27 | 39% | 93k | 0.22 | 1.00 |  | FAIL |
| NIFT | `G3_SWING/tf15/OPT` | 722 | -58 | 37% | 119k | 0.67 | 1.00 |  | FAIL |
| BANK | `G3_SWING/tf15/T30` | 552 | -81 | 43% | 131k | 0.71 | 1.00 |  | FAIL |
| FINN | `G3_SWING/tf15/T30` | 455 | -76 | 43% | 98k | 0.70 | 1.00 |  | FAIL |
| NIFT | `G3_SWING/tf15/T30` | 730 | -90 | 42% | 127k | 0.93 | 1.00 |  | FAIL |
| BANK | `G3_SWING/tf60/NAT` | 120 | -100 | 36% | 102k | 0.66 | 1.00 |  | FAIL |
| FINN | `G3_SWING/tf60/NAT` | 98 | -2 | 48% | 33k | 0.04 | 1.00 | -44 | FAIL |
| NIFT | `G3_SWING/tf60/NAT` | 177 | -60 | 41% | 101k | 0.80 | 1.00 |  | FAIL |
| BANK | `G3_SWING/tf60/OPT` | 120 | -64 | 33% | 69k | 0.82 | 1.00 |  | FAIL |
| FINN | `G3_SWING/tf60/OPT` | 98 | -6 | 39% | 35k | 0.54 | 1.00 |  | FAIL |
| NIFT | `G3_SWING/tf60/OPT` | 178 | -67 | 33% | 95k | 0.96 | 1.00 |  | FAIL |
| BANK | `G3_SWING/tf60/T30` | 120 | -14 | 45% | 24k | 0.72 | 1.00 |  | FAIL |
| FINN | `G3_SWING/tf60/T30` | 98 | -36 | 41% | 37k | 0.93 | 1.00 |  | FAIL |
| NIFT | `G3_SWING/tf60/T30` | 178 | -35 | 39% | 54k | 0.86 | 1.00 |  | FAIL |
| BANK | `G4_MID50/bnc/NAT` | 626 | -57 | 58% | 106k | 0.14 | 1.00 | -373 | FAIL |
| FINN | `G4_MID50/bnc/NAT` | 504 | -168 | 51% | 165k | 0.70 | 1.00 |  | FAIL |
| NIFT | `G4_MID50/bnc/NAT` | 759 | -136 | 53% | 197k | 0.86 | 1.00 |  | FAIL |
| BANK | `G4_MID50/bnc/OPT` | 575 | -99 | 36% | 128k | 0.21 | 1.00 |  | FAIL |
| FINN | `G4_MID50/bnc/OPT` | 467 | -149 | 36% | 157k | 0.40 | 1.00 |  | FAIL |
| NIFT | `G4_MID50/bnc/OPT` | 725 | -158 | 34% | 216k | 0.91 | 1.00 |  | FAIL |
| BANK | `G4_MID50/bnc/T30` | 790 | -82 | 43% | 130k | 0.23 | 1.00 |  | FAIL |
| FINN | `G4_MID50/bnc/T30` | 621 | -125 | 40% | 136k | 0.54 | 1.00 |  | FAIL |
| NIFT | `G4_MID50/bnc/T30` | 966 | -151 | 41% | 201k | 0.96 | 1.00 |  | FAIL |
| BANK | `G4_MID50/brk/NAT` | 659 | -161 | 55% | 169k | 0.49 | 1.00 |  | FAIL |
| FINN | `G4_MID50/brk/NAT` | 509 | -143 | 52% | 136k | 0.73 | 1.00 |  | FAIL |
| NIFT | `G4_MID50/brk/NAT` | 826 | -88 | 55% | 128k | 0.48 | 1.00 |  | FAIL |
| BANK | `G4_MID50/brk/OPT` | 629 | -196 | 35% | 222k | 0.71 | 1.00 |  | FAIL |
| FINN | `G4_MID50/brk/OPT` | 485 | -47 | 38% | 53k | 0.47 | 1.00 | -129 | FAIL |
| NIFT | `G4_MID50/brk/OPT` | 819 | -127 | 34% | 169k | 0.90 | 1.00 |  | FAIL |
| BANK | `G4_MID50/brk/T30` | 818 | -79 | 43% | 92k | 0.54 | 1.00 |  | FAIL |
| FINN | `G4_MID50/brk/T30` | 620 | -71 | 38% | 94k | 0.53 | 1.00 |  | FAIL |
| NIFT | `G4_MID50/brk/T30` | 1030 | -66 | 41% | 106k | 0.41 | 1.00 | -118 | FAIL |
| BANK | `G5_ANG/1x1/NAT` | 994 | -304 | 22% | 324k | 0.65 | 1.00 |  | FAIL |
| FINN | `G5_ANG/1x1/NAT` | 791 | -362 | 23% | 355k | 0.81 | 1.00 |  | FAIL |
| NIFT | `G5_ANG/1x1/NAT` | 1273 | -27 | 27% | 181k | 0.13 | 1.00 |  | FAIL |
| BANK | `G5_ANG/1x1/OPT` | 994 | -217 | 36% | 236k | 0.44 | 1.00 |  | FAIL |
| FINN | `G5_ANG/1x1/OPT` | 791 | -212 | 37% | 219k | 0.47 | 1.00 |  | FAIL |
| NIFT | `G5_ANG/1x1/OPT` | 1273 | -23 | 39% | 138k | 0.09 | 1.00 |  | FAIL |
| BANK | `G5_ANG/1x1/T30` | 994 | -72 | 43% | 116k | 0.46 | 1.00 |  | FAIL |
| FINN | `G5_ANG/1x1/T30` | 791 | -162 | 41% | 189k | 0.52 | 1.00 |  | FAIL |
| NIFT | `G5_ANG/1x1/T30` | 1273 | -34 | 46% | 73k | 0.08 | 1.00 |  | FAIL |
| BANK | `G5_ANG/2x1/NAT` | 991 | -97 | 30% | 221k | 0.15 | 1.00 | -496 | FAIL |
| FINN | `G5_ANG/2x1/NAT` | 794 | -245 | 28% | 263k | 0.46 | 1.00 |  | FAIL |
| NIFT | `G5_ANG/2x1/NAT` | 1270 | +75 | 34% | 148k | 0.01 | 1.00 | -240 | FAIL |
| BANK | `G5_ANG/2x1/OPT` | 991 | -172 | 37% | 193k | 0.16 | 1.00 |  | FAIL |
| FINN | `G5_ANG/2x1/OPT` | 794 | -148 | 36% | 189k | 0.17 | 1.00 |  | FAIL |
| NIFT | `G5_ANG/2x1/OPT` | 1270 | +36 | 40% | 84k | 0.01 | 1.00 |  | FAIL |
| BANK | `G5_ANG/2x1/T30` | 991 | -56 | 43% | 112k | 0.36 | 1.00 |  | FAIL |
| FINN | `G5_ANG/2x1/T30` | 794 | -85 | 41% | 116k | 0.20 | 1.00 | -87 | FAIL |
| NIFT | `G5_ANG/2x1/T30` | 1270 | -56 | 44% | 94k | 0.17 | 1.00 |  | FAIL |
| BANK | `G6_TEXT/90-144/OPT` | 712 | -85 | 37% | 134k | 0.50 | 1.00 | -112 | FAIL |
| FINN | `G6_TEXT/90-144/OPT` | 503 | -218 | 35% | 243k | 0.95 | 1.00 |  | FAIL |
| NIFT | `G6_TEXT/90-144/OPT` | 799 | -163 | 34% | 232k | 0.99 | 1.00 |  | FAIL |
| BANK | `G6_TEXT/90-144/T30` | 712 | -130 | 41% | 135k | 0.85 | 1.00 |  | FAIL |
| FINN | `G6_TEXT/90-144/T30` | 503 | -108 | 38% | 106k | 0.38 | 1.00 |  | FAIL |
| NIFT | `G6_TEXT/90-144/T30` | 799 | -89 | 44% | 122k | 0.93 | 1.00 |  | FAIL |
| BANK | `G6_TEXT/90-144/T60` | 712 | -216 | 40% | 221k | 0.99 | 1.00 |  | FAIL |
| FINN | `G6_TEXT/90-144/T60` | 503 | -155 | 39% | 162k | 0.96 | 1.00 | -119 | FAIL |
| NIFT | `G6_TEXT/90-144/T60` | 799 | -86 | 42% | 112k | 0.71 | 1.00 | -65 | FAIL |
| BANK | `G6_TOPEN/45-90-180/OPT` | 876 | -158 | 37% | 223k | 0.37 | 1.00 |  | FAIL |
| FINN | `G6_TOPEN/45-90-180/OPT` | 641 | -134 | 37% | 126k | 0.42 | 1.00 | -68 | FAIL |
| NIFT | `G6_TOPEN/45-90-180/OPT` | 1033 | -277 | 31% | 370k | 1.00 | 1.00 |  | FAIL |
| BANK | `G6_TOPEN/45-90-180/T30` | 876 | -103 | 42% | 121k | 0.34 | 1.00 |  | FAIL |
| FINN | `G6_TOPEN/45-90-180/T30` | 641 | -147 | 40% | 137k | 0.77 | 1.00 |  | FAIL |
| NIFT | `G6_TOPEN/45-90-180/T30` | 1033 | -129 | 40% | 173k | 0.85 | 1.00 |  | FAIL |
| BANK | `G6_TOPEN/45-90-180/T60` | 876 | -78 | 42% | 114k | 0.52 | 1.00 | -152 | FAIL |
| FINN | `G6_TOPEN/45-90-180/T60` | 641 | -135 | 42% | 126k | 0.74 | 1.00 |  | FAIL |
| NIFT | `G6_TOPEN/45-90-180/T60` | 1033 | -124 | 42% | 179k | 0.64 | 1.00 | -51 | FAIL |
| BANK | `G7_SEAS/fade5d/EOD` | 33 | -49 | 36% | 48k | 0.94 | 1.00 |  | FAIL |
| FINN | `G7_SEAS/fade5d/EOD` | 26 | -15 | 42% | 30k | 1.00 | 1.00 |  | FAIL |
| NIFT | `G7_SEAS/fade5d/EOD` | 41 | -11 | 41% | 47k | 0.73 | 1.00 |  | FAIL |
| BANK | `G7_SEAS/fade5d/OPT` | 33 | -3 | 36% | 15k | 0.68 | 1.00 | 42 | FAIL |
| FINN | `G7_SEAS/fade5d/OPT` | 26 | -11 | 35% | 23k | 1.00 | 1.00 |  | FAIL |
| NIFT | `G7_SEAS/fade5d/OPT` | 41 | -5 | 32% | 18k | 0.78 | 1.00 | -39 | FAIL |
| BANK | `H_ABCD/tf15/NAT1` | 148 | -26 | 45% | 63k | 0.29 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf15/NAT1` | 105 | -70 | 43% | 67k | 0.97 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf15/NAT1` | 178 | -28 | 45% | 46k | 0.52 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf15/NAT2` | 148 | +5 | 41% | 66k | 0.11 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf15/NAT2` | 104 | -85 | 35% | 82k | 0.95 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf15/NAT2` | 178 | -47 | 34% | 65k | 0.54 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf15/OPT` | 147 | +70 | 45% | 42k | 0.04 | 1.00 | -160 | FAIL |
| FINN | `H_ABCD/tf15/OPT` | 103 | -72 | 37% | 79k | 0.97 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf15/OPT` | 177 | -51 | 35% | 71k | 0.78 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf3/NAT1` | 703 | -127 | 48% | 132k | 0.79 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf3/NAT1` | 495 | -148 | 42% | 141k | 0.93 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf3/NAT1` | 900 | -86 | 45% | 116k | 0.52 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf3/NAT2` | 685 | -124 | 36% | 126k | 0.64 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf3/NAT2` | 489 | -115 | 36% | 118k | 0.40 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf3/NAT2` | 883 | -80 | 35% | 108k | 0.39 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf3/OPT` | 625 | -106 | 37% | 127k | 0.34 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf3/OPT` | 441 | -237 | 34% | 224k | 0.99 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf3/OPT` | 798 | -125 | 37% | 172k | 0.87 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf5/NAT1` | 363 | -101 | 45% | 107k | 0.93 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf5/NAT1` | 274 | -127 | 40% | 118k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf5/NAT1` | 463 | -30 | 49% | 47k | 0.42 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf5/NAT2` | 359 | -137 | 32% | 140k | 0.78 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf5/NAT2` | 271 | -154 | 31% | 143k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf5/NAT2` | 456 | -59 | 34% | 77k | 0.60 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf5/OPT` | 341 | -81 | 35% | 104k | 0.64 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf5/OPT` | 260 | -137 | 34% | 135k | 0.74 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf5/OPT` | 446 | -64 | 36% | 86k | 0.86 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf60/NAT1` | 45 | +23 | 47% | 22k | 0.14 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf60/NAT1` | 36 | -42 | 39% | 52k | 0.87 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf60/NAT1` | 48 | +10 | 48% | 23k | 0.09 | 1.00 | 57 | FAIL |
| BANK | `H_ABCD/tf60/NAT2` | 45 | -1 | 38% | 49k | 0.31 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf60/NAT2` | 36 | -41 | 39% | 54k | 0.76 | 1.00 |  | FAIL |
| NIFT | `H_ABCD/tf60/NAT2` | 48 | +12 | 44% | 31k | 0.16 | 1.00 |  | FAIL |
| BANK | `H_ABCD/tf60/OPT` | 45 | -12 | 31% | 32k | 0.37 | 1.00 |  | FAIL |
| FINN | `H_ABCD/tf60/OPT` | 36 | -17 | 42% | 29k | 0.66 | 1.00 | -8 | FAIL |
| NIFT | `H_ABCD/tf60/OPT` | 48 | -5 | 40% | 20k | 0.43 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf15/NAT1` | 280 | -66 | 44% | 85k | 0.47 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf15/NAT1` | 216 | -86 | 44% | 82k | 0.86 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf15/NAT1` | 344 | -35 | 47% | 74k | 0.23 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf15/NAT2` | 276 | -24 | 38% | 71k | 0.36 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf15/NAT2` | 213 | -122 | 39% | 116k | 0.88 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf15/NAT2` | 342 | -60 | 38% | 82k | 0.45 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf15/OPT` | 269 | +45 | 41% | 50k | 0.07 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf15/OPT` | 206 | -94 | 37% | 100k | 0.82 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf15/OPT` | 342 | -65 | 38% | 89k | 0.68 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf3/NAT1` | 1231 | -127 | 50% | 144k | 0.30 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf3/NAT1` | 877 | -207 | 44% | 195k | 0.84 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf3/NAT1` | 1589 | -118 | 48% | 164k | 0.33 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf3/NAT2` | 1184 | -158 | 39% | 174k | 0.21 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf3/NAT2` | 856 | -201 | 37% | 192k | 0.44 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf3/NAT2` | 1540 | -128 | 37% | 173k | 0.15 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf3/OPT` | 948 | -149 | 38% | 172k | 0.19 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf3/OPT` | 696 | -349 | 32% | 330k | 0.97 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf3/OPT` | 1261 | -163 | 37% | 217k | 0.53 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf5/NAT1` | 632 | -113 | 48% | 137k | 0.58 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf5/NAT1` | 472 | -157 | 44% | 146k | 0.98 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf5/NAT1` | 828 | -83 | 49% | 116k | 0.65 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf5/NAT2` | 623 | -162 | 35% | 183k | 0.65 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf5/NAT2` | 464 | -203 | 33% | 189k | 0.98 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf5/NAT2` | 815 | -136 | 34% | 179k | 0.83 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf5/OPT` | 566 | -119 | 38% | 197k | 0.84 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf5/OPT` | 418 | -172 | 36% | 162k | 0.98 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf5/OPT` | 736 | -108 | 37% | 147k | 0.87 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf60/NAT1` | 80 | +45 | 49% | 25k | 0.03 | 1.00 | 59 | FAIL |
| FINN | `H_ALL/tf60/NAT1` | 78 | -54 | 42% | 70k | 0.58 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf60/NAT1` | 104 | +8 | 49% | 27k | 0.18 | 1.00 | 57 | FAIL |
| BANK | `H_ALL/tf60/NAT2` | 80 | +34 | 44% | 36k | 0.18 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf60/NAT2` | 78 | -54 | 41% | 72k | 0.67 | 1.00 |  | FAIL |
| NIFT | `H_ALL/tf60/NAT2` | 104 | -8 | 43% | 41k | 0.22 | 1.00 |  | FAIL |
| BANK | `H_ALL/tf60/OPT` | 80 | -1 | 35% | 43k | 0.41 | 1.00 |  | FAIL |
| FINN | `H_ALL/tf60/OPT` | 78 | -33 | 41% | 52k | 0.56 | 1.00 | -62 | FAIL |
| NIFT | `H_ALL/tf60/OPT` | 107 | -17 | 41% | 28k | 0.39 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf3/NAT1` | 2 | +0 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_ALTBAT/tf3/NAT1` | 2 | -2 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_ALTBAT/tf3/NAT1` | 2 | -0 | 50% | 1k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf3/NAT2` | 2 | +1 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_ALTBAT/tf3/NAT2` | 2 | -1 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_ALTBAT/tf3/NAT2` | 2 | -1 | 0% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf3/OPT` | 2 | -7 | 0% | 7k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_ALTBAT/tf3/OPT` | 2 | +9 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_ALTBAT/tf3/OPT` | 2 | -3 | 0% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf5/NAT1` | 1 | +7 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf5/NAT2` | 1 | +13 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf5/OPT` | 1 | +3 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf60/NAT1` | 1 | -1 | 0% | 1k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf60/NAT2` | 1 | -1 | 0% | 1k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_ALTBAT/tf60/OPT` | 1 | -1 | 0% | 1k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf15/NAT1` | 4 | +11 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf15/NAT1` | 5 | +7 | 80% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf15/NAT1` | 4 | -5 | 25% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf15/NAT2` | 4 | +15 | 75% | 1k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf15/NAT2` | 5 | +14 | 80% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf15/NAT2` | 4 | -5 | 25% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf15/OPT` | 4 | +2 | 50% | 4k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf15/OPT` | 5 | +7 | 80% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf15/OPT` | 4 | -4 | 25% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf3/NAT1` | 20 | -9 | 45% | 13k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf3/NAT1` | 12 | -1 | 50% | 4k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf3/NAT1` | 15 | +1 | 60% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf3/NAT2` | 20 | -13 | 35% | 17k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf3/NAT2` | 12 | +2 | 42% | 3k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf3/NAT2` | 15 | +3 | 47% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf3/OPT` | 19 | +3 | 42% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf3/OPT` | 12 | -8 | 25% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf3/OPT` | 15 | +11 | 60% | 6k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf5/NAT1` | 1 | +4 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf5/NAT1` | 6 | -4 | 50% | 5k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf5/NAT1` | 5 | -4 | 0% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf5/NAT2` | 1 | -4 | 0% | 4k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf5/NAT2` | 6 | -6 | 33% | 6k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf5/NAT2` | 5 | -4 | 0% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf5/OPT` | 1 | -1 | 0% | 1k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf5/OPT` | 6 | +7 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf5/OPT` | 5 | -3 | 20% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf60/NAT1` | 4 | +10 | 75% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf60/NAT1` | 1 | +1 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf60/NAT1` | 5 | -5 | 40% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf60/NAT2` | 4 | +20 | 75% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf60/NAT2` | 1 | +1 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf60/NAT2` | 5 | -8 | 40% | 10k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BAT/tf60/OPT` | 4 | +10 | 75% | 3k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BAT/tf60/OPT` | 1 | +4 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BAT/tf60/OPT` | 5 | -2 | 60% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf15/NAT1` | 11 | -12 | 18% | 12k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf15/NAT1` | 7 | -6 | 29% | 6k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf15/NAT1` | 16 | -4 | 38% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf15/NAT2` | 11 | -10 | 18% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf15/NAT2` | 7 | -12 | 14% | 11k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf15/NAT2` | 16 | -2 | 38% | 8k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf15/OPT` | 11 | -20 | 9% | 20k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf15/OPT` | 7 | -17 | 14% | 15k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf15/OPT` | 16 | -3 | 31% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf3/NAT1` | 39 | +2 | 49% | 9k | 0.17 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf3/NAT1` | 29 | +9 | 66% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf3/NAT1` | 58 | -7 | 36% | 15k | 0.63 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf3/NAT2` | 39 | +5 | 41% | 9k | 0.06 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf3/NAT2` | 29 | +2 | 41% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf3/NAT2` | 58 | -8 | 26% | 15k | 0.56 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf3/OPT` | 39 | +24 | 46% | 13k | 0.23 | 1.00 | 5 | FAIL |
| FINN | `H_BUTTERFLY/tf3/OPT` | 28 | -3 | 39% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf3/OPT` | 58 | -8 | 34% | 27k | 0.34 | 1.00 | -1 | FAIL |
| BANK | `H_BUTTERFLY/tf5/NAT1` | 22 | -1 | 41% | 5k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf5/NAT1` | 12 | -9 | 25% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf5/NAT1` | 27 | +3 | 52% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf5/NAT2` | 22 | -3 | 27% | 11k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf5/NAT2` | 12 | -10 | 8% | 10k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf5/NAT2` | 27 | -2 | 30% | 9k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf5/OPT` | 21 | -6 | 38% | 17k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf5/OPT` | 12 | -10 | 17% | 16k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf5/OPT` | 27 | -1 | 30% | 10k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf60/NAT1` | 2 | +1 | 50% | 1k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf60/NAT1` | 2 | +4 | 50% | 3k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf60/NAT1` | 2 | +5 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf60/NAT2` | 2 | +1 | 50% | 1k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf60/NAT2` | 2 | +4 | 50% | 3k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf60/NAT2` | 2 | +9 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_BUTTERFLY/tf60/OPT` | 2 | +1 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_BUTTERFLY/tf60/OPT` | 2 | +5 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_BUTTERFLY/tf60/OPT` | 2 | +2 | 50% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf15/NAT1` | 4 | -3 | 50% | 4k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf15/NAT1` | 2 | -4 | 50% | 5k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf15/NAT1` | 2 | -1 | 50% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf15/NAT2` | 4 | -9 | 25% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf15/NAT2` | 2 | -4 | 50% | 5k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf15/NAT2` | 2 | -4 | 0% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf15/OPT` | 4 | -5 | 50% | 6k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf15/OPT` | 2 | -0 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf15/OPT` | 2 | -3 | 0% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf3/NAT1` | 4 | +0 | 75% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf3/NAT1` | 3 | +7 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf3/NAT1` | 9 | -2 | 33% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf3/NAT2` | 4 | +1 | 75% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf3/NAT2` | 3 | +13 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf3/NAT2` | 9 | -1 | 33% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf3/OPT` | 4 | -3 | 25% | 3k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CRAB/tf3/OPT` | 3 | +12 | 67% | 1k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf3/OPT` | 9 | +5 | 44% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf5/NAT1` | 2 | -6 | 0% | 6k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf5/NAT1` | 5 | -1 | 60% | 6k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf5/NAT2` | 2 | -6 | 0% | 6k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf5/NAT2` | 5 | +2 | 60% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CRAB/tf5/OPT` | 2 | -7 | 0% | 7k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CRAB/tf5/OPT` | 5 | +1 | 60% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf15/NAT1` | 8 | -8 | 25% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf15/NAT1` | 6 | +5 | 67% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf15/NAT1` | 12 | +7 | 67% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf15/NAT2` | 8 | -6 | 25% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf15/NAT2` | 6 | +6 | 67% | 2k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf15/NAT2` | 12 | +13 | 67% | 6k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf15/OPT` | 8 | -6 | 12% | 11k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf15/OPT` | 6 | -1 | 17% | 4k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf15/OPT` | 12 | +5 | 50% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf3/NAT1` | 54 | +19 | 72% | 9k | 0.01 | 1.00 | -1 | FAIL |
| FINN | `H_CYPHER/tf3/NAT1` | 35 | -11 | 51% | 11k | 0.58 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf3/NAT1` | 73 | -14 | 44% | 24k | 0.51 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf3/NAT2` | 54 | +14 | 54% | 11k | 0.06 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf3/NAT2` | 35 | -14 | 43% | 14k | 0.52 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf3/NAT2` | 73 | -15 | 40% | 21k | 0.74 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf3/OPT` | 53 | +31 | 53% | 10k | 0.01 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf3/OPT` | 35 | -20 | 31% | 20k | 0.43 | 1.00 | -6 | FAIL |
| NIFT | `H_CYPHER/tf3/OPT` | 72 | -33 | 33% | 48k | 0.93 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf5/NAT1` | 24 | +11 | 67% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf5/NAT1` | 27 | -10 | 44% | 11k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf5/NAT1` | 43 | +2 | 60% | 12k | 0.21 | 1.00 | -23 | FAIL |
| BANK | `H_CYPHER/tf5/NAT2` | 24 | +14 | 46% | 14k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf5/NAT2` | 27 | -2 | 44% | 10k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf5/NAT2` | 43 | -13 | 35% | 19k | 0.63 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf5/OPT` | 24 | +20 | 58% | 7k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf5/OPT` | 27 | -2 | 44% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf5/OPT` | 43 | -7 | 30% | 20k | 0.47 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf60/NAT1` | 1 | +1 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf60/NAT1` | 4 | -5 | 25% | 10k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf60/NAT1` | 7 | -4 | 57% | 9k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf60/NAT2` | 1 | +1 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf60/NAT2` | 4 | -5 | 25% | 10k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf60/NAT2` | 7 | -2 | 57% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_CYPHER/tf60/OPT` | 1 | -4 | 0% | 4k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_CYPHER/tf60/OPT` | 4 | -8 | 0% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_CYPHER/tf60/OPT` | 7 | +1 | 57% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf15/NAT1` | 13 | -2 | 46% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf15/NAT1` | 4 | -9 | 0% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf15/NAT1` | 14 | -12 | 21% | 21k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf15/NAT2` | 13 | -5 | 31% | 18k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf15/NAT2` | 4 | -9 | 0% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf15/NAT2` | 14 | -10 | 29% | 19k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf15/OPT` | 13 | -1 | 31% | 16k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf15/OPT` | 4 | -10 | 0% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf15/OPT` | 14 | -6 | 29% | 9k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf3/NAT1` | 32 | +6 | 59% | 6k | 0.04 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf3/NAT1` | 21 | -2 | 57% | 5k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf3/NAT1` | 36 | +3 | 56% | 6k | 0.14 | 1.00 | -30 | FAIL |
| BANK | `H_DEEPCRAB/tf3/NAT2` | 32 | -4 | 41% | 11k | 0.42 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf3/NAT2` | 21 | -12 | 33% | 13k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf3/NAT2` | 36 | -3 | 39% | 13k | 0.21 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf3/OPT` | 32 | +18 | 53% | 7k | 0.16 | 1.00 | -31 | FAIL |
| FINN | `H_DEEPCRAB/tf3/OPT` | 21 | -13 | 33% | 15k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf3/OPT` | 36 | -1 | 42% | 13k | 0.58 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf5/NAT1` | 10 | -0 | 50% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf5/NAT1` | 5 | -3 | 40% | 4k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf5/NAT1` | 23 | -10 | 30% | 13k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf5/NAT2` | 10 | +6 | 50% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf5/NAT2` | 5 | -2 | 40% | 4k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf5/NAT2` | 23 | -14 | 22% | 17k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf5/OPT` | 10 | +5 | 60% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf5/OPT` | 5 | -6 | 40% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf5/OPT` | 23 | -26 | 17% | 33k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf60/NAT1` | 5 | -3 | 60% | 11k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf60/NAT1` | 1 | -0 | 0% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf60/NAT1` | 4 | +5 | 75% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf60/NAT2` | 5 | -5 | 60% | 11k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf60/NAT2` | 1 | -0 | 0% | 0k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf60/NAT2` | 4 | +0 | 75% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_DEEPCRAB/tf60/OPT` | 5 | +4 | 60% | 5k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_DEEPCRAB/tf60/OPT` | 1 | -2 | 0% | 1k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_DEEPCRAB/tf60/OPT` | 4 | +5 | 75% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf15/NAT1` | 9 | -7 | 33% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf15/NAT1` | 12 | -8 | 33% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf15/NAT1` | 13 | -5 | 46% | 6k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf15/NAT2` | 9 | -17 | 11% | 17k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf15/NAT2` | 12 | -9 | 42% | 9k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf15/NAT2` | 13 | -7 | 38% | 9k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf15/OPT` | 9 | -14 | 11% | 14k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf15/OPT` | 12 | +1 | 42% | 7k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf15/OPT` | 13 | -2 | 31% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf3/NAT1` | 47 | +7 | 64% | 4k | 0.00 | 1.00 | 1 | FAIL |
| FINN | `H_FIVE0/tf3/NAT1` | 36 | +1 | 61% | 4k | 0.03 | 1.00 | -5 | FAIL |
| NIFT | `H_FIVE0/tf3/NAT1` | 68 | +6 | 72% | 3k | 0.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf3/NAT2` | 47 | +1 | 66% | 13k | 0.13 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf3/NAT2` | 36 | -3 | 56% | 6k | 0.18 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf3/NAT2` | 68 | +6 | 66% | 7k | 0.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf3/OPT` | 47 | -27 | 34% | 27k | 0.82 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf3/OPT` | 34 | +3 | 32% | 16k | 0.21 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf3/OPT` | 68 | +19 | 50% | 10k | 0.03 | 1.00 | 18 | FAIL |
| BANK | `H_FIVE0/tf5/NAT1` | 15 | +1 | 80% | 4k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf5/NAT1` | 13 | -11 | 46% | 10k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf5/NAT1` | 39 | -8 | 62% | 13k | 0.81 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf5/NAT2` | 15 | -6 | 47% | 10k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf5/NAT2` | 13 | -14 | 31% | 13k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf5/NAT2` | 39 | -15 | 46% | 20k | 0.90 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf5/OPT` | 15 | -11 | 33% | 15k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf5/OPT` | 13 | -13 | 23% | 15k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf5/OPT` | 39 | -4 | 38% | 24k | 0.68 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf60/NAT1` | 4 | +11 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf60/NAT1` | 5 | -2 | 60% | 6k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf60/NAT1` | 5 | -2 | 60% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf60/NAT2` | 4 | +15 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf60/NAT2` | 5 | -5 | 40% | 7k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf60/NAT2` | 5 | -5 | 40% | 7k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_FIVE0/tf60/OPT` | 4 | +5 | 50% | 2k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_FIVE0/tf60/OPT` | 5 | -2 | 40% | 8k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_FIVE0/tf60/OPT` | 5 | -8 | 20% | 10k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf15/NAT1` | 12 | +3 | 50% | 12k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf15/NAT1` | 12 | +3 | 58% | 3k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf15/NAT1` | 8 | +11 | 88% | 2k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf15/NAT2` | 12 | -3 | 50% | 18k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf15/NAT2` | 12 | -19 | 17% | 18k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf15/NAT2` | 8 | +6 | 62% | 3k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf15/OPT` | 12 | +12 | 50% | 9k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf15/OPT` | 12 | -13 | 25% | 15k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf15/OPT` | 8 | +6 | 62% | 4k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf3/NAT1` | 61 | +14 | 67% | 5k | 0.02 | 1.00 | -28 | FAIL |
| FINN | `H_GARTLEY/tf3/NAT1` | 52 | -18 | 50% | 17k | 0.83 | 1.00 | -27 | FAIL |
| NIFT | `H_GARTLEY/tf3/NAT1` | 93 | -2 | 56% | 14k | 0.35 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf3/NAT2` | 61 | -8 | 43% | 15k | 0.71 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf3/NAT2` | 52 | -28 | 33% | 27k | 0.98 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf3/NAT2` | 93 | -9 | 41% | 21k | 0.64 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf3/OPT` | 61 | -9 | 41% | 27k | 0.47 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf3/OPT` | 51 | -50 | 27% | 46k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf3/OPT` | 91 | -15 | 36% | 33k | 0.35 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf5/NAT1` | 45 | -3 | 58% | 14k | 0.33 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf5/NAT1` | 23 | +0 | 61% | 5k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf5/NAT1` | 58 | -3 | 53% | 14k | 0.47 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf5/NAT2` | 44 | -12 | 43% | 15k | 0.45 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf5/NAT2` | 23 | -7 | 39% | 11k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf5/NAT2` | 58 | -1 | 40% | 14k | 0.52 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf5/OPT` | 44 | -6 | 43% | 18k | 0.62 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf5/OPT` | 23 | -3 | 35% | 17k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf5/OPT` | 57 | +9 | 40% | 12k | 0.15 | 1.00 | -21 | FAIL |
| BANK | `H_GARTLEY/tf60/NAT1` | 1 | +6 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf60/NAT1` | 6 | -0 | 83% | 7k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf60/NAT1` | 7 | +8 | 57% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf60/NAT2` | 1 | +4 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf60/NAT2` | 6 | -1 | 83% | 7k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf60/NAT2` | 7 | +11 | 57% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_GARTLEY/tf60/OPT` | 1 | +4 | 100% | 0k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_GARTLEY/tf60/OPT` | 6 | -0 | 67% | 3k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_GARTLEY/tf60/OPT` | 7 | +0 | 29% | 5k | 1.00 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf15/NAT1` | 80 | -31 | 42% | 31k | 0.90 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf15/NAT1` | 70 | -10 | 44% | 24k | 0.37 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf15/NAT1` | 106 | +10 | 52% | 12k | 0.04 | 1.00 | -60 | FAIL |
| BANK | `H_SHARK/tf15/NAT2` | 80 | -6 | 38% | 22k | 0.65 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf15/NAT2` | 70 | -13 | 44% | 22k | 0.40 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf15/NAT2` | 105 | +9 | 45% | 18k | 0.07 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf15/OPT` | 76 | +10 | 45% | 15k | 0.22 | 1.00 | -84 | FAIL |
| FINN | `H_SHARK/tf15/OPT` | 67 | -12 | 39% | 23k | 0.52 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf15/OPT` | 104 | +0 | 46% | 16k | 0.22 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf3/NAT1` | 360 | -37 | 49% | 41k | 0.26 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf3/NAT1` | 258 | -66 | 39% | 62k | 0.90 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf3/NAT1` | 452 | -28 | 49% | 41k | 0.36 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf3/NAT2` | 359 | -46 | 38% | 55k | 0.31 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf3/NAT2` | 257 | -68 | 34% | 63k | 0.62 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf3/NAT2` | 448 | -36 | 40% | 58k | 0.14 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf3/OPT` | 322 | -37 | 42% | 62k | 0.20 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf3/OPT` | 234 | -97 | 32% | 95k | 0.83 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf3/OPT` | 413 | -47 | 38% | 94k | 0.33 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf5/NAT1` | 185 | -27 | 47% | 46k | 0.69 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf5/NAT1` | 129 | -2 | 50% | 13k | 0.08 | 1.00 | 5 | FAIL |
| NIFT | `H_SHARK/tf5/NAT1` | 217 | -33 | 46% | 46k | 0.87 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf5/NAT2` | 183 | -35 | 37% | 46k | 0.61 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf5/NAT2` | 128 | -14 | 38% | 23k | 0.16 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf5/NAT2` | 217 | -32 | 34% | 55k | 0.51 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf5/OPT` | 168 | -63 | 38% | 91k | 0.95 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf5/OPT` | 123 | -16 | 41% | 30k | 0.19 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf5/OPT` | 210 | -24 | 40% | 66k | 0.20 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf60/NAT1` | 17 | -4 | 29% | 24k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf60/NAT1` | 24 | -8 | 38% | 16k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf60/NAT1` | 30 | -10 | 43% | 19k | 0.62 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf60/NAT2` | 17 | -1 | 29% | 24k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf60/NAT2` | 24 | -5 | 38% | 15k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf60/NAT2` | 30 | -24 | 33% | 35k | 0.93 | 1.00 |  | FAIL |
| BANK | `H_SHARK/tf60/OPT` | 17 | -9 | 24% | 15k | 1.00 | 1.00 |  | FAIL |
| FINN | `H_SHARK/tf60/OPT` | 24 | -15 | 38% | 16k | 1.00 | 1.00 |  | FAIL |
| NIFT | `H_SHARK/tf60/OPT` | 30 | -8 | 40% | 14k | 0.79 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/r0925/NATk1` | 992 | -125 | 50% | 127k | 0.32 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/r0925/NATk1` | 772 | -144 | 54% | 157k | 0.27 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/r0925/NATk1` | 1276 | -85 | 62% | 143k | 0.21 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/r0925/NATk3` | 992 | -60 | 38% | 102k | 0.09 | 1.00 | -171 | FAIL |
| FINN | `M1_SQ9I/r0925/NATk3` | 772 | -78 | 33% | 128k | 0.07 | 1.00 | -284 | FAIL |
| NIFT | `M1_SQ9I/r0925/NATk3` | 1276 | -4 | 35% | 84k | 0.02 | 1.00 | -167 | FAIL |
| BANK | `M1_SQ9I/r0925/OPT` | 992 | -405 | 34% | 423k | 0.90 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/r0925/OPT` | 772 | -241 | 35% | 282k | 0.49 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/r0925/OPT` | 1276 | -130 | 37% | 206k | 0.34 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/r0925/T30` | 992 | -196 | 41% | 207k | 0.71 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/r0925/T30` | 772 | -194 | 42% | 222k | 0.61 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/r0925/T30` | 1276 | -62 | 45% | 104k | 0.13 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rOpen/NATk1` | 992 | -98 | 45% | 102k | 0.17 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rOpen/NATk1` | 771 | -162 | 44% | 153k | 0.82 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rOpen/NATk1` | 1276 | -121 | 54% | 154k | 0.81 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rOpen/NATk3` | 992 | -192 | 45% | 198k | 0.87 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rOpen/NATk3` | 771 | -280 | 38% | 264k | 0.93 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rOpen/NATk3` | 1276 | -143 | 40% | 192k | 0.30 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rOpen/OPT` | 992 | -420 | 34% | 469k | 0.97 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rOpen/OPT` | 771 | -404 | 32% | 389k | 0.99 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rOpen/OPT` | 1276 | -75 | 36% | 124k | 0.23 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rOpen/T30` | 992 | -275 | 40% | 279k | 0.84 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rOpen/T30` | 771 | -250 | 40% | 245k | 0.92 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rOpen/T30` | 1276 | -171 | 44% | 226k | 0.73 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rPC/NATk1` | 992 | -109 | 42% | 117k | 0.28 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rPC/NATk1` | 773 | -129 | 43% | 123k | 0.21 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rPC/NATk1` | 1275 | -123 | 47% | 164k | 0.76 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rPC/NATk3` | 992 | -152 | 43% | 169k | 0.78 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rPC/NATk3` | 773 | -290 | 41% | 274k | 0.97 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rPC/NATk3` | 1275 | -213 | 44% | 299k | 0.84 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rPC/OPT` | 992 | -362 | 34% | 394k | 0.87 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rPC/OPT` | 773 | -307 | 33% | 292k | 0.79 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rPC/OPT` | 1275 | -146 | 35% | 233k | 0.47 | 1.00 |  | FAIL |
| BANK | `M1_SQ9I/rPC/T30` | 992 | -216 | 42% | 246k | 0.82 | 1.00 |  | FAIL |
| FINN | `M1_SQ9I/rPC/T30` | 773 | -170 | 42% | 196k | 0.52 | 1.00 |  | FAIL |
| NIFT | `M1_SQ9I/rPC/T30` | 1275 | -173 | 45% | 239k | 0.76 | 1.00 |  | FAIL |
| BANK | `M2_SQ9H/r0925/NATk1` | 992 | -154 | 59% | 162k | 0.54 | 1.00 |  | FAIL |
| FINN | `M2_SQ9H/r0925/NATk1` | 771 | -176 | 53% | 218k | 0.17 | 1.00 |  | FAIL |
| NIFT | `M2_SQ9H/r0925/NATk1` | 1268 | -109 | 53% | 172k | 0.05 | 1.00 |  | FAIL |
| BANK | `M2_SQ9H/r0925/OPT` | 992 | -354 | 35% | 370k | 0.89 | 1.00 |  | FAIL |
| FINN | `M2_SQ9H/r0925/OPT` | 771 | -195 | 37% | 230k | 0.34 | 1.00 |  | FAIL |
| NIFT | `M2_SQ9H/r0925/OPT` | 1268 | -166 | 36% | 237k | 0.53 | 1.00 |  | FAIL |
| BANK | `M2_SQ9H/r0925/T30` | 992 | -171 | 43% | 180k | 0.66 | 1.00 | -368 | FAIL |
| FINN | `M2_SQ9H/r0925/T30` | 771 | -62 | 43% | 107k | 0.16 | 1.00 | -121 | FAIL |
| NIFT | `M2_SQ9H/r0925/T30` | 1268 | -66 | 45% | 113k | 0.21 | 1.00 | -280 | FAIL |
| BANK | `M2_SQ9H/rPC/NATk1` | 992 | -155 | 49% | 166k | 0.62 | 1.00 |  | FAIL |
| FINN | `M2_SQ9H/rPC/NATk1` | 776 | -303 | 47% | 286k | 0.95 | 1.00 |  | FAIL |
| NIFT | `M2_SQ9H/rPC/NATk1` | 1273 | -224 | 52% | 291k | 0.93 | 1.00 |  | FAIL |
| BANK | `M2_SQ9H/rPC/OPT` | 992 | -383 | 34% | 405k | 0.79 | 1.00 |  | FAIL |
| FINN | `M2_SQ9H/rPC/OPT` | 776 | -323 | 33% | 308k | 0.83 | 1.00 |  | FAIL |
| NIFT | `M2_SQ9H/rPC/OPT` | 1273 | -172 | 35% | 265k | 0.58 | 1.00 |  | FAIL |
| BANK | `M2_SQ9H/rPC/T30` | 992 | -204 | 41% | 241k | 0.69 | 1.00 |  | FAIL |
| FINN | `M2_SQ9H/rPC/T30` | 776 | -160 | 42% | 174k | 0.59 | 1.00 |  | FAIL |
| NIFT | `M2_SQ9H/rPC/T30` | 1273 | -161 | 45% | 232k | 0.74 | 1.00 |  | FAIL |
| BANK | `M3_RMAG/major/NAT` | 556 | -113 | 45% | 113k | 1.00 | 1.00 |  | FAIL |
| FINN | `M3_RMAG/major/NAT` | 387 | -72 | 47% | 67k | 0.21 | 1.00 |  | FAIL |
| NIFT | `M3_RMAG/major/NAT` | 506 | -36 | 48% | 59k | 0.60 | 1.00 |  | FAIL |
| BANK | `M3_RMAG/major/OPT` | 556 | -211 | 36% | 213k | 0.92 | 1.00 |  | FAIL |
| FINN | `M3_RMAG/major/OPT` | 387 | -205 | 34% | 191k | 0.82 | 1.00 |  | FAIL |
| NIFT | `M3_RMAG/major/OPT` | 506 | +22 | 39% | 48k | 0.14 | 1.00 | -55 | FAIL |
| BANK | `M3_RMAG/major/T30` | 556 | -199 | 40% | 208k | 1.00 | 1.00 |  | FAIL |
| FINN | `M3_RMAG/major/T30` | 387 | -66 | 42% | 66k | 0.48 | 1.00 | -7 | FAIL |
| NIFT | `M3_RMAG/major/T30` | 506 | -8 | 46% | 42k | 0.20 | 1.00 |  | FAIL |
| BANK | `M3_RMAG/minor/NAT` | 1126 | -191 | 45% | 190k | 0.97 | 1.00 |  | FAIL |
| FINN | `M3_RMAG/minor/NAT` | 1623 | -245 | 47% | 234k | 0.07 | 1.00 |  | FAIL |
| NIFT | `M3_RMAG/minor/NAT` | 2500 | -198 | 47% | 262k | 0.56 | 1.00 |  | FAIL |
| BANK | `M3_RMAG/minor/OPT` | 1096 | -389 | 36% | 397k | 0.95 | 1.00 |  | FAIL |
| FINN | `M3_RMAG/minor/OPT` | 1221 | -279 | 36% | 271k | 0.34 | 1.00 |  | FAIL |
| NIFT | `M3_RMAG/minor/OPT` | 2190 | -311 | 35% | 438k | 0.87 | 1.00 |  | FAIL |
| BANK | `M3_RMAG/minor/T30` | 1117 | -79 | 43% | 152k | 0.56 | 1.00 | -178 | FAIL |
| FINN | `M3_RMAG/minor/T30` | 1449 | -209 | 42% | 217k | 0.58 | 1.00 |  | FAIL |
| NIFT | `M3_RMAG/minor/T30` | 2295 | -238 | 41% | 337k | 0.92 | 1.00 |  | FAIL |
| BANK | `M4_RFADE/minor/NAT` | 1564 | -256 | 47% | 269k | 0.83 | 1.00 |  | FAIL |
| FINN | `M4_RFADE/minor/NAT` | 1884 | -359 | 46% | 336k | 0.09 | 1.00 |  | FAIL |
| NIFT | `M4_RFADE/minor/NAT` | 2737 | -376 | 46% | 482k | 0.97 | 1.00 |  | FAIL |
| BANK | `M4_RFADE/minor/OPT` | 1100 | -368 | 34% | 396k | 0.92 | 1.00 | -370 | FAIL |
| FINN | `M4_RFADE/minor/OPT` | 1370 | -279 | 36% | 316k | 0.21 | 1.00 | -358 | FAIL |
| NIFT | `M4_RFADE/minor/OPT` | 2134 | -484 | 34% | 621k | 0.96 | 1.00 | -191 | FAIL |
| BANK | `M4_RFADE/minor/T30` | 1477 | -310 | 41% | 312k | 0.96 | 1.00 |  | FAIL |
| FINN | `M4_RFADE/minor/T30` | 1945 | -368 | 40% | 390k | 0.61 | 1.00 |  | FAIL |
| NIFT | `M4_RFADE/minor/T30` | 2841 | -358 | 42% | 484k | 0.95 | 1.00 |  | FAIL |
| BANK | `M9_ORFIB/or15/NATk127` | 970 | -171 | 56% | 178k | 0.73 | 1.00 |  | FAIL |
| FINN | `M9_ORFIB/or15/NATk127` | 768 | -116 | 56% | 124k | 0.02 | 1.00 |  | FAIL |
| NIFT | `M9_ORFIB/or15/NATk127` | 1258 | -119 | 59% | 157k | 0.74 | 1.00 |  | FAIL |
| BANK | `M9_ORFIB/or15/NATk162` | 970 | -224 | 53% | 234k | 0.60 | 1.00 | -150 | FAIL |
| FINN | `M9_ORFIB/or15/NATk162` | 768 | -164 | 52% | 201k | 0.31 | 1.00 |  | FAIL |
| NIFT | `M9_ORFIB/or15/NATk162` | 1258 | -107 | 56% | 154k | 0.23 | 1.00 |  | FAIL |
| BANK | `M9_ORFIB/or15/OPT` | 970 | -441 | 34% | 446k | 0.97 | 1.00 |  | FAIL |
| FINN | `M9_ORFIB/or15/OPT` | 768 | -225 | 37% | 247k | 0.65 | 1.00 |  | FAIL |
| NIFT | `M9_ORFIB/or15/OPT` | 1258 | -44 | 38% | 101k | 0.23 | 1.00 | -332 | FAIL |
| BANK | `M9_ORFIB/or15/T30` | 970 | -229 | 40% | 239k | 0.99 | 1.00 |  | FAIL |
| FINN | `M9_ORFIB/or15/T30` | 768 | -61 | 43% | 96k | 0.14 | 1.00 | -317 | FAIL |
| NIFT | `M9_ORFIB/or15/T30` | 1258 | -136 | 45% | 194k | 0.86 | 1.00 |  | FAIL |
