# R27 step 4a: walk-forward test years 2023, 2024, 2025 (Jan-Sep)

PREREG sha256 4a464676e38621541d35f4155c513cc39e5dc8ef79ae6f163965de8b6e884c45

| variant | trades | held_pct | win_pct | avg_win | avg_loss | per_trade | per_day | net | diff | net15 | green_days_pct | worst_day | max_dd | gave_back | rescued | helped | hurt | big_gain_b0_kept | p (vs B0) | BH q (8) | twin p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B0 | 472 | 0.0 | 55.9 | 1961.0 | -1806.0 | 301.3 | 209.7 | 142209 | 0 | 133816 | 54.0 | -15383 | -39504 | 0 | 0 | 0 | 0 | 1.0 |  |  |  |
| V1_H1_I20s | 472 | 100.0 | 54.7 | 2175.0 | -1894.0 | 330.0 | 229.7 | 155769 | 13559 | 147363 | 51.6 | -13741 | -53810 | 22 | 16 | 235 | 237 | 1.063 | 0.2126758909226547 | 0.739426015962537 |  |
| V2_H1_I30s | 472 | 100.0 | 51.9 | 2248.0 | -1853.0 | 275.2 | 191.6 | 129910 | -12300 | 121525 | 50.3 | -13976 | -62650 | 42 | 23 | 217 | 255 | 1.061 | 0.7060802326788189 | 0.795203144446059 |  |
| V3_H1_I20r | 472 | 100.0 | 56.8 | 2102.0 | -1943.0 | 353.8 | 246.3 | 166987 | 24778 | 158572 | 54.0 | -14169 | -47980 | 15 | 19 | 243 | 227 | 1.067 | 0.05949987198085881 | 0.47599897584687045 |  |
| V4_H1_I30r | 472 | 100.0 | 54.4 | 2177.0 | -1892.0 | 323.9 | 225.5 | 152876 | 10667 | 144473 | 51.9 | -14403 | -51109 | 30 | 23 | 233 | 237 | 1.054 | 0.29678246947861986 | 0.739426015962537 |  |
| V5_H1_P20 | 472 | 100.0 | 54.0 | 2153.0 | -1887.0 | 296.0 | 206.1 | 139712 | -2498 | 131320 | 50.0 | -15187 | -52073 | 32 | 23 | 237 | 235 | 1.08 | 0.5545695119719027 | 0.739426015962537 |  |
| V6_H1_P30 | 472 | 100.0 | 51.7 | 2287.0 | -1916.0 | 256.9 | 178.8 | 121247 | -20962 | 112870 | 50.3 | -14797 | -58713 | 50 | 30 | 231 | 241 | 1.075 | 0.795203144446059 | 0.795203144446059 |  |
| V7_H2_I30s | 472 | 19.7 | 55.5 | 1986.0 | -1804.0 | 299.5 | 208.5 | 141346 | -863 | 132953 | 54.3 | -15383 | -45294 | 4 | 2 | 48 | 45 | 0.994 | 0.5330872151272152 | 0.739426015962537 | 0.7131434282858571 |
| V8_H3_LOCK | 472 | 100.0 | 55.7 | 2252.0 | -2165.0 | 296.1 | 206.1 | 139749 | -2461 | 131355 | 51.9 | -15450 | -52057 | 40 | 39 | 197 | 275 | 1.081 | 0.5415671215510633 | 0.739426015962537 |  |

## By year: total diff vs B0 (Rs) / trades

| variant | 2023 | 2024 | 2025 | DESC (in-sample) |
|---|---|---|---|---|
| V1_H1_I20s | 4228 / 166 | 10430 / 167 | -1099 / 139 | 21387 |
| V2_H1_I30s | 1193 / 166 | 784 / 167 | -14276 / 139 | 31348 |
| V3_H1_I20r | 5794 / 166 | 10019 / 167 | 8965 / 139 | 24157 |
| V4_H1_I30r | 4660 / 166 | 5270 / 167 | 737 / 139 | 34382 |
| V5_H1_P20 | 5852 / 166 | 5359 / 167 | -13708 / 139 | 6716 |
| V6_H1_P30 | 3260 / 166 | 13966 / 167 | -38188 / 139 | 15706 |
| V7_H2_I30s | 3465 / 166 | -307 / 167 | -4021 / 139 | 2308 |
| V8_H3_LOCK | -365 / 166 | 403 / 167 | -2499 / 139 | 32809 |

B0 by year: 2023 30249 (166 trades), 2024 38277 (167 trades), 2025 73683 (139 trades)

Anchored walk-forward pick: {'picks': [{'year': 2023, 'pick': 'V4_H1_I30r', 'past_diff': 34382, 'diff_that_year': 4660}, {'year': 2024, 'pick': 'V4_H1_I30r', 'past_diff': 39042, 'diff_that_year': 5270}, {'year': 2025, 'pick': 'V4_H1_I30r', 'past_diff': 44312, 'diff_that_year': 737}], 'total_diff_vs_b0': 10667}

## Gates

- V1_H1_I20s: {'g1_diff_pos': True, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': True, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V2_H1_I30s: {'g1_diff_pos': False, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': False, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V3_H1_I20r: {'g1_diff_pos': True, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': True, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V4_H1_I30r: {'g1_diff_pos': True, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': True, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V5_H1_P20: {'g1_diff_pos': False, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': False, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V6_H1_P30: {'g1_diff_pos': False, 'g2_bh_q8': False, 'g3_years': True, 'g4_stress': False, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False
- V7_H2_I30s: {'g1_diff_pos': False, 'g2_bh_q8': False, 'g3_years': False, 'g4_stress': False, 'g5_risk': False, 'g6_twin': False, 'g7_n': True} -> PASS_WF False
- V8_H3_LOCK: {'g1_diff_pos': False, 'g2_bh_q8': False, 'g3_years': False, 'g4_stress': False, 'g5_risk': False, 'g6_twin': None, 'g7_n': True} -> PASS_WF False

## By index

- V1_H1_I20s: NIFTY trades 222, diff 8005, gave back 6; BANKNIFTY trades 250, diff 5554, gave back 16; exits {'idx_stop': 234, 'idx_target': 238}
- V2_H1_I30s: NIFTY trades 222, diff -5265, gave back 19; BANKNIFTY trades 250, diff -7035, gave back 23; exits {'idx_target': 214, 'idx_stop': 246, 'time_1030': 12}
- V3_H1_I20r: NIFTY trades 222, diff 8005, gave back 6; BANKNIFTY trades 250, diff 16772, gave back 9; exits {'idx_stop': 240, 'idx_target': 232}
- V4_H1_I30r: NIFTY trades 222, diff -5265, gave back 19; BANKNIFTY trades 250, diff 15932, gave back 11; exits {'idx_target': 232, 'idx_stop': 230, 'time_1030': 10}
- V5_H1_P20: NIFTY trades 222, diff -718, gave back 21; BANKNIFTY trades 250, diff -1779, gave back 11; exits {'stop': 218, 'target': 222, 'time_1030': 15, 'target_gap': 8, 'stop_gap': 9}
- V6_H1_P30: NIFTY trades 222, diff -13228, gave back 30; BANKNIFTY trades 250, diff -7734, gave back 20; exits {'target': 198, 'stop': 206, 'time_1030': 52, 'stop_gap': 8, 'target_gap': 8}
- V7_H2_I30s: NIFTY trades 222, diff -1648, gave back 2; BANKNIFTY trades 250, diff 785, gave back 2; exits {'b0_0916': 379, 'idx_target': 30, 'idx_stop': 62, 'time_1030': 1}
- V8_H3_LOCK: NIFTY trades 222, diff 6016, gave back 18; BANKNIFTY trades 250, diff -8477, gave back 22; exits {'stop': 149, 'lock': 197, 'lock_gap': 59, 'time_1030': 61, 'stop_gap': 5, 'time_1030_lastclose': 1}
