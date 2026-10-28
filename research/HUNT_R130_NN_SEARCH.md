# R130: more S5-like rules from the network? None better than S5

28 Oct 2026. Research only: nothing in the app changes. Boss: *"Now using neuro network, find more amazing strategy like s5"*.

## The answer

**84 rules built like S5 were searched: 7 time windows × any / puts / calls × an expected-net threshold of ₹300 or ₹600 × fixed
or trailing exits. None is better than S5, and none passes every gate.**
- Only one new rule passed the walk-forward: **W6-any-300-x0**, picks at 13:05-14:00 with the cell's own fixed exits. It made
  311 trades, 53% won, +₹130 a trade, q 0.04, 3 of 4 folds.
- **It is a 2024 artifact:**
  - 92% of its trades were in 2024 (285 of 311);
  - its best 5 days give 109% of the profit;
  - its worst month was −₹17,984, which breaks Boss's −₹13,000 month stop.
- **It cannot be checked on 2026:** it made only 4 trades in Apr - Oct 2026 (−₹75 a trade). Nothing to forward-test with any
  confidence.
- **The network only trades the morning in 2026.**
  - Every window's picks bunch in 2024, when the network's forecasts were most often above ₹300.
  - In Apr - Oct 2026 only the 09:20-09:30 window (S5's) traded more than 30 times: 39-41 trades, +₹579 to +₹625 a trade.
  - So S5 is the one rule with recent evidence.
- **Under this study's stricter random-time twins, S5 Trail itself is p 0.037, q 0.21.** R128's twins took a
  random side instead, and gave q 0.004. Its timing beats random rows of the same morning only weakly, after correcting for 84
  rules.
- **22 of the 36 rules with ≥ 100 walk-forward trades were positive, almost all from 2024.**

**Nothing new goes to paper or to a forward test.** S5 Trail stays as it is (Boss's exception).

## The best rules by walk-forward average (≥ 100 trades)

Columns: walk-forward trades, won, average net, folds positive, q, the share of trades in the busiest year, then the holdout's
trades and average.

| rule | wf trades | won | avg net | folds + | q | busiest year | holdout trades | holdout avg |
|---|---|---|---|---|---|---|---|---|
| W3-call-300-x0 | 111 | 56% | +₹254 | 1/4 | 0.62 | 99% | 0 | - |
| W7-any-300-x1 | 200 | 51% | +₹249 | 2/4 | 0.26 | 96% | 6 | −₹1,020 |
| W7-put-300-x1 | 142 | 51% | +₹243 | 2/4 | 0.14 | 96% | 6 | −₹1,020 |
| W1-any-300-x1 | 562 | 59% | +₹231 | 4/4 | 0.21 | 86% | 40 | +₹625 |
| W3-call-300-x1 | 115 | 62% | +₹219 | 2/4 | 0.65 | 99% | 0 | - |
| W4-call-300-x1 | 113 | 56% | +₹198 | 2/4 | 0.83 | 98% | 0 | - |
| W1-put-300-x1 | 500 | 59% | +₹198 | 3/4 | 0.19 | 85% | 39 | +₹624 |
| W1-put-300-x0 | 494 | 53% | +₹198 | 2/4 | 0.16 | 85% | 40 | +₹608 |
| W1-any-300-x0 | 555 | 53% | +₹172 | 3/4 | 0.21 | 86% | 41 | +₹579 |
| W6-any-300-x1 | 322 | 60% | +₹147 | 3/4 | 0.26 | 92% | 5 | +₹1,154 |
| W6-any-300-x0 | 311 | 53% | +₹130 | 3/4 | 0.04 | 92% | 4 | −₹75 |
| W4-call-300-x0 | 108 | 54% | +₹109 | 2/4 | 0.67 | 98% | 0 | - |

Rule names read window-side-threshold-exit:
- **window:** W1 09:20-09:30, W2 09:35-10:00, W3 10:05-11:00, W4 11:05-12:00, W5 12:05-13:00, W6 13:05-14:00, W7 14:05-14:55;
- **exit:** x0 is the cell's own fixed exit; x1 is the trailing lock.

All rules: `research/hunt/r130_nn_search/results/results.json`.

## How it was done

- **Pre-registration:** `research/hunt/r130_nn_search/PREREG.md`, commit `3572389`, before any number.
- **Code and tests:** `r130.py`, `test_r130.py` (5 tests).
- **Inputs:** R123's out-of-sample forecasts from N1: the walk-forward folds 2023, 2024, 2025 and Jan - Mar 2026, then the
  holdout fit for Apr - Oct 2026.
- **The rule:** the best allowed, fillable cell-side by expected net at each decision row of the window. R123's portfolio: one
  open trade per index, 5 a day.
- **Exits:** x0 uses R123's own simulated exits; x1 uses R128's trailing lock, on the cleaned lake option bars.
- **Money:** 1 lot, dated charges and liquidity-aware slippage.
- **Twins:** 100 random decision rows of the same window, index and day, with the same cell-side and exit, pooled into 1,000
  totals. BH over the 84 rules.
- **Signal source:** the index (the network reads index, VIX and breadth minutes; the option chain is context). The ATM option is
  what is bought.

## Honest notes

1. **The holdout had been read by R123-R129.** A pass would only have earned a forward test.
2. **The network was trained up to Mar 2026.** In 2026 its expected nets rarely clear ₹300 outside the morning, so later windows
   barely trade. A retrained network might differ, but retraining on data that includes the holdout would leave no clean test at
   all.
3. **Six rules had q ≤ 0.10** (W2 puts, W6, W7). Each failed another gate: too few trades, too few positive folds, or a breached
   account stop.
