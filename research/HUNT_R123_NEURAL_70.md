# R123: one neural network over all the index and option data. Can it give 70% wins and Rs 1,000 a trade?

28 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss: *"create a neuro network of all the data we
have for index and their options and create a simple strategies using all the data in network, which could give me
70% winrate and 1000rs profit"*; then *"Create a neuro network with multiple levels."*

## The answer: no

**No rule reached about 70% wins with about Rs 1,000 a trade on unseen data, and no rule passed every gate.** Nothing
goes on paper and nothing changes in the app.

1. **The "70% rule" did win about 63-66% of the time in design, and it lost money.**
   - S1 aims at +Rs 1,000 with a Rs 2,000 stop, and enters when the network says P(+Rs 1,000 first) ≥ 70%.
   - Design walk-forward, 635 trades: 63% wins, **-Rs 82 a trade** after charges and slippage. With wins capped at
     Rs 1,000 and losses at Rs 2,000, it needed 66% just to break even.
   - In the holdout the network almost never said "70%" (7 trades: 3 wins, -Rs 872 a trade).
   - The reason: **when the network says 70%+, it is right only about 58% of the time** (design, 3,120 cases).
     It is honest in the middle (says 55%, 55% happen) but over-confident exactly where a 70% rule needs it to be right.
2. **The best honest trade-off was about 55-60% wins at Rs 350-580 a trade, and too few trades to trust.**
   S5 is the network's best-expectancy pick at 09:20-09:30 only; in practice it buys the ATM **put** with a +Rs 2,000
   target, a Rs 2,000 stop and a 60-minute limit.

   | S5 "morning best expectancy" (single network) | trades | win rate | avg net / trade | per session | max drawdown |
   |---|---|---|---|---|---|
   | design walk-forward (2023 - 15 Mar 2026) | 555 | 53% | **Rs 172** (2x slippage Rs 156) | Rs 121 | -Rs 31,141 |
   | holdout (1 Apr - 19 Oct 2026, read once) | 41 | **61%** | **Rs 579** (2x slippage Rs 563) | Rs 173 | -Rs 6,186 |

   - It beat random entries with the same exits (holdout p 0.002, BH q 0.020).
   - But it **fails the 100-trade gate** (41 trades in 137 sessions).
   - Its design profit leaned on one year: 2024 made Rs 1,00,652 of its Rs 95,710 total; 2025 lost Rs 5,617.
   - S4 (the same idea at any time of day) is similar: 52 holdout trades, 56% wins, Rs 345 a trade, q 0.045. It also
     fails only on trade count.
   - Neither is 70% and Rs 1,000: winners average about Rs 1,650, losers about -Rs 1,100 to -Rs 1,300.
3. **The multi-level network (Boss's second ask) was built and tested. It did no better.** It has four levels: two
   market encoders, four specialists, a committee and a calibration/abstain level (below).
   - It forecast the next 30/60 minutes' range best of everything here: holdout R² 0.405 vs trees 0.386 for 60
     minutes (43,155 forecasts, q 0.08).
   - But its "+Rs X before -Rs Y" forecasts were no better than gradient-boosted trees in the holdout (no cell better).
   - It was never confident enough to trade in the holdout: 0 trades on all five rules.
   - Its committee leaned mostly on the option-chain specialist: weight 0.43, against size 0.15, direction 0.23 and
     calendar 0.19.
4. **Direction is still a coin, for both networks** (holdout log-loss 0.694 vs 0.694 for "always 50%"). This is the
   fourth time: R16, B1, R41 and now R123.

**What could go on paper:** nothing as a rule (gates failed). The one lead worth a fresh, pre-registered test later is
S5's "morning ATM put with ±Rs 2,000 / 60 minutes" pick, once enough new sessions exist to reach 100 trades. It would be
paper only, behind a switch, and logged. Not proposed now.

## What was built

- **Pre-registration:** `research/hunt/r123_neural_70/PREREG.md`.
  - Commit `a5a5cb6`: written before any model or outcome.
  - Amendment 1, `990e110`, before any fit: the multi-level network.
  - Addendum, `2cdbe4f`, before any fit: the encoder reads all 120 minutes.
  - Amendment 2, `ad8bd64`, after the first walk-forward and before the holdout: lake cleaning (see "Honest notes").
- **Commits:**
  - design results `035bb15`, committed before the holdout;
  - holdout read **once**, `e8b8157`: 28 Oct, 09:06-09:30 UTC, not resumed.
- **Code and 38 tests:** `research/hunt/r123_neural_70/` (`r123data.py`, `r123net.py`, `r123rules.py`, `run.py`,
  `report.py`, `entries.py`, `test_r123.py`).
- **Every table:** `results/report.md`.
- **Big files (rows, predictions, model weights):** `gdrive:IraAlgo-data-lake/derived/r123/`, never in git.

**Data (Drive lake, 28 Oct copy).**
- Index minutes of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX, plus India VIX.
- The nearest-expiry option chain at ATM±10 by minute (OHLC, volume, OI, IV), Aug 2021 - 19 Oct 2026.
- Breadth from the five indices; expiry days from the data; budget days.
- MIDCPNIFTY and SENSEX options start 2022-23.
- RBI days and constituent breadth are not in the lake, so they were not used. MCX was not used.

**Sample sizes.**
- Design: 333,955 decision rows (one every 5 minutes, 09:20-14:55); the walk-forward scored 258,872 of them over
  792 sessions.
- Holdout: 46,580 rows over 137 sessions.

**Signal source of every rule:** the network's reading of the **underlying**.
- It reads the index minutes, VIX, breadth and the calendar, with the option chain (OI, PCR, IV, skew, straddle level)
  as context.
- It then buys the ATM call or put. The premium sets only the target and stop.
- No rule reads the option's own price chart. The premium's past returns were deliberately left out of the inputs.

**The networks** (each one an average of 3 seeds, retrained per fold; scalers fitted on training rows only):
- **N1, single network:**
  - a causal TCN reads the last 120 index minutes (7 channels);
  - an MLP reads 42 inputs (index state, VIX, breadth, calendar, option chain);
  - three heads: (a) for each of 8 target/stop/time cells and both sides, *+Rs X first / -Rs Y first / neither*;
    (b) the next 30/60-minute range; (c) direction.
- **N2, multi-level network** (`research/data/ARCHITECTURE.md` L5):
  - *Level 1:* a fast encoder (the TCN) and a slow one (a GRU over the last 20 completed sessions).
  - *Level 2:* four specialists, each with its own inputs: size (trained on the range), direction (trained on
    direction), option chain and calendar (each with its own hit-first head).
  - *Level 3:* a gated committee makes the final hit-first forecast.
  - *Level 4:* per-cell temperatures fitted on the validation slice; no trade when the 3 seeds disagree by more than
    0.10.
- **Grid:** target X ∈ {Rs 1,000, Rs 2,000} net, stop Y ∈ {Rs 1,000, Rs 2,000} net, time H ∈ {30, 60} minutes.
  Square-off 15:20 (15:05 on expiry days).
- **Costs:** 1 lot at today's lots (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120, SENSEX 20), dated charges and
  liquidity-aware slippage. If the target and stop are inside one minute, the stop is taken.

**Rules** (fixed in advance; at most 5 trades a day; one open trade per index):

| rule | what |
|---|---|
| S1 | target +Rs 1,000, stop -Rs 2,000, 60 minutes; enter when P(+Rs 1,000 first) ≥ 0.70 |
| S2 | S1, plus the range head must forecast a move worth ≥ Rs 2,000 (it never filtered anything: S2 = S1) |
| S3 | target +Rs 1,000, stop -Rs 1,000, 30 minutes; enter when P ≥ 0.60 |
| S4 | the cell and side with the highest expected net, when ≥ Rs 300 |
| S5 | S4 at 09:20 / 09:25 / 09:30 only (R113) |

Every rule ran with both networks: 10 tests, one Benjamini-Hochberg family.

**Gates.**
- Design walk-forward: ≥ 300 trades; positive at 1x and 2x slippage; positive in 3 of the 4 folds; q ≤ 0.10 vs
  random-entry twins.
- Holdout: ≥ 100 trades; positive at 1x and 2x slippage; q ≤ 0.10; Boss's account stop not breached.

## Results

Rs per lot after charges and slippage. Holdout q is BH over the 10 rule tests; p is vs 1,000 random-entry twins with
the same exits.

| rule | design trades | design win | design avg | design q | holdout trades | holdout win | holdout avg | per session | holdout q | fails |
|---|---|---|---|---|---|---|---|---|---|---|
| N1-S1 / S2 (70% rule) | 635 | 63% | -82 | 0.045 | 7 | 43% | -872 | -45 | 1.00 | design net, folds; holdout n, net |
| N1-S3 (1:1 at 60%) | 92 | 47% | -92 | 0.48 | 0 | - | - | - | - | everything |
| **N1-S4** | 1,085 | 51% | 78 | 0.005 | 52 | 56% | **345** | 131 | 0.045 | holdout n < 100 |
| **N1-S5** | 555 | 53% | 172 | 0.005 | 41 | 61% | **579** | 173 | 0.020 | holdout n < 100 |
| N2-S1 / S2 | 14 | 14% | -1,571 | 1.00 | 0 | - | - | - | - | everything |
| N2-S3 / S4 / S5 | 0-2 | - | - | - | 0 | - | - | - | - | everything |

- **Per day (holdout, 137 sessions):**
  - S5 traded on 27 days. It made Rs 879 on an average trading day, Rs 173 per session overall. Its worst day was
    -Rs 3,080 and its worst month -Rs 4,154 (Apr 2026). 5 of its 7 months were green.
  - S4: Rs 561 a trading day over 32 days; Rs 131 a session.
  - That is far from "5 orders × Rs 1,000": these rules trade about once every three sessions.
- **By index (holdout S5):** NIFTY +Rs 9,719 (18 trades), SENSEX +Rs 10,973 (8), BANKNIFTY +Rs 4,003 (2),
  FINNIFTY -Rs 770 (9), MIDCPNIFTY -Rs 190 (4).
- **Side choice:** S4/S5 picks were 80-98% puts. Against "same entries, random side" twins they still win
  (holdout p 0.001-0.004). So the network did pick the side, not just the moment, though it rarely chose calls.
- **Calibration** (single network, the 70% rule's cell):

  | forecast | design (n) | design happened | holdout (n) | holdout happened |
  |---|---|---|---|---|
  | 55% | 47,652 | 55% | 15,956 | 55% |
  | 63% | 19,978 | 60% | 2,576 | 60% |
  | 74% | 2,815 | 58% | 7 | 43% |
  | 82% | 305 | 61% | - | - |

  The multi-level network's temperatures pulled its forecasts in, so it almost never said 70%.
- **Forecast quality (model tests):**
  - In the design walk-forward both networks beat the trees on most "+X first" cells: 29 of 32 at q ≤ 0.10.
  - **None of them held in the holdout:** best q 0.34; the differences were within ±0.002 Brier.
  - The range forecasts did hold: N2 range-60 R² 0.405 vs trees 0.386 (q 0.08); N1 0.397 (q 0.34).
  - So, again, what is predictable is *how much* the index will move, not *which way* or *which option wins first*.

## Honest notes

1. **A data bug was found between the first walk-forward and the holdout, and fixed before the holdout.**
   - The first walk-forward used option prices without the repo's lake cleaning.
   - On 3 Feb 2026, MIDCPNIFTY puts jumped Rs 134 → 256.8 → 528 on zero volume, and one trade "lost" Rs 14,923.
   - Amendment 2 applied `lakeclean` (foreign rows, stray prints, stale prints) and treated zero-volume minutes as no
     print. Section 4 of the pre-registration already said "bars without a print are never priced".
   - The rules, models and gates did not change.
   - The first run is kept: `results/wf_uncleaned.json`. On it S4 made +Rs 196 a trade over 1,671 trades. Cleaning
     cut that to +Rs 78 over 1,085.
   - Seeing the first run before the fix is a weakness, and it is disclosed here.
2. **A test caught a design flaw before any fit.** The pre-registered encoder only reached about 45 of its 120 minutes.
   It now also averages all 120 (addendum).
3. **The holdout is not virgin.** R106, R113 and R120-R122 read Apr-Oct 2026 for the paper arms. No network or rule
   of this kind had been run on it.
4. **Few holdout trades.** The single network grew more cautious in 2026 (its top forecasts were rarer), so S4 traded
   0.38 times a session against 1.37 in design. The trade-count gate exists for exactly this.
5. **S2 never differed from S1.** With ATM deltas and today's lots, the range head almost always forecast enough room
   for Rs 2,000, so the "room" filter never bit.
6. **Compute.** About 2 hours on a 4-core CPU:
   - data build: 10-35 minutes;
   - each network: 3 seeds × at most 20 epochs, about 5-20 s an epoch;
   - the holdout fits: 10-12 minutes per network.

## Reproduce

```bash
export IRAALGO_LAKE=<lake copy> R123_OUT=<cache>
python research/hunt/r123_neural_70/run.py build     # design rows (cleaned)
python research/hunt/r123_neural_70/run.py wf        # walk-forward, both networks, all rules
python research/hunt/r123_neural_70/run.py holdout   # refused: already read (results/holdout.json is committed)
python research/hunt/r123_neural_70/report.py
python -m pytest research/hunt/r123_neural_70/test_r123.py -q
```
