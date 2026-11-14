# Verified execution —15November2026 India

- Original local prereg67f8436 preceded training outcomes. Subsequent explicitly
  user-requested bar extension prereg e8d2559 preceded its outcomes.
- Six neural checkpoints actually trained for84totalepochs across two folds:
  dense14/25epochs; hybrid seeds42/73:11/10and11/13epochs. Best earlier validation
  checkpoint retained; each hybrid averages both seeds before calibration.
- 16808eligible sequences,16804observed labels;4missing-label rows retained in
  prospective forecast population.30features,6same-session/same-side snapshots.
- Eight target/sequence/whole-lot/candle tests passed. Together with the unchanged
  payoff20 and strategy verification suite,24unique tests passed across runs.
  Tiny-sample variance warnings in synthetic fixtures are expected.
- A synthetic unaffordable entry found barrier construction could precede cash
  rejection. Entry debit and roundtrip-cost checks now precede barriers; all
  final real-data training/replay used the corrected execution code.
- Every saved outer probability and expected-net prediction was reconstructed
  from real rebuilt inputs and saved scaler/calibration/checkpoint files;
  inference_verification.json records six fold/model comparisons and tolerances.
- All62original and32extension full-ledger files reconciled per trade against
  dated cost function,whole-lot quantity,fixed stop/target levels,trade counts,
  wins,total net,ending cash,date and time ordering. No scaling of returns.
- Both ZIP archives were verified byte-for-byte against their ledger payloads;
  models.zip was verified against six neural checkpoint files,two baseline
  bundles and six scaler/calibration JSONs. Metadata records full feature order.
- The extension loader exactly reproduced the original linear-ungated ledger.
  Completed-bar prefix/missing-component tests verify causal confirmation.
- Original31policies x2costs=62endpoints; extension16x2=32;94total. MonteCarlo
  uses1000permutations and1000five-day block resamples per account; reordered
  account constraints are not replayed. Joint94endpoint BH saved in bar_results.
- No independently fresh holdout,no paper/live approval,no paid compute,no real
  orders,no production/workflow change. No promise of65%wins orRs2000per trade.
- Post-result probability-coherence audit found6linear-baseline rows with target
  probability above win probability; neural models had0such rows. Independent
  calibration does not enforce event nesting. These diagnostic inconsistencies
  are recorded, not silently corrected or used to tune outer predictions.
