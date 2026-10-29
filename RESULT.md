# R130: the sweep → MSS → FVG video setup fails as stated, wick to wick, after the improvement search, and cannot reach 65% wins honestly (research only; nothing in the app changed)

1. As stated (V1, signal on the index, buying the ATM option, real costs): 218 design trades, 45% won, -Rs 307 a trade; holdout -Rs 750. All 6 versions lost.
2. Wick to wick, no candle opens or closes (Amendment 1): W1 485 trades, 44% won, -Rs 26 a trade; holdout -Rs 258. All 4 lost.
3. Improvement search (Amendment 2, 96 versions): the 15-min trend filter helped most (+Rs 216 a trade, 57% won, 201 trades) but did no better than random entries on the same day and side.
4. Profit lock: it raises the win rate and lowers the money (no lock +Rs 216 / 57%; +Rs 1,000 lock +Rs 29 / 62%; +Rs 500 lock -Rs 16 / 67%).
5. 65% search (Amendment 3, 32 versions: minimum target, wider stop, 60-min trend): no version reached 65% even on the past data; the best was 60% on 95 trades.
6. A minimum target raises money per trade but LOWERS the win rate (57% → 55% → 51% → 44% as targets grow).
7. Walk-forward (each year picked from earlier years only): 120 trades, 52.5% won, +Rs 45 a trade, 3 of 4 years positive. It fails the 65% rule and the 150-trade minimum.
8. The version picked on all design (60% won) went 13 trades, 38% won, -Rs 503 a trade on Apr-Oct 2026 (that period's 4th read, so labelled "already seen").
9. No paper arm is proposed. The one lead is the 15-minute trend direction on its own, to be tested as a new idea on data after 19 Oct.
10. Write-up: research/HUNT_R130_SWEEP_MSS_FVG.md. Pre-registrations: PREREG.md, PREREG_WICK.md, PREREG_ENH.md, PREREG_WR.md. Entries: research/experiments/entries/r130-*.yaml.
