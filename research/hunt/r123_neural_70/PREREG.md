# R123 pre-registration: one neural network over all the index and option data, and a few plain rules from it

Written 28 Oct 2026, before any model was fitted and before any trade outcome was computed. Research only: nothing in
the app changes (no `android/**`), nothing trades.

Boss, 28 Oct: *"We have all the required data in google drive, can you create a neuro network of all the data we have
for index and their options and create a simple strategies using all the data in network, which could give me 70%
winrate and 1000rs profit."* Earlier (R106): *"5 orders each give me 1,000 rupees ... I'm not expecting 20,000 to
30,000 per day."*

## 0. Reading of the target

- "70% win rate": the share of trades with net P&L > 0 after charges and slippage, 1 lot.
- "₹1,000 profit": read two ways, both reported.
  - **Strict:** the AVERAGE trade nets about ₹1,000. Boss's target is met when the holdout win rate is ≥ 65% AND the
    average net per trade is ≥ ₹800.
  - **Lenient:** a winning trade nets about ₹1,000 (a +₹1,000 target), the win rate is ≥ 65%, and the average trade is
    still positive after costs.
- Neither reading is a gate for "passes". A rule passes on net expectancy (section 6). A 70% win rate is easy with a
  far stop, so the win rate alone proves nothing.

## 1. What earlier studies found, and how this differs

| study | finding | what R123 does differently |
|---|---|---|
| R16, B1, R41 | the next 15/30/60-minute direction of the index is a coin (R41 holdout AUC 0.510-0.517, networks too) | R123 does not ask "up or down". It asks a different question: "will THIS option gain +₹X before it loses ₹Y within H minutes". That question mixes size (which IS predictable: R41 R² 0.43) with direction. If the size forecast alone is what helps, both sides look good together, and the side choice stays a coin; the random-side twin below checks exactly that. A direction head is kept as a control (expected weak). |
| R41 | networks forecast the next 30/60-minute range better than trees (R² 0.432 vs 0.382) | reused as head (b) and as the "room to move" filter in rule S2 |
| R106 | fixed small targets on the arms' signals lost; a capped win needs a very high win rate | R123 picks entries for the target itself, instead of bolting a target onto signals built for big wins. The break-even maths of R106 is applied up front: every rule's threshold is set above its cost-adjusted break-even. |
| R105 / R105B | +₹1,000 is ordinary noise on 1 lot (+4-10% of the premium); book direction filters failed | same warning; the stop distance Y is part of the grid so the network can only help if it beats noise |
| R113 | 09:15-09:30 entries carried the arms' money | rule S5 is the morning-only version |
| R120-R122 | time-machine and context filters looked good in design and failed the final | no search loop here: 5 rules, fixed thresholds, written below, read once |

## 2. Data (the Drive lake, copied 28 Oct 2026; nothing raw goes to git)

- `index_1m`: NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and INDIAVIX, 2021 - 19 Oct 2026.
- `options_rolling_1m/opt_<UND>_WEEK_<YEAR>`: the nearest expiry at ATM-10..+10 by minute, with OHLC, volume, OI, IV
  and spot. Option bars start Aug 2021 (NIFTY, BANKNIFTY, FINNIFTY), 2022-23 (MIDCPNIFTY), 2023 (SENSEX).
- Expiry days: from the data with R25's rule (as R41). Expiry dates are scheduled in advance, so knowing the next one
  at minute t is not look-ahead.
- Events: `research/brain/calendar_data/events.csv` (budget days) and expiry days. That is all the event data the
  repo has; RBI days are not in it.
- Breadth: the five indices themselves (how many are up on the day; their average 15-minute return). Constituent
  breadth is not in the lake for the whole span, so it is not used.
- MCX: not used (the question is index options; MCX options are not in the lake).

## 3. Periods

| part | dates | use |
|---|---|---|
| design | 2021-08-01 .. 2026-03-15 | walk-forward |
| embargo | 2026-03-16 .. 2026-03-31 | unused |
| **holdout** | **2026-04-01 .. 2026-10-19** (the end of the lake) | read ONCE, all 5 rules, after the design results are committed |

- The holdout is not virgin: R106, R113 and R120-R122 read 1 Apr - 13 Oct 2026 for the paper arms' trades. No network
  and no rule of this kind has been run on it. It is the most recent half-year and carries today's charges (STT 0.15%
  from 1 Apr 2026), so it is the fairest test of "would this work now".
- Walk-forward folds (test spans; each fold trains on all design sessions before its start minus a 10-calendar-day
  embargo; early stopping on the last 20% of training sessions):
  F1 2023, F2 2024, F3 2025, F4 2026-01-01 .. 2026-03-15.
- The holdout model trains on all design sessions to 2026-03-15 (the same recipe).
- A lock file refuses a second holdout read (`results/HOLDOUT_READ.json` on the cache, and a committed
  `results/holdout.json` also locks it).

## 4. Rows, inputs, labels

**Decision rows.** Every 5 minutes, at the close of the bars 09:19 .. 14:54 (decision "at 09:20" .. "at 14:55"), on
every session of every index. The trade enters at the OPEN of the next minute.

**Inputs at row t (point-in-time: only bars ≤ t).** One network for all five indices.
- *Market encoder (sequence):* the last 120 one-minute bars of the index (reaching into the previous session when
  needed), 7 channels: log return vs the previous close, bar range, move since the session's open, India VIX change,
  the other indices' average 1-minute return (breadth), a new-session marker, and a mask.
- *Index state:* index id; minutes since 09:15; weekday; sessions to the next expiry; expiry-day flag; budget-day flag;
  opening gap; move since the open; returns over 5/15/30/60 minutes; 30-minute realised volatility; the day's range
  so far; distance from the day's high and low; the previous day's range and return; the 20-day average daily range.
- *VIX:* level, change since the previous close, 30-minute change.
- *Breadth:* how many of the five indices are up on the day; their average move since the open; their average
  15-minute return.
- *Option-chain state (context, CLAUDE.md):* ATM call and put premium as % of spot, straddle as % of spot, the
  straddle in rupees per lot, ATM IV, IV skew (put IV at -3 strikes minus call IV at +3), PCR by OI over ATM±5 and its
  15-minute change, call and put OI change over 15 minutes (ATM±5), call/put volume ratio over 15 minutes.
  *Not used:* the option premium's own past returns (that would make the signal the option's own chart, which is not
  the default).

**Labels (bars > t only; never inputs).**
- *Strike rule:* ATM, half-up rounding of the index close at t to the strike step; the call or the put of the nearest
  weekly expiry in the lake series. 1 lot at **today's lot sizes** (NIFTY 65, BANKNIFTY 35, FINNIFTY 60, MIDCPNIFTY 120,
  SENSEX 20), as Boss trades now.
- *Fill:* buy at the open of bar t+1, `Fills("liq")` (5 bps plus a volume-aware half-spread). Charges `Costs("dated")`
  (the rates in force on the trade date). Every rupee below is net of both.
- *Exits:* a resting sell at the premium that nets **+₹X** (filled when a later bar's high reaches it); a stop at the
  premium that nets **-₹Y** (triggered when a bar's low reaches it, filled at the lower of that price and the bar's
  open, with stop slippage); else the close of bar t+H; square-off at the 15:19 bar's close (15:04 on expiry days).
  If the target and the stop are both inside one bar, **the stop is taken** (worst case). Bars without a print are
  skipped (never priced).
- *Grid* (8 cells): X ∈ {₹1,000, ₹2,000} × Y ∈ {₹1,000, ₹2,000} × H ∈ {30, 60} minutes. A cell is defined at row t
  only if t + H ends by the square-off.
- *Head (a):* for each cell and each side (call, put), 3 classes: +X first / -Y first / neither by H (16 × 3).
- *Head (b):* log of the index's high-low range over the next 30 and 60 minutes (% of the close at t).
- *Head (c):* direction: the index close at t+30 and t+60 above the close at t.
- *Slippage x2:* the trade's net minus its slippage once more.

## 5. Models

- **Network (N):** a causal temporal-convolution encoder over the sequence (4 layers, 32 channels, kernel 3,
  dilations 1/2/4/8) + an MLP over the tabular inputs (64 units), joined into 128 → 64 units, then the three heads.
  Loss: the mean of the 16 cross-entropies, the 2 range MSEs and the 2 direction BCEs (labels missing → masked).
  Dropout 0.2, AdamW (lr 1e-3, weight decay 1e-4), batch 1,024, at most 20 epochs, early stop after 3 epochs without a
  better validation loss. 3 seeds; probabilities averaged. Scalers fitted on training rows only (R41's `Tab`/`Seq`).
- **Baselines** on the same rows: (i) the base rate of each class per index, cell, side and hour of day in training;
  (ii) gradient-boosted trees (sklearn `HistGradientBoostingClassifier`, B1/R41's settings) on the tabular inputs, one
  per cell-side for "+X first vs not"; (iii) for the range head, the trees' regressor.
- **Model tests (reported; one BH family of 16 + 2 + 2 cells):** network Brier score for "+X first" vs the best
  baseline (range: R²; direction: log-loss), pooled walk-forward with a 95% session-block bootstrap CI, folds where N
  is better, and the holdout CI.
- **Calibration:** for the cells of S1 and S3, predicted P(+X first) in bins [0.5, 0.6), [0.6, 0.7), [0.7, 0.8),
  [0.8, 1] vs what happened, with n.

## 6. The rules (5, fixed now; no thresholds are tuned)

Common to all: at most one open trade per index; at most **5 trades a day** across all indices (Boss's "5 orders");
decisions taken in time order, ties at the same minute ranked by the rule's score; exits as in section 4.
Signal source: the network's reading of the **underlying** (index minutes, VIX, breadth, calendar), with the option
chain as context; the option premium sets the target and stop only. No rule reads the option's own chart.

| rule | cell (X / Y / H) | enter when | cost-adjusted break-even |
|---|---|---|---|
| **S1 "70% rule"** | ₹1,000 / ₹2,000 / 60 | the side with the higher P(+X first) has P ≥ 0.70 | ≈ 67% if every trade ends at +X or -Y |
| **S2 "70% with room"** | ₹1,000 / ₹2,000 / 60 | S1, and the range head's 60-minute forecast (index points) × 0.5 (ATM delta) × lot ≥ ₹2,000 | as S1 |
| **S3 "1:1 at 60%"** | ₹1,000 / ₹1,000 / 30 | the side with the higher P(+X first) has P ≥ 0.60 | ≈ 50% |
| **S4 "best expectancy"** | any of the 16 cell-sides | the highest EV = P(+X)·X - P(-Y)·Y + P(neither)·m is ≥ ₹300, where m is the training mean net of "neither" trades for that index, cell and side | EV > 0 by construction |
| **S5 "morning best expectancy"** | as S4 | S4, only at the 09:20, 09:25 and 09:30 decisions (R113) | as S4 |

**Twins (1,000 per rule and period).** Random-entry twins with the same exits: for each rule trade, a random decision
row of the same index and day where the same cell is defined, a random side, the same X / Y / H. Same number of trades
per index-day. (The one-open-trade limit is not imposed on twins.) p = (1 + twins with total net ≥ the rule's) / 1,001.
Reported, not gated: same entries with a random side (does the side choice add anything?).

**Gates. A rule passes only if it clears all of them.**
- Walk-forward (pooled out-of-sample folds F1-F4):
  1. at least 300 trades;
  2. average net per trade > 0 at 1x AND at 2x slippage;
  3. net positive in at least 3 of the 4 folds;
  4. BH q ≤ 0.10 over the 5 rules' twin p-values.
- Holdout (read once; all 5 rules are computed and reported; only a rule that passed the walk-forward can pass):
  5. at least 100 trades;
  6. average net per trade > 0 at 1x AND at 2x slippage;
  7. BH q ≤ 0.10 over the 5 rules' holdout twin p-values;
  8. Boss's account stop is not breached on 1 lot: max drawdown above -₹40,000 and no calendar month below -₹13,000.
- Then, separately, Boss's target (section 0), strict and lenient.

**Reported per rule and period:** n, win rate, +X hit rate, average win, average loss, average net per trade (1x, 2x),
net per session and per trading day, max drawdown, worst day, worst month, % green days, twin p, BH q; per fold; per
index.

## 7. If something passes

Propose (not implement) a paper-only arm: off by default, logged in the Autopilot timeline, undoable in one tap,
never carried to live. If nothing passes, nothing is proposed and nothing changes.

## 8. Tests (before any result)

`research/hunt/r123_neural_70/test_r123.py`: the point-in-time guard (features and the sequence at t do not change when
every bar after t is changed); labels read only bars > t; the target/stop premiums net exactly +X / -Y; the
stop-first rule inside one bar; square-off; scalers fit on training rows only; the holdout lock; the portfolio limits;
the twins; BH.

## 9. Outputs

- Code and small JSON results: `research/hunt/r123_neural_70/` (`results/`).
- Datasets, predictions and model weights: the cache, copied to `gdrive:IraAlgo-data-lake/derived/r123/`, never git.
- `research/HUNT_R123_NEURAL_70.md`, experiment entries `research/experiments/entries/r123-*.yaml` (every rule and
  model test, failures included), a TODO.md line only if something passes, and RESULT.md at the repo root.

## Amendment 1 (28 Oct 2026, before any model was fitted or any outcome was looked at): a network with multiple levels

Boss, 28 Oct: *"Create a neuro network with multiple levels."* Written while the design rows were still being built.
No model had been fitted and no label or trade outcome had been read when this was committed.

**What changes.** A second network, **N2 "multi-level"**, is added. It follows the four-level Brain in
`research/data/ARCHITECTURE.md` (L5). The pre-registered single network of section 5 stays as **N1**, unchanged, so
the two can be compared.

| level | what it is | what it reads | what it is trained on |
|---|---|---|---|
| 1. Market encoders | **fast:** the causal TCN of section 5 over the last 120 one-minute bars. **slow:** a GRU (32 units) over the last 20 *completed* sessions' daily bars: gap, open-to-close, open-to-high, open-to-low, range, India VIX daily change | the index's minutes and days (bars ≤ t; days before today) | through the levels above it |
| 2. Specialists | four small MLPs (64 → 32 units), each taking the Level-1 state plus its own inputs: **size** (VIX, volatility, ranges); **direction** (returns, gap, breadth); **option chain** (the chain-state inputs of section 4); **calendar** (index, time, weekday, expiry distance, budget day) | Level 1 + their own input group | **size** → head (b), the next 30/60-minute range; **direction** → head (c); **option chain** and **calendar** → each has its own copy of head (a), 16 × 3 (auxiliary, weight 0.5) |
| 3. Committee | combines the four specialists' 32-number outputs through a learned gate (one weight per specialist per row, adding to 1, which shows which specialist it leans on), then 64 units → **head (a)**, 16 × 3 | Level 2 only | head (a), weight 1 |
| 4. Calibration and abstention | one temperature per cell-side, fitted on the validation slice (the last 20% of training sessions, never test rows), applied to the committee's probabilities. **Abstains** (no trade) when the 3 seeds disagree: the spread (max − min) of P(+X first) for the chosen cell-side above **0.10** | Level 3's output | validation slice only |

The loss is the weighted mean of the terms above. Training settings (optimiser, epochs, early stopping, seeds,
scalers on training rows only) are section 5's.

**How it is judged.** Every rule S1-S5 is run twice: with N1 and with N2 (10 rule tests). BH runs over all 10, in
the walk-forward and in the holdout, so adding N2 makes every rule's bar a little harder, not easier. The gates of
section 6 are unchanged, and the holdout is still read once, for both networks together. Neither network is
picked on holdout results: both are reported in full. The model tests of section 5 are also run for N2: one BH
family of 40 cells (20 per network). N2's abstention is a part of N2. It is reported (how many signals it held back),
never tuned.

**Tests added:** the slow encoder reads only sessions before today (point-in-time); the temperatures are fitted on
the validation slice only; abstention removes exactly the rows whose seed spread is above 0.10.

**Addendum to amendment 1, the same day, also before any fit.** A test found that the encoder of section 5 (4 causal layers, kernel 3,
dilations 1/2/4/8) reaches back only about 31 minutes from its last step, plus the 15-minute average: about 45 of the
120 minutes. The encoder output now also includes the average over all 120 minutes, so the whole window counts.
This applies to N1 and to N2's fast encoder. Nothing else changes.

## Amendment 2 (28 Oct 2026, after the first walk-forward, BEFORE the holdout): the lake cleaning was missing

**What happened.** The first walk-forward ran on option prices that had not been through the repo's lake cleaning
(`research/hunt/lakeclean.py`, used by R51 onward). It is kept unchanged as `results/wf_uncleaned.json` and
`results/report_wf_uncleaned.md`. Checking its two passing rules (N1-S4, N1-S5) found trades priced on junk prints.
For example, MIDCPNIFTY puts on 3 Feb 2026 showed flat zero-volume prices of Rs 134, then 256.8, then 528 within six
minutes. One trade "lost" Rs 14,923 on 1 lot. That is a data error, not a market move.

**The fix (a bug fix, applied before any holdout read; no rule, threshold, grid, model or gate changes):**
1. Every session's option rows go through `lakeclean.clean(..., stale=True)` (steps 1-3: rows of another index,
   stray opens / capped highs, stale impossible prints) against that session's index closes. Expiry detection keeps
   reading the raw rows, as lakeclean's notes say.
2. A minute with zero traded volume is the lake's carried-forward price (HUNT_LAKE_STALE_PRINTS.md), so it is
   treated as section 4 already says: *"bars without a print are skipped (never priced)"*. No entry, target fill,
   stop or time exit happens at a price nobody traded. The chain-state inputs (context) are unchanged.

The design rows are rebuilt and the walk-forward is run again with the same code, seeds and gates. Only the second
walk-forward counts for the gates. The first is reported beside it, so the effect of the fix is visible. Seeing the
first run's results is a weakness. It is disclosed. The fix was chosen because the repo's own data rules require it,
not because of which rules passed.
