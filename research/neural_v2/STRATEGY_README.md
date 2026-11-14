# NIFTY confirmed breakout research

Read strategy_results/REPORT.md before considering any use. The strategy lost money and is rejected as a trading candidate.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
python -m pytest research/neural_v2/test_strategy.py -q
python -m research.neural_v2.strategy --lake /absolute/path/to/lake
```

Use the same eight data files documented in README.md. No raw market data or account information is committed. Strategy rules were registered locally in STRATEGY_PREREG.md before calculating this candidate's results.

The strategy uses NIFTY price breaks, BANKNIFTY confirmation, India VIX context and actual fixed option strikes. Final all-data neural models are excluded from historical signal generation because doing otherwise would contaminate the claimed evaluation.

Base and doubled-friction accounts independently apply their own cash and risk limits. Therefore a cost correction or stress scenario may alter future eligibility and trade count, not only the cost of identical trades. Initial inherited-cost outputs are in strategy_initial_cost_result.json; corrected outputs are in strategy_results/. A rerun rebuilds all ledgers and reports. Extended average win/loss and exit diagnostics in the saved results can be recomputed from the CSV ledgers.

No fresh holdout, parameter optimization, live trades or production integration. Missing holding quotes delay executable orders. Conservative OHLC stop ordering and assumed friction cannot reconstruct exact historical market depth.
