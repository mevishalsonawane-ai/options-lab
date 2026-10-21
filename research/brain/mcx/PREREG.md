# MCX Data Brain (MB1): pre-registration

Written 19 Oct 2026, before any MCX brain feature, label, model, metric or trade was computed. Committed on its own,
before the code that computes them. Done before this commit: downloads, row counts and date spans only (listed in
section 1).

Boss, 19 Oct: "After commodities are done, save the data in numerical tensors, vectors, or optimized binary/columnar
file formats and save it in neuro network of 5 levels, so that we could use that to find best strategy."

Research only. Nothing in `android/**` or `.github/**` changes. **Signal source of every rule: the MCX future itself
(the underlying); option-chain data (ATM IV, put/call OI) is context only.** A rule that passes every gate goes to
paper only, with an APP_SPEC; a rule that fails stays a note.

## 0. Prior knowledge (read, not repeated)

- India brain B1 (`research/BRAIN_V1.md`): the size of the next 30/60 minutes is predictable (10-22% better than the
  best plain baseline); trend-day vs range-day is not. R16: direction is not predictable.
- MCX: M3, M4, R53, R82, R83 (0 of 13; EIA and storage fade/follow lose after costs), R85 (0 of 4 candle option-buying
  rules; "gross is about zero, costs decide"; MCX options lose for a buyer on average, realised move 0.73-0.93 of the
  priced move). R86, R87: India. This study does not re-run their rules; it asks whether a brain built on the MCX
  futures can (a) forecast move size and direction better than plain baselines and (b) turn that into rules that pay.

## 1. Data (raw data never in git; large files go to Drive `gdrive:IraAlgo-data-lake/derived/mcx_brain/`)

| source | content | span | role |
|---|---|---|---|
| `options_rolling_1m/opt_<SYM>_MONTH_*.parquet` (Drive lake) | per minute: the near future's price (`spot`, the future under the nearest monthly option), and ATM-5..+5 call/put close, IV, OI, volume | 5 Aug 2025 - 13 Oct 2026; GOLD, GOLDM, SILVER, SILVERM, CRUDEOIL, NATURALGAS | the long minute series (CLOSE-only) and the option context |
| `fut_1m_live_mcx` (Dhan, fetched 19-20 Oct in this session with `research/data/lake_fetch.py`, the Drive copy being 14 Oct) | true 1-minute OHLC, volume, OI of every listed contract | from each live contract's listing to 19 Oct 2026; 11 commodities | the continuous future (`research/data/mcx.py`, volume rule) for volume / OI features; the only minute source for CRUDEOILM, NATGASMINI, COPPER, ZINC, ALUMINIUM |
| `fut_daily_continuous` | near-month daily OHLC | 2012 - 19 Oct 2026 | daily context features (previous days) |

Missing, noted: MCX options for CRUDEOILM, NATGASMINI, COPPER, ZINC (being fetched by another session) and any option
minutes after 13 Oct; futures minutes of contracts that expired before 13 Oct 2026 (Dhan serves listed contracts
only), so true OHLCV/volume/OI exist only for recent months. A symbol enters a model only with >= 60 design sessions;
the others are built into the cube and tensors and described, not modelled.

**One price rule for every model: closes only.** A minute's price is its close. Ranges are close-to-close (max close
minus min close), on every source, so the option-spot months and the true-OHLC months are measured the same way. The
close comes from the option spot (median of the minute's `spot` values) through 13 Oct; after that from the
continuous future. A change of contract or of source starts a new segment: no return or window crosses it.

## 2. Splits (fixed by date)

- **Design: sessions up to 23 Jun 2026.** Embargo 24-30 Jun 2026 (used by neither).
- **Locked holdout: sessions from 1 Jul 2026** (about 75-80 sessions to 13-19 Oct). Read **once**, after every model,
  rule and threshold is frozen, by `python -m research.brain.mcx.study holdout` (a lock file refuses a second read).
- Within design: 6 equal blocks of sessions; walk-forward tests on blocks 3-6, each trained only on the blocks before
  it (`research/brain/split.walk_forward`, purge = the label window, embargo 1 day). Every model and baseline is refitted
  per fold.

## 3. Decision rows and labels

- Decision rows: every 15 minutes from 09:30 to (session close - 75 min), on the 6 option-book symbols (and any other
  symbol with >= 60 design sessions). Features at the decision row only (row t = the minute that closes at t + 1).
- `y_range_60m` = (max - min of closes t+1..t+60) / close_t x 100, modelled as its log (floor 0.001%); `y_ret_60m` =
  log(close_{t+60} / close_t) x 100. Same session and segment only (NaN otherwise).

## 4. Level 1: specialists (one question each)

Every specialist has a linear model (ridge alpha 1 / logistic C 1, standardised, training-median fill) and a primary
model: scikit-learn HistGradientBoosting (max_iter 200, learning_rate 0.05, max_leaf_nodes 15, min_samples_leaf 50,
l2 1.0, random_state 0; loss absolute_error for regression, log_loss for classification). One model per symbol family
is NOT allowed: models are fitted per symbol.

| id | question | target | baselines (best one counts) | bar |
|---|---|---|---|---|
| L1-A range | how big is the next hour? | log `y_range_60m` | B-tod (mean log range of the same clock window, previous 20 sessions), B-last (log range of the last 60 minutes), B-iv (a + b log(ATM IV x sqrt(60 / 525,600)), fitted in fold; B-tod where IV is missing) | MAE skill >= 5% vs best baseline and BH q <= 0.05 |
| L1-B persistence | does the last hour's move continue? | 1 if sign(`y_ret_60m`) = sign(last-60 return) (rows with a non-zero last-60 return) | training base rate | AUC >= 0.55, log-loss below the base rate, q <= 0.05 |
| L1-C direction | up or down over the next hour? | 1 if `y_ret_60m` > 0 | training base rate | as L1-B |
| L1-D session | the US open (09:30 ET): (i) how big is the next 150 minutes? (ii) does it continue the day's move so far? | log close-range of the US-open window; continuation 0/1 | (i) mean of the previous 20 sessions; (ii) base rate | (i) as L1-A; (ii) as L1-B |
| L1-E events | EIA crude (CRUDEOIL), EIA gas storage (NATURALGAS), US CPI / NFP (all 6) | (i) log ratio of the 60 minutes after the release to the same clock window's median over the previous 20 non-event sessions; (ii) does the first 60 minutes follow the 60 minutes before? | (i) 0; (ii) 50% | (i) mean log ratio > 0, day-bootstrap p, q <= 0.05 (descriptive: is an event day bigger?); (ii) hit rate vs 50%, binomial p, q <= 0.05 |

Calendars: R83's EIA and storage calendars (`research/hunt/r83/events.py`), R82's CPI / NFP lists. FOMC (14:00 ET)
falls at or after the MCX close and is a feature flag only.

p-values: one-sided paired session-block bootstrap (B = 2000, seed 0) of the per-session mean loss difference
(regression: absolute error; classification: log-loss) against the best baseline. **One BH family** over every L1, L2
and L3 primary test (q = 0.05).

## 5. Level 2: sequence encoder (market-state vector)

- Input window: the last 120 minutes in 24 five-minute steps x 6 channels: 5-minute log return / the rolling 60-minute
  realised volatility (clipped +-5), position of the close in the window's close range, ATM IV z-score over the last
  20 sessions (0 where missing) and its missing mask, time of day (sin, cos). 144 numbers, float32.
- Model: a self-supervised autoencoder, scikit-learn MLPRegressor 144-64-16-64-144 (tanh, adam, alpha 1e-4,
  max_iter 200, early stopping off, random_state 0), trained per fold on that fold's training windows only, all
  symbols pooled (the windows are scale-free). The state vector = the 16 bottleneck values. Baseline: PCA-16.
- Test (per symbol, walk-forward): the L1-A and L1-C primary models with and without the 16 state values.
  Bar: MAE better by >= 1% (L1-A) or AUC better by >= 0.01 (L1-C), q <= 0.05.
- Descriptive: KMeans (k = 6, seed 0) on the training states = "market states"; each state's next-hour range and
  up-rate on the test blocks (for the cards).

## 6. Level 3: committee (calibrates and abstains)

- Size: the mean of the L1-A ridge, L1-A HGB and L1-A HGB + state forecasts. An 80% band from the training rows'
  out-of-time residuals (inner split: first 70% of training sessions fit, last 30% give residuals). Abstain when the
  members' spread is above its training 80th percentile. Report: MAE on all rows and on kept rows, band coverage
  (target 80%).
- Direction: logistic stack of the L1-B and L1-C probabilities (+ the state vector) fitted on the inner 30%, isotonic
  calibration on the same rows; **acts only when p >= 0.55 or p <= 0.45**, else abstains. Bar: on acted rows, hit rate
  >= 53% with >= 300 acted rows and binomial q <= 0.05.

## 7. Level 4: strategy search (rules from the brain's outputs)

Traded instrument: one lot of the mini future (GOLDM; SILVERM; CRUDEOILM priced from the CRUDEOIL series; NATGASMINI
from the NATURALGAS series), priced on minute closes. Entry = the close of the minute after the decision row. Stops and
targets are checked on minute closes (a jump through a level fills at the close that crossed it). Everything is flat
15 minutes before the session close. Costs: R53's Zerodha MCX futures charges (McxCosts), R53's futures half-spread on
entry and exit, plus 0.02% stop slippage (R83). Option rule: R53's MCX option charges and option half-spread, printed
option minutes only.

| rule | entry (signal on the future) | exit | variants |
|---|---|---|---|
| B1 committee direction | committee acts (not abstained): long if p >= thr, short if p <= 1 - thr | 60 minutes; stop 1.0 x predicted 60-min range | thr 0.55, 0.60 |
| B2 big-size follow | predicted range in the top tercile (top fifth) of the fold's training forecasts and abs(last-30 return) >= 0.33 x predicted range: follow it | target 1.0 x, stop 0.75 x predicted range, 60 minutes | top 1/3, top 1/5 |
| B3 quiet fade | predicted range in the bottom tercile and the close >= k x predicted range away from the session mean of closes: fade | target the session mean, stop 0.75 x predicted range, 60 minutes | k 1.0, 1.5 |
| B4 size edge straddle (option BUY) | predicted range / ATM-IV-implied 60-minute range in the top decile (top fifth) of the fold's training rows: buy the ATM call and the ATM put | 60 minutes; stop when the straddle has lost 30% | top 10%, top 20% |

4 rules x 2 variants x 4 symbols = 32 variant tests. The brain's forecasts used by the rules are the walk-forward test
forecasts (blocks 3-6), so every design trade uses a model fitted only on earlier sessions.

- Variant pick: walk-forward over the 4 test blocks (from block 4 on, the variant with the best net on the blocks
  before it); frozen variant = the best net over blocks 3-6.
- Random twins (200, seed 20261019): the same number of trades at random decision rows of the same symbol, test blocks
  and time-of-day hour, random side (B1-B3) or the same straddle (B4), with the rule's own exits.
- **Design gate** (all): frozen net > 0 after costs; walk-forward net > 0; twin p of the frozen variant with BH over the
  32 tests q <= 0.10; >= 30 trades; net > 0 without its 5 best trades.
- **Holdout gate** (passers only, models refitted on all design sessions, one read): net > 0; twin p < 0.10; >= 20
  trades; net > 0 without the 5 best trades; worst month above -Rs 13,000 (Boss's account rule). If no rule passes the
  design gate, the holdout is not read for rules.

## 8. Holdout read for the predictions

After L1-L3 are frozen, the holdout is read once for the prediction tests (models refitted on all design sessions):
L1-A skill, L1-C AUC, committee hit rate on acted rows, each with its sample size. Reported as they come; nothing is
changed after.

## 9. Level 5: knowledge (not a test)

Plain-English cards for Jarvis and Boss (`research/brain/mcx/results/cards.json` and the BRAIN write-up): per symbol
the usual hourly range by time of day, event-day multipliers, the market states, and what the brain can and cannot
forecast, each with its sample size.

## 10. Not allowed

Reading any session from 24 Jun 2026 on before the holdout step; changing a target, bar, model setting, feature list,
rule or variant after a result (a change needs an amendment committed before it is run, and counts in the BH family);
dropping a test from the report.
