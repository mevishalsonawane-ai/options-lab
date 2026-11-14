# Repository-wide audit and direction research

Read AUDIT_REPORT.md first. This is an independent experimental package; no
production logic, application knowledge assets or main-branch merge.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
python -m pytest research/audit_v3 -q
python -m research.audit_v3.corpus
python -m research.audit_v3.direction --lake /absolute/path/to/lake
python -m research.audit_v3.improvements --lake /absolute/path/to/lake
```

The six index files NIFTY/BANKNIFTY/INDIAVIX for 2025/2026 are sufficient for the
direction experiment. The improvement replay also needs the two NIFTY weekly
option files documented in neural_v2/README.md. No raw market data is committed.

The corpus scan reads available text from tracked research paths; sparse
checkouts must include study docs, knowledge, experiments, result tables and
holdout markers. It inventories binary exclusions rather than executing every
archived model. inventory.json.gz and experiments.json.gz contain JSON
provenance/metadata, not copied private accounts or raw quotes. Coverage is not
a claim that every historical study was independently rerun.

Each direction checkpoint stores data/code signatures, train-only scaler,
optimizer/RNG state, best epoch state and loss history. Re-running resumes only
matching checkpoints. Load trusted PyTorch files only. Calibration parameters
and outer time cutoffs are separate JSON files. Forecast availability is bar
timestamp plus one minute. All target labels use exact elapsed timestamps.

direction_results/forecasts.npz stores all outer forecasts, targets and cutoffs
without object pickles. Use direction.load_forecasts(path) to reconstruct the
table. Outcome columns are for scoring only and are never read by the strategy
gate. This archive round-trips exactly. No all-data fitted model enters these
historical predictions.

The remove-only gate is a diagnostic subset of an existing executed ledger.
It reserves original time/slots and does not promote new entries. It rechecks
realized cash/risk admission but cannot substitute for each arm's full engine
and intrabar equity replay. The strategy matrix explicitly marks unverified
families; Indian intraday predictions do not generalize to every instrument.

Evidence and dated-tax guards in guards.py are research tools. The original
production cost helper is untouched. Section-scoped number matching still
cannot establish semantic truth or a pristine final test.

All results are reused historical development evidence. No profitable universal
win-rate filter, reliable direction edge, paper approval or live approval was
established. No paid resources or real orders.
