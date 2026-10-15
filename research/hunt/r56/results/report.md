# R56 results: Boss's manual NIFTY ORB plan

Signal source: the NIFTY index (1-minute closes vs the 09:15-09:29 range). Instrument: ATM option, nearest expiry, 1 lot. Rupees per 1 lot, net of dated charges and slippage unless marked gross.

## r56-boss-orb

| part | trades | win | target / stop / sq-off | avg win | avg loss | net | net/trade | worst day | worst 5 sessions | max DD | losing streak | months green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole | 467 | 69% | 69% / 22% / 9% | Rs +983 | Rs -2,754 | Rs -79,190 | Rs -170 | Rs -6,963 | Rs -13,977 | Rs -82,634 | 9 | 7 / 24 |
| design | 333 | 70% | 70% / 21% / 9% | Rs +986 | Rs -2,755 | Rs -45,736 | Rs -137 | Rs -6,963 | Rs -12,510 | Rs -63,826 | 9 | 5 / 17 |
| recent | 134 | 67% | 66% / 23% / 10% | Rs +973 | Rs -2,750 | Rs -33,454 | Rs -250 | Rs -6,658 | Rs -13,977 | Rs -33,797 | 3 | 2 / 7 |

### Random-entry twins (1,000; same days, same sides, same exits; entry minute uniform 09:30-14:59)

| part | rule net | twin mean | twin 5%-95% | p |
|---|---|---|---|---|
| whole | Rs -79,190 | Rs -20,709 | Rs -69,404 .. Rs +24,080 | 0.974 |
| design | Rs -45,736 | Rs -13,347 | Rs -52,777 .. Rs +25,457 | 0.907 |
| recent | Rs -33,454 | Rs -7,362 | Rs -34,409 .. Rs +16,875 | 0.946 |

### Sessions

- whole: 471 sessions, {'traded': 467, 'no_signal': 3, 'no_range': 1}
- design: 337 sessions, {'traded': 333, 'no_signal': 3, 'no_range': 1}
- recent: 134 sessions, {'traded': 134}

### Loss size (net, per losing trade)

| part | losers | worse than -Rs 1,000 | median | 75th pct | 90th pct | worst | buckets (Rs) |
|---|---|---|---|---|---|---|---|
| whole | 144 | 128 (89%) | Rs 2,606 | Rs 3,569 | Rs 4,845 | Rs 6,963 | 0-500: 5, 500-1000: 11, 1000-1500: 18, 1500-2000: 13, 2000-3000: 39, 3000-inf: 58 |
| design | 100 | 88 (88%) | Rs 2,576 | Rs 3,507 | Rs 4,944 | Rs 6,963 | 0-500: 3, 500-1000: 9, 1000-1500: 13, 1500-2000: 9, 2000-3000: 26, 3000-inf: 40 |
| recent | 44 | 40 (91%) | Rs 2,674 | Rs 3,676 | Rs 4,193 | Rs 6,658 | 0-500: 2, 500-1000: 2, 1000-1500: 5, 1500-2000: 4, 2000-3000: 13, 3000-inf: 18 |

### Target exits: gross trigger vs net

| part | target exits | avg gross | avg charges | avg net |
|---|---|---|---|---|
| whole | 322 | Rs +1,049 | Rs 65 | Rs +984 |
| design | 233 | Rs +1,049 | Rs 64 | Rs +985 |
| recent | 89 | Rs +1,048 | Rs 68 | Rs +980 |

### Splits (whole span / design / recent)

| split | whole | design | recent |
|---|---|---|---|
| expiry days | 99 tr, 71% win, Rs -6,368 | 71 tr, 75% win, Rs +1,150 | 28 tr, 61% win, Rs -7,518 |
| other days | 368 tr, 69% win, Rs -72,822 | 262 tr, 69% win, Rs -46,886 | 106 tr, 69% win, Rs -25,936 |
| calls | 222 tr, 73% win, Rs -24,379 | 159 tr, 72% win, Rs -23,281 | 63 tr, 73% win, Rs -1,097 |
| puts | 245 tr, 66% win, Rs -54,811 | 174 tr, 68% win, Rs -22,455 | 71 tr, 62% win, Rs -32,357 |
| by lot | lot 25: 29 tr Rs -11,842; lot 65: 195 tr Rs -21,120; lot 75: 243 tr Rs -46,228 | lot 25: 29 tr Rs -11,842; lot 65: 61 tr Rs +12,334; lot 75: 243 tr Rs -46,228 | lot 65: 134 tr Rs -33,454 |

### Per month (whole span)

| month | trades | win | net |
|---|---|---|---|
| 2024-11 | 7 | 43% | Rs -3,636 |
| 2024-12 | 20 | 40% | Rs -7,152 |
| 2025-01 | 23 | 65% | Rs -7,244 |
| 2025-02 | 20 | 80% | Rs -1,457 |
| 2025-03 | 19 | 89% | Rs +11,162 |
| 2025-04 | 18 | 83% | Rs +8,593 |
| 2025-05 | 20 | 80% | Rs -4,511 |
| 2025-06 | 21 | 71% | Rs -2,644 |
| 2025-07 | 23 | 61% | Rs -10,888 |
| 2025-08 | 19 | 58% | Rs -14,567 |
| 2025-09 | 22 | 86% | Rs +16,355 |
| 2025-10 | 20 | 65% | Rs -10,763 |
| 2025-11 | 19 | 53% | Rs -15,045 |
| 2025-12 | 22 | 55% | Rs -15,447 |
| 2026-01 | 20 | 80% | Rs +7,501 |
| 2026-02 | 21 | 76% | Rs -2,290 |
| 2026-03 | 19 | 89% | Rs +6,297 |
| 2026-04 | 20 | 75% | Rs -7,940 |
| 2026-05 | 19 | 74% | Rs -8,107 |
| 2026-06 | 21 | 76% | Rs +6,826 |
| 2026-07 | 23 | 52% | Rs -14,523 |
| 2026-08 | 21 | 57% | Rs -6,804 |
| 2026-09 | 21 | 71% | Rs +2,642 |
| 2026-10 | 9 | 67% | Rs -5,549 |

## c1-no-target (CONTEXT ONLY, not a decision)

| part | trades | win | target / stop / sq-off | avg win | avg loss | net | net/trade | worst day | worst 5 sessions | max DD | losing streak | months green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole | 467 | 36% | 0% / 38% / 62% | Rs +4,103 | Rs -2,782 | Rs -156,206 | Rs -334 | Rs -9,636 | Rs -22,930 | Rs -203,887 | 12 | 7 / 24 |
| design | 333 | 36% | 0% / 39% / 61% | Rs +4,317 | Rs -2,857 | Rs -90,496 | Rs -272 | Rs -9,636 | Rs -20,842 | Rs -139,240 | 12 | 5 / 17 |
| recent | 134 | 34% | 0% / 37% / 63% | Rs +3,546 | Rs -2,600 | Rs -65,710 | Rs -490 | Rs -6,658 | Rs -22,930 | Rs -97,892 | 12 | 2 / 7 |

## c2-hard-stop-1000 (CONTEXT ONLY, not a decision)

| part | trades | win | target / stop / sq-off | avg win | avg loss | net | net/trade | worst day | worst 5 sessions | max DD | losing streak | months green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole | 467 | 48% | 48% / 51% / 1% | Rs +973 | Rs -1,073 | Rs -40,765 | Rs -87 | Rs -2,409 | Rs -5,514 | Rs -51,766 | 9 | 7 / 24 |
| design | 333 | 51% | 51% / 49% / 0% | Rs +976 | Rs -1,080 | Rs -12,230 | Rs -37 | Rs -2,409 | Rs -5,485 | Rs -30,320 | 9 | 7 / 17 |
| recent | 134 | 42% | 42% / 57% / 1% | Rs +966 | Rs -1,059 | Rs -28,535 | Rs -213 | Rs -1,119 | Rs -5,514 | Rs -30,334 | 8 | 0 / 7 |

## c3-limit-target (CONTEXT ONLY, not a decision)

| part | trades | win | target / stop / sq-off | avg win | avg loss | net | net/trade | worst day | worst 5 sessions | max DD | losing streak | months green |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| whole | 467 | 69% | 69% / 22% / 9% | Rs +932 | Rs -2,773 | Rs -94,368 | Rs -202 | Rs -6,963 | Rs -14,188 | Rs -94,665 | 9 | 7 / 24 |
| design | 333 | 70% | 70% / 21% / 9% | Rs +935 | Rs -2,783 | Rs -56,773 | Rs -170 | Rs -6,963 | Rs -12,510 | Rs -71,618 | 9 | 5 / 17 |
| recent | 134 | 67% | 66% / 23% / 10% | Rs +927 | Rs -2,750 | Rs -37,595 | Rs -281 | Rs -6,658 | Rs -14,188 | Rs -37,892 | 3 | 2 / 7 |

