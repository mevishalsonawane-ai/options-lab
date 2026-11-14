# Underlying-direction development results

Predictions are calibrated on prior data and evaluated on later sessions. Historical periods were previously seen; this is not fresh proof.

| Instrument | Model | Minutes | N | Accuracy | Majority | Balanced | AUC | Confident coverage | Confident accuracy | BH q |
|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| BANKNIFTY | gru | 5 | 6813 | 50.07% | 49.48% | 49.81% | 0.500 | 7.7% | 47.70% | 0.467 |
| BANKNIFTY | gru | 15 | 6819 | 50.70% | 50.14% | 49.97% | 0.491 | 0.0% | n/a | 0.515 |
| BANKNIFTY | gru | 30 | 6817 | 51.40% | 53.28% | 50.13% | 0.493 | 0.0% | n/a | 0.922 |
| NIFTY | gru | 5 | 6799 | 50.73% | 50.01% | 50.41% | 0.508 | 5.7% | 53.89% | 0.467 |
| NIFTY | gru | 15 | 6808 | 51.06% | 49.81% | 50.48% | 0.505 | 0.0% | n/a | 0.467 |
| NIFTY | gru | 30 | 6817 | 51.08% | 52.82% | 49.99% | 0.503 | 0.0% | n/a | 0.922 |
| BANKNIFTY | logistic | 5 | 6813 | 50.48% | 49.48% | 50.22% | 0.508 | 4.2% | 53.29% | 0.467 |
| BANKNIFTY | logistic | 15 | 6819 | 50.73% | 50.14% | 50.00% | 0.494 | 0.0% | n/a | 0.515 |
| BANKNIFTY | logistic | 30 | 6817 | 51.53% | 53.28% | 50.21% | 0.497 | 0.0% | n/a | 0.922 |
| NIFTY | logistic | 5 | 6799 | 51.26% | 50.01% | 50.94% | 0.512 | 4.7% | 52.20% | 0.467 |
| NIFTY | logistic | 15 | 6808 | 51.06% | 49.81% | 50.48% | 0.505 | 0.0% | n/a | 0.467 |
| NIFTY | logistic | 30 | 6817 | 50.99% | 52.82% | 49.85% | 0.498 | 0.0% | n/a | 0.922 |
| BANKNIFTY | mlp | 5 | 6813 | 50.05% | 49.48% | 49.82% | 0.503 | 11.5% | 50.96% | 0.467 |
| BANKNIFTY | mlp | 15 | 6819 | 50.89% | 50.14% | 50.31% | 0.503 | 0.2% | 14.29% | 0.515 |
| BANKNIFTY | mlp | 30 | 6817 | 51.31% | 53.28% | 50.04% | 0.503 | 0.0% | 0.00% | 0.922 |
| NIFTY | mlp | 5 | 6799 | 50.63% | 50.01% | 50.46% | 0.508 | 7.3% | 51.93% | 0.467 |
| NIFTY | mlp | 15 | 6808 | 50.81% | 49.81% | 50.28% | 0.503 | 0.2% | 9.09% | 0.513 |
| NIFTY | mlp | 30 | 6817 | 50.83% | 52.82% | 49.70% | 0.504 | 0.0% | n/a | 0.922 |

Accuracy predicts the sign of an underlying return; it is not an option-trade win rate. Exact ties/missing outcomes are masked only for scoring and loss; all valid feature rows still receive forecasts.
Features exclude option prices, OI/IV and unverifiable volume/VWAP. Magnitude and direction are separate tasks. No all-data model enters evaluation.
The 55% gate is fixed in advance. Calibration is monotone and fitted on its own preceding window. High-confidence subsets may be tiny; lower coverage is not a guaranteed edge.
Full calibration bins, Brier/log loss, clustered tests and cutoffs are in results.json. BH covers 18 model/instrument/horizon tests, not the entire historically reused research program. No trade/paper/live approval.
