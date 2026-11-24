# R210 results: hybrid trail, Chandelier exit and higher-timeframe filter on R72, R51 and Liquidity 15+5

Design 2024-11-01 - 2026-03-31 (4 walk-forward folds), 1 lot, dated Zerodha charges and R17 slippage. Baseline = each arm's current exits (V5). BH over m = 4,826.

## R72 Breadth Donchian

Reproduction of R193's V5 on the design years: 1,595 trades / Rs 237,089 (R193: 1,595 / Rs 237,089; reproduced).

| rule | trades | win | net | worst month | max DD |
|---|---:|---:|---:|---|---:|
| V5 (current exits) | 1,595 | 65.39% | Rs 237,089 | -Rs 26,420 (2025-02) | Rs 100,582 |
| R72-HU: hybrid trail on the index (breakeven at +1R, ATR trail at +2R, structure trail at +3R) | 1,529 | 56.05% | Rs 485,494 | -Rs 16,512 (2025-03) | Rs 101,377 |
| R72-HUNT: hybrid trail on the index, without the arm's time stop | 1,344 | 53.05% | Rs 313,106 | -Rs 78,935 (2025-02) | Rs 189,728 |
| R72-HP: hybrid trail on the option premium (option-chart rule, labelled) | 1,529 | 56.05% | Rs 488,552 | -Rs 16,512 (2025-03) | Rs 102,093 |
| R72-CH: current exits + Chandelier exit on the index (3 x ATR(14), 5-min) | 1,595 | 65.39% | Rs 233,919 | -Rs 24,827 (2025-02) | Rs 96,805 |
| R72-F15: current exits + 15-minute trend filter (close vs EMA(20)) | 1,151 | 64.9% | Rs 162,555 | -Rs 13,493 (2024-11) | Rs 73,344 |
| R72-F60: current exits + 60-minute trend filter (close vs EMA(20)) | 953 | 65.16% | Rs 155,008 | -Rs 15,520 (2025-01) | Rs 62,334 |

| test | folds beaten | sign-flip p | BH q | within-study q | design gate | failed items |
|---|---|---:|---:|---:|---|---|
| R72-HU | F1, F2, F3, F4 | 0.0042 | 1 | 0.045 | fail | BH q 1 > 0.1 |
| R72-HUNT | F1, F4 | 0.3340 | 1 | 0.751 | fail | walk-forward: beat V5 in 2 of 4 folds (< 3); design: worst month -78,935 worse than V5's -26,420; BH q 1 > 0.1 |
| R72-HP | F1, F2, F3, F4 | 0.0036 | 1 | 0.045 | fail | BH q 1 > 0.1 |
| R72-CH | F1, F2 | 0.6651 | 1 | 0.962 | fail | design: net 233,919 not above V5's 237,089; walk-forward: beat V5 in 2 of 4 folds (< 3); BH q 1 > 0.1 |
| R72-F15 | F1 | 0.9083 | 1 | 0.962 | fail | design: net 162,555 not above V5's 237,089; walk-forward: beat V5 in 1 of 4 folds (< 3); BH q 1 > 0.1 |
| R72-F60 | none | 0.9028 | 1 | 0.962 | fail | design: net 155,008 not above V5's 237,089; walk-forward: beat V5 in 0 of 4 folds (< 3); BH q 1 > 0.1 |

## R51 Donchian 20 + Supertrend

Reproduction of R193's V5 on the design years: 2,240 trades / Rs 124,786 (R193: 2,240 / Rs 124,786; reproduced).

| rule | trades | win | net | worst month | max DD |
|---|---:|---:|---:|---|---:|
| V5 (current exits) | 2,240 | 61.88% | Rs 124,786 | -Rs 36,762 (2025-03) | Rs 85,937 |
| R51-HU: hybrid trail on the index (breakeven at +1R, ATR trail at +2R, structure trail at +3R) | 2,103 | 51.21% | Rs 316,058 | -Rs 38,007 (2025-06) | Rs 71,613 |
| R51-HUNT: hybrid trail on the index, without the arm's time stop | 1,839 | 49.54% | Rs 209,859 | -Rs 54,438 (2025-12) | Rs 149,888 |
| R51-HP: hybrid trail on the option premium (option-chart rule, labelled) | 2,103 | 51.21% | Rs 318,754 | -Rs 38,007 (2025-06) | Rs 71,612 |
| R51-CH: current exits + Chandelier exit on the index (3 x ATR(14), 5-min) | 2,240 | 61.74% | Rs 112,509 | -Rs 33,326 (2025-03) | Rs 89,735 |
| R51-F15: current exits + 15-minute trend filter (close vs EMA(20)) | 1,604 | 61.35% | Rs 62,376 | -Rs 35,839 (2025-03) | Rs 69,560 |
| R51-F60: current exits + 60-minute trend filter (close vs EMA(20)) | 1,262 | 61.33% | Rs 42,747 | -Rs 21,592 (2025-03) | Rs 68,703 |

| test | folds beaten | sign-flip p | BH q | within-study q | design gate | failed items |
|---|---|---:|---:|---:|---|---|
| R51-HU | F1, F2, F3, F4 | 0.0338 | 1 | 0.147 | fail | design: worst month -38,007 worse than V5's -36,762; BH q 1 > 0.1 |
| R51-HUNT | F1, F2, F3 | 0.3272 | 1 | 0.751 | fail | design: worst month -54,438 worse than V5's -36,762; BH q 1 > 0.1 |
| R51-HP | F1, F2, F3, F4 | 0.0302 | 1 | 0.147 | fail | design: worst month -38,007 worse than V5's -36,762; BH q 1 > 0.1 |
| R51-CH | F1, F4 | 0.8709 | 1 | 0.962 | fail | design: net 112,509 not above V5's 124,786; walk-forward: beat V5 in 2 of 4 folds (< 3); BH q 1 > 0.1 |
| R51-F15 | F1 | 0.8513 | 1 | 0.962 | fail | design: net 62,376 not above V5's 124,786; walk-forward: beat V5 in 1 of 4 folds (< 3); BH q 1 > 0.1 |
| R51-F60 | F3 | 0.8940 | 1 | 0.962 | fail | design: net 42,747 not above V5's 124,786; walk-forward: beat V5 in 1 of 4 folds (< 3); BH q 1 > 0.1 |

## Liquidity 15+5

Reproduction of R193's V5 on the design years: 908 trades / Rs 218,210 (R193: 908 / Rs 218,210; reproduced).

| rule | trades | win | net | worst month | max DD |
|---|---:|---:|---:|---|---:|
| V5 (current exits) | 908 | 50.0% | Rs 218,210 | -Rs 10,129 (2024-11) | Rs 25,771 |
| LIQ-HU: hybrid trail on the index (breakeven at +1R, ATR trail at +2R, structure trail at +3R) | 908 | 44.49% | Rs 242,146 | -Rs 15,881 (2025-09) | Rs 28,137 |
| LIQ-HUNT: hybrid trail on the index, without the arm's time stop | 908 | 43.28% | Rs 296,887 | -Rs 22,187 (2025-12) | Rs 36,253 |
| LIQ-HP: hybrid trail on the option premium (option-chart rule, labelled) | 908 | 44.49% | Rs 227,589 | -Rs 16,223 (2025-09) | Rs 28,137 |
| LIQ-CH: current exits + Chandelier exit on the index (3 x ATR(14), 5-min) | 908 | 50.33% | Rs 217,058 | -Rs 12,618 (2025-03) | Rs 25,771 |
| LIQ-F15: current exits + 15-minute trend filter (close vs EMA(20)) | 833 | 49.46% | Rs 168,000 | -Rs 16,831 (2025-03) | Rs 30,868 |
| LIQ-F60: current exits + 60-minute trend filter (close vs EMA(20)) | 657 | 50.08% | Rs 178,787 | -Rs 12,055 (2025-12) | Rs 21,360 |

| test | folds beaten | sign-flip p | BH q | within-study q | design gate | failed items |
|---|---|---:|---:|---:|---|---|
| LIQ-HU | F2, F4 | 0.2620 | 1 | 0.751 | fail | walk-forward: beat V5 in 2 of 4 folds (< 3); design: worst month -15,881 worse than V5's -10,129; BH q 1 > 0.1 |
| LIQ-HUNT | F1, F2, F3, F4 | 0.1311 | 1 | 0.457 | fail | design: worst month -22,187 worse than V5's -10,129; BH q 1 > 0.1 |
| LIQ-HP | F2, F4 | 0.4037 | 1 | 0.804 | fail | walk-forward: beat V5 in 2 of 4 folds (< 3); design: worst month -16,223 worse than V5's -10,129; BH q 1 > 0.1 |
| LIQ-CH | F2, F3 | 0.5236 | 1 | 0.946 | fail | design: net 217,058 not above V5's 218,210; walk-forward: beat V5 in 2 of 4 folds (< 3); design: worst month -12,618 worse than V5's -10,129; BH q 1 > 0.1 |
| LIQ-F15 | none | 0.9858 | 1 | 0.983 | fail | design: net 168,000 not above V5's 218,210; walk-forward: beat V5 in 0 of 4 folds (< 3); design: worst month -16,831 worse than V5's -10,129; BH q 1 > 0.1 |
| LIQ-F60 | F3 | 0.8974 | 1 | 0.962 | fail | design: net 178,787 not above V5's 218,210; walk-forward: beat V5 in 1 of 4 folds (< 3); design: worst month -12,055 worse than V5's -10,129; BH q 1 > 0.1 |

## Holdout (1 Apr - 13 Nov 2026), read once for the design survivors only

Not read: no variant passed the design gate.
