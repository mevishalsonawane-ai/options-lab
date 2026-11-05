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
