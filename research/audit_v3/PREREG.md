# Repository audit and direction experiment, 14 November 2026

Owner requested use of the complete research and knowledge folders, flaw review,
strategy win-rate improvements and neural direction prediction. Research only.
Do not alter production, application knowledge assets, existing verdicts or main.

Inventory every tracked research file, read all available study text/source and
structured result files, parse every experiment entry and strategy card. Report
coverage, missing files and audit candidates. Keyword matches are not proof;
confirmed findings require source locations and regression tests. No claim that
every historical study has been independently re-executed.

Direction task: pooled NIFTY/BANKNIFTY, latest available twelve months. Predict
actual underlying close returns over 5, 15 and 30 elapsed minutes. Exact timestamp
targets; missing outcomes and exact ties are masked per head, never used to
remove forecasts. Features must exist at bar close; publish availability time
one minute after the bar timestamp. Use signals every five minutes from 10:15
through 14:45 and 30-step sequences with sufficient causal feature history.

Models: fixed MLP 64/32/16 and causal CNN-GRU attention (32 units), three binary
heads. No parameter search: AdamW lr0.001, decay0.001, dropout0.2, batch256,
seed42, up to20 epochs, patience4. Compare regularized logistic regression and
training-frequency majority/constant forecasts. Early-stop on masked BCE.

Three expanding outer folds at 50-65%,65-80%,80-100% of session dates. The
preceding data are split chronologically 60/20/20 for fitting, epoch validation,
and monotone temperature/intercept calibration. Fit scalers only on fit data.
Do not refit on calibration or outer outcomes. Record all cutoffs.

Report accuracy, balanced accuracy, AUC, log-loss, Brier score, calibration,
fixed 55%-confidence coverage/accuracy and day-clustered comparison against the
fit-majority classifier. BH across 18 model/instrument/horizon tests. These
are reused historical development folds; no untouched final test is available.
The previously trained all-data models must not enter historical evaluation.

Strategy experiments: preserve the prior confirmed-breakout baseline; test two
fixed exit ablations: (1) remove the hard target but keep its profit lock and
45-minute cap; (2) remove target and profit lock, retain the initial stop and
20-minute failure check, extend maximum holding to60 minutes. Same entries,
contracts, fees, account budgets and timestamps; normal and double friction
each independently replay the account. No relaxed risk limit or new sizing.

Also test a remove-only 55%-confidence direction gate using outer-fold forecasts
from the fixed CNN-GRU model (chosen before reading outer forecast scores)
on the baseline's existing executed trades. Skipped trades reserve their old
time/slots; never promote an entry that the baseline did not execute. Recheck
cash/risk admission on retained trades. Evaluate only the common forecast date
window, resetting initial capital once at that fixed start for both variants.
Any such gate is diagnostic, not a promise that every strategy can improve.

Consolidate an improvement/eligibility matrix for every knowledge strategy card
plus missing current families (R51, R72, R52, CISD). Mark strategy-specific
replays lacking inputs or engine parity as unverified. Intraday Indian-index
direction models do not cover overnight, MCX, crypto or multi-leg selling.

Require net expectancy, stressed P&L, drawdown and sample size alongside win rate.
Report all failures; do not tune until a desired win percentage appears. No live
or paper promotion can pass without fresh evidence and strategy-engine parity.
