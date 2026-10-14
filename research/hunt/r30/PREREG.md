# R30 pre-registration: every parameter at once (multi-factor CE/PE buying) on the Dhan lake

Written 14 Oct 2026, BEFORE any R30 label, trade outcome or model result was computed. Committed and pushed on its own.
After this commit nothing below changes; a change needs a new entry and a new pre-registration.

Boss (14 Oct 2026, after R29): "use all other combination too whatever you think of, and also combination of all other
parameters too, which play a good role in trading" and "always check the regular index, use all indicators and data on
it, and on that basis buy call / put". R30 does exactly that: the index plus everything known about it at that minute
(EMA ribbon, every Data Brain indicator, option chain OI / IV / PCR / straddle / volume, VIX, calendar, the other
indices) decides CE, PE or no trade. Research only: no app change, nothing trades.

What is already known, so R30 is not a re-run: R29 (EMA ribbons alone) and H25 (195 single signals) found no edge for a
single family of signals; BRAIN_V1 found the SIZE of the next 30-60 minutes predictable on every index, the direction
not. R30's new ideas: (a) many parameters at once in one model, (b) trade direction only when a big move is also
expected, (c) a debit spread that loses less to time decay, (d) 2- and 3-parameter combinations found on earlier years
and tested on later ones. F1 (`claude/f1-india-factory`) screens single indicators and at most 20 rules; R30 does the
multi-factor part and does not repeat F1.

## Data and periods

- Lake (Drive `IraAlgo-data-lake`): `index_1m` NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX, INDIAVIX;
  `options_rolling_1m/opt_<UND>_WEEK_<YEAR>` (nearest expiry, ATM±10, with OI, IV, volume).
- **F1's locked holdout, 1 Jan 2023 – 30 Jun 2024, is never loaded by R30** (`r30data.check_day` raises). R30 uses
  P1 = Aug 2021 – 31 Dec 2022 and P2 = 1 Jul 2024 – 12 Oct 2026 only.
- **Walk-forward (expanding window, fixed procedure, nothing tuned by hand):**

  | fold | train on | test on |
  |---|---|---|
  | F1 | Aug 2021 – Dec 2022 | 1 Jul – 31 Dec 2024 |
  | F2 | the above + Jul – Dec 2024 | 2025 |
  | F3 | the above + 2025 | 1 Jan – 12 Oct 2026 |

  Out-of-sample = the three test blocks put together. SENSEX has no options before May 2023, so its first test fold is
  F2 (models are trained on all indices together, with the index as a feature, so SENSEX still gets a model).
- **The final test is forward paper trading** from 15 Oct 2026 (at least 40 sessions), because every block of the lake
  has now been read by some study. A strategy that passes the walk-forward gate below becomes a candidate for that
  paper test, behind a switch that is off by default. Never live from R30.
- Honest caveat: the lake's 2024-26 sessions were read by many earlier studies (and R29 read EMA-pattern outcomes on
  2024-26). R30's procedure is fixed here before its own outcomes, but the researcher has seen R29's.

## Decision grid, features (point in time) and labels

- **Grid:** every 5-minute bar close from 09:30 to 14:45 (64 a day per index). The order fills at the next minute's open.
- **Features at a grid row use only data up to that minute** (the brain features are covered by the look-ahead guard;
  R30's own chain features have their own guard test):
  - **164 Data Brain L4 features** computable from index minutes + VIX + calendar (price, structure, 102 indicators
    incl. the 77 EMA-ribbon features, candles, calendar, market).
  - **Option chain (R30, from the lake's ATM±10):** ATM IV and its 15-minute change; IV skew (OTM-2 put IV − OTM-2
    call IV); PCR of OI over the stored strikes and its 30-minute change; CE and PE OI change over 30 minutes (whole
    chain and ATM±2); ATM straddle as % of spot, vs its 09:20 value and its 15-minute change; CE / PE volume over the
    last 15 minutes and their ratio; distance to the max-OI call and put strikes (% of spot).
  - **Cross-index:** NIFTY's 15-minute and day return and 5-minute ribbon stack for the other indices; BANKNIFTY's for
    NIFTY.
  - **Index id**, days to expiry and expiry day (expiry days found from the data as in R25 / R29; NSE publishes them in
    advance, so this is not information a trader lacks).
- **Labels (future, never features):** for each grid row and side (CE / PE), the net per lot of
  - ATM with **X1** (Boss's lock: stop −30%, after +Rs 1,000 keep ≥ +Rs 500, 60 minutes),
  - ATM with **X4** (out after 30 minutes, stop −40%),
  - **SPR**: a debit spread, buy ATM and sell the strike 2 steps OTM, 1 lot each, exits on the spread's closes: stop
    at −40% of the debit, target +60%, out after 45 minutes; each leg filled with its own spread and charges;
  and the index's absolute and signed 30-minute move (bps).
  Fills, charges, square-off (15:20; 15:05 on expiry days) and missing-price handling are R29's (`hunt/r29/sim.py`).

## The strategies (the confirmatory family: 6 strategies × 5 indices = 30 tests)

All models are scikit-learn HistGradientBoosting with fixed settings (200 iterations, learning rate 0.05, 31 leaves,
min 200 rows a leaf, L2 1.0, seed 30), trained on all indices together. No hyper-parameter search.

| id | idea |
|---|---|
| M1 | regress the net of CE and of PE (ATM X1); trade the better side when its predicted net > 0 and is in the top 5% of the training fold's predictions |
| M2 | the same on the debit spread SPR |
| M3 | two-stage: a size model (predicted absolute 30-min move in the top 20% of the training fold) AND a direction model (P(up) ≥ its training fold's 80th percentile → CE; ≤ 20th → PE); ATM X1 |
| M4 | classify P(net > 0) per side (ATM X1); trade the better side when P is in the top 5% of the training fold |
| C1 | combinations: every pair of features in training-fold terciles (a grid row is in a cell); on the training years, keep the 20 cells per side with the best mean net (≥ 300 rows and ≥ 60 days, ATM X1, positive in every training year); trade when a row falls in any kept cell |
| C2 | the same restricted to (EMA-ribbon feature × any non-ribbon feature) pairs and triples (ribbon × 2 others, ≥ 300 rows) — Boss's "EMA with everything else" |

Common: one trade at a time per strategy and index, at most 3 a day; ties between CE and PE skip.

## Gate (walk-forward, per strategy and index)

A strategy-index passes if, out of sample (the test folds together):
1. net P&L > 0, and > 0 in every test fold it has;
2. p < 0.05 against random twins (1,000 sets: same day, same side, a random grid row), one-sided;
3. BH q ≤ 0.10 over the 30 tests (R30's whole family; reported with F1 and R29 combined too);
4. still > 0 at 2× the spread.

Reported for every strategy and index: trades / day, win rate, average win / loss, P&L / day, % green days, worst day,
max drawdown, big winners cut short, per fold and out of sample, p, q, sample sizes; and which features drive each model
(permutation importance on the test fold). A pass becomes a forward-paper candidate behind an off switch; a fail stays a
note.

## Amendment 1 (14 Oct 2026, before any R30 label or outcome was computed)

- **C2 triples:** "ribbon × 2 others" is about 490,000 triples per side per fold, too slow to run. The triples are
  (ribbon feature × a pair of the 30 non-ribbon features whose training-fold terciles separate the training net
  the most; mean over CE and PE of the best minus the worst tercile mean). This is chosen inside each training fold
  only. C2 pairs are unchanged (ribbon × every non-ribbon feature).
- **F1's holdout rows** are dropped as soon as they are read (index minutes, option rows; the 2023 option file is
  skipped). No grid row, feature value or label from it is used, and the guard asserts that.
- **M2's one-trade-at-a-time gate** uses the spread's 45-minute limit as the busy time (its exit minute is not stored).
  Its 2× spread stress is re-simulated for the trades taken.

## Forward test of M4-NIFTY (14 Oct 2026, after the walk-forward; before any forward session exists)

Boss said "ok" to the forward test proposed in `HUNT_R30_ALL_PARAMETERS.md`. M4-NIFTY was the near miss: positive in
every test period, p 0.025, but BH q 0.109. This is its one confirmatory test, on sessions that did not exist when
the model was frozen.

- **Model (frozen, never retrained):** M4 trained exactly as fold F3 (all five indices, every allowed day before
  1 Jan 2026), threshold 0.68231 (the 95th percentile of the training predictions), 185 inputs. frozen model sha256 `87b1bc8d4ab2fcdde8662f7dcd35bf03b0f35a813a6cbaeab508639f3fdcedbd`
  (on Drive: `IraAlgo-data-lake/derived/r30/forward/m4_frozen.pkl`). Applied to the 2026 test rows, it reproduces
  the walk-forward's 35 NIFTY trades and +Rs 5,666 exactly.
- **Rule:** NIFTY only. At each 5-minute bar close 09:30–14:45, buy the ATM CE or PE whose predicted P(net > 0) is
  higher, when it is ≥ the threshold. Next minute's open. Boss's lock exit (X1). One trade at a time, at most 3 a day.
  R30's fills and app charges.
- **Sessions:** the first 40 NIFTY sessions with option data from 13 Oct 2026, as they arrive in the lake
  (`forward.py score` after each refresh). Logged only: nothing is placed anywhere, no app change.
- **Read once, after 40 sessions** (`forward.py verdict`, which refuses an earlier or second read). It **passes** if
  net P&L > 0, p < 0.05 against 1,000 random-twin sets (same day and side, random grid minute), and net > 0 at 2× the
  spread. It is one pre-registered test; it is also reported with R30's 30.
- **If it passes:** it becomes a PAPER candidate behind a switch that is off by default, logged in the Autopilot
  timeline and shown to Boss (CLAUDE.md). Never live from this test. **If it fails,** it stays a note.
