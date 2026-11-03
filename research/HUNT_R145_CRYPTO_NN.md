# R145: a neural network over the top-100 crypto coins. What patterns are real?

Research only; nothing in the app changes. Pre-registration: `research/brain/crypto/PREREG_R145_NN.md`.

## Progress note (kept up to date so a restarted session can resume)

- 3 Nov 2026: pre-registration written and committed before any data was built or any model fitted.
- 3 Nov 2026: code in `research/brain/crypto/r145/` (data, panel, guard, models, run, stats, patterns, trade, evaluate)
  and 25 tests in `research/brain/tests/test_r145_nn.py`. Design bars (96 perps, Dec 2023 - Mar 2026) being built
  into `<cache>/crypto/r145/bars5/` (5-minute bars + 1-minute lead-lag stats; raw minutes never kept).
  Resume: `python -m research.brain.crypto.r145.data bars --manifest <csv> --from 2023-12 --to 2026-03` (skips done
  months), then `... data funding ...`, `... run build --manifest <csv>`, `... run fit`, `... evaluate design`.
  Checkpoints: `gdrive:IraAlgo-data-crypto/derived/r145/`.
- 3 Nov 2026, 12:00 UTC: design panel built (1,483,142 hourly rows, 95 coins). Look-ahead guard on real data: PASS
  (8 coins, 7 cuts x 2 modes, 51 features). Walk-forward runs in chunks (a background job lasts at most 2 hours);
  `run fit --folds ... --models ...` skips any model/fold whose predictions already exist. Done by 12:00: baselines
  folds 0-3, networks fold 0 and N1/TW fold 1.
- 3 Nov 2026, 18:30 UTC: design evaluation done (`results/r145_design.json`: 254 tests, 185 "real" after BH and the
  gates). Trading test done (`results/r145_trade_design.json`): all 40 variants lose (-Rs 122 to -Rs 176 a trade
  at Rs 1 lakh); none passes, so no trading rule goes to the holdout. Next: the one holdout read of the forecasts
  (Amendment 1): `data bars --from 2026-04 --to 2026-10 --allow-holdout`, `run holdout-build`, `run holdout-fit`,
  `evaluate holdout`.
