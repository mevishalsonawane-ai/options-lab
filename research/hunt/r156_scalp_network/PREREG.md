# R156 pre-registration: a neural network trained on the scalp itself

Written 10 Nov 2026, **before any R156 label, model or number was computed** (committed and pushed on
`claude/r156-scalp-network` ahead of the code's first run). Research only; nothing in `android/**` changes.

Boss, 10 Nov, after R154 (0 of 792) and R155 (0 of 280): *"Try harder, create a neuro network, to understand scalping
and provide me results."*

**Signal source: the index.** The network reads the index's own 1-minute bars and index-derived context; the option
chain (OI, PCR, the option's price / OI change) and India VIX enter only as context, which CLAUDE.md allows. It buys the
1-ITM nearest-expiry option of the index (the arms' strike rule). It never trades from an option's own chart.

## 0. What is known, and what is new here

- R154: 10-15 point scalps lose with every entry (premium points win 49% vs 58% needed; index points 43% vs 51%).
  R155: 20 filters and a logistic picker lose less at best (-Rs 73 / -Rs 64 a trade), never positive.
- R16, B1, R41, R123, R124: the SIZE of the next 15-30 minutes is predictable (R124's network AUC 0.85); its DIRECTION
  was a coin in all five.
- **New in R156:** (i) the network is trained on the scalp's own net result after charges and slippage, not on a proxy;
  (ii) at EVERY 5-minute moment of every session (no entry rule decides when), for both sides, so it has about 1.3
  million examples; (iii) it reads the raw last hour of 1-minute bars through a convolutional network plus every R155
  feature; (iv) it chooses when to trade, the side and the exit rule.

## 1. Examples (labels)

- **Moments:** every session of every index with options (R154's stores: NIFTY, BANKNIFTY, FINNIFTY from Aug 2021,
  MIDCPNIFTY from Jan 2023, SENSEX from May 2023, to 19 Oct 2026); decisions at the close of each 09:15-anchored
  5-minute bar from 09:19 to 14:44 (66 a session), entry at the next minute's open, both sides (call, put).
- **Label, per moment x side x exit rule:** the scalp's real result under each of R154's 24 exit rules (A: premium
  points, B: index points; 10 / 12 / 15 points; stop 1:1 or 1:1.5; 15 / 30 minutes; 15:15 square-off, 15:05 on expiry),
  1 lot, priced by R154's engine on the one contract bought (R17 fills, dated Zerodha charges): its net in rupees, whether
  it won (net > 0), and its exit minute. A moment with no fill has no label.

## 2. Inputs (all known at the decision minute s; the look-ahead guard erases everything after s)

- **Sequence:** the last 60 one-minute index bars up to s (earlier sessions are not read; minutes before 09:15 are
  padding), 5 channels: close-to-close return, (high - low), (close - open), where the close sits in the bar's range,
  and a "printed" flag. Returns and ranges are divided by the index's own scale: the mean absolute 1-minute return of
  the previous 20 sessions. **For a put the sequence is mirrored** (returns negated, close location flipped), so one
  network answers "trade in this direction?" for both sides.
- **Table (43 numbers):** R155's 27 side-signed features (distances to support / resistance ahead and behind for W5, W20,
  W50, 15-minute majors and floor pivots; crossed a level; EMA20 and session-average distances; Supertrend; OI build-up;
  PCR change; the option's price and OI change; wick / body / close-location shares of the last 5-minute and 1-minute
  bars; time of day; side) plus: India VIX level and its change since the open; the index's scale; today's range so far
  and the move since the open (in scale units, side-signed); the 1-ITM premium as a share of the index; expiry day;
  weekday; 10 / 12 / 15 points in scale units; the index (5 flags). Missing numbers are median-imputed and standardised, both
  fitted on the training sessions only.

## 3. The network (fixed now)

- Sequence branch: Conv1d(5 -> 32, k 5) - ReLU - Conv1d(32 -> 32, k 5) - ReLU - Conv1d(32 -> 32, k 3) - ReLU, then
  average- and max-pooled over time (64 numbers). Table branch: Linear(43 -> 64) - ReLU. Joined: Linear(128 -> 128) -
  ReLU - Dropout(0.2) - Linear(128 -> 48): for each of the 24 exit rules a win logit and an expected net (rupees / 1,000).
- Loss: binary cross-entropy on win + Huber (delta 1) on net / 1,000, averaged over the labelled outputs.
- Training: Adam (lr 1e-3, weight decay 1e-5), batch 2,048, at most 6 epochs; the last 10% of the training sessions
  (by date) are held for early stopping (the epoch with the lowest validation loss is kept). **Ensemble of 3 seeds**
  (156, 157, 158); a forecast is their average.
- **Walk-forward, expanding:** a model sees only sessions before its fold.

| fold scored | trained on |
|---|---|
| 2023 | Aug 2021 - Dec 2022 |
| 2024 | Aug 2021 - Dec 2023 |
| 2025 | Aug 2021 - Dec 2024 |
| Jan - Mar 2026 | Aug 2021 - Dec 2025 |
| final 1 Apr - 13 Oct 2026, and post 14 - 19 Oct | Aug 2021 - Mar 2026 |

- **Comparison model:** gradient trees (HistGradientBoostingClassifier, max_depth 3, learning_rate 0.05, max_iter 200,
  min_samples_leaf 200) on the 43 table numbers only, same folds, one per exit rule's win label; reported next to the
  network (AUC), not traded.

## 4. Trading rules from the forecasts (fixed now; 1 lot)

Walked per index and session in time order, **one open position per index at a time, at most 5 trades per index a
day** (R154's taking rule). At a free moment the rule looks at both sides:

| id | rule | tests |
|---|---|---|
| EV-r | for exit rule r: take the side with the higher forecast net if that forecast is > 0 | 24 |
| TOP-r | for exit rule r: take the side with the higher forecast win chance if it is at or above the 90th percentile of the model's own forecasts for r on its training sessions | 24 |
| BEST-A / BEST-B / BEST | take the (exit rule, side) with the highest forecast net among reading A's 12 / reading B's 12 / all 24, if > 0 | 3 |

51 tests, each pooled over the five indices (per index shown, not gated).

## 5. Periods and gates (never relaxed)

- **O1:** 2023 - Mar 2025 (walk-forward forecasts). **P2:** Apr 2025 - Mar 2026. **P3 final:** 1 Apr - 13 Oct 2026, read
  once by R156 (`results/FINAL_READ`), **a third look for scalps (R154, R155) and read by many studies: weak
  evidence.** **P4 post:** 14 - 19 Oct, reported only.
1. Net > 0 in O1, P2 and P3.
2. Beats random entries: P3, 2,000 twins (seed 156). A twin takes, for each index and session, the same number of
   trades as the rule at random moments and random sides (same exit rule; for BEST the rule's own exit rule per trade),
   drawn from that session's labelled moments; p = (1 + #twins with total net >= the rule's) / 2,001. BH q <= 0.10
   with m = 1,962 earlier (R155's tally) + 51 = 2,013.
3. At least 40 final trades.

## 6. "Understanding scalping" (reported, never gated)

- **Can the result be forecast at all?** AUC of the win forecast per exit rule and period, network vs trees vs 0.5.
- **Calibration:** forecasts cut into tenths: the forecast win chance, the real win rate, the win rate needed to break
  even and the net a trade in each tenth (O1, P2, P3). This shows how far the best moments are from break-even.
- **What it leans on:** P2 loss increase when one input group is shuffled across examples (the price sequence; support /
  resistance; trend; OI; wicks; time and calendar; VIX and scale; the index).
- **When, where, which side:** the rule's trades by hour, index, side and expiry day.
- The best and worst single rules, labelled as data-mined when they are.

## 7. What a pass means

A passing rule could become a PAPER arm only by Boss's choice (no scalp arm exists). Nothing goes live from R156.
Failures stay notes here and in `research/experiments/entries/r156-*.yaml` (one per test).

## 8. Tests

`test_r156.py`: the sequence builder (mirror for puts, padding, scale from earlier sessions only), **the look-ahead
guard** (every input at s unchanged when bars, OI and VIX after s are erased), labels against R154's engine, fold
membership (no training session on or after its fold; validation = the last 10% of training sessions), the three trading
rules on hand-made forecasts (one open, 5 a day, thresholds), the random-entry twin, calibration bins and the gates.
