# Verification, 14 November 2026

- New pipeline: 13 tests passed, including a real epoch-three interruption/resume with exact prediction equality, fixed-strike paths, missing quotes, duplicate conflicts, chronological feature causality, dated charges, network backward passes and trade-overlap control.
- Existing research brain suite: 209 passed, 1 existing skip, 51 warnings; 355.68 seconds. No tests disabled or changed.
- Existing feature catalog: FEATURES.md up to date.
- Baseline: reported test metrics and train/validation/test counts reproduced exactly; selected weight maximum absolute difference 1.043081283569336e-7; scaler equal. NIFTY 2026 timestamp/close columns equal between readers.
- Corrected real-data experiment: 18 checkpoints, six epochs each, three expanding folds, 25 features, 13,460 samples; 6,753 evaluated samples across 123 sessions. Every checkpoint reached epoch six, with inner-selected best state retained.
- Initial and corrected passes retained in experiment log; BH covers 24 reported entries. No fresh final test was available or claimed.
- No raw lake files, account data, private source links, authentication material, production changes, workflow changes or orders are included.
- Shell runner syntax checked. Full diff reviewed for leakage, fixed-contract handling, costs, split boundaries, checkpoint resume and data coverage restrictions.

The full Android build was not run locally: this branch changes only independent offline research and documentation. Remote CI status must be reported separately; local test success is not a claim that remote checks finished.
