# R155: every Donchian filter, OI and candle wicks on the 10-15 point scalps

## For Boss (short)

- **Verdict: nothing fixes 10-15 point scalping.** 0 of 280 filter x entry x reading tests passed the gates.
- **Without filters (R154):** option points win 51% (needed 60%), -Rs 119 a trade in Apr - Oct 2026; index points win 43% (needed 51%), -Rs 79.
- **Best filter on option points:** F20 PICK: logistic picker on every feature (walk-forward): win 57% (needed 62%), -Rs 73 a trade in the final (-Rs 32 in the walk-forward year).
- **Best filter on index points:** F20 PICK: logistic picker on every feature (walk-forward): win 46% (needed 52%), -Rs 64 a trade in the final (-Rs 34 in the walk-forward year).
- **By family (best on the final):** S/R: F5 (PIV); trend: F9 (ST); OI: F11 (OIB); wick: F14 (WKA5); all: F20 (PICK).
- **Filters that lost less than no filter in BOTH the walk-forward year and the final:** option points 6 of 20 (F20, F9, F14, F8, F17, F10); index points 8 of 20 (F20, F9, F18, F17, F14, F10, F8, F19). Losing less is not making money: the win rate stays below the rate needed.
- **Better than random, still losing:** 68 of 280 tests pick better trades than random skips of the same size (BH q <= 0.10; smallest q 0.0169), i.e. they lose less per trade, but every one of them still loses money, so none passes.
- **Made money in the final period:** 3 of 280 tests, all the picker: F20 E1 B Rs 96,375 on 345 trades (P1 -Rs 8,56,683, P2 -Rs 2,88,433); F20 E2 A Rs 1,592 on 53 trades (P1 -Rs 16,08,546, P2 -Rs 13,511); F20 E4 A Rs 83 on 109 trades (P1 -Rs 26,54,735, P2 -Rs 81,080). Each lost money in an earlier period, so none is a result.
- **Single cells** (one filter, one entry, one index, one exit; 16,632 tried): 11 made money in all three periods. With that many tries a few always do by luck; they are listed at the end as data-mined, not as findings.
- Nothing changes in the app. Every test is logged in `research/experiments/entries/r155-*.yaml`.

Signal source: the index for every entry; the filters read the index's own bars (levels, pivots, EMA, Supertrend, average price, candle wicks) or the option chain as context (OI build-up, PCR, the bought option's price and OI).

## How it was tested

Pre-registered in `PREREG.md` before any number. R154's 7 entries x indices x 24 exits (10 / 12 / 15 points on the option or the index, stop 1:1 or 1:1.5, 15 / 30 minutes, 15:15 square-off), with 20 filters taken from the Donchian studies: support / resistance lines (W5, W20, W50, 15-min 3-month majors, floor pivots, all lines at once, crossed a level), trend (EMA20, Supertrend, session average price), OI (build-up, PCR change, R144c's price-and-OI fall) and candle wicks (wick against on 5-min and 1-min bars, rejection wick, close location, strong body), a 5-of-7 vote and a walk-forward logistic picker on every feature. A filter skips the signal before the walk, so the freed slot can take the next signal (as R101). The unfiltered run reproduces R154 exactly for every entry, index, exit and period (asserted). Gated unit: filter x entry x reading (280), pooled over the indices and 12 exits; gates: net > 0 in P1, P2 and P3, better per trade than no filter in P2 and P3, beats 2,000 random skips with BH q <= 0.10 (m = 1,962), >= 40 final trades per exit, the arms above their own exits. P3 (Apr - 13 Oct 2026) was read for these scalps by R154: this is a second look. Implementation note: the random-skip subsets (uniform, without replacement) were first drawn one by one, which was too slow; they are drawn in batches as the n smallest of uniform keys, the same distribution. The switch was made before any gate result was seen.

## Every filter pooled over all entries, option points (reading A), 1 lot

| filter | P1 design: trades / win / needed / a trade | P2 walk-forward: trades / win / needed / a trade | P3 final (2nd look): trades / win / needed / a trade | a trade vs no filter (P3) |
|---|---|---|---|---|
| F0 no filter (R154's scalp) | 514,709 / 47% / 58% / -Rs 103 | 256,999 / 52% / 58% / -Rs 90 | 137,013 / 51% / 60% / -Rs 119 | - |
| F1 W5: no 5-min S/R level (5 sessions) within 0.25% ahead | 373,131 / 48% / 59% / -Rs 105 | 182,370 / 52% / 58% / -Rs 93 | 97,645 / 50% / 60% / -Rs 132 | -Rs 13 |
| F2 W20: no 5-min S/R level (20 sessions) within 0.25% ahead | 310,057 / 47% / 59% / -Rs 110 | 144,408 / 51% / 58% / -Rs 109 | 78,191 / 50% / 59% / -Rs 125 | -Rs 6 |
| F3 W50: no 5-min S/R level (50 sessions) within 0.25% ahead | 282,243 / 47% / 59% / -Rs 108 | 133,683 / 50% / 58% / -Rs 125 | 68,189 / 49% / 60% / -Rs 136 | -Rs 17 |
| F4 W3M: no 15-min major level (3 months) within 0.25% ahead | 433,566 / 48% / 59% / -Rs 104 | 198,600 / 51% / 58% / -Rs 102 | 111,756 / 50% / 60% / -Rs 131 | -Rs 12 |
| F5 PIV: no floor pivot (P, R1, S1, R2, S2) within 0.25% ahead | 379,359 / 48% / 59% / -Rs 101 | 177,190 / 51% / 58% / -Rs 99 | 99,553 / 50% / 60% / -Rs 128 | -Rs 9 |
| F6 ALLSR: no W20 / W50 / W3M / pivot line within 0.25% ahead (all lines at once) | 155,009 / 48% / 59% / -Rs 109 | 68,280 / 51% / 59% / -Rs 135 | 34,821 / 50% / 60% / -Rs 129 | -Rs 10 |
| F7 B20: the last 5 minutes crossed a 20-session level the trade's way | 256,787 / 48% / 58% / -Rs 98 | 122,657 / 51% / 57% / -Rs 98 | 66,253 / 49% / 59% / -Rs 135 | -Rs 16 |
| F8 EMA20: last 5-min close on the trade's side of EMA20 | 486,660 / 47% / 58% / -Rs 103 | 247,778 / 52% / 58% / -Rs 87 | 131,625 / 51% / 60% / -Rs 116 | Rs 3 |
| F9 ST: Supertrend (10, 3) on 5-min bars agrees | 415,058 / 48% / 59% / -Rs 101 | 219,626 / 53% / 58% / -Rs 88 | 118,189 / 51% / 60% / -Rs 112 | Rs 7 |
| F10 TWAP: index on the trade's side of the session average price | 497,066 / 47% / 58% / -Rs 100 | 247,185 / 52% / 58% / -Rs 85 | 130,820 / 51% / 59% / -Rs 118 | Rs 1 |
| F11 OIB: 15-min OI build-up (ATM +-3) agrees | 451,298 / 47% / 58% / -Rs 104 | 237,058 / 51% / 58% / -Rs 106 | 125,668 / 49% / 59% / -Rs 126 | -Rs 7 |
| F12 PCR: 15-min PCR change agrees | 315,452 / 47% / 58% / -Rs 104 | 162,385 / 50% / 58% / -Rs 116 | 85,837 / 49% / 60% / -Rs 133 | -Rs 14 |
| F13 FT3: not (option price and OI both fell in 15 min) | 461,001 / 47% / 58% / -Rs 105 | 241,618 / 50% / 58% / -Rs 109 | 127,518 / 49% / 59% / -Rs 129 | -Rs 10 |
| F14 WKA5: last 5-min wick against the trade < 50% of the bar | 497,126 / 47% / 58% / -Rs 104 | 249,163 / 52% / 58% / -Rs 86 | 131,428 / 51% / 59% / -Rs 115 | Rs 4 |
| F15 WKA1: last 1-min wick against the trade < 50% of the bar | 489,500 / 47% / 59% / -Rs 103 | 243,697 / 52% / 58% / -Rs 92 | 129,647 / 50% / 60% / -Rs 122 | -Rs 3 |
| F16 WKF5: last 5-min wick for the trade >= 50% (rejection candle) | 231,064 / 45% / 58% / -Rs 109 | 92,960 / 50% / 58% / -Rs 116 | 48,208 / 48% / 59% / -Rs 132 | -Rs 13 |
| F17 CLOC: last 5-min close in the trade-side third of the bar | 456,486 / 47% / 58% / -Rs 105 | 228,196 / 52% / 58% / -Rs 84 | 120,798 / 50% / 59% / -Rs 116 | Rs 3 |
| F18 BODY: last 5-min body >= 60% of the bar, coloured the trade's way | 401,078 / 47% / 58% / -Rs 100 | 202,456 / 52% / 58% / -Rs 84 | 108,126 / 50% / 59% / -Rs 122 | -Rs 3 |
| F19 STACK: at least 5 of 7 (ALLSR, EMA20, ST, TWAP, OIB, PCR, CLOC) agree | 369,464 / 47% / 58% / -Rs 103 | 201,283 / 51% / 58% / -Rs 100 | 106,365 / 50% / 59% / -Rs 118 | Rs 1 |
| F20 PICK: logistic picker on every feature (walk-forward) | 88,249 / 50% / 59% / -Rs 104 | 23,480 / 57% / 59% / -Rs 32 | 11,694 / 57% / 62% / -Rs 73 | Rs 46 |

## Every filter pooled over all entries, index points (reading B), 1 lot

| filter | P1 design: trades / win / needed / a trade | P2 walk-forward: trades / win / needed / a trade | P3 final (2nd look): trades / win / needed / a trade | a trade vs no filter (P3) |
|---|---|---|---|---|
| F0 no filter (R154's scalp) | 522,205 / 42% / 52% / -Rs 75 | 260,994 / 46% / 52% / -Rs 62 | 139,253 / 43% / 51% / -Rs 79 | - |
| F1 W5: no 5-min S/R level (5 sessions) within 0.25% ahead | 377,655 / 42% / 52% / -Rs 75 | 184,768 / 46% / 52% / -Rs 62 | 99,237 / 43% / 51% / -Rs 85 | -Rs 6 |
| F2 W20: no 5-min S/R level (20 sessions) within 0.25% ahead | 313,843 / 42% / 53% / -Rs 80 | 146,443 / 45% / 52% / -Rs 72 | 79,359 / 42% / 51% / -Rs 90 | -Rs 11 |
| F3 W50: no 5-min S/R level (50 sessions) within 0.25% ahead | 285,887 / 42% / 53% / -Rs 79 | 135,599 / 44% / 52% / -Rs 83 | 69,310 / 42% / 51% / -Rs 92 | -Rs 13 |
| F4 W3M: no 15-min major level (3 months) within 0.25% ahead | 439,268 / 42% / 52% / -Rs 74 | 201,546 / 45% / 52% / -Rs 73 | 113,486 / 42% / 51% / -Rs 86 | -Rs 7 |
| F5 PIV: no floor pivot (P, R1, S1, R2, S2) within 0.25% ahead | 383,578 / 43% / 52% / -Rs 71 | 179,430 / 46% / 51% / -Rs 63 | 101,071 / 43% / 51% / -Rs 82 | -Rs 3 |
| F6 ALLSR: no W20 / W50 / W3M / pivot line within 0.25% ahead (all lines at once) | 157,211 / 43% / 53% / -Rs 73 | 69,260 / 46% / 53% / -Rs 92 | 35,464 / 42% / 51% / -Rs 94 | -Rs 15 |
| F7 B20: the last 5 minutes crossed a 20-session level the trade's way | 258,667 / 42% / 52% / -Rs 79 | 123,821 / 46% / 52% / -Rs 64 | 66,836 / 42% / 49% / -Rs 76 | Rs 3 |
| F8 EMA20: last 5-min close on the trade's side of EMA20 | 492,018 / 42% / 52% / -Rs 77 | 250,850 / 47% / 52% / -Rs 57 | 133,310 / 43% / 51% / -Rs 78 | Rs 1 |
| F9 ST: Supertrend (10, 3) on 5-min bars agrees | 417,952 / 42% / 52% / -Rs 75 | 221,408 / 47% / 52% / -Rs 59 | 119,166 / 44% / 51% / -Rs 73 | Rs 6 |
| F10 TWAP: index on the trade's side of the session average price | 503,919 / 42% / 52% / -Rs 75 | 250,909 / 46% / 52% / -Rs 58 | 133,021 / 43% / 51% / -Rs 77 | Rs 2 |
| F11 OIB: 15-min OI build-up (ATM +-3) agrees | 455,800 / 42% / 53% / -Rs 80 | 240,030 / 45% / 52% / -Rs 75 | 127,298 / 42% / 51% / -Rs 80 | -Rs 1 |
| F12 PCR: 15-min PCR change agrees | 318,506 / 43% / 53% / -Rs 78 | 164,330 / 45% / 52% / -Rs 78 | 86,991 / 42% / 52% / -Rs 94 | -Rs 15 |
| F13 FT3: not (option price and OI both fell in 15 min) | 467,433 / 42% / 53% / -Rs 80 | 245,424 / 45% / 52% / -Rs 77 | 129,738 / 42% / 51% / -Rs 82 | -Rs 3 |
| F14 WKA5: last 5-min wick against the trade < 50% of the bar | 504,199 / 41% / 52% / -Rs 76 | 253,066 / 46% / 52% / -Rs 60 | 133,526 / 43% / 51% / -Rs 77 | Rs 2 |
| F15 WKA1: last 1-min wick against the trade < 50% of the bar | 496,542 / 42% / 52% / -Rs 77 | 247,532 / 46% / 52% / -Rs 62 | 131,682 / 43% / 51% / -Rs 77 | Rs 2 |
| F16 WKF5: last 5-min wick for the trade >= 50% (rejection candle) | 232,254 / 41% / 53% / -Rs 83 | 93,560 / 44% / 52% / -Rs 81 | 48,626 / 41% / 51% / -Rs 89 | -Rs 10 |
| F17 CLOC: last 5-min close in the trade-side third of the bar | 462,584 / 41% / 52% / -Rs 77 | 231,513 / 46% / 51% / -Rs 49 | 122,650 / 43% / 51% / -Rs 76 | Rs 3 |
| F18 BODY: last 5-min body >= 60% of the bar, coloured the trade's way | 405,322 / 41% / 51% / -Rs 73 | 204,746 / 46% / 51% / -Rs 53 | 109,623 / 43% / 51% / -Rs 75 | Rs 4 |
| F19 STACK: at least 5 of 7 (ALLSR, EMA20, ST, TWAP, OIB, PCR, CLOC) agree | 372,009 / 42% / 52% / -Rs 79 | 202,981 / 46% / 52% / -Rs 60 | 107,425 / 43% / 51% / -Rs 78 | Rs 1 |
| F20 PICK: logistic picker on every feature (walk-forward) | 95,587 / 45% / 53% / -Rs 65 | 30,139 / 49% / 52% / -Rs 34 | 14,408 / 46% / 52% / -Rs 64 | Rs 15 |

## Cells positive in all three periods (descriptive, data-mined)

| filter | cells (of 792) |
|---|---|
| F0 | 0 |
| F1 | 0 |
| F2 | 0 |
| F3 | 0 |
| F4 | 0 |
| F5 | 0 |
| F6 | 0 |
| F7 | 1 |
| F8 | 0 |
| F9 | 0 |
| F10 | 0 |
| F11 | 0 |
| F12 | 0 |
| F13 | 0 |
| F14 | 0 |
| F15 | 0 |
| F16 | 5 |
| F17 | 0 |
| F18 | 0 |
| F19 | 1 |
| F20 | 4 |

Best 10 cells by their weakest period (data-mined; each is one of 16,632 tries):

| filter | entry | index | exit | P1 / P2 / P3 net | P3 trades |
|---|---|---|---|---|---|
| F20 | E3 | MIDCPNIFTY | A15s22.5t15 | Rs 20,468 / Rs 6,184 / Rs 5,256 | 27 |
| F19 | E3 | MIDCPNIFTY | A15s22.5t15 | Rs 16,080 / Rs 5,164 / Rs 8,219 | 144 |
| F20 | R72 | MIDCPNIFTY | A15s22.5t15 | Rs 4,371 / Rs 3,912 / Rs 5,169 | 70 |
| F16 | E1 | MIDCPNIFTY | B15s22.5t30 | Rs 5,987 / Rs 3,136 / Rs 7,108 | 23 |
| F16 | E1 | MIDCPNIFTY | B15s15t30 | Rs 2,915 / Rs 3,662 / Rs 7,969 | 23 |
| F16 | E1 | MIDCPNIFTY | B15s22.5t15 | Rs 2,207 / Rs 6,152 / Rs 4,224 | 23 |
| F16 | E1 | MIDCPNIFTY | B15s15t15 | Rs 1,477 / Rs 5,487 / Rs 4,745 | 23 |
| F7 | E1 | MIDCPNIFTY | A15s15t15 | Rs 1,615 / Rs 8,695 / Rs 338 | 69 |
| F20 | E2 | SENSEX | B12s12t30 | Rs 761 / Rs 530 / Rs 287 | 2 |
| F20 | E2 | SENSEX | B10s15t15 | Rs 722 / Rs 223 / Rs 387 | 1 |

Full gate table (280 tests): `results/tables.md`.

## Post window (14-19 Oct 2026, 4 sessions, never gated)

| filter | reading | trades | win | net |
|---|---|---|---|---|
| F0 | A | 4,077 | 49% | -Rs 6,57,032 |
| F0 | B | 4,122 | 45% | -Rs 6,14,629 |
| F20 | A | 410 | 35% | -Rs 2,51,444 |
| F20 | B | 447 | 40% | -Rs 1,61,580 |
