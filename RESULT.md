# R146 result (1 Nov 2026): the Singapore market and the Indian index day
1. Correction: Singapore does not close before India opens. It trades 06:30-14:30 IST, so only its overnight gap and its 06:30-08:30 IST move are known before 09:15.
2. "SGX Nifty" moved to GIFT City in 2023 (GIFT Nifty). H27 already tested it with US and Asian cues: they set the gap, but 0 of 234 option variants passed. R146 tested Singapore's own index (STI), which H27 left out.
3. Singapore's overnight gap lines up with India's 09:15 opening gap: correlation 0.61 NIFTY, 0.57 BANKNIFTY (2,583 days, 2015 - Sep 2025); 0.50 / 0.49 in the holdout (Oct 2025 - Oct 2026, read once).
4. It does NOT predict the first hour or the rest of the Indian day: design correlations -0.06 to +0.11, none significant (all q >= 0.16), so no trade test was run (as pre-registered).
5. One holdout-only link (BANKNIFTY's first hour, US removed, r 0.16) was zero in design and faded within the holdout: noise, not an edge.
6. Meaning: by 09:15 India's price already includes Singapore, Asia, the US and GIFT Nifty. Buying options on Singapore's direction would not make money. GIFT Nifty is the better guide to the open.
7. Data: STI and S&P 500 from Yahoo (STI daily 2015+, hourly Dec 2023+), NIFTY / BANKNIFTY from the Drive lake. Raw files not in git.
8. Write-up with every test: research/HUNT_R146_SINGAPORE.md. Entry: research/experiments/entries/r146-singapore.yaml.
9. Nothing in the app changes.
10. Earlier today: R139-R145 (market structure, the arms, Liquidity), see research/HUNT_R139 to HUNT_R145.
