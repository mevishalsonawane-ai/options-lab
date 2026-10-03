# Solo: Jarvis trading by himself (paper), tested on two years of real data

Written 3 Oct 2026. Code: `android/ira-core/.../Solo.kt` (the same code runs in the app and in this test);
harness: `SoloHistoryTest.kt` (`SOLO_DATA=<folder> SOLO_VARIANTS=base,one,... gradle :ira-core:test --tests '*SoloHistoryTest*'`).

## Data
`research-data` release: `nifty_prev.parquet` (Apr 2024 - Apr 2025, 250 days), `nifty_year.parquet` (Apr 2025 - Apr 2026,
249 days), `banknifty_year.parquet` (Feb 2025 - Feb 2026, 249 days). Index 1-minute bars plus REAL 1-minute option prices
(nearest expiry, strikes within 500 / 300 points). Exported to `day,expiry,right,strike,m,o,h,l,c` CSV for the harness.

## Rules
Setup (research/COMBINED.md, the only intraday direction edge found in both years): a 15-minute candle (09:15-14:15) with
a body in the top 30% of the last 20 days; within 60 minutes a 5-minute close pulled back 40% of its range without
breaking its low (green) / high (red); buy the ATM option the candle's way at the next minute's open +0.5; stop = the
index through that level, target = 2x the risk, 15:10 out; exit at the option's close -0.5; Rs 60 a round trip.
Risk layer: max trades a day, done after 2 losses or Rs 5,000 down, no entry after 14:30, none in an expiry's last hour.
Lots: NIFTY 75, BANKNIFTY 30. Variants chosen on NIFTY 2024-25, judged on the other two files.

## Results (net Rs, 1 lot)
| variant | NIFTY 24-25 | NIFTY 25-26 | BANKNIFTY 25-26 |
|---|---|---|---|
| base (2 a day, ATM) | +34,661 (369 tr, t 0.57) | -50,925 (377, t -1.60) | -1,401 (322) |
| 1 strike ITM | +41,228 | -46,736 | -10,944 |
| 2 strikes ITM | +49,387 | -42,731 | -20,793 |
| **1 trade a day (chosen)** | **+52,151 (217 tr, t 0.92, DD 23k)** | **-42,968 (219, t -1.72, DD 45k)** | **+2,041 (195, DD 22k)** |
| top 20% candles | +49,545 | -59,955 | -5,598 |
| target 1.5R | +4,718 | -39,416 | +1,767 |
| time stop 20 min (no 0.5R) | -8,482 | -55,594 | -5,909 |
| time stop 30 min | -2,681 | -65,516 | -5,474 |
| time stop 20 + 1 ITM | -1,661 | -52,755 | -14,354 |
| time stop 30 + 1.5R | -25,099 | -47,843 | +1,714 |
| time stop 20 + 1 a day | +22,834 | -43,005 | -2,040 |

Index edge (R before option costs) is positive in all three files (+0.02 .. +0.21 R), but small; the option's spread,
costs and decay take about all of it. NIFTY 2025-26 loses with every variant.

## Conclusion
No version is profitable in both NIFTY years. Solo ships ON PAPER ONLY, off by default, one trade a day, with a
self-pause at Rs 15,000 below its best: a live test of the rules, not a money-maker. It must earn a live record on paper
before real money is even discussed (and that is the owner's decision, in the app, not Jarvis's).
