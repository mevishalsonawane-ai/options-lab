# Neural V2 development experiment, 14 November 2026

Research only; no production changes or orders. Registered before V2 fitting.

Scope: latest available twelve months of NIFTY weekly options, with NIFTY,
BANKNIFTY and India VIX minute context. Other markets and adaptive production
retraining are deferred until this minimal experiment demonstrates an edge.

Three architectures: 64/32/16 MLP, causal CNN + LSTM, causal CNN + GRU +
attention. Learning rates 0.001 and 0.0003, six epochs maximum, seed 42,
AdamW, dropout 0.2, weight decay 0.001, gradient clipping 1. Inner validation
chooses epoch and learning rate per architecture; equal-weight ensemble is
fixed in advance. No further hyperparameter search in this run.

Underlying signals; OI, IV and chain activity are context. Thirty-minute
sequences; sample signals every five minutes after sixty minutes of history.
Buy the signal-time ATM strike at next-minute open, hold that fixed strike
and expiry, exit after fifteen minutes or at first minute-open observation
of -15% stop/+30% target. No intrabar guarantee is assumed. Exit before 15:05;
one position at a time. Reject incomplete paths and ambiguous quotes.

Targets: CE/PE/no-trade (best net trade exceeding Rs100), two net premium
outcomes scaled by Rs1000, current trailing regime as auxiliary task.
Labels do not turn retrospective returns into executable foresight.

Three expanding outer development folds: 50-65%, 65-80%, 80-100% of session
dates. Inner validation is the final 20% of preceding dates. Splits are by
session, with no sequence/label crossing sessions. Fit scaling on inner
training only. Six candidates, three inner-selected architectures and fixed
ensemble are all reported, plus no-trade and underlying momentum baselines.
No selection on outer results; all historical results are previously seen
development data. Fresh final test is unavailable and must not be invented.

Cost assumptions: one dated NIFTY lot; brokerage Rs20 each order, dated STT,
dated exchange/IPFT, SEBI turnover fees, GST and buy stamp duty. Spread law
from R171: full spread = 3*(0.162+0.00292*premium), half each side; additional
slippage 0.05% each side. Stress doubles spread and slippage. These are
assumptions, not recorded bid/ask executions. Record coverage exclusions.

Report each model, sample size, net P&L, expectancy, profit factor, win rate,
drawdown including zero initial peak, worst month, daily Sharpe on fixed
Rs100000 capital, centered day-bootstrap one-sided p and BH q across all
reported nontrivial candidates. Historical experiment reuse is an additional
uncontrolled selection risk; a small q cannot overcome it. Any suitable
paper-trading verdict requires fresh validation, positive stressed results,
at least 100 evaluation trades and compliance with the repository's risk
limits. No strategy in this run can satisfy the fresh-test requirement.

Checkpoint after every epoch with optimizer, RNG, scaler, configuration and
data fingerprint. Resume only matching configuration and data. Structured
JSON logs and an HTML progress view show real status. No claim of unattended
persistence beyond the available runtime.

## Implementation correction recorded after first development pass

The initial run floored a slippage-adjusted sale at Rs0.05, which could
overstate proceeds on near-worthless options. It was corrected to a zero
lower bound and all 18 fold checkpoints were retrained. Closed-trade
drawdown replaced daily-close drawdown. Initial results are retained in
initial_attempt_results.json; all 24 reported entries across both passes
are counted for BH. No thresholds or architecture settings were relaxed.
The correction did not change the no-trade verdict.
