# Whole-lot neural target2000 —15 November2026 India

Objective: target Rs2000 NET on a winning trade, report both average winner and
all-trade expectancy. No claim every trade can win or attain Rs2000 expectancy.
One whole NIFTY weekly lot only: historical75units before2026expiry,65thereafter,
existing calendar mapping. No fractional quantity or multiplication of profits.
Rs100000 account,2500maximum initial risk,3000daily,13000monthly,40000DD,
max2trades/day; one position at a time. No real orders or production changes.

At next-open entry calculate fixed premium barriers corresponding to +Rs2000
and -Rs1500 after NORMAL dated fees/friction. Stop floorRs0.05 if required.
Round target UP and stop DOWN to Rs0.05 price ticks. Stress uses IDENTICAL
normal-cost barriers, doubled execution friction, actual stressed risk/debit.
Stop before target when OHLC touches both; gap stop at open; max30minutes at
next open; square off14:50; no trail. Gaps/account gates may exceed loss budgets.
Labels use the same fixed contract, exit rules and normal costs. Missing future
outcomes mask labels only, not prospective rows. No manipulated winning subset.

Reuse payoff20 causal30features (underlying, VIX, levels, IV/modeled delta,
fixed-strike OI and volume proxies) at5minute decision times09:45–13:45.
Sequence consists of SIX consecutive5minute snapshots for SAME side and SAME
session, latest time included. Underlying features themselves include1/5/15/30
minute context. Changing snapshot candidate strikes is a market-state feature,
not holding a rolling ATM contract. Final held strike remains fixed. No10s claims.
Exclude early/incomplete sequences prospectively. Use contemporaneous30features
for dense/logistic baselines on exactly the same eligible rows.

Models: logistic(C.1)/ridge(alpha100) baseline; dense64/32/dropout.2 with3heads;
CNN32(kernel3 causal left padding)→GRU32→learned temporal attention→dense32→3heads.
Heads: p(target hit), p(net>0), expected net/2000. Loss BCE(target)+BCE(win)
+0.5Huber(net/2000). AdamW lr.001,decay.001,batch256,max25epochs,patience5,
gradient clip1. Dense seed42; hybrid seeds42and73 averaged BEFORE calibration.
Both hybrid checkpoints saved, not best-seed selection. No hyperparameter sweep.
Train-only imputation/scaling/missing indicators. Temperature/intercept calibration
of each binary head on separate earlier calibration dates. No outer fitting.

Outer chronological evaluation Apr–Jun and Jul–19Oct2026. Preceding dates split
70%fit,15%earlystop,15%calibration by full session; same-day labels only, no labels
crossing splits. Earlier fitted weights ONLY used for the appropriate outer fold.
ALL history has been inspected before: these are development walk-forward
experiments, not untouched final tests. Freeze all gates before outcomes.

For each3model families linear,dense,hybrid: p_win>=.55/.60/.65 crossed with
predicted net>=0/250/500, p_target>=.25 fixed (27policies). Select highest expected
net qualifying side, CE tie-break. Add3ungated highest-expected-net model policies
and1underlying15minute momentum baseline:31policies,62normal/stress endpoints.
All policies evaluated, no selection by outer results. Account carries through
both outerfolds. Separate fold-restart diagnostics explicitly labeled if reported.
Report candidate target prevalence, Brier/AUC/calibration, actual target reaches,
net wins, averagewinner, averagealltrade, profitfactor, netprofit, drawdown.
1000permutations +1000five-day blocks for each account; BH across62endpoints.
Require>=100trades,>=65%wins,positive normal ANDstress profit and existing risk
limits before historical qualification; future unseen verification still needed.
No post-result threshold expansion, no paid compute, no automatic main merge.
