# Final-fitting verification

14 November 2026. Local CPU execution completed.

- Neural pipeline and final-fitting tests: 20 passed. Includes exact interruption/recovery in both duration selection and all-data refit, completed-checkpoint resume, early stopping, train-only selection scaling, exact all-data scaling, schema and mixed-dataset rejection, and finite ensemble outputs.
- Existing research brain suite: 209 passed, 1 existing skip, 51 warnings; 136.62 seconds. No tests disabled or changed.
- Existing feature catalog check: up to date.
- All three final checkpoints reached completed phase; row count 13,460; refit history length matches selected epoch; selected validation loss equals recorded history minimum; training dates precede selection-validation dates; saved SHA256 hashes match the manifest.
- Training-code fingerprints verified against current source. Offline ensemble inference verified after loading checkpoints from disk.
- Prior walk-forward models and results preserved. No final-model historical backtest is presented as unseen performance.
- No production logic, workflow, real orders, private source URLs, account data or credentials were changed or published.

GitHub CI is separate. The existing data-harvest workflow previously failed because its external Kaggle download returned HTTP 404. Local training uses the verified data lake files and did not depend on that download.
