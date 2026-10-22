# R101 report: entry filters and sizing on the arms with a real record (tables)

Net after charges and slippage, 1 lot (B5: as stated). Win rate and net side by side; a higher win rate with a lower net fails.

## Liquidity 15+5 (BANKNIFTY 15+5, FINNIFTY 30+5, MIDCPNIFTY 15+5)

B0 parity (design): 881 trades, +Rs 257,249, published [881, 257249]: reproduced

### Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | walk-forward net | wf vs B0 | twin p | |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 881 | 44% | +Rs 257,249 | | +Rs 292 | -Rs 8,416 | -Rs 32,537 | +Rs 175,714 | +Rs 238,064 | | | 
| L1 BREADTH3: >= 3 of 5 indices' 5-min bars closed the signal's way | 733 | 44% | +Rs 197,895 | -Rs 59,354 | +Rs 270 | -Rs 5,926 | -Rs 29,171 | +Rs 125,841 | +Rs 191,749 | -Rs 46,315 | 0.8024 | 
| L2 VIXDIR: VIX up >= 1% since open for a put / down for a call | 411 | 48% | +Rs 203,113 | -Rs 54,136 | +Rs 494 | -Rs 5,583 | -Rs 32,941 | +Rs 123,434 | +Rs 211,337 | -Rs 26,727 | 0.022 | 
| L3 RVHI: realised-vol rank high | 380 | 43% | +Rs 146,868 | -Rs 110,382 | +Rs 386 | -Rs 8,416 | -Rs 32,818 | +Rs 65,332 | +Rs 157,779 | -Rs 80,285 | 0.2076 | 
| L3b RVLO: realised-vol rank low | 501 | 44% | +Rs 110,382 | -Rs 146,868 | +Rs 220 | -Rs 6,363 | -Rs 21,420 | +Rs 59,491 | +Rs 80,285 | -Rs 157,779 | 0.8962 | 
| L4 DOW: drop the weekday worst in design (walk-forward pick) | 815 | 43% | +Rs 247,812 | -Rs 9,438 | +Rs 304 | -Rs 8,416 | -Rs 32,537 | +Rs 166,276 | +Rs 228,626 | -Rs 9,438 | 0.3453 | 

B0 parity (final): 357 trades, +Rs 11,155, published [357, 11155]: reproduced

### Final 1 Apr - 13 Oct 2026 (read once)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | twin p | q (all) | q (arm) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 357 | 37% | +Rs 11,155 | | +Rs 31 | -Rs 11,405 | -Rs 61,062 | -Rs 51,376 | | | | |
| L1 BREADTH3: >= 3 of 5 indices' 5-min bars closed the signal's way | 303 | 37% | +Rs 115 | -Rs 11,040 | Rs 0 | -Rs 7,193 | -Rs 48,652 | -Rs 49,010 | 0.7445 | 1.0 | 0.9202 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10 |
| L2 VIXDIR: VIX up >= 1% since open for a put / down for a call | 244 | 40% | +Rs 49,067 | +Rs 37,912 | +Rs 201 | -Rs 11,405 | -Rs 35,155 | -Rs 13,464 | 0.02 | 0.4 | 0.1 | fail: walk-forward not above B0; BH q >= 0.10 |
| L3 RVHI: realised-vol rank high | 243 | 35% | -Rs 21,162 | -Rs 32,317 | -Rs 87 | -Rs 11,405 | -Rs 61,062 | -Rs 69,324 | 0.9202 | 1.0 | 0.9202 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| L3b RVLO: realised-vol rank low | 114 | 41% | +Rs 32,317 | +Rs 21,162 | +Rs 283 | -Rs 3,985 | -Rs 14,380 | -Rs 12,981 | 0.0739 | 0.739 | 0.1847 | fail: walk-forward not above B0; twin p >= 0.05; BH q >= 0.10 |
| L4 DOW: drop the weekday worst in design (walk-forward pick) | 288 | 40% | +Rs 18,997 | +Rs 7,842 | +Rs 66 | -Rs 11,405 | -Rs 46,892 | -Rs 34,677 | 0.2834 | 1.0 | 0.4723 | fail: walk-forward not above B0; twin p >= 0.05; BH q >= 0.10 |

## R72 Breadth Donchian

B0 parity (design): 3071 trades, +Rs 216,929, published [3071, 216929]: reproduced

### Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | walk-forward net | wf vs B0 | twin p | |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 3071 | 47% | +Rs 216,929 | | +Rs 71 | -Rs 58,613 | -Rs 199,077 | +Rs 160,476 | +Rs 335,882 | | | 
| B1 VIXLO: previous VIX close <= 60-session median | 1535 | 46% | +Rs 119,155 | -Rs 97,774 | +Rs 78 | -Rs 23,307 | -Rs 86,529 | +Rs 71,723 | +Rs 74,210 | -Rs 261,672 | 0.1337 | 
| B1b VIXHI: previous VIX close > 60-session median | 1536 | 47% | +Rs 97,774 | -Rs 119,155 | +Rs 64 | -Rs 58,613 | -Rs 188,424 | +Rs 41,320 | +Rs 261,671 | -Rs 74,211 | 0.2595 | 
| B2 GAP: the index's gap within +-0.5% | 2434 | 46% | +Rs 156,609 | -Rs 60,321 | +Rs 64 | -Rs 58,613 | -Rs 125,914 | +Rs 100,996 | +Rs 218,090 | -Rs 117,792 | 0.5609 | 
| B3 NO0920: not the opening bar's break | 2770 | 45% | +Rs 67,734 | -Rs 149,195 | +Rs 24 | -Rs 58,613 | -Rs 151,053 | +Rs 11,280 | +Rs 179,207 | -Rs 156,675 | 1.0 | 
| B4 AM: entries by 12:00 | 1614 | 48% | +Rs 205,942 | -Rs 10,987 | +Rs 128 | -Rs 44,106 | -Rs 113,195 | +Rs 156,215 | +Rs 226,201 | -Rs 109,681 | 0.004 | 
| B5 SIZE55: 2 lots when 5 of 5 break (per lot) | 3071 | 47% | +Rs 483,542 (4536 lots; +Rs 107 a lot vs +Rs 71) | +Rs 110,442 at B0 lots | +Rs 157 | -Rs 116,519 | -Rs 288,829 | +Rs 377,431 | +Rs 577,523 | +Rs 55,073 | 0.0639 | 

B0 parity (final): 1202 trades, +Rs 171,366, published [1202, 171366]: reproduced

### Final 1 Apr - 13 Oct 2026 (read once)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | twin p | q (all) | q (arm) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1202 | 46% | +Rs 171,366 | | +Rs 143 | -Rs 22,571 | -Rs 61,884 | +Rs 112,623 | | | | |
| B1 VIXLO: previous VIX close <= 60-session median | 797 | 45% | +Rs 111,187 | -Rs 60,179 | +Rs 140 | -Rs 22,571 | -Rs 80,820 | +Rs 52,997 | 0.7166 | 1.0 | 0.986 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| B1b VIXHI: previous VIX close > 60-session median | 405 | 48% | +Rs 60,179 | -Rs 111,187 | +Rs 149 | -Rs 20,728 | -Rs 44,252 | +Rs 21,774 | 0.8403 | 1.0 | 0.986 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| B2 GAP: the index's gap within +-0.5% | 750 | 44% | +Rs 102,363 | -Rs 69,003 | +Rs 136 | -Rs 17,394 | -Rs 57,857 | +Rs 62,578 | 0.8124 | 1.0 | 0.986 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| B3 NO0920: not the opening bar's break | 1016 | 43% | +Rs 95,288 | -Rs 76,078 | +Rs 94 | -Rs 21,306 | -Rs 62,952 | +Rs 27,665 | 0.986 | 1.0 | 0.986 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| B4 AM: entries by 12:00 | 599 | 47% | +Rs 89,799 | -Rs 81,567 | +Rs 150 | -Rs 16,564 | -Rs 42,800 | +Rs 53,749 | 0.5549 | 1.0 | 0.986 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| B5 SIZE55: 2 lots when 5 of 5 break (per lot) | 1202 | 46% | +Rs 299,848 (1862 lots; +Rs 161 a lot vs +Rs 143) | +Rs 22,199 at B0 lots | +Rs 249 | -Rs 44,435 | -Rs 111,858 | +Rs 183,309 | 0.475 | 1.0 | 0.986 | fail: twin p >= 0.05; BH q >= 0.10 |

## R51 Donchian 20

B0 parity (design): 4498 trades, +Rs 9,998, published [4498, 9998]: reproduced

### Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | walk-forward net | wf vs B0 | twin p | |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 4498 | 45% | +Rs 9,998 | | +Rs 2 | -Rs 56,647 | -Rs 224,592 | -Rs 57,344 | +Rs 175,789 | | | 
| C1 VIXLO (Donchian) | 2311 | 45% | -Rs 4,386 | -Rs 14,384 | -Rs 2 | -Rs 29,576 | -Rs 147,311 | -Rs 60,408 | +Rs 50,266 | -Rs 125,523 | 0.0539 | 
| C1b VIXHI (Donchian) | 2187 | 45% | +Rs 14,384 | +Rs 4,386 | +Rs 7 | -Rs 56,647 | -Rs 134,424 | -Rs 46,955 | +Rs 125,523 | -Rs 50,266 | 0.0419 | 
| C2 GAP (Donchian) | 3602 | 45% | +Rs 43,012 | +Rs 33,014 | +Rs 12 | -Rs 56,647 | -Rs 123,379 | -Rs 23,924 | +Rs 129,309 | -Rs 46,480 | 0.0559 | 
| C3 NO0920 (Donchian) | 4291 | 44% | -Rs 77,188 | -Rs 87,186 | -Rs 18 | -Rs 56,647 | -Rs 175,120 | -Rs 145,505 | +Rs 70,461 | -Rs 105,328 | 0.996 | 
| C4 AM (Donchian) | 2769 | 46% | +Rs 56,693 | +Rs 46,694 | +Rs 20 | -Rs 34,807 | -Rs 166,692 | -Rs 8,463 | +Rs 140,952 | -Rs 34,837 | 0.012 | 

B0 parity (final): 1781 trades, +Rs 81,845, published [1781, 81845]: reproduced

### Final 1 Apr - 13 Oct 2026 (read once)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | twin p | q (all) | q (arm) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1781 | 44% | +Rs 81,845 | | +Rs 46 | -Rs 23,012 | -Rs 91,626 | +Rs 29,590 | | | | |
| C1 VIXLO (Donchian) | 1265 | 44% | +Rs 28,538 | -Rs 53,307 | +Rs 23 | -Rs 17,259 | -Rs 92,648 | -Rs 23,717 | 0.8802 | 1.0 | 1.0 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| C1b VIXHI (Donchian) | 516 | 45% | +Rs 53,307 | -Rs 28,538 | +Rs 103 | -Rs 23,012 | -Rs 29,918 | +Rs 21,291 | 0.3932 | 1.0 | 1.0 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| C2 GAP (Donchian) | 1199 | 43% | +Rs 48,703 | -Rs 33,142 | +Rs 41 | -Rs 17,259 | -Rs 87,424 | +Rs 13,877 | 0.6866 | 1.0 | 1.0 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| C3 NO0920 (Donchian) | 1672 | 43% | -Rs 15,599 | -Rs 97,444 | -Rs 9 | -Rs 21,178 | -Rs 114,672 | -Rs 67,854 | 1.0 | 1.0 | 1.0 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| C4 AM (Donchian) | 1061 | 45% | +Rs 17,487 | -Rs 64,358 | +Rs 16 | -Rs 15,054 | -Rs 81,955 | -Rs 16,336 | 0.7345 | 1.0 | 1.0 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |

## R52 Open outside value

B0 parity (design): 706 trades, +Rs 290,573, published [None, 290573]: reproduced

### Design 20 Nov 2024 - 31 Mar 2026 (walk-forward window Apr 2025 - Mar 2026)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | walk-forward net | wf vs B0 | twin p | |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 706 | 56% | +Rs 290,573 | | +Rs 412 | -Rs 25,140 | -Rs 82,713 | +Rs 230,722 | +Rs 247,057 | | | 
| D1 VIXLO (OOV) | 358 | 57% | +Rs 104,797 | -Rs 185,777 | +Rs 293 | -Rs 25,140 | -Rs 81,723 | +Rs 54,427 | +Rs 104,108 | -Rs 142,949 | 0.8164 | 
| D1b VIXHI (OOV) | 348 | 54% | +Rs 185,777 | -Rs 104,797 | +Rs 534 | -Rs 18,590 | -Rs 43,406 | +Rs 127,814 | +Rs 142,948 | -Rs 104,109 | 0.1737 | 
| D2 FAR: open >= 25% of the value width beyond the edge | 589 | 56% | +Rs 291,751 | +Rs 1,178 | +Rs 495 | -Rs 25,140 | -Rs 70,939 | +Rs 231,899 | +Rs 255,810 | +Rs 8,753 | 0.0858 | 
| D3 OPEN3: >= 3 of 5 indices open outside value the same way | 521 | 57% | +Rs 274,819 | -Rs 15,754 | +Rs 527 | -Rs 25,140 | -Rs 50,810 | +Rs 220,719 | +Rs 218,443 | -Rs 28,614 | 0.0719 | 

B0 parity (final): 293 trades, +Rs 48,612, published [None, 48612]: reproduced

### Final 1 Apr - 13 Oct 2026 (read once)

| variant | trades | win | net | vs B0 | per trade | worst day | max DD | without 5 best | twin p | q (all) | q (arm) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 293 | 52% | +Rs 48,612 | | +Rs 166 | -Rs 16,554 | -Rs 47,194 | +Rs 9,031 | | | | |
| D1 VIXLO (OOV) | 217 | 52% | +Rs 29,554 | -Rs 19,058 | +Rs 136 | -Rs 16,554 | -Rs 47,194 | -Rs 7,783 | 0.6208 | 1.0 | 0.8277 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| D1b VIXHI (OOV) | 76 | 51% | +Rs 19,058 | -Rs 29,554 | +Rs 251 | -Rs 14,796 | -Rs 16,300 | -Rs 13,870 | 0.3453 | 1.0 | 0.8277 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| D2 FAR: open >= 25% of the value width beyond the edge | 249 | 53% | +Rs 40,315 | -Rs 8,297 | +Rs 162 | -Rs 16,554 | -Rs 65,095 | +Rs 3,707 | 0.5269 | 1.0 | 0.8277 | fail: final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |
| D3 OPEN3: >= 3 of 5 indices open outside value the same way | 239 | 49% | +Rs 12,049 | -Rs 36,563 | +Rs 50 | -Rs 16,554 | -Rs 53,275 | -Rs 26,510 | 0.9481 | 1.0 | 0.9481 | fail: walk-forward not above B0; final not above B0; twin p >= 0.05; BH q >= 0.10; carried by its 5 best trades |

## Decision

0 of 20 pass every gate.
