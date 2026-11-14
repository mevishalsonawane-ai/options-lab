# Cost-aware 20-minute option payoff research

**No dependable 65%-win profitable strategy established. No paper/live promotion.**
Training source: 2025-10-20–2026-10-19, 247sessions, 18718potential action rows, 18711observed labels and 7masked outcomes. 30causal features. Outer predictions: 2026-04-01–2026-10-16 on eligible non-expiry decision days.
Three systems (logistic/ridge, gradient trees, two-head neural MLP) fit on earlier data, with separate epoch selection and probability calibration. Saved outer folds never use all-data weights. Full normal and stress accounts each startRs100000 once and continue across the two outer windows.
Qualifying historical policies after sample/cost/risk/BH gates: 0. All dates were previously inspected in repository studies; chronological separation does not restore pristine data.

## Candidate payoff prediction

These are hypothetical standalone candidate outcomes, NOT the win rates of executed, account-gated trades.

| Model | Scored actions | Actual positive-net share | AUC | Brier | Net MAE Rs |
|---|---:|---:|---:|---:|---:|
| linear | 10582 | 36.93% | 0.526 | 0.2332 | 784.59 |
| mlp | 10582 | 36.93% | 0.517 | 0.2332 | 774.62 |
| trees | 10582 | 36.93% | 0.526 | 0.2330 | 785.44 |

## Actual executed accounts and fixed threshold sensitivity

p is the calibrated normal-friction probability; net is the predicted net-rupee floor. Each model has the entire predeclared3x3grid, with no threshold selected after reading outer results. Zero trades have undefined win rate.

| Policy | Trades | Net wins | Win % | Net Rs | Double-friction net Rs | Realized DD Rs | Wilson95% win interval | Block BH q |
|---|---:|---:|---:|---:|---:|---:|---|---:|
| linear__p0.60__net0 | 8 | 5 | 62.50 | -36.72 | -1105.99 | 1917.10 | 30.6–86.3% | 1.000 |
| linear__p0.60__net100 | 6 | 4 | 66.67 | 380.57 | -415.71 | 1121.75 | 30.0–90.3% | 1.000 |
| linear__p0.60__net200 | 2 | 2 | 100.00 | 855.02 | 587.54 | 0.00 | 34.2–100.0% | 1.000 |
| linear__p0.65__net0 | 3 | 2 | 66.67 | 75.56 | -350.86 | 1121.75 | 20.8–93.9% | 1.000 |
| linear__p0.65__net100 | 3 | 2 | 66.67 | 75.56 | -350.86 | 1121.75 | 20.8–93.9% | 1.000 |
| linear__p0.65__net200 | 1 | 1 | 100.00 | 746.03 | 596.83 | 0.00 | 20.7–100.0% | 1.000 |
| linear__p0.70__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| linear__p0.70__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| linear__p0.70__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.60__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.60__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.60__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.65__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.65__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.65__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.70__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.70__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| trees__p0.70__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.60__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.60__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.60__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.65__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.65__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.65__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.70__net0 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.70__net100 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| mlp__p0.70__net200 | 0 | 0 | n/a | 0.00 | 0.00 | 0.00 | n/a | 1.000 |
| baseline_call__p0.00__net0 | 135 | 49 | 36.30 | -32914.72 | -32013.28 | 38879.25 | 28.7–44.7% | 1.000 |
| baseline_put__p0.00__net0 | 154 | 59 | 38.31 | -38412.00 | -36362.09 | 38843.47 | 31.0–46.2% | 1.000 |
| baseline_momentum__p0.00__net0 | 99 | 33 | 33.33 | -39448.12 | -34629.00 | 39448.12 | 24.8–43.1% | 1.000 |

Complete calibration bins, predictions, all60executed ledgers, chronological restart diagnostics, MonteCarlo and costs are saved. A few profitable trades or a selected100% reading do not meet the100-trade requirement or demonstrate65% future success.

## Hindsight diagnostics: explicitly non-tradable

On 5291outer decision times with both outcomes observable, at least one option had positive gross P&L at 92.86% of times, versus 73.86% after costs. Perfect endpoint direction selected a profitable stopped option at 73.48% of times.
Both calculations look into the future. They are diagnostics of the opportunity pool, not executable signals or a demonstrated strategy. They ignore cash/day caps and restrict to paired observed outcomes. A selective strategy can exceed an always-on fraction, so this is not a universal upper bound. The learned models must identify profitable cases BEFORE entry; the AUC/calibration tables show whether they actually can.

## Monte Carlo and chronology

Every account has1000fixed-rupee trade permutations and1000five-consecutive-session block resamples, including zero-trade sessions; metrics are in results.json. Shuffling leaves total P&L unchanged and does not replay counterfactual cash admission. Empty ledgers stay zero and prove no edge. Single-trade bootstrap intervals are especially uninformative. Daily block net p-values are jointly BH adjusted across60endpoints, not the entire prior research program.
The first outer model learns from pre-April sessions; the second learns from pre-July sessions. Within each preceding block,70%fit/15%validation/15%calibration by whole dates. Label availability is bounded by the last earlier session15:30. Full trading equity does not reset at July; the separately reported calendar-window diagnostics do reset and must not be summed.

## Execution, targets and limitations

Signal/feature clock is on the underlying. Option chain snapshots are context. At each five-minute decision09:45–13:45, evaluate both one-strike-ITM actions; enter at the next-minute option open with modeled friction. Keep the same strike and scheduled expiry. Exit at an initial15% premium stop or20minutes; no target or profit lock. Missing held quotes delay fills. Account loss gates and14:50square-off can close earlier; normal-label win probabilities are not exact account-conditional probabilities.
Labels use historical counterfactual trade paths. Missing outcomes are masked only in loss/calibration/scoring; every valid causal feature row still receives predictions, and execution may reject it for absent quotes, cash or risk. Features use train-only median imputation and missing indicators, train-only scaling, clipped z-scores. Entry-open prices and future returns never enter the feature matrix.
The two MLP heads learn BCE(net>0)+0.25Huber(net/1000), with a64/32/16network; early stopping is on preceding validation data. Probabilities receive positive-temperature/intercept calibration on a different earlier window. Regression estimates are not guaranteed profits. Models are fit to normal friction and replayed unchanged under double friction.
Costs use the corrected shared research helper: brokerage, dated taxes, exchange/IPFT, GST, SEBI and stamp plus assumed spread/slippage. The cost model and zero-latency open fill remain assumptions; these are minute OHLC simulations, not recorded executable bid/ask or intrabar worst drawdown. Budgets: one lot, max2trades/day,2500initial risk,3000daily,13000monthly,40000drawdown.
Reported IV and modeled Black-Scholes delta are not buyer/seller delta. Signed volume remains a proxy. No futures-volume or footprint feature is added to paper over its limited history; no order book or spoofing detector is claimed. New forward data and executable quotes are still needed. No paid resources, real trades, production changes or universal internet/math search claim.

## Nonempty-account sequence diagnostics

| Policy | Trades | Actual DD Rs | Shuffled DD95 Rs | Day-block DD95 Rs | Day-block net95% interval Rs |
|---|---:|---:|---:|---:|---|
| linear__p0.60__net0 | 8 | 1917.10 | 3106.54 | 4377.60 | -4061.29 to 4225.60 |
| linear__p0.60__net100 | 6 | 1121.75 | 2197.70 | 3273.65 | -3228.04 to 4313.90 |
| linear__p0.60__net200 | 2 | 0.00 | 0.00 | 0.00 | 0.00 to 2984.14 |
| linear__p0.65__net0 | 3 | 1121.75 | 1121.75 | 2243.50 | -2243.50 to 2766.96 |
| linear__p0.65__net100 | 3 | 1121.75 | 1121.75 | 2243.50 | -2243.50 to 2766.96 |
| linear__p0.65__net200 | 1 | 0.00 | 0.00 | 0.00 | 0.00 to 2238.10 |
| baseline_call__p0.00__net0 | 135 | 38879.25 | 41978.01 | 53678.94 | -55309.98 to -8921.36 |
| baseline_put__p0.00__net0 | 154 | 38843.47 | 45845.41 | 60608.48 | -61954.64 to -16055.40 |
| baseline_momentum__p0.00__net0 | 99 | 39448.12 | 48275.99 | 61980.98 | -65770.72 to -16323.91 |
