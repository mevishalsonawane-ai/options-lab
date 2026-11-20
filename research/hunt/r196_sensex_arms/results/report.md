# R196: SENSEX in R72, R51 and Liquidity 15+5 with today's exits

Pre-registration: `research/hunt/r196_sensex_arms/PREREG.md`. Span 1 Nov 2024 - 13 Nov 2026, 1 lot, dated Zerodha charges (BSE rates for SENSEX), R17 fills. Signal source of every rule: the index; options are only bought.

## Reproduction (R193's pooled totals, all indices)

| line | trades | net | R193 | gap |
|---|---:|---:|---:|---:|
| A:R72 | 4,571 | Rs 390,101 | 4,571 / Rs 390,101 | +0 / Rs 0 |
| R72-V5 | 2,352 | Rs 358,078 | 2,352 / Rs 358,078 | +0 / Rs 0 |
| A:R51 | 6,269 | Rs 191,394 | 6,269 / Rs 191,394 | +0 / Rs 0 |
| R51-V5 | 3,082 | Rs 203,580 | 3,082 / Rs 203,580 | +0 / Rs 0 |
| A:LIQ | 1,323 | Rs 255,293 | 1,323 / Rs 255,293 | +0 / Rs 0 |
| LIQ-V5 | 1,323 | Rs 220,469 | 1,323 / Rs 220,469 | +0 / Rs 0 |

LIQ others unchanged by SENSEX (A): yes (1,323 vs 1,323 trades)

LIQ others unchanged by SENSEX (V5): yes (1,323 vs 1,323 trades)

## R72-SX: R72 Breadth Donchian on SENSEX, live rule (picker + V5)

**Verdict: FAILED** (twin p 0.1474, normal p 0.151, BH q 1, m = 4,811; walk-forward 4 of 6 W1 quarters positive).

Failed: W2: net Rs -2,833 not above 0; twin p 0.1474 >= 0.05; BH q 1 > 0.1.

Live rule (V5 + R151 picker), SENSEX trades only:

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 295 | 66.4% | Rs 38,518 | Rs 864 / -Rs 1,321 | Rs 19,985 | 2025-02 -Rs 6,145 | Rs 18,142 | ok |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 145 | 54.5% | -Rs 2,833 | Rs 990 / -Rs 1,228 | Rs 12,110 | 2026-04 -Rs 6,917 | -Rs 17,335 | ok |
| total 1 Nov 2024 - 13 Nov 2026 | 440 | 62.5% | Rs 35,686 | Rs 900 / -Rs 1,284 | Rs 19,985 | 2026-04 -Rs 6,917 | Rs 14,809 | ok |

Own exits (A: no picker, no breakeven, no lock), SENSEX trades only (descriptive):

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 609 | 44.5% | Rs 24,633 | Rs 1,404 / -Rs 1,053 | Rs 31,540 | 2025-12 -Rs 16,632 | -Rs 19,380 | BREACH |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 272 | 44.1% | -Rs 1,247 | Rs 1,362 / -Rs 1,084 | Rs 28,466 | 2026-06 -Rs 14,919 | -Rs 30,551 | BREACH |
| total 1 Nov 2024 - 13 Nov 2026 | 881 | 44.4% | Rs 23,386 | Rs 1,391 / -Rs 1,063 | Rs 31,540 | 2025-12 -Rs 16,632 | -Rs 23,113 | BREACH |

Quarters: 2024 Nov-Dec 30 tr Rs 3,971; 2025 Q1 68 tr -Rs 10,646; 2025 Q2 58 tr Rs 15,455; 2025 Q3 41 tr Rs 7,699; 2025 Q4 37 tr -Rs 89; 2026 Q1 61 tr Rs 22,128; 2026 Q2 73 tr -Rs 11,061; 2026 Q3 43 tr Rs 10,830; 2026 Oct-13 Nov 29 tr -Rs 2,601

Twins: 332 (day, side) groups, 2,000 twins, mean Rs 10,814, sd Rs 24,098, actual Rs 35,686.

Picker removed (SENSEX): W1 343, W2 138, total 481. SENSEX expiry days (never traded): W1 72, W2 32, total 104.

Exits (total): step_lock 211, stop 101, time 92, lock 30, squareoff 6

Per index, live rule, total (descriptive): BANKNIFTY 509 tr Rs 79,789; FINNIFTY 474 tr Rs 117,114; MIDCPNIFTY 472 tr Rs 70,957; NIFTY 457 tr Rs 54,531; SENSEX 440 tr Rs 35,686

Arm with SENSEX 2,352 tr Rs 358,078; without 1,912 tr Rs 322,392.

## R51-SX: R51 Donchian 20 on SENSEX, live rule (picker + V5)

**Verdict: FAILED** (twin p 0.8856, normal p 0.879, BH q 1, m = 4,811; walk-forward 1 of 6 W1 quarters positive).

Failed: W1: net Rs -27,024 not above 0; W2: net Rs -9,818 not above 0; walk-forward: 1 of 6 W1 quarters positive < 4; total: net without the 5 best trades Rs -54,181 not above 0; twin p 0.8856 >= 0.05; BH q 1 > 0.1.

Live rule (V5 + R151 picker), SENSEX trades only:

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 387 | 59.7% | -Rs 27,024 | Rs 723 / -Rs 1,243 | Rs 52,559 | 2025-01 -Rs 14,845 | -Rs 43,864 | BREACH |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 156 | 57.0% | -Rs 9,818 | Rs 837 / -Rs 1,259 | Rs 17,009 | 2026-04 -Rs 15,030 | -Rs 23,607 | BREACH |
| total 1 Nov 2024 - 13 Nov 2026 | 543 | 58.9% | -Rs 36,842 | Rs 754 / -Rs 1,248 | Rs 52,559 | 2026-04 -Rs 15,030 | -Rs 54,181 | BREACH |

Own exits (A: no picker, no breakeven, no lock), SENSEX trades only (descriptive):

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 790 | 42.7% | -Rs 34,536 | Rs 1,333 / -Rs 1,068 | Rs 71,070 | 2024-12 -Rs 20,899 | -Rs 78,548 | BREACH |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 350 | 46.0% | Rs 31,586 | Rs 1,441 / -Rs 1,060 | Rs 17,270 | 2026-06 -Rs 12,329 | -Rs 404 | ok |
| total 1 Nov 2024 - 13 Nov 2026 | 1,140 | 43.7% | -Rs 2,950 | Rs 1,368 / -Rs 1,065 | Rs 71,070 | 2024-12 -Rs 20,899 | -Rs 49,448 | BREACH |

Quarters: 2024 Nov-Dec 31 tr -Rs 1,549; 2025 Q1 73 tr -Rs 23,334; 2025 Q2 66 tr -Rs 16,022; 2025 Q3 80 tr -Rs 550; 2025 Q4 71 tr -Rs 4,307; 2026 Q1 66 tr Rs 18,739; 2026 Q2 59 tr -Rs 8,942; 2026 Q3 62 tr Rs 1,267; 2026 Oct-13 Nov 35 tr -Rs 2,142

Twins: 405 (day, side) groups, 2,000 twins, mean -Rs 7,346, sd Rs 25,244, actual -Rs 36,842.

Picker removed (SENSEX): W1 423, W2 209, total 632. SENSEX expiry days (never traded): W1 72, W2 32, total 104.

Exits (total): step_lock 224, stop 136, time 125, lock 57, squareoff 1

Per index, live rule, total (descriptive): BANKNIFTY 661 tr Rs 103,730; FINNIFTY 617 tr Rs 103,526; MIDCPNIFTY 733 tr Rs 16,195; NIFTY 528 tr Rs 16,972; SENSEX 543 tr -Rs 36,842

Arm with SENSEX 3,082 tr Rs 203,580; without 2,539 tr Rs 240,422.

## LIQ-SX: Liquidity 15+5 on SENSEX (new books 15 + 5 min, index stop 50 pts, V5)

**Verdict: FAILED** (twin p 0.7116, normal p 0.732, BH q 1, m = 4,811; walk-forward 4 of 6 W1 quarters positive).

Failed: W2: net Rs -996 not above 0; total: net without the 5 best trades Rs -29,789 not above 0; twin p 0.7116 >= 0.05; BH q 1 > 0.1.

Live rule (V5), SENSEX trades only:

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 242 | 50.4% | Rs 17,789 | Rs 864 / -Rs 730 | Rs 24,748 | 2025-01 -Rs 7,265 | -Rs 24,317 | ok |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 92 | 45.6% | -Rs 996 | Rs 877 / -Rs 757 | Rs 15,837 | 2026-04 -Rs 8,151 | -Rs 24,966 | ok |
| total 1 Nov 2024 - 13 Nov 2026 | 334 | 49.1% | Rs 16,792 | Rs 867 / -Rs 738 | Rs 24,748 | 2026-04 -Rs 8,151 | -Rs 29,789 | ok |

Own exits (A: no picker, no breakeven, no lock), SENSEX trades only (descriptive):

| window | trades | win | net | avg win / loss | max drawdown | worst month | net without top 5 | account stop |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| W1 1 Nov 2024 - 31 Mar 2026 (design, walk-forward) | 242 | 32.6% | Rs 2,420 | Rs 1,716 / -Rs 817 | Rs 41,725 | 2024-12 -Rs 15,626 | -Rs 43,919 | BREACH |
| W2 1 Apr - 13 Nov 2026 (holdout, read once) | 92 | 35.9% | Rs 3,900 | Rs 1,538 / -Rs 794 | Rs 13,860 | 2026-04 -Rs 4,017 | -Rs 20,644 | ok |
| total 1 Nov 2024 - 13 Nov 2026 | 334 | 33.5% | Rs 6,320 | Rs 1,663 / -Rs 811 | Rs 41,725 | 2024-12 -Rs 15,626 | -Rs 42,747 | BREACH |

Quarters: 2024 Nov-Dec 34 tr -Rs 9,024; 2025 Q1 48 tr -Rs 8,947; 2025 Q2 51 tr Rs 30,961; 2025 Q3 32 tr Rs 2,902; 2025 Q4 37 tr Rs 7; 2026 Q1 40 tr Rs 1,890; 2026 Q2 32 tr -Rs 4,561; 2026 Q3 36 tr Rs 1,573; 2026 Oct-13 Nov 24 tr Rs 1,992

Twins: 226 (day, side) groups, 2,000 twins, mean Rs 38,569, sd Rs 35,160, actual Rs 16,792.

Picker removed (SENSEX): W1 0, W2 0, total 0. SENSEX expiry days (never traded): W1 72, W2 32, total 104.

Exits (total): rule_exit 154, lock 76, time 58, stop 31, squareoff 9, step_lock 5, missing 1

Per index, live rule, total (descriptive): BANKNIFTY 500 tr Rs 96,510; FINNIFTY 380 tr Rs 92,356; MIDCPNIFTY 443 tr Rs 31,602; SENSEX 334 tr Rs 16,792

Arm with SENSEX 1,657 tr Rs 237,261; without 1,323 tr Rs 220,469.

