# R124: every 30-point move, traced back. What came before it, and can a neural network see it coming?

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"do more deep research/backtest each
move (could be multiple candles) who has moved 30 points+ (in any direction) and backtrack it, and find what has
caused it, use the neural network"*, and *"always check the head and tail (highest/lowest of the candle), don't check
closing/opening point."* Every move here is measured on candle **highs and lows (wicks)**.

## The answer, first

1. **30-point moves are everyday events, not rare ones.**
   - NIFTY made **23,606** of them in design (Aug 2021 - Mar 2026, 1,142 sessions), wick to wick: **about 20 a day**.
   - In the holdout (Apr - Oct 2026) it made 3,083 in 137 sessions, about 22 a day.
   - The typical move is 40 points in 6 minutes. 90% are done within 15 minutes, and 90% are under 69 points.
   - They split evenly: 50% up, 50% down.
2. **What comes before a move is mostly "the market is already busy". Nothing in the data tells the direction.**

   These held on the unseen holdout. Each "×" is how much more often a move starts in the top fifth of that factor
   than on average, at the same time of day.

   | before the move | design | holdout |
   |---|---|---|
   | the last 30 minutes were already wide (high recent range) | 2.4× | 1.7× |
   | India VIX high | 1.7× | 2.2× |
   | the ATM straddle expensive | 2.0× | 2.1× |
   | price already far from the day's open | 1.8× | 1.6× |
   | price already far from yesterday's high | 2.0× | 1.5× |
   | price already far from the opening range | 1.8× | 1.6× |
   | a big opening gap | 1.3× | 1.2× |

   - **Time of day:** 09:15-09:45 has 2.8× the average rate of move starts (holdout 2.5×). The quietest time is
     11:15-13:15 (0.6-0.7×), and moves pick up again after 14:45.
   - **No effect, or did not hold:**
     - round-100 levels (q 0.12);
     - expiry day (q 0.49);
     - PCR change;
     - call/put OI changes: they were linked to moves in design, but did not hold in the holdout (q 0.38 / 0.48).
   - **Built into the definition, not a cause:**
     - "price went the other way just before the move" (a swing starts at a turning point, by definition);
     - "the other indices also went the other way";
     - "moves come in clusters" (a new move starts a median 8 minutes after the last one ends, against 22 minutes at
       ordinary minutes).

     When a new move follows one, it continues the last one's direction 53% of the time (21,832 cases): a coin.
   - **The honest limit:** this shows what came *before* moves, not what *caused* them. News, big orders and global
     markets are not in the data.
3. **The neural network can tell when a move is likely, a little better than trees. It cannot tell which way.**

   | holdout (46,580 forecasts, 137 sessions) | network | trees | verdict |
   |---|---|---|---|
   | a 30-point move within 15 min (happens 65% of the time) | AUC 0.832 | 0.823 | network better (q 0.002), but only 2 of 4 design folds: fails the pre-registered bar |
   | a 30-point move within 30 min (happens 88%) | AUC 0.853 | 0.842 | **network better** (q 0.002, 3 of 4 folds, design q 0.038) |
   | direction, given a move comes (15 min) | AUC 0.513 | 0.503 | a coin (q 0.45) |
   | direction, given a move comes (30 min) | AUC 0.506 | 0.493 | a coin (q 0.57) |

   - The forecast is honest: when it says 55%, a move happened 54% of the time, and at 87% it happened 88% (holdout,
     20,604 forecasts in the top bin).
   - **What the network uses:** almost only the last 2 hours of price action. Shuffling those minutes worsens the
     forecast about **16-44×** more (holdout / design) than shuffling the 20-day history, VIX/size, the option chain, the calendar or the
     levels.
   - This is the fifth study to find direction is a coin: R16, B1, R41, R123 and now R124.
4. **Can it make money? No. All four option rules fail.**

   | rule | design trades / avg net | holdout trades / avg net | why it fails |
   |---|---|---|---|
   | M1 call/put, move in 15 min + direction ≥ 60% | 0 / - | 0 / - | the network was never 60/40 sure of the direction when a move was likely |
   | M2 the same over 30 min | 7 / -Rs 254 | 5 / +Rs 889 | far too few trades (gates: 300 design, 100 holdout) |
   | M3 M1 at 09:20-09:30 only | 0 / - | 0 / - | as M1 |
   | M4 ATM straddle when a move is likely (15 min) | 2,264 / **-Rs 188** (37% win) | 516 / **-Rs 211** (32% win) | loses money in every fold and in the holdout |

   - **Why the straddle loses although the move usually comes:** on NIFTY, a 30-point move is small for a straddle.
     The winning leg gains about Rs 1,000 on 1 lot (65 × ~15 points), but the losing leg gives back much of that.
     Two legs pay charges and spreads twice, and time decay runs too.
   - Average win Rs 347 vs average loss -Rs 470 in the holdout. It loses less than buying straddles at random times
     (twins lose more: p 0.001), but it still loses: -Rs 794 a session in the holdout, worst month -Rs 25,483.
   - It also breaches the account stop: max drawdown -Rs 1,10,254 on 1 lot in the holdout.

**Nothing passes, so nothing goes on paper, and nothing changes in the app.**

**What could be useful (information, not a trade):** the 30-minute "a move is coming" forecast held on unseen data. Like
R41's size forecast, it could only ever *lower* risk, for example by skipping a trade when a dead half-hour is
forecast. That would need its own pre-registered paper test behind a switch that is off by default. It is not proposed
here.

## How it was done

- **Pre-registration:** `research/hunt/r124_big_moves/PREREG.md`.
  - Commit `d7c4c63`: written before any move was counted.
  - Amendment 1, `6cd9ad6`, before any result: wicks only, never opens or closes.
- **Commits:**
  - census design results `353a630`;
  - walk-forward `859a32b`, committed before the holdout;
  - holdout read **once**, `a7abd72`: 28 Oct, 11:02-11:17 UTC, not resumed.
- **Code and tests:** `research/hunt/r124_big_moves/` (`r124zig.py` the zigzag, census and factors; `r124data.py` the
  network rows, labels and option trades; `r124rules.py`; `r124run.py`; `test_r124.py`, 15 tests).
  - It reuses R123's data pipeline, lake cleaning, costs and multi-level network.
- **Results:** `results/` (`census_design.json`, `census_holdout.json`, `wf.json`, `holdout.json`).
- **Big files:** `gdrive:IraAlgo-data-lake/derived/r124/`.

**Data:** the Drive lake (28 Oct copy).
- Index minutes for all five indices and India VIX.
- The nearest-expiry option chain at ATM±10.
- Cleaning as in R123's amendment 2: lakeclean steps 1-3, and minutes with no trade are never priced.

**The move (wicks).**
- A zigzag on each session's highs and lows.
- A swing runs from the lowest low to the highest high (or the reverse). It ends when price comes back 10 points from
  the extreme's wick.
- A **big move** is a swing of 30 points or more.
- The other indices use the same size as a share of price (30 / NIFTY's open × the index's open), and are reported
  separately.

**Part A, the census.**
- 20 pre-registered factors, measured at the minute before each start.
- Compared with "control" minutes: every other minute not within 5 minutes of a start, at the same time of day.
- Statistics: a standardised difference, the lift by fifth of the factor, day-block bootstrap CIs, and BH over the 20
  factors.
- Each factor that passed in design was re-measured once on the holdout.

**Part B, the network.**
- R123's multi-level network: a fast encoder over the last 120 minutes; a slow encoder over the last 20 days; five
  specialists (size, direction, option chain, calendar, levels); a gated committee; calibration and abstention.
- It was trained on every 5-minute decision (09:20-14:55) across all five indices.
- **Label:** did a 30-point wick-to-wick move complete in the next 15 / 30 minutes, which way first, or neither.
- Baselines: the base rate and gradient-boosted trees.
- Walk-forward with yearly folds (2023, 2024, 2025, early 2026), then the holdout.
- "What it uses" is permutation importance: shuffle one input group and measure how much worse the forecast gets.

**Part C, the money test.**
- Signal on the underlying; buy the ATM option (or the straddle) at the next minute's open.
- Exit when the index's high or low touches ±30 from the entry, else at 15 / 30 minutes or the square-off.
- 1 lot at today's lots, dated charges and liquidity-aware slippage; at most 5 trades a day.
- 1,000 random-entry twins; BH over the 4 rules; R123's gates.

## Sample sizes

| part | n |
|---|---|
| census, design | 23,606 NIFTY big moves (92,964 swings of any size); 138,227 factor rows (starts + every 2nd control minute) |
| census, holdout | 3,083 big moves over 137 sessions |
| network | 333,955 design rows (258,872 scored out of sample); 46,580 holdout rows |
| abstained (the seeds disagreed by > 0.10) | 37,241 / 43,886 walk-forward rows (15 / 30 min) |

## Honest notes

1. **The holdout is not virgin.** R123 read Apr - Oct 2026 earlier the same day, for its own rules; R106, R113 and
   R120-R122 read it earlier for the paper arms. Nothing about 30-point moves had been run on it.
2. **The direction-signed factors are partly mechanical.** A swing starts where the last one turned, so "price fell
   just before an up move" is true by construction. They are reported as description only, never used as forecasts.
3. **The 15:15 slot** shows a lift of 9.8-11.3×. That is an artefact: controls stop at bar 359 (15:14), and the slot
   has only 343 starts. It should be ignored.
4. **Same-bar touches:** a candle that spans both +30 and -30 is "unclear" for the label (masked). For a trade, it
   counts as the stop (worst case).
5. **Compute:** about 1.5 hours on a 4-core CPU.

## Reproduce

```bash
export IRAALGO_LAKE=<lake copy> R124_OUT=<cache>
cd research/hunt/r124_big_moves
python -c "import r124zig as Z, r124cfg as K; M,R=Z.census('design'); M.to_parquet(K.OUT/'census_moves_design.parquet'); R.to_parquet(K.OUT/'census_rows_design.parquet')"
python -c "import r124data as RD; RD.save('design')"
python r124run.py census_stats && python r124run.py wf
python r124run.py holdout        # refused: already read
python -m pytest test_r124.py -q
```
