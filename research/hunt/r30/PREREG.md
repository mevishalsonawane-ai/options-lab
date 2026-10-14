# R30 pre-registration: a neural-network arm for every running study

Written 14 Oct 2026. Committed and pushed **before any model was fitted** and before any test period's labels or trades
were computed. Only data inventories, day counts and other studies' committed write-ups had been read. After this
commit, nothing in sections 1-8 changes. A change needs a dated amendment at the end that states what was known when
it was made. A changed rule gets a new candidate number and counts in the multiple-testing family.

Boss (14 Oct 2026): "make sure all the studies going on in other sessions use neural networks, and use this data for
creating/testing." The running studies (R3 ORB family, R26 OI-wall blast, R27 Night, R28 GEX, R29 EMA ribbon,
F1 India, F1 crypto) cannot change mid-run. R30 adds the neural-network arm on the same data and judges it by the
same rules. **Research only. Nothing here touches the app, and nothing trades.**

## 1. Rules this study obeys (CLAUDE.md)

- **Signal on the underlying, trade the option.** Every model input is built from the underlying (index minutes;
  index futures where they exist). Option-chain data (ATM IV, OI, PCR, skew, max-OI strikes) enters only as context
  features from the brain's store. Every India trade is simulated by **buying the option the rule's strike rule picks**,
  with stops or targets on the premium. Crypto trades the BTC/ETH perpetual future.
  - Q2 (arm trades) and Q3 (blast) have extra option inputs, each labelled.
    - Q2 uses each arm's own trades, which come from that arm's own (underlying) signal.
    - Q3 uses the 13:45 premium and the OI of the option R26 trades. R26's blast rule itself reads the option, so Q3 is
      **labelled "option-chart context"**, reported separately, and could only ever be switched on by Boss.
- **Point in time.** A decision at grid row t (the bar that starts at 09:15 + t and closes at t + 1 minute) uses only
  rows <= t. It fills at the open of row t + 1. Inputs come from the brain's look-ahead-guarded feature store
  (`research/brain/features`) plus a raw-minute sequence ending at row t. Labels are computed in R30's own code from
  rows > t, are never inputs, and the two meet only in `split.join_forward`-style joins inside the training code.
- **Scaling and imputation** (medians, means, standard deviations, the label scale for the size head) are fitted on
  each fold's training rows only. A test checks this.
- Walk-forward by year, retrained every fold. The locked holdout is read once. Real charges and slippage are used,
  with Benjamini-Hochberg (BH) correction across every family. Every model and rule gets an experiment entry,
  failures included. Every number comes with its sample size.
- Crypto: every simulated trade has a stop-loss **and** a profit lock (section 6). Nothing is ever run without a stop.
- Big tables, predictions and model weights go to `<cache>/r30` and Drive `gdrive:IraAlgo-data-lake/derived/r30/`,
  never to git. Git holds code, this file, small JSON results, the report and the entries.

## 2. Periods and the locked holdout

Every period earlier studies read was inventoried on 14 Oct 2026. Sources: `research/experiments/entries`, the HUNT_*
docs, and the PREREGs and holdout flags on the running branches.

| study | locked holdout | read? |
|---|---|---|
| H-series, R1-R15 (Dhan hunt cache) | 1 Oct 2025 - 6 Oct 2026 (R15: BANKNIFTY to 23 Feb 2026) | read |
| R16-R22 (release) | BANKNIFTY 25 Aug 2025 - 23 Feb 2026, NIFTY 13 Oct 2025 - 13 Apr 2026; R16 daily 9 Apr - 9 Oct 2026 | read |
| R19 / R19b / R19c | BANKNIFTY 1 Aug 2024 - 14 Feb 2025 / 3 Mar - 22 Aug 2025 / 1 Apr 2025 on | read |
| R24 what-if | 27 Apr - 12 Oct 2026 | read |
| R25 hero | expiry days 5 Aug 2021 - 29 Dec 2022 (NIFTY, BANKNIFTY) | read 14 Oct 02:59 UTC |
| R27 Night | 1 Oct 2025 - 12 Oct 2026 | read 14 Oct 05:17 UTC |
| R3 ORB family | every FINNIFTY, MIDCPNIFTY and SENSEX session (out-of-instrument) | read 14 Oct 06:24 UTC |
| R29 EMA ribbon | 1 Jan - 31 Dec 2023, all five indices | read 14 Oct 06:49 UTC |
| R28 GEX | family A: start - 31 Dec 2022; family H: 1 Jan 2024 - 31 Mar 2025 | read 14 Oct 07:14 UTC |
| Brain v1 (B1) | 27 Apr 2026 on | **not read** (reserved; R30 does not touch it) |
| **F1 India** | **1 Jan 2023 - 30 Jun 2024**, all five indices | **not read** (F1 commit 57b704c) |
| crypto C1 / C2 / brain | 1 Apr 2026 on | not read (reserved; R30 does not touch it) |
| RC1 / RC2 / NN_CRYPTO / STRAD_CRYPTO | 13 Oct 2025 - 12 Oct 2026 / 13 Apr - 13 Oct 2026 / 1 Apr - 7 Oct 2026 | read |
| **F1 crypto** | **1 Apr - 30 Sep 2025** | **read by F1C** (14 Oct 07:39 UTC) |

**India: R30 uses F1 India's committed split, unchanged** (`research/hunt/f1/f1cfg.py` on `claude/f1-india-factory`):
- **Locked holdout: 1 Jan 2023 - 30 Jun 2024**, all five indices. It is read ONCE for all of R30-India
  (`<cache>/r30/HOLDOUT_READ_india.json` refuses a second read).
- Embargo (not used at all): 15 - 31 Dec 2022 and 1 - 14 Jul 2024.
- **Design: 1 Aug 2021 - 14 Dec 2022 and 15 Jul 2024 - 13 Apr 2026.**
- Never used: 14 Apr 2026 on (the brain's embargo and holdout).
- Honest caveats. The block is not unseen, and three later reads overlap it:
  - R29 read 2023 as its holdout and R28-H read Jan 2024 - Mar 2025, both after F1 committed its split. Both read only
    their own rules (EMA ribbon; GEX filters on Hero/L3), whose results R30 does not use for any choice.
  - R3 read every FINNIFTY / SENSEX / MIDCPNIFTY session for ORB-family rules only.
  - Earlier studies (H-series, OBUY, R24, R25 design) used it as design data and found no indicator edge.
  - R30's models are new and are fixed here before any R30 number exists.
  The block is the cleanest one left. It is also the comparison Boss asked for: the same block as F1.

**Crypto: R30 uses F1 crypto's split, unchanged** (`research/hunt/f1c/f1ccfg.py`):
- holdout 1 Apr 2025 00:00 - 30 Sep 2025 23:59 UTC, read once by R30 (`<cache>/r30/HOLDOUT_READ_crypto.json`);
- embargo October 2025;
- design 1 Nov 2023 - 31 Mar 2025 and 1 Nov 2025 - 31 Mar 2026;
- Apr 2026 on is not touched.
- **Caveat: F1C has already read this block for its own 20 rules** (all lose after costs and tax). R30 has not looked
  at F1C's holdout tables and uses nothing from them. Its crypto holdout is "unseen by R30's models", not "unseen".
  The more recent block is worse: four studies have read it.

**Walk-forward (design only, by year; each fold retrains every model and baseline from scratch on data BEFORE the
fold):**
- India folds: **2022** (training data Aug - Dec 2021), **2024H2** (15 Jul - 31 Dec 2024), **2025**,
  **2026** (to 13 Apr). These are F1's folds.
- Crypto folds: **2024H2** (Jul - Dec 2024; training Nov 2023 - Jun 2024), **2025Q1** (Jan - Mar 2025),
  **2025Q4-2026Q1** (Nov 2025 - Mar 2026). The crypto design is only 22 months, so "by year" becomes these three
  blocks.
- A fold needs at least 150 training sessions (India, pooled over indices: 150 index-sessions) or 120 training days
  (crypto). A fold below that is reported as "too little training data" and is not scored.
- **Holdout models** are trained on design data strictly BEFORE the holdout (time order kept, never trained on the
  future): India Aug 2021 - 14 Dec 2022 (with embargo), crypto Nov 2023 - 31 Mar 2025. Thresholds come from those
  models' validation predictions. The 2024H2-2026 design data is not used to predict 2023.

**Validation split, inside each fold's training window only:** the last 20% of training sessions/days by date, with
one session (crypto: one day) dropped between training and validation. It is used for early stopping and for every
threshold (rule quantiles). It is never used for test scoring.

## 3. Questions, inputs and labels

The India indices are NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY and SENSEX. Every India model is **pooled across the five
indices**: one model per fold, with a one-hot index input. This gives about 5x the data. Results are reported per
index and pooled.

**Common India inputs.**
- *Tabular*: every registered brain feature (`research/brain/features`, point in time) at row t. Features that are
  NaN on more than 80% of a fold's training rows are dropped for that fold. Training medians are imputed and the
  values standardised with training means and SDs, then clipped to +-5 (trees get the raw values with NaN).
  The one-hot index and minute-of-day / 375 are added.
- *Sequence*: **N = 60** one-minute rows ending at row t, crossing into the previous session if needed. There are
  5 channels:
  1. index log-return x 100;
  2. (high - low) / close x 100;
  3. log(close / session open) x 100;
  4. VIX log-change x 100;
  5. a 0/1 "bar present" mask (missing values are 0 with mask 0, never filled from the future).

**Q1: next 15 / 30 / 60-minute direction and size.**
- Decisions every 15 minutes from 09:45 (row 29) to the last row with t + h <= 374.
- Labels:
  - direction = 1{log(close(t+h)/close(t)) > 0} (exact zeros dropped);
  - size = log(high-low range of rows t+1..t+h, % of close(t)).
- One multi-task net per fold: 3 direction logits + 3 size outputs, with a masked loss (BCE + MSE on the training-
  standardised size). Baselines are fitted per target.
- Size has been known since R16/B1 to be predictable. Q1 asks whether nets beat the trees and the linear model on it.

**Q2: trade filters (meta-labelling) for the arms.**
- Each arm's trades are rebuilt with the arm's own code through R28's unified rebuild
  (`research/hunt/r28/r28arms.py`: `orb` = ORB + Range Fade; `liq` = Liquidity 15+5; `hero` = Hero H0 + lottery L3;
  `night` = Night R3 09:16 sale). Each study keeps its own fills, charges and strike rule.
- Solo (`research/hunt/h22/solo_learner.py`) is included only if its data cache can be rebuilt from the lake. If not,
  the report says so.
- Label = 1{net Rs after costs > 0}. Inputs = brain features and the N = 60 sequence at the arm's signal row, plus a
  side flag (+1 long call / -1 long put).
- An arm needs **>= 300 design trades** to get models. Arms with fewer are reported, not modelled.
- Models are pooled across an arm's indices.

**Q3: expiry-afternoon blast from the 13:45 state (option-chart context, labelled).**
- Population: every expiry day of each index's weekly series in the cube (`is_expiry_day`), both sides.
- The option is R26's: ATM+1 CE / ATM-1 PE of the 13:44 row (close at 13:45), price p0 = its 13:45 close.
- Label "blast" = 1{highest high of that strike's rows 13:46 .. 15:04 >= 4 x p0} (R26 `describe.blast`).
  The R25/R32 10x count is reported, not modelled: too rare.
- Inputs:
  - brain features at row 269;
  - the 60-minute index sequence;
  - option context: log p0, p0 / spot, the option's OI change since 12:00 (%), its IV, and dte (minutes);
  - side flag.

**Q4: next-morning gap direction from the day-end state (Night arm).**
- Decision row 244 (15:19 bar, close 15:20; R27's signal time). Non-expiry days only (R27).
- Label = 1{open of the next session's 09:16 row (row 1) > close of row 244}.
- Inputs: brain features at row 244 and the 60-minute sequence.

**Q5: crypto BTC / ETH, next 15 / 60 / 240-minute move.**
- Data: Binance USDT-M perpetual 1-minute klines (Drive `gdrive:IraAlgo-data-crypto/futures_um/klines_1m`).
- Decisions every 15 minutes, 24x7. One multi-task net per fold, pooled over BTC and ETH with a coin flag.
- Labels as in Q1 (direction, log range), windows run across midnight.
- Tabular inputs, all from bars <= t:
  - log-returns over 5 / 15 / 60 / 240 / 1440 minutes;
  - realised volatility over 60 / 240 / 1440 minutes;
  - 60-minute range;
  - volume z-score (60 vs 1440);
  - taker-buy share over 15 / 60 minutes;
  - number-of-trades z;
  - hour-of-day sin/cos, day-of-week one-hot;
  - distance from the 1440-minute high and low.
- Sequence: **N = 120** one-minute rows with log-return, range, log volume z, taker-buy share and mask.

## 4. Models (fixed compute budget; CPU)

Every network is PyTorch on CPU with 1 thread per process.

| model | architecture |
|---|---|
| **MLP** | tabular -> 64 -> 32 -> heads; ReLU, dropout 0.2 |
| **TCN** | 3 causal 1-D conv layers, 16 channels, kernel 3, dilations 1 / 2 / 4, ReLU, dropout 0.2; the last step's 16 values joined to the tabular vector -> 32 -> heads |
| **GRU** | 1 layer, hidden 32 over the sequence; the last hidden state joined to the tabular vector -> 32 -> heads |

- **Training:** AdamW, learning rate 1e-3, weight decay 1e-4, batch 512, at most **25 epochs**. Early stopping uses
  the validation loss with patience 4, and the best epoch's weights are kept. Training is capped at 10 minutes of
  wall time per fit (stop at the cap, keep the best weights).
- **Ensembles:** 3 seeds (0, 1, 2) per model, averaged (probabilities; size outputs).
- **"NN"** = the average of the MLP, TCN and GRU seed ensembles. It is the model every trading rule uses.
- **Baselines**, on the same rows and folds:
  - **base rate**: the training-fold share (classification) or the training mean / time-of-day mean (size);
  - **logistic / linear regression** on the same standardised tabular inputs (L2, C = 1);
  - **gradient-boosted trees** (scikit-learn HistGradientBoosting with the brain's B1 settings: 200 iterations,
    learning rate 0.05, 15 leaves, 50 per leaf, L2 1.0) on the raw tabular inputs;
  - for size, also **B1's 4-input linear formula** (time-of-day range, last 30 / 60-minute range, VIX), India only;
  - **random twins** for the rules (section 6).

## 5. Metrics and model tests

- Classification is scored on log-loss (primary), AUC and Brier score. Size is scored on R² of the log range (primary)
  and mean absolute error.
- 95% confidence intervals come from a **session-block bootstrap** (1,000 resamples of whole days).
- Calibration: 10 quantile bins of the predicted value, with the observed rate and the mean prediction.
- **Primary model test, per question x target cell:** NN minus the best baseline (lowest log-loss / highest R² on the
  same fold pooled out-of-sample rows).
  - One-sided bootstrap p for "NN better". BH over all primary model-test cells of R30 in one family.
  - "NN adds something" means q <= 0.05 **and** the design walk-forward difference has the same sign in at least 3 of
    the 4 India folds (2 of 3 crypto folds).
  - Then confirmed on the holdout (same direction, bootstrap CI excluding 0).
- The cells:
  - Q1: 3 horizons x 2 targets = 6 cells, pooled over indices. Per-index numbers are reported but not tested.
  - Q2: 1 per modelled arm.
  - Q3: 1.
  - Q4: 1.
  - Q5: 6.

## 6. Trading rules (the ONLY candidates, 16 to 18 in all; each is fixed here)

All India rules buy options:
- quantity: 1 lot per the dated lot schedule (`research/obuy/config.LOT_SCHEDULE`);
- fills: `Fills("liq")` (app bps + volume-aware half-spread, stops x2);
- charges: `Costs("app")` (`research/obuy/costs.py`), BSE rates for SENSEX.
- The "confidence" threshold is a quantile of the NN's validation-split scores in the same fold, so it never sees test
  rows.
- Missing option bars: no fill means no trade, never a filled price.
- Expiry day: no position past 15:05. The F&O close is 15:30 for options; every India exit here is by 15:20.

| # | question | rule |
|---|---|---|
| R1-R3 | Q1, h = 15 / 30 / 60 | At a Q1 decision row, if the NN's |p_up - 0.5| is in the top 10% of its validation scores, buy the **ATM** option (strike = index close of row t rounded to the step, half up) on the predicted side (CE if up), at the open of row t + 1. Exit at the close of row t + h, or earlier at a minute close <= 70% of the fill (premium stop -30%, sold with stop fills). At most 1 open position per index; overlapping signals are skipped. |
| R4-R6 | Q1, h = 15 / 30 / 60 | R1-R3 plus the NN's size forecast >= its validation median (trade only when a big move is forecast). |
| R7-R11 (+ R17 lottery L3, R18 Solo) | Q2, one per arm: R7 ORB, R8 Range Fade, R9 Liquidity, R10 Hero H0, R11 Night; R17 lottery L3 and R18 Solo only if those arms are rebuilt with >= 300 design trades | **Skip** the arm's trade when the NN's P(win) is in the bottom 30% of its validation scores. It is risk-lowering only. |
| R12 | Q3 | On an expiry day at 13:46, buy R26's option (ATM+1 CE / ATM-1 PE) when the NN's P(blast) is in the top 20% of its validation scores (both sides may trade). Exit: a resting sell at 4 x p0 (filled at 4 x p0 with sell fills when a later high reaches it), else the close of the 15:04 row. No stop (the premium is the risk; R26's design). |
| R13 | Q4 | On a non-expiry day, if the NN's |p_up - 0.5| is in the top 20% of its validation scores, buy the **1-ITM** option (R27's strike rule: CE at ATM - 1 step, PE at ATM + 1 step from the 15:19 close) at the open of row 245. Sell at the next session's row-1 open (09:16). The option must have bars at both ends, else no trade. |
| R14-R16 | Q5, h = 15 / 60 / 240 | BTC and ETH perpetuals, decisions **inside Boss's crypto window** (00:00 - 09:00 IST; entry and exit both inside, flat by 09:00). Trade if |p_up - 0.5| is in the top 10% of validation scores: 1 coin-unit notional, at the open of the next minute, on the predicted side. **Stop-loss** at 1.0 x sigma_h against the entry (sigma_h = the SD of 1-minute log-returns over the previous 1440 minutes x sqrt(h)). **Profit lock**: when open profit reaches 1.0 x sigma_h, the stop moves to break-even after costs; after that it trails at the best price minus 1.0 x sigma_h, never below break-even. Time exit at t + h. Within a bar, a stop is assumed hit before a new high or low (conservative). Costs: Delta India taker 0.05% + 18% GST per side, slippage 2 bp per side (F1C's constants); stress x2. P&L is in Rs at 85 Rs/USD, per 0.01 BTC or 0.1 ETH. Before-tax is primary; after the 31.2% tax on each winning trade (F1C) is also reported. |

**Random twins (1,000 per rule):**
- R1-R6, R13-R16: the same entry times and exits, with the side drawn at random (50/50). This tests the direction
  skill.
- R7-R11, R17, R18: skip the same NUMBER of trades, chosen at random.
- R12: the same number of expiry-day option buys, drawn at random from the eligible (day, side) pairs.
- p = share of twins with P&L >= the rule's.

**Reported for every rule:**
- trades/day, win rate, average win and loss, P&L/day after costs, % green days, worst day, max drawdown;
- "big winners cut short": trades whose exit was followed, before 15:20 (crypto: within h more minutes), by the
  option (perp) going a further +100% of the entry premium (crypto: +2 sigma_h) in the trade's direction;
- design walk-forward and holdout, p vs twins, BH q, and sample sizes;
- the same rule built from the GBM baseline's scores, side by side ("does the NN rule beat the trees' rule?").

**Gates for "could go on PAPER behind a switch, off by default; never live":**
1. Holdout P&L/day > 0 after costs, and also at stress costs.
2. Holdout p vs twins with BH q <= 0.05 over all the rules that ran (16-18).
3. Walk-forward P&L positive in >= 3 of 4 India folds (2 of 3 crypto).
4. At least 100 holdout trades (R7-R11, R17, R18: at least 100 skipped trades).
5. The NN rule's holdout P&L >= the GBM rule's. If the GBM rule passes and the NN rule does not, the report says the
   trees are enough.

Anything that fails stays a note. Q3 (option-chart context) would in any case need Boss's own switch.

## 7. Phone budget (reported if any rule passes)

The report gives parameter count, the float32 file size, and the measured single-row CPU latency of each model.
Thresholds such as "under 1 MB and 5 ms" are reported, not gated.

## 8. Order of work

1. This file, committed and pushed.
2. Build the data (cube, features, R30 labels, sequences, arm trades). Run the tests: look-ahead (inputs at t use
   rows <= t only), training-only scaling, the holdout lock, costs.
3. Design walk-forward for every question. Commit the results JSON.
4. Read each holdout once (India once for Q1-Q4, crypto once for Q5), with the models trained before it.
5. Report, entries, knowledge pack.

## Amendment 1 (14 Oct 2026, before any India model was fitted): where option prices come from

What was known when it was written:
- No India model had been fitted and no India walk-forward or holdout result existed.
- The crypto walk-forward (Q5) was running; its first fold had finished, and no score had been looked at.
- For MIDCPNIFTY design only, coverage counts had been read: the share of decisions with a priced option, the number
  of expiry days, and the mean net of all Q1 / Q4 option trades as a sanity check. Nothing was chosen on them.

The change:
- The brain's cube has an option chain only where its expiry calendar has rules (NIFTY, BANKNIFTY).
  FINNIFTY, MIDCPNIFTY and SENSEX cubes were built index-only.
- R30 therefore reads option bars for all five indices **straight from the lake's rolling weekly files**
  (`opt_<UND>_WEEK_<YEAR>`, nearest expiry, ATM-10..+10), as R25 / R26 / R28 do.
- **Expiry days come from the data with R25's rule**: the ATM time value at 15:15 is < 0.6% of spot and more than
  doubles the next session. This replaces the cube's `is_expiry_day` in Q1 (the 15:05 square-off), Q3 (the population)
  and Q4 (no entry on an expiry day). BANKNIFTY's monthly expiries after Nov 2024 count as expiry days. They are real
  expiries, so the square-off applies.
- Within a session a strike is one contract. Q4 never enters on an expiry day, so the next morning's bar of the same
  strike is the same contract.
- Brain features are unchanged: the three index-only indices have no option-context features, as in B1.

Nothing else changes.
