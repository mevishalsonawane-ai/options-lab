# Exit economics, frozen 15 November 2026 (India)

Hypothesis: the opening-range retest with completed five-minute trend and
prior move/cost filter has positive gross price movement but insufficient net
expectancy. Change only the exit geometry, retaining fixed contract execution.

This follows review of retest-20261114. ALL historical dates have been inspected;
chronological tests below are research diagnostics, not pristine holdouts.
No neural training or real orders. No production modification.

Entries: opening/retest/trend_cost/wait5/tolerance0.10ATR from retest.study.
Exit grid: initial premium stop 10%,15%,20% x target 8%,10%,12%, maximum20min.
Two additional center exits (15%stop,10%target): maximum10min and30min.
One center20min extra1minute execution-delay scenario retaining signal strike.
Thus11unique exit policies plus1delay scenario,24normal/double-friction endpoints.
No post-result expansion. Center20min must reproduce original quick exit ledger.

Use shared replay cash/risk gates. Recompute risk budget with actual chosen stop;
ignore shared engine's trailing-stop updates. Fixed stop, stop before target on
ambiguous OHLC bars; gap stops and deadline exit at open. Same original ordering
for baseline parity. Costs, capital, lot sizes, expiry, bar eligibility unchanged.
No removal of transaction costs to make policy qualify.

Run every full-year account, 1000trade permutations and1000five-day blocks,
Wilson intervals, BH across24account endpoints. Center3x3grid is sensitivity.
For two quarterly evaluation windows starting April1/July1, score11undelayed
policies on preceding data only. Strict selector retains existing >=100trades,
>=65%wins, positive normal/stress net and existing risk gates; carries account
state across evaluation windows. Also report EVERY policy in each subsequent
window as diagnostic, not a retrospectively selected strategy.
Save full ledgers and cost/exit attribution. No passed strategy without credible
sample, cost stress, neighboring profitability and independent future evidence.
