# Cost-aware20-minute payoff selection —14 November2026, before outcomes

Continue the65%-net-win AND positive-cost-profit search. This is related to,
not an independent reinvention of, R163's failed rich-input pickers and neural_v2.
New estimand: single fixed-strike CE/PE net P&L with initial15% premium stop,
no profit target/lock and mandatory20-minute exit. Predict costed OPTION payoff,
not underlying direction or probability of reaching a distant target.

Universe: NIFTY nearest weekly,1–7days to scheduled expiry, one-strike ITM.
Latest available12months. Every5minute decision09:45–13:45, underlying/chain
features from the completed preceding minute. Forecast both actions regardless
of whether their eventual execution/outcome is observable. Missing future entry
or terminal quotes mask labels only, never remove forecast feature rows. Missing
holding minutes delay fills until the next observed quote. No changing held K.

Features: causal underlying return1/5/15/30,vol15, ATR14, EMA9/21gap, current
range/body, session and previous-session levels/gap, VIX level/15return, time,
side; snapshot selected premium, IV, delta, relative volume, signed-volume PROXY,
PCR, fixed-strike5minute OI change, moneyness, DTE, estimated current round-trip
cost and expected-move/cost ratio. Invalid/missing chain features receive train-
median imputation plus missing indicators; no fabricated buyer/seller delta.
No futures profile feature: its57session coverage is too short for this design.
No option-price return/chart entry signal. Option snapshot is execution context.

Models: logistic(C=.1)+ridge(alpha100) baseline; histogram gradient boosting
classifier/regressor100iterations,depth3,leaves7,lr.05,l2=.1; neural MLP64/32/16,
ReLU/dropout.2,two heads (win logit, net/1000), AdamW lr.001 decay.001,20epochs,
patience4,batch256,seed42. Loss BCE+.25Huber(net/1000). Train-only imputer/scaler.
Separate monotone temperature/intercept calibration for win scores; no test fit.
Regression target is actual net/1000, with Huber robustness and no selected-winner
training subset. Save model/checkpoint/calibration and every outer forecast.

Chronology: expanding earlier dates; outer tests Apr–Jun and Jul–19Oct2026.
Within preceding dates,70%fit,15%epochvalidation,15%probabilitycalibration, split
by complete sessions. No cross-day target. Label cutoff bounded by preceding
session15:30; all evaluation decisions later. History already reviewed in other
studies, so NOT a pristine final test. No final-all-data weights used in replay.

Trading gates, fixed in advance: p(win)>=.60/.65/.70 crossed with predicted
net>=0/100/200rupees,3x3sensitivity for each of3models =27policies. Take highest
predicted-net eligible side; deterministic CE tie. Baselines: alwaysCE,
alwaysPE,15minute underlying momentum (zero=no trade), same times/exits.
30policies, normal and independently replayed doubled execution friction:
60primary account endpoints in jointBH of five-day-block net tests. No selection
of a threshold on outer outcomes. Existing single-account cash/risk engine,
100000capital,1lot,2trades/day,2500risk,3000daily,13000monthly,40000DD; carry
one account across outer windows. Skip unfillable/cash/risk entries at execution.

Outcome labels model standalone trade exits; actual account controls can close
sooner. Report calibration of candidate outcomes separately from executed trade
wins. Do not market a calibrated65% score as a65% realized account win rate.
Probability forecasts are from normal-friction labels; doubled friction is an
execution stress, not retraining to a newly inspected target.

Diagnostics only: best-of-two actual future option net and perfect underlying
20minute direction. These use future data and are NEVER trading signals or model
inputs. Report opportunity fractions/gross-net differences on observed labels,
missing coverage and their optimistic/non-tradable meaning. A selected subset
can exceed an always-on opportunity fraction; it is not a universal ceiling.

Robustness:1000fixed-amount trade permutations plus1000five-day block resamples
for every account, fixed terminal-P&L limitation and no reordered cash-gate replay.
All9parameter cells/model, samples/calibration/cost identity, chronological
breakdowns. >=65%realized win,>=100executed trades, positive normal/stress net,
registered risk limits, and new forward data required for a trading verdict;
Wilson lower95%>=.65 required for a dependable65% probability claim. No guarantee,
paid resources, real orders, production change or permission to merge main.
