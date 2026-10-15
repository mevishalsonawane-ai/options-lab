# R3 Step 2: anatomy of the ORB family (design indices BANKNIFTY + NIFTY only)

Mean net Rs per trade (n, win rate, total). Day type and 'closed inside the range' need the whole day: descriptive only, never a filter. Every other split is known at the signal.


## ORB (2,489 trades)


**day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| range | 1,047 | -76 | 38% | -79,778 |
| trend down | 664 | -83 | 38% | -55,317 |
| trend up | 778 | -113 | 33% | -87,945 |

**day closed inside the opening range (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| inside | 779 | -149 | 35% | -116,316 |
| outside | 1,710 | -62 | 37% | -106,724 |

**trade direction vs day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| against the trend | 348 | -247 | 28% | -85,992 |
| range day | 1,047 | -76 | 38% | -79,778 |
| with the trend | 1,094 | -52 | 37% | -57,270 |

**vix_prev terciles (cuts 13 / 16)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 826 | -98 | 35% | -80,545 |
| mid | 831 | -65 | 40% | -53,768 |
| high | 829 | -108 | 33% | -89,533 |
| n/a | 3 | +268 | 100% | +805 |

**vix_chg terciles (cuts -0.929 / 0.939)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 829 | -91 | 35% | -75,522 |
| mid | 827 | -108 | 36% | -89,693 |
| high | 830 | -70 | 37% | -58,181 |
| n/a | 3 | +119 | 67% | +356 |

**gap_dir terciles (cuts -0.158 / 0.151)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 830 | -125 | 34% | -103,722 |
| mid | 829 | -84 | 36% | -69,792 |
| high | 830 | -60 | 39% | -49,525 |

**width_atr terciles (cuts 0.376 / 0.517)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 829 | -84 | 36% | -69,814 |
| mid | 830 | -109 | 36% | -90,124 |
| high | 830 | -76 | 37% | -63,103 |

**signal time**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 10:00 hour | 1,439 | -121 | 35% | -173,910 |
| 11:00 hour | 503 | -84 | 36% | -42,340 |
| 12:00 hour | 311 | +28 | 42% | +8,844 |
| 13:00 hour | 236 | -66 | 36% | -15,634 |

**expiry day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| expiry | 447 | -61 | 37% | -27,187 |
| not expiry | 2,042 | -96 | 36% | -195,854 |

**twap_dir terciles (cuts 0.174 / 0.254)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 830 | -127 | 32% | -105,262 |
| mid | 829 | -79 | 38% | -65,895 |
| high | 830 | -63 | 40% | -51,883 |

**index**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| BANKNIFTY | 1,207 | -103 | 38% | -123,935 |
| NIFTY | 1,282 | -77 | 35% | -99,106 |

**side**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| CE (up) | 1,253 | -85 | 35% | -106,328 |
| PE (down) | 1,236 | -94 | 37% | -116,712 |

**entry number that day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 1 | 2,231 | -103 | 35% | -230,646 |
| 2 | 258 | +29 | 45% | +7,606 |

**year**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 2021 | 187 | -43 | 36% | -8,040 |
| 2022 | 506 | -154 | 30% | -77,971 |
| 2023 | 473 | -129 | 33% | -60,935 |
| 2024 | 484 | -49 | 40% | -23,940 |
| 2025 | 464 | -50 | 41% | -23,366 |
| 2026 | 375 | -77 | 37% | -28,789 |

**exit**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| lock | 1,668 | +39 | 36% | +64,846 |
| square_off | 3 | -591 | 0% | -1,774 |
| stop | 511 | -1,131 | 0% | -578,164 |
| target | 307 | +951 | 97% | +292,052 |

## ORB Fresh (2,489 trades)


**day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| range | 1,047 | -77 | 38% | -80,550 |
| trend down | 664 | -83 | 38% | -55,317 |
| trend up | 778 | -113 | 33% | -87,945 |

**day closed inside the opening range (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| inside | 779 | -149 | 35% | -116,316 |
| outside | 1,710 | -63 | 37% | -107,495 |

**trade direction vs day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| against the trend | 348 | -247 | 28% | -85,992 |
| range day | 1,047 | -77 | 38% | -80,550 |
| with the trend | 1,094 | -52 | 37% | -57,270 |

**vix_prev terciles (cuts 13 / 16)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 826 | -98 | 35% | -81,317 |
| mid | 831 | -65 | 40% | -53,768 |
| high | 829 | -108 | 33% | -89,532 |
| n/a | 3 | +268 | 100% | +805 |

**vix_chg terciles (cuts -0.929 / 0.939)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 829 | -91 | 35% | -75,522 |
| mid | 827 | -108 | 36% | -89,693 |
| high | 830 | -71 | 37% | -58,953 |
| n/a | 3 | +119 | 67% | +356 |

**gap_dir terciles (cuts -0.158 / 0.151)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 830 | -125 | 34% | -103,722 |
| mid | 829 | -85 | 36% | -70,564 |
| high | 830 | -60 | 39% | -49,525 |

**width_atr terciles (cuts 0.376 / 0.517)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 829 | -85 | 36% | -70,585 |
| mid | 830 | -109 | 36% | -90,124 |
| high | 830 | -76 | 37% | -63,103 |

**signal time**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 10:00 hour | 1,438 | -121 | 35% | -174,674 |
| 11:00 hour | 503 | -84 | 36% | -42,340 |
| 12:00 hour | 312 | +28 | 42% | +8,837 |
| 13:00 hour | 236 | -66 | 36% | -15,634 |

**expiry day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| expiry | 447 | -63 | 37% | -27,958 |
| not expiry | 2,042 | -96 | 36% | -195,853 |

**twap_dir terciles (cuts 0.174 / 0.254)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 830 | -127 | 32% | -105,497 |
| mid | 829 | -79 | 38% | -65,733 |
| high | 830 | -63 | 40% | -52,581 |

**index**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| BANKNIFTY | 1,207 | -103 | 38% | -123,935 |
| NIFTY | 1,282 | -78 | 35% | -99,877 |

**side**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| CE (up) | 1,253 | -85 | 35% | -106,328 |
| PE (down) | 1,236 | -95 | 37% | -117,484 |

**entry number that day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 1 | 2,231 | -103 | 35% | -230,646 |
| 2 | 258 | +26 | 45% | +6,834 |

**year**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 2021 | 187 | -43 | 36% | -8,040 |
| 2022 | 506 | -154 | 30% | -77,970 |
| 2023 | 473 | -130 | 33% | -61,707 |
| 2024 | 484 | -49 | 40% | -23,940 |
| 2025 | 464 | -50 | 41% | -23,366 |
| 2026 | 375 | -77 | 37% | -28,789 |

**exit**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| lock | 1,669 | +39 | 36% | +64,838 |
| square_off | 3 | -591 | 0% | -1,774 |
| stop | 511 | -1,131 | 0% | -578,164 |
| target | 306 | +952 | 97% | +291,289 |

## ORB Sweep (3,156 trades)


**day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| range | 1,368 | -122 | 29% | -167,405 |
| trend down | 833 | -176 | 26% | -146,213 |
| trend up | 955 | -201 | 28% | -192,336 |

**day closed inside the opening range (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| inside | 1,162 | -1 | 32% | -1,592 |
| outside | 1,994 | -253 | 25% | -504,362 |

**trade direction vs day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| against the trend | 1,260 | -427 | 19% | -537,403 |
| range day | 1,368 | -122 | 29% | -167,405 |
| with the trend | 528 | +377 | 47% | +198,854 |

**vix_prev terciles (cuts 13.2 / 16.3)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,046 | -165 | 28% | -172,549 |
| mid | 1,052 | -173 | 27% | -181,583 |
| high | 1,054 | -144 | 29% | -151,678 |
| n/a | 4 | -36 | 25% | -144 |

**vix_chg terciles (cuts -1.09 / 0.829)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,051 | -173 | 26% | -181,530 |
| mid | 1,050 | -167 | 28% | -175,107 |
| high | 1,051 | -140 | 29% | -147,172 |
| n/a | 4 | -536 | 0% | -2,144 |

**gap_dir terciles (cuts -0.138 / 0.164)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,052 | -132 | 29% | -138,984 |
| mid | 1,052 | -146 | 27% | -153,736 |
| high | 1,052 | -203 | 27% | -213,234 |

**width_atr terciles (cuts 0.383 / 0.526)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,052 | -201 | 26% | -211,414 |
| mid | 1,052 | -112 | 29% | -117,447 |
| high | 1,052 | -168 | 28% | -177,093 |

**signal time**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 10:00 hour | 1,384 | -172 | 27% | -237,544 |
| 11:00 hour | 735 | -138 | 29% | -101,622 |
| 12:00 hour | 480 | -233 | 27% | -111,666 |
| 13:00 hour | 368 | -90 | 27% | -32,943 |
| 14:00 hour | 189 | -117 | 33% | -22,178 |

**expiry day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| expiry | 378 | -185 | 25% | -70,092 |
| not expiry | 2,778 | -157 | 28% | -435,862 |

**twap_dir terciles (cuts -0.142 / -0.0724)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,052 | -157 | 27% | -165,233 |
| mid | 1,052 | -157 | 28% | -165,021 |
| high | 1,052 | -167 | 29% | -175,700 |

**index**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| BANKNIFTY | 1,677 | -179 | 27% | -299,428 |
| NIFTY | 1,479 | -140 | 28% | -206,526 |

**side**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| CE (up) | 1,576 | -191 | 28% | -300,612 |
| PE (down) | 1,580 | -130 | 28% | -205,342 |

**entry number that day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 1 | 2,038 | -158 | 28% | -321,277 |
| 2 | 1,118 | -165 | 28% | -184,677 |

**year**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 2021 | 224 | -187 | 27% | -41,990 |
| 2022 | 648 | -160 | 27% | -103,369 |
| 2023 | 577 | -157 | 28% | -90,341 |
| 2024 | 613 | -71 | 28% | -43,666 |
| 2025 | 596 | -263 | 26% | -156,904 |
| 2026 | 498 | -140 | 31% | -69,683 |

**exit**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| lock | 1,601 | +220 | 38% | +352,582 |
| square_off | 167 | -259 | 25% | -43,261 |
| stale | 1 | -294 | 0% | -294 |
| stop | 1,154 | -1,131 | 0% | -1,304,621 |
| target | 233 | +2,101 | 100% | +489,640 |

## Range Fade (3,512 trades)


**day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| range | 1,504 | -120 | 31% | -180,524 |
| trend down | 929 | -155 | 30% | -143,716 |
| trend up | 1,079 | -173 | 29% | -186,738 |

**day closed inside the opening range (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| inside | 1,333 | -19 | 36% | -25,960 |
| outside | 2,179 | -223 | 27% | -485,018 |

**trade direction vs day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| against the trend | 1,414 | -275 | 24% | -388,716 |
| range day | 1,504 | -120 | 31% | -180,524 |
| with the trend | 594 | +98 | 42% | +58,262 |

**vix_prev terciles (cuts 13.1 / 16.3)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,165 | -161 | 29% | -188,055 |
| mid | 1,174 | -138 | 31% | -161,920 |
| high | 1,169 | -138 | 30% | -161,166 |
| n/a | 4 | +41 | 25% | +162 |

**vix_chg terciles (cuts -1.15 / 0.756)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,170 | -151 | 30% | -176,641 |
| mid | 1,169 | -150 | 29% | -175,819 |
| high | 1,169 | -136 | 31% | -158,676 |
| n/a | 4 | +39 | 25% | +157 |

**gap_dir terciles (cuts -0.155 / 0.155)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,171 | -145 | 29% | -169,671 |
| mid | 1,170 | -147 | 33% | -171,688 |
| high | 1,171 | -145 | 30% | -169,619 |

**width_atr terciles (cuts 0.386 / 0.539)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,170 | -141 | 29% | -165,268 |
| mid | 1,170 | -151 | 30% | -176,156 |
| high | 1,172 | -145 | 32% | -169,554 |

**signal time**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 10:00 hour | 1,329 | -140 | 30% | -185,696 |
| 11:00 hour | 1,271 | -153 | 30% | -194,607 |
| 12:00 hour | 582 | -154 | 31% | -89,800 |
| 13:00 hour | 330 | -124 | 29% | -40,875 |

**expiry day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| expiry | 431 | -155 | 30% | -66,929 |
| not expiry | 3,081 | -144 | 30% | -444,049 |

**twap_dir terciles (cuts -0.123 / -0.0571)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 1,171 | -159 | 29% | -186,674 |
| mid | 1,170 | -132 | 31% | -154,773 |
| high | 1,171 | -145 | 30% | -169,531 |

**index**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| BANKNIFTY | 1,843 | -155 | 30% | -286,033 |
| NIFTY | 1,669 | -135 | 30% | -224,946 |

**side**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| CE (up) | 1,696 | -149 | 30% | -253,291 |
| PE (down) | 1,816 | -142 | 30% | -257,688 |

**entry number that day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 1 | 2,075 | -156 | 30% | -324,312 |
| 2 | 1,437 | -130 | 30% | -186,666 |

**year**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 2021 | 250 | -189 | 28% | -47,142 |
| 2022 | 719 | -129 | 28% | -92,427 |
| 2023 | 652 | -134 | 29% | -87,301 |
| 2024 | 633 | -84 | 30% | -53,391 |
| 2025 | 674 | -225 | 33% | -151,403 |
| 2026 | 584 | -136 | 32% | -79,313 |

**exit**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| lock | 2,317 | +73 | 33% | +169,603 |
| square_off | 58 | -545 | 3% | -31,590 |
| stale | 2 | -185 | 0% | -370 |
| stop | 838 | -1,137 | 0% | -952,729 |
| target | 297 | +1,024 | 99% | +304,108 |

## ORB (no cap) (17,923 trades)


**day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| range | 7,291 | -120 | 35% | -871,712 |
| trend down | 5,055 | -64 | 38% | -324,304 |
| trend up | 5,577 | -71 | 37% | -394,753 |

**day closed inside the opening range (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| inside | 4,187 | -203 | 31% | -851,997 |
| outside | 13,736 | -54 | 38% | -738,772 |

**trade direction vs day type (descriptive)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| against the trend | 1,664 | -265 | 27% | -441,663 |
| range day | 7,291 | -120 | 35% | -871,712 |
| with the trend | 8,968 | -31 | 40% | -277,394 |

**vix_prev terciles (cuts 13.5 / 16.4)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 5,965 | -68 | 37% | -407,056 |
| mid | 5,972 | -87 | 38% | -518,608 |
| high | 5,977 | -111 | 36% | -665,800 |
| n/a | 9 | +77 | 44% | +695 |

**vix_chg terciles (cuts -0.825 / 1.66)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 5,968 | -104 | 36% | -621,713 |
| mid | 5,971 | -81 | 37% | -485,965 |
| high | 5,970 | -81 | 37% | -484,252 |
| n/a | 14 | +83 | 43% | +1,161 |

**gap_dir terciles (cuts -0.162 / 0.129)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 5,966 | -91 | 36% | -543,048 |
| mid | 5,980 | -85 | 37% | -508,399 |
| high | 5,977 | -90 | 37% | -539,323 |

**width_atr terciles (cuts 0.385 / 0.53)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 5,975 | -91 | 36% | -545,020 |
| mid | 5,973 | -81 | 37% | -483,824 |
| high | 5,975 | -94 | 37% | -561,925 |

**signal time**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 10:00 hour | 3,282 | -124 | 34% | -407,970 |
| 11:00 hour | 4,335 | -91 | 36% | -396,376 |
| 12:00 hour | 4,796 | -70 | 38% | -338,079 |
| 13:00 hour | 5,510 | -81 | 38% | -448,343 |

**expiry day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| expiry | 3,617 | -69 | 37% | -250,150 |
| not expiry | 14,306 | -94 | 37% | -1,340,620 |

**twap_dir terciles (cuts 0.162 / 0.279)**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| low | 5,975 | -91 | 35% | -544,904 |
| mid | 5,973 | -79 | 37% | -471,136 |
| high | 5,975 | -96 | 38% | -574,729 |

**index**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| BANKNIFTY | 9,492 | -98 | 37% | -927,236 |
| NIFTY | 8,431 | -79 | 37% | -663,533 |

**side**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| CE (up) | 8,579 | -91 | 37% | -778,900 |
| PE (down) | 9,344 | -87 | 37% | -811,869 |

**entry number that day**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 1 | 2,231 | -103 | 35% | -230,646 |
| 2 | 2,021 | -94 | 36% | -189,848 |
| 3 | 1,856 | -91 | 36% | -169,754 |
| 4 | 11,815 | -85 | 37% | -1,000,522 |

**year**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| 2021 | 1,288 | -95 | 36% | -122,660 |
| 2022 | 3,529 | -87 | 37% | -307,650 |
| 2023 | 2,840 | -67 | 37% | -188,971 |
| 2024 | 4,183 | -67 | 36% | -279,769 |
| 2025 | 3,358 | -113 | 37% | -379,532 |
| 2026 | 2,725 | -115 | 37% | -312,187 |

**exit**

| bucket | trades | mean Rs/trade | win rate | total Rs |
|---|---|---|---|---|
| lock | 11,807 | +44 | 37% | +518,518 |
| square_off | 15 | -306 | 13% | -4,597 |
| stop | 3,862 | -1,139 | 0% | -4,400,160 |
| target | 2,239 | +1,025 | 99% | +2,295,470 |

## Wide opening ranges and 'no break by 14:00' (Boss's 13 Oct BANKNIFTY case: a 630-point range)

| index | width / ATR14 | sessions | no ORB break by 13:55 | of those, broke 14:00-15:10 |
|---|---|---|---|---|
| BANKNIFTY | <0.4 | 421 | 26 (6%) | 8 |
| BANKNIFTY | 0.4-0.6 | 492 | 68 (14%) | 30 |
| BANKNIFTY | 0.6-0.8 | 222 | 41 (18%) | 13 |
| BANKNIFTY | >=0.8 | 119 | 33 (28%) | 9 |
| NIFTY | <0.4 | 479 | 38 (8%) | 15 |
| NIFTY | 0.4-0.6 | 511 | 49 (10%) | 20 |
| NIFTY | 0.6-0.8 | 188 | 24 (13%) | 10 |
| NIFTY | >=0.8 | 105 | 26 (25%) | 6 |
