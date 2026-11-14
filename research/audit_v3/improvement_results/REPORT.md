# Controlled improvements: results

One predefined exit family and one fixed, remove-only 55% GRU direction gate. No parameter search or production changes.

| Replay | Trades | Win rate | Net Rs | Drawdown Rs | Profit factor |
|---|---:|---:|---:|---:|---:|
| baseline_base | 179 | 44.13% | -38542.15 | 38661.74 | 0.61 |
| baseline_double_friction | 129 | 34.88% | -39689.65 | 39694.87 | 0.47 |
| no_target_base | 198 | 43.94% | -38563.99 | 38683.58 | 0.64 |
| no_target_double_friction | 134 | 35.82% | -39497.56 | 39502.78 | 0.49 |
| runner_base | 199 | 39.70% | -14866.22 | 26185.85 | 0.86 |
| runner_double_friction | 192 | 29.17% | -36757.31 | 38108.00 | 0.69 |
| common_baseline_base | 144 | 34.72% | -38194.00 | 38194.00 | 0.49 |
| remove_only_direction_base | 0 | n/a | 0.00 | 0.00 | n/a |
| common_baseline_double_friction | 110 | 31.82% | -38687.17 | 38687.17 | 0.41 |
| remove_only_direction_double_friction | 0 | n/a | 0.00 | 0.00 | n/a |

Full period: 2025-10-20 through 2026-10-19. Common direction-gate period starts 2026-04-22; baseline and filtered account each start once with Rs100000 there.

no_target removes only the +30% target, keeping the existing profit lock and 45-minute limit. runner removes target and profit lock, retains initial -15% stop and 20-minute failure exit, and extends maximum hold to60 minutes.

Full exit scenarios replay their own cash, risk gates and entries. Direction filtering removes only already-executed common-baseline trades and reserves their old time/slots. It does not free capacity for promoted entries. It rechecks realized cash/risk admission; it does not reconstruct a changed intrabar account-equity path, so it is a diagnostic subset replay, not full app-engine parity.

Forecasts are out-of-fold within previously reviewed history. No all-data model is used. A 55% direction probability is not a 55% option-trade win probability. No proof of a shared improvement for all strategies or fresh trading edge.

Use the separate strategy matrix for every knowledge-card family and current missing families. R51/R72, ORB, Liquidity, CISD, overnight, MCX and crypto require their own original-engine parity and inputs before promotion. Higher win rate alone cannot pass.
