# R206 results: how reliable is the arms' direction call, and is a call other signals agree with more reliable?

Pre-registered in `PREREG.md` before any number. Index-only (no option file read): the dhan-data branch's 1-minute index bars, 8 Oct 2021 - 5 Oct 2026. "Right" = the index's close 15 minutes after the signal is beyond its close at the signal, the signal's way. OLD era read once (2026-11-20T15:23:53.803039+00:00 UTC).

**0 of 8 conditions make the call more reliable**.

These are signals, not trades (no picker, no one-position rule, no day caps, expiry days included), so the counts are larger than the arms' trade counts.

## Part 1: how reliable is the call today

| source | era | signals | right after 15 min (95% interval) | right after 30 min | average move its way at 15 min |
|---|---|---|---|---|---|
| R72 Breadth Donchian | OLD 1 Apr 2022 - 19 Nov 2024 (the test) | 12,709 | 49.3% (47.6% - 50.9%) | 48.4% | 0.11 hundredths of a percent |
| R72 Breadth Donchian | RECENT 21 Nov 2024 - 5 Oct 2026 (where the lead was seen) | 9,132 | 51.6% (49.4% - 53.7%) | 52.4% | 1.07 hundredths of a percent |
| R51 Donchian 20 + Supertrend | OLD 1 Apr 2022 - 19 Nov 2024 (the test) | 27,101 | 49.2% (48.1% - 50.3%) | 49.6% | 0.1 hundredths of a percent |
| R51 Donchian 20 + Supertrend | RECENT 21 Nov 2024 - 5 Oct 2026 (where the lead was seen) | 19,104 | 50.2% (48.9% - 51.4%) | 51.1% | 0.55 hundredths of a percent |
| Liquidity 15+5 | OLD 1 Apr 2022 - 19 Nov 2024 (the test) | 1,598 | 51.0% (48.2% - 53.7%) | 50.8% | 0.28 hundredths of a percent |
| Liquidity 15+5 | RECENT 21 Nov 2024 - 5 Oct 2026 (where the lead was seen) | 1,250 | 53.2% (50.0% - 56.4%) | 54.5% | 2.08 hundredths of a percent |

### By year (right after 15 minutes; signals)

- R72 Breadth Donchian: 2022 49.4% (4,377); 2023 50.8% (4,929); 2024 47.0% (3,403); 2024-25 52.2% (5,253); 2026 50.7% (3,879).
- R51 Donchian 20 + Supertrend: 2022 50.0% (8,649); 2023 50.2% (10,364); 2024 47.0% (8,088); 2024-25 50.3% (11,437); 2026 50.0% (7,665).
- Liquidity 15+5: 2022 51.6% (434); 2023 51.9% (553); 2024 49.8% (611); 2024-25 54.4% (706); 2026 51.7% (544).

### By index (right after 15 minutes; signals)

| source | era | NIFTY | BANKNIFTY | FINNIFTY | MIDCPNIFTY | SENSEX |
|---|---|---|---|---|---|---|
| R72 Breadth Donchian | OLD | 49.8% (2,835) | 48.8% (2,669) | 48.9% (2,749) | 48.2% (1,657) | 50.2% (2,799) |
| R72 Breadth Donchian | RECENT | 51.8% (2,008) | 50.7% (1,879) | 52.0% (1,948) | 52.1% (1,306) | 51.3% (1,991) |
| R51 Donchian 20 + Supertrend | OLD | 49.2% (5,730) | 47.9% (4,773) | 48.7% (4,980) | 50.8% (5,863) | 49.0% (5,755) |
| R51 Donchian 20 + Supertrend | RECENT | 50.0% (3,812) | 50.0% (3,466) | 50.7% (3,606) | 51.1% (4,328) | 48.9% (3,890) |
| Liquidity 15+5 | OLD | - | 50.6% (607) | 50.4% (500) | 52.1% (491) | - |
| Liquidity 15+5 | RECENT | - | 51.6% (463) | 56.1% (367) | 52.4% (420) | - |

### By entry hour (right after 15 minutes; signals)

| source | era | 9 | 10 | 11 | 12 | 13 | 14 | 15 |
|---|---|---|---|---|---|---|---|---|
| R72 Breadth Donchian | OLD | 48.4% (2,610) | 41.8% (1,072) | 49.4% (1,494) | 50.5% (2,060) | 48.5% (2,013) | 48.3% (2,507) | 61.4% (953) |
| R72 Breadth Donchian | RECENT | 54.4% (1,848) | 50.4% (877) | 44.7% (1,017) | 51.2% (1,486) | 53.1% (1,534) | 50.4% (1,825) | 57.4% (545) |
| R51 Donchian 20 + Supertrend | OLD | 50.0% (5,041) | 45.6% (3,236) | 48.9% (3,510) | 49.6% (4,163) | 46.6% (4,307) | 48.6% (5,001) | 60.4% (1,843) |
| R51 Donchian 20 + Supertrend | RECENT | 52.9% (3,730) | 48.6% (2,443) | 45.7% (2,378) | 50.1% (2,914) | 50.7% (3,148) | 49.3% (3,385) | 55.2% (1,104) |
| Liquidity 15+5 | OLD | 49.4% (627) | 55.5% (200) | 51.5% (239) | 51.5% (241) | 50.6% (259) | 50.0% (32) | - |
| Liquidity 15+5 | RECENT | 57.7% (522) | 44.0% (193) | 48.4% (153) | 47.6% (185) | 58.2% (177) | 70.0% (20) | - |

### By side (right after 15 minutes; signals)

| source | era | C | P |
|---|---|---|---|
| R72 Breadth Donchian | OLD | 52.0% (6,569) | 46.4% (6,140) |
| R72 Breadth Donchian | RECENT | 53.6% (4,383) | 49.7% (4,749) |
| R51 Donchian 20 + Supertrend | OLD | 50.7% (14,897) | 47.3% (12,204) |
| R51 Donchian 20 + Supertrend | RECENT | 51.4% (9,746) | 48.8% (9,356) |
| Liquidity 15+5 | OLD | 51.4% (863) | 50.5% (735) |
| Liquidity 15+5 | RECENT | 53.7% (629) | 52.7% (621) |

## Part 2: the eight conditions (A against B, share right after 15 minutes)

| id | signals | A | OLD: A right (signals) | OLD: B right (signals) | OLD difference (points) | bootstrap p | OLD years 2022 / 2023 / 2024 | RECENT difference | 30-min difference OLD / RECENT | verdict |
|---|---|---|---|---|---|---|---|---|---|---|
| H1 | R51 Donchian 20 + Supertrend | an R72 signal on the same index, minute and side | 49.4% (12,272) | 49.0% (14,829) | +0.4 | 0.327 | -0.8 / +1.1 / +0.3 | +2.7 | -1.9 / +2.7 | not more reliable |
| H2 | Liquidity 15+5 | an R51 or R72 signal on the same index, minute and side | 51.4% (1,111) | 50.1% (487) | +1.3 | 0.314 | +2.2 / +6.2 / -3.6 | +0.5 | +1.2 / +2.3 | not more reliable |
| H3 | R72 Breadth Donchian | all 5 indices broke (against 4 of 5) | 50.3% (6,145) | 48.3% (6,564) | +2.0 | 0.098 | +4.2 / +3.6 / -2.9 | -0.1 | +1.4 / +2.0 | not more reliable |
| H4 | R72 Breadth Donchian | an R51 signal on the same index, minute and side | 49.4% (12,272) | 45.8% (437) | +3.6 | 0.155 | +5.0 / +1.4 / +5.6 | +0.6 | +2.4 / +4.6 | not more reliable |
| H5 | R72 Breadth Donchian | with the day (index above its first open for a call, below for a put) | 49.2% (10,359) | 49.4% (2,347) | -0.1 | 0.522 | -2.6 / -1.7 / +4.9 | +1.6 | -0.8 / -0.5 | not more reliable |
| H6 | Liquidity 15+5 | with the day | 52.6% (1,320) | 43.5% (278) | +9.1 | 0.006 | +14.5 / +11.0 / +3.6 | -1.1 | +6.6 / +0.4 | not more reliable |
| H7 | R72 Breadth Donchian | with the market (NIFTY's last 15-minute close on the trade's side of its EMA20) | 49.4% (10,881) | 48.3% (1,828) | +1.1 | 0.320 | -1.9 / +1.1 / +4.8 | -2.4 | +1.0 / -3.8 | not more reliable |
| H8 | Liquidity 15+5 | with the market | 50.7% (1,320) | 52.5% (278) | -1.8 | 0.680 | -0.7 / -2.2 / -2.4 | -6.9 | +0.9 / -2.6 | not more reliable |

Why each one fails or passes:

- **H1** (not more reliable): OLD: difference +0.4 points with p 0.3267 (needs above 0 with p < 0.05); BH q 1 > 0.1; OLD: A is +0.2 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.444).
- **H2** (not more reliable): OLD: difference +1.3 points with p 0.3137 (needs above 0 with p < 0.05); BH q 1 > 0.1; OLD: A is +0.4 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.444).
- **H3** (not more reliable): OLD: difference +2.0 points with p 0.0980 (needs above 0 with p < 0.05); BH q 1 > 0.1; RECENT: difference -0.1 points not above 0; OLD: A is +1.0 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.395).
- **H4** (not more reliable): OLD: difference +3.6 points with p 0.1546 (needs above 0 with p < 0.05); BH q 1 > 0.1; OLD: A is +0.1 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.414).
- **H5** (not more reliable): OLD: difference -0.1 points with p 0.5219 (needs above 0 with p < 0.05); BH q 1 > 0.1; above 0 in 1 of 3 OLD years < 2; OLD: A is -0.0 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.606).
- **H6** (not more reliable): BH q 1 > 0.1; RECENT: difference -1.1 points not above 0; OLD: A is +1.6 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.0422).
- **H7** (not more reliable): OLD: difference +1.1 points with p 0.3195 (needs above 0 with p < 0.05); BH q 1 > 0.1; RECENT: difference -2.4 points not above 0; OLD: A is +0.2 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.444).
- **H8** (not more reliable): OLD: difference -1.8 points with p 0.6797 (needs above 0 with p < 0.05); BH q 1 > 0.1; above 0 in 0 of 3 OLD years < 2; RECENT: difference -6.9 points not above 0; OLD: A is -0.3 points above the source's own share right (needs 2 points). BH q 1 (m = 4,888; within these 8: 0.681).
