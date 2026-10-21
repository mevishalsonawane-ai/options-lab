# R21 results (release): design 33 expiry days, holdout 31 (read once)

Big days (hindsight: some Rs 1-10 option reached 5x before 15:05): design 24 / 33, holdout 23 / 31.

## design

| id | idea | trades | wins | net Rs | per expiry Rs | worst Rs | max DD Rs | big days caught / missed |
|---|---|---|---|---|---|---|---|---|
| V0 | baseline (app's Hero) | 9 | 1 | -17,054 | -517 | -3,413 | -17,054 | 1 / 23 |
| V1 | (a) one-sided k 2.5 | 7 | 3 | +10,338 | +313 | -3,413 | -9,391 | 3 / 21 |
| V2 | (b) straddle 10% in last 15 min | 9 | 3 | +3,870 | +117 | -3,413 | -12,742 | 3 / 21 |
| V3 | (a)+(c) to 15:00 | 8 | 3 | +7,398 | +224 | -3,413 | -9,391 | 3 / 21 |
| V4 | (a)+(c)+(d) Rs 1-15 | 10 | 5 | +44,527 | +1,349 | -3,318 | -6,215 | 5 / 19 |
| V5 | V4 + lock 2x after 4x | 10 | 5 | +45,227 | +1,371 | -3,318 | -6,215 | 5 / 19 |
| V6 | V4 + give-back trail | 10 | 5 | +46,170 | +1,399 | -3,318 | -6,215 | 5 / 19 |
| V7 | V4, plain 15:05 exit | 10 | 3 | +9,342 | +283 | -4,748 | -28,367 | 5 / 19 |
| V8 | (f) blind 13:30 | 31 | 4 | -46,285 | -1,403 | -3,502 | -46,376 | 4 / 20 |

## holdout

| id | idea | trades | wins | net Rs | per expiry Rs | worst Rs | max DD Rs | big days caught / missed |
|---|---|---|---|---|---|---|---|---|
| V0 | baseline (app's Hero) | 2 | 0 | -6,259 | -202 | -3,203 | -6,259 | 0 / 23 |
| V1 | (a) one-sided k 2.5 | 11 | 0 | -35,188 | -1,135 | -3,387 | -35,188 | 0 / 23 |
| V2 | (b) straddle 10% in last 15 min | 13 | 1 | -18,837 | -608 | -3,899 | -22,721 | 1 / 22 |
| V3 | (a)+(c) to 15:00 | 11 | 0 | -35,188 | -1,135 | -3,387 | -35,188 | 0 / 23 |
| V4 | (a)+(c)+(d) Rs 1-15 | 15 | 0 | -46,268 | -1,493 | -3,267 | -46,268 | 0 / 23 |
| V5 | V4 + lock 2x after 4x | 15 | 0 | -46,268 | -1,493 | -3,267 | -46,268 | 0 / 23 |
| V6 | V4 + give-back trail | 15 | 0 | -46,268 | -1,493 | -3,267 | -46,268 | 0 / 23 |
| V7 | V4, plain 15:05 exit | 15 | 0 | -61,863 | -1,996 | -4,850 | -61,863 | 0 / 23 |
| V8 | (f) blind 13:30 | 30 | 2 | -66,764 | -2,154 | -4,013 | -66,764 | 1 / 22 |

## gates

| id | holdout 2x spread Rs | twin mean Rs | twin p | BH q | G1 design>0 | G2 holdout>0, >=5 | G3 q<0.05 | G4 stress | G5 > V0 | passes |
|---|---|---|---|---|---|---|---|---|---|---|
| V0 | -6,507 | +17,881 | 0.704 | 1.000 | no | no | no | no | no | fail |
| V1 | -35,750 | +7,787 | 0.905 | 1.000 | yes | no | no | no | no | fail |
| V2 | -19,624 | +26,154 | 0.919 | 1.000 | yes | no | no | no | no | fail |
| V3 | -35,750 | +960 | 0.846 | 1.000 | yes | no | no | no | no | fail |
| V4 | -47,099 | +9,319 | 0.996 | 1.000 | yes | no | no | no | no | fail |
| V5 | -47,099 | +9,022 | 1.000 | 1.000 | yes | no | no | no | no | fail |
| V6 | -47,099 | +6,626 | 0.999 | 1.000 | yes | no | no | no | no | fail |
| V7 | -62,651 | +3,504 | 1.000 | 1.000 | yes | no | no | no | no | fail |
| V8 | -69,081 | -85,716 | 0.006 | 0.054 | no | no | no | no | no | fail |

## walk-forward (V4, k re-chosen monthly on earlier expiries): 22 trades, 3 wins, net Rs -24,812

| month | k | expiries | trades | net Rs |
|---|---|---|---|---|
| 2025-06 | 4.0 | 5 | 1 | +7,729 |
| 2025-07 | 3.0 | 6 | 3 | -9,508 |
| 2025-08 | 2.5 | 5 | 1 | +16,021 |
| 2025-09 | 2.5 | 6 | 1 | +11,036 |
| 2025-10 | 2.5 | 5 | 3 | -9,022 |
| 2025-11 | 2.5 | 5 | 1 | -3,015 |
| 2025-12 | 2.0 | 4 | 1 | -3,173 |
| 2026-01 | 2.0 | 5 | 3 | -9,277 |
| 2026-02 | 2.0 | 4 | 1 | -3,151 |
| 2026-03 | 2.0 | 5 | 5 | -16,051 |
| 2026-04 | 2.0 | 2 | 2 | -6,401 |
