# R153: combinations to make the Donchian arms lose less (7 Nov 2026)

## For Boss, in short

**No combination passed (0 of 24). Most do cut the losses, but they cut the profit by more, and random choices of the
same size cut the losses just as much.** Nothing changes in the app.

What was tried, on R72 and R51, at 1, 2 and 3 lots (Rs amounts for the whole position, after charges):
- **C1** -Rs 2,000 stop + once a trade is +Rs 2,000, lock +Rs 1,000 (the winner keeps running);
- **C2** stop new trades for the day once the day's closed trades are -Rs 4,000;
- **C3** C1 + C2;
- **C4** new trades only until 10:00 + C1.

Apr - Oct 2026, 133 sessions. "Losing days" = the total of all red days (smaller is better):

| | lots | today: net / losing days / worst day | best "lose less" combination | its net / losing days / worst day |
|---|---|---|---|---|
| R72 | 1 | Rs 1,71,366 / -Rs 3,89,644 / -Rs 22,571 | C4 (10:00 + stop + lock) | Rs 77,323 / -Rs 1,57,539 / -Rs 10,585 |
| R72 | 3 | Rs 6,25,048 / -Rs 11,20,275 / -Rs 66,325 | C4 | Rs 80,827 / -Rs 2,67,686 / -Rs 13,024 |
| R51 | 1 | Rs 1,34,743 / -Rs 3,95,932 / -Rs 21,578 | C4 | Rs 82,673 / -Rs 2,22,622 / -Rs 9,077 |
| R51 | 3 | Rs 5,57,695 / -Rs 11,07,670 / -Rs 63,776 | C3 (stop + lock + day cap) | Rs 92,304 / -Rs 5,59,297 / -Rs 11,412 |

- **So the losses do get much smaller** (often halved or better, worst day -Rs 9k to -13k instead of -Rs 21k to -66k),
  **but the profit falls 40-90%**, and in R51 at 3 lots C4 even ends slightly negative (-Rs 5,533).
- **Why it fails the "better than chance" test:** C4 takes about a third of the trades (369 instead of 1,202 on R72).
  Throwing away the same number of trades at random cuts the losses about as much. The stop and the lock never beat
  random exit times (best p 0.19): selling a falling trade at a random earlier minute loses less than waiting for -2k.
- **The day cap (C2) alone** keeps most of the profit at 1 lot (R72 Rs 1,66,394 vs Rs 1,71,366) with a smaller worst day
  (-Rs 14,064), but the trades it blocks are on average the afternoon recoveries, so random blocks do better; on R72 in the
  walk-forward year it made the losing days bigger.
- **One lead (not a pass):** the 10:00 cut on its own beat random skips at 3 lots (p 0.025 R72, 0.033 R51). It was only
  tested together with the stop and lock, so it cannot be adopted from here; R112 found the 10:00 cut alone made less
  money than today. It could be its own pre-registered test if Boss wants.

**What already lowers losses in the app:** R151's picker (live since 6 Nov). On Apr - Oct 2026 (second look), R72's
picks at 1 lot made Rs 1,69,674 with losing days -Rs 2,33,631 and drawdown Rs 31,620, against the plain arm's
Rs 1,71,366 / -Rs 3,89,644 / Rs 61,884: about the same profit with 40% less on red days. On the picks, the day cap at 1 lot looked a little
better (R72 Rs 1,89,335, losing days -Rs 1,98,933), but that is a second look and the day cap failed its test on the
full arm; the other combinations cut the picks' profit (all in the report's second-look table).

## Method (honest notes)

- Pre-registered in `PREREG.md` (committed before any R153 number), the same engine and parity as R152 (R72 3,071 /
  Rs 2,16,929 and 1,202 / Rs 1,71,366; R51 + Supertrend 4,208 / Rs 50,425 and 1,670 / Rs 1,34,743, reproduced).
- Signal source: the index (unchanged). Only the option's exit (stop, lock on the premium) and the day's entries change.
- Gates (R140's "lose less", Boss 31 Oct): in design, walk-forward and final, smaller losing days, drawdown, worst day and
  worst month than today at the same lots, net still > 0; beats 2,000 random twins on the losing days (p < 0.05; a
  combination must beat both its exit twin and its skip twin); BH q <= 0.10 with m = 890; >= 40 final trades.
- The final period was read once by R153 but has been read by many earlier studies. The post window (14 - 19 Oct,
  4 sessions) is in the tables and never gated.

## Every table

Net after dated Zerodha charges and R17 slippage, for the whole position. 'wf' = walk-forward window Apr 2025 - Mar 2026 (inside design). Sum of losing days = the total of every red day. Twins: final period, 2,000 sets, on the sum of losing days. BH q with m = 890. Signal source: the index (unchanged).

Rules: **B0** today's rules; **C1** -Rs 2,000 stop + lock +Rs 1,000 once +Rs 2,000; **C2** stop new entries for the day at -Rs 4,000 closed; **C3** C1 + the day cap; **C4** entries until 10:00 + C1.

### R72 Breadth Donchian

B0 parity at 1 lot: design 3071 / Rs 216,929, final 1202 / Rs 171,366 (reproduced).

#### 1 lot

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | Rs 216,929 | -Rs 1,154,058 | 176 of 323 | -Rs 58,613 | -Rs 199,077 | -Rs 78,467 | 0 / 0 / 568 / 66 / 2437 |  |
|  | wf | 2238 | 49% | Rs 335,882 | -Rs 708,002 | 122 of 236 | -Rs 25,837 | -Rs 86,529 | -Rs 72,605 | 0 / 0 / 365 / 51 / 1822 |  |
|  | final | 1202 | 46% | Rs 171,366 | -Rs 389,644 | 62 of 129 | -Rs 22,571 | -Rs 61,884 | -Rs 40,908 | 0 / 0 / 174 / 13 / 1015 |  |
|  | post | 25 | 56% | -Rs 624 | -Rs 18,451 | 1 of 3 | -Rs 18,451 | -Rs 18,451 | -Rs 624 | 0 / 0 / 6 / 0 / 19 |  |
| **C1** | design | 3092 | 47% | Rs 114,305 | -Rs 999,988 | 169 of 323 | -Rs 26,795 | -Rs 138,410 | -Rs 67,781 | 0 / 518 / 338 / 453 / 1783 |  |
|  | wf | 2249 | 49% | Rs 212,673 | -Rs 648,271 | 116 of 236 | -Rs 19,896 | -Rs 74,085 | -Rs 67,781 | 0 / 331 / 236 / 315 / 1367 |  |
|  | final | 1205 | 46% | Rs 112,960 | -Rs 400,606 | 67 of 129 | -Rs 21,633 | -Rs 62,517 | -Rs 42,198 | 0 / 184 / 112 / 104 / 805 | p 1.0, q 1.0; fail: final: losing days not smaller; final: drawdown worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 26 | 46% | -Rs 7,129 | -Rs 16,798 | 1 of 3 | -Rs 16,798 | -Rs 16,798 | -Rs 7,129 | 0 / 5 / 4 / 4 / 13 |  |
| **C2** | design | 2448 | 46% | Rs 123,009 | -Rs 1,097,970 | 193 of 323 | -Rs 26,277 | -Rs 135,217 | -Rs 69,723 | 0 / 0 / 470 / 52 / 1926 |  |
|  | wf | 1791 | 47% | Rs 133,303 | -Rs 766,617 | 136 of 236 | -Rs 26,277 | -Rs 96,044 | -Rs 69,723 | 0 / 0 / 311 / 38 / 1442 |  |
|  | final | 999 | 47% | Rs 166,394 | -Rs 335,199 | 67 of 129 | -Rs 14,064 | -Rs 58,663 | -Rs 35,561 | 0 / 0 / 145 / 13 / 841 | p 1.0, q 1.0; fail: wf: losing days not smaller; wf: drawdown worse; wf: worst day worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 20 | 70% | Rs 3,332 | -Rs 14,495 | 1 of 3 | -Rs 14,495 | -Rs 14,495 | Rs 3,332 | 0 / 0 / 5 / 0 / 15 |  |
| **C3** | design | 2448 | 46% | Rs 48,689 | -Rs 948,635 | 191 of 323 | -Rs 16,911 | -Rs 98,069 | -Rs 56,373 | 0 / 410 / 279 / 354 / 1405 |  |
|  | wf | 1791 | 47% | Rs 88,295 | -Rs 670,665 | 134 of 236 | -Rs 16,911 | -Rs 98,069 | -Rs 56,373 | 0 / 266 / 198 / 249 / 1078 |  |
|  | final | 1019 | 47% | Rs 105,169 | -Rs 357,020 | 71 of 129 | -Rs 14,436 | -Rs 56,376 | -Rs 36,176 | 0 / 151 / 99 / 93 / 676 | p 1.0, q 1.0; fail: wf: drawdown worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 21 | 57% | -Rs 3,173 | -Rs 12,842 | 1 of 3 | -Rs 12,842 | -Rs 12,842 | -Rs 3,173 | 0 / 5 / 3 / 4 / 9 |  |
| **C4** | design | 859 | 53% | Rs 196,932 | -Rs 452,019 | 101 of 190 | -Rs 16,911 | -Rs 63,667 | -Rs 33,242 | 0 / 202 / 101 / 176 / 380 |  |
|  | wf | 596 | 54% | Rs 159,836 | -Rs 305,646 | 71 of 135 | -Rs 16,911 | -Rs 63,667 | -Rs 33,242 | 0 / 129 / 65 / 130 / 272 |  |
|  | final | 369 | 51% | Rs 77,323 | -Rs 157,539 | 45 of 86 | -Rs 10,585 | -Rs 47,874 | -Rs 41,285 | 0 / 66 / 39 / 41 / 223 | p 1.0, q 1.0; fail: final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 11 | 36% | -Rs 7,832 | -Rs 12,842 | 1 of 2 | -Rs 12,842 | -Rs 12,842 | -Rs 7,832 | 0 / 5 / 2 / 1 / 3 |  |

Twins (final, sum of losing days): C1: exit p 1 (twin mean -Rs 302,463); C2: skip p 1 (twin mean -Rs 237,926); C3: exit p 1 (twin mean -Rs 271,287), skip p 1 (twin mean -Rs 265,383); C4: exit p 1 (twin mean -Rs 127,707), skip p 0.384 (twin mean -Rs 162,179).

#### 2 lots

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | Rs 576,488 | -Rs 2,237,072 | 171 of 323 | -Rs 116,525 | -Rs 375,471 | -Rs 148,090 | 0 / 0 / 568 / 66 / 2437 |  |
|  | wf | 2238 | 49% | Rs 775,437 | -Rs 1,367,320 | 119 of 236 | -Rs 51,084 | -Rs 164,515 | -Rs 135,116 | 0 / 0 / 365 / 51 / 1822 |  |
|  | final | 1202 | 46% | Rs 398,405 | -Rs 754,744 | 58 of 129 | -Rs 44,447 | -Rs 111,087 | -Rs 72,639 | 0 / 0 / 174 / 13 / 1015 |  |
|  | post | 25 | 56% | -Rs 121 | -Rs 36,430 | 1 of 3 | -Rs 36,430 | -Rs 36,430 | -Rs 121 | 0 / 0 / 6 / 0 / 19 |  |
| **C1** | design | 3203 | 45% | -Rs 208,265 | -Rs 1,704,085 | 200 of 323 | -Rs 28,986 | -Rs 390,422 | -Rs 120,355 | 0 / 1396 / 56 / 926 / 825 |  |
|  | wf | 2326 | 47% | Rs 44,040 | -Rs 1,105,728 | 140 of 236 | -Rs 26,998 | -Rs 136,271 | -Rs 120,355 | 0 / 947 / 42 / 665 / 672 |  |
|  | final | 1222 | 45% | Rs 159,864 | -Rs 621,599 | 66 of 129 | -Rs 25,462 | -Rs 111,194 | -Rs 54,805 | 0 / 543 / 5 / 283 / 391 | p 1.0, q 1.0; fail: design: drawdown worse; design: the arm no longer makes money; final: drawdown worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 27 | 52% | -Rs 14,588 | -Rs 26,552 | 1 of 3 | -Rs 26,552 | -Rs 26,552 | -Rs 14,588 | 0 / 12 / 1 / 10 / 4 |  |
| **C2** | design | 2277 | 46% | Rs 338,605 | -Rs 2,044,999 | 203 of 323 | -Rs 52,365 | -Rs 243,201 | -Rs 82,851 | 0 / 0 / 442 / 45 / 1790 |  |
|  | wf | 1672 | 48% | Rs 348,044 | -Rs 1,412,573 | 144 of 236 | -Rs 52,365 | -Rs 125,000 | -Rs 82,851 | 0 / 0 / 291 / 37 / 1344 |  |
|  | final | 943 | 46% | Rs 313,454 | -Rs 652,128 | 69 of 129 | -Rs 27,904 | -Rs 105,828 | -Rs 51,882 | 0 / 0 / 134 / 12 / 797 | p 1.0, q 1.0; fail: wf: losing days not smaller; wf: worst day worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 20 | 70% | Rs 7,555 | -Rs 28,754 | 1 of 3 | -Rs 28,754 | -Rs 28,754 | Rs 7,555 | 0 / 0 / 5 / 0 / 15 |  |
| **C3** | design | 2319 | 45% | -Rs 93,070 | -Rs 1,397,840 | 216 of 323 | -Rs 31,678 | -Rs 179,897 | -Rs 81,679 | 0 / 1015 / 37 / 666 / 601 |  |
|  | wf | 1757 | 46% | -Rs 21,147 | -Rs 1,010,473 | 152 of 236 | -Rs 31,678 | -Rs 127,671 | -Rs 81,679 | 0 / 735 / 29 / 501 / 492 |  |
|  | final | 942 | 47% | Rs 217,317 | -Rs 487,048 | 74 of 129 | -Rs 12,099 | -Rs 82,029 | -Rs 44,695 | 0 / 407 / 4 / 230 / 301 | p 1.0, q 1.0; fail: design: the arm no longer makes money; wf: the arm no longer makes money; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 15 | 47% | -Rs 14,965 | -Rs 22,960 | 2 of 3 | -Rs 16,968 | -Rs 22,960 | -Rs 14,965 | 0 / 8 / 0 / 6 / 1 |  |
| **C4** | design | 924 | 49% | Rs 99,070 | -Rs 650,908 | 110 of 190 | -Rs 31,678 | -Rs 109,952 | -Rs 54,259 | 0 / 426 / 11 / 324 / 163 |  |
|  | wf | 643 | 51% | Rs 113,565 | -Rs 450,967 | 76 of 135 | -Rs 31,678 | -Rs 72,101 | -Rs 48,307 | 0 / 283 / 7 / 234 / 119 |  |
|  | final | 386 | 49% | Rs 78,349 | -Rs 260,363 | 46 of 86 | -Rs 16,749 | -Rs 85,742 | -Rs 61,663 | 0 / 174 / 3 / 104 / 105 | p 1.0, q 1.0; fail: twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 11 | 27% | -Rs 16,098 | -Rs 16,968 | 1 of 2 | -Rs 16,968 | -Rs 16,968 | -Rs 16,098 | 0 / 8 / 0 / 2 / 1 |  |

Twins (final, sum of losing days): C1: exit p 1 (twin mean -Rs 436,708); C2: skip p 1 (twin mean -Rs 446,855); C3: exit p 1 (twin mean -Rs 360,985), skip p 1 (twin mean -Rs 379,308); C4: exit p 1 (twin mean -Rs 197,789), skip p 0.542 (twin mean -Rs 257,476).

#### 3 lots

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 3071 | 47% | Rs 934,016 | -Rs 3,321,867 | 170 of 323 | -Rs 174,444 | -Rs 552,158 | -Rs 217,892 | 0 / 0 / 568 / 66 / 2437 |  |
|  | wf | 2238 | 50% | Rs 1,213,421 | -Rs 2,027,948 | 118 of 236 | -Rs 76,355 | -Rs 242,618 | -Rs 197,761 | 0 / 0 / 365 / 51 / 1822 |  |
|  | final | 1202 | 47% | Rs 625,048 | -Rs 1,120,275 | 58 of 129 | -Rs 66,325 | -Rs 160,424 | -Rs 104,488 | 0 / 0 / 174 / 13 / 1015 |  |
|  | post | 25 | 56% | Rs 399 | -Rs 54,418 | 1 of 3 | -Rs 54,418 | -Rs 54,418 | Rs 399 | 0 / 0 / 6 / 0 / 19 |  |
| **C1** | design | 3279 | 44% | -Rs 344,946 | -Rs 2,059,096 | 214 of 323 | -Rs 37,908 | -Rs 418,355 | -Rs 150,185 | 0 / 1653 / 12 / 1182 / 432 |  |
|  | wf | 2379 | 45% | -Rs 127,025 | -Rs 1,397,446 | 148 of 236 | -Rs 37,908 | -Rs 238,067 | -Rs 135,183 | 0 / 1162 / 10 / 850 / 357 |  |
|  | final | 1243 | 41% | -Rs 25,363 | -Rs 778,538 | 77 of 129 | -Rs 27,409 | -Rs 178,404 | -Rs 72,173 | 0 / 651 / 0 / 367 / 225 | p 0.966, q 1.0; fail: design: the arm no longer makes money; wf: the arm no longer makes money; final: drawdown worse; final: the arm no longer makes money; twin p 0.966 >= 0.05; BH q 1 > 0.1 |
|  | post | 25 | 48% | Rs 11,895 | -Rs 17,135 | 1 of 3 | -Rs 17,135 | -Rs 17,135 | Rs 11,895 | 0 / 13 / 0 / 5 / 7 |  |
| **C2** | design | 2216 | 46% | Rs 514,752 | -Rs 3,028,726 | 209 of 323 | -Rs 78,453 | -Rs 355,373 | -Rs 121,782 | 0 / 0 / 436 / 43 / 1737 |  |
|  | wf | 1636 | 48% | Rs 508,419 | -Rs 2,113,817 | 148 of 236 | -Rs 78,453 | -Rs 186,755 | -Rs 121,782 | 0 / 0 / 290 / 36 / 1310 |  |
|  | final | 910 | 47% | Rs 503,343 | -Rs 949,289 | 71 of 129 | -Rs 41,737 | -Rs 152,139 | -Rs 70,407 | 0 / 0 / 125 / 12 / 773 | p 1.0, q 1.0; fail: wf: losing days not smaller; wf: worst day worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 20 | 70% | Rs 11,795 | -Rs 43,022 | 1 of 3 | -Rs 43,022 | -Rs 43,022 | Rs 11,795 | 0 / 0 / 5 / 0 / 15 |  |
| **C3** | design | 2290 | 45% | -Rs 218,052 | -Rs 1,592,753 | 239 of 323 | -Rs 46,449 | -Rs 265,603 | -Rs 112,423 | 0 / 1150 / 4 / 846 / 290 |  |
|  | wf | 1732 | 46% | -Rs 160,537 | -Rs 1,138,593 | 167 of 236 | -Rs 46,449 | -Rs 262,903 | -Rs 112,423 | 0 / 843 / 3 / 642 / 244 |  |
|  | final | 962 | 42% | Rs 18,822 | -Rs 606,786 | 86 of 129 | -Rs 12,663 | -Rs 109,465 | -Rs 58,952 | 0 / 495 / 0 / 291 / 176 | p 1.0, q 1.0; fail: design: the arm no longer makes money; wf: drawdown worse; wf: the arm no longer makes money; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 13 | 38% | -Rs 5,623 | -Rs 15,957 | 2 of 3 | -Rs 9,971 | -Rs 15,957 | -Rs 5,623 | 0 / 8 / 0 / 3 / 2 |  |
| **C4** | design | 968 | 49% | Rs 122,634 | -Rs 775,897 | 109 of 190 | -Rs 46,449 | -Rs 155,517 | -Rs 75,006 | 0 / 459 / 1 / 412 / 96 |  |
|  | wf | 666 | 52% | Rs 156,521 | -Rs 517,061 | 70 of 135 | -Rs 46,449 | -Rs 98,087 | -Rs 75,006 | 0 / 299 / 0 / 293 / 74 |  |
|  | final | 407 | 48% | Rs 80,827 | -Rs 267,686 | 50 of 86 | -Rs 13,024 | -Rs 71,366 | -Rs 43,205 | 0 / 196 / 0 / 137 / 74 | p 0.1894, q 1.0; fail: twin p 0.189 >= 0.05; BH q 1 > 0.1 |
|  | post | 11 | 18% | -Rs 1,059 | -Rs 9,971 | 1 of 2 | -Rs 9,971 | -Rs 9,971 | -Rs 1,059 | 0 / 9 / 0 / 0 / 2 |  |

Twins (final, sum of losing days): C1: exit p 0.966 (twin mean -Rs 708,174); C2: skip p 1 (twin mean -Rs 648,234); C3: exit p 0.864 (twin mean -Rs 571,294), skip p 1 (twin mean -Rs 519,404); C4: exit p 0.189 (twin mean -Rs 288,936), skip p 0.025 (twin mean -Rs 323,281).

### R51 Donchian 20 (+ Supertrend 10, 3)

B0 parity at 1 lot: design 4208 / Rs 50,425, final 1670 / Rs 134,743 (reproduced).

#### 1 lot

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 44% | Rs 50,425 | -Rs 1,346,619 | 185 of 337 | -Rs 49,738 | -Rs 214,239 | -Rs 69,428 | 0 / 0 / 796 / 73 / 3339 |  |
|  | wf | 3044 | 46% | Rs 213,748 | -Rs 851,416 | 127 of 247 | -Rs 28,942 | -Rs 94,176 | -Rs 53,654 | 0 / 0 / 512 / 53 / 2479 |  |
|  | final | 1670 | 45% | Rs 134,743 | -Rs 395,932 | 72 of 133 | -Rs 21,578 | -Rs 81,683 | -Rs 54,587 | 0 / 0 / 219 / 14 / 1437 |  |
|  | post | 46 | 50% | Rs 3,412 | -Rs 20,847 | 2 of 4 | -Rs 13,478 | -Rs 20,847 | Rs 3,412 | 0 / 0 / 12 / 2 / 32 |  |
| **C1** | design | 4242 | 45% | -Rs 101,114 | -Rs 1,202,039 | 195 of 337 | -Rs 25,248 | -Rs 215,625 | -Rs 63,984 | 0 / 781 / 482 / 615 / 2364 |  |
|  | wf | 3066 | 46% | Rs 14,923 | -Rs 803,399 | 137 of 247 | -Rs 21,362 | -Rs 113,877 | -Rs 63,984 | 0 / 512 / 335 / 416 / 1803 |  |
|  | final | 1674 | 45% | Rs 33,408 | -Rs 425,729 | 76 of 133 | -Rs 22,041 | -Rs 74,872 | -Rs 66,912 | 0 / 275 / 132 / 133 / 1134 | p 1.0, q 1.0; fail: design: drawdown worse; design: the arm no longer makes money; wf: drawdown worse; wf: worst month worse; final: losing days not smaller; final: worst day worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 47 | 43% | -Rs 3,706 | -Rs 19,868 | 2 of 4 | -Rs 12,662 | -Rs 19,868 | -Rs 3,706 | 0 / 11 / 8 / 8 / 20 |  |
| **C2** | design | 3082 | 45% | -Rs 2,190 | -Rs 1,167,106 | 205 of 337 | -Rs 26,277 | -Rs 152,775 | -Rs 78,138 | 0 / 0 / 635 / 53 / 2394 |  |
|  | wf | 2273 | 46% | Rs 70,712 | -Rs 809,933 | 144 of 247 | -Rs 26,277 | -Rs 101,465 | -Rs 78,138 | 0 / 0 / 420 / 43 / 1810 |  |
|  | final | 1285 | 44% | Rs 55,073 | -Rs 384,984 | 81 of 133 | -Rs 14,064 | -Rs 100,995 | -Rs 66,448 | 0 / 0 / 180 / 12 / 1093 | p 1.0, q 1.0; fail: design: worst month worse; design: the arm no longer makes money; wf: drawdown worse; wf: worst month worse; final: drawdown worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 35 | 54% | Rs 5,575 | -Rs 18,683 | 2 of 4 | -Rs 11,747 | -Rs 18,683 | Rs 5,575 | 0 / 0 / 9 / 2 / 24 |  |
| **C3** | design | 3144 | 44% | -Rs 98,811 | -Rs 1,076,032 | 216 of 337 | -Rs 16,911 | -Rs 208,503 | -Rs 72,614 | 0 / 596 / 387 / 445 / 1716 |  |
|  | wf | 2314 | 45% | -Rs 42,057 | -Rs 773,168 | 153 of 247 | -Rs 16,911 | -Rs 128,410 | -Rs 72,614 | 0 / 399 / 277 / 314 / 1324 |  |
|  | final | 1313 | 45% | Rs 24,827 | -Rs 384,324 | 83 of 133 | -Rs 9,077 | -Rs 99,379 | -Rs 76,548 | 0 / 206 / 113 / 98 / 896 | p 1.0, q 1.0; fail: design: worst month worse; design: the arm no longer makes money; wf: drawdown worse; wf: worst month worse; wf: the arm no longer makes money; final: drawdown worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 32 | 47% | Rs 1,045 | -Rs 15,117 | 2 of 4 | -Rs 10,094 | -Rs 15,117 | Rs 1,045 | 0 / 8 / 5 / 6 / 13 |  |
| **C4** | design | 1484 | 49% | Rs 167,068 | -Rs 711,067 | 194 of 326 | -Rs 16,911 | -Rs 88,858 | -Rs 52,326 | 0 / 371 / 178 / 281 / 654 |  |
|  | wf | 1053 | 50% | Rs 101,259 | -Rs 495,044 | 142 of 240 | -Rs 16,911 | -Rs 88,858 | -Rs 52,326 | 0 / 250 / 117 / 200 / 486 |  |
|  | final | 577 | 50% | Rs 82,673 | -Rs 222,622 | 70 of 130 | -Rs 9,077 | -Rs 50,581 | -Rs 30,863 | 0 / 107 / 55 / 60 / 355 | p 1.0, q 1.0; fail: twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 17 | 35% | -Rs 8,594 | -Rs 15,117 | 2 of 3 | -Rs 10,094 | -Rs 15,117 | -Rs 8,594 | 0 / 7 / 4 / 3 / 3 |  |

Twins (final, sum of losing days): C1: exit p 1 (twin mean -Rs 279,130); C2: skip p 1 (twin mean -Rs 237,620); C3: exit p 1 (twin mean -Rs 273,284), skip p 1 (twin mean -Rs 261,944); C4: exit p 1 (twin mean -Rs 172,574), skip p 0.226 (twin mean -Rs 239,053).

#### 2 lots

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 45% | Rs 295,708 | -Rs 2,589,868 | 181 of 337 | -Rs 98,679 | -Rs 381,894 | -Rs 127,531 | 0 / 0 / 796 / 73 / 3339 |  |
|  | wf | 3044 | 46% | Rs 568,032 | -Rs 1,634,452 | 123 of 247 | -Rs 57,260 | -Rs 179,011 | -Rs 95,481 | 0 / 0 / 512 / 53 / 2479 |  |
|  | final | 1670 | 46% | Rs 346,548 | -Rs 751,245 | 69 of 133 | -Rs 42,690 | -Rs 145,608 | -Rs 96,531 | 0 / 0 / 219 / 14 / 1437 |  |
|  | post | 46 | 50% | Rs 8,947 | -Rs 40,466 | 2 of 4 | -Rs 26,343 | -Rs 40,466 | Rs 8,947 | 0 / 0 / 12 / 2 / 32 |  |
| **C1** | design | 4333 | 46% | -Rs 93,088 | -Rs 1,869,341 | 203 of 337 | -Rs 31,824 | -Rs 310,009 | -Rs 108,223 | 0 / 1900 / 88 / 1305 / 1040 |  |
|  | wf | 3126 | 47% | Rs 92,772 | -Rs 1,258,915 | 144 of 247 | -Rs 24,353 | -Rs 156,947 | -Rs 108,223 | 0 / 1317 / 60 / 921 / 828 |  |
|  | final | 1701 | 45% | Rs 135,927 | -Rs 702,038 | 80 of 133 | -Rs 30,388 | -Rs 109,492 | -Rs 49,367 | 0 / 721 / 11 / 414 / 555 | p 1.0, q 1.0; fail: design: the arm no longer makes money; wf: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 48 | 48% | -Rs 5,167 | -Rs 31,603 | 2 of 4 | -Rs 24,800 | -Rs 31,603 | -Rs 5,167 | 0 / 24 / 1 / 15 / 8 |  |
| **C2** | design | 2662 | 46% | Rs 272,912 | -Rs 2,025,204 | 214 of 337 | -Rs 52,365 | -Rs 245,842 | -Rs 127,515 | 0 / 0 / 553 / 50 / 2059 |  |
|  | wf | 1980 | 47% | Rs 311,515 | -Rs 1,418,611 | 151 of 247 | -Rs 52,365 | -Rs 186,421 | -Rs 127,515 | 0 / 0 / 367 / 40 / 1573 |  |
|  | final | 1170 | 44% | Rs 152,504 | -Rs 674,595 | 83 of 133 | -Rs 27,904 | -Rs 160,522 | -Rs 116,416 | 0 / 0 / 164 / 10 / 996 | p 1.0, q 1.0; fail: wf: drawdown worse; wf: worst month worse; final: drawdown worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 28 | 64% | Rs 32,165 | -Rs 17,248 | 2 of 4 | -Rs 9,999 | -Rs 17,248 | Rs 32,165 | 0 / 0 / 5 / 1 / 22 |  |
| **C3** | design | 2739 | 47% | Rs 134,482 | -Rs 1,372,109 | 230 of 337 | -Rs 31,678 | -Rs 163,115 | -Rs 71,293 | 0 / 1212 / 50 / 849 / 628 |  |
|  | wf | 2047 | 49% | Rs 179,489 | -Rs 978,527 | 164 of 247 | -Rs 31,678 | -Rs 130,455 | -Rs 71,293 | 0 / 866 / 32 / 636 / 513 |  |
|  | final | 1153 | 46% | Rs 113,354 | -Rs 536,741 | 93 of 133 | -Rs 11,217 | -Rs 116,447 | -Rs 80,705 | 0 / 491 / 10 / 282 / 370 | p 1.0, q 1.0; fail: twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 22 | 41% | -Rs 19,268 | -Rs 30,956 | 3 of 4 | -Rs 20,507 | -Rs 30,956 | -Rs 19,268 | 0 / 12 / 1 / 7 / 2 |  |
| **C4** | design | 1630 | 48% | Rs 163,538 | -Rs 1,056,351 | 199 of 326 | -Rs 31,678 | -Rs 93,393 | -Rs 57,729 | 0 / 770 / 24 / 560 / 276 |  |
|  | wf | 1149 | 49% | Rs 123,766 | -Rs 734,761 | 145 of 240 | -Rs 31,678 | -Rs 93,393 | -Rs 57,729 | 0 / 526 / 15 / 399 / 209 |  |
|  | final | 622 | 48% | Rs 96,020 | -Rs 356,712 | 73 of 130 | -Rs 13,203 | -Rs 81,251 | -Rs 54,289 | 0 / 274 / 5 / 175 / 168 | p 1.0, q 1.0; fail: twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 19 | 26% | -Rs 19,824 | -Rs 28,023 | 2 of 3 | -Rs 20,507 | -Rs 28,023 | -Rs 19,824 | 0 / 13 / 1 / 3 / 2 |  |

Twins (final, sum of losing days): C1: exit p 1 (twin mean -Rs 365,845); C2: skip p 1 (twin mean -Rs 399,454); C3: exit p 1 (twin mean -Rs 349,252), skip p 1 (twin mean -Rs 368,423); C4: exit p 1 (twin mean -Rs 273,427), skip p 0.127 (twin mean -Rs 394,259).

#### 3 lots

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | design | 4208 | 45% | Rs 538,237 | -Rs 3,835,367 | 175 of 337 | -Rs 147,647 | -Rs 558,163 | -Rs 185,784 | 0 / 0 / 796 / 73 / 3339 |  |
|  | wf | 3044 | 46% | Rs 920,100 | -Rs 2,418,911 | 121 of 247 | -Rs 85,577 | -Rs 263,933 | -Rs 137,413 | 0 / 0 / 512 / 53 / 2479 |  |
|  | final | 1670 | 46% | Rs 557,695 | -Rs 1,107,670 | 67 of 133 | -Rs 63,776 | -Rs 209,599 | -Rs 138,454 | 0 / 0 / 219 / 14 / 1437 |  |
|  | post | 46 | 50% | Rs 14,470 | -Rs 60,086 | 2 of 4 | -Rs 39,207 | -Rs 60,086 | Rs 14,470 | 0 / 0 / 12 / 2 / 32 |  |
| **C1** | design | 4400 | 45% | -Rs 228,734 | -Rs 2,247,584 | 207 of 337 | -Rs 30,672 | -Rs 405,086 | -Rs 152,028 | 0 / 2227 / 17 / 1620 / 536 |  |
|  | wf | 3170 | 45% | -Rs 21,609 | -Rs 1,522,116 | 147 of 247 | -Rs 30,672 | -Rs 211,097 | -Rs 128,650 | 0 / 1585 / 8 / 1147 / 430 |  |
|  | final | 1736 | 41% | -Rs 86,805 | -Rs 924,022 | 85 of 133 | -Rs 29,030 | -Rs 200,444 | -Rs 97,009 | 0 / 915 / 0 / 513 / 308 | p 1.0, q 1.0; fail: design: the arm no longer makes money; wf: the arm no longer makes money; final: the arm no longer makes money; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 47 | 49% | Rs 8,802 | -Rs 16,884 | 2 of 4 | -Rs 14,339 | -Rs 16,884 | Rs 8,802 | 0 / 23 / 0 / 16 / 8 |  |
| **C2** | design | 2425 | 46% | Rs 343,285 | -Rs 2,949,861 | 225 of 337 | -Rs 78,453 | -Rs 419,782 | -Rs 179,074 | 0 / 0 / 514 / 48 / 1863 |  |
|  | wf | 1793 | 47% | Rs 407,412 | -Rs 2,045,912 | 162 of 247 | -Rs 78,453 | -Rs 260,056 | -Rs 179,074 | 0 / 0 / 339 / 39 / 1415 |  |
|  | final | 1118 | 45% | Rs 155,110 | -Rs 985,197 | 85 of 133 | -Rs 41,737 | -Rs 225,223 | -Rs 164,011 | 0 / 0 / 159 / 9 / 950 | p 1.0, q 1.0; fail: wf: worst month worse; final: drawdown worse; final: worst month worse; twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 27 | 67% | Rs 58,766 | -Rs 15,790 | 2 of 4 | -Rs 10,779 | -Rs 15,790 | Rs 58,766 | 0 / 0 / 4 / 1 / 22 |  |
| **C3** | design | 2755 | 46% | Rs 271,642 | -Rs 1,461,206 | 237 of 337 | -Rs 46,449 | -Rs 137,860 | -Rs 70,156 | 0 / 1366 / 4 / 1070 / 315 |  |
|  | wf | 2021 | 47% | Rs 197,970 | -Rs 1,071,135 | 171 of 247 | -Rs 46,449 | -Rs 137,860 | -Rs 70,156 | 0 / 999 / 1 / 769 / 252 |  |
|  | final | 1101 | 44% | Rs 92,304 | -Rs 559,297 | 95 of 133 | -Rs 11,412 | -Rs 136,790 | -Rs 68,309 | 0 / 558 / 0 / 352 / 191 | p 1.0, q 1.0; fail: twin p 1 >= 0.05; BH q 1 > 0.1 |
|  | post | 21 | 38% | -Rs 15,747 | -Rs 24,954 | 3 of 4 | -Rs 13,318 | -Rs 24,954 | -Rs 15,747 | 0 / 13 / 0 / 6 / 2 |  |
| **C4** | design | 1719 | 47% | Rs 242,768 | -Rs 1,169,412 | 193 of 326 | -Rs 46,449 | -Rs 100,423 | -Rs 66,477 | 0 / 845 / 3 / 703 / 168 |  |
|  | wf | 1210 | 49% | Rs 208,149 | -Rs 777,348 | 138 of 240 | -Rs 46,449 | -Rs 92,054 | -Rs 66,477 | 0 / 580 / 0 / 502 / 128 |  |
|  | final | 661 | 45% | -Rs 5,533 | -Rs 439,283 | 79 of 130 | -Rs 13,983 | -Rs 100,393 | -Rs 79,373 | 0 / 335 / 0 / 231 / 95 | p 0.7586, q 1.0; fail: final: the arm no longer makes money; twin p 0.759 >= 0.05; BH q 1 > 0.1 |
|  | post | 19 | 16% | -Rs 24,586 | -Rs 24,586 | 3 of 3 | -Rs 13,318 | -Rs 24,586 | -Rs 24,586 | 0 / 16 / 0 / 2 / 1 |  |

Twins (final, sum of losing days): C1: exit p 1 (twin mean -Rs 605,780); C2: skip p 1 (twin mean -Rs 573,397); C3: exit p 0.993 (twin mean -Rs 475,564), skip p 1 (twin mean -Rs 434,635); C4: exit p 0.759 (twin mean -Rs 419,294), skip p 0.033 (twin mean -Rs 514,213).

## Second look: on the trades R151's app pickers take (Apr - Oct 2026)

Descriptive only, never gated (the period was read many times; the pickers failed their own gates). A skipped break still holds its side, as in the app.

### R72 Breadth Donchian + R151 picker

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1 lot | 603 | 49% | Rs 169,674 | -Rs 233,631 | 46 of 102 | -Rs 21,301 | -Rs 31,620 | -Rs 20,632 | 0 / 0 / 79 / 10 / 514 |  |
| **C1** | 1 lot | 606 | 49% | Rs 132,634 | -Rs 228,880 | 52 of 102 | -Rs 23,631 | -Rs 41,397 | -Rs 16,315 | 0 / 104 / 47 / 75 / 380 |  |
| **C2** | 1 lot | 530 | 51% | Rs 189,335 | -Rs 198,933 | 48 of 102 | -Rs 14,064 | -Rs 36,059 | -Rs 7,649 | 0 / 0 / 68 / 10 / 452 |  |
| **C3** | 1 lot | 540 | 51% | Rs 152,261 | -Rs 204,715 | 53 of 102 | -Rs 14,436 | -Rs 28,572 | -Rs 7,166 | 0 / 85 / 44 / 69 / 342 |  |
| **C4** | 1 lot | 268 | 54% | Rs 89,182 | -Rs 106,832 | 38 of 74 | -Rs 8,976 | -Rs 35,122 | -Rs 24,433 | 0 / 45 / 23 / 32 / 168 |  |
| **B0** | 2 lots | 603 | 49% | Rs 367,222 | -Rs 455,067 | 44 of 102 | -Rs 41,948 | -Rs 60,657 | -Rs 34,292 | 0 / 0 / 79 / 10 / 514 |  |
| **C1** | 2 lots | 622 | 47% | Rs 191,835 | -Rs 339,993 | 54 of 103 | -Rs 25,487 | -Rs 77,143 | -Rs 42,507 | 0 / 273 / 4 / 166 / 179 |  |
| **C2** | 2 lots | 507 | 50% | Rs 376,887 | -Rs 394,158 | 50 of 102 | -Rs 27,904 | -Rs 73,934 | -Rs 11,100 | 0 / 0 / 65 / 10 / 432 |  |
| **C3** | 2 lots | 519 | 49% | Rs 238,768 | -Rs 293,200 | 56 of 103 | -Rs 11,941 | -Rs 48,088 | -Rs 13,935 | 0 / 226 / 3 / 139 / 151 |  |
| **C4** | 2 lots | 275 | 50% | Rs 107,572 | -Rs 177,662 | 40 of 74 | -Rs 9,985 | -Rs 64,510 | -Rs 42,734 | 0 / 120 / 3 / 73 / 79 |  |
| **B0** | 3 lots | 603 | 50% | Rs 564,570 | -Rs 676,826 | 44 of 102 | -Rs 62,591 | -Rs 89,763 | -Rs 48,023 | 0 / 0 / 79 / 10 / 514 |  |
| **C1** | 3 lots | 639 | 41% | Rs 17,007 | -Rs 442,587 | 65 of 105 | -Rs 21,663 | -Rs 106,877 | -Rs 60,299 | 0 / 336 / 0 / 198 / 105 |  |
| **C2** | 3 lots | 495 | 51% | Rs 573,080 | -Rs 585,415 | 51 of 102 | -Rs 41,737 | -Rs 109,825 | -Rs 24,713 | 0 / 0 / 64 / 10 / 421 |  |
| **C3** | 3 lots | 540 | 43% | Rs 102,588 | -Rs 338,628 | 67 of 105 | -Rs 10,773 | -Rs 76,885 | -Rs 31,824 | 0 / 274 / 0 / 174 / 92 |  |
| **C4** | 3 lots | 285 | 46% | Rs 50,938 | -Rs 199,016 | 42 of 74 | -Rs 12,514 | -Rs 57,535 | -Rs 31,429 | 0 / 141 / 0 / 93 / 51 |  |

### R51 Donchian 20 (+ Supertrend 10, 3) + R151 picker

| rule | period | trades | win | net | sum of losing days | losing days | worst day | max DD | worst month | exits (target / Rs 2k stop / -15% stop / lock / other) | verdict |
|---|---|---|---|---|---|---|---|---|---|---|---|
| **B0** | 1 lot | 653 | 50% | Rs 142,884 | -Rs 217,038 | 55 of 123 | -Rs 14,642 | -Rs 38,799 | -Rs 22,978 | 0 / 0 / 86 / 7 / 560 |  |
| **C1** | 1 lot | 659 | 49% | Rs 57,684 | -Rs 235,763 | 64 of 123 | -Rs 17,071 | -Rs 60,124 | -Rs 34,637 | 0 / 124 / 48 / 66 / 421 |  |
| **C2** | 1 lot | 583 | 50% | Rs 140,212 | -Rs 208,987 | 58 of 123 | -Rs 14,064 | -Rs 41,039 | -Rs 23,537 | 0 / 0 / 80 / 6 / 497 |  |
| **C3** | 1 lot | 582 | 49% | Rs 71,824 | -Rs 219,803 | 65 of 123 | -Rs 9,077 | -Rs 46,684 | -Rs 17,829 | 0 / 104 / 45 / 54 / 379 |  |
| **C4** | 1 lot | 387 | 53% | Rs 110,756 | -Rs 146,858 | 57 of 117 | -Rs 9,077 | -Rs 39,323 | -Rs 16,590 | 0 / 71 / 33 / 40 / 243 |  |
| **B0** | 2 lots | 653 | 51% | Rs 315,804 | -Rs 421,296 | 53 of 123 | -Rs 28,984 | -Rs 73,811 | -Rs 42,877 | 0 / 0 / 86 / 7 / 560 |  |
| **C1** | 2 lots | 685 | 48% | Rs 63,817 | -Rs 380,874 | 71 of 123 | -Rs 22,023 | -Rs 82,812 | -Rs 55,861 | 0 / 296 / 7 / 194 / 188 |  |
| **C2** | 2 lots | 563 | 51% | Rs 290,502 | -Rs 403,910 | 58 of 123 | -Rs 27,904 | -Rs 77,135 | -Rs 44,124 | 0 / 0 / 78 / 5 / 480 |  |
| **C3** | 2 lots | 563 | 50% | Rs 103,507 | -Rs 333,792 | 74 of 123 | -Rs 11,217 | -Rs 52,133 | -Rs 30,729 | 0 / 238 / 6 / 159 / 160 |  |
| **C4** | 2 lots | 406 | 51% | Rs 126,490 | -Rs 261,139 | 64 of 117 | -Rs 11,217 | -Rs 52,105 | -Rs 28,669 | 0 / 177 / 4 / 121 / 104 |  |
| **B0** | 3 lots | 653 | 51% | Rs 488,424 | -Rs 625,700 | 53 of 123 | -Rs 43,311 | -Rs 108,826 | -Rs 62,761 | 0 / 0 / 86 / 7 / 560 |  |
| **C1** | 3 lots | 704 | 43% | -Rs 54,231 | -Rs 496,615 | 70 of 124 | -Rs 20,501 | -Rs 146,237 | -Rs 54,442 | 0 / 373 / 0 / 226 / 105 |  |
| **C2** | 3 lots | 551 | 52% | Rs 484,969 | -Rs 561,542 | 59 of 123 | -Rs 41,737 | -Rs 105,989 | -Rs 67,713 | 0 / 0 / 77 / 5 / 469 |  |
| **C3** | 3 lots | 559 | 45% | Rs 76,242 | -Rs 357,953 | 73 of 124 | -Rs 10,773 | -Rs 87,276 | -Rs 33,738 | 0 / 282 / 0 / 190 / 87 |  |
| **C4** | 3 lots | 429 | 47% | Rs 68,694 | -Rs 303,160 | 64 of 117 | -Rs 10,657 | -Rs 50,292 | -Rs 34,676 | 0 / 208 / 0 / 156 / 65 |  |

