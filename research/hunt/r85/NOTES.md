# R85 notes: deviations from PREREG.md, in the order they were made

## D1 (19 Oct 2026, after the first Part 1 run, before any Part 2 rule was chosen or run): colour-matched baseline

- **What PREREG section 3 said.** Baseline = every coloured candle of the same instrument, timeframe and session
  bucket, each signed by its own colour.
- **What the first run showed.** 59 of 7,564 cells passed BH q < 0.10 at h = 3. Most were red candles "reversing",
  mostly on gold and silver. Gold and silver rose strongly over the design period (Aug 2025 - Mar 2026). Under a
  drift, every red candle's next move is up on average and every green candle's is up too, so against a pooled
  baseline red looks like "reversal" and green like "continuation" whatever the shape.
- **The change.** Each cell is also compared with every candle of the SAME colour, instrument, timeframe and bucket
  (`effect_cm`, `p_cm`, BH `q_cm` in the same families). Part 2 may use only cells that pass BOTH the pre-registered
  test (q < 0.10) and the colour-matched test (q_cm < 0.10). This is stricter than PREREG, so it can only remove
  findings. The plain colour cells ("any green / any red candle") have no colour-matched test (zero by construction);
  they are reported as the drift itself.
- A test (`test_colour_matched_effect_removes_market_drift`) plants a drift and checks that the pooled test is fooled
  and the colour-matched one is not.
