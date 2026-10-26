# R105B report: book direction biases on the paper arms (all tables)

Final read 2026-10-26T08:56:15+00:00. BH family: 40 new + 183 earlier filter / direction tests on these arms (28 with p < 0.05). 1 lot, net after charges and slippage. 'dropped' = the arm's signals the bias removed; 'lost' = how many of them lost on their own. D1 and D10 are post-hoc (PREREG section 0).

## R72 Breadth Donchian

| bias | design trades / win / net | WF net | quarters | final trades / win / net | final max DD | dropped d (lost) | twin p d / f | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 3071 / 47% / Rs 216,929 | Rs 335,882 | [85063, 70225, 9774, 170821] | 1202 / 46% / Rs 171,366 | Rs 61,884 | - | - | - | - |
| D1 GAPHL: trade the gap's side (open beyond yesterday's high / low) (post-hoc) | 2620 / 47% / Rs 282,526 | Rs 357,494 | [107055, 61555, 17399, 171484] | 974 / 44% / Rs 154,478 | Rs 79,295 | 787 (458) | 0.006 / 0.4531 | 1.0 | fail: a_final, b_twin, c_bh, e_top5, f_dd |
| D2 WK3: weekly 3-bar swing state (Trader Tom) | 1485 / 49% / Rs 184,006 | Rs 202,847 | [83241, 15612, 9125, 94869] | 596 / 46% / Rs 66,537 | Rs 65,441 | 3008 (1716) | 0.0319 / 0.8723 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D3 WK89: weekly 89 SMA (Trader Tom) | 1677 / 46% / Rs 54,528 | Rs 199,994 | [79136, 5879, 12950, 102029] | 703 / 45% / Rs 91,646 | Rs 54,224 | 2766 (1535) | 0.6148 / 0.8004 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D4 DDOW: daily Dow structure HH/HL (Trader Tom, Douglas) | 2075 / 47% / Rs 192,734 | Rs 173,277 | [59164, 8383, -15905, 121635] | 788 / 47% / Rs 104,940 | Rs 66,651 | 1940 (1094) | 0.0758 / 0.7545 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D5 H60: 60-minute Dow structure (Woods) | 2194 / 46% / Rs 107,559 | Rs 214,925 | [-1734, 19361, 45048, 152250] | 854 / 44% / Rs 118,238 | Rs 44,874 | 1753 (979) | 0.6687 / 0.7305 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D6 PDAY: prior-day trend (Porwal) | 1860 / 48% / Rs 231,173 | Rs 236,738 | [19751, 79528, 37295, 100165] | 681 / 43% / Rs 57,724 | Rs 65,401 | 2243 (1296) | 0.024 / 0.9661 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D7 SMA200: daily 200 SMA with a 1% band (Fabozzi, Aziz) | 1662 / 48% / Rs 238,957 | Rs 219,231 | [86138, 5879, 12950, 114264] | 654 / 43% / Rs 47,394 | Rs 68,093 | 2619 (1492) | 0.006 / 0.9681 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5, f_dd |
| D8 ADX30: daily ADX(20) > 30 with DI (Larry Swing) | 3024 / 47% / Rs 233,952 | Rs 330,967 | [85063, 70225, 9774, 165906] | 1151 / 46% / Rs 138,745 | Rs 61,884 | 88 (55) | 0.0639 / 0.9461 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D9 F15: first 15-minute candle, entries from 09:30 (Heitkoetter) | 1919 / 48% / Rs 268,343 | Rs 304,298 | [26102, 85845, 43714, 148636] | 771 / 48% / Rs 161,317 | Rs 49,583 | 2305 (1328) | 0.002 / 0.2295 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D10 GAP25: trade the gap's side, open vs close +-0.25% (post-hoc) | 2562 / 47% / Rs 288,581 | Rs 354,308 | [93412, 70863, 18852, 171181] | 916 / 45% / Rs 153,582 | Rs 77,734 | 896 (525) | 0.004 / 0.475 | 1.0 | fail: a_final, b_twin, c_bh, e_top5, f_dd |

OOS 14-19 Oct (4 sessions): baseline 25 trades / -Rs 624; D1 20 / Rs 3,971; D2 18 / -Rs 14,402; D3 15 / -Rs 7,281; D4 13 / Rs 12,056; D5 17 / -Rs 14,338; D6 9 / -Rs 9,726; D7 18 / -Rs 14,402; D8 22 / -Rs 8,524; D9 22 / -Rs 5,557; D10 25 / -Rs 624

## R51 Donchian 20 + Supertrend

| bias | design trades / win / net | WF net | quarters | final trades / win / net | final max DD | dropped d (lost) | twin p d / f | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 4208 / 44% / Rs 50,425 | Rs 213,748 | [13763, 59076, 20835, 120074] | 1670 / 45% / Rs 134,743 | Rs 81,683 | - | - | - | - |
| D1 GAPHL: trade the gap's side (open beyond yesterday's high / low) (post-hoc) | 3615 / 45% / Rs 105,691 | Rs 212,761 | [22190, 53041, 14544, 122986] | 1350 / 44% / Rs 101,792 | Rs 90,797 | 1530 (885) | 0.0319 / 0.6886 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5, f_dd |
| D2 WK3: weekly 3-bar swing state (Trader Tom) | 2074 / 45% / Rs 82,333 | Rs 182,114 | [60280, -1814, 25909, 97739] | 823 / 45% / Rs 32,526 | Rs 78,128 | 6478 (3708) | 0.012 / 0.8164 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D3 WK89: weekly 89 SMA (Trader Tom) | 2332 / 44% / -Rs 14,831 | Rs 123,006 | [46636, 32947, 11237, 32185] | 977 / 44% / Rs 51,775 | Rs 67,041 | 5553 (3140) | 0.2116 / 0.8204 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D4 DDOW: daily Dow structure HH/HL (Trader Tom, Douglas) | 2887 / 45% / Rs 27,853 | Rs 135,079 | [22784, 52345, -8160, 68110] | 1111 / 45% / Rs 56,793 | Rs 69,339 | 4010 (2254) | 0.1856 / 0.8603 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D5 H60: 60-minute Dow structure (Woods) | 3124 / 45% / Rs 30,331 | Rs 159,985 | [-17506, 7876, 40318, 129298] | 1207 / 44% / Rs 74,297 | Rs 72,640 | 3529 (2043) | 0.1657 / 0.7625 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D6 PDAY: prior-day trend (Porwal) | 2550 / 45% / Rs 145,561 | Rs 138,386 | [1543, 60737, 11264, 64843] | 971 / 42% / -Rs 21,905 | Rs 74,793 | 4795 (2829) | 0.004 / 0.992 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D7 SMA200: daily 200 SMA with a 1% band (Fabozzi, Aziz) | 2310 / 44% / Rs 96,381 | Rs 160,321 | [58761, 32947, 11237, 57375] | 928 / 42% / -Rs 18,225 | Rs 85,764 | 5580 (3183) | 0.004 / 0.9721 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D8 ADX30: daily ADX(20) > 30 with DI (Larry Swing) | 4147 / 45% / Rs 63,393 | Rs 212,653 | [13763, 59076, 20835, 118979] | 1618 / 45% / Rs 89,094 | Rs 81,683 | 229 (140) | 0.1357 / 0.988 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D9 F15: first 15-minute candle, entries from 09:30 (Heitkoetter) | 2604 / 46% / Rs 216,169 | Rs 268,003 | [-22421, 71108, 59735, 159582] | 1055 / 47% / Rs 171,587 | Rs 63,061 | 4696 (2726) | 0.002 / 0.0339 | 0.2908 | fail: c_bh |
| D10 GAP25: trade the gap's side, open vs close +-0.25% (post-hoc) | 3485 / 45% / Rs 127,152 | Rs 243,224 | [11455, 66342, 23483, 141945] | 1275 / 45% / Rs 141,045 | Rs 83,759 | 1831 (1091) | 0.014 / 0.1996 | 0.9676 | fail: b_twin, c_bh |

OOS 14-19 Oct (4 sessions): baseline 46 trades / Rs 3,412; D1 37 / Rs 7,458; D2 28 / -Rs 3,986; D3 26 / -Rs 3,086; D4 26 / Rs 10,045; D5 31 / -Rs 13,793; D6 22 / -Rs 16,792; D7 28 / -Rs 3,986; D8 34 / -Rs 948; D9 34 / Rs 4,053; D10 45 / Rs 4,764

## Liquidity 15+5

| bias | design trades / win / net | WF net | quarters | final trades / win / net | final max DD | dropped d (lost) | twin p d / f | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 881 / 44% / Rs 257,249 | Rs 238,064 | [94245, 17659, 9336, 116825] | 357 / 37% / Rs 11,155 | Rs 61,062 | - | - | - | - |
| D1 GAPHL: trade the gap's side (open beyond yesterday's high / low) (post-hoc) | 831 / 44% / Rs 255,591 | Rs 222,647 | [83874, 17588, 7342, 113844] | 342 / 37% / Rs 6,982 | Rs 65,237 | 50 (31) | 0.2535 / 0.7066 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D2 WK3: weekly 3-bar swing state (Trader Tom) | 436 / 44% / Rs 122,143 | Rs 120,996 | [22484, 14952, -890, 84450] | 171 / 37% / Rs 8,481 | Rs 33,976 | 446 (252) | 0.6427 / 0.4511 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D3 WK89: weekly 89 SMA (Trader Tom) | 474 / 42% / Rs 68,398 | Rs 55,970 | [27028, -14858, 1531, 42269] | 197 / 38% / -Rs 17,179 | Rs 40,799 | 412 (224) | 0.99 / 0.8443 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D4 DDOW: daily Dow structure HH/HL (Trader Tom, Douglas) | 627 / 43% / Rs 185,534 | Rs 163,378 | [58371, 3379, -1557, 103185] | 233 / 37% / Rs 22,286 | Rs 29,578 | 258 (142) | 0.5509 / 0.2335 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D5 H60: 60-minute Dow structure (Woods) | 672 / 42% / Rs 196,123 | Rs 171,838 | [47447, 24034, -5932, 106289] | 258 / 40% / Rs 29,040 | Rs 35,413 | 212 (109) | 0.5928 / 0.1417 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D6 PDAY: prior-day trend (Porwal) | 595 / 44% / Rs 196,111 | Rs 162,944 | [59175, 20498, -1110, 84381] | 205 / 39% / Rs 10,178 | Rs 38,630 | 286 (165) | 0.3353 / 0.4311 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D7 SMA200: daily 200 SMA with a 1% band (Fabozzi, Aziz) | 454 / 41% / Rs 76,068 | Rs 70,332 | [44488, -14858, 1531, 39170] | 198 / 39% / -Rs 1,180 | Rs 26,799 | 433 (235) | 0.9581 / 0.6248 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D8 ADX30: daily ADX(20) > 30 with DI (Larry Swing) | 873 / 43% / Rs 254,336 | Rs 238,064 | [94245, 17659, 9336, 116825] | 353 / 37% / Rs 13,969 | Rs 58,248 | 8 (2) | 0.6407 / 0.2036 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D9 F15: first 15-minute candle, entries from 09:30 (Heitkoetter) | 619 / 43% / Rs 211,594 | Rs 180,105 | [58653, 12003, -1453, 110901] | 249 / 36% / -Rs 13,412 | Rs 54,341 | 262 (147) | 0.1996 / 0.8743 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D10 GAP25: trade the gap's side, open vs close +-0.25% (post-hoc) | 795 / 44% / Rs 255,936 | Rs 223,949 | [80651, 20667, 10475, 112156] | 341 / 38% / Rs 20,778 | Rs 59,910 | 86 (53) | 0.1717 / 0.0918 | 0.9305 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |

OOS 14-19 Oct (4 sessions): baseline 15 trades / -Rs 7,105; D1 15 / -Rs 7,105; D2 8 / -Rs 7,098; D3 9 / -Rs 7,371; D4 6 / Rs 1,218; D5 12 / -Rs 8,738; D6 11 / -Rs 8,416; D7 8 / -Rs 7,098; D8 13 / -Rs 6,933; D9 9 / -Rs 7,419; D10 15 / -Rs 7,105

## R52 Open outside value

| bias | design trades / win / net | WF net | quarters | final trades / win / net | final max DD | dropped d (lost) | twin p d / f | BH q | verdict |
|---|---|---|---|---|---|---|---|---|---|
| baseline | 706 / 56% / Rs 290,573 | Rs 247,057 | [-1935, 80367, 33299, 135326] | 293 / 52% / Rs 48,612 | Rs 47,194 | - | - | - | - |
| D1 GAPHL: trade the gap's side (open beyond yesterday's high / low) (post-hoc) | 706 / 56% / Rs 290,573 | Rs 247,057 | [-1935, 80367, 33299, 135326] | 293 / 52% / Rs 48,612 | Rs 47,194 | 0 (0) | 1.0 / 1.0 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D2 WK3: weekly 3-bar swing state (Trader Tom) | 343 / 55% / Rs 184,668 | Rs 192,931 | [34698, 15151, 30780, 112302] | 138 / 54% / Rs 22,408 | Rs 34,876 | 368 (159) | 0.1896 / 0.5309 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D3 WK89: weekly 89 SMA (Trader Tom) | 429 / 55% / Rs 137,926 | Rs 148,800 | [26180, 26160, 28671, 67789] | 170 / 55% / Rs 55,255 | Rs 38,331 | 284 (118) | 0.7924 / 0.1317 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D4 DDOW: daily Dow structure HH/HL (Trader Tom, Douglas) | 486 / 55% / Rs 202,209 | Rs 184,455 | [-3448, 50186, 16149, 121567] | 195 / 53% / Rs 40,831 | Rs 45,036 | 223 (96) | 0.487 / 0.3253 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D5 H60: 60-minute Dow structure (Woods) | 570 / 55% / Rs 240,754 | Rs 194,032 | [-16499, 70572, 22876, 117083] | 219 / 53% / Rs 65,639 | Rs 24,420 | 136 (58) | 0.4072 / 0.0659 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D6 PDAY: prior-day trend (Porwal) | 603 / 53% / Rs 175,556 | Rs 124,871 | [-25296, 53713, 11484, 84970] | 225 / 52% / Rs 46,052 | Rs 37,157 | 103 (32) | 0.992 / 0.3273 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D7 SMA200: daily 200 SMA with a 1% band (Fabozzi, Aziz) | 413 / 55% / Rs 155,680 | Rs 174,597 | [25242, 24425, 28671, 96258] | 177 / 53% / Rs 52,305 | Rs 42,665 | 297 (126) | 0.6248 / 0.1437 | 1.0 | fail: a_design, a_wf, a_quarters, b_twin, c_bh, e_top5 |
| D8 ADX30: daily ADX(20) > 30 with DI (Larry Swing) | 693 / 56% / Rs 312,887 | Rs 245,012 | [-1935, 80367, 33299, 133281] | 279 / 52% / Rs 51,776 | Rs 47,194 | 13 (9) | 0.012 / 0.2715 | 1.0 | fail: a_wf, a_quarters, b_twin, c_bh |
| D9 F15: first 15-minute candle, entries from 09:30 (Heitkoetter) | 706 / 56% / Rs 290,573 | Rs 247,057 | [-1935, 80367, 33299, 135326] | 293 / 52% / Rs 48,612 | Rs 47,194 | 0 (0) | 1.0 / 1.0 | 1.0 | fail: a_design, a_wf, a_final, a_quarters, b_twin, c_bh, e_top5 |
| D10 GAP25: trade the gap's side, open vs close +-0.25% (post-hoc) | 703 / 56% / Rs 294,074 | Rs 247,057 | [-1935, 80367, 33299, 135326] | 285 / 51% / Rs 28,036 | Rs 69,758 | 3 (2) | 0.1976 / 0.984 | 1.0 | fail: a_wf, a_final, a_quarters, b_twin, c_bh, e_top5, f_dd |

OOS 14-19 Oct (4 sessions): baseline 6 trades / -Rs 7,291; D1 6 / -Rs 7,291; D2 1 / -Rs 3,545; D3 1 / Rs 1,002; D4 4 / -Rs 7,729; D5 6 / -Rs 7,291; D6 6 / -Rs 7,291; D7 1 / -Rs 3,545; D8 1 / -Rs 3,545; D9 6 / -Rs 7,291; D10 6 / -Rs 7,291

