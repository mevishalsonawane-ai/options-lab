### Calendar spreads (idea1)

| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | capital for median trade at 2% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| c-C1-GOLDM-2611-2612 | 21 | 29% | -11,973 | -4,721 | -4,721 | -10,850 | -20,350 | 0.031 | 0.70 | n, net, q, walk_forward, wo_top5 | 2 | -1,567 | -2,789 | 0.452 | 0% | 232,443 |
| c-C1-GOLDM-2612-2701 | 10 | 20% | -16,557 | -5,784 | -10,847 | -8,307 | -16,557 | 0.721 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 3 | -4,351 | -2,977 | 0.746 | 0% | 269,784 |
| c-C1-GOLDM-2701-2702 | 12 | 50% | -4,010 | -2,120 | -3,679 | -4,010 | -6,246 | 0.026 | 0.70 | n, net, q, walk_forward, wo_top5 | 8 | -1,563 | -1,552 | 0.016 | 0% | 266,784 |
| c-C1-SILVER-2612-2703 | 4 | 75% | 30,951 | -48,593 | -48,593 | -45,225 | -48,593 | 0.128 | 1.00 | month_stop, n, p, q, walk_forward | 3 | -366 | -531 | 0.219 | 0% | 3,003,894 |
| c-C1-SILVERM-2611-2702 | 13 | 46% | -15,535 | -14,349 | -23,454 | -18,734 | -38,364 | 0.446 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 3 | -3,611 | -2,636 | 0.826 | 0% | 530,314 |
| c-C1-SILVERM-2702-2704 | 8 | 50% | 13,078 | -9,826 | -9,826 | -16,535 | -19,089 | 0.026 | 0.70 | month_stop, n, q | 3 | -1,590 | -1,421 | 0.452 | 0% | 369,365 |
| c-Z1-CRUDEOIL-2610-2611 | 12 | 50% | -18,767 | -6,062 | -6,062 | -18,767 | -21,671 | 0.666 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 28 | -64,887 | -56,936 | 0.916 | 0% | 214,220 |
| c-Z1-CRUDEOIL-2611-2612 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 26 | -34,225 | -32,775 | 0.467 | - | - |
| c-Z1-CRUDEOILM-2610-2611 | 33 | 18% | -7,984 | -1,230 | -1,700 | -5,350 | -7,984 | 0.628 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 27 | -5,582 | -4,776 | 0.465 | 100% | 21,800 |
| c-Z1-CRUDEOILM-2611-2612 | 13 | 15% | -2,322 | -640 | -974 | -2,100 | -2,322 | 0.204 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 38 | -7,299 | -5,536 | 0.271 | 100% | 26,025 |
| c-Z1-CRUDEOILM-2612-2701 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 34 | -6,543 | -6,027 | 0.177 | - | - |
| c-Z1-GOLDM-2611-2612 | 74 | 14% | -102,906 | -5,275 | -6,742 | -54,609 | -102,906 | 0.583 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 68 | -72,149 | -55,808 | 0.005 | 3% | 134,348 |
| c-Z1-GOLDM-2612-2701 | 45 | 18% | -56,222 | -5,961 | -10,192 | -53,956 | -56,222 | 0.236 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 67 | -94,033 | -62,225 | 0.756 | 0% | 144,489 |
| c-Z1-GOLDM-2701-2702 | 18 | 44% | -2,453 | -5,104 | -3,757 | -2,453 | -8,275 | 0.001 | 0.15 | n, net, q, walk_forward, wo_top5 | 51 | -70,645 | -39,020 | 0.566 | 0% | 194,787 |
| c-Z1-NATGASMINI-2610-2611 | 41 | 32% | -11,174 | -1,349 | -1,500 | -4,801 | -11,174 | 0.584 | 1.00 | net, p, q, walk_forward, wo_top5 | 35 | -7,983 | -6,449 | 0.545 | 98% | 40,146 |
| c-Z1-NATGASMINI-2611-2612 | 16 | 19% | -4,214 | -1,077 | -1,077 | -3,864 | -4,214 | 0.519 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 47 | -9,564 | -5,965 | 0.158 | 100% | 39,541 |
| c-Z1-NATGASMINI-2612-2701 | 1 | 100% | 174 | 174 | 174 | 174 | -0 | 0.116 | 1.00 | n, p, q | 33 | -5,294 | -4,295 | 0.056 | 100% | 53,254 |
| c-Z1-NATURALGAS-2610-2611 | 21 | 38% | -18,854 | -4,484 | -4,484 | -16,655 | -23,495 | 0.589 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 35 | -22,444 | -21,045 | 0.383 | 0% | 165,546 |
| c-Z1-NATURALGAS-2611-2612 | 5 | 20% | -5,123 | -4,001 | -4,001 | -4,250 | -5,123 | 0.511 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 37 | -33,489 | -21,785 | 0.736 | 0% | 187,306 |
| c-Z1-SILVER-2612-2703 | 6 | 33% | -38,917 | -27,861 | -27,861 | -38,917 | -72,208 | 0.479 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 40 | -156,300 | -84,822 | 0.020 | 0% | 1,391,811 |
| c-Z1-SILVERM-2611-2702 | 74 | 31% | -97,097 | -14,100 | -14,100 | -34,492 | -97,128 | 0.638 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 68 | -69,061 | -40,226 | 0.345 | 0% | 158,266 |
| c-Z1-SILVERM-2702-2704 | 24 | 17% | -47,316 | -9,627 | -9,387 | -40,801 | -48,112 | 0.970 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 53 | -56,768 | -36,643 | 0.369 | 0% | 192,262 |
| c-Z2-CRUDEOIL-2610-2611 | 1 | 0% | -68 | -68 | -68 | -68 | -68 | 0.355 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 8 | -23,724 | -13,236 | 0.706 | 0% | 282,892 |
| c-Z2-CRUDEOIL-2611-2612 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 3 | -15,902 | -15,902 | 0.921 | - | - |
| c-Z2-CRUDEOILM-2610-2611 | 7 | 71% | -558 | -562 | -1,094 | -558 | -1,094 | 0.137 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 8 | -3,821 | -2,608 | 0.825 | 100% | 25,192 |
| c-Z2-CRUDEOILM-2611-2612 | 1 | 0% | -911 | -911 | -911 | -911 | -911 | 0.993 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 12 | -3,418 | -1,764 | 0.726 | 100% | 36,103 |
| c-Z2-CRUDEOILM-2612-2701 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 6 | -1,402 | -1,665 | 0.504 | - | - |
| c-Z2-GOLDM-2611-2612 | 27 | 26% | -45,295 | -6,555 | -7,002 | -38,466 | -45,295 | 0.913 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 23 | -31,311 | -27,252 | 0.689 | 0% | 138,537 |
| c-Z2-GOLDM-2612-2701 | 23 | 22% | -36,109 | -5,961 | -10,408 | -36,109 | -36,109 | 0.635 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 33 | -45,876 | -26,412 | 0.627 | 0% | 158,635 |
| c-Z2-GOLDM-2701-2702 | 1 | 100% | 2,581 | 2,581 | 2,581 | 2,581 | -0 | 0.017 | 0.70 | n, q | 19 | -22,423 | -14,492 | 0.280 | 0% | 207,538 |
| c-Z2-NATGASMINI-2610-2611 | 8 | 38% | -2,825 | -1,572 | -1,572 | -2,181 | -4,279 | 0.684 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 6 | 244 | -1,687 | 0.205 | 75% | 61,976 |
| c-Z2-NATGASMINI-2611-2612 | 3 | 67% | 49 | -725 | -725 | 49 | -725 | 0.105 | 1.00 | n, p, q | 10 | -474 | -424 | 0.089 | 100% | 40,522 |
| c-Z2-NATGASMINI-2612-2701 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 4 | -877 | -1,402 | 0.483 | - | - |
| c-Z2-NATURALGAS-2610-2611 | 4 | 25% | -10,046 | -5,604 | -5,604 | -5,604 | -10,046 | 0.815 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 7 | 8,636 | -5,298 | 0.087 | 0% | 306,908 |
| c-Z2-NATURALGAS-2611-2612 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 11 | -6,087 | -6,479 | 0.328 | - | - |
| c-Z2-SILVER-2612-2703 | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 22 | -34,573 | -28,260 | 0.002 | - | - |
| c-Z2-SILVERM-2611-2702 | 18 | 50% | -18,031 | -6,338 | -8,802 | -13,627 | -26,963 | 0.431 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 27 | -21,227 | -16,672 | 0.030 | 0% | 231,557 |
| c-Z2-SILVERM-2702-2704 | 2 | 50% | -2,736 | -3,287 | -3,287 | -2,736 | -3,287 | 0.530 | 1.00 | n, net, p, q, walk_forward, wo_top5 | 29 | -23,640 | -12,942 | 0.073 | 0% | 379,045 |

### Sector-index pairing (proxies) (idea2)

| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | capital for median trade at 2% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| p-CA-W120 | 48 | 54% | 297,202 | -151,792 | -151,792 | -139,709 | -368,614 | 0.124 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | 24 | 104,126 | -146,667 | 0.266 | 0% | 2,504,342 |
| p-CA-W60 | 67 | 51% | 101,307 | -168,267 | -168,267 | -163,251 | -294,833 | 0.268 | 1.00 | month_stop, p, q, wo_top5 | 37 | -63,949 | -127,009 | 0.486 | 0% | 1,748,079 |
| p-CN-W120 | 44 | 36% | -705,128 | -257,932 | -257,932 | -546,653 | -1,865,701 | 0.777 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 18 | -47,754 | -390,936 | 0.516 | 0% | 7,692,142 |
| p-CN-W60 | 68 | 47% | -229,137 | -205,920 | -205,920 | -551,858 | -1,204,072 | 0.580 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 28 | 1,207,330 | -390,936 | 0.102 | 0% | 5,417,796 |
| p-CZ-W120 | 51 | 47% | 193,152 | -130,583 | -130,583 | -130,583 | -342,734 | 0.180 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | 23 | -126,762 | -137,794 | 0.651 | 0% | 2,528,307 |
| p-CZ-W60 | 71 | 56% | 124,960 | -197,213 | -197,213 | -197,213 | -521,613 | 0.254 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | 32 | -217,922 | -109,150 | 0.728 | 0% | 1,750,081 |
| p-GS-W120 | 50 | 38% | -354,044 | -83,953 | -83,953 | -224,248 | -476,793 | 0.876 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 21 | -740,116 | -386,391 | 0.997 | 0% | 2,287,587 |
| p-GS-W60 | 68 | 44% | -626,347 | -183,450 | -183,450 | -183,450 | -667,916 | 0.961 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | 33 | -168,790 | -281,059 | 0.676 | 0% | 1,518,683 |
| p-ZA-W120 | 49 | 47% | 144,900 | -100,296 | -100,296 | -100,296 | -280,589 | 0.227 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | 27 | -23,193 | -104,849 | 0.465 | 0% | 2,705,152 |
| p-ZA-W60 | 70 | 51% | 254,190 | -133,823 | -133,823 | -133,823 | -346,057 | 0.128 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | 36 | -9,170 | -86,875 | 0.400 | 0% | 1,854,124 |

### Option selling (idea3) - OUT OF SCOPE (Boss, 21 Oct): design only, holdout not read

| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | capital for median trade at 2% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| o-CC-CRUDEOIL-CY-ALL | 7 | 43% | -21,999 | -8,575 | -8,575 | -8,575 | -21,999 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 286,372 |
| o-CC-CRUDEOIL-CY-HIGH | 3 | 33% | -12,174 | -8,575 | -8,575 | -8,575 | -12,174 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 292,457 |
| o-CC-CRUDEOIL-D1-ALL | 149 | 60% | -92,785 | -12,860 | -13,454 | -39,229 | -117,883 | 0.962 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 287,894 |
| o-CC-CRUDEOIL-D1-HIGH | 67 | 69% | 14,734 | -12,860 | -12,860 | -18,381 | -41,012 | 0.358 | 1.00 | month_stop, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 299,795 |
| o-CC-GOLDM-CY-ALL | 6 | 17% | -91,210 | -45,741 | -45,741 | -45,741 | -91,210 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 666,396 |
| o-CC-GOLDM-CY-HIGH | 1 | 100% | 2,819 | 2,819 | 2,819 | 2,819 | -0 | 1.000 | 1.00 | n, p, q | - | - | - | - | 0% | 650,272 |
| o-CC-GOLDM-D1-ALL | 110 | 51% | -446,447 | -54,163 | -71,518 | -171,092 | -481,729 | 0.997 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 659,241 |
| o-CC-GOLDM-D1-HIGH | 20 | 55% | -32,922 | -17,339 | -17,339 | -18,443 | -38,718 | 0.927 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 629,660 |
| o-CC-NATURALGAS-CY-ALL | 8 | 25% | -24,574 | -5,673 | -5,673 | -5,673 | -25,738 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 200,356 |
| o-CC-NATURALGAS-CY-HIGH | 4 | 25% | -12,007 | -5,673 | -5,673 | -5,673 | -14,265 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 212,375 |
| o-CC-NATURALGAS-D1-ALL | 148 | 57% | -89,480 | -6,374 | -9,903 | -32,457 | -103,685 | 0.975 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 193,483 |
| o-CC-NATURALGAS-D1-HIGH | 59 | 49% | -64,200 | -5,692 | -5,692 | -32,457 | -64,200 | 0.996 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 211,364 |
| o-CC-SILVERM-CY-ALL | 6 | 33% | -17,605 | -9,726 | -9,726 | -9,726 | -19,266 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 454,139 |
| o-CC-SILVERM-CY-HIGH | 3 | 33% | -12,510 | -9,726 | -9,726 | -9,726 | -12,510 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 416,154 |
| o-CC-SILVERM-D1-ALL | 108 | 43% | -651,564 | -190,692 | -190,692 | -306,142 | -659,440 | 0.984 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 406,330 |
| o-CC-SILVERM-D1-HIGH | 28 | 54% | -65,621 | -23,067 | -23,067 | -38,640 | -84,993 | 0.955 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 401,061 |
| o-IC-CRUDEOIL-CY-ALL | 7 | 43% | -10,504 | -10,291 | -10,291 | -10,291 | -12,768 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 14% | 142,735 |
| o-IC-CRUDEOIL-CY-HIGH | 3 | 33% | -11,461 | -10,291 | -10,291 | -10,291 | -11,461 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 33% | 106,280 |
| o-IC-CRUDEOIL-D1-ALL | 149 | 5% | -242,943 | -9,360 | -9,360 | -101,031 | -242,943 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 4% | 132,306 |
| o-IC-CRUDEOIL-D1-HIGH | 67 | 1% | -168,965 | -9,360 | -9,360 | -101,031 | -168,965 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 9% | 120,110 |
| o-IC-CRUDEOILM-CY-ALL | 7 | 14% | -2,689 | -1,237 | -1,237 | -1,237 | -2,754 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 22,116 |
| o-IC-CRUDEOILM-CY-HIGH | 3 | 0% | -2,059 | -1,237 | -1,237 | -1,237 | -2,059 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 18,685 |
| o-IC-CRUDEOILM-D1-ALL | 149 | 1% | -49,811 | -1,167 | -1,167 | -13,213 | -49,811 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 21,565 |
| o-IC-CRUDEOILM-D1-HIGH | 67 | 1% | -27,365 | -1,167 | -1,167 | -13,213 | -27,365 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 19,808 |
| o-IC-GOLDM-CY-ALL | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IC-GOLDM-CY-HIGH | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IC-GOLDM-D1-ALL | 3 | 0% | -12,288 | -4,554 | -4,554 | -8,386 | -12,288 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 140,466 |
| o-IC-GOLDM-D1-HIGH | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IC-NATGASMINI-CY-ALL | 8 | 12% | -4,982 | -1,211 | -1,211 | -1,211 | -5,069 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 36,391 |
| o-IC-NATGASMINI-CY-HIGH | 4 | 0% | -2,665 | -1,211 | -1,211 | -1,211 | -2,665 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 35,157 |
| o-IC-NATGASMINI-D1-ALL | 146 | 1% | -88,896 | -1,432 | -1,432 | -15,086 | -88,896 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 38,422 |
| o-IC-NATGASMINI-D1-HIGH | 58 | 2% | -37,666 | -1,432 | -1,432 | -12,079 | -37,666 | 1.000 | 1.00 | net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 36,307 |
| o-IC-NATURALGAS-CY-ALL | 8 | 12% | -21,387 | -5,352 | -5,352 | -5,352 | -22,477 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 149,804 |
| o-IC-NATURALGAS-CY-HIGH | 4 | 0% | -11,686 | -5,352 | -5,352 | -5,352 | -11,686 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 143,765 |
| o-IC-NATURALGAS-D1-ALL | 147 | 1% | -352,201 | -6,286 | -6,286 | -61,035 | -352,201 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 156,852 |
| o-IC-NATURALGAS-D1-HIGH | 58 | 0% | -159,662 | -6,286 | -6,286 | -48,965 | -159,662 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 149,085 |
| o-IC-SILVERM-CY-ALL | 1 | 0% | -1,484 | -1,484 | -1,484 | -1,484 | -1,484 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 52,000 |
| o-IC-SILVERM-CY-HIGH | 1 | 0% | -1,484 | -1,484 | -1,484 | -1,484 | -1,484 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 52,000 |
| o-IC-SILVERM-D1-ALL | 21 | 38% | -2,561 | -1,660 | -1,660 | -4,184 | -9,261 | 0.500 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 30,833 |
| o-IC-SILVERM-D1-HIGH | 6 | 17% | -4,110 | -1,660 | -1,660 | -2,572 | -4,825 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 47,632 |
| o-IF-CRUDEOIL-CY-ALL | 7 | 43% | -12,651 | -10,515 | -10,515 | -10,515 | -13,736 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 79,566 |
| o-IF-CRUDEOIL-CY-HIGH | 3 | 33% | -11,479 | -10,515 | -10,515 | -10,515 | -11,479 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 67,933 |
| o-IF-CRUDEOIL-D1-ALL | 149 | 3% | -181,598 | -9,811 | -9,811 | -74,605 | -181,598 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 72% | 82,648 |
| o-IF-CRUDEOIL-D1-HIGH | 67 | 1% | -121,605 | -9,811 | -9,811 | -74,605 | -121,605 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 70% | 80,204 |
| o-IF-CRUDEOILM-CY-ALL | 7 | 0% | -2,575 | -1,332 | -1,332 | -1,332 | -2,575 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 15,963 |
| o-IF-CRUDEOILM-CY-HIGH | 3 | 0% | -1,833 | -1,332 | -1,332 | -1,332 | -1,833 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 15,654 |
| o-IF-CRUDEOILM-D1-ALL | 148 | 1% | -42,219 | -698 | -698 | -9,595 | -42,219 | 1.000 | 1.00 | net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 16,775 |
| o-IF-CRUDEOILM-D1-HIGH | 66 | 2% | -22,105 | -698 | -698 | -9,595 | -22,105 | 1.000 | 1.00 | net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 16,561 |
| o-IF-GOLDM-CY-ALL | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IF-GOLDM-CY-HIGH | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IF-GOLDM-D1-ALL | 4 | 0% | -9,241 | -2,862 | -5,307 | -5,307 | -9,241 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 79,692 |
| o-IF-GOLDM-D1-HIGH | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-IF-NATGASMINI-CY-ALL | 8 | 12% | -2,077 | -955 | -955 | -955 | -3,764 | 0.551 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 26,767 |
| o-IF-NATGASMINI-CY-HIGH | 4 | 0% | -2,390 | -955 | -955 | -955 | -2,390 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 26,421 |
| o-IF-NATGASMINI-D1-ALL | 146 | 0% | -71,733 | -949 | -949 | -11,156 | -71,733 | 1.000 | 1.00 | net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 27,451 |
| o-IF-NATGASMINI-D1-HIGH | 57 | 0% | -30,887 | -949 | -949 | -9,713 | -30,887 | 1.000 | 1.00 | net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 26,948 |
| o-IF-NATURALGAS-CY-ALL | 8 | 25% | -5,335 | -3,823 | -3,823 | -3,823 | -14,844 | 0.433 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 38% | 100,905 |
| o-IF-NATURALGAS-CY-HIGH | 4 | 0% | -9,585 | -3,823 | -3,823 | -3,823 | -9,585 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 50% | 96,953 |
| o-IF-NATURALGAS-D1-ALL | 147 | 0% | -251,326 | -3,998 | -3,998 | -43,033 | -251,326 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 39% | 102,980 |
| o-IF-NATURALGAS-D1-HIGH | 58 | 0% | -112,597 | -3,998 | -3,998 | -34,501 | -112,597 | 1.000 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 50% | 100,206 |
| o-IF-SILVERM-CY-ALL | 2 | 50% | 247 | -1,429 | -1,429 | -1,429 | -1,429 | 1.000 | 1.00 | n, p, q, walk_forward | - | - | - | - | 100% | 42,162 |
| o-IF-SILVERM-CY-HIGH | 1 | 0% | -1,429 | -1,429 | -1,429 | -1,429 | -1,429 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 54,599 |
| o-IF-SILVERM-D1-ALL | 26 | 38% | -7,746 | -2,580 | -2,580 | -3,034 | -9,935 | 0.936 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 35,761 |
| o-IF-SILVERM-D1-HIGH | 7 | 29% | -4,922 | -1,495 | -1,495 | -2,789 | -6,112 | 1.000 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 100% | 54,599 |
| o-NS-CRUDEOIL-CY-ALL | 7 | 71% | -37,483 | -97,629 | -97,629 | -97,629 | -97,629 | 1.000 | 1.00 | month_stop, n, net, p, q, wo_top5 | - | - | - | - | 0% | 1,313,806 |
| o-NS-CRUDEOIL-CY-HIGH | 3 | 33% | -74,365 | -97,629 | -97,629 | -97,629 | -97,629 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,874,130 |
| o-NS-CRUDEOIL-D1-ALL | 149 | 61% | -532,912 | -149,006 | -149,006 | -505,246 | -586,130 | 0.930 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,547,607 |
| o-NS-CRUDEOIL-D1-HIGH | 67 | 48% | -554,557 | -149,006 | -149,006 | -505,246 | -579,456 | 0.991 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 2,305,839 |
| o-NS-CRUDEOILM-CY-ALL | 7 | 86% | -1,690 | -10,008 | -10,008 | -10,008 | -10,008 | 0.878 | 1.00 | n, net, p, q, wo_top5 | - | - | - | - | 0% | 137,473 |
| o-NS-CRUDEOILM-CY-HIGH | 3 | 67% | -5,073 | -10,008 | -10,008 | -10,008 | -10,008 | 1.000 | 1.00 | n, net, p, q, wo_top5 | - | - | - | - | 0% | 197,693 |
| o-NS-CRUDEOILM-D1-ALL | 149 | 46% | -59,714 | -15,008 | -15,008 | -49,693 | -60,843 | 0.952 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 20% | 160,429 |
| o-NS-CRUDEOILM-D1-HIGH | 67 | 43% | -53,064 | -15,008 | -15,008 | -49,693 | -57,558 | 0.961 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 12% | 237,424 |
| o-NS-GOLDM-CY-ALL | 2 | 50% | 19,213 | -5,206 | -5,206 | -5,206 | -5,206 | 1.000 | 1.00 | n, p, q, walk_forward | - | - | - | - | 0% | 1,792,001 |
| o-NS-GOLDM-CY-HIGH | 0 | - | 0 | - | - | - | 0 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | - | - |
| o-NS-GOLDM-D1-ALL | 31 | 48% | -72,472 | -74,747 | -74,747 | -99,218 | -244,123 | 0.679 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 2,150,305 |
| o-NS-GOLDM-D1-HIGH | 2 | 50% | 2,539 | -1,757 | -1,757 | -1,757 | -1,757 | 1.000 | 1.00 | n, p, q, walk_forward | - | - | - | - | 0% | 2,864,170 |
| o-NS-NATGASMINI-CY-ALL | 8 | 75% | -7,335 | -10,832 | -10,832 | -10,832 | -20,522 | 0.608 | 1.00 | n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 457,651 |
| o-NS-NATGASMINI-CY-HIGH | 4 | 75% | -657 | -9,689 | -9,689 | -9,689 | -9,689 | 1.000 | 1.00 | n, net, p, q, wo_top5 | - | - | - | - | 0% | 457,651 |
| o-NS-NATGASMINI-D1-ALL | 147 | 46% | -63,640 | -9,457 | -13,210 | -22,954 | -65,060 | 0.981 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 383,354 |
| o-NS-NATGASMINI-D1-HIGH | 58 | 48% | -24,993 | -8,680 | -13,210 | -22,954 | -38,745 | 0.821 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 417,238 |
| o-NS-NATURALGAS-CY-ALL | 8 | 75% | -42,799 | -52,025 | -52,025 | -52,025 | -103,488 | 0.670 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 2,253,830 |
| o-NS-NATURALGAS-CY-HIGH | 4 | 75% | -6,154 | -51,463 | -51,463 | -51,463 | -51,463 | 1.000 | 1.00 | month_stop, n, net, p, q, wo_top5 | - | - | - | - | 0% | 2,253,830 |
| o-NS-NATURALGAS-D1-ALL | 148 | 53% | -341,732 | -46,634 | -65,729 | -104,128 | -354,024 | 0.995 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,873,020 |
| o-NS-NATURALGAS-D1-HIGH | 59 | 56% | -171,892 | -43,461 | -65,729 | -104,128 | -189,566 | 0.941 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 2,112,386 |
| o-NS-SILVERM-CY-ALL | 3 | 0% | -119,511 | -54,923 | -54,923 | -54,923 | -119,511 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,960,248 |
| o-NS-SILVERM-CY-HIGH | 1 | 0% | -39,894 | -39,894 | -39,894 | -39,894 | -39,894 | 1.000 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,960,248 |
| o-NS-SILVERM-D1-ALL | 59 | 49% | -262,424 | -136,414 | -136,414 | -191,338 | -472,836 | 0.913 | 1.00 | month_stop, net, p, q, walk_forward, wo_top5 | - | - | - | - | 0% | 1,657,279 |
| o-NS-SILVERM-D1-HIGH | 19 | 42% | 42,510 | -58,771 | -58,771 | -103,784 | -111,465 | 0.492 | 1.00 | month_stop, n, p, q, walk_forward | - | - | - | - | 0% | 2,319,299 |

### Bullion seasonals (idea4)

| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | capital for median trade at 2% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| s-AT-PRE-GOLD | 10 | 80% | 40,607 | -4,740 | -4,740 | -4,740 | -4,740 | 0.092 | 1.00 | p, q | 5 | 64,625 | -11,449 | 0.313 | 0% | 348,169 |
| s-AT-PRE-SILVER | 10 | 40% | -16,473 | -8,459 | -8,459 | -8,459 | -28,352 | 0.624 | 1.00 | net, p, q, walk_forward, wo_top5 | 5 | 140,549 | -12,381 | 0.150 | 0% | 403,439 |
| s-DW-POST-GOLD | 10 | 60% | 44,734 | -11,218 | -11,218 | -11,218 | -11,218 | 0.037 | 0.70 | q | 4 | 77,248 | -10,161 | 0.006 | 0% | 290,510 |
| s-DW-POST-SILVER | 10 | 70% | 57,271 | -12,354 | -12,354 | -12,354 | -12,354 | 0.062 | 1.00 | p, q | 4 | 16,401 | -15,291 | 0.162 | 0% | 373,503 |
| s-DW-PRE-GOLD | 10 | 50% | -10,780 | -11,565 | -11,565 | -11,565 | -16,250 | 0.710 | 1.00 | net, p, q, walk_forward, wo_top5 | 4 | 89,205 | -12,604 | 0.072 | 0% | 345,015 |
| s-DW-PRE-SILVER | 10 | 60% | 12,147 | -6,504 | -6,504 | -6,504 | -11,364 | 0.285 | 1.00 | p, q, walk_forward | 4 | 38,549 | -18,062 | 0.355 | 0% | 465,228 |
| s-WED-GOLD | 59 | 42% | 89,602 | -13,543 | -13,543 | -13,543 | -53,906 | 0.241 | 1.00 | month_stop, p, q, wo_top5 | 28 | 573,106 | -20,043 | 0.094 | 0% | 300,149 |
| s-WED-SILVER | 59 | 37% | -27,684 | -19,300 | -19,300 | -19,300 | -95,657 | 0.474 | 1.00 | month_stop, net, p, q, wo_top5 | 28 | 503,942 | -294,772 | 0.168 | 0% | 371,613 |

### US overnight -> MCX open (idea5)

| cell | design n | win | net Rs | worst trade | worst day | worst month | max DD | p | q | design gate | holdout n | holdout net | holdout worst month | holdout p | fits 2% @ Rs 1L | capital for median trade at 2% |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| g-G1-CRUDEOILM | 20 | 50% | 5,002 | -987 | -987 | -1,143 | -2,669 | 0.365 | 1.00 | n, p, q, walk_forward | 13 | 8,220 | -2,624 | 0.335 | 100% | 38,640 |
| g-G1-GOLDM | 23 | 43% | -41,877 | -40,579 | -40,579 | -38,121 | -83,685 | 0.526 | 1.00 | month_stop, n, net, p, q, walk_forward, wo_top5 | 23 | 17,037 | -12,920 | 0.199 | 0% | 609,757 |
| g-G1-NATGASMINI | 9 | 44% | 8,164 | -3,312 | -3,312 | -2,577 | -4,023 | 0.271 | 1.00 | n, p, q, walk_forward | 12 | -2,176 | -1,165 | 0.705 | 22% | 164,622 |
| g-G1-SILVERM | 25 | 64% | 83,403 | -37,348 | -37,348 | -48,318 | -74,910 | 0.196 | 1.00 | month_stop, n, p, q, walk_forward | 21 | -9,006 | -30,835 | 0.488 | 0% | 656,206 |
| g-G2-CRUDEOILM | 58 | 45% | 8,827 | -1,372 | -1,372 | -1,364 | -3,787 | 0.084 | 1.00 | p, q, walk_forward, wo_top5 | 57 | 6,707 | -7,805 | 0.263 | 86% | 39,678 |
| g-G2-GOLDM | 67 | 60% | 113,304 | -30,029 | -30,029 | -1,287 | -43,740 | 0.037 | 0.70 | q, wo_top5 | 64 | -21,031 | -40,910 | 0.382 | 0% | 680,617 |
| g-G2-NATGASMINI | 29 | 69% | 73,995 | -3,261 | -3,261 | -218 | -3,261 | 0.003 | 0.23 | n, q, walk_forward | 68 | 3,249 | -2,963 | 0.013 | 31% | 129,545 |
| g-G2-SILVERM | 56 | 57% | 65,688 | -42,469 | -42,469 | -38,733 | -91,883 | 0.245 | 1.00 | month_stop, p, q, wo_top5 | 62 | 4,467 | -29,818 | 0.350 | 0% | 816,656 |
