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
