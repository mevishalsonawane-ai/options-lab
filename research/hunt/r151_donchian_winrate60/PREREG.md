# R151 pre-registration: both Donchian arms to a 60%+ win rate WITH profit, by choosing trades (not by selling earlier)

Written 5 Nov 2026, BEFORE any R151 feature, model or result was computed. Boss (5 Nov): "Use all the resources you can,
and try to both donchain winrate to 60%+".

Research only; nothing under `android/` changes.

## 0. What is already known (the bar)

- **R105C (26 Oct):** exits alone reach 60% only by selling at +5%, and then both arms LOSE (R72 -Rs 1.18 lakh, R51
  -Rs 1.38 lakh in Apr - Oct 2026). A small target cuts the few big winners the arms live on.
- **R123:** networks that say "70%" are right about 58% of the time.
- **R140 / R141 / R144a:** single skip filters on these arms failed.
- **R150 B:** W20 (skip trades running into a nearby 5-min level) is R51's best lead. It is better than random skips,
  but its win rate stays about 45%.
- **So R151 keeps each arm's exits exactly as the app runs them** (R51: Donchian 20 + Supertrend; R72: Breadth Donchian;
  -15% stop, the 30-60-100 lock with L25, the time cap). It only DECIDES WHICH TRADES TO TAKE.

## 1. The idea

- **A trade picker per arm:** a model that scores each of the arm's candidates at its decision minute, from facts known
  then.
- **The rule:** take the trade only when the score is at or above a threshold.
- **The threshold** is chosen on DESIGN data only (section 4). It is the lowest score whose selected design trades won
  >= 60% AND made more per trade than the arm, with at least 25% of the arm's design trades kept. If no threshold does
  both, the arm FAILS at design and nothing more is read.

## 2. Features (index and context only; known at the decision minute)

**From the R51 store's index minutes (the traded index):**
- the time of day;
- the day's move to now;
- the move from the open;
- the range so far vs the 20-session average daily range;
- today's gap;
- distance from the session TWAP;
- the 5-min EMA20's slope and the close's distance from it;
- the size of the last 5-min bar vs its 20-bar average;
- the break's size beyond the Donchian level (close vs the 20-bar high / low);
- the number of the arm's breaks on this index earlier today;
- the 5-day return;
- the side (call / put).

**Support / resistance (R150's 5-min levels, 3+ turns, last 20 sessions):**
- distance to the nearest level AHEAD and BEHIND the trade (in %);
- the touches of the nearest ahead;
- whether the break crossed a level;
- the previous day's floor pivots: the distance to the nearest ahead.

**Context:**
- India VIX at the decision and its change from the previous close (lake minutes);
- cross-index breadth: the share of the 5 indices up on the day at the decision minute.

## 3. Models

| id | model |
|---|---|
| M1 | gradient trees: HistGradientBoostingClassifier(max_depth=3, learning_rate=0.05, max_iter=200, min_samples_leaf=40, l2_regularization=1.0, random_state=151) |
| M2 | L2 logistic (C = 0.1) on standardised features (median-imputed) |

- **Label:** the trade made money after costs (net > 0) under the arm's own exits.
- **Training:** on the arm's TAKEN trades (today's walk).
- **Design walk-forward:** expanding by quarter. The folds are R51's WF quarters (Apr 2025 to Mar 2026); each is scored
  by a model trained on every design trade before it (from 20 Nov 2024). Design scores are these out-of-fold scores.
- **For the later periods** the model is trained on ALL design trades and frozen.

## 4. Periods and gates

| period | role |
|---|---|
| DESIGN | 20 Nov 2024 - 31 Mar 2026, out-of-fold scores (Apr 2025 - Mar 2026) |
| FINAL | 1 Apr - 13 Oct 2026 |
| OLD | 1 Dec 2022 - 19 Nov 2024 (R143's store). Read by R143 / R144a / R150 B / C for these arms, so a second look. The model is trained on LATER data than this period: an out-of-time check, disclosed |

**A picker passes when, in BOTH FINAL and OLD:**
1. its kept trades won >= 60%;
2. its net is > 0 and its net per trade is >= the arm's;
3. it keeps >= 100 trades (FINAL >= 60);
4. a random-skip twin (keep the same number of the arm's candidates at random, 2,000 times) has a win rate at least as
   high with p <= 0.05;
5. BH q <= 0.10 over the 4 pickers (2 arms x 2 models).

**Reported for every picker, period and threshold:** win rate, trades, net, per trade, worst month, drawdown.

**The trade-off curve** (win rate vs net at thresholds giving 10-100% of trades) is reported too, so Boss sees what 60%
costs.

## 5. Verdict rules (CLAUDE.md)

- **Every period is a second look, so even a pass is NOT live-ready.** At most:
  - a SHADOW log in the app (each R51 / R72 trade scored and labelled "the picker would / would not take it"), judged
    after about 100 new trades;
  - paper only as Boss's exception if it fails.
- Experiment entries are `r151-*.yaml`, failures included.
- **Signal source:** the index. The model only filters the arm's own index signals.

## 6. Tests

Pytest tests cover:
- each feature's look-ahead (scrambling minutes after the decision changes nothing);
- the threshold choice on synthetic scores;
- the walk-forward folds (no training trade from a fold or later).

## Amendment A, 5 Nov 2026: the indicators' own parameters too (Boss: "Try to change level, or parameters in the indicator too"); before any result

- **Seen before.** Nothing of R151. R105C already swept the EXIT levels (targets +5/+10/+20%, stops -10/-15%): none
  gave 60% with profit. R126 swept indicator settings for direction forecasts, not these arms.

**Part P, signal parameters (exits as the app; 1-ITM; every other rule unchanged):**

| arm | Donchian lookback (5-min bars) | other | variants | today's |
|---|---|---|---|---|
| R51 | 10, 20, 30, 55 | Supertrend (ATR 10) multiple 2, 3, 4 | 12 | 20 / 3 |
| R72 | 10, 20, 30, 55 | indices breaking together (of 5) M = 3, 4, 5 | 12 | 20 / 4 |

- **Choice, on DESIGN only:** the variant with the highest win rate among those whose design net per trade >= the arm's
  today, with at least 300 design trades. Then that ONE variant is read on FINAL and OLD.
- **Part P passes** on R151's section 4 gates (>= 60% wins, net > 0 and per trade >= today's, enough trades), with
  the twin replaced by: its win rate is above today's on both FINAL and OLD.
- **The picker (sections 1-4) is ALSO run on the chosen variant** if its design win rate is above today's. This adds 2
  pickers per arm-variant; BH runs over all pickers tried.
- **Every variant is reported** (win rate, trades, net, per trade) on DESIGN, with FINAL and OLD for the chosen one
  only.

## Amendment B, 5 Nov 2026: W20 under the picker (Boss: "Try adding W20 skip on top of picker"); before any B result

- **Seen before.** Parts of R151's design run were already printed: the R51 sweep and the two R51 pickers at today's
  parameters (both found NO threshold at design). Nothing on FINAL or OLD. W20 is R150 B's rule (skip a trade when a
  5-min level, 3+ turns, last 20 sessions, lies within 0.25% ahead); on R72 it failed (R150 C, p 0.35).
- **What changes.** The arm's candidates are first cut by W20, then the picker (same features, models, folds and
  threshold rule as sections 1-3) is trained and scored on the W20-kept candidates and trades.
- **Which.** Both arms, at today's parameters and at the Amendment A variant if that variant got pickers. This adds up
  to 2 models x (1-2 parameter sets) per arm; ids end in "-W20".
- **The bar does not move.** The threshold must give >= 60% wins and per trade >= the arm's TODAY (no W20) on the WF
  folds, keeping >= 25% of the arm's (no W20) WF trades. FINAL / OLD gates as section 4; the twin drops at random from
  the W20-kept candidates; BH over every picker tried in R151.
- **Also reported:** the arm + W20 alone, per period.

## Amendment C, 5 Nov 2026: the R72 logistic picker over Nov 2024 - Oct 2026 (Boss: "Test the R72 picker on nov 24 - Oct 2026"); before any C result

- **Seen before.** The picker's design curve (out-of-fold, Apr 2025 - Mar 2026), from which its best point was quoted to
  Boss: score >= 0.4656 keeps 598 of 2,238 trades, 53.7% won, Rs 389 a trade. That point was CHOSEN because it looked
  best, so it is biased upward. Nothing of this picker on Apr - Oct 2026 or Dec 2022 - Nov 2024 (R151 never read it
  there: it failed at design).
- **The rule, fixed now:** R72 as the app (Donchian 20, 4 of 5), the picker R72-20-4-M2 (L2 logistic, C 0.1), take a
  candidate when its score >= 0.4656.
- **Scores per period (no score from a model that saw the trade):**
  - Nov 2024 - Mar 2025: no out-of-sample score exists (these months train the first model). Reported as in-sample,
    flagged, and NOT counted.
  - Apr 2025 - Mar 2026: the walk-forward out-of-fold scores already in `design.json`.
  - Apr - Oct 2026: the frozen model trained on all design trades (`models.pkl`), read once.
- **Gate on Apr - Oct 2026 (the only new read):** kept trades win >= 50%, net > 0, per trade >= the arm's, >= 60 trades,
  and a random-skip twin (same number kept at random, 2,000 times) wins at least as often with p <= 0.05. One test, no
  BH.
- **Reported:** each period and Apr 2025 - Oct 2026 together (trades, win, net, per trade, worst month, drawdown); the
  arm alone beside it; the trade-off curve on Apr - Oct 2026 for the same thresholds as the design curve.
- **Second look:** Apr - Oct 2026 was read for R72 itself by earlier studies (R105C, R140-R144a, R150 C), not for this
  picker. Even a pass is shadow / paper-exception only (CLAUDE.md).

## Amendment D, 5 Nov 2026: a feature-cache bug; everything re-run (found while running Amendment C)

- **The bug.** `feats_of_units` cached features by (index, day, minute, side) for the whole process. `design` and
  `design_w20` ran R51 before R72 in one process, so R72 candidates on the same minute as an R51 candidate took R51's
  features: R51's "breaks today" count and no R72 breadth count (`f_count`). R51 at 30 / 4 likewise took 20 / 3's
  counts. Under W20, "breaks today" counted only W20-kept breaks. Found because the clean Amendment C run kept 634
  Apr 2025 - Mar 2026 trades at the threshold where `design.json` showed 598.
- **The fix.** The cache is per call, so each arm / setting gets its own features. Under W20, features come from the
  arm's full candidate list, and W20 only removes candidates after that.
- **What is re-run, with no rule changed:** `design`, `design_w20`, then `final` / `old` (for any picker that now
  passes design), and Amendment C as written: R72-20-4-M2, threshold 0.4656, the same gate. The model is re-frozen on
  the clean features.
- **Both versions are reported.** The buggy files are kept as `results/bug_cache/`.
- Every Amendment C number already printed (Apr - Oct 2026: 403 trades, 47.4%, Rs 115 a trade, twin p 0.22) came from
  the buggy frozen model on clean features. It is therefore a second look at Apr - Oct 2026 for this picker, and that
  is disclosed.
