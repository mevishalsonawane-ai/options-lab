# R130: the sweep → MSS → FVG video setup fails as stated, wick to wick, and after a 96-version improvement search (research only; nothing in the app changed)

1. As stated (V1, 5-min, on the index, buying the ATM option, real costs): 218 design trades, 45% won, -Rs 307 a trade; holdout 36 trades, -Rs 750 a trade. All 6 versions lost.
2. Wick to wick, with no candle opens or closes (Amendment 1): W1 485 trades, 44% won, -Rs 26 a trade; holdout -Rs 258. All 4 versions lost.
3. Improvement search (Amendment 2, 96 versions, after results): major liquidity, morning only, 15-min trend, stop ≥ 0.10%, 1-ITM, and a profit lock. None passed the design gates; BH q 0.95 for all.
4. Profit lock: it raises the win rate and LOWERS the money. Best version: no lock +Rs 216 a trade (57% won); +Rs 1,000 lock +Rs 29 (62%); +Rs 500 lock -Rs 16 (67%, clears 65% and still loses).
5. The 15-minute trend filter helped most: trend + stop ≥ 0.10% + 1-ITM made +Rs 216 a trade on 201 design trades (3 of 4 years positive).
6. But it did no better than random entries on the same day and side (p 0.36): the gain is trading with the 15-min trend, not the sweep/FVG pattern.
7. Checked once on Apr-Oct 2026 (already seen twice before): 25 trades, 56% won, +Rs 182 (ATM, all from MIDCPNIFTY's 6 trades); 1-ITM 24 trades, -Rs 21. Both fail 65% and n ≥ 50.
8. "A sweep makes the break stronger" is false with closes (50.7% vs 52.9%) and with wicks (54.3% vs 55.2%, n 10,773).
9. No paper arm is proposed. The one lead, the 15-minute trend direction on its own, would be a new pre-registered idea, tested on data after 19 Oct once the lake is topped up.
10. Write-up: research/HUNT_R130_SWEEP_MSS_FVG.md. Pre-registrations: PREREG.md (66d2467), PREREG_WICK.md (fad9c7c), PREREG_ENH.md (1564595). Entries: research/experiments/entries/r130-*.yaml.
