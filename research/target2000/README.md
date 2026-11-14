# Whole-lot Rs2000 neural research

This study targets Rs2000 NET on profitable first-passage exits; losing/time/gap
exits remain in performance. It does not promise Rs2000 per trade or scale prior
small-profit results into larger positions. One complete historical lot per trade.

## Reproduce

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 python -m pytest research/target2000 -q
OMP_NUM_THREADS=2 OPENBLAS_NUM_THREADS=2 MKL_NUM_THREADS=2 python -m research.target2000.study --lake /absolute/path/to/lake
```

Raw lake data is excluded. Six input Parquet hashes,feature order and sequence
counts are in results/data_audit.json. Read PREREG.md before interpreting results.
The existing requirements lock and research/retest/environment.json describe the
same runtime. CPU training uses two threads, no paid resources or GPU required.

## Models and training recovery

results/models.zip holds six neural checkpoints (two folds: dense seed42 plus
hybrid seeds42/73), two logistic/ridge bundles and six scaler/calibration JSONs.
Extract it into results/models/ to resume. Checkpoints include epoch,selected best
weights,optimizer,RNG,architecture,history and fingerprint. A mismatched data/code/
split fingerprint aborts resume. The same max25epoch cap and patience5 still apply;
resuming a completed checkpoint does not train further.

For inference: construct the six consecutive SAME-session/SAME-side feature rows
in the audit feature order. Impute each feature using training median; append
missing indicators; standardize with saved mean/std;clip to[-10,10]. Dense and
logistic/ridge use latest snapshot; hybrid uses all six. Load neural best_model,
not last model; eval() disables dropout. Hybrid averages seed42/73 raw outputs.
Each probability uses sigmoid(raw_logit / exp(log_temperature) + intercept).
Expected net is the third raw output times2000 (logistic bundle ridge likewise).
Only each fold's earlier weights may be used within that fold's evaluation dates.
Forecasts.npz saves all actual outer predictions without pickle.

Daily cash/risk controls and every trade are in results/ledgers.zip. Results JSON
contains MonteCarlo,confidence,calibration and account metrics. Threshold grid is
fixed before outcomes; no best outer model is approved by this process.

Progress/checkpoint dashboard is results/dashboard.html. The process must run in
an active compute environment. No persistent service is configured; reruns rebuild
data, resume matching neural checkpoints, and replay accounts. Data preparation
and account replay restart from the beginning. Completion/failures are logged.

`research.target2000.inference.predict(raw,kind,fold,model_directory)` applies the
saved imputer/scaler, selected weights, seed averaging and calibration to an
N×6×30array in the audit feature order. Extract models.zip before use. The
function performs offline inference only; it does not enforce timestamps or
account gates. The study decisions/replay pipeline enforces those separately.
Only load these trusted project checkpoints; PyTorch/joblib files aren't a
safe format for arbitrary untrusted code sources.

## User-requested consecutive-bar extension

```bash
python -m research.target2000.bars --lake /absolute/path/to/lake
```

Read BAR_CONFIRMATION_PREREG.md and bar_results/REPORT.md. This separately
registered extension tests2/3completed1m/5m candles, alone or as a remove-only
filter on frozen model directions. It adds32endpoints, adjusts significance both
within the extension and jointly over all94endpoints, and retains whole lots.

Optional full inference reproducibility audit (rebuilds inputs):

```bash
python -m research.target2000.verify_inference --lake /absolute/path/to/lake
```
