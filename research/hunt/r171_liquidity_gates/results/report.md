# R171: four liquidity / execution-cost gates on the arms

13 Nov 2026. Pre-registered in `PREREG.md` before any number (written first; this session could not commit or push, so the pre-registration is not in its own earlier commit). Research only: nothing in the app changes.

## For Boss (short)

- **No gate passed: 0 of 72.** None of the four upgrades beat random skips of the same size after counting every earlier test (BH, m = 3,913). Nothing should change in the app.
- **1. Same-day 30/60-minute window:** it never skipped an out-of-sample trade. Every out-of-sample R51, R72 and Liquidity trade's strike traded in every one of the 30 minutes before entry; in design only 2 R51 and 2 R72 trades had a short gap (NIFTY and BANKNIFTY near-the-money options are always active). Today's d-1 check is not missing anything on these arms.
- **3a. Depth:** never binding. The thinnest 1% of BANKNIFTY monthly entries still traded about 30-90 lots a minute; NIFTY about 250-1,900. A 1- or 3-lot order is far inside that (0% of trades would be 'stress' at 1 lot, at most 0.6% at 3 lots).
- **2. Cost cap:** it skips cheap options and that LOWERED profit on R51 / R72 in design (the cheap options were not the losers). On the expiry put it removes almost every trade (a Rs 5 sold put costs far more than 3% of its premium), so it cannot be used there.
- **Best leads (not passed, keep watching only):**
  - 4a (spread vs theta, effectively 'on BANKNIFTY monthly, only the last week before expiry') on R72: 473 of 778 out-of-sample trades kept, +Rs 92 a trade vs -Rs 30, better in all 4 quarters, but only +Rs 10 a trade better in design, p 0.00845 -> q 1. Same direction on R51 (+Rs 60 a trade, p 0.092).
  - 3c (skip a strike whose Roll spread just doubled) on Liquidity 15+5: +Rs 39 a trade out-of-sample (732 of 960 kept) and +Rs 46 in design, but p 0.057 -> q 1.
- **The big caveat:** the spread is an assumption. At the 'roll' spread these arms make +Rs 23 (R51) / +Rs 81 (R72) / +Rs 14 (Liquidity) a trade out-of-sample; at 'quoted' it is -Rs 88 / -Rs 30 / -Rs 88. Which one is true decides whether the arms make money at all, and only the phone's recorded depth (sealed, private IraAlgo repo, not opened here) can tell.
- These numbers are on the public release data (2024-02 to 2026-02-23, NIFTY + BANKNIFTY), not the app's own data, so the arms' rupee totals do not match their published records. Only 'gate vs no gate vs random skip on the same trades' is the result.

## What was run

- Data: the public `research-data` GitHub release (1-minute OHLCV + OI with real traded volume; one expiry a day, 20 strikes). NIFTY 2024-04-01 - 2026-02-23, BANKNIFTY 2024-02-13 - 2026-02-23; FINNIFTY / SENSEX index minutes for R72's breadth; `options_lab/data/expiry_cache` for the expiry put. **Nothing on or after 2026-02-24** (the 2026-02-24 .. 2026-09-05 hole, docs/design.md:83). The Drive lake the arms' own studies use is not reachable from this session, so the Apr - Oct 2026 period could not be read at all.
- Strategies (signal on the index, the option bought; the expiry put sells): R51 = R51 Donchian 20 + Supertrend; R72 = R72 Breadth Donchian (4 of the 4 indices with data); LIQ = Liquidity 15+5; EXP = options_lab expiry put (sold, to settlement). Rules as PREREG.md section 1.
- Costs: dated statutory charges + Rs 20 an order (`research/obuy/costs.py` 'dated'), plus the `options_lab/costs.py` spread law crossed half each way. Primary regime 'quoted'; 'roll' and 'stress' beside it.
- Periods: DESIGN start - 2025-03-31 (the combination is chosen here); OUT-OF-SAMPLE 2025-04-01 - 2026-02-23, read once, also shown by quarter. The expiry put's last 30 sessions are options_lab's sealed holdout and were not read, so it has only 24 out-of-sample sessions (cannot pass, < 40).
- Control: random skips of the same size from the same trades (20,000 draws; the finite-population normal p agrees). Every gated trade is one of the no-gate trades (a skipped break still holds its side and counts in the caps, as the app does), so the control is exact.
- Signal source: the index for every R51 / R72 / Liquidity entry; the expiry put's strike from the options' parity forward. Option volume, premium and the Roll estimate are used only as filters (CLAUDE.md allows).

## Out-of-sample results (quoted spread)

Columns: trades kept / no-gate trades; net per trade; gain per trade vs no gate (quoted); the gain under roll / stress costs; total net; win %; worst day; max drawdown; random-skip p (Monte-Carlo); BH q.

### R51 Donchian 20 + Supertrend

No gate: 1061 trades, -Rs 88 a trade, total -Rs 93,452, win 41.19%, worst day -Rs 13,633, max DD -Rs 93,452 (roll +Rs 23 / stress -Rs 291 a trade).

| gate | kept | net / trade | gain | gain roll / stress | total | win | worst day | max DD | p | q |
|---|---|---|---|---|---|---|---|---|---|---|
| G1a | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G1b | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G1c | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G2a | 835 / 1061 | -Rs 78 | +Rs 10 | +Rs 22 / -Rs 12 | -Rs 65,165 | 41.32% | -Rs 13,633 | -Rs 75,253 | 0.368 | 1 |
| G2b | 1039 / 1061 | -Rs 88 | +Rs 0 | +Rs 2 / -Rs 2 | -Rs 91,178 | 41.19% | -Rs 13,633 | -Rs 91,178 | 0.501 | 1 |
| G2c | 1060 / 1061 | -Rs 87 | +Rs 1 | +Rs 1 / +Rs 1 | -Rs 92,675 | 41.23% | -Rs 13,633 | -Rs 92,675 | 0.389 | 1 |
| G2d | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G2e | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G2f | 143 / 1061 | -Rs 327 | -Rs 239 | -Rs 157 / -Rs 390 | -Rs 46,810 | 34.27% | -Rs 10,051 | -Rs 47,408 | 0.957 | 1 |
| G3a | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G3b | 1061 / 1061 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 93,452 | 41.19% | -Rs 13,633 | -Rs 93,452 | 1 | 1 |
| G3c | 795 / 1061 | -Rs 97 | -Rs 9 | -Rs 7 / -Rs 13 | -Rs 77,131 | 41.01% | -Rs 10,839 | -Rs 85,391 | 0.615 | 1 |
| G3d | 881 / 1061 | -Rs 96 | -Rs 8 | -Rs 6 / -Rs 11 | -Rs 84,644 | 41.09% | -Rs 10,372 | -Rs 84,644 | 0.629 | 1 |
| G4a | 639 / 1061 | -Rs 28 | +Rs 60 | +Rs 35 / +Rs 107 | -Rs 17,680 | 41.94% | -Rs 8,276 | -Rs 36,536 | 0.0921 | 1 |
| G4b | 436 / 1061 | -Rs 47 | +Rs 41 | +Rs 11 / +Rs 96 | -Rs 20,701 | 41.97% | -Rs 8,276 | -Rs 40,416 | 0.275 | 1 |
| G4c | 436 / 1061 | -Rs 47 | +Rs 41 | +Rs 11 / +Rs 96 | -Rs 20,701 | 41.97% | -Rs 8,276 | -Rs 40,416 | 0.275 | 1 |
| G4d | 622 / 1061 | -Rs 95 | -Rs 7 | -Rs 19 / +Rs 14 | -Rs 59,263 | 39.55% | -Rs 8,276 | -Rs 59,263 | 0.564 | 1 |
| C | no combination (nothing qualified on design) | | | | | | | | | |

### R72 Breadth Donchian (4 of the 4 indices with data)

No gate: 778 trades, -Rs 30 a trade, total -Rs 23,617, win 43.06%, worst day -Rs 12,200, max DD -Rs 56,075 (roll +Rs 81 / stress -Rs 234 a trade).

| gate | kept | net / trade | gain | gain roll / stress | total | win | worst day | max DD | p | q |
|---|---|---|---|---|---|---|---|---|---|---|
| G1a | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G1b | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G1c | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G2a | 629 / 778 | -Rs 47 | -Rs 16 | -Rs 6 / -Rs 35 | -Rs 29,324 | 42.61% | -Rs 10,659 | -Rs 52,614 | 0.698 | 1 |
| G2b | 762 / 778 | -Rs 38 | -Rs 7 | -Rs 6 / -Rs 9 | -Rs 28,575 | 42.78% | -Rs 12,200 | -Rs 54,733 | 0.782 | 1 |
| G2c | 774 / 778 | -Rs 28 | +Rs 2 | +Rs 3 / +Rs 2 | -Rs 21,575 | 43.15% | -Rs 12,200 | -Rs 55,535 | 0.306 | 1 |
| G2d | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G2e | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G2f | 103 / 778 | -Rs 402 | -Rs 371 | -Rs 290 / -Rs 521 | -Rs 41,360 | 35.92% | -Rs 6,101 | -Rs 41,360 | 0.99 | 1 |
| G3a | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G3b | 778 / 778 | -Rs 30 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 23,617 | 43.06% | -Rs 12,200 | -Rs 56,075 | 1 | 1 |
| G3c | 593 / 778 | -Rs 12 | +Rs 18 | +Rs 20 / +Rs 15 | -Rs 7,350 | 44.86% | -Rs 10,839 | -Rs 45,099 | 0.312 | 1 |
| G3d | 655 / 778 | -Rs 37 | -Rs 7 | -Rs 6 / -Rs 10 | -Rs 24,516 | 44.12% | -Rs 9,629 | -Rs 47,605 | 0.598 | 1 |
| G4a | 473 / 778 | +Rs 92 | +Rs 123 | +Rs 98 / +Rs 167 | +Rs 43,608 | 45.24% | -Rs 12,200 | -Rs 30,711 | 0.00845 | 1 |
| G4b | 317 / 778 | +Rs 69 | +Rs 99 | +Rs 71 / +Rs 151 | +Rs 21,775 | 47.32% | -Rs 12,200 | -Rs 27,774 | 0.103 | 1 |
| G4c | 317 / 778 | +Rs 69 | +Rs 99 | +Rs 71 / +Rs 151 | +Rs 21,775 | 47.32% | -Rs 12,200 | -Rs 27,774 | 0.103 | 1 |
| G4d | 471 / 778 | -Rs 46 | -Rs 16 | -Rs 28 / +Rs 7 | -Rs 21,822 | 43.74% | -Rs 9,101 | -Rs 40,702 | 0.622 | 1 |
| C | no combination (nothing qualified on design) | | | | | | | | | |

### Liquidity 15+5

No gate: 960 trades, -Rs 88 a trade, total -Rs 84,877, win 40.73%, worst day -Rs 11,280, max DD -Rs 115,362 (roll +Rs 14 / stress -Rs 276 a trade).

| gate | kept | net / trade | gain | gain roll / stress | total | win | worst day | max DD | p | q |
|---|---|---|---|---|---|---|---|---|---|---|
| G1a | 960 / 960 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 84,877 | 40.73% | -Rs 11,280 | -Rs 115,362 | 1 | 1 |
| G1b | 960 / 960 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 84,877 | 40.73% | -Rs 11,280 | -Rs 115,362 | 1 | 1 |
| G1c | 960 / 960 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 84,877 | 40.73% | -Rs 11,280 | -Rs 115,362 | 1 | 1 |
| G2a | 695 / 960 | -Rs 93 | -Rs 5 | +Rs 11 / -Rs 34 | -Rs 64,657 | 40.58% | -Rs 8,803 | -Rs 110,384 | 0.561 | 1 |
| G2b | 873 / 960 | -Rs 87 | +Rs 2 | +Rs 7 / -Rs 8 | -Rs 75,574 | 41.01% | -Rs 11,280 | -Rs 109,357 | 0.461 | 1 |
| G2c | 938 / 960 | -Rs 88 | +Rs 0 | +Rs 2 / -Rs 2 | -Rs 82,589 | 40.83% | -Rs 11,280 | -Rs 113,075 | 0.501 | 1 |
| G2d | 960 / 960 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 84,877 | 40.73% | -Rs 11,280 | -Rs 115,362 | 1 | 1 |
| G2e | 956 / 960 | -Rs 89 | -Rs 0 | +Rs 0 / -Rs 1 | -Rs 84,667 | 40.69% | -Rs 11,280 | -Rs 115,152 | 0.557 | 1 |
| G2f | 85 / 960 | -Rs 113 | -Rs 24 | +Rs 61 / -Rs 181 | -Rs 9,571 | 36.47% | -Rs 3,266 | -Rs 24,522 | 0.551 | 1 |
| G3a | 960 / 960 | -Rs 88 | +Rs 0 | +Rs 0 / +Rs 0 | -Rs 84,877 | 40.73% | -Rs 11,280 | -Rs 115,362 | 1 | 1 |
| G3b | 950 / 960 | -Rs 83 | +Rs 5 | +Rs 5 / +Rs 6 | -Rs 79,070 | 40.95% | -Rs 11,280 | -Rs 110,462 | 0.111 | 1 |
| G3c | 732 / 960 | -Rs 49 | +Rs 39 | +Rs 41 / +Rs 36 | -Rs 35,848 | 42.76% | -Rs 11,280 | -Rs 60,674 | 0.0572 | 1 |
| G3d | 811 / 960 | -Rs 84 | +Rs 4 | +Rs 6 / +Rs 1 | -Rs 68,451 | 41.55% | -Rs 11,280 | -Rs 91,083 | 0.431 | 1 |
| G4a | 502 / 960 | -Rs 76 | +Rs 13 | -Rs 12 / +Rs 59 | -Rs 37,927 | 42.03% | -Rs 7,178 | -Rs 46,582 | 0.382 | 1 |
| G4b | 299 / 960 | -Rs 100 | -Rs 12 | -Rs 46 / +Rs 50 | -Rs 30,016 | 41.14% | -Rs 7,178 | -Rs 37,710 | 0.567 | 1 |
| G4c | 299 / 960 | -Rs 100 | -Rs 12 | -Rs 46 / +Rs 50 | -Rs 30,016 | 41.14% | -Rs 7,178 | -Rs 37,710 | 0.567 | 1 |
| G4d | 508 / 960 | -Rs 112 | -Rs 24 | -Rs 34 / -Rs 6 | -Rs 57,089 | 40.75% | -Rs 10,708 | -Rs 63,627 | 0.711 | 1 |
| C (G3c) | 732 / 960 | -Rs 49 | +Rs 39 | +Rs 41 / +Rs 36 | -Rs 35,848 | 42.76% | -Rs 11,280 | -Rs 60,674 | 0.0572 | 1 |

### options_lab expiry put (sold, to settlement)

No gate: 24 trades, +Rs 469 a trade, total +Rs 11,267, win 100.0%, worst day +Rs 133, max DD +Rs 0 (roll +Rs 483 / stress +Rs 444 a trade).

| gate | kept | net / trade | gain | gain roll / stress | total | win | worst day | max DD | p | q |
|---|---|---|---|---|---|---|---|---|---|---|
| G1a | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G1b | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G1c | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G2a | 1 / 24 | +Rs 3,319 | +Rs 2,850 | +Rs 2,858 / +Rs 2,835 | +Rs 3,319 | 100.0% | +Rs 3,319 | +Rs 0 | 0.0419 | 0.00346 |
| G2b | 1 / 24 | +Rs 3,319 | +Rs 2,850 | +Rs 2,858 / +Rs 2,835 | +Rs 3,319 | 100.0% | +Rs 3,319 | +Rs 0 | 0.0419 | 0.00346 |
| G2c | 1 / 24 | +Rs 3,319 | +Rs 2,850 | +Rs 2,858 / +Rs 2,835 | +Rs 3,319 | 100.0% | +Rs 3,319 | +Rs 0 | 0.0419 | 0.00346 |
| G2d | 2 / 24 | +Rs 2,308 | +Rs 1,838 | +Rs 1,844 / +Rs 1,828 | +Rs 4,615 | 100.0% | +Rs 1,296 | +Rs 0 | 0.00465 | 0.00914 |
| G2e | 2 / 24 | +Rs 2,308 | +Rs 1,838 | +Rs 1,844 / +Rs 1,828 | +Rs 4,615 | 100.0% | +Rs 1,296 | +Rs 0 | 0.00465 | 0.00914 |
| G2f | 0 / 24 | - | - | - / - | +Rs 0 | -% | - | - | 1 | 1 |
| G3a | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G3b | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G3c | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G3d | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G4a | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G4b | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G4c | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| G4d | 24 / 24 | +Rs 469 | +Rs 0 | +Rs 0 / +Rs 0 | +Rs 11,267 | 100.0% | +Rs 133 | +Rs 0 | 1 | 1 |
| C (G2b) | 1 / 24 | +Rs 3,319 | +Rs 2,850 | +Rs 2,858 / +Rs 2,835 | +Rs 3,319 | 100.0% | +Rs 3,319 | +Rs 0 | 0.0419 | 0.00346 |

## Design period (the gates were fixed in advance; only the combination was chosen here)

| strategy | no gate (n, net / trade) | combination chosen | gains per trade, G1a..G4d (quoted) |
|---|---|---|---|
| R51 | 1186, -Rs 196 | none | G1a +Rs 0, G1b +Rs 0, G1c -Rs 0, G2a -Rs 79, G2b -Rs 38, G2c -Rs 13, G2d +Rs 0, G2e -Rs 4, G2f -Rs 322, G3a +Rs 0, G3b +Rs 0, G3c -Rs 29, G3d -Rs 32, G4a +Rs 2, G4b -Rs 27, G4c -Rs 27, G4d -Rs 43 |
| R72 | 754, -Rs 186 | none | G1a +Rs 0, G1b +Rs 0, G1c -Rs 1, G2a -Rs 70, G2b -Rs 13, G2c -Rs 7, G2d +Rs 0, G2e -Rs 2, G2f -Rs 578, G3a +Rs 0, G3b +Rs 0, G3c +Rs 14, G3d -Rs 2, G4a +Rs 10, G4b -Rs 9, G4c -Rs 9, G4d -Rs 6 |
| LIQ | 1032, -Rs 141 | G3c | G1a +Rs 0, G1b +Rs 0, G1c +Rs 0, G2a -Rs 112, G2b -Rs 54, G2c -Rs 16, G2d +Rs 0, G2e -Rs 2, G2f -Rs 601, G3a +Rs 0, G3b -Rs 0, G3c +Rs 46, G3d +Rs 33, G4a -Rs 4, G4b +Rs 26, G4c +Rs 26, G4d +Rs 11 |
| EXP | 116, +Rs 230 | G2b | G1a +Rs 0, G1b +Rs 0, G1c +Rs 0, G2a -, G2b +Rs 1,664, G2c +Rs 1,664, G2d +Rs 408, G2e +Rs 1,524, G2f -, G3a +Rs 0, G3b +Rs 0, G3c +Rs 0, G3d +Rs 0, G4a +Rs 0, G4b +Rs 0, G4c +Rs 0, G4d +Rs 0 |

## Why each family failed

- **G1 (same-day window):** no trade had a single empty minute in its 30- or 60-minute pre-entry window (G1c skipped 2 R51 / 2 R72 trades in design only). The idea is right in principle but these arms only buy near-the-money NIFTY / BANKNIFTY options, which trade every minute; it would matter for far strikes, FINNIFTY / MIDCPNIFTY or stock options (no option data here).
- **G2 (cost cap):** skipping cheap premiums cut good trades as often as bad ones; the tick-floor rule (Rs 10) never fired on the arms (their 1-ITM premiums are Rs 100+). G2f (stress spread) keeps under 15% of trades and they are worse. On the expiry put the cap is incompatible with the strategy.
- **G3 (depth / Roll):** depth never binds at 1-3 lots. The Roll spike (G3c/d) helped Liquidity in both periods but not enough to beat random skips after BH; it hurt R51 in both periods, so it is not a general rule.
- **G4 (DTE-coupled):** the spread-to-premium ratio is nearly constant, so spread_in_theta_minutes is mostly a DTE cap; in this data it skips BANKNIFTY monthly trades more than about a week from expiry (few NIFTY trades: 5 / 3 / 79 out-of-sample for R51 / R72 / Liquidity). Those trades lost; the kept BANKNIFTY trades made money out-of-sample, but the design period showed almost none of that gain, and G4d (the same rule with the MEASURED Roll spread) did not help at all, so the effect is about DTE, not about the spread. Not passed; it would need a fresh pre-registered test of 'BANKNIFTY monthly only in its last week' on new data.

## Spread assumption (costs.py) and what would validate it

- No free bid / ask history exists; every spread here is the costs.py law. Results that depend on it (G2, G4a-c) are shown under roll / quoted / stress above; G4d and G3c/d use the Roll estimate measured from real 1-minute prices instead.
- The arms' sign flips between the roll and quoted regimes. Validating the regime needs the phone's recorded market depth (sealed recordings in the private IraAlgo repo); they were NOT opened (encrypted, and this session may not read that repo).

## App change

None: no gate passed, so there is no app spec. Nothing in `android/**` changed. The gate functions are in `options_lab/features/gates.py` (new functions only; `liquid()` and `LIQUID_THRESHOLD` unchanged) with tests in `options_lab/tests/test_gates_r171.py`. If Boss wants the two leads watched, the only allowed form is a SHADOW log (off by default, never skipping a trade) of 'would 4a / 3c have skipped this', judged later on new days.

## Reproduce

```
R171_DATA=<release files> R171_CACHE=<cache> python research/hunt/r171_liquidity_gates/r171data.py build
... python research/hunt/r171_liquidity_gates/r171study.py trades && ... design && ... oos   # oos once only
python research/hunt/r171_liquidity_gates/r171report.py
python -m pytest research/hunt/r171_liquidity_gates/test_r171.py options_lab/tests/test_gates_r171.py -q
```
