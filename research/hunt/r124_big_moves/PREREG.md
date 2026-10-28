# R124 pre-registration: every 30-point move, traced back. What came before it, and can a network see it coming?

Written 28 Oct 2026, before any move was counted or any model fitted. Research only: nothing in the app changes, and
nothing trades.

Boss, 28 Oct: *"do more deep research/backtest each move (could be multiple candles) who has moved 30 points+ (in any
direction) and backtrack it, and find what has caused it, use the neural network."*

## 0. What is asked, in plain words

1. **Find every move:** every time NIFTY went 30 points or more in one direction, over one or many candles.
2. **Trace it back:** what did the market look like just before each move started, compared with ordinary moments
   at the same time of day?
3. **Use the network:** train it to say "a 30-point move is coming in the next 15 / 30 minutes, and which way". Then
   ask which inputs it leans on.
4. **The money test:** if the network says a move is coming, does buying the ATM option (or a straddle) make money
   after costs?

**Honest limit, said up front:** a study of data can show what usually came *before* a move (an association). It
cannot prove what *caused* it. News, large orders and global markets are not in the lake.

## 1. Earlier results this builds on

| study | finding | what R124 does with it |
|---|---|---|
| R16, B1, R41, R123 | the size of the next 30-60 minutes is predictable (R² 0.40-0.43); its direction is a coin | the "a move is coming" question is a size question, which should be the easier part. Direction is tested separately, given that a move comes |
| R123 | its morning put pick made money on the holdout but on too few trades; the 70% rule lost | R124 asks a different question: the index move itself, not an option-rupee target |
| R113 | the paper arms earn in 09:15-09:30 | time of day is a pre-registered factor; rule M3 is morning-only |

## 2. Definitions

- **Index and size.**
  - NIFTY, literally **30 points**.
  - The other four indices (BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX) use the same size *as a share of price*:
    30 / NIFTY's open that day, times the index's own open. They are reported separately.
- **A move (part A, the census):** a zigzag on one-minute highs and lows, within one session.
  - A leg runs from a turning point to an extreme. It ends when price comes back **10 points** (NIFTY; scaled
    elsewhere) from that extreme.
  - A **big move** is a leg of 30 points or more.
  - Its *start* is the turning point. Its *confirmation* time is when the 10-point turn-back that ends the previous leg
    happens: that is when the start becomes knowable.
- **Before the move:** the state at the close of the bar just before the start, built from bars at or before that
  minute only.
- **Controls:** every other minute of the same index, in the same 30-minute time-of-day slot, that is not within 5
  minutes of a big-move start.

## 3. Periods (as R123)

| part | dates |
|---|---|
| design | 2021-08-01 .. 2026-03-15; walk-forward folds 2023, 2024, 2025, 2026-01-01..03-15 |
| embargo | 2026-03-16 .. 03-31 |
| holdout | 2026-04-01 .. 2026-10-19, read once |

The holdout is **not virgin**. R123 read it today for its own rules, and R106 / R113 / R120-R122 read it earlier for
the arms. Nothing about 30-point moves, and no R124 model, has been run on it.

## 4. Part A: the census (descriptive)

For NIFTY big moves in design, report:
- how many there are, per day, by size, duration and direction;
- by time of day, weekday, expiry day and gap.

**The factors traced back** (pre-registered, 20; each is measured at the minute before the start):

| # | factor |
|---|---|
| F1 | time of day (30-minute slot) |
| F2 | compression: the last 30 minutes' range divided by the 20-day average daily range |
| F3 | the last 5 minutes' move, *in the direction of the coming move* (momentum into it) |
| F4 | the last 15 minutes' move, in the direction of the coming move |
| F5 | distance to the day's open |
| F6 | distance to the previous day's high |
| F7 | distance to the previous day's low |
| F8 | distance to the previous close |
| F9 | distance to the opening-range (09:15-09:29) high or low |
| F10 | distance to the nearest round 100 |
| F11 | India VIX level |
| F12 | India VIX 30-minute change |
| F13 | breadth: the other indices' last-15-minute move, in the direction of the coming move |
| F14 | PCR change over 15 minutes |
| F15 | call OI change over 15 minutes |
| F16 | put OI change over 15 minutes |
| F17 | ATM straddle as % of spot |
| F18 | expiry day |
| F19 | size of the opening gap |
| F20 | minutes since the last *confirmed* big move ended, and whether the new move continues or reverses it |

For each factor: the start-vs-control difference (standardised), and the **lift** (rate of big-move starts per
quintile of the factor ÷ overall rate, within time-of-day slot). Each comes with a 95% day-block bootstrap CI and a
p-value. BH runs over the 20 factors.

"Direction-signed" factors (F3, F4, F13) use the coming move's direction, which is only known afterwards. They
describe the move; they are never inputs to a forecast.

**Out-of-sample check:** every factor with q ≤ 0.05 in design is re-measured once on the holdout. The question is
whether its lift keeps the same side of 1 with its CI.

## 5. Part B: the network

- **Rows:** R123's decision grid (every 5 minutes, decisions 09:20-14:55, all five indices). Inputs are R123's
  point-in-time inputs (sequence, index state, VIX, breadth, calendar, option chain), plus the level distances
  F5-F10 and F20 (point-in-time versions: only confirmed legs count).
- **Labels**, from the close at t, for H = 15 and 30 minutes:
  - *up* if the index touches +30 (scaled) before -30;
  - *down* if the reverse;
  - *neither* if it touches neither by t+H or by the square-off.
  - A bar that touches both is *unclear* and masked out.
- **Model:** R123's multi-level network N2. It keeps its two encoders, four specialists (plus a fifth, *levels*, for
  the new inputs), the gated committee and the Level-4 calibration and abstention. Settings are R123's (3 seeds,
  early stopping, scalers fitted on training rows only). Its heads: the 3 classes for H 15 and 30, the next-30/60
  range and direction.
- **Baselines:** base rate per index and hour; gradient-boosted trees on the same tabular inputs.
- **Tests.** Network vs the best baseline, pooled walk-forward (3 of 4 folds better), then the holdout. BH runs
  over 4 cells.
  - *Move coming* = P(up) + P(down): Brier score, and AUC.
  - *Direction given a move* (rows where a move came): log-loss of P(up) / (P(up) + P(down)), and AUC.
- **What the network leans on:** permutation importance by input group on the walk-forward predictions. For
  each group in turn (fast encoder, slow encoder, size, direction, chain, calendar, levels), shuffle it across rows
  within a fold and measure how much worse the loss gets. That says what the network uses. It is not a cause.

## 6. Part C: the money test (signal on the underlying, trade the option)

Common to all rules:
- 1 lot at today's lots, ATM, cleaned option bars (R123 amendment 2), dated charges and liquidity-aware slippage.
- At most 5 trades a day; one open trade per index.
- Exits are on the **index**: the trade is sold at the close of the option's bar in the minute the index touches the
  threshold either way (target in the trade's favour, stop against it), else at the close of t+H, or at the
  square-off (15:20; 15:05 on expiry days).
- An option minute with no trade is never priced. If the exit minute has no option print, the last traded close
  before it is used.
- Thresholds use the training rows only: "top 10%" is the 90th percentile of P(move) on that fold's training rows.

| rule | enter when | buys |
|---|---|---|
| **M1** | P(move in 15 min) is in the top 10%, and the move's direction share P(up)/(P(up)+P(down)) is ≥ 0.60 or ≤ 0.40 | the ATM call (up) or put (down); exits ±30 / 15 min |
| **M2** | as M1 for 30 minutes | ATM call or put; exits ±30 / 30 min |
| **M3** | M1 at the 09:20 / 09:25 / 09:30 decisions only | as M1 |
| **M4** | P(move in 15 min) in the top 10% (no direction needed) | the ATM **straddle** (call + put); exits when the index touches ±30 either way, or at 15 min |

**Twins:** 1,000 random-entry twins with the same exits (a random row of the same index and day where the trade is
defined, a random side; for M4, a random row). For M1-M3 there are also same-row, random-side twins, reported only.

**Gates (as R123):**
- Walk-forward: ≥ 300 trades; positive average net at 1x and 2x slippage; positive in 3 of 4 folds; BH q ≤ 0.10
  over the 4 rules.
- Holdout: ≥ 100 trades; positive at 1x and 2x; q ≤ 0.10; Boss's account stop not breached (drawdown > -Rs 40,000,
  no month below -Rs 13,000).
- Reported per rule: n, win rate, average win and loss, average net (1x, 2x), per session and per trading day, max
  drawdown, worst day and month, by fold and by index.
- A rule that passes everything is *proposed* for a paper test (CLAUDE.md), not built here.

## 7. Tests (before any result)

The zigzag (legs, the 10-point turn-back, the 30-point filter, no crossing between sessions, the confirmation time);
factors read bars at or before the start only; labels read bars after t only (and a both-ways bar is masked); the
index-exit option simulator; the quantile threshold uses training rows only; the holdout lock; BH.

## 8. Outputs

- Code and small results: `research/hunt/r124_big_moves/`.
- Big files: `gdrive:IraAlgo-data-lake/derived/r124/`.
- Write-up: `research/HUNT_R124_BIG_MOVES.md`.
- Experiment entries: `research/experiments/entries/r124-*.yaml`.
- RESULT.md.

## Amendment 1 (28 Oct 2026, before any move was counted or any model fitted): wicks, never opens or closes

Boss, 28 Oct: *"always check the head and tail (i.e. highest point / lowest of candle), don't check closing / opening
point."* Every move is measured on candle **highs and lows**:

1. **Census zigzag:** already on highs and lows. A leg runs from the lowest low to the highest high (or the reverse),
   and the 10-point turn-back is measured from the extreme's wick.
2. **Network labels (replacing section 5's "from the close at t"):** inside the window of bars t+1 .. t+H, *up* means
   some bar's high is ≥ 30 above an earlier-or-same bar's low in that window. *Down* means some bar's low is ≥ 30 below
   an earlier-or-same bar's high. Whichever completes first (by the bar that completes it) is the label. If none
   completes, the label is *neither*. If both complete in the same bar, it is *unclear* (masked).
3. **Factors F3, F4, F13 (momentum into the move):** for an up move, the start's low minus the highest high of the
   last 5 / 15 minutes (≤ 0: price fell into the start) and, mirrored, for a down move. For F13, the same on the other
   indices, as % of price.
4. **Distances F5-F10:** measured from the start's wick (the pivot low of an up move, the pivot high of a down move).
5. **Trades (part C):**
   - The target and stop must be measured from the price actually bought: the index at the entry, the open of bar
     t+1, because that is when the option is filled.
   - The touches are checked on **wicks**: target when a bar's high (call) or low (put) reaches entry ± 30; stop on the
     opposite wick.
   - The straddle exits on either wick touch.
   - If both touch in one bar, the stop is taken (worst case).
   - The option is sold at that minute's option close: the option's own bar is the only price at which a fill is known
     for that minute.

Nothing else changes.
