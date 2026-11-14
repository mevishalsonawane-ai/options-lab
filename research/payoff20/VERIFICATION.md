# Verification —14 November2026

- Payoff20 suite plus shared option-strategy tests:16passed.
- Existing research brain suite:209passed, one existing skip. Its unconstrained
  run completed in642.74seconds. A separate thread-limited repeat is additional
  verification, not a second independent test count.
- Feature catalog: up to date. Experimental-branch registry:4,643entries valid,
  including4new records (27model policies plus3baselines).
- Final report change separately checked:1overlapping report test passed.
- All60executed CSV ledgers independently reconcile trade count, positive-net
  wins, net=gross-fees-friction and ending capital. Entry time and expiry rules
  checked. Every account stores1000permutation/five-day block diagnostics.
- Saved31,752forecasts retain missing actual outcomes. Every forecast's earlier
  training-label cutoff precedes its decision time. Dataset has18,718candidate
  rows and7missing labels, with no outcome-conditioned forecast-row deletion.
- Feature prefix and train-only scaler/calibration tests; fixed-strike label/
  actual engine parity; missing quote/no contract-roll regression; neural exact
  resume and incompatible-checkpoint rejection; causal gate ignores actual-net
  columns; outer-date and complete30policy execution/report tests.
- Six fitted systems completed:2linear pairs,2tree pairs,2neural networks.
  Neural training stopped after7/19epochs; best validation states saved.
- Source self-review, whitespace and scoped changed-text credential checks.
  This is not a scan/proof for every historical repository binary or secret.

No qualifying historical policy or dependable65%-win profitable strategy.
The few positive accounts have1–6trades; the central65%/Rs100linear gate has
3trades and loses under doubled friction. Tree/MLP threshold cells are empty.
Hindsight opportunity figures use future information and must never be traded.

No production/workflow change, main merge, paid compute or real order. Source
history remains previously inspected; no pristine final holdout or true bid/ask
execution evidence. Existing GitHub BANKNIFTY data workflow has a Kaggle HTTP404
failure; current-commit CI must be checked separately from local test success.
