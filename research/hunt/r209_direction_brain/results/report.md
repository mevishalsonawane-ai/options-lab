# R209 results: the direction brain

Pre-registered in `PREREG.md` (with Amendment A) before any number. Index direction 15 minutes ahead at every 5-minute point of five indices, inputs from the five indices, India VIX and the option chain. Walk-forward 2023 Q1 - 2026 Q1; holdout 1 Apr - 5 Oct 2026 read once (2026-11-20T17:53:38.297693+00:00 UTC).

**0 of 8 tests pass.** Chosen model (best walk-forward AUC): logistic regression.

## Walk-forward (284,000 points, 13 quarters)

| model / inputs | AUC (95% day-block interval) | right overall | most confident 20% right | quarters with top 20% >= 55% |
|---|---|---|---|---|
| logistic regression, inputs ALL | 0.5260 (0.5209 - 0.5311) | 51.8% | 54.5% | 6 of 13 |
| gradient-boosted trees, inputs ALL | 0.5244 (0.5190 - 0.5292) | 51.9% | 53.3% | 4 of 13 |
| neural network (64, 32), inputs ALL | 0.5144 (0.5108 - 0.5185) | 51.1% | 52.3% | 1 of 13 |
| gradient-boosted trees, inputs P | 0.5208 (0.5167 - 0.5257) | 51.7% | 53.0% | 2 of 13 |
| gradient-boosted trees, inputs PX | 0.5229 (0.5178 - 0.5276) | 51.8% | 53.5% | 4 of 13 |
| gradient-boosted trees, inputs PXV | 0.5164 (0.5117 - 0.5209) | 51.4% | 52.3% | 0 of 13 |

Inputs: P = own index, X = the other indices, V = India VIX, ALL = also the option chain.

## Holdout, read once

| model | points | AUC (95% interval) | p | right overall | most confident 20% right | verdict |
|---|---|---|---|---|---|---|
| logistic regression | 45,085 | 0.5107 (0.4980 - 0.5240) | 0.0495 | 50.6% | 51.2% | fail |
| gradient-boosted trees | 45,085 | 0.5133 (0.5015 - 0.5248) | 0.0130 | 51.0% | 51.4% | fail |
| neural network (64, 32) | 45,085 | 0.5193 (0.5101 - 0.5292) | 0.0005 | 51.4% | 53.0% | fail |

Calibration of the chosen model in the holdout (predicted P(up) -> share up, ten equal bins): 0.458 -> 0.472; 0.478 -> 0.482; 0.489 -> 0.496; 0.497 -> 0.493; 0.505 -> 0.493; 0.512 -> 0.500; 0.520 -> 0.497; 0.529 -> 0.504; 0.539 -> 0.502; 0.561 -> 0.508.

## T2: does it make the arms' own calls more reliable? (holdout, R206's signals)

| signals | favoured by the model: right (signals) | not favoured: right (signals) | difference | p | verdict |
|---|---|---|---|---|---|
| R72 | 51.4% (1,176) | 50.4% (1,299) | +1.0 points | 0.402 | fail |
| R51 | 50.7% (2,120) | 49.5% (2,975) | +1.2 points | 0.325 | fail |
| LIQ | 45.9% (181) | 55.6% (171) | -9.7 points | 0.950 | fail |

## T3: as a filter on today's arms (1 lot, real costs)

| arm | threshold (chosen on design) | window | trades removed | net with the filter | net today | verdict |
|---|---|---|---|---|---|---|
| R72 | 0.45 | design | 147 | Rs 222,516 | Rs 232,769 | - |
| R72 | 0.45 | holdout | 27 | Rs 97,587 | Rs 98,609 | fail |
| LIQ | 0.45 | design | 77 | Rs 226,492 | Rs 231,580 | - |
| LIQ | 0.45 | holdout | 16 | Rs 9,134 | Rs 11,087 | fail |

Design nets by threshold: R72: t 0.45 Rs 222,516, t 0.48 Rs 125,843, t 0.5 Rs 100,118, t 0.52 Rs 68,337 (today Rs 232,769); LIQ: t 0.45 Rs 226,492, t 0.48 Rs 135,627, t 0.5 Rs 93,964, t 0.52 Rs 34,181 (today Rs 231,580).

## Why each test fails

- **T1-LOG**: holdout AUC 0.5107 < 0.53; holdout top-20% accuracy 51.2% < 55%; walk-forward top-20% accuracy >= 55% in 6 of 13 quarters < 9; BH q 1 > 0.1 (q within these 8: 0.132).
- **T1-GBM**: holdout AUC 0.5133 < 0.53; holdout top-20% accuracy 51.4% < 55%; walk-forward top-20% accuracy >= 55% in 4 of 13 quarters < 9; BH q 1 > 0.1 (q within these 8: 0.0466).
- **T1-NET**: holdout AUC 0.5193 < 0.53; holdout top-20% accuracy 53.0% < 55%; walk-forward top-20% accuracy >= 55% in 1 of 13 quarters < 9; BH q 0.195 > 0.1 (q within these 8: 0.000317).
- **T2-R72**: difference +1.0 points with p 0.4017; favoured signals +0.5 points above the source's own (needs 3); BH q 1 > 0.1 (q within these 8: 0.53).
- **T2-R51**: difference +1.2 points with p 0.3245; favoured signals +0.7 points above the source's own (needs 3); BH q 1 > 0.1 (q within these 8: 0.53).
- **T2-LIQ**: difference -9.7 points with p 0.9502; favoured signals -4.7 points above the source's own (needs 3); BH q 1 > 0.1 (q within these 8: 0.951).
- **T3-R72**: design: net 222,516 not better than B's 232,769; design: max drawdown 110,766 above B's 100,582; holdout: net 97,587 not better than B's 98,609; holdout: 27 trades changed / removed < 40; walk-forward: better in 1 of 4 design quarters < 3; holdout twin p 0.3673 >= 0.05; BH q 1 > 0.1 (q within these 8: 0.53).
- **T3-LIQ**: design: net 226,492 not better than B's 231,580; holdout: net 9,134 not better than B's 11,087; holdout: 16 trades changed / removed < 40; walk-forward: better in 2 of 4 design quarters < 3; holdout twin p 0.6187 >= 0.05; BH q 1 > 0.1 (q within these 8: 0.626).
