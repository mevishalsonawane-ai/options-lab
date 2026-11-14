# Offline neural development research

This folder contains the preregistered experiment, trained epoch checkpoints,
cost-adjusted retrospective results and baseline reproduction. No broker or
production integration exists. Read results/REPORT.md before using any output.

Install Python 3.12 dependencies in an isolated virtual environment:

```bash
python -m pip install -r research/neural_v2/requirements.txt
bash research/neural_v2/run.sh /absolute/path/to/lake
```

The lake folder must contain index_1m_{NIFTY,BANKNIFTY,INDIAVIX}_{2025,2026}.parquet
and opt_NIFTY_WEEK_{2025,2026}.parquet. Obtain the owner's data through their
authorized connection; no raw market data is committed. SHA256 fingerprints are
in results/data_audit.json. The shared repository expiry table is also required.

The runner tests the pipeline, retries failures at most three times and resumes
matching checkpoints (data, settings and training-code fingerprints). A completed rerun reconstructs predictions/backtests using
the existing selected checkpoints. It does not continue past six epochs or start
new experiments. Runtime survival is not guaranteed; there is no hosted service,
scheduled retraining, or background job after completion. Install additional
repository dependencies before running the full existing research test suite.

Open results/dashboard.html to inspect progress, current epoch/validation loss,
last checkpoint, errors and completion status. The static page refreshes every
ten seconds; serve the repository locally with Python's http.server if the browser
does not reload local files. Training histories are embedded in each checkpoint;
events.jsonl contains the progress history. The page is not publicly hosted.

Checkpoint fields: model (last epoch), best_model (inner-validation selected),
optimizer, epoch, rng, mean, scale, config, fingerprint and history. Only load
trusted checkpoints; torch.load with weights_only=False accepts Python objects.
Use best_model for reproducing evaluation. Each checkpoint belongs to its own
fold; the last fold is not fitted on the later evaluation days. Final all-data models are now in final_training/; those models are not approved for deployment. See final_training/TRAINING_REPORT.md.

The baseline/original folder preserves the four prior artifacts unchanged.
baseline/reproduced preserves the rerun outputs. To rerun the original script,
point its /mnt/data input root to the eight-file data folder and remove the
.bin suffix from input paths (the original downloaded names included .bin).
The baseline is a direction classifier, not an option backtest. Its test metrics
reproduced exactly; selected state max absolute difference was 1.0430813e-7.
NIFTY 2026 ts/close columns were equal between the custom reader and pyarrow.

Scope limits: only NIFTY options plus two context indices are covered. Full-lake
validation, verified Greeks, alternate strikes/expiries, multi-instrument models,
adaptive learning and a fresh final test remain future work. Do not treat this
completed bounded experiment as completion of those wider project stages.

## Completed final fitting

`finish_training.py` completes duration selection with chronological 80/20
validation and early stopping, then resets and refits each architecture on all
available valid samples for its selected duration. This is a separately
registered stage, not an extension of the original six-epoch experiment.

```bash
python -m pytest research/neural_v2/test_finish_training.py -q
python -m research.neural_v2.finish_training --lake /absolute/path/to/lake
```

The three `final_training/*_final.pt` files retain both phase histories,
optimizer/RNG state, feature order, scaler, and code/data fingerprints. They
resume after interruption and completed reruns do not perform more training.
`finish_training.predict(checkpoints, sequences, feature_names)` provides
strict-schema, equal-weight offline ensemble inference; it sends no orders.
Use the recorded dependency versions in requirements-final-lock.txt to reproduce
this fitting run. Load only trusted PyTorch checkpoints.

Full-data models have no historical holdout left. Their prospective performance
is unknown. Earlier walk-forward reports and fold models remain unchanged.
