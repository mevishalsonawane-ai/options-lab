# R105 report: Donchian giveback (all tables)

Final read 2026-10-26T07:34:53+00:00. BH family: 20 new + 274 earlier Donchian tests (46 of them with p < 0.05). 1 lot, net after charges and slippage. 'fixed' = the baseline's own trades re-priced (no re-walk).

## R72 Breadth Donchian

| rule | design trades / net | WF net | quarters | final trades / net | final worst day / max DD | changed d / f | twin p d / f | BH q | 2x slip final | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| baseline | 3071 / Rs 216,929 | Rs 335,882 | [85063, 70225, 9774, 170821] | 1202 / Rs 171,366 | -Rs 22,571 / Rs 61,884 | - | - | - | Rs 137,648 | - |
| X1 BE10: stop to breakeven once +10% | 3088 / Rs 189,083 | Rs 286,350 | [90265, 60103, 14902, 121080] | 1203 / Rs 162,967 | -Rs 22,571 / Rs 65,587 | 386 / 109 | 0.984 / 0.8343 | 1.0 | Rs 129,180 | fail: a_design, a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X2 BE15: stop to breakeven once +15% | 3075 / Rs 201,194 | Rs 312,877 | [84057, 71812, 8420, 148588] | 1202 / Rs 172,168 | -Rs 22,571 / Rs 63,256 | 141 / 34 | 0.988 / 0.6327 | 1.0 | Rs 138,431 | fail: a_design, a_wf, a_quarters, b_final, c_twin, d_bh, f_ex_top5 |
| X3 L3AT15: stop to +3% once +15% | 3078 / Rs 199,907 | Rs 309,305 | [89621, 72906, 9439, 137339] | 1202 / Rs 157,499 | -Rs 22,571 / Rs 68,196 | 179 / 53 | 0.996 / 0.9681 | 1.0 | Rs 123,398 | fail: a_design, a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X4 RS1K: stop to breakeven once the open profit reaches Rs 1,000 | 3114 / Rs 64,794 | Rs 193,819 | [53833, 35415, -498, 105070] | 1207 / Rs 134,226 | -Rs 22,653 / Rs 74,655 | 784 / 216 | 1.0 / 0.9661 | 1.0 | Rs 95,458 | fail: a_design, a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X5 TT15: after 15 minutes, breakeven if it has been +8% | 3073 / Rs 211,463 | Rs 317,294 | [61149, 55838, 16162, 184145] | 1203 / Rs 150,419 | -Rs 22,571 / Rs 65,242 | 435 / 121 | 0.8862 / 0.8543 | 1.0 | Rs 116,319 | fail: a_design, a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X6 GB50: once +12%, keep half of the best gain | 3126 / Rs 125,219 | Rs 267,762 | [60243, 70833, 1090, 135595] | 1209 / Rs 122,292 | -Rs 22,571 / Rs 73,072 | 679 / 220 | 1.0 / 0.9541 | 1.0 | Rs 86,565 | fail: a_design, a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X7 IDX0: index close back through the breakout level (from the fill) | 3116 / Rs 278,825 | Rs 312,521 | [49260, 72142, 63785, 127333] | 1217 / Rs 140,319 | -Rs 21,568 / Rs 53,679 | 1508 / 546 | 0.9401 / 0.4112 | 1.0 | Rs 107,231 | fail: a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X8 IDX10: X7, armed once +10% | 3071 / Rs 218,821 | Rs 328,275 | [90543, 68236, 12184, 157312] | 1202 / Rs 174,969 | -Rs 22,571 / Rs 60,825 | 99 / 29 | 0.6567 / 0.2295 | 1.0 | Rs 141,296 | fail: a_wf, a_quarters, c_twin, d_bh, f_ex_top5 |
| X9 PART15 (Carli / Douglas): 1 of 2 lots at +15%, the other to breakeven | 3075 / Rs 141,180 | Rs 267,797 | [62245, 64151, 13023, 128377] | 1202 / Rs 113,294 | -Rs 22,571 / Rs 56,134 | 740 / 264 | 1.0 / 1.0 | 1.0 | Rs 81,158 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X10 TOM05 (Trader Tom): breakeven at +1R (+15%), then trail the last 10-minute index bar | 3075 / Rs 198,332 | Rs 310,637 | [72846, 70329, 16379, 151083] | 1202 / Rs 176,360 | -Rs 22,571 / Rs 57,859 | 263 / 79 | 0.99 / 0.3573 | 1.0 | Rs 142,687 | fail: a_design, a_wf, a_quarters, c_twin, d_bh, f_ex_top5 |

### The giveback cohort (baseline trades)

| group | design n / net | WF n / net | final n / net | OOS n / net |
|---|---|---|---|---|
| all | 3071 / Rs 216,929 | 2238 / Rs 335,882 | 1202 / Rs 171,366 | 25 / -Rs 624 |
| reached_rs1000 | 1638 / Rs 1,848,726 | 1214 / Rs 1,411,328 | 590 / Rs 829,786 | 14 / Rs 16,314 |
| rs1000_lost | 384 / -Rs 561,586 | 268 / -Rs 372,663 | 118 / -Rs 108,736 | 2 / -Rs 1,703 |
| rs1000_2000_lost | 284 / -Rs 401,696 | 211 / -Rs 286,948 | 98 / -Rs 91,608 | 2 / -Rs 1,703 |
| rs2000plus_lost | 100 / -Rs 159,890 | 57 / -Rs 85,715 | 20 / -Rs 17,128 | 0 / Rs 0 |
| pct8_25_lost | 290 / -Rs 331,964 | 193 / -Rs 209,961 | 83 / -Rs 66,066 | 0 / Rs 0 |
| pct8_15_lost | 226 / -Rs 262,517 | 148 / -Rs 168,288 | 65 / -Rs 53,980 | 0 / Rs 0 |
| pct15_25_lost | 64 / -Rs 69,448 | 45 / -Rs 41,672 | 18 / -Rs 12,086 | 0 / Rs 0 |
| pct25plus_lost | 4 / -Rs 2,395 | 3 / -Rs 2,210 | 0 / Rs 0 | 0 / Rs 0 |
| never8 | 1657 / -Rs 1,717,307 | 1200 / -Rs 1,122,545 | 716 / -Rs 623,240 | 15 / -Rs 17,667 |

### What each rule does to the cohort and to the winners (same trades, no re-walk)

| rule | period | cohort (peak >= Rs 1,000, lost) | cohort (+8-25%, lost) | baseline winners | net change |
|---|---|---|---|---|---|
| X1 | design | -Rs 561,586 -> -Rs 371,228 | -Rs 331,964 -> -Rs 117,879 | Rs 2,463,080 -> Rs 2,215,166 | -Rs 33,829 |
| X2 | design | -Rs 561,586 -> -Rs 495,613 | -Rs 331,964 -> -Rs 263,925 | Rs 2,463,080 -> Rs 2,366,526 | -Rs 28,515 |
| X3 | design | -Rs 561,586 -> -Rs 478,921 | -Rs 331,964 -> -Rs 247,251 | Rs 2,463,080 -> Rs 2,337,000 | -Rs 41,037 |
| X4 | design | -Rs 561,586 -> -Rs 43,547 | -Rs 331,964 -> -Rs 81,882 | Rs 2,463,080 -> Rs 1,754,386 | -Rs 190,656 |
| X5 | design | -Rs 561,586 -> -Rs 419,386 | -Rs 331,964 -> -Rs 161,530 | Rs 2,463,080 -> Rs 2,287,565 | -Rs 5,081 |
| X6 | design | -Rs 561,586 -> -Rs 339,003 | -Rs 331,964 -> -Rs 97,200 | Rs 2,463,080 -> Rs 2,082,670 | -Rs 143,009 |
| X7 | design | -Rs 561,586 -> -Rs 446,703 | -Rs 331,964 -> -Rs 257,664 | Rs 2,463,080 -> Rs 1,782,617 | Rs 26,027 |
| X8 | design | -Rs 561,586 -> -Rs 516,965 | -Rs 331,964 -> -Rs 282,736 | Rs 2,463,080 -> Rs 2,413,681 | -Rs 170 |
| X9 | design | -Rs 561,586 -> -Rs 438,770 | -Rs 331,964 -> -Rs 210,411 | Rs 2,463,080 -> Rs 2,249,945 | -Rs 87,094 |
| X10 | design | -Rs 561,586 -> -Rs 490,573 | -Rs 331,964 -> -Rs 258,886 | Rs 2,463,080 -> Rs 2,358,624 | -Rs 31,377 |
| X1 | final | -Rs 108,736 -> -Rs 73,980 | -Rs 66,066 -> -Rs 26,725 | Rs 963,414 -> Rs 903,776 | -Rs 20,298 |
| X2 | final | -Rs 108,736 -> -Rs 97,651 | -Rs 66,066 -> -Rs 54,980 | Rs 963,414 -> Rs 947,818 | -Rs 4,511 |
| X3 | final | -Rs 108,736 -> -Rs 93,230 | -Rs 66,066 -> -Rs 50,559 | Rs 963,414 -> Rs 926,665 | -Rs 21,243 |
| X4 | final | -Rs 108,736 -> -Rs 12,039 | -Rs 66,066 -> -Rs 18,528 | Rs 963,414 -> Rs 817,166 | -Rs 49,552 |
| X5 | final | -Rs 108,736 -> -Rs 88,974 | -Rs 66,066 -> -Rs 37,749 | Rs 963,414 -> Rs 914,117 | -Rs 20,981 |
| X6 | final | -Rs 108,736 -> -Rs 58,990 | -Rs 66,066 -> -Rs 15,822 | Rs 963,414 -> Rs 868,211 | -Rs 44,959 |
| X7 | final | -Rs 108,736 -> -Rs 97,346 | -Rs 66,066 -> -Rs 56,150 | Rs 963,414 -> Rs 740,433 | -Rs 23,754 |
| X8 | final | -Rs 108,736 -> -Rs 101,658 | -Rs 66,066 -> -Rs 58,417 | Rs 963,414 -> Rs 959,368 | Rs 3,603 |
| X9 | final | -Rs 108,736 -> -Rs 83,336 | -Rs 66,066 -> -Rs 40,666 | Rs 963,414 -> Rs 876,518 | -Rs 61,496 |
| X10 | final | -Rs 108,736 -> -Rs 93,753 | -Rs 66,066 -> -Rs 51,082 | Rs 963,414 -> Rs 948,112 | -Rs 319 |

### Paper bootstrap (percentile of the paper net; P(net <= paper))

| paper | from | iid trades | consecutive trades | 6 consecutive sessions |
|---|---|---|---|---|
| C: 11 trades, -Rs 3,583 | final | 22.5 (n 20000) | 31.1 (n 1192) | 37.9 (n 124) |
| C: 11 trades, -Rs 3,583 | wf | 22.2 (n 20000) | 32.9 (n 2228) | 29.0 (n 231) |

### OOS 14-19 Oct (4 sessions; 25 trades; never gated)

| rule | trades | net |
|---|---|---|
| baseline | 25 | -Rs 624 |
| X1 | 25 | -Rs 624 |
| X2 | 25 | -Rs 624 |
| X3 | 25 | -Rs 1,183 |
| X4 | 25 | -Rs 386 |
| X5 | 25 | -Rs 624 |
| X6 | 27 | -Rs 6,665 |
| X7 | 26 | -Rs 10,293 |
| X8 | 25 | -Rs 624 |
| X9 | 25 | -Rs 1,587 |
| X10 | 25 | -Rs 1,574 |

## R51 Donchian 20 + Supertrend

| rule | design trades / net | WF net | quarters | final trades / net | final worst day / max DD | changed d / f | twin p d / f | BH q | 2x slip final | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| baseline | 4208 / Rs 50,425 | Rs 213,748 | [13763, 59076, 20835, 120074] | 1670 / Rs 134,743 | -Rs 21,578 / Rs 81,683 | - | - | - | Rs 86,825 | - |
| X1 BE10: stop to breakeven once +10% | 4219 / Rs 66,956 | Rs 172,298 | [16554, 54147, 24620, 76977] | 1673 / Rs 124,693 | -Rs 20,407 / Rs 85,777 | 541 / 144 | 0.9261 / 0.8423 | 1.0 | Rs 76,689 | fail: a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X2 BE15: stop to breakeven once +15% | 4209 / Rs 22,329 | Rs 170,008 | [5780, 60696, 17444, 86088] | 1670 / Rs 130,669 | -Rs 21,578 / Rs 80,078 | 192 / 49 | 0.992 / 0.8663 | 1.0 | Rs 82,716 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X3 L3AT15: stop to +3% once +15% | 4213 / Rs 26,521 | Rs 181,152 | [14885, 65108, 19225, 81933] | 1670 / Rs 120,027 | -Rs 21,578 / Rs 81,440 | 232 / 69 | 0.994 / 0.978 | 1.0 | Rs 71,585 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X4 RS1K: stop to breakeven once the open profit reaches Rs 1,000 | 4245 / -Rs 31,657 | Rs 124,752 | [8703, 15474, 21477, 79098] | 1676 / Rs 119,759 | -Rs 20,331 / Rs 73,282 | 1090 / 311 | 1.0 / 0.9182 | 1.0 | Rs 64,850 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X5 TT15: after 15 minutes, breakeven if it has been +8% | 4210 / Rs 70,824 | Rs 198,108 | [-3514, 47174, 28738, 125711] | 1675 / Rs 105,434 | -Rs 20,525 / Rs 90,547 | 618 / 170 | 0.9301 / 0.9261 | 1.0 | Rs 56,971 | fail: a_wf, a_final, a_quarters, b_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X6 GB50: once +12%, keep half of the best gain | 4258 / -Rs 51,853 | Rs 118,190 | [-8175, 55868, 22392, 48105] | 1684 / Rs 105,950 | -Rs 21,578 / Rs 73,254 | 906 / 271 | 1.0 / 0.9681 | 1.0 | Rs 55,394 | fail: a_design, a_wf, a_final, a_quarters, b_design, c_twin, d_bh, f_ex_top5, g_slip2 |
| X7 IDX0: index close back through the breakout level (from the fill) | 4295 / Rs 106,938 | Rs 190,588 | [11620, 51803, 20387, 106778] | 1702 / Rs 130,788 | -Rs 14,813 / Rs 61,160 | 2398 / 916 | 1.0 / 0.1297 | 1.0 | Rs 83,715 | fail: a_wf, a_final, a_quarters, c_twin, d_bh, g_slip2 |
| X8 IDX10: X7, armed once +10% | 4209 / Rs 44,872 | Rs 193,171 | [17017, 61750, 22816, 91588] | 1670 / Rs 134,120 | -Rs 21,614 / Rs 81,111 | 202 / 49 | 0.9461 / 0.5429 | 1.0 | Rs 86,303 | fail: a_design, a_wf, a_final, c_twin, d_bh, f_ex_top5, g_slip2 |
| X9 PART15 (Carli / Douglas): 1 of 2 lots at +15%, the other to breakeven | 4209 / -Rs 64,584 | Rs 107,840 | [-18733, 47010, 26208, 53355] | 1670 / Rs 73,433 | -Rs 21,578 / Rs 69,721 | 948 / 334 | 1.0 / 1.0 | 1.0 | Rs 27,560 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |
| X10 TOM05 (Trader Tom): breakeven at +1R (+15%), then trail the last 10-minute index bar | 4209 / Rs 21,955 | Rs 171,010 | [3577, 53723, 26611, 87100] | 1671 / Rs 128,289 | -Rs 21,578 / Rs 79,376 | 343 / 102 | 0.988 / 0.7904 | 1.0 | Rs 80,389 | fail: a_design, a_wf, a_final, a_quarters, c_twin, d_bh, f_ex_top5, g_slip2 |

### The giveback cohort (baseline trades)

| group | design n / net | WF n / net | final n / net | OOS n / net |
|---|---|---|---|---|
| all | 4208 / Rs 50,425 | 3044 / Rs 213,748 | 1670 / Rs 134,743 | 46 / Rs 3,412 |
| reached_rs1000 | 2211 / Rs 2,382,522 | 1595 / Rs 1,797,587 | 793 / Rs 1,015,205 | 26 / Rs 31,227 |
| rs1000_lost | 580 / -Rs 825,777 | 392 / -Rs 541,851 | 171 / -Rs 182,911 | 6 / -Rs 7,300 |
| rs1000_2000_lost | 425 / -Rs 595,092 | 299 / -Rs 415,248 | 145 / -Rs 157,372 | 5 / -Rs 7,259 |
| rs2000plus_lost | 155 / -Rs 230,686 | 93 / -Rs 126,603 | 26 / -Rs 25,539 | 1 / -Rs 41 |
| pct8_25_lost | 440 / -Rs 481,738 | 291 / -Rs 294,269 | 117 / -Rs 92,333 | 3 / -Rs 2,452 |
| pct8_15_lost | 341 / -Rs 374,952 | 223 / -Rs 230,112 | 89 / -Rs 74,109 | 2 / -Rs 2,411 |
| pct15_25_lost | 99 / -Rs 106,786 | 68 / -Rs 64,158 | 28 / -Rs 18,225 | 1 / -Rs 41 |
| pct25plus_lost | 1 / -Rs 185 | 0 / Rs 0 | 0 / Rs 0 | 0 / Rs 0 |
| never8 | 2355 / -Rs 2,455,282 | 1709 / -Rs 1,665,522 | 1039 / -Rs 852,934 | 26 / -Rs 31,302 |

### What each rule does to the cohort and to the winners (same trades, no re-walk)

| rule | period | cohort (peak >= Rs 1,000, lost) | cohort (+8-25%, lost) | baseline winners | net change |
|---|---|---|---|---|---|
| X1 | design | -Rs 825,777 -> -Rs 547,358 | -Rs 481,738 -> -Rs 160,426 | Rs 3,277,448 -> Rs 2,956,207 | Rs 72 |
| X2 | design | -Rs 825,777 -> -Rs 728,065 | -Rs 481,738 -> -Rs 380,198 | Rs 3,277,448 -> Rs 3,146,966 | -Rs 28,941 |
| X3 | design | -Rs 825,777 -> -Rs 702,078 | -Rs 481,738 -> -Rs 353,706 | Rs 3,277,448 -> Rs 3,109,935 | -Rs 39,149 |
| X4 | design | -Rs 825,777 -> -Rs 59,653 | -Rs 481,738 -> -Rs 125,618 | Rs 3,277,448 -> Rs 2,406,896 | -Rs 104,427 |
| X5 | design | -Rs 825,777 -> -Rs 610,878 | -Rs 481,738 -> -Rs 224,667 | Rs 3,277,448 -> Rs 3,028,032 | Rs 7,656 |
| X6 | design | -Rs 825,777 -> -Rs 500,531 | -Rs 481,738 -> -Rs 128,630 | Rs 3,277,448 -> Rs 2,730,535 | -Rs 193,147 |
| X7 | design | -Rs 825,777 -> -Rs 620,622 | -Rs 481,738 -> -Rs 373,005 | Rs 3,277,448 -> Rs 2,182,765 | Rs 41,636 |
| X8 | design | -Rs 825,777 -> -Rs 756,684 | -Rs 481,738 -> -Rs 400,680 | Rs 3,277,448 -> Rs 3,181,698 | -Rs 14,691 |
| X9 | design | -Rs 825,777 -> -Rs 641,877 | -Rs 481,738 -> -Rs 291,412 | Rs 3,277,448 -> Rs 2,963,411 | -Rs 123,078 |
| X10 | design | -Rs 825,777 -> -Rs 718,470 | -Rs 481,738 -> -Rs 370,241 | Rs 3,277,448 -> Rs 3,136,787 | -Rs 29,163 |
| X1 | final | -Rs 182,911 -> -Rs 136,922 | -Rs 92,333 -> -Rs 41,241 | Rs 1,236,817 -> Rs 1,166,423 | -Rs 19,301 |
| X2 | final | -Rs 182,911 -> -Rs 165,810 | -Rs 92,333 -> -Rs 75,233 | Rs 1,236,817 -> Rs 1,209,075 | -Rs 10,641 |
| X3 | final | -Rs 182,911 -> -Rs 159,402 | -Rs 92,333 -> -Rs 68,824 | Rs 1,236,817 -> Rs 1,188,947 | -Rs 24,360 |
| X4 | final | -Rs 182,911 -> -Rs 17,201 | -Rs 92,333 -> -Rs 19,899 | Rs 1,236,817 -> Rs 1,035,144 | -Rs 35,963 |
| X5 | final | -Rs 182,911 -> -Rs 148,267 | -Rs 92,333 -> -Rs 48,735 | Rs 1,236,817 -> Rs 1,165,365 | -Rs 27,853 |
| X6 | final | -Rs 182,911 -> -Rs 117,082 | -Rs 92,333 -> -Rs 24,458 | Rs 1,236,817 -> Rs 1,125,245 | -Rs 43,696 |
| X7 | final | -Rs 182,911 -> -Rs 130,863 | -Rs 92,333 -> -Rs 75,938 | Rs 1,236,817 -> Rs 867,427 | Rs 17,874 |
| X8 | final | -Rs 182,911 -> -Rs 173,746 | -Rs 92,333 -> -Rs 82,139 | Rs 1,236,817 -> Rs 1,225,450 | -Rs 1,172 |
| X9 | final | -Rs 182,911 -> -Rs 144,184 | -Rs 92,333 -> -Rs 53,606 | Rs 1,236,817 -> Rs 1,132,225 | -Rs 65,864 |
| X10 | final | -Rs 182,911 -> -Rs 162,642 | -Rs 92,333 -> -Rs 72,064 | Rs 1,236,817 -> Rs 1,204,475 | -Rs 12,073 |

### Paper bootstrap (percentile of the paper net; P(net <= paper))

| paper | from | iid trades | consecutive trades | 6 consecutive sessions |
|---|---|---|---|---|
| B: 19 trades, -Rs 11,997 | final | 4.6 (n 20000) | 7.7 (n 1652) | 20.3 (n 128) |
| B: 19 trades, -Rs 11,997 | wf | 6.6 (n 20000) | 12.0 (n 3026) | 21.5 (n 242) |
| B14: 14 trades, -Rs 16,188 | final | 0.5 (n 20000) | 2.3 (n 1657) | 13.3 (n 128) |
| B14: 14 trades, -Rs 16,188 | wf | 1.1 (n 20000) | 4.1 (n 3031) | 17.4 (n 242) |
| B14plain: 14 trades, -Rs 5,591 | final | 18.1 (n 20000) | 22.9 (n 1657) | 31.2 (n 128) |
| B14plain: 14 trades, -Rs 5,591 | wf | 20.4 (n 20000) | 25.9 (n 3031) | 30.6 (n 242) |

### OOS 14-19 Oct (4 sessions; 46 trades; never gated)

| rule | trades | net |
|---|---|---|
| baseline | 46 | Rs 3,412 |
| X1 | 46 | -Rs 2,312 |
| X2 | 46 | Rs 2,524 |
| X3 | 46 | -Rs 97 |
| X4 | 46 | Rs 637 |
| X5 | 46 | Rs 3,598 |
| X6 | 48 | -Rs 2,660 |
| X7 | 48 | -Rs 10,319 |
| X8 | 46 | Rs 3,412 |
| X9 | 46 | Rs 2,480 |
| X10 | 46 | Rs 3,280 |

