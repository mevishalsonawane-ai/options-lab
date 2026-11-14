# Completed-candle timeframe alignment test

Signal source: NIFTY underlying. Bullish=close>open, bearish=close<open; doji/missing=no signal. Real fixed-strike option contracts with modeled fills/costs.
Period: 2025-10-20 through 2026-10-19; 247 available sessions. Initial capital Rs100000 per independently replayed arm.

**Exact 5-minute + 1-minute + 10-second strategy: BLOCKED. No win rate or profit result. The following are partial ablations only.**

| Arm | Trades | Net wins | Net win rate | Gross Rs | Fees Rs | Friction Rs | Net Rs | Ending capital Rs | Realized DD Rs | Close-mark DD Rs | PF |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| one_minute_base | 210 | 93 | 44.29% | 12159.30 | 14537.05 | 28730.02 | -31107.77 | 68892.23 | 39404.83 | 39404.83 | 0.786 |
| one_minute_double_friction | 178 | 86 | 48.31% | 27674.86 | 12190.24 | 47929.82 | -32445.20 | 67554.80 | 39463.65 | 39463.65 | 0.745 |
| one_plus_five_base | 144 | 56 | 38.89% | -9373.03 | 9710.78 | 19242.47 | -38326.28 | 61673.72 | 39296.06 | 39378.05 | 0.632 |
| one_plus_five_double_friction | 78 | 25 | 32.05% | -15757.34 | 4941.45 | 18741.73 | -39440.52 | 60559.48 | 39440.52 | 39440.52 | 0.397 |

## Chronological diagnostic restarts

Each window starts afresh with Rs100000; these are not a concatenated portfolio or unseen holdout. They show whether the full-account halt conceals later outcomes.

| Arm/window | Trades | Win rate | Net Rs | Realized DD Rs |
|---|---:|---:|---:|---:|
| one_minute_base_first | 134 | 46.27% | -21699.70 | 31496.90 |
| one_minute_base_middle | 76 | 40.79% | -9408.07 | 23854.12 |
| one_minute_base_last | 116 | 39.66% | -26019.76 | 27111.67 |
| one_minute_double_friction_first | 120 | 50.00% | -19518.15 | 37860.97 |
| one_minute_double_friction_middle | 67 | 41.79% | -16457.74 | 26832.52 |
| one_minute_double_friction_last | 108 | 38.89% | -33280.33 | 35002.00 |
| one_plus_five_base_first | 121 | 38.84% | -35556.32 | 37701.71 |
| one_plus_five_base_middle | 80 | 40.00% | -18749.85 | 27725.18 |
| one_plus_five_base_last | 118 | 39.83% | -24803.52 | 34709.57 |
| one_plus_five_double_friction_first | 78 | 32.05% | -39440.52 | 39440.52 |
| one_plus_five_double_friction_middle | 62 | 37.10% | -27248.75 | 30861.93 |
| one_plus_five_double_friction_last | 85 | 28.24% | -38381.58 | 39243.63 |

## Rules and limits

Five-minute bars anchor at09:15 IST and require all five valid observed minutes. At09:20 the latest completed five-minute candle is09:15–09:19; the latest completed minute is09:19. No in-progress higher candle or next-minute close is used. Entry uses the option open at the decision timestamp (a modeled fill, not a latency-aware executable quote).
Both ablations allow decisions09:20–13:45, maximum two trades/day and one position. First aligned eligible signal while flat can trade; later alignments after an exit may re-enter. One lot, one-strike ITM, scheduled weekly expiry1–7 days away; skip expiry day. Existing fixed-contract loader resolves rolling offsets at entry only.
Same exits: initial -15% premium stop,+30% target,60% profit lock after+15% from completed prior bars;20-minute failure exit unless+5%, maximum45min, square-off14:50. Stop-first if both stop and target touched. Same Rs2500 initial risk, Rs3000 daily, Rs13000 monthly and Rs40000 drawdown budgets; gaps can overshoot. The account can stop trading before data ends.
Brokerage, dated STT, GST, SEBI, exchange/IPFT, buy stamp and assumed spread/slippage are included. Double-friction scenarios fully replay risk/cash and can take different trades. Missing held quotes delay fills and are counted. Quote spreads, tick-level stop ordering and latency were not observed.
All dates were previously inspected in repository studies. BH adjusts the four primary signal/friction replay p-values in results.json; it does not correct the whole previously reused research program or make diagnostic restarts fresh. There is no pristine final holdout, no neural model, no parameter search, no inferential claim of a new edge, no live/paper promotion and no actual orders. Win rate refers to net-positive option trades, not candle prediction accuracy.
The unavailable ten-second arm must not inherit these partial results. Sealed phone recordings require local decryption before their feed, coverage and contract quotes can be evaluated. No private recordings or catalogue metadata are published here.

Monthly trade counts and net values, rejection reasons and last trade dates are in results.json; four complete executed ledgers are alongside this report. See PREREG.md for the registered protocol.
