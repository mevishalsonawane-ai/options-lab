# R78 result: Daily High/Low Reversal on XAUUSD — REJECTED

Study commit: `81a357c` (pre-registration `33239c3`, pushed before any result). Write-up:
`research/HUNT_R78_GOLD_DAILY_HL_REVERSAL.md`. Signal source: XAUUSD itself. Daily candle: UTC day, Mon-Fri.
Data: Dukascopy XAUUSD 1-minute BID, release file + 98 Dukascopy day files, 2 Oct 2023 - 16 Oct 2026.
USD per 100 oz lot after spread $0.30, $7 commission, $0.05 stop slippage.

**Verdict: 0 of 24 lines pass** (8 variants x long / short / combined). Note only; no paper arm.

| line | design trades | design net | WF net | BH q | final trades | final net |
|---|---|---|---|---|---|---|
| V1 as described, combined | 353 | -9,239 | -4,586 | 0.60 | 75 | -10,710 |
| V1 long | 154 | +2,578 | +3,103 | 0.58 | 45 | -7,885 |
| V1 short | 199 | -11,817 | -7,689 | 0.85 | 30 | -2,825 |
| best long in design: V5 1.5R | 156 | +13,371 | +13,305 | 0.17 | 45 | -4,172 |
| V7 swing + profit lock, long | 154 | +11,831 | +13,371 | 0.17 | 45 | -3,951 |
| best combined: V2 K=1 | 259 | -609 | +3,899 | 0.47 | 55 | -8,066 |

- Shorts lose in every variant and period.
- Longs: positive in design for V2/V5/V7/V8 (raw twin p 0.008-0.03) but BH q 0.17 > 0.10, and all 8 long lines lose in
  the final window (-$3,951 to -$18,789 on 33-51 trades).
- Final window (1 Apr - 16 Oct 2026) read once (`results/FINAL_READ`).
- Checks: R78 tests 89 passed; experiments validate OK; features catalog up to date. Two brain tests
  (`test_volume_profile_level_features_point_in_time`, `test_every_feature_is_point_in_time`) fail in this sandbox on a
  clean `main` as well (library versions), unrelated to R78.
