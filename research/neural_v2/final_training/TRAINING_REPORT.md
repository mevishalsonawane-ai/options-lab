# Final NIFTY research training completed

All three architectures were refitted on all 13,460 valid samples across 246 sessions.
The data window is 20 October 2025 through 19 October 2026. Each sequence contains 30 minutes and 25 features.

| Architecture | Duration-selection epochs | Selected all-data epochs | Best reused validation loss | Stop reason |
|---|---:|---:|---:|---|
| mlp | 26 | 20 | 1.110961 | early_stopping |
| lstm | 12 | 6 | 1.131585 | early_stopping |
| gru | 21 | 15 | 1.131594 | early_stopping |

Equal-weight ensemble inference passed the finite-output and exact feature-schema checks.
Each checkpoint includes optimizer/RNG state, scaler, configuration, code/data fingerprints and both training histories.

This is completion of NIFTY research fitting, not completion of the entire multi-market trading project.
The monitored validation period has already been seen in historical research. The final refits consume all available samples and have no remaining historical holdout.
Do not backtest these final refits on their training data and call it out-of-sample performance. Earlier walk-forward checkpoints and reports are preserved separately.
No profitable edge, paper-trading approval or live-trading approval is established. No real orders were placed.

Full-market expansion, verified Greeks, account risk integration and fresh prospective profitability testing remain separate stages.
