# MCX Data Brain (MB1): RESULT for Boss (20 Oct 2026)

1. Done: all 11 MCX commodities are stored as Parquet (zstd) and float32 tensors (memory-mapped arrays, Arrow, one
   joint tensor of 11 commodities x 270,160 minutes x 56 features, ready-made 120-minute windows), 2.0 GB on Drive
   `IraAlgo-data-lake/derived/mcx_brain/v1/`. Only code and small summaries are in git.
2. The 5-level brain is built: specialists, a sequence encoder (a 16-number "market state"), a committee that
   calibrates and abstains, a strategy search, and plain-English cards for Jarvis (`research/brain/mcx/results/CARDS.md`).
3. What it can do: tell how BIG the next hour will be on gold, gold mini, silver, silver mini and crude, 8-13% better
   than the option market's own IV or the usual hour, on the locked Jul-Oct 2026 data it had never seen (3,917
   forecasts each). Natural gas did not hold up (3.5%).
4. What it cannot do: say UP or DOWN (AUC 0.48-0.54, a coin flip), as the India brain found.
5. No strategy built on it pays after real MCX costs: 0 of 16. The closest, "big hour expected + follow the last 30
   minutes" on silver mini and natural gas mini, made money but only thanks to its 5 best trades. Nothing goes to paper.
6. Useful facts: US CPI / jobs-report hours are 1.2-1.7x a normal hour on gold and silver, gas-storage hours 1.2x on
   natural gas; MCX's busiest hour is the US open (19:00-20:00 IST).
7. Not modelled yet: crude mini, gas mini, copper, zinc, aluminium (only 20-37 days of minutes, all recent). MCX option
   books for four of them are still being fetched by another session.
8. Nothing in the app changed. Full write-up: `research/BRAIN_MCX.md`; rules: `research/HUNT_MB1_MCX_BRAIN.md`.
