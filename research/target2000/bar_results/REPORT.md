# Two/three-bar whole-lot confirmation extension

User-requested extension, registered after neural results. All history reused; no independent final holdout.
Bullish: every close above open and successive closes rising. Bearish: inverse. Only completed1minute or5minute candles;2or3consecutive candles, no missing components/session carry.
One WHOLE lot per trade; premium is quoted per unit. Cash debit and profit multiply the per-unit premium by the date-appropriate contract quantity. Same Rs2000normal-net target,Rs1500planned stop,30minute horizon and account controls.
Neural directions are chosen before confirmation; confirmation only removes candidates. These use the preregistered ungated diagnostic directions because original confidence-gated neural models found no eligible trades. Standalone bar policies use no model.

| Policy | Trades | Win % | Rs2000 reaches | Average winner Rs | Average ALL trades Rs | Net Rs | Stress net Rs | Joint BH q |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| bars_only_1m_2bars | 127 | 33.07% | 12 | 988.67 | -282.38 | -35862.16 | -36227.60 | 1.000 |
| linear_1m_2bars | 196 | 38.78% | 19 | 984.01 | -89.98 | -17636.71 | -37769.49 | 1.000 |
| dense_1m_2bars | 104 | 29.81% | 10 | 1125.35 | -361.53 | -37599.33 | -37979.20 | 1.000 |
| hybrid_1m_2bars | 195 | 42.05% | 18 | 943.42 | -74.60 | -14546.14 | -35509.29 | 1.000 |
| bars_only_1m_3bars | 190 | 42.11% | 20 | 924.77 | -100.40 | -19075.69 | -36503.08 | 1.000 |
| linear_1m_3bars | 168 | 43.45% | 17 | 949.11 | -81.33 | -13663.57 | -35854.28 | 1.000 |
| dense_1m_3bars | 179 | 45.25% | 21 | 974.04 | -58.72 | -10511.29 | -32454.74 | 1.000 |
| hybrid_1m_3bars | 191 | 44.50% | 23 | 992.55 | -40.26 | -7689.58 | -31046.72 | 1.000 |
| bars_only_5m_2bars | 90 | 31.11% | 12 | 1099.96 | -392.57 | -35331.61 | -36554.54 | 1.000 |
| linear_5m_2bars | 195 | 35.90% | 24 | 1037.16 | -138.23 | -26954.10 | -38165.72 | 1.000 |
| dense_5m_2bars | 182 | 38.46% | 22 | 1011.95 | -128.67 | -23418.48 | -36622.62 | 1.000 |
| hybrid_5m_2bars | 88 | 30.68% | 11 | 1103.80 | -370.27 | -32583.53 | -33080.00 | 1.000 |
| bars_only_5m_3bars | 87 | 28.74% | 9 | 1117.44 | -437.71 | -38080.54 | -38823.11 | 1.000 |
| linear_5m_3bars | 147 | 33.33% | 15 | 989.56 | -254.57 | -37422.47 | -37347.46 | 1.000 |
| dense_5m_3bars | 152 | 35.53% | 14 | 964.31 | -252.75 | -38418.10 | -38405.65 | 1.000 |
| hybrid_5m_3bars | 150 | 37.33% | 15 | 950.92 | -250.39 | -37559.20 | -38806.86 | 1.000 |

Historically qualifying policies: [].
No strategy approved for trading. Joint BH includes all94original+extension endpoints; five-day block tests do not erase prior research reuse.1000trade shuffles and1000day-block resamples per endpoint do not replay reordered account controls.
All normal/doubled accounts replay independently; changing confirmation can change later positions and risk admission, so ledgers need not be subsets. Candidate filtering itself is remove-only for neural policies.
Original linear ungated ledger parity verified. Each held position retains its entry strike and expiry. One complete lot is recorded explicitly in every ledger.
See ../BAR_CONFIRMATION_PREREG.md and ../results/REPORT.md for original model evidence. Results are saved in results.json and32full trade ledgers in ledgers.zip.
