# R123 result (28 Oct 2026): a neural network of all the index + option data. 70% wins and Rs 1,000 a trade? No.
1. Built two networks over 5 indices' minutes, VIX, breadth, calendar and the option chain: a single one and a multi-level one (encoders, specialists, committee, abstain).
2. "70% rule" (+Rs 1,000 / -Rs 2,000 / 60 min, enter when the net says >= 70%): won 63% in design (635 trades) but lost Rs 82 a trade; 7 holdout trades, lost.
3. Why: when the network says 70%+, it is right only about 58% of the time; and a capped Rs 1,000 win against a Rs 2,000 loss needs 66% to break even.
4. Best honest trade-off: morning ATM put pick (S5), holdout 41 trades, 61% wins, +Rs 579 a trade (+Rs 173 a session), p 0.002 vs random twins, q 0.020.
5. It still fails: fewer than the 100 trades the gate needs, and its design profit leaned on 2024. So nothing passes every gate; nothing goes on paper.
6. The multi-level network forecast the next hour's range best (R² 0.405 vs trees 0.386) but never traded in the holdout.
7. Direction is still a coin for both networks (4th time: R16, B1, R41, R123).
8. A data bug (uncleaned junk option prints) was found and fixed BEFORE the one holdout read; the first run is kept and disclosed.
9. Details: research/HUNT_R123_NEURAL_70.md; code + 38 tests: research/hunt/r123_neural_70/; big files on Drive (derived/r123).
10. Research only: no app code changed; holdout read once (09:06-09:30 UTC, not resumed).
