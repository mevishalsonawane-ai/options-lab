# Payoff20: continue the search with a cost-aware learning target

Read results/REPORT.md after execution. This is a new fixed-horizon experiment,
not a claim that R163's already-failed rich-input picker was never attempted.
It changes the payoff definition and signal clock; it does not prove novelty,
a dependable65% win rate, or an exhaustive search of all mathematical strategies.

```bash
python -m pip install -r research/neural_v2/requirements-final-lock.txt
python -m pytest research/payoff20 research/neural_v2/test_strategy.py -q
python -m research.payoff20.study --lake /absolute/path/to/lake
python -m research.payoff20.report
```

Runtime versions are captured in environment.json. Inputs are the2025/2026
NIFTY/INDIAVIX minute indices and NIFTY weekly option files used in earlier
research. Hashes and feature names appear in data_audit.json. The training
matrix is reconstructed from those inputs and is not committed as raw data.

Six fitted systems: linear classifier+ridge, histogram classifier+regressor,
and two-head MLP, each across two chronological outer folds. Four sklearn
joblib bundles and two PyTorch checkpoints are saved; separate JSON files
hold scalers, calibration and code/data signatures. Load trusted own artifacts
only. MLP checkpoints save optimizer/RNG, best weights and epoch history and
resume matching data/code. Re-running refits deterministic sklearn models.
No paid or persistent background compute was configured.

Neural input is30features plus30missing indicators, standardized on fit rows.
Median imputation is also fit only on earlier fit rows. To use a checkpoint:
reconstruct the declared feature order, impute with its saved medians, append
missing flags, standardize with saved mean/std and clip to[-10,10]. Load
models.Network and checkpoint best_model. Head0 is the win logit, head1 net/1000.
Apply the saved positive-temperature/intercept calibration to head0; multiply
head1 by1000 for estimated rupees. This is research inference, not a live order.

forecasts.npz uses the existing pickle-free save_forecasts/load_forecasts
helpers. It includes all outer action forecasts, actual outcomes for scoring,
provenance cutoffs and baseline momentum. Actual outcomes are NEVER read by
the trading-gate function. Both sides are forecast before choosing an action;
after entry the same strike and scheduled expiry are held.

30fixed policies (27model threshold cells plus3baselines), each independently
replayed with normal/double execution friction. Full account carries across
outer windows; diagnostic calendar-window restarts are separate. Every60policy
ledger is saved, including empty CSVs. No trade is added to manufacture a
minimum sample. Candidate calibration differs from account-executed win rates.

The standalone label and execution helper share stop/time semantics. Label
outcomes can differ from actual account exits if existing drawdown/month/day
risk gates close earlier. Shared engine profit-lock updates are explicitly
ignored by this study's horizon exit; no target/lock is claimed. It is a
20-minute time exit plus15% initial premium stop, with gap/missing-price rules.

A partial_results.json snapshot and progress/events/dashboard are updated as
execution proceeds. Checkpoints resume neural epochs; rebuilding features or
restarting account replay still requires a new process. No ongoing autonomous
service or unreached experiment stage is implied by a saved progress file.
The final snapshot duplicates results and is excluded from git.

Hindsight diagnostics are future-looking, non-tradable, restricted to observed
pairs, and not a universal ceiling for selective strategies. New forward data
and executable quote/depth evidence remain necessary. No real trades, app or
workflow changes, paid resources or merge to main. Local prereg45a9997 preceded
outcomes; see PREREG.md for all fixed choices and limits.
