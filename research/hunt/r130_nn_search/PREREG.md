# R130 pre-registration: more S5-like rules from R123's network

Written 28 Oct 2026, before any R130 number was computed. Boss: *"Now using neuro network, find more amazing strategy like s5"*.

## 0. Honest limits, stated first

- **No new model.** The network is R123's N1, with its existing out-of-sample forecasts:
  - walk-forward folds 2023, 2024, 2025, 1 Jan - 15 Mar 2026;
  - the holdout fit for Apr - Oct 2026.
- **The holdout is not fresh.** Apr - Oct 2026 has been read by R123-R129. A rule that passes here earns only a logged-only
  forward test on sessions after 19 Oct 2026, decided by Boss. It is never paper by itself: CLAUDE.md's S5 exception covers S5
  only.
- **This is a search of 84 rules**, corrected by Benjamini-Hochberg. S5 Trail itself is one of them (W1-any-300-x1) and is
  reported as the reference, not as a find.

## 1. The rules (84 = 7 windows × 3 sides × 2 thresholds × 2 exits)

At each 5-minute decision row in the window, the network's expected net (EV) is read for the 16 cell-sides (8 target/stop/time
cells × call/put). The best allowed, fillable cell-side is taken when its EV is at least the threshold.

| part | values |
|---|---|
| **window** (decision bar closes) | W1 09:20-09:30 (S5's), W2 09:35-10:00, W3 10:05-11:00, W4 11:05-12:00, W5 12:05-13:00, W6 13:05-14:00, W7 14:05-14:55 |
| **side** | any (as S5), puts only, calls only |
| **EV threshold** | ₹300 (as S5), ₹600 |
| **exit** | x0: the cell's own target, stop and time (R123); x1: R128's trailing lock (the cell's stop; once +₹1,000 net, out at 60% of the best, minute marks; else the square-off) |

- One open trade per index, at most 5 a day: R123's portfolio, using each exit's own exit row.
- The signal comes from the index (the network reads index, VIX and breadth minutes, with the option chain as context); the ATM
  option is what is bought.

## 2. Money and data

As R123 and R128:
- the lake's cleaned option bars (minutes with no trade never priced);
- 1 lot at today's lots, `Costs("dated")` and `Fills("liq")`, and 2x slippage;
- x0 nets are R123's own; x1 nets are R128's `convex_exit`.

## 3. Twins and gates

- **Twins:** for each trade, 100 random decision rows in the same window, index and day, with the same cell-side and exit. They
  are pooled into 1,000 twin totals. p is the share of twin totals ≥ the rule's total. BH runs over the 84 walk-forward p's.
- **Gates (R123's, unchanged):**
  - walk-forward: ≥ 300 trades; average net > 0 at 1x and 2x; ≥ 3 of the 4 folds positive; q ≤ 0.10;
  - holdout (re-read): ≥ 100 trades; average net > 0 at 1x and 2x; the account stop (drawdown above −₹40,000, no month below
    −₹13,000).
- **Fragility (reported for every rule that passes the walk-forward):**
  - the share of profit from the best 5 days;
  - the share of trades in the busiest year;
  - the result without its best year.

## 4. Outputs

- Code and tests: `research/hunt/r130_nn_search/`.
- Results: `results/results.json`. The trade lists go to Drive (`derived/r130`).
- Write-up: `research/HUNT_R130_NN_SEARCH.md`.
- Entries: `research/experiments/entries/r130-*.yaml` (a summary entry plus each rule that passes the walk-forward).
- RESULT.md.
