# R163 pre-registration: a stronger trade picker for the Donchian arms (R72 Breadth Donchian, R51 Donchian 20)

Written 12 Nov 2026, BEFORE any R163 feature, model, score or result was computed. The only runs before this file were
R151's own scripts, re-run unchanged to check that this session's data and engine reproduce R151's committed numbers.
Research only: nothing under `android/` changes.

Boss (12 Nov): "I want to increase my win rate [of the] Donchian family, try your best ... use all resources you can."
Siblings run today: R161 and R162 (other families). Their raw p-values and mine go into one joint BH (section 7).

## 0. What is known (the bar)

- **Live today (Boss's fourth exception, 6 Nov):** R151's L2-logistic pickers skip a new break scoring under 0.4656 (R72)
  or 0.4469 (R51). A skipped break still holds its side and counts in the day caps (the app's rule).
- **R151's record on 1 Apr - 13 Oct 2026** (research walk: skipped breaks removed BEFORE the walk, so they free their side):
  - R72 picker: 652 trades, 48.9% won, Rs 2.19 lakh (arm: 1,202 trades, 45.8%, Rs 1.71 lakh);
  - R51 picker: 693 trades, 49.9% won, Rs 1.59 lakh (arm: Rs 1.35 lakh).
- R123: networks that say "70%" are right about 58% of the time. R140 / R141 / R144a / R150: single skip filters failed.
  R105C: exits alone reach 60% only by losing money.
- **Signal source (CLAUDE.md):** the index. Every candidate is the arm's own index signal (Donchian 20 on 5-minute index
  bars; R72's breadth of 4 of 5 indices; R51's Supertrend (10, 3)). The picker only decides which of those to take.
  Option-chain data (OI, PCR, IV, skew, the bought contract's OI and volume) is used as CONTEXT only. No input is the
  bought option's own price chart (premium returns are not used).

## 1. Arms, exits, costs (unchanged from R151)

- R72 and R51 exactly as R151 / the app: 1-ITM, the R101 XL exits (-15% stop, the 30-60-100 lock + L25, 30 min, 15:15),
  no trade on the index's expiry day, 09:20 - 15:10 entries.
- Priced by R101 / R106's engine on R51's Amendment A clean stores: dated Zerodha charges (Rs 20 an order, STT, exchange,
  SEBI, stamp, GST at the rates of the trade date) and R17's slippage (buy x1.0005, sell x0.9995, stop x0.999, plus a
  one-tick half-spread).
- **Lots:** every number is 1 lot unless said. 2 and 3 lots are reported from each trade's own real fills
  (`(rX - rE) x qty x n - charges(qty x n)`); the slippage multipliers are per unit, so a bigger size is assumed to fill
  at the same prices (stated, not tested).

## 2. The walk: the app's rule (primary) and R151's research walk (reference)

- **App walk (primary, all gates):** the arm's walk runs on ALL its breaks, as the app does. A break the picker skips
  still holds its side and counts in the day caps; its trade is simply not taken. So the picker's trades are a SUBSET of
  the plain arm's trades, and the picker can never free a place for a trade the arm would not have made.
- **Research walk (reference only):** skipped breaks removed before the walk (R151's numbers). Reported for R151's picker
  and for any model that passes, to show the effect of the app's rule.

## 3. Periods

| period | dates | use |
|---|---|---|
| training start | 20 Nov 2024 | first trade any model learns from |
| WF (walk-forward) | 4 quarters, 1 Apr 2025 - 31 Mar 2026 | out-of-fold scores; each quarter is scored by a model trained ONLY on trades of days before the quarter starts (strictly expanding window) |
| FINAL | 1 Apr - 13 Oct 2026 | one model per (arm, method) trained on every trade 20 Nov 2024 - 31 Mar 2026, frozen, read ONCE (flag file `results/FINAL_READ`) |
| POST | 14 - 19 Oct 2026 | the lake ends 19 Oct: 4 sessions. Too few for any test; not used (stated honestly) |

- **FINAL is not virgin.** 1 Apr - 13 Oct 2026 has been read for these arms by many studies (R105C, R140 - R144a,
  R150 C, R151 C / E, R152 - R155) and R151's pickers were judged on it. Any pass is a second look and is at most a
  shadow candidate (section 9).
- The OLD period (Dec 2022 - Nov 2024) is not used: the chain features would need option files R163 did not fetch, and
  the arms' lots and expiries differ there.

## 4. Features (decision minute = the close of row er - 1; nothing from row er on)

- **F0, R151's 28 features** (its `FEATS`, computed by its own `features()`), so every model sees at least what R151 saw.
- **F1, index multi-timeframe trend and volatility** (the traded index's 1-minute bars; side-signed where it has a
  direction: + = in the trade's favour):
  - returns over the last 15, 30 and 60 minutes (reaching into the previous session when needed);
  - 30-minute realised volatility (std of 1-minute log returns) divided by the previous 20 sessions' mean whole-day std
    of 1-minute log returns;
  - the close vs the 20-session daily SMA (%, signed) and vs the 5-session SMA;
  - the 15-minute bars' EMA20 slope (signed) and the close's distance from it;
  - the distance from the day's high (calls) / low (puts) so far, % of price;
  - the previous session's return (signed) and range (% of close).
- **F2, breadth details:** the 5 indices' average move since the open (signed), their average 15-minute return
  (signed), the share whose 15-minute return agrees with the side, and the dispersion (std) of their day moves.
- **F3, India VIX:** its 30-minute change (%), and its level's percentile among the previous 250 sessions' closes.
  (Level and change from the previous close are already in F0.)
- **F4, option-chain context (the lake's `options_rolling_1m` WEEK file of the traded index; last print at or before
  row er - 1; this session only):**
  - PCR by OI over ATM-5..+5 (log, signed: + = favours the side) and its 15-minute change (signed);
  - call and put OI change over 15 minutes over ATM-5..+5 (%), expressed as the trade's own side and the other side;
  - the change in OI since 09:20 over ATM-2..+2, own side minus other side (R105F O2's quantity, as a number);
  - ATM IV (mean of call and put), the IV skew (put IV at ATM-3 minus call IV at ATM+3), and ATM IV's change since 09:20;
  - the ATM straddle as % of spot;
  - **the bought contract** (the 1-ITM strike and side the arm buys): its OI change over 15 minutes and since 09:20 (%),
    its volume over the last 5 minutes (log of lots), and a spread proxy: the mean (high - low) / close of its last 5
    one-minute bars (the lake has no bid / ask);
  - the bought premium as % of spot.
  - Missing chain values are left missing (trees handle them; the MLP and logistic use the training median).
- **F5, S/R (R150's 5-minute levels, 3+ turns, last 20 sessions):** the number of levels within 0.5% ahead and within
  0.5% behind (the nearest distances are already in F0).
- **F6, time, expiry and gap context:** sessions to the index's next expiry (from the store's expiry flags; expiry dates
  are scheduled, so this is not look-ahead), minutes since the arm's previous break on this index today (none: 999),
  and whether today's gap was filled by the decision minute.
- **R123's network score: NOT used.** R123's walk-forward predictions were kept only in that session's cache, and
  rebuilding the network (rows every 5 minutes on 5 indices since 2021, a 3-seed temporal-conv net per fold) is beyond
  this session. R123's tabular inputs (index state, VIX, breadth, chain state) enter directly through F0 - F4 instead;
  R123's own baseline (gradient trees on them) was about as good as its network on the tabular part. Stated as a gap.

## 5. Models (a small fixed set; nothing tuned on any period)

Each model is trained on the arm's TAKEN trades (plain arm, app walk) of its training window. Label for classifiers:
the trade made money after costs (net > 0, 1 lot).

| id | model | score |
|---|---|---|
| P151 | **R151's live picker**: L2 logistic (C 0.1, median impute, standardise) on R151's 28 features, threshold fixed at 0.4656 (R72) / 0.4469 (R51); re-fit per fold for WF, the exported model for FINAL | P(win) |
| G1 | LightGBM classifier on F0 - F6: 300 trees, learning rate 0.03, 15 leaves, min 50 trades a leaf, row and feature subsample 0.8, L2 1.0, seed 163 | P(win) |
| G2 | LightGBM regressor of the trade's net (Rs, 1 lot), Huber loss (alpha Rs 1,000), same settings | expected net (Rs) |
| N1 | MLP (sklearn) on F0 - F6, median impute + standardise, hidden 32-16 ReLU, L2 alpha 0.01, Adam lr 0.001, early stopping (last 20% of the training rows, 20 epochs patience), the mean of 5 seeds (163-167) | P(win) |
| E1 | **calibrated ensemble**: G1, N1 and R151's logistic each fitted on the first 75% (time order) of the training trades, each Platt-calibrated on the last 25%, then averaged | P(win) |

- No early stopping or calibration ever sees a later trade than the training window.
- If LightGBM failed to install, sklearn's HistGradientBoosting would have been used; it installed (4.7.0).

## 6. The threshold (chosen on WF folds only)

- For each model, the candidate thresholds are the pooled WF out-of-fold scores' quantiles that keep 25%, 30%, ..., 95%
  of the arm's WF trades (15 values).
- **The rule:** among thresholds whose kept WF trades (app walk, 1 lot) make a net at least P151's WF net (P151 at its live
  threshold on its own out-of-fold scores, app walk), take the one with the HIGHEST WF win rate. Ties: the lower
  threshold (more trades).
- If no threshold reaches P151's WF net, the model FAILS at WF. Its FINAL numbers are still reported (Boss asked for the
  comparison), but it cannot pass.
- The chosen threshold is a raw score (as the app would run it) and is applied unchanged to the frozen FINAL model.

## 7. Success criterion and gates (all must hold, per model and arm)

1. **WF:** pooled WF win rate >= P151's + 5.0 points AND pooled WF net >= P151's (app walk, 1 lot).
2. **FINAL:** win rate >= P151's + 5.0 points AND net >= P151's (app walk, 1 lot, P151 at its live threshold).
3. **Better than random skips of the same size:** on FINAL, keeping the same number of the plain arm's trades at random.
   Because the app walk makes the kept trades a subset of the arm's trades, the win count of a random subset is exactly
   hypergeometric: p = P(wins >= the model's wins), exact (no simulation). A random-skip twin on NET (20,000 draws) is
   reported, not gated.
4. **BH q <= 0.10** counting every earlier test at p = 1: m = 2,164 (R160's tally through R160) + R163's 8 tests
   (4 models x 2 arms). R161 / R162's tests join when their counts are known; the raw p-values are written to
   `results/pvalues.csv` for one joint BH with the siblings. Within-R163 q (m = 8) is reported too.
5. **>= 40 FINAL trades.**
6. **Positive in all periods:** net > 0 in each WF quarter and in FINAL (1 lot).

- P151 is the comparator, not a test. The plain arm and P151 are reported beside every model at 1, 2 and 3 lots.

## 8. Also reported

- Every model's WF curve (win rate, trades, net at each keep fraction) and FINAL at the chosen threshold; worst month,
  drawdown, per trade, per period.
- **Feature importance, plainly:** LightGBM gain (frozen G1, G2) and permutation importance (drop in AUC on the pooled WF
  out-of-fold scores, 5 repeats) for G1; the top 15 in words.
- **Calibration:** predicted P(win) in bins (< 0.40, 0.40-0.45, 0.45-0.50, 0.50-0.55, 0.55-0.60, >= 0.60) vs the actual
  win rate, with n, for P151, G1, N1 and E1, on WF (out-of-fold) and FINAL. Brier score and AUC beside.
- The effect of the app's rule (section 2): P151 in both walks on FINAL.

## 9. Verdict rules (CLAUDE.md)

- A pass is a second look on FINAL, so it is NOT live-ready. At most: a SHADOW label in the app next to R151's picker,
  judged on new days; nothing in the app changes now.
- If a model passes, it is exported in R151's way (`export_picker.py`: the model's numbers + feature list + threshold as
  JSON in `results/`), so the app could load it later. Trees are exported as LightGBM's JSON dump.
- Experiment entries `research/experiments/entries/r163-*.yaml`, one per test, failures included.

## 10. Tests (pytest)

- Look-ahead guard: scrambling every index, VIX and chain minute at or after row er changes no F1 - F6 feature.
- The folds: no training trade on or after the fold's first day.
- The threshold rule on synthetic curves; the hypergeometric twin against a brute-force count; BH with m_total; the
  lot scaling of net.
