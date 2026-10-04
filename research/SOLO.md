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
| base (2 a day, ATM) | +23,981 (370 tr, t 0.39) | -50,925 (377, t -1.60) | +655 (360) |
| 1 strike ITM | +41,228 | -46,736 | -10,944 |
| 2 strikes ITM | +49,387 | -42,731 | -20,793 |
| **1 trade a day (chosen)** | **+52,151 (217 tr, t 0.92, DD 23k)** | **-42,968 (219, t -1.72, DD 45k)** | **+6,906 (211, DD 19k)** |
| top 20% candles | +49,545 | -59,955 | -5,598 |
| target 1.5R | +4,718 | -39,416 | +1,767 |
| time stop 20 min (no 0.5R) | -8,482 | -55,594 | -5,909 |
| time stop 30 min | -2,681 | -65,516 | -5,474 |
| time stop 20 + 1 ITM | -1,661 | -52,755 | -14,354 |
| time stop 30 + 1.5R | -25,099 | -47,843 | +1,714 |
| time stop 20 + 1 a day | +22,834 | -43,005 | -2,040 |

Learning (3 Oct): trade only while the setup's last N signals (index R before costs, judged on earlier days only)
averaged >= 0, one trade a day:

| N | NIFTY 24-25 | NIFTY 25-26 | BANKNIFTY 25-26 |
|---|---|---|---|
| (none) | +52,151 (217 tr) | -42,968 (219) | +6,906 (211) |
| 20 | +53,272 (165) | -29,096 (143) | +24,151 (122) |
| 40 | +46,436 (174) | -21,844 (151) | +16,147 (137) |
| **60 (chosen on 24-25)** | **+65,280 (186, DD 18k)** | **-31,808 (170, DD 41k)** | **+12,711 (155, DD 16k)** |
| 40, mean >= 0.1 R | +35,392 (138) | -21,488 (121) | +10,849 (121) |

Every N improves NIFTY 25-26 and BANKNIFTY; NIFTY 25-26 still loses. The app now uses N = 60.

Room left (3 Oct, on top of N = 60): no entry once the day's range is already k x the 20-day average range.
k 0.8: +18,754 / -29,520 / +8,343; k 1.0: +30,293 / -24,143 / +15,489; k 1.2: +25,358 / -23,936 / +13,951.
It narrows the losing year and cuts drawdowns, but halves the 2024-25 profit (the year rules are chosen on), so it is
NOT used (kept in the code as an off-by-default rule).

Profit lock (3 Oct, the owner's rule: Solo always runs with one), on top of N = 60, rungs = (share of the target
reached -> share locked; 0 = the entry):

| ladder | NIFTY 24-25 | NIFTY 25-26 | BANKNIFTY 25-26 | sum |
|---|---|---|---|---|
| none | +65,280 | -31,808 | +12,711 | +46k |
| app ladder 25->0, 50->25, 75->50 | +31,253 | -24,131 | -12,717 | -6k |
| 50->0, 75->25 | +53,771 | -34,373 | +9,018 | +28k |
| 75->25 | +51,083 | -32,036 | -4,455 | +15k |
| **75->0 (chosen on 24-25)** | **+57,686 (DD 25k)** | **-29,813 (DD 39k)** | **+5,767 (DD 16k)** | **+34k** |
| 50->0 | +55,526 | -32,708 | +19,785 | +43k |

Every lock costs something: it closes trades on ordinary pullbacks that would have gone on to the target (the app
ladder hits the target 15-29 times vs 56-75 without). The latest, breakeven-only rung gives up the least.

Stop-loss on the option (3 Oct, the owner's rule: no ladder, always a stop-loss, sized by the study), on top of N = 60,
a resting stop filled at the stop (or the open if it gapped through) -0.5:

| stop | NIFTY 24-25 | NIFTY 25-26 | BANKNIFTY 25-26 | sum |
|---|---|---|---|---|
| none (index stop only) | +65,280 | -31,808 | +12,711 | +46k |
| 15% of premium | -11,496 | -32,687 | +22,419 | -22k |
| 20% | +64,118 | -39,332 | +19,338 | +44k |
| 25% | +59,004 | -40,865 | +16,904 | +35k |
| **30% (chosen on 24-25)** | **+69,393 (DD 14k)** | **-32,796 (DD 42k)** | **+14,853 (DD 16k)** | **+51k** |
| 40% | +68,779 | -31,808 | +13,349 | +50k |
| entry - 0.5 x index risk | +42,546 | -23,940 | +27,774 | +46k |
| entry - 0.7 x index risk | +62,639 | -28,405 | +20,797 | +55k |

The app now uses the 30% stop (a real stop-loss order) with the index stop; the profit-lock ladder is off.
Review (3 Oct): the 30% stop fired on only 10 of 511 trades (5 / 1 / 4); its difference from "none" comes from those ~10
trades, and 40% scores about the same - so it is insurance against a sharp fall, not an edge. The fill model is
honest (worst-case fills at the minute's low: +67.9k / -32.9k / +14.1k). Rules.profitLock now defaults to off, so the
harness variants reproduce the rows above without the lock.

Index edge (R before option costs) is positive in all three files (+0.02 .. +0.21 R), but small; the option's spread,
costs and decay take about all of it. NIFTY 2025-26 loses with every variant.

Review (3 Oct): no look-ahead (signals recomputed on days cut at each minute: identical). Rows other than base and
"1 a day" were run before the nearest-strike fix: BANKNIFTY's file holds 6 strikes a day, and ~16% of its signals were
skipped when the ATM strike was missing; the backtest now takes the nearest strike held (as combined.py does).
Expiry days are priced with the next expiry's options (the data has no same-day contracts) - the app also buys the
next expiry, so live and test agree.

## Conclusion
No version is profitable in both NIFTY years. Solo ships ON PAPER ONLY, off by default, one trade a day, with a
self-pause at Rs 15,000 below its best: a live test of the rules, not a money-maker. It must earn a live record on paper
before real money is even discussed (and that is the owner's decision, in the app, not Jarvis's).

## Solo's learning brain (3 Oct, Boss: "learn from the market itself, not a strategy made in advance")

`ira-core/Learner.kt`: an online logistic model over 12 readings of the last 1-60 minutes (moves scaled by the learned
minute volatility, place in the day's range, distance from the open and the previous close, time of day, the last 15
minutes' range and up-bars). Every minute it guesses whether the next H minutes go up; H minutes later it learns the
answer. It trades only when |p - 0.5| >= edge and its last 400 confident guesses were at least 55% right (200 at
least). Replayed minute by minute (`LearnerTest.learnerOverHistory`, no look-ahead), 1 lot, real option prices,
Rs 60 a trip, 0.5 slippage a fill, 30% premium stop:

| set | edge / H / min hit | trades | won | net |
|---|---|---|---|---|
| NIFTY Apr 24-Apr 25 | 0.10 / 15 / 55% | 987 | 392 | -Rs 1,31,183 |
| NIFTY Apr 25-Apr 26 | 0.10 / 15 / 55% | 963 | 403 | -Rs 1,15,255 |
| BANKNIFTY Feb 25-Feb 26 | 0.10 / 15 / 55% | 744 | 336 | -Rs 1,00,924 |
| NIFTY Apr 24-Apr 25 | 0.15 / 60 / 57% | 342 | 141 | -Rs 38,532 |
| NIFTY Apr 25-Apr 26 | 0.15 / 60 / 57% | 358 | 139 | -Rs 43,685 |
| BANKNIFTY Feb 25-Feb 26 | 0.20 / 30 / 60% | 155 | 74 | -Rs 10,366 |

Every version loses after costs: from price alone it guesses the next 15-60 minutes about as well as a coin (its own
record swings between 36% and 73% as the market changes, and it learns the change late). Boss chose to run it anyway,
on paper only, learning live (the app keeps each market's model from day to day). No real money until it proves itself.

### Self-calibration (4 Oct)

The learner now keeps how often it was right in three bands of sureness (60-65%, 65-70%, 70%+; faded, about the
last 500 confident guesses) and does not trade a band whose own record is below 55%, even when its overall record
passes. Replayed the same way (edge 0.10, H 15): NIFTY 24-25 674 trades, -Rs 1,29,522 (was 987, -1,31,183);
NIFTY 25-26 706 trades, -Rs 1,14,828 (was 963, -1,15,255); BANKNIFTY 557 trades, -Rs 67,690 (was 744, -1,00,924).
Fewer trades and smaller losses, still no edge after costs: paper only.
