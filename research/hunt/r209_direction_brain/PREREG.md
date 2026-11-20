# R209 pre-registration: a "direction brain" - every input the data has, three models, one locked holdout

Written 20 Nov 2026, **before any R209 number was computed** (only file coverage was listed). Committed and pushed on
`claude/trend-exit-study` before the code's first run. Research only: nothing in `android/**` changes. Owner: Boss.

Boss, 20 Nov: *"If we get correct direction prediction then our all strategies will work more accurate, use your 100%
effort to get your best direction."*

**Signal source: the index.** The prediction is of the index's own direction. Option-chain data (OI, IV, skew, PCR,
straddle, volume) is used only as **context inputs** around the index (CLAUDE.md allows this); no rule reads an option's
own price chart for its signal.

## 0. Honest prior, written before any number

Direction at the 15 - 60 minute horizon has failed every test here: R16 (AUC 0.51 - 0.54), the 35-feature direction model
(51 - 53%), R41 / R123 / R187 (neural networks), R151 (pickers), R105B, R79, R206 (today: the arms' own calls are right
49 - 53%). R209 is the most complete single attempt so far - all five indices together, India VIX and the option chain,
three model families, 4.5 years - but **the expected result is that it fails**. The bar below is set so that a pass means
something usable, not just "a hair better than a coin".

## 1. Data (nothing raw in git)

From the `dhan-data` branch of options-lab (Dhan v2, fetched 6 Oct 2026), read with `python3 -I`:
- 1-minute index candles of NIFTY, BANKNIFTY, FINNIFTY, MIDCPNIFTY, SENSEX and INDIA VIX, 8 Oct 2021 - 5 Oct 2026;
- 1-minute rolling options (ATM-10 .. ATM+10, calls and puts, with OI, IV, volume) of the **nearest expiry**: for each
  index and day, the WEEK series if it has rows that day, else the MONTH series. Coverage: NIFTY, BANKNIFTY and FINNIFTY
  from Oct 2021, MIDCPNIFTY from Jan 2022, SENSEX from May 2023. Where an index has no option rows, its option inputs are
  missing (the tree model takes missing values natively; the linear and neural models get 0 plus a "missing" flag).

## 2. What is predicted

**Decision points:** every index, every trading day, every minute e = 5, 10, ..., 355 (09:20 - 15:10, the arms' entry
window; every arm signal's entry minute is one of these). Everything the model sees is known at the close of minute
e - 1.

**Target (primary):** up15 = 1 when the index's last close at or before e + 14 is above its last close at or before
e - 1 (R206's definition). The model's call is "up" when P(up) >= 0.5, else "down"; it is **right** when the index moved
that way. Secondary (reported only): the 30-minute version.

## 3. Inputs (all causal; 1-minute bars before e, and 5-minute bars that end before e)

| group | inputs |
|---|---|
| **P** own index | returns over the last 1, 5, 15, 30, 60 minutes and since the day's open; the gap; yesterday's return; position in today's range; distance to the day's average price; 30-minute realised volatility; the last 15 minutes' range over its 20-session average at that minute; 5-minute Donchian-20 position; Supertrend(10, 3) direction; EMA20 - EMA50 of 5-minute closes (% of price); minute of the day; weekday |
| **X** cross-index | the other four indices' 5, 15 and 60-minute returns (in a fixed order); how many of the five are up over 5 and over 15 minutes; NIFTY's 5 and 15-minute return |
| **V** India VIX | level; change over 5, 15, 60 minutes and since the open |
| **O** option chain | ATM call IV, ATM put IV, put - call IV at 2 strikes out (skew); their 15-minute changes; put / call OI over ATM-5 .. ATM+5 and its 15 and 30-minute change; total call OI and put OI 15-minute change (%); straddle price / spot and its 15-minute change; put / call volume over the last 15 minutes |

Plus the target index as a one-hot. One model for all five indices.

## 4. Models (fixed now; no tuning on the holdout)

- **M-LOG**: logistic regression (standardised inputs, C = 1.0).
- **M-GBM**: gradient-boosted trees (`HistGradientBoostingClassifier`, 300 rounds, learning rate 0.05, 31 leaves, at
  least 500 samples a leaf, L2 1.0, no early stopping).
- **M-NET**: a neural network (`MLPClassifier`, layers 64 and 32, ReLU, alpha 1e-3, early stopping on a 10% slice of the
  training data, seed 209).

**Walk-forward** (design): train on every decision point before a quarter, predict the quarter; quarters 2023 Q1 -
2026 Q1 (13). Training starts 1 Jan 2022 (Oct - Dec 2021 is warm-up for the inputs). **The model used for the holdout and
for the arms** = the one of the three with the highest pooled walk-forward AUC (chosen before the holdout), trained on
everything up to 31 Mar 2026.

Also reported in the walk-forward (descriptive): input-group ablation for M-GBM (P; P + X; P + X + V; all).

**Holdout: 1 Apr - 5 Oct 2026, read once** (`results/HOLDOUT_READ` written first). This period has been read for the arms'
P&L by earlier studies, but **no direction model has been fitted or scored on it here.**

## 5. The tests (8) and the gate

| id | question | passes only if (holdout) |
|---|---|---|
| **T1-LOG**, **T1-GBM**, **T1-NET** | is the model better than a coin? | AUC > 0.5 with day-block bootstrap p < 0.05 **and** AUC >= 0.530 **and** its most confident 20% of calls are right >= 55% **and** in the walk-forward that top-20% accuracy was >= 55% in at least 9 of 13 quarters |
| **T2-R72**, **T2-R51**, **T2-LIQ** | does it make the arms' own calls more reliable? (R206's signals, index-only) | signals whose side the chosen model favours (P(side) >= 0.5) are right more often than the others (d > 0, day-block p < 0.05), **and** the favoured signals' share right is at least 3 points above the source's own |
| **T3-R72**, **T3-LIQ** | does it make money as a filter on today's arms? | see below |

**T3 (money):** today's rules (V5), 1 lot, R202's option build (data to 5 Oct 2026). The filter skips an arm's trade when
the chosen model's P(the trade's side) is below t. t is chosen on the design window only (21 Nov 2024 - 31 Mar 2026, from
the walk-forward predictions) from {0.45, 0.48, 0.50, 0.52} as the value with the highest net (ties: the lowest t);
applied as the app applies a skip (after the walk and the picker). Gate: R202's (better than B in design and in the
holdout; 3 of 4 design quarters; drawdown not above B's; at least 40 trades removed in each window; holdout random-skip
twin p < 0.05). R51 is not tested for money (Boss is stopping it live).

**BH:** one family of 8 on the normal-tail p of each test's statistic, m = 4,904 (the entry files on this branch before
R209) + 8 = **4,912**, q <= 0.10. A test passes only if its own items and q <= 0.10 hold.

**What a pass would mean:** T1 = the index's 15-minute direction is predictable beyond a coin, usefully; T2 = it improves
the arms' calls; T3 = it adds money. Only T3 is a trading result, and even a T3 pass would need new days before anything
goes live (CLAUDE.md: a new skip on live starts in shadow).

## 6. Order of work

1. This file is committed and pushed before any number.
2. `r209data.py build` (inputs, labels), `r209study.py wf` -> `results/wf.json` (walk-forward of the three models, the
   ablation, the model choice, T3's t); committed with the code.
3. `r209study.py holdout` (read once) -> `results/holdout.json`; `r209study.py arms` (T2, T3) -> `results/arms.json`;
   `decide` -> `results/decision.json`; `r209report.py` -> `results/report.md` and 8 log entries.
4. Nothing in sections 2 - 5 changes after the walk-forward numbers; an amendment is dated and written here first.

## 7. Tests (pytest, synthetic, no data)

The look-ahead guard (every input of a decision point is unchanged when every minute from e on - index, VIX and options -
is replaced by noise; the label reads only up to e + 14), missing minutes and an index with no options, the
nearest-expiry choice, the walk-forward split (no training row on or after the quarter's first day), the day-block AUC
bootstrap (a real signal is found, a coin is not), top-20% accuracy, and the gates (each item fails alone).
