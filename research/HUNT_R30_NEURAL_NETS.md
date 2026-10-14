# R30: neural networks on every running study (India indices, the arms, crypto)

Written 14 Oct 2026, for Boss: "make sure all the studies going on in other sessions use neural networks, and use this
data for creating/testing."
- The running studies (R3 ORB family, R26 blast, R27 Night, R28 GEX, R29 EMA ribbon, F1 India, F1 crypto) could not
  change mid-run.
- So R30 adds the neural-network arm for all of them, on the same data, under the same rules: pre-registration,
  walk-forward, one holdout read, real charges and slippage, BH, and an experiment entry for every model.

**Research only. Nothing in the app changes, and nothing trades.**

- **Pre-registration:** `research/hunt/r30/PREREG.md`.
  - Commit `36af9e9`: written before any model was fitted.
  - Amendment 1, commit `d7ecbd0`: option bars come from the lake for all five indices. It was written before any
    India model was fitted.
- **Design results:** committed before each holdout was read (`cf58c1c`, `6b45ab7`, `363b5b3`, `58cd628`, `b5c99df`).
- **Holdout reads, once each:**
  - India `79e2bb5`: lock file `<cache>/r30/HOLDOUT_READ_india.json`, 09:34-09:49 UTC, not resumed.
  - Crypto `dff38ed`: 11:06-11:48 UTC, not resumed.
- **Code and tests:** `research/hunt/r30/` (`run.py`, `india.py`, `arms.py`, `crypto.py`, `nets.py`, `evalx.py`,
  `report.py`, `test_r30.py`).
- **Results:** every number below is in `research/hunt/r30/results/` (`wf_q1..q5.json`, `holdout_india.json`,
  `holdout_crypto.json`, `summary.json`).
- **Big tables, predictions and model weights:** on Drive, `gdrive:IraAlgo-data-lake/derived/r30/` (`india/`, `arms/`,
  `preds/`, `models/`, 946 MB), never in git.
- **Experiment entries:** `research/experiments/entries/r30-*.yaml` (13, failures included).

## Verdict for Boss

**Neural networks do not find a trade that the simple rules and trees miss. On unseen data, after costs, every one of
the 16 trading rules fails its gates. They do one thing better than the trees: forecasting how BIG the next 30-60
minutes will be.**

1. **Direction of NIFTY / BANKNIFTY / FINNIFTY / MIDCPNIFTY / SENSEX over the next 15/30/60 minutes is a coin**, for the
   networks too.
   - Holdout: 36,462-41,967 forecasts on 368 sessions per index, AUC 0.510-0.517.
   - The networks' log-loss is no better than just saying "50%" (0.6928-0.6969 vs 0.6923-0.6926).
   - Every option-buying rule built on it loses after costs, or (R4, R6) trades too rarely to judge.
   - This is the third time this answer has come out: R16, B1 and now R30.
2. **Size of the next 30 / 60 minutes: the networks beat the boosted trees on unseen data.**
   - Holdout R² for the log range of the next 60 minutes, 36,320 forecasts:

     | model | holdout R² |
     |---|---|
     | GRU | 0.434 |
     | TCN | 0.430 |
     | MLP | 0.424 |
     | NN average | 0.432 |
     | trees | 0.382 |
     | B1's 4-input formula | 0.353 |

   - For 30 minutes, the NN average is 0.465 vs the trees' 0.440.
   - Both pass the pre-registered bar: walk-forward q 0.005, better in 3 of 4 folds, holdout CI excluding 0, holdout
     q 0.006. They also win on every index.
   - The models that read the raw minute sequence (GRU, TCN) add a little over the plain MLP.
   - It is a better *volatility* forecast, not a trade. In the holdout, the quietest tenth of half-hours later moved
     0.18% in 60 minutes and the busiest tenth 0.61% (3,632 forecasts each).
3. **Filters on the arms' own trades (meta-labelling) do not pass.**
   - ORB, Range Fade, Liquidity, Night and lottery L3: no network beats the base rate on log-loss, on 195-1,083 design
     trades per arm and 243-829 holdout trades.
   - The one tempting result is the **Night** filter. It lifted the arm from Rs 174 to Rs 295 a day in the holdout.
     But it fails:
     - p 0.035, BH q 0.14;
     - only 66 skipped trades (the gate is 100);
     - it was trained on 211 trades;
     - the trees' filter did as well (Rs 314).
   - The Liquidity filter made the arm worse in both periods (holdout Rs 234 → −Rs 70 a day).
   - Hero H0 (28 design trades) was too small to model. Solo could not be rebuilt from the lake (its data is the old
     Dhan hunt cache).
4. **Expiry-afternoon blast from the 13:45 state (option-chart context): not predictable.**
   - Every model is worse than the base rate: holdout 685 option-days, 12.7% blasts, NN log-loss 0.439 vs 0.381.
   - The rule loses Rs 156 a day.
5. **Next-morning gap (Night arm): the network only learned that Indian indices usually gap up.**
   - The 15:20-state gap model does not beat the base rate on log-loss (holdout 1,476 nights, 0.681 vs 0.676).
   - Its overnight option rule R13 made +Rs 24 a day in the holdout (208 trades, p 0.014 vs random-side twins, BH q
     0.11: fails).
   - But 99.5% of its trades were calls. Simply buying the call on the same nights made +Rs 22 a day.
6. **Crypto (BTC / ETH): a real but tiny 60-minute direction signal, far too small to pay costs.**
   - The networks beat the base rate on the holdout (35,112 forecasts over 184 days, BTC and ETH): log-loss 0.6915 vs 0.6928,
     AUC 0.533, CI excluding 0, q 0.006. This repeats NN_CRYPTO.
   - Even the most confident tenth only gets 57% right.
   - All three crypto rules, each with a stop-loss and a profit lock, lose after Delta India fees, before tax:
     - 15 minutes: −Rs 301 a day, 773 trades;
     - 60 minutes: −Rs 152 a day, 386 trades;
     - 240 minutes: −Rs 4 a day, 99 trades.
   - After India's 31.2% tax on each winning trade they would lose more.

**What could go on PAPER behind a switch (never live): nothing as a trading rule.**
- The only candidate is information, not a trade: the **network size forecast** for the next 30/60 minutes. It could
  replace B1's gradient-boosted range model as context for stop and target distances.
  - That would need its own pre-registered paper test, behind a switch that is off by default and logged in the
    Autopilot timeline.
  - It could only ever *lower* risk (for example, skip or shrink a trade when a dead half-hour is forecast). No such
    rule was tested here.
- Hero H0 and Solo were not modelled; see points 3 and 4 above.

**What the phone could run if a model is ever used.** These are measured on this research machine's CPU, 1 thread,
float32, 1 row at a time (a phone is perhaps 2-5x slower):

| model | parameters | file (one seed) | 3-seed ensemble | one forecast (one seed / ensemble) |
|---|---|---|---|---|
| MLP (India) | 11,046 | 43 KB | 129 KB | 0.07 / 0.21 ms |
| TCN (India) | 6,918 | 27 KB | 81 KB | 0.37 / 1.1 ms |
| GRU (India) | 9,350 | 37 KB | 110 KB | 1.5 / 4.4 ms |
| MLP / TCN / GRU (crypto) | 3,942 / 3,366 / 5,798 | 15 / 13 / 23 KB | 46 / 39 / 68 KB | 0.05 / 0.3 / 2.9 ms per seed |

All three together are under 0.4 MB and about 6 ms per forecast. That is tiny, and it would sit off the order and tick
path. The size forecast is needed once a minute at most.

## What was tested (PREREG.md has every detail)

| | India | crypto |
|---|---|---|
| data | Dhan lake on Drive: 1-minute index bars of 5 indices, VIX, and the rolling weekly option series ATM±10 (OHLC, volume, OI, IV), Aug 2021 - Apr 2026; brain features (cube v2, 150+ point-in-time features) | Binance USDT-M perpetual 1-minute bars, BTC and ETH, Oct 2023 - Oct 2026 |
| holdout (read once) | **1 Jan 2023 - 30 Jun 2024**: F1 India's committed block (not read by F1); embargo 15-31 Dec 2022 and 1-14 Jul 2024 | **1 Apr - 30 Sep 2025**: F1 crypto's block. F1C read it earlier for its own rules; R30 used nothing from F1C's results |
| design / walk-forward | Aug 2021 - 14 Dec 2022 and 15 Jul 2024 - 13 Apr 2026; folds 2022, 2024H2, 2025, 2026 (retrained each fold on data before it) | Nov 2023 - Mar 2025 and Nov 2025 - Mar 2026; folds 2024H2, 2025Q1, 2025Q4-26Q1 |
| never touched | 14 Apr 2026 on (the brain's holdout) | Apr 2026 on (the crypto brain's holdout) |

- **Signal on the underlying, trade the option (CLAUDE.md).**
  - Every input comes from the index (or the coin): the brain's features at row t and the last N = 60 one-minute bars
    (crypto N = 120).
  - Option-chain data enters only as the brain's context features.
  - Every India trade buys the option the rule's strike rule picks:
    - ATM for Q1;
    - 1-ITM for Q4 (R27's rule);
    - R26's ATM±1 for Q3;
    - each arm's own strike for Q2.
  - Charges are `Costs("app")`; fills are `Fills("liq")` (app bps plus a volume-aware half-spread).
  - Crypto trades the perpetual. Every trade has a 1-sigma stop-loss and a profit lock (break-even after costs at
    +1 sigma, then a 1-sigma trail).
  - Q3 reads the option's own premium and OI, so it is labelled "option-chart context".
- **Models.** Each one is a 3-seed ensemble with dropout 0.2, weight decay 1e-4, and early stopping on the last 20% of
  each training window.
  - MLP: 64-32 hidden units.
  - 1-D causal TCN: 3 layers, 16 channels, dilations 1/2/4.
  - GRU: 32 hidden units.
  - The TCN and GRU also see the tabular features. "NN" is the average of the three.
- **Baselines** on the same rows: base rate, logistic / ridge, gradient-boosted trees (B1's settings), B1's 4-input
  size formula (India), and random twins for every rule.
- **Tests.**
  - Inputs at t use only rows ≤ t: perturbation tests on the sequence, the crypto features and the causal TCN.
  - Scalers, imputers and the label scale are fitted on training rows only (validation and test rows changed: same
    fit).
  - The holdout lock; the trade simulators; the metrics.
  - `python -m pytest research/hunt/r30/test_r30.py -q`: 30 tests.

## Results

Every model test pools the out-of-sample rows of the walk-forward folds (WF), then the holdout. "NN-best diff" is
NN minus the best baseline on the pre-registered metric (log-loss: negative = NN better; R²: positive = NN better),
with a 95% session-block bootstrap CI. BH runs over 19 model cells, and separately over the 16 rules.

### Model tests

A cell passes when WF q ≤ 0.05, the NN is better in at least 3 of 4 India folds (2 of 3 crypto), and the holdout CI
excludes 0 in the same direction. Three cells pass:
- India size of the next 30 minutes;
- India size of the next 60 minutes;
- crypto direction over the next 60 minutes (tiny).

| question | target | rows / sessions (WF) | NN | best baseline | metric NN vs best | NN-best diff [95% CI] WF | NN better in folds | q WF | holdout NN vs best [95% CI] | q holdout |
|---|---|---|---|---|---|---|---|---|---|---|
| q1 | dir_15 | 75,940 / 667 | 0.6939 | base 0.6933 | logloss (AUC 0.505 vs 0.501) | 0.0006 [0.0001, 0.0011] | 1/4 | 1.000 | 0.0003 [-0.0006, 0.0012] | 1.000 |
| q1 | size_15 | 75,966 / 667 | 0.5010 | trees 0.5078 | r2 | -0.0067 [-0.0110, -0.0025] | 1/4 | 1.000 | 0.0081 [0.0016, 0.0150] | 0.033 |
| q1 | dir_30 | 72,632 / 667 | 0.6947 | base 0.6932 | logloss (AUC 0.508 vs 0.502) | 0.0015 [0.0005, 0.0024] | 2/4 | 1.000 | 0.0028 [0.0009, 0.0048] | 1.000 |
| q1 | **size_30** | 72,624 / 667 | 0.4938 | trees 0.4769 | r2 | 0.0169 [0.0111, 0.0223] | 3/4 | 0.005 | 0.0248 [0.0168, 0.0331] | 0.006 |
| q1 | dir_60 | 66,027 / 666 | 0.6959 | base 0.6934 | logloss (AUC 0.507 vs 0.497) | 0.0025 [0.0008, 0.0042] | 1/4 | 1.000 | 0.0045 [0.0007, 0.0082] | 1.000 |
| q1 | **size_60** | 65,949 / 666 | 0.4737 | trees 0.4409 | r2 | 0.0328 [0.0239, 0.0418] | 3/4 | 0.005 | 0.0497 [0.0383, 0.0618] | 0.006 |
| q3 | blast | 576 / 239 | 0.4121 | base 0.3497 | logloss (AUC 0.501 vs 0.472) | 0.0624 [0.0388, 0.0861] | 0/3 | 1.000 | 0.0577 [0.0334, 0.0798] | 1.000 |
| q4 | gap | 2,868 / 663 | 0.6866 | base 0.6921 | logloss (AUC 0.570 vs 0.557) | -0.0056 [-0.0181, 0.0074] | 3/4 | 0.537 | 0.0056 [-0.0122, 0.0222] | 1.000 |
| q5 | dir_15 | 81,538 / 427 | 0.6912 | linear 0.6916 | logloss (AUC 0.537 vs 0.537) | -0.0005 [-0.0008, -0.0001] | 3/3 | 0.005 | -0.0003 [-0.0008, 0.0001] | 0.203 |
| q5 | size_15 | 81,584 / 427 | 0.6151 | trees 0.6118 | r2 | 0.0034 [0.0013, 0.0056] | 3/3 | 0.005 | 0.0008 [-0.0016, 0.0031] | 0.509 |
| q5 | **dir_60** | 81,554 / 427 | 0.6916 | base 0.6932 | logloss (AUC 0.536 vs 0.503) | -0.0016 [-0.0026, -0.0005] | 3/3 | 0.011 | -0.0013 [-0.0020, -0.0006] | 0.006 |
| q5 | size_60 | 81,578 / 427 | 0.5678 | trees 0.5669 | r2 | 0.0009 [-0.0023, 0.0045] | 2/3 | 0.548 | 0.0016 [-0.0021, 0.0052] | 0.477 |
| q5 | dir_240 | 81,525 / 427 | 0.6929 | base 0.6936 | logloss (AUC 0.535 vs 0.510) | -0.0007 [-0.0030, 0.0017] | 2/3 | 0.547 | -0.0015 [-0.0034, 0.0005] | 0.203 |
| q5 | size_240 | 81,532 / 427 | 0.5183 | trees 0.5142 | r2 | 0.0041 [-0.0019, 0.0105] | 3/3 | 0.244 | 0.0041 [-0.0030, 0.0111] | 0.353 |
| q2 | win_orb | 726 / 390 | 0.6710 | base 0.6750 | logloss (AUC 0.511 vs 0.464) | -0.0039 [-0.0158, 0.0082] | 2/3 | 0.547 | 0.0039 [-0.0054, 0.0137] | 1.000 |
| q2 | win_fade | 1,083 / 387 | 0.6316 | base 0.6255 | logloss (AUC 0.516 vs 0.526) | 0.0061 [-0.0025, 0.0139] | 1/3 | 1.000 | 0.0028 [-0.0079, 0.0134] | 1.000 |
| q2 | win_liq | 318 / 192 | 0.6811 | base 0.7157 | logloss (AUC 0.496 vs 0.494) | -0.0347 [-0.0737, 0.0039] | 2/2 | 0.142 | 0.0159 [-0.0092, 0.0466] | 1.000 |
| q2 | win_night | 315 / 217 | 0.7055 | base 0.6934 | logloss (AUC 0.528 vs 0.502) | 0.0121 [-0.0111, 0.0376] | 1/3 | 1.000 | -0.0039 [-0.0231, 0.0147] | 0.646 |
| q2 | win_l3 | 195 / 118 | 0.2835 | base 0.1574 | logloss (AUC 0.684 vs 0.464) | 0.1261 [0.0809, 0.1701] | 0/2 | 1.000 | 0.1552 [0.1178, 0.1908] | 1.000 |

Holdout scores of every model:

| question / target | holdout rows | MLP | TCN | GRU | NN | base | linear | trees | B1 formula |
|---|---|---|---|---|---|---|---|---|---|
| India dir 15 (log-loss) | 41,967 | 0.6933 | 0.6929 | 0.6928 | 0.6928 | 0.6926 | 0.6969 | 0.6985 | |
| India size 15 (R²) | 41,965 | 0.465 | 0.470 | 0.471 | 0.472 | -0.420 | 0.427 | 0.464 | 0.389 |
| India dir 30 | 40,128 | 0.6949 | 0.6947 | 0.6964 | 0.6951 | 0.6923 | 0.7006 | 0.7053 | |
| India size 30 | 40,071 | 0.458 | 0.464 | 0.466 | 0.465 | -0.471 | 0.408 | 0.440 | 0.384 |
| India dir 60 | 36,462 | 0.6972 | 0.6960 | 0.6986 | 0.6969 | 0.6924 | 0.7042 | 0.7335 | |
| India size 60 | 36,320 | 0.424 | 0.430 | 0.434 | 0.432 | -0.521 | 0.328 | 0.382 | 0.353 |
| blast (13:45) | 685 | 0.408 | 0.474 | 0.457 | 0.439 | 0.381 | 1.070 | 0.439 | |
| morning gap (15:20) | 1,476 | 0.680 | 0.682 | 0.685 | 0.681 | 0.676 | 0.821 | 0.859 | |
| crypto dir 15 | 35,114 | 0.6912 | 0.6911 | 0.6912 | 0.6910 | 0.6931 | 0.6914 | 0.6916 | |
| crypto size 15 | 35,132 | 0.635 | 0.654 | 0.663 | 0.656 | -0.028 | 0.561 | 0.656 | |
| crypto dir 60 | 35,112 | 0.6916 | 0.6915 | 0.6917 | 0.6915 | 0.6928 | 0.6927 | 0.6932 | |
| crypto size 60 | 35,126 | 0.606 | 0.616 | 0.624 | 0.620 | -0.026 | 0.533 | 0.618 | |
| crypto dir 240 | 35,095 | 0.6888 | 0.6887 | 0.6890 | 0.6886 | 0.6924 | 0.6901 | 0.6933 | |
| crypto size 240 | 35,102 | 0.567 | 0.570 | 0.572 | 0.574 | -0.029 | 0.484 | 0.570 | |

- **Calibration.**
  - The size forecasts are close: in each predicted tenth the observed log range is within 0.03-0.10 of the forecast,
    slightly over-forecast in the holdout.
  - Crypto 60-minute direction is well calibrated: forecast 0.44 → observed 0.47, forecast 0.58 → observed 0.57
    (3,511 per tenth).
  - The blast and arm models are over-confident, which is why their log-loss loses to the base rate.
- **Per index** (India size 60, holdout R², NN vs trees): NIFTY 0.420 vs 0.327, BANKNIFTY 0.399 vs 0.357, FINNIFTY
  0.346 vs 0.317, MIDCPNIFTY 0.421 vs 0.387, SENSEX 0.352 vs 0.279.
- **The base rate's R² is negative for size.** "Base" for size is the training mean of the log range. The holdout year
  2023 was quieter than the 2021-2022 training data, so that baseline is off in level.

### Trading rules (the 16 pre-registered candidates)

- Rs per lot / per contract-unit, after charges and slippage; "stress" is double the spread or slippage.
- "Cut short": exits followed, before 15:20 (crypto: within h more minutes), by a further +100% of the premium (crypto:
  +2 sigma) in the trade's direction.
- "p vs twins": the same entries with a random side (Q1, Q4, Q5), random skips of the same number (Q2), or random
  picks from the same expiry pool (Q3). BH runs over the 16 rules.
- The trees' version of each rule is in the last column.

| rule | what | part | trades | trades/day | win rate | avg win | avg loss | P&L/day | P&L/day stress | % green days | worst day | max DD | cut short | p vs twins | BH q | trees P&L/day |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| R1 | Q1 h15 top-10% confidence, ATM | WF | 5,530 | 8.29 | 0.42 | 722 | -656 | -649 | -795 | 0.36 | -19,238 | -490,660 | 399 | 0.106 | 0.228 | -839 |
| R1 | Q1 h15 top-10% confidence, ATM | holdout | 2,752 | 7.48 | 0.38 | 344 | -301 | -424 | -480 | 0.31 | -7,287 | -184,279 | 300 | 0.229 | 0.423 | -635 |
| R2 | Q1 h30 top-10% | WF | 3,988 | 5.98 | 0.42 | 857 | -784 | -575 | -676 | 0.39 | -22,649 | -401,164 | 268 | 0.377 | 0.464 | -1,008 |
| R2 | Q1 h30 top-10% | holdout | 2,150 | 5.84 | 0.42 | 400 | -365 | -252 | -296 | 0.35 | -8,297 | -95,421 | 246 | 0.033 | 0.140 | -601 |
| R3 | Q1 h60 top-10% | WF | 2,731 | 4.09 | 0.41 | 1,442 | -1,122 | -267 | -338 | 0.42 | -23,658 | -352,236 | 234 | 0.114 | 0.228 | -580 |
| R3 | Q1 h60 top-10% | holdout | 1,499 | 4.07 | 0.43 | 577 | -484 | -106 | -137 | 0.38 | -6,094 | -43,563 | 174 | 0.006 | 0.096 | -575 |
| R4 | Q1 h15 + big-move filter | WF | 1,968 | 2.95 | 0.45 | 1,117 | -1,040 | -207 | -276 | 0.43 | -19,238 | -194,852 | 173 | 0.078 | 0.208 | -285 |
| R4 | Q1 h15 + big-move filter | holdout | 172 | 0.47 | 0.46 | 1,201 | -942 | 20 | 15 | 0.44 | -5,365 | -20,561 | 33 | 0.223 | 0.423 | -323 |
| R5 | Q1 h30 + big-move filter | WF | 894 | 1.34 | 0.43 | 1,492 | -1,325 | -158 | -192 | 0.41 | -22,649 | -139,878 | 54 | 0.362 | 0.464 | -329 |
| R5 | Q1 h30 + big-move filter | holdout | 56 | 0.15 | 0.43 | 1,172 | -1,136 | -22 | -24 | 0.47 | -6,543 | -10,702 | 8 | 0.746 | 0.853 | -308 |
| R6 | Q1 h60 + big-move filter | WF | 695 | 1.04 | 0.45 | 2,720 | -1,916 | 193 | 166 | 0.48 | -23,658 | -76,834 | 94 | 0.004 | 0.032 | -23 |
| R6 | Q1 h60 + big-move filter | holdout | 21 | 0.06 | 0.43 | 2,271 | -2,008 | -10 | -11 | 0.47 | -5,923 | -9,013 | 6 | 0.447 | 0.642 | -378 |
| R7 | Q2 skip ORB trades (unfiltered -146, skipped 231) | WF | 495 | 1.27 | 0.39 | 543 | -442 | -76 | -88 | 0.41 | -3,158 | -32,504 | - | 0.155 | 0.275 | -54 |
| R7 | Q2 skip ORB trades (unfiltered -193, skipped 75) | holdout | 491 | 1.56 | 0.36 | 386 | -367 | -154 | -163 | 0.41 | -3,204 | -50,720 | - | 0.166 | 0.423 | -156 |
| R8 | Q2 skip Range Fade trades (unfiltered -501, skipped 402) | WF | 681 | 1.76 | 0.33 | 609 | -502 | -232 | -243 | 0.38 | -4,501 | -92,533 | - | 0.008 | 0.043 | -280 |
| R8 | Q2 skip Range Fade trades (unfiltered -306, skipped 286) | holdout | 543 | 1.79 | 0.31 | 376 | -333 | -198 | -211 | 0.36 | -2,138 | -61,421 | - | 0.482 | 0.642 | -190 |
| R9 | Q2 skip Liquidity trades (unfiltered 532, skipped 114) | WF | 204 | 1.06 | 0.42 | 1,585 | -881 | 156 | - | 0.41 | -5,541 | -12,489 | - | 0.950 | 0.950 | 296 |
| R9 | Q2 skip Liquidity trades (unfiltered 234, skipped 184) | holdout | 81 | 0.46 | 0.35 | 844 | -676 | -70 | - | 0.29 | -2,088 | -18,398 | - | 0.975 | 0.975 | 154 |
| R11 | Q2 skip Night trades (unfiltered 396, skipped 61) | WF | 254 | 1.17 | 0.56 | 2,548 | -2,219 | 500 | 472 | 0.53 | -12,718 | -36,694 | - | 0.066 | 0.208 | 557 |
| R11 | Q2 skip Night trades (unfiltered 174, skipped 66) | holdout | 177 | 1.09 | 0.58 | 1,564 | -1,532 | 295 | 280 | 0.57 | -8,830 | -16,291 | - | 0.035 | 0.140 | 314 |
| R12 | Q3 buy the blast option (top 20%) | WF | 152 | 0.64 | 0.22 | 3,097 | -1,017 | -79 | -83 | 0.29 | -6,113 | -26,339 | - | 0.179 | 0.286 | -108 |
| R12 | Q3 buy the blast option (top 20%) | holdout | 210 | 0.73 | 0.16 | 1,494 | -530 | -156 | -158 | 0.23 | -2,770 | -44,505 | - | 0.873 | 0.931 | -86 |
| R13 | Q4 overnight 1-ITM option (top 20%) | WF | 538 | 0.81 | 0.52 | 2,478 | -2,513 | 76 | 58 | 0.50 | -33,350 | -98,272 | 76 | 0.002 | 0.032 | -59 |
| R13 | Q4 overnight 1-ITM option (top 20%) | holdout | 208 | 0.57 | 0.48 | 1,245 | -1,071 | 24 | 19 | 0.48 | -12,193 | -30,459 | 41 | 0.014 | 0.112 | -74 |
| R14 | Q5 crypto h15 (top 10%, stop + lock) | WF | 3,413 | 7.99 | 0.30 | 96 | -130 | -493 | -627 | 0.18 | -3,490 | -210,587 | 327 | 0.028 | 0.112 | -408 |
| R14 | Q5 crypto h15 (top 10%, stop + lock) | holdout | 773 | 4.20 | 0.30 | 76 | -134 | -301 | -381 | 0.21 | -2,596 | -55,414 | 79 | 0.345 | 0.551 | -505 |
| R15 | Q5 crypto h60 | WF | 1,756 | 4.11 | 0.40 | 173 | -232 | -283 | -353 | 0.35 | -4,365 | -120,931 | 143 | 0.434 | 0.496 | -237 |
| R15 | Q5 crypto h60 | holdout | 386 | 2.10 | 0.42 | 149 | -233 | -152 | -191 | 0.42 | -1,786 | -27,936 | 30 | 0.238 | 0.423 | -194 |
| R16 | Q5 crypto h240 | WF | 528 | 1.24 | 0.49 | 358 | -470 | -77 | -97 | 0.54 | -4,384 | -33,972 | 42 | 0.291 | 0.423 | -45 |
| R16 | Q5 crypto h240 | holdout | 99 | 0.54 | 0.56 | 359 | -466 | -4 | -14 | 0.60 | -2,252 | -5,119 | 3 | 0.124 | 0.396 | -16 |
| R17 | Q2 skip lottery L3 trades (unfiltered -573, skipped 64) | WF | 131 | 1.11 | 0.04 | 936 | -406 | -394 | -403 | 0.04 | -995 | -46,496 | - | 0.659 | 0.703 | -449 |
| R17 | Q2 skip lottery L3 trades (unfiltered -646, skipped 94) | holdout | 268 | 1.63 | 0.03 | 4,252 | -432 | -449 | -464 | 0.05 | -1,769 | -89,421 | - | 0.533 | 0.657 | -369 |

Sessions per period:
- India WF: 667 trading days across the folds. India holdout: 368 days; per index 2023 - Jun 2024.
- Crypto WF: 427 days (IST). Crypto holdout: 184 days. Crypto trades are inside Boss's 00:00-09:00 IST window only.

**Gates (PREREG section 6). No rule passes.**

| gate | rules that fail it |
|---|---|
| holdout P&L/day > 0 (also at stress) | R1, R2, R3, R5, R6, R12, R14, R15, R16 lose. R9's filter makes Liquidity worse. |
| BH q ≤ 0.05 on the holdout | all 16: the best are R3 (0.096; it still loses money), R13 (0.112) and R11 (0.140) |
| at least 100 holdout trades (Q2: 100 skipped) | R5 / R6 had only 56 / 21 trades (R16 99); R7 / R11 / R17 skipped 75 / 66 / 94 |
| WF positive in at least 3 of 4 folds (2 of 3 crypto) | R1-R5, R9, R12, R14-R16 |
| the NN rule at least as good as the trees' rule | R8, R9, R11, R12 and R17 (the trees did better) |

Three near-misses, in plain words:
- **R6 (60-minute direction + "big move" forecast)** looked good in design (+Rs 193 a day, 695 trades, BH q 0.03).
  - But its design result came mostly from the 2026 fold: +Rs 1,287 a day, 322 trades. The other folds were +Rs 214,
    +8 and −39.
  - In the 2023 holdout the big-move filter almost never switched on (21 trades), and it lost Rs 10 a day.
- **R13 (overnight option from the 15:20 state)** is "buy calls overnight", 99.5% calls. This check was not
  pre-registered and is reported only:
  - in the holdout, the same nights with a call every time made +Rs 22 a day, against the rule's +Rs 24;
  - in design, always-call on its nights made −Rs 1 a day against the rule's +Rs 76. The difference is about five put
    trades on big gap-down mornings;
  - the design walk-forward also lost Rs 1,068 a day in its 2026 fold.
- **R11 (skip the Night arm's weakest 30%)** lifted the holdout from Rs 174 to Rs 295 a day (177 kept trades, 66
  skipped, p 0.035).
  - It fails BH (q 0.14) and the 100-skip gate.
  - The trees' filter did a little better (Rs 314), so the network adds nothing over trees here.
  - It is the one idea worth a fresh pre-registered test once more Night trades exist. It would be paper only and
    risk-lowering only, so it fits the paper rules if it passes then.

## Honest notes

1. **A units bug was found and fixed before the crypto holdout was read.**
   - The first crypto walk-forward used a stop distance in fractions where percent was meant. That put every stop 100x
     too close, and the win rate came out at 2%.
   - It was fixed in `crypto.py`, with a test (`3ae99f4`), and the crypto data and walk-forward were redone once. No
     rule, threshold or model changed.
   - The buggy run's file was kept out of git (scratchpad `wf_q5_sigma_bug.json`).
2. **Option prices: amendment 1.** The brain's cube has an option chain only for NIFTY / BANKNIFTY, because its calendar
   lacks rules for the others. So R30 reads the lake's rolling weekly files for all five indices and finds expiry days
   from the data (R25's rule). This was written and pushed before any India model was fitted.
3. **How the code reads the pre-registration (no result was seen before these choices):**
   - Direction twins need both the call and the put priced at that minute, so a rule trade without both is skipped.
   - The Q3 label window is the bars from 13:45 to the 15:04 bar (the first bar after the 13:45 decision through the
     15:05 square-off).
   - The Q2 rule id per arm is in `report.ARM_RULE`.
   - The Q2 "WF positive" gate counts folds where the filter beat the unfiltered arm.
   - The holdout Q2 models train only on the arm's trades before 15 Dec 2022: ORB 517, Range Fade 782, Liquidity 234,
     Night 211, L3 247. The 300-trade floor applied to the whole design set.
4. **Two additions after the pre-registration, reported and never gated:**
   - the "always the call" comparison for R13, added after its design walk-forward was seen;
   - per-index scores.
5. **The holdout is not virgin.**
   - India 2023 - Jun 2024 was read by R29 (EMA ribbon, all of 2023) and R28-H (Hero/L3 GEX, 2024 - Mar 2025). R3 read
     every FINNIFTY / MIDCPNIFTY / SENSEX session for the ORB family. All of this happened on 14 Oct, after F1 committed
     the block.
   - F1 crypto read the crypto block for its own rules.
   - R30's models and rules were fixed before R30 read anything. Nothing from those studies' holdout results was used.
6. **Not covered.**
   - Hero H0: only 28 design trades.
   - Solo: its trades come from the old Dhan hunt cache, not the lake.
   - Futures volume / VWAP features exist only from mid-2026, so the brain's volume family was NaN and dropped (more
     than 80% missing).
   - FINNIFTY / MIDCPNIFTY / SENSEX have no option-context features (index-only cubes).
7. **Compute.**
   - About 5 CPU-hours in all on a 4-core machine, one thread per job.
   - The largest fit was a crypto fold: 99,068 rows × 3 architectures × 3 seeds, 41 minutes.
   - No seed hit the 10-minute cap; the longest took 512 s.

## Reproduce

```bash
python -m research.brain.refresh --source lake --und NIFTY BANKNIFTY FINNIFTY MIDCPNIFTY SENSEX --no-models --no-tables
# arm trades: R28's r28arms.py {orb,liq,hero,night} with R28_OUT=<cache>/r30/arms (needs R25 and R27 data builds)
python research/hunt/r30/run.py build              # India design data, arm data, crypto design data
python research/hunt/r30/run.py wf q1   # ... q2 q3 q4 q5
python research/hunt/r30/run.py holdout india      # refused after the first read
python research/hunt/r30/run.py holdout crypto
python research/hunt/r30/report.py                 # BH, gates, the tables above
```
