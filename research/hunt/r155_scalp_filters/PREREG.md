# R155 pre-registration: every Donchian filter, OI and candle wicks on the 10-15 point scalps

Written 9 Nov 2026, **before any R155 number was computed** (committed and pushed on `claude/r155-scalp-filters` ahead of
the code's first run). Research only; nothing in `android/**` changes.

Boss, 9 Nov, after R154 (0 of 792 scalps passed): *"Use all the things we tried on donchain like multiple support and
resistance lines at onces, w50 and many more like oi and all other things, and test with all candle wigs too."*

**Signal source: the index** for every entry (R154's, unchanged). The filters below read the index's own bars (levels,
pivots, EMA, Supertrend, average price, candle wicks) or the option chain as context around the index signal (OI, PCR),
which CLAUDE.md allows. No rule reads its signal from an option's own chart; F13 (R144c's FT3 rule) reads the bought
option's price and OI as a filter around the index signal, as FT3 already does in the app.

## 0. What is already known

- **R154:** 10-15 point scalps lose on every entry and index: premium points win 49% vs 58% needed (-Rs 101 a trade),
  index points 43% vs 51% (-Rs 72). The four plain entries beat random entries by only Rs 13-14 a trade.
  **R154's final period (Apr - 13 Oct 2026) has therefore been read for these scalps; R155 reads it again with filters:
  a second look, disclosed.**
- **What was tried on Donchian (the source of R155's filters):** R150 (support / resistance lines at once: W5 / W20 on
  5-minute levels, 15-minute 3-month major levels, B20 "the break crossed a level", floor pivots, EMA20, VWAP / EMA cross),
  R151 (one picker on all of these features), R144c (FT3: option price and OI both falling), R125 (OI build-up, PCR),
  R115 / R28B / R91 (candle shape, wicks, close location, strong body), R73 / R112 (Supertrend). On the Donchian arms the
  best of these (W20) beat random skips on R51 but none passed every gate.
- **"W50"** is not a rule in the repo; it is read as R150's wall-ahead rule with a 50-session lookback (W5 / W20 are its
  5- and 20-session versions), and all three are tested.

## 1. Base (unchanged from R154)

R154's engine, stores, fills, charges, exits, entries, taking rules and periods, exactly: 7 entries (R72, R51 +
Supertrend, Liquidity 15+5, E1 OR15, E2 TWAPX, E3 EMA921, E4 PBB) x their indices x R154's 24 exit rules (A: premium
points, B: index points; 10 / 12 / 15 points; stop 1:1 or 1:1.5; 15 or 30 minutes; 15:15 square-off, 15:05 expiry), 1 lot.
A filter removes a SIGNAL before the walk (so a skipped signal frees the position, as R101); the arms' own taking rules
and R154's plain walk (one open, 5 a day) then run on what is left. The unfiltered run (F0) must reproduce R154's 1-lot
results for every entry, index, rule and period (asserted).

## 2. The filters (fixed now; each is read at the decision minute s = entry row - 1, from bars and OI rows <= s)

Levels follow R150 exactly (`hunt/r150_sr_levels/levels.py`): pivots on 09:15-anchored index bars known only once the K
bars after them have closed; prices grouped within 0.15%; a level needs 3+ turns; "ahead" = above a call / below a put,
within 0.25% of the index close at s. "Last closed 5-minute bar" = the bar whose last row is <= s.

| id | family | the trade is TAKEN only when |
|---|---|---|
| F1 W5 | S/R | no 3+-turn 5-minute level (pivots 6 bars each side, last 5 sessions) is ahead within 0.25% |
| F2 W20 | S/R | the same, last 20 sessions (R150 B's lead) |
| F3 W50 | S/R | the same, last 50 sessions (Boss's "W50") |
| F4 W3M | S/R | no 3+-turn 15-minute MAJOR level (pivots 12 bars each side, last 63 sessions; R150 A1) is ahead within 0.25% |
| F5 PIV | S/R | none of the previous session's floor pivots (P, R1, S1, R2, S2; R150 D1) is ahead within 0.25% |
| F6 ALLSR | S/R | **all lines at once**: none of F2, F3, F4 and F5's levels is ahead within 0.25% |
| F7 B20 | S/R | the index's move from s - 5 to s crossed a 3+-turn 20-session 5-minute level the trade's way (R150 B20) |
| F8 EMA20 | trend | the last closed 5-minute close is on the trade's side of its 20-bar EMA (continuous; R150 D2) |
| F9 ST | trend | Supertrend (ATR 10, x3) on 5-minute bars points the trade's way at the last closed bar (R73 / R112) |
| F10 TWAP | trend | the index close at s is on the trade's side of the session's running average price (R154's VWAP stand-in) |
| F11 OIB | OI | R125's OI build-up score over the last 15 minutes, ATM +-3 strikes, is > 0 for a call / < 0 for a put (+1 a call strike with premium up and OI up or a put strike with premium down and OI up; -1 the mirror; averaged) |
| F12 PCR | OI | the PCR (put OI / call OI, ATM +-10) rose over the last 15 minutes for a call / fell for a put |
| F13 FT3 | OI | NOT (the bought option's close AND its OI both fell over the 15 minutes before s) (R144c FT3) |
| F14 WKA5 | wick | the last closed 5-minute bar's wick AGAINST the trade (upper for a call, lower for a put) is < 50% of its range |
| F15 WKA1 | wick | the same on the 1-minute bar at s |
| F16 WKF5 | wick | the last closed 5-minute bar's wick FOR the trade (lower for a call, upper for a put) is >= 50% of its range (a rejection / hammer the trade's way) |
| F17 CLOC | wick | the last closed 5-minute close lies in the trade-side third of its range |
| F18 BODY | wick | the last closed 5-minute bar's body is >= 60% of its range and coloured the trade's way (R91) |
| F19 STACK | all | at least 5 of these 7 agree: F6, F8, F9, F10, F11, F12, F17 (no fitting) |
| F20 PICK | all | R151's way: an L2 logistic (C = 0.1, standardised, median-imputed) on every number behind F1-F18 (distances to the nearest level ahead / behind per lookback, touches, EMA / TWAP distances, Supertrend side, OI score, PCR change, the option's price and OI change, wick / body / close-location shares of both bars, time of day, side), per entry x exit rule, label = the scalp won (net > 0); trained on the unfiltered trades of every earlier day and refitted at each quarter (walk-forward: P2's four quarters and the final each scored by a model trained on all trades before it; inside P1, yearly). The trade is taken when the predicted win chance is >= the break-even win rate of the training trades. Days with fewer than 300 earlier trades are not scored (taken). |

A filter that cannot be read (too few bars, no OI print) does not confirm: the signal is skipped (R101's rule). For the
arms, F9 duplicates R51's own Supertrend; it is still run.

## 3. Tests and gates (never relaxed)

**Gated unit: filter x entry x reading** = 20 x 7 x 2 = **280 tests**, each pooled over the entry's indices and the 12
exit rules of the reading (each exit rule's trades counted once; the pool is the average exit variant), 1 lot.
1. Net > 0 in P1, P2 and P3 (R154's periods).
2. Better than the unfiltered scalp (F0): net per trade higher in P2 AND P3.
3. Beats random skips: P3, 2,000 random subsets of F0's pooled P3 trades of the same size (seed 155); p = (1 + #subsets
   with total net >= the filter's) / 2,001. BH q <= 0.10 with m = 1,682 earlier (R154's tally) + 280 = 1,962.
4. At least 40 final trades per exit rule on average (pooled P3 trades / 12 >= 40).
5. Arms only: the pooled net in P2 and P3 above the arm's own exits (B0) on the same indices.

**Descriptive (never gated):** every filter x entry x index x exit cell (20 x 792 = 15,840) in every period, the count
of cells positive in P1, P2 and P3, and the best cells, labelled as data-mined; win rate vs break-even per filter; lots
2 and 3 for any passing test; the post window (14 - 19 Oct, read once).

## 4. What a pass means

A passing filter could act on PAPER for that scalp only after Boss chooses to run a scalp arm at all (none exists).
Nothing goes live. Failures stay notes here and in `research/experiments/entries/r155-*.yaml` (one per filter x entry x
reading).

## 5. Tests

`test_r155.py`: each filter on hand-built bars, the look-ahead guard (every filter's decision at s is unchanged when bars
and OI rows after s are erased), level windows (no pivot before its confirmation; 5 / 20 / 50 / 63 sessions), the OI
score, PCR and FT3 rules, wick / body / close-location shares, STACK's vote, the picker's folds (no training trade on or
after the fold's first day), F0 parity with R154, the twin and BH.
