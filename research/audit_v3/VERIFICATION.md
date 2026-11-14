# Verification — 14 November 2026

- New audit/direction/improvement tests plus neural_v2: 41 passed.
- Existing brain suite: 209 passed, one existing skip.
- Knowledge pack suite: 25 passed after correcting a test's handling of app-kind experiment facts; production builder unchanged.
- Dated cost helper plus audit suite: 28 passed (overlaps the 41-test run).
- Feature catalog: FEATURES.md up to date.
- Experiment registry: all 4,633 entries valid, including three new records.
- Saved forecast NPZ round-trips exactly without object pickles.
- Six neural checkpoints and calibration parameters are saved. Training completed; result files, ledgers and progress dashboard are included.

The corpus snapshot contains 4,630 pre-existing entries; registry validation includes the three current records. Snapshot coverage does not mean independent rerunning or manual review of every study. Aggregate logged variant counts overlap and are not a unique hypothesis count.

Model evaluation uses chronological outer windows with separate fit, epoch-selection and calibration periods. These dates have been used in previous repository research, so none is a pristine final holdout. No model or strategy was promoted. Removal-only direction filtering is a diagnostic ledger subset, not a full changed-path intrabar replay. OHLC execution assumptions and unmeasured live spreads remain limitations.

The repository's prior external-data CI failure (Kaggle downloader HTTP 404) is independent of these local tests; no workflow or production application code was modified.
