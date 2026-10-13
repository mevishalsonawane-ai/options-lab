# R19: a profit lock for Liquidity 15+5

Written 13 Oct 2026 for Boss, after the Liquidity 5m MIDCPNIFTY trade that rose about +Rs 2.5k and settled at -Rs 864.

- Pre-registration: `research/hunt/r19/PREREG.md`, committed and pushed on its own (9891bb8) before any result existed.
- Code, tests and the design / walk-forward results were committed (619978a) before the holdout was read. The holdout
  was then read once, guarded by a flag file.
- Code: `research/hunt/r19/` (`sim.py` arm + locks, `run.py` stages, `test_r19.py`).
  Results: `r19/results/design.json`, `holdout.json`, `today.json`. Log: `research/experiments/entries/r19.yaml`.
- Raw data stays out of git.

## Verdict (read this first)

**No lock passed. The app does not change.** Liquidity keeps no profit lock, on paper and live.

- 0 of 12 tests (6 locks x 2 BANKNIFTY books) passed. On the held-back 136 sessions, 11 of the 12 lost money against
  the plain rules. The one that gained (breakeven at +10% on the 15m book) gained Rs 452 over 36 trades (p 0.40).
  Every Benjamini-Hochberg q is 0.97.
- The walk-forward gate failed on both books before the holdout was read (15m -Rs 15,125, 5m -Rs 6,083), so no variant
  could have passed anyway.
- **MIDCPNIFTY, Boss's book, could not be tested.** The `research-data` release has no MIDCPNIFTY data at all. The
  6-year Dhan lake that h4 used is not published, and the Dhan API was off limits. MIDCPNIFTY stays on the plain rules.

Plain words: **Liquidity makes its money on a few trades that run all the way to the next level. A lock cannot tell
those runners apart from a trade like today's. On the way up, both look the same. So a lock sells the runners early,
and those cuts cost more than the give-backs it saves.** The arm already cuts its losers with the index stop and the
time stop. Those two rules are what made it work (LIQUIDITY_REVERSAL.md).

### Holdout, 1 Aug 2024 - 14 Feb 2025 (136 sessions; 15m 36 trades, 5m 103 trades)

Net is in Rs, 1 lot of the lot in force, after charges and modelled spread. "vs plain" is the change against the
plain rules over the same trades.

| book | variant | net | vs plain | one-sided p | BH q | % of MFE captured | winners cut short (Rs) | trades helped (Rs) | worst day | max drawdown | twin p |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 15m | d plain rules | +24,227 | | | | 39% | | | -1,763 | -2,837 | |
| 15m | a1 index ladder, keep 25% | +21,438 | -2,789 | 0.94 | 0.97 | 35% | 6 (-3,729) | 2 (+940) | -1,763 | -4,252 | 0.37 |
| 15m | a2 index ladder, keep 50% | +19,290 | -4,937 | 0.94 | 0.97 | 31% | 6 (-5,890) | 3 (+952) | -1,763 | -3,888 | 0.49 |
| 15m | b1 breakeven at +10% | +24,680 | +452 | 0.40 | 0.97 | 40% | 2 (-1,690) | 4 (+2,143) | -1,763 | -2,627 | 0.20 |
| 15m | b2 breakeven at +20% | +24,095 | -132 | 0.54 | 0.97 | 39% | 1 (-1,029) | 2 (+897) | -1,763 | -2,508 | 0.59 |
| 15m | c1 give-back 50% after +10% | +1,966 | -22,262 | 0.97 | 0.97 | 7% | 7 (-25,243) | 4 (+2,981) | -1,763 | -2,269 | 1.00 |
| 15m | c2 give-back 50% after +20% | +10,051 | -14,177 | 0.96 | 0.97 | 20% | 5 (-16,172) | 2 (+1,996) | -1,763 | -2,269 | 0.99 |
| 5m | d plain rules | +32,288 | | | | 29% | | | -2,787 | -7,943 | |
| 5m | a1 index ladder, keep 25% | +21,764 | -10,524 | 0.96 | 0.97 | 22% | 15 (-16,773) | 7 (+6,249) | -2,371 | -9,486 | 0.64 |
| 5m | a2 index ladder, keep 50% | +23,332 | -8,956 | 0.93 | 0.97 | 23% | 18 (-15,952) | 7 (+6,995) | -2,371 | -10,512 | 0.60 |
| 5m | b1 breakeven at +10% | +28,757 | -3,531 | 0.75 | 0.97 | 27% | 5 (-8,788) | 9 (+5,257) | -2,787 | -7,686 | 0.56 |
| 5m | b2 breakeven at +20% | +29,738 | -2,551 | 0.86 | 0.97 | 26% | 2 (-2,551) | 0 | -2,787 | -7,943 | 0.91 |
| 5m | c1 give-back 50% after +10% | +7,651 | -24,637 | 0.93 | 0.97 | 13% | 10 (-34,355) | 11 (+9,718) | -2,787 | -11,025 | 0.98 |
| 5m | c2 give-back 50% after +20% | +21,573 | -10,716 | 0.94 | 0.97 | 20% | 5 (-11,890) | 2 (+1,174) | -2,787 | -7,943 | 0.94 |

- "% of MFE captured" is the sum of (exit - fill) over the sum of each trade's best price after the fill (MFE, taken
  from the plain-rules trade). It is measured on the 36 / 102 trades with a positive MFE.
- "Winners cut short" counts trades that won on the plain rules and earned less with the lock. "Trades helped" counts
  trades the lock improved. The twin p is the share of 1,000 random twins (early exits on random trades, at the lock's
  own delays) that did at least as well. A good lock needs a twin p under 0.05.

### Gates (PREREG.md)

| book | variant | G1 design | G2 walk-forward | G3 holdout + BH | G4 twin | G5 risk | G6 1.5x spread | pass |
|---|---|---|---|---|---|---|---|---|
| 15m | a1 | yes (+1,739, 5/8 folds) | no | no | no | no (drawdown) | no | **no** |
| 15m | a2 | yes (+2,945, 5/8 folds) | no | no | no | no (drawdown) | no | **no** |
| 15m | b1 | no (-2,962) | no | no (q 0.97) | no | yes | yes | **no** |
| 15m | b2 | no | no | no | no | yes | no | **no** |
| 15m | c1 | no | no | no | no | yes | no | **no** |
| 15m | c2 | no | no | no | no | yes | no | **no** |
| 5m | a1 | yes (+2,768, 5/8 folds) | no | no | no | no (drawdown) | no | **no** |
| 5m | a2 | no | no | no | no | no | no | **no** |
| 5m | b1 | yes (+3,432, 5/8 folds) | no | no | no | yes | no | **no** |
| 5m | b2 | no | no | no | no | yes | no | **no** |
| 5m | c1 | no | no | no | no | no | no | **no** |
| 5m | c2 | no | no | no | no | yes | no | **no** |

### Design, 13 Feb - 31 Jul 2024 and 17 Feb 2025 - 23 Feb 2026 (362 sessions; 15m 91 trades, 5m 283 trades)

| variant | 15m net | 15m vs plain | 5m net | 5m vs plain | MFE captured 15m / 5m | winners cut 15m / 5m |
|---|---|---|---|---|---|---|
| d plain rules | +35,759 | | +39,548 | | 27% / 19% | |
| a1 | +37,498 | +1,739 (p 0.07) | +42,316 | +2,768 (p 0.33) | 28% / 19% | 4 / 19 |
| a2 | +38,703 | +2,945 (p 0.025) | +38,548 | -1,000 | 29% / 18% | 2 / 27 |
| b1 | +32,796 | -2,962 | +42,979 | +3,432 (p 0.29) | 24% / 20% | 4 / 12 |
| b2 | +30,860 | -4,898 | +40,992 | +1,444 (p 0.24) | 22% / 20% | 2 / 3 |
| c1 | +766 | -34,992 | +17,039 | -22,509 | 5% / 11% | 9 / 22 |
| c2 | +17,527 | -18,231 | +37,562 | -1,986 | 12% / 19% | 4 / 11 |

The best design result (a2 on 15m: +Rs 2,945 over 91 trades, p 0.025 before correction) was a few trades, and the
holdout reversed it (-Rs 4,937). Walk-forward, fold by fold, choosing the best lock so far lost on both books:
- 15m: -Rs 15,125 over 7 folds. The first choice, c2, lost Rs 17,390 in 2024 Q2; after that, a2 made +Rs 2,265 in 6 folds.
- 5m: -Rs 6,083.
No lock changed the 15m book's worst day in either period (design -Rs 5,294, holdout -Rs 1,763): a lock
only acts on trades that first went up.

## Today's trade under each lock (illustration only, NOT evidence)

Liquidity 5m MIDCPNIFTY PE, 1 lot of 120: bought at 195.45, best about 216.45 (+21, +10.7%), sold at 188.25 on the
index stop. Boss's -Rs 864 is (188.25 - 195.45) x 120, before charges. Breakeven after charges is 196.31.

| variant | what it would have done | exit | before charges |
|---|---|---|---|
| d plain rules | index stop | 188.25 | -Rs 864 |
| a1 / a2 index ladder | unknown: it needs the index path and the trade's target level, which are not in the release | - | - |
| b1 breakeven at +10% | +10.7% armed it; sold at breakeven | 196.35 | +Rs 108 (about 0 after charges) |
| b2 breakeven at +20% | never armed (+10.7% < 20%) | 188.25 | -Rs 864 |
| c1 give-back 50% after +10% | armed; sold at half the best gain | 205.95 | +Rs 1,260 |
| c2 give-back 50% after +20% | never armed | 188.25 | -Rs 864 |

The lock that would have saved today's trade best (c1) was the WORST lock in every period:
- design: -Rs 34,992 on 15m and -Rs 22,509 on 5m;
- holdout: -Rs 22,262 on 15m and -Rs 24,637 on 5m.
It also cut 7 and 10 holdout winners short. One painful give-back is easy to see. The runners a lock would have
sold early are not.

## Data and setup

| | |
|---|---|
| Data | `research-data` release: BANKNIFTY index + option minute bars (OHLC, volume), 13 Feb 2024 - 23 Feb 2026, 498 sessions; nearest expiry after each day (the contract the app's Liquidity buys, expiry days included) |
| Books | BANKNIFTY 15m (91 + 36 trades) and 5m (283 + 103 trades). 3 design signals had no option prints (skipped); 0 in the holdout |
| Arm | engine `LiquidityRules` as of 13 Oct: pool-on-swing break, room filter (1 index stop), 1 ITM, entries 09:20-14:00, resting 15% premium stop, 30-point index stop, 20-minute +5% time stop, next-level target, failed break, new level, 15:10 |
| Locks | resting stop that only rises, from what was known before the minute (the best option high and the best index move of the earlier minutes since the entry). Never below breakeven after charges. A trigger counts only below the best price seen and is rounded up to the 0.05 tick (`ProfitLock.onTick`). Filled at min(lock, open) with stop slippage |
| Fills / charges | app paper fills (5 bps market, 10 bps stops) plus a 1 / 2 / 4-tick half-spread from the last 5 minutes' volume (x2 on stops); the app's charges at today's rates; 1 lot of the lot in force (15 / 30 / 35) |
| Holdout | 1 Aug 2024 - 14 Feb 2025, read once. Every BANKNIFTY session from 23 Feb 2025 on was already read as a holdout (i6, R15, R16, R17, h4), so the holdout had to come from the first release year |
| Tests | 12 (6 locks x 2 books), one BH family at q 0.10; 1,000 random twins per test |

## Honest limits

- **Not the newest data.** The holdout is older than half of the design, because the newer sessions were already spent
  as holdouts.
- **The data had been seen before.** LIQUIDITY_LOCK.md (5 premium-target ladders) and LIQUIDITY_TRAIL.md (10 trails)
  ran on these same two years in-sample, with older rules. They found the same thing: every ladder and early trail was
  worse. R19 confirms it on today's rules, out of sample.
- **Small books.** The 15m holdout has 36 trades. A lock that helps a little cannot be told from noise at this size.
  But 11 of 12 holdout deltas are negative, so the direction of the evidence is clear.
- **Same entries for every variant.** Each variant takes the plain rules' trades. A lock that exits earlier could free
  the book for another signal. That happened once in all the data (1 holdout signal, for a1 and a2), so it does not
  change the result.
- **MIDCPNIFTY untested.** Its 8-point index stop and thinner options could behave differently. Test it with the same
  code once MIDCPNIFTY minute data (index + options) is in a release or the lake. Until then it stays on the plain rules.

## For Jarvis (when Boss asks "why no profit lock on Liquidity?")

Tested on 13 Oct (R19): six profit locks on BANKNIFTY Liquidity's real option prices, and none passed. On the 6 held-back
months, 11 of the 12 tests lost money, because Liquidity's profit comes from a few trades that run to the next level
and a lock sells those early. MIDCPNIFTY could not be tested: there is no data for it.
