# R129 amendment 2 (pre-registration): can a filter keep only the loose gamma rule's winners?

Written 28 Oct 2026, after amendment 1's result and before any number of this test was computed. Boss: *"Can we do something
where only that 18% won trades can be bought"*, then *"Yes, run it"*.

**Question:** do facts known at entry time pick the loose rule's winners well enough to turn it positive out of sample?

## 1. Trades

Amendment 1's own trades:
- **L2:** trigger on the index's dual Supertrend; 221 trades, 37 won.
- **L4:** index breakout only; 228 trades, 38 won.

Both use the default signal source (the index); L1 (the option chart) is left out. The trades and their nets are unchanged; the
filter only decides which ones are kept.

## 2. The facts at entry (all from bars before the entry minute)

Ten inputs:
1. the entry minute;
2. log of the entry premium;
3. India VIX at 13:15;
4. the index's move since the open, the trade's way, in %;
5. the day's range so far, in %;
6. the wall's distance from spot, in strikes (at 13:45);
7. log of the wall's OI ratio, the trade's way (call/put for bullish, put/call for bearish);
8. how many strikes out of the money the option is;
9. the direction (+1 / −1);
10. monthly expiry (1 / 0).

## 3. The model and the keep rule (fixed here; nothing is tuned)

- **Model:** logistic regression on the 10 inputs, standardised with the training rows' mean and SD, L2 penalty C = 1.0
  (scikit-learn defaults otherwise).
- **Label:** net > 0.
- **Keep rule:** a test trade is kept when its score is at or above the 70th percentile of the training rows' own scores. That
  is roughly the top 30%.

## 4. Walk-forward, then the holdout once

| fold | trained on | tested on |
|---|---|---|
| F1 | trades before 2023 | 2023 |
| F2 | before 2024 | 2024 |
| F3 | before 2025 | 2025 |
| F4 | before 2026 | 1 Jan - 15 Mar 2026 |
| holdout (read once, after the walk-forward is written) | before 1 Apr 2026 | 1 Apr - 19 Oct 2026 |

## 5. Gates and reports (one BH family: M-L2, M-L4; also q over all R129 versions)

A filter passes only if all of these hold:
- **Walk-forward:**
  - the kept trades average a positive net at 1x and 2x slippage;
  - positive in ≥ 3 of the 4 folds;
  - better than random keeps: 2,000 random subsets of the same size from the same test trades, p ≤ 0.10 after BH;
  - AUC > 0.5 on the pooled out-of-fold scores.
- **Holdout:** the kept trades' average net > 0, and the account stop is not breached.
- **Sample size:** at least 100 kept trades over the walk-forward and the holdout together.
  - R129's 300-trade gate cannot be met: the base rule has about 220 trades in five years. That is stated plainly, and a pass
    here earns only a shadow forward test, never paper directly.

Reported:
- the kept trades: n, win rate, average win and loss, average net, total, by fold and year;
- out-of-fold AUC;
- the share of winners kept vs the share of losers kept;
- the inputs' weights (the last fold's model).
