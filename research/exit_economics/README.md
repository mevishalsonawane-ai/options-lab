# Opening-range retest exit economics

Focused continuation of retest-20261114, with entries frozen. See PREREG.md for
exact grid and results/REPORT.md for all results once completed.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 python -m pytest research/exit_economics research/retest research/neural_v2/test_strategy.py -q
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 python -m research.exit_economics.study --lake /absolute/path/to/lake
```

Results include 24full-year account endpoints,48separate chronological diagnostic
ledgers and2continuous walk-forward ledgers (74CSV files in ledgers.zip).
Cash/risk-controlled account replay uses fixed strikes and real calendar dates.
The baseline center is checked against the original retest implementation.

The pure exit function ignores trailing updates in the shared research engine;
stop_pct is also passed to entry risk eligibility. Changing stop size can change
which signals are affordable, so these are full policy comparisons rather than
identical trade cohorts. Deadlines use bar open, stops precede targets when both
are touched, and gap stops execute at the observed open. OHLC fills are modeled.
All historical data has already been studied. Passing a retrospective threshold
would not be independent validation. No production or order-placement changes.

Progress is updated per scenario. There is no persistent background service;
restarting replays from the beginning. No raw data or private recordings included.
