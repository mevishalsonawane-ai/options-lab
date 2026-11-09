# R152: sell the Donchian arms at +Rs 2,000 or -Rs 2,000 (7 Nov 2026)

## For Boss, in short

**No. Selling at +Rs 2,000 / -Rs 2,000 makes both Donchian arms worse, at 1, 2 and 3 lots. Keep today's exits.**
All 18 tests failed the gates. Nothing changes in the app.

Apr - Oct 2026 (the test period, 133 sessions), whole position, after all charges and slippage:

| arm | lots | today's rules | +2k / -2k | +2k only | -2k only |
|---|---|---|---|---|---|
| R72 Breadth Donchian | 1 | **Rs 1,71,366** | -Rs 39,539 | -Rs 22,409 | Rs 1,37,432 |
| | 2 | **Rs 3,98,405** | -Rs 2,01,824 | -Rs 1,53,493 | Rs 2,30,871 |
| | 3 | **Rs 6,25,048** | -Rs 3,54,878 | -Rs 3,02,389 | Rs 1,71,399 |
| R51 Donchian 20 | 1 | **Rs 1,34,743** | -Rs 90,851 | -Rs 64,922 | Rs 91,538 |
| | 2 | **Rs 3,46,548** | -Rs 2,07,174 | -Rs 1,62,059 | Rs 1,40,376 |
| | 3 | **Rs 5,57,695** | -Rs 4,66,992 | -Rs 3,65,237 | Rs 1,44,937 |

(R72: 1,202 trades today; R51: 1,670. The earlier 16 months, Nov 2024 - Mar 2026, say the same: every rule makes less
than today's rules at every lot size.)

**Why it fails.** These arms win only about 46% of trades. They make their money on a few big moves that run far past
Rs 2,000; a +Rs 2,000 cap cuts exactly those. With +2k / -2k the wins and losses are about the same size, so after charges the arm
needs about 47% winners to break even at 1 lot and 50-51% at 2-3 lots, and it gets 44-47%. A +2k cap alone lifts the win rate to 56-64% at 2-3 lots,
but the losers stay big (the -15% stop), so it needs 58-67% to break even.

**The -Rs 2,000 stop alone** is the only one that stays in profit. It does cut the worst day (R72, 3 lots: -Rs 32,476
instead of -Rs 66,325), but it gives up much more profit than it saves (R72, 3 lots: Rs 1.71 lakh instead of Rs 6.25
lakh). It fails the "better than today" gate everywhere.

**With R151's pickers** (only the trades the app's pickers take, Apr - Oct 2026, a second look): the same answer. R72
picks at 1 lot: today Rs 1,69,674, +2k / -2k Rs 33,979; at 3 lots: Rs 5,64,570 vs -Rs 1,67,722. R51 picks at 1 lot:
Rs 1,42,884 vs -Rs 10,763.

**One side note for Boss (not part of the test).** In the app, a break the picker skips still holds its side for as long
as the trade would have lasted (the safe rule chosen on 6 Nov). On Apr - Oct 2026 that leaves R72's picker with 603 trades
and Rs 1,69,674, against R151's research figure of 652 trades and Rs 2,19,057 (R51: 653 / Rs 1,42,884 against
693 / Rs 1,59,040). The drawdown is still about half the plain arm's (R72 Rs 31,620 vs Rs 61,884). This is described, not
tested; it may be worth its own study.

## Method (honest notes)

- Pre-registered in `PREREG.md` (committed before any R152 number). R106's harness and engine; R72's and R51's trades
  reproduce their published figures exactly at 1 lot (R72 3,071 / Rs 2,16,929 design and 1,202 / Rs 1,71,366 final; R51
  with Supertrend 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743; R106's older R51 4,498 / Rs 9,998 also reproduced).
- Signal source: the index (unchanged). Only the option's exit changes: a limit sell at +Rs 2,000 net and / or a stop at
  -Rs 2,000 net on the premium, for the whole position. The arm's own -15% stop stays; the nearer stop acts (Boss's
  "whichever comes first"). At 2-3 lots the Rs 2,000 stop is the nearer one on 95-99% of trades; at 1 lot on 63-65%.
- Today's rules (B0) were re-priced at 2 and 3 lots with the same exits, real charges of the whole quantity and slippage
  from the size of the order. Every rule is compared with B0 at its own lots.
- Gates (R106's): net > 0 in design, walk-forward and final; better than today's rules; beats 500 random-exit twins
  with Benjamini-Hochberg q <= 0.10 (m = 866: R152's 18 + 848 earlier tests); >= 40 final trades. The final period was read
  once by R152, but Apr - Oct 2026 has been read by many earlier studies (weak evidence either way; here the result is
  one-sided).
- The post window (14 - 19 Oct, 4 sessions, 25-52 trades) is reported in the tables and never gated.

## Every table

Net after dated Zerodha charges and R17 slippage, for the whole position. Rs 2,000 is for the position at every lot size. 'wf' = walk-forward window Apr 2025 - Mar 2026 (inside design). Break-even win = average loss / (average win + average loss) of the rule's own trades. Exit-twin p: final period, 500 random-exit twins. BH q counts R152's 18 tests plus 848 earlier ones (m = 866). Signal source: the index (unchanged); only the option's exit changes.

### R72 Breadth Donchian

B0 parity at 1 lot (design): 3071 trades, Rs 216,929; published [3071, 216929]: reproduced. Final: 1202 trades, Rs 171,366; reproduced.
 Sessions: design 337, final 133, post 4.

#### 1 lot

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | 44% | Rs 216,929 | Rs 644 | -Rs 58,613 | -Rs 199,077 | Rs 160,476 | -Rs 11,511 | 0 / 0 / 568 / 2503 |  |
|  | wf | 2238 | 49% | 44% | Rs 335,882 | Rs 1,360 | -Rs 25,837 | -Rs 86,529 | Rs 279,428 | -Rs 11,511 | 0 / 0 / 365 / 1873 |  |
|  | final | 1202 | 46% | 41% | Rs 171,366 | Rs 1,288 | -Rs 22,571 | -Rs 61,884 | Rs 112,623 | -Rs 6,245 | 0 / 0 / 174 / 1028 |  |
|  | post | 25 | 56% | 57% | -Rs 624 | -Rs 156 | -Rs 18,451 | -Rs 18,451 | -Rs 13,429 | -Rs 4,513 | 0 / 0 / 6 / 19 |  |
| **T2000** | design | 3213 | 50% | 49% | Rs 110,021 | Rs 326 | -Rs 50,485 | -Rs 182,206 | Rs 99,988 | -Rs 11,511 | 962 / 0 / 569 / 1682 |  |
|  | wf | 2327 | 52% | 48% | Rs 221,284 | Rs 896 | -Rs 28,349 | -Rs 69,749 | Rs 211,251 | -Rs 11,511 | 681 / 0 / 364 / 1282 |  |
|  | final | 1241 | 47% | 48% | -Rs 22,409 | -Rs 168 | -Rs 22,571 | -Rs 138,691 | -Rs 32,438 | -Rs 6,245 | 309 / 0 / 189 / 743 | p 0.002, q 0.866 (R152 alone 0.018); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.87 > 0.10 |
|  | post | 27 | 52% | 57% | -Rs 3,982 | -Rs 996 | -Rs 18,451 | -Rs 18,451 | -Rs 13,992 | -Rs 4,513 | 8 / 0 / 8 / 11 |  |
| **T2000S** | design | 3214 | 48% | 47% | Rs 110,517 | Rs 328 | -Rs 25,138 | -Rs 131,916 | Rs 100,485 | -Rs 9,603 | 927 / 537 / 355 / 1395 |  |
|  | wf | 2327 | 50% | 47% | Rs 180,201 | Rs 730 | -Rs 18,875 | -Rs 57,903 | Rs 170,169 | -Rs 9,603 | 654 / 348 / 244 / 1081 |  |
|  | final | 1243 | 46% | 47% | -Rs 39,539 | -Rs 297 | -Rs 20,744 | -Rs 113,586 | -Rs 49,568 | -Rs 5,139 | 303 / 200 / 118 / 622 | p 0.9701, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 27 | 44% | 53% | -Rs 7,299 | -Rs 1,825 | -Rs 16,798 | -Rs 16,798 | -Rs 17,309 | -Rs 4,513 | 8 / 5 / 5 / 9 |  |
| **S2000** | design | 3073 | 45% | 42% | Rs 214,754 | Rs 637 | -Rs 29,142 | -Rs 156,495 | Rs 158,301 | -Rs 9,603 | 0 / 547 / 345 / 2181 |  |
|  | wf | 2238 | 47% | 42% | Rs 305,209 | Rs 1,236 | -Rs 20,898 | -Rs 73,890 | Rs 248,755 | -Rs 9,603 | 0 / 347 / 240 / 1651 |  |
|  | final | 1204 | 45% | 41% | Rs 137,432 | Rs 1,033 | -Rs 20,744 | -Rs 49,716 | Rs 78,688 | -Rs 5,139 | 0 / 189 / 114 / 901 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 25 | 48% | 53% | -Rs 3,940 | -Rs 985 | -Rs 16,798 | -Rs 16,798 | -Rs 16,745 | -Rs 4,513 | 0 / 5 / 3 / 17 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 85,063, Rs 70,225, Rs 9,774, Rs 170,821 ; T2000: Rs 68,366, Rs 46,381, Rs 29,713, Rs 76,824 ; T2000S: Rs 53,240, Rs 23,259, Rs 36,847, Rs 66,855 ; S2000: Rs 78,497, Rs 47,159, Rs 19,469, Rs 160,083.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 63%, S2000 63%.

#### 2 lots

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | 44% | Rs 576,488 | Rs 1,711 | -Rs 116,525 | -Rs 375,471 | Rs 463,345 | -Rs 22,974 | 0 / 0 / 568 / 2503 |  |
|  | wf | 2238 | 49% | 44% | Rs 775,437 | Rs 3,139 | -Rs 51,084 | -Rs 164,515 | Rs 662,293 | -Rs 22,974 | 0 / 0 / 365 / 1873 |  |
|  | final | 1202 | 46% | 41% | Rs 398,405 | Rs 2,996 | -Rs 44,447 | -Rs 111,087 | Rs 280,683 | -Rs 12,468 | 0 / 0 / 174 / 1028 |  |
|  | post | 25 | 56% | 56% | -Rs 121 | -Rs 30 | -Rs 36,430 | -Rs 36,430 | -Rs 25,950 | -Rs 8,979 | 0 / 0 / 6 / 19 |  |
| **T2000** | design | 3331 | 59% | 60% | -Rs 167,314 | -Rs 496 | -Rs 62,353 | -Rs 388,441 | -Rs 177,382 | -Rs 22,974 | 1731 / 0 / 509 / 1091 |  |
|  | wf | 2421 | 60% | 59% | Rs 138,789 | Rs 562 | -Rs 62,353 | -Rs 218,748 | Rs 128,720 | -Rs 22,974 | 1274 / 0 / 331 / 816 |  |
|  | final | 1266 | 56% | 58% | -Rs 153,493 | -Rs 1,154 | -Rs 50,763 | -Rs 282,641 | -Rs 163,552 | -Rs 12,468 | 609 / 0 / 172 / 485 | p 0.0639, q 1.0 (R152 alone 0.2875); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 69% | 67% | Rs 2,702 | Rs 676 | -Rs 32,722 | -Rs 32,722 | -Rs 7,330 | -Rs 8,979 | 18 / 0 / 6 / 5 |  |
| **T2000S** | design | 3338 | 47% | 50% | -Rs 396,106 | -Rs 1,175 | -Rs 27,949 | -Rs 404,315 | -Rs 406,175 | -Rs 19,158 | 1399 / 1454 / 59 / 426 |  |
|  | wf | 2425 | 49% | 50% | -Rs 128,356 | -Rs 520 | -Rs 26,998 | -Rs 144,536 | -Rs 138,425 | -Rs 19,158 | 1043 / 989 / 43 / 350 |  |
|  | final | 1268 | 46% | 50% | -Rs 201,824 | -Rs 1,517 | -Rs 22,544 | -Rs 229,418 | -Rs 211,882 | -Rs 8,388 | 513 / 560 / 6 / 189 | p 1.0, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 55% | 58% | -Rs 3,128 | -Rs 782 | -Rs 26,552 | -Rs 26,552 | -Rs 13,157 | -Rs 8,979 | 14 / 12 / 1 / 2 |  |
| **S2000** | design | 3125 | 35% | 34% | Rs 179,318 | Rs 532 | -Rs 31,949 | -Rs 357,430 | Rs 67,857 | -Rs 19,158 | 0 / 1644 / 57 / 1424 |  |
|  | wf | 2262 | 38% | 35% | Rs 359,037 | Rs 1,454 | -Rs 30,342 | -Rs 146,121 | Rs 247,576 | -Rs 19,158 | 0 / 1102 / 43 / 1117 |  |
|  | final | 1209 | 37% | 34% | Rs 230,871 | Rs 1,736 | -Rs 29,587 | -Rs 79,173 | Rs 118,073 | -Rs 8,388 | 0 / 608 / 5 / 596 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 25 | 44% | 47% | -Rs 3,567 | -Rs 892 | -Rs 26,552 | -Rs 26,552 | -Rs 27,162 | -Rs 8,979 | 0 / 13 / 1 / 11 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 193,621, Rs 165,640, Rs 47,537, Rs 368,638 ; T2000: Rs 81,585, Rs 68,603, -Rs 26,080, Rs 14,682 ; T2000S: -Rs 52,944, Rs 35,458, -Rs 83,492, -Rs 27,378 ; S2000: Rs 49,019, Rs 83,728, Rs 8,346, Rs 217,944.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 95%, S2000 95%.

#### 3 lots

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | 44% | Rs 934,016 | Rs 2,772 | -Rs 174,444 | -Rs 552,158 | Rs 764,182 | -Rs 34,438 | 0 / 0 / 568 / 2503 |  |
|  | wf | 2238 | 50% | 44% | Rs 1,213,421 | Rs 4,913 | -Rs 76,355 | -Rs 242,618 | Rs 1,043,587 | -Rs 34,438 | 0 / 0 / 365 / 1873 |  |
|  | final | 1202 | 47% | 41% | Rs 625,048 | Rs 4,700 | -Rs 66,325 | -Rs 160,424 | Rs 448,347 | -Rs 18,678 | 0 / 0 / 174 / 1028 |  |
|  | post | 25 | 56% | 56% | Rs 399 | Rs 100 | -Rs 54,418 | -Rs 54,418 | -Rs 38,462 | -Rs 13,453 | 0 / 0 / 6 / 19 |  |
| **T2000** | design | 3391 | 67% | 68% | -Rs 210,444 | -Rs 624 | -Rs 88,181 | -Rs 438,143 | -Rs 220,548 | -Rs 20,231 | 2176 / 0 / 432 / 783 |  |
|  | wf | 2466 | 68% | 67% | Rs 157,898 | Rs 639 | -Rs 79,069 | -Rs 287,106 | Rs 147,794 | -Rs 18,887 | 1593 / 0 / 279 / 594 |  |
|  | final | 1282 | 63% | 67% | -Rs 302,389 | -Rs 2,274 | -Rs 82,863 | -Rs 438,314 | -Rs 312,475 | -Rs 18,678 | 764 / 0 / 151 / 367 | p 0.9361, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 72% | 76% | -Rs 8,862 | -Rs 2,216 | -Rs 45,476 | -Rs 45,476 | -Rs 18,911 | -Rs 13,453 | 20 / 0 / 6 / 3 |  |
| **T2000S** | design | 3404 | 47% | 51% | -Rs 540,976 | -Rs 1,605 | -Rs 24,987 | -Rs 559,986 | -Rs 551,078 | -Rs 8,192 | 1550 / 1724 / 12 / 118 |  |
|  | wf | 2475 | 48% | 51% | -Rs 320,832 | -Rs 1,299 | -Rs 24,987 | -Rs 335,596 | -Rs 330,934 | -Rs 8,192 | 1148 / 1216 / 10 / 101 |  |
|  | final | 1286 | 44% | 51% | -Rs 354,878 | -Rs 2,668 | -Rs 21,523 | -Rs 364,925 | -Rs 364,964 | -Rs 11,250 | 549 / 664 / 0 / 73 | p 1.0, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 29 | 55% | 51% | Rs 4,537 | Rs 1,134 | -Rs 16,081 | -Rs 16,081 | -Rs 5,509 | -Rs 2,132 | 15 / 13 / 0 / 1 |  |
| **S2000** | design | 3172 | 27% | 27% | Rs 108,918 | Rs 323 | -Rs 45,897 | -Rs 431,934 | -Rs 57,551 | -Rs 28,714 | 0 / 2168 / 12 / 992 |  |
|  | wf | 2295 | 30% | 28% | Rs 295,500 | Rs 1,196 | -Rs 45,897 | -Rs 195,227 | Rs 129,030 | -Rs 28,714 | 0 / 1497 / 10 / 788 |  |
|  | final | 1223 | 29% | 27% | Rs 171,399 | Rs 1,289 | -Rs 32,476 | -Rs 135,145 | Rs 6,922 | -Rs 11,250 | 0 / 803 / 0 / 420 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 25 | 40% | 34% | Rs 9,162 | Rs 2,291 | -Rs 20,083 | -Rs 20,083 | -Rs 22,438 | -Rs 2,132 | 0 / 15 / 0 / 10 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 301,958, Rs 260,625, Rs 84,864, Rs 565,973 ; T2000: Rs 135,502, Rs 40,053, -Rs 5,787, -Rs 11,870 ; T2000S: -Rs 34,434, -Rs 50,620, -Rs 87,573, -Rs 148,205 ; S2000: Rs 28,158, Rs 62,277, -Rs 1,572, Rs 206,636.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 99%, S2000 99%.

### R51 Donchian 20 (+ Supertrend 10, 3)

B0 parity at 1 lot (design): 4208 trades, Rs 50,425; published [4208, 50425]: reproduced. Final: 1670 trades, Rs 134,743; reproduced.
 R106's R51 before Supertrend (engine check): 4498 / Rs 9,998, reproduced.
 Sessions: design 337, final 133, post 4.

#### 1 lot

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 44% | 44% | Rs 50,425 | Rs 150 | -Rs 49,738 | -Rs 214,239 | -Rs 17,892 | -Rs 11,511 | 0 / 0 / 796 / 3412 |  |
|  | wf | 3044 | 46% | 44% | Rs 213,748 | Rs 865 | -Rs 28,942 | -Rs 94,176 | Rs 145,431 | -Rs 11,511 | 0 / 0 / 512 / 2532 |  |
|  | final | 1670 | 45% | 42% | Rs 134,743 | Rs 1,013 | -Rs 21,578 | -Rs 81,683 | Rs 76,711 | -Rs 5,985 | 0 / 0 / 219 / 1451 |  |
|  | post | 46 | 50% | 48% | Rs 3,412 | Rs 853 | -Rs 13,478 | -Rs 20,847 | -Rs 14,530 | -Rs 3,533 | 0 / 0 / 12 / 34 |  |
| **T2000** | design | 4309 | 48% | 49% | -Rs 101,370 | -Rs 301 | -Rs 34,770 | -Rs 203,504 | -Rs 111,404 | -Rs 11,511 | 1255 / 0 / 796 / 2258 |  |
|  | wf | 3111 | 49% | 49% | Rs 33,948 | Rs 137 | -Rs 34,770 | -Rs 118,105 | Rs 23,915 | -Rs 11,511 | 874 / 0 / 510 / 1727 |  |
|  | final | 1708 | 46% | 48% | -Rs 64,922 | -Rs 488 | -Rs 21,578 | -Rs 131,033 | -Rs 74,952 | -Rs 6,073 | 403 / 0 / 235 / 1070 | p 0.002, q 0.866 (R152 alone 0.018); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 0.87 > 0.10 |
|  | post | 51 | 47% | 51% | -Rs 7,007 | -Rs 1,752 | -Rs 17,642 | -Rs 22,943 | -Rs 17,028 | -Rs 4,513 | 19 / 0 / 17 / 15 |  |
| **T2000S** | design | 4314 | 46% | 47% | -Rs 121,404 | -Rs 360 | -Rs 21,362 | -Rs 182,083 | -Rs 131,436 | -Rs 9,603 | 1202 / 799 / 495 / 1818 |  |
|  | wf | 3115 | 47% | 47% | -Rs 24,417 | -Rs 99 | -Rs 21,362 | -Rs 101,635 | -Rs 34,449 | -Rs 9,603 | 831 / 520 / 342 / 1422 |  |
|  | final | 1710 | 45% | 47% | -Rs 90,851 | -Rs 683 | -Rs 23,628 | -Rs 138,559 | -Rs 100,881 | -Rs 4,218 | 391 / 284 / 138 / 897 | p 1.0, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 51 | 41% | 47% | -Rs 10,403 | -Rs 2,601 | -Rs 14,657 | -Rs 21,432 | -Rs 20,423 | -Rs 4,513 | 18 / 12 / 10 / 11 |  |
| **S2000** | design | 4217 | 42% | 42% | Rs 46,999 | Rs 139 | -Rs 34,158 | -Rs 178,499 | -Rs 16,794 | -Rs 9,603 | 0 / 816 / 486 / 2915 |  |
|  | wf | 3052 | 44% | 42% | Rs 162,556 | Rs 658 | -Rs 21,362 | -Rs 96,451 | Rs 98,763 | -Rs 9,603 | 0 / 529 / 336 / 2187 |  |
|  | final | 1672 | 44% | 42% | Rs 91,538 | Rs 688 | -Rs 20,732 | -Rs 66,115 | Rs 33,506 | -Rs 4,218 | 0 / 276 / 133 / 1263 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 46 | 41% | 43% | -Rs 2,302 | -Rs 575 | -Rs 12,662 | -Rs 21,652 | -Rs 20,243 | -Rs 2,864 | 0 / 11 / 7 / 28 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 13,763, Rs 59,076, Rs 20,835, Rs 120,074 ; T2000: -Rs 31,722, Rs 20,037, Rs 33,661, Rs 11,972 ; T2000S: -Rs 55,246, -Rs 12,083, Rs 30,712, Rs 12,200 ; S2000: -Rs 21,254, Rs 51,307, Rs 29,200, Rs 103,303.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 65%, S2000 64%.

#### 2 lots

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 45% | 44% | Rs 295,708 | Rs 877 | -Rs 98,679 | -Rs 381,894 | Rs 158,838 | -Rs 22,974 | 0 / 0 / 796 / 3412 |  |
|  | wf | 3044 | 46% | 43% | Rs 568,032 | Rs 2,300 | -Rs 57,260 | -Rs 179,011 | Rs 431,161 | -Rs 22,974 | 0 / 0 / 512 / 2532 |  |
|  | final | 1670 | 46% | 42% | Rs 346,548 | Rs 2,606 | -Rs 42,690 | -Rs 145,608 | Rs 230,248 | -Rs 11,923 | 0 / 0 / 219 / 1451 |  |
|  | post | 46 | 50% | 47% | Rs 8,947 | Rs 2,237 | -Rs 26,343 | -Rs 40,466 | -Rs 27,172 | -Rs 7,020 | 0 / 0 / 12 / 34 |  |
| **T2000** | design | 4430 | 59% | 60% | -Rs 354,855 | -Rs 1,053 | -Rs 54,609 | -Rs 413,614 | -Rs 364,924 | -Rs 22,974 | 2329 / 0 / 714 / 1387 |  |
|  | wf | 3201 | 59% | 60% | -Rs 100,752 | -Rs 408 | -Rs 54,609 | -Rs 218,855 | -Rs 110,820 | -Rs 22,974 | 1666 / 0 / 465 / 1070 |  |
|  | final | 1748 | 56% | 58% | -Rs 162,059 | -Rs 1,218 | -Rs 42,871 | -Rs 249,644 | -Rs 172,119 | -Rs 12,099 | 832 / 0 / 215 / 701 | p 0.004, q 1.0 (R152 alone 0.024); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 52 | 60% | 66% | -Rs 18,344 | -Rs 4,586 | -Rs 37,583 | -Rs 50,751 | -Rs 28,390 | -Rs 8,979 | 29 / 0 / 14 / 9 |  |
| **T2000S** | design | 4443 | 47% | 50% | -Rs 460,612 | -Rs 1,367 | -Rs 29,322 | -Rs 473,839 | -Rs 470,680 | -Rs 19,158 | 1902 / 1958 / 90 / 493 |  |
|  | wf | 3209 | 48% | 50% | -Rs 229,933 | -Rs 931 | -Rs 24,333 | -Rs 229,933 | -Rs 240,001 | -Rs 19,158 | 1381 / 1362 / 60 / 406 |  |
|  | final | 1752 | 47% | 50% | -Rs 207,174 | -Rs 1,558 | -Rs 28,136 | -Rs 221,932 | -Rs 217,233 | -Rs 8,388 | 708 / 741 / 11 / 292 | p 1.0, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 52 | 50% | 55% | -Rs 10,079 | -Rs 2,520 | -Rs 21,096 | -Rs 31,523 | -Rs 20,126 | -Rs 8,979 | 24 / 25 / 1 / 2 |  |
| **S2000** | design | 4267 | 34% | 34% | Rs 9,220 | Rs 27 | -Rs 37,950 | -Rs 305,983 | -Rs 114,208 | -Rs 19,158 | 0 / 2327 / 89 / 1851 |  |
|  | wf | 3080 | 36% | 35% | Rs 208,310 | Rs 843 | -Rs 26,787 | -Rs 212,105 | Rs 84,932 | -Rs 19,158 | 0 / 1599 / 61 / 1420 |  |
|  | final | 1684 | 36% | 35% | Rs 140,376 | Rs 1,055 | -Rs 34,002 | -Rs 113,336 | Rs 35,555 | -Rs 8,388 | 0 / 836 / 10 / 838 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 46 | 35% | 32% | Rs 7,715 | Rs 1,929 | -Rs 15,080 | -Rs 26,127 | -Rs 27,393 | -Rs 2,457 | 0 / 27 / 1 / 18 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 61,532, Rs 154,398, Rs 77,427, Rs 274,674 ; T2000: -Rs 80,073, -Rs 15,939, Rs 10,516, -Rs 15,256 ; T2000S: -Rs 123,017, -Rs 53,457, -Rs 37,730, -Rs 15,729 ; S2000: -Rs 22,705, Rs 95,184, -Rs 2,603, Rs 138,433.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 95%, S2000 95%.

#### 3 lots

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 45% | 44% | Rs 538,237 | Rs 1,597 | -Rs 147,647 | -Rs 558,163 | Rs 332,834 | -Rs 34,438 | 0 / 0 / 796 / 3412 |  |
|  | wf | 3044 | 46% | 43% | Rs 920,100 | Rs 3,725 | -Rs 85,577 | -Rs 263,933 | Rs 714,697 | -Rs 34,438 | 0 / 0 / 512 / 2532 |  |
|  | final | 1670 | 46% | 42% | Rs 557,695 | Rs 4,193 | -Rs 63,776 | -Rs 209,599 | Rs 383,127 | -Rs 17,861 | 0 / 0 / 219 / 1451 |  |
|  | post | 46 | 50% | 47% | Rs 14,470 | Rs 3,617 | -Rs 39,207 | -Rs 60,086 | -Rs 39,808 | -Rs 10,506 | 0 / 0 / 12 / 34 |  |
| **T2000** | design | 4486 | 68% | 69% | -Rs 319,264 | -Rs 947 | -Rs 57,501 | -Rs 501,373 | -Rs 329,367 | -Rs 18,921 | 2927 / 0 / 592 / 967 |  |
|  | wf | 3240 | 68% | 67% | Rs 96,415 | Rs 390 | -Rs 57,501 | -Rs 203,143 | Rs 86,311 | -Rs 18,744 | 2113 / 0 / 380 / 747 |  |
|  | final | 1761 | 64% | 67% | -Rs 365,237 | -Rs 2,746 | -Rs 50,555 | -Rs 443,064 | -Rs 375,325 | -Rs 18,124 | 1051 / 0 / 194 / 516 | p 0.8283, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 52 | 67% | 75% | -Rs 30,384 | -Rs 7,596 | -Rs 49,655 | -Rs 55,584 | -Rs 40,451 | -Rs 13,453 | 34 / 0 / 11 / 7 |  |
| **T2000S** | design | 4509 | 47% | 51% | -Rs 637,562 | -Rs 1,892 | -Rs 28,094 | -Rs 670,991 | -Rs 647,666 | -Rs 11,972 | 2072 / 2300 / 15 / 122 |  |
|  | wf | 3255 | 48% | 51% | -Rs 399,672 | -Rs 1,618 | -Rs 28,094 | -Rs 413,549 | -Rs 409,776 | -Rs 8,192 | 1513 / 1635 / 7 / 100 |  |
|  | final | 1770 | 45% | 51% | -Rs 466,992 | -Rs 3,511 | -Rs 32,102 | -Rs 466,992 | -Rs 477,080 | -Rs 11,250 | 761 / 920 / 0 / 89 | p 1.0, q 1.0 (R152 alone 1.0); fail: not positive in design, walk-forward and final; not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 52 | 50% | 52% | -Rs 4,372 | -Rs 1,093 | -Rs 8,126 | -Rs 17,468 | -Rs 14,435 | -Rs 3,661 | 25 / 26 / 0 / 1 |  |
| **S2000** | design | 4312 | 27% | 27% | Rs 60,879 | Rs 181 | -Rs 37,320 | -Rs 323,821 | -Rs 124,360 | -Rs 28,714 | 0 / 3001 / 16 / 1295 |  |
|  | wf | 3113 | 28% | 27% | Rs 245,141 | Rs 992 | -Rs 37,320 | -Rs 259,323 | Rs 59,978 | -Rs 28,714 | 0 / 2103 / 8 / 1002 |  |
|  | final | 1709 | 28% | 27% | Rs 144,937 | Rs 1,090 | -Rs 40,323 | -Rs 185,087 | -Rs 5,756 | -Rs 11,250 | 0 / 1124 / 0 / 585 | p 1.0, q 1.0 (R152 alone 1.0); fail: not better than today's rules (walk-forward and final); exit-twin BH q 1.00 > 0.10 |
|  | post | 47 | 28% | 28% | -Rs 2,450 | -Rs 613 | -Rs 13,003 | -Rs 24,828 | -Rs 47,479 | -Rs 3,661 | 0 / 33 / 0 / 14 |  |

Walk-forward quarters (Apr-Jun, Jul-Sep, Oct-Dec 2025, Jan-Mar 2026): B0: Rs 108,905, Rs 249,078, Rs 133,537, Rs 428,580 ; T2000: Rs 86,337, -Rs 20,552, Rs 19,977, Rs 10,652 ; T2000S: -Rs 69,256, -Rs 107,068, -Rs 92,753, -Rs 130,595 ; S2000: Rs 18,509, Rs 144,590, -Rs 54,624, Rs 136,667.

Share of design trades whose Rs 2,000 stop was nearer than -15%: T2000S 99%, S2000 99%.

## Second look: only the trades R151's app pickers take (Apr - Oct 2026)

Descriptive only, never gated: Apr - Oct 2026 was read many times, and both pickers failed their own gates (Boss's 6 Nov exception put them in the app). As the app does, a skipped break still holds its side and counts in the day's caps.

### R72 Breadth Donchian + R151 picker

Parity (R151's walk, skipped breaks removed before the walk, 1 lot): 652 trades, Rs 219,057; published [652, 219057]: reproduced. Candidates 2367, scored below the threshold 1291.

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1 lot | 603 | 49% | 40% | Rs 169,674 | Rs 1,276 | -Rs 21,301 | -Rs 31,620 | Rs 113,387 | -Rs 6,245 | 0 / 0 / 79 / 524 |  |
| **T2000** | 1 lot | 627 | 50% | 48% | Rs 41,504 | Rs 312 | -Rs 21,541 | -Rs 61,408 | Rs 31,476 | -Rs 6,245 | 191 / 0 / 92 / 344 |  |
| **T2000S** | 1 lot | 628 | 49% | 47% | Rs 33,979 | Rs 255 | -Rs 22,562 | -Rs 43,857 | Rs 23,950 | -Rs 3,892 | 186 / 115 / 50 / 277 |  |
| **S2000** | 1 lot | 604 | 48% | 40% | Rs 146,752 | Rs 1,103 | -Rs 22,322 | -Rs 27,730 | Rs 90,465 | -Rs 3,892 | 0 / 106 / 48 / 450 |  |
| **B0** | 2 lots | 603 | 49% | 40% | Rs 367,222 | Rs 2,761 | -Rs 41,948 | -Rs 60,657 | Rs 254,425 | -Rs 12,468 | 0 / 0 / 79 / 524 |  |
| **T2000** | 2 lots | 641 | 60% | 60% | -Rs 14,309 | -Rs 108 | -Rs 50,763 | -Rs 130,623 | -Rs 24,368 | -Rs 12,468 | 345 / 0 / 86 / 210 |  |
| **T2000S** | 2 lots | 646 | 48% | 50% | -Rs 51,500 | -Rs 387 | -Rs 24,354 | -Rs 94,791 | -Rs 61,559 | -Rs 7,516 | 284 / 285 / 4 / 73 |  |
| **S2000** | 2 lots | 611 | 38% | 31% | Rs 256,676 | Rs 1,930 | -Rs 28,345 | -Rs 60,888 | Rs 143,878 | -Rs 7,516 | 0 / 309 / 4 / 298 |  |
| **B0** | 3 lots | 603 | 50% | 40% | Rs 564,570 | Rs 4,245 | -Rs 62,591 | -Rs 89,763 | Rs 395,255 | -Rs 18,678 | 0 / 0 / 79 / 524 |  |
| **T2000** | 3 lots | 650 | 67% | 69% | -Rs 83,277 | -Rs 626 | -Rs 82,863 | -Rs 191,506 | -Rs 93,362 | -Rs 18,678 | 415 / 0 / 75 / 160 |  |
| **T2000S** | 3 lots | 655 | 45% | 51% | -Rs 167,722 | -Rs 1,261 | -Rs 19,690 | -Rs 167,722 | -Rs 177,808 | -Rs 11,250 | 283 / 342 / 0 / 30 |  |
| **S2000** | 3 lots | 622 | 28% | 24% | Rs 207,041 | Rs 1,557 | -Rs 29,453 | -Rs 91,447 | Rs 44,443 | -Rs 11,250 | 0 / 414 / 0 / 208 |  |

### R51 Donchian 20 (+ Supertrend 10, 3) + R151 picker

Parity (R151's walk, skipped breaks removed before the walk, 1 lot): 693 trades, Rs 159,040; published [693, 159040]: reproduced. Candidates 4846, scored below the threshold 3714.

| rule | period | trades | win | break-even win | net | per session | worst day | max DD | without 5 best | largest loss | exits (target / Rs 2k stop / -15% stop / other) | exit-twin p, BH q, verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1 lot | 653 | 50% | 43% | Rs 142,884 | Rs 1,074 | -Rs 14,642 | -Rs 38,799 | Rs 103,038 | -Rs 5,985 | 0 / 0 / 86 / 567 |  |
| **T2000** | 1 lot | 684 | 51% | 49% | Rs 24,733 | Rs 186 | -Rs 14,642 | -Rs 48,742 | Rs 14,705 | -Rs 6,073 | 190 / 0 / 99 / 395 |  |
| **T2000S** | 1 lot | 688 | 48% | 49% | -Rs 10,763 | -Rs 81 | -Rs 16,663 | -Rs 57,267 | -Rs 20,792 | -Rs 4,218 | 180 / 135 / 53 / 320 |  |
| **S2000** | 1 lot | 657 | 48% | 43% | Rs 96,286 | Rs 724 | -Rs 15,762 | -Rs 38,988 | Rs 56,441 | -Rs 4,218 | 0 / 124 / 48 / 485 |  |
| **B0** | 2 lots | 653 | 51% | 43% | Rs 315,804 | Rs 2,374 | -Rs 28,984 | -Rs 73,811 | Rs 235,877 | -Rs 11,923 | 0 / 0 / 86 / 567 |  |
| **T2000** | 2 lots | 717 | 61% | 61% | -Rs 24,847 | -Rs 187 | -Rs 30,924 | -Rs 107,425 | -Rs 34,906 | -Rs 12,099 | 374 / 0 / 92 / 251 |  |
| **T2000S** | 2 lots | 721 | 48% | 52% | -Rs 94,203 | -Rs 708 | -Rs 19,772 | -Rs 108,599 | -Rs 104,262 | -Rs 8,388 | 303 / 322 / 8 / 88 |  |
| **S2000** | 2 lots | 668 | 37% | 35% | Rs 107,236 | Rs 806 | -Rs 23,597 | -Rs 87,898 | Rs 27,309 | -Rs 8,388 | 0 / 349 / 7 / 312 |  |
| **B0** | 3 lots | 653 | 51% | 43% | Rs 488,424 | Rs 3,672 | -Rs 43,311 | -Rs 108,826 | Rs 368,416 | -Rs 17,861 | 0 / 0 / 86 / 567 |  |
| **T2000** | 3 lots | 731 | 67% | 70% | -Rs 127,382 | -Rs 958 | -Rs 44,885 | -Rs 208,190 | -Rs 137,469 | -Rs 18,124 | 461 / 0 / 78 / 192 |  |
| **T2000S** | 3 lots | 736 | 45% | 52% | -Rs 226,759 | -Rs 1,705 | -Rs 23,573 | -Rs 226,759 | -Rs 236,846 | -Rs 11,250 | 315 / 395 / 0 / 26 |  |
| **S2000** | 3 lots | 682 | 29% | 27% | Rs 123,524 | Rs 929 | -Rs 25,302 | -Rs 132,106 | Rs 3,516 | -Rs 11,250 | 0 / 457 / 0 / 225 |  |

