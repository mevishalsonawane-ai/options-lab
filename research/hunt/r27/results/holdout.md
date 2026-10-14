# R27 step 4b: LOCKED HOLDOUT (read once)

PREREG sha256 4a464676e38621541d35f4155c513cc39e5dc8ef79ae6f163965de8b6e884c45

| variant | trades | held_pct | win_pct | avg_win | avg_loss | per_trade | per_day | net | diff | net15 | green_days_pct | worst_day | max_dd | gave_back | rescued | helped | hurt | big_gain_b0_kept | p (vs B0) | BH q (8) | twin p |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| B0 | 173 | 0.0 | 49.7 | 3899.0 | -3367.0 | 245.1 | 168.3 | 42411 | 0 | 37733 | 49.2 | -15115 | -85200 | 0 | 0 | 0 | 0 | 1.0 |  |  |  |
| V1_H1_I20s | 173 | 100.0 | 51.4 | 3826.0 | -3604.0 | 218.7 | 150.1 | 37834 | -4576 | 33160 | 53.3 | -16182 | -89050 | 8 | 11 | 82 | 91 | 0.961 | 0.6452927734841385 | 0.9717473325945138 |  |
| V2_H1_I30s | 173 | 100.0 | 49.7 | 3934.0 | -3617.0 | 136.6 | 93.8 | 23632 | -18779 | 18969 | 50.8 | -16263 | -89766 | 12 | 12 | 77 | 96 | 0.94 | 0.872439572378401 | 0.9717473325945138 |  |
| V3_H1_I20r | 173 | 100.0 | 50.3 | 3905.0 | -3519.0 | 214.8 | 147.5 | 37162 | -5248 | 32488 | 50.8 | -16182 | -90276 | 7 | 8 | 85 | 88 | 0.95 | 0.6754005009261166 | 0.9717473325945138 |  |
| V4_H1_I30r | 173 | 100.0 | 49.7 | 3977.0 | -3552.0 | 190.3 | 130.7 | 32927 | -9484 | 28256 | 50.0 | -16182 | -89588 | 8 | 8 | 85 | 88 | 0.946 | 0.7505854430142331 | 0.9717473325945138 |  |
| V5_H1_P20 | 173 | 100.0 | 50.3 | 4003.0 | -3701.0 | 173.2 | 118.9 | 29966 | -12445 | 25298 | 50.0 | -16481 | -95430 | 10 | 11 | 77 | 96 | 1.005 | 0.829315015159424 | 0.9717473325945138 |  |
| V6_H1_P30 | 173 | 100.0 | 46.8 | 4290.0 | -3596.0 | 96.3 | 66.1 | 16667 | -25744 | 12010 | 44.3 | -16019 | -94498 | 17 | 12 | 75 | 98 | 0.968 | 0.9195842092568203 | 0.9717473325945138 |  |
| V7_H2_I30s | 173 | 26.0 | 49.7 | 3785.0 | -3356.0 | 193.6 | 132.9 | 33494 | -8916 | 28824 | 48.4 | -15115 | -83938 | 1 | 1 | 22 | 23 | 0.917 | 0.7869991045463797 | 0.9717473325945138 | 0.6201899050474763 |
| V8_H3_LOCK | 173 | 100.0 | 55.5 | 3678.0 | -4371.0 | 95.5 | 65.5 | 16517 | -25893 | 11859 | 55.7 | -16581 | -98695 | 7 | 17 | 60 | 113 | 0.972 | 0.9717473325945138 | 0.9717473325945138 |  |

## By index

- V1_H1_I20s: NIFTY trades 75, diff -2562, gave back 4; BANKNIFTY trades 98, diff -2014, gave back 4; exits {'idx_stop': 103, 'idx_target': 70}
- V2_H1_I30s: NIFTY trades 75, diff -4847, gave back 5; BANKNIFTY trades 98, diff -13932, gave back 7; exits {'idx_stop': 95, 'idx_target': 76, 'time_1030': 2}
- V3_H1_I20r: NIFTY trades 75, diff -2562, gave back 4; BANKNIFTY trades 98, diff -2686, gave back 3; exits {'idx_stop': 113, 'idx_target': 60}
- V4_H1_I30r: NIFTY trades 75, diff -4847, gave back 5; BANKNIFTY trades 98, diff -4637, gave back 3; exits {'idx_stop': 101, 'idx_target': 71, 'time_1030': 1}
- V5_H1_P20: NIFTY trades 75, diff -1646, gave back 4; BANKNIFTY trades 98, diff -10798, gave back 6; exits {'stop': 91, 'target': 74, 'time_1030': 6, 'stop_gap': 2}
- V6_H1_P30: NIFTY trades 75, diff -12630, gave back 8; BANKNIFTY trades 98, diff -13114, gave back 9; exits {'time_1030': 19, 'target': 67, 'stop': 84, 'stop_gap': 2, 'target_gap': 1}
- V7_H2_I30s: NIFTY trades 75, diff -3345, gave back 0; BANKNIFTY trades 98, diff -5571, gave back 1; exits {'b0_0916': 128, 'idx_target': 18, 'idx_stop': 26, 'time_1030': 1}
- V8_H3_LOCK: NIFTY trades 75, diff -12039, gave back 3; BANKNIFTY trades 98, diff -13854, gave back 4; exits {'stop': 66, 'lock': 75, 'lock_gap': 21, 'time_1030': 10, 'stop_gap': 1}
