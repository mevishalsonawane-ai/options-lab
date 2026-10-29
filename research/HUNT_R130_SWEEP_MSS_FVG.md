# R130: the liquidity sweep → structure shift → fair value gap video setup, backtested as stated

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss sent a video's setup:
- **Liquidity sweep:** price takes out the stops below a recent low (or above a recent high).
- **Market structure shift (MSS):** price then breaks the recent high (or low).
- **Fair value gap (FVG):** the gap left by the strong move, used as the entry zone.

Bullish, on a 5-minute chart:
1. Mark the recent low and high.
2. Wait for price to dip below the low (the sweep).
3. Wait for it to turn and break the high (the MSS).
4. Enter when price pulls back into the move's FVG and prints an engulfing candle.
5. Stop just below the FVG; target the next liquidity, such as the equal highs above.

Bearish is the mirror.

## The answer: it loses money, in every version, before and after the cut-off

**On all five indices from Aug 2021 to 19 Oct 2026, buying the ATM option on this setup lost money after charges and
slippage in all 6 versions. It lost in the walk-forward (design) and again in the locked holdout. No version did better
than random entries with the same stop and target. No version came near Boss's 65% win rate: the best was 50%, on 14
trades.**

Design, Aug 2021 to 15 Mar 2026 (1,144 sessions):

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | max drawdown | vs random entries (p / BH q) |
|---|---|---|---|---|---|---|---|
| **V1, the rule as stated** (5-min) | 218 | 45% (below) | Rs 716 / -Rs 1,158 | **-Rs 307** | **-Rs 66,966** | -Rs 69,435 | no better (0.995 / 1.00) |
| V2, 1-minute chart | 1,706 | 42% (below) | Rs 382 / -Rs 398 | -Rs 70 | -Rs 1,19,783 | -Rs 1,29,977 | no better (0.23 / 0.70) |
| V3, no engulfing (enter on the first touch of the FVG) | 1,073 | 43% (below) | Rs 586 / -Rs 596 | -Rs 84 | -Rs 89,739 | -Rs 96,906 | no better (0.06 / 0.36) |
| V4, fixed 2R target | 226 | 32% (below) | Rs 1,926 / -Rs 1,454 | -Rs 362 | -Rs 81,871 | -Rs 1,05,380 | no better (1.00 / 1.00) |
| V5, longer swings (5 bars each side) | 111 | 42% (below) | Rs 747 / -Rs 1,457 | -Rs 524 | -Rs 58,146 | -Rs 59,358 | no better (1.00 / 1.00) |
| V6, the sweep must close back inside | 132 | 45% (below) | Rs 653 / -Rs 916 | -Rs 214 | -Rs 28,300 | -Rs 31,385 | no better (0.97 / 1.00) |

Holdout, 1 Apr to 19 Oct 2026 (137 sessions). It was read once, after the design results were committed, at
12:33-12:34 UTC on 29 Oct:

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | max drawdown | vs random entries (p) |
|---|---|---|---|---|---|---|---|
| **V1, as stated** | 36 | 39% (below) | Rs 845 / -Rs 1,765 | **-Rs 750** | **-Rs 26,997** | -Rs 28,501 | 0.995 |
| V2, 1-minute | 206 | 33% (below) | Rs 373 / -Rs 436 | -Rs 173 | -Rs 35,550 | -Rs 40,500 | 0.95 |
| V3, no engulfing | 126 | 38% (below) | Rs 506 / -Rs 859 | -Rs 339 | -Rs 42,689 | -Rs 44,599 | 0.97 |
| V4, 2R | 36 | 19% (below) | Rs 1,427 / -Rs 1,760 | -Rs 1,141 | -Rs 41,059 | -Rs 41,059 | 1.00 |
| V5, longer swings | 14 | 50% (below) | Rs 667 / -Rs 725 | -Rs 29 | -Rs 409 | -Rs 3,858 | 0.54 |
| V6, close back inside | 22 | 36% (below) | Rs 778 / -Rs 1,887 | -Rs 918 | -Rs 20,199 | -Rs 20,698 | 1.00 |

- **Gates (none passed; most failed several):**
  - The trade count was too small (V1, V4, V5 and V6 had under 300 design trades).
  - Every version lost money at 1x and 2x slippage.
  - Only 0-1 of the 4 walk-forward folds was positive.
  - No BH q was 0.10 or less.
  - In the holdout, every version lost money and none reached 65% wins; only V2 and V3 had 50 or more trades.
  - Over design plus holdout, every version's drawdown on 1 lot was worse than Boss's -Rs 40,000 account stop:
    - V1 -Rs 94,001 over 254 trades;
    - V6 -Rs 48,499, the smallest.
- **By year (V1):**

  | year | net (1 lot) | trades | win rate |
  |---|---|---|---|
  | 2021 (from Aug) | -Rs 2,630 | 10 | 40% |
  | 2022 | -Rs 7,420 | 28 | 46% |
  | 2023 | -Rs 1,837 | 52 | 60% |
  | 2024 | -Rs 34,108 | 59 | 44% |
  | 2025 | -Rs 18,961 | 56 | 36% |
  | 2026 to 15 Mar | -Rs 2,009 | 13 | 38% |
  | holdout (Apr to Oct 2026) | -Rs 26,997 | 36 | 39% |

  V2 lost in every year. V3-V6 each had one or two small positive slices (for example V3 2022 +Rs 2,582 on 163 trades;
  V4 Jan-Mar 2026 +Rs 14,453 on 15; V5 2023 +Rs 3,601 on 27), with losses on either side.
- **By index (V1, design):**

  | index | net (1 lot) | trades | win rate |
  |---|---|---|---|
  | NIFTY | -Rs 9,264 | 60 | 52% |
  | BANKNIFTY | -Rs 15,642 | 49 | 47% |
  | FINNIFTY | -Rs 15,523 | 33 | 39% |
  | MIDCPNIFTY | -Rs 8,673 | 38 | 50% |
  | SENSEX | -Rs 17,864 | 38 | 34% |

  All 30 index × version cells in design lost money. In the holdout, NIFTY V1 made +Rs 2,707 (6 trades), and a few
  other cells were slightly positive on 2-18 trades each. That is noise.
- **The setup is rare as stated.**
  - Funnel on the 5-minute chart (V1, all five indices, Jan 2021 to Mar 2026):

    | stage | count |
    |---|---|
    | sweeps | 54,096 |
    | MSS closes within 12 bars | 6,977 |
    | with an FVG in the leg | 4,098 |
    | pulled back into the FVG within 12 bars | 1,899 |
    | engulfing candle | 352 |

  - That is about 50 trades a year across all five indices.
  - Moving to the 1-minute chart (V2) or dropping the engulfing candle (V3) gives more trades, but they lose too.

**Nothing passes. Nothing goes on paper. Nothing changes in the app.**

## The video's claims, checked on the data (index wicks; design / holdout)

| claim | what the data shows |
|---|---|
| C1 "the target is hit more often than the stop" | **True on the index, but it does not pay.** V1 hit its target 124 times and its stop 70 times in design (holdout 17 and 17). But the "next liquidity" target is close: its median distance is 0.48 of the stop's (V1, 218 trades). So the average index win was 24 points against a 59-point loss, and the average trade lost 7.7 index points (-0.05R). On the option, only 73% of the index-target trades made money after charges, and the win rate fell to 45%. With the fixed 2R target (V4), the stop came first 118 times against 39 targets (holdout 23 against 3). |
| C2 "price comes back into the FVG" | **Less than half the time.** Of 4,098 sweep → MSS → FVG setups on the 5-minute chart (Jan 2021 to Mar 2026), 1,899 (46%) traded back into the FVG within 12 bars. From 16 Mar to 19 Oct 2026 it was 231 of 497 (46%). Only 352 then printed an engulfing candle in time. |
| C3 "the sweep first makes the break stronger" | **No, slightly the opposite.** Every first 5-minute close beyond a swing with an FVG in its leg was tested with a 1:1 race on the index (+d vs -d, d = the distance from the leg's extreme). With a sweep in the 12 bars before, price went the break's way first 50.7% of the time (n 3,454 decided). Without a sweep it was 52.9% (n 6,808). p = 0.03, the wrong way for the claim. Holdout: 48.2% (n 394) vs 52.7% (n 808), p = 0.14. |
| C4 "wait for the engulfing candle" | **It did not help.** V1 (engulfing) lost Rs 307 a trade (218 trades); V3 (enter on the first touch) lost Rs 84 (1,073). In the holdout: -Rs 750 (36) vs -Rs 339 (126). |

**Index-points version (context, no costs).** The average trade in index points / R:

| version | design | holdout |
|---|---|---|
| V1 | -7.7 / -0.05R | -21.8 / -0.16R |
| V2 | +0.4 / -0.02R | -0.6 / -0.08R |
| V3 | +0.3 / +0.03R | -7.0 / -0.16R |
| V4 | -7.4 / -0.06R | -28.5 / -0.34R |
| V5 | -18.6 / -0.11R | +8.6 / +0.26R |
| V6 | -3.1 / -0.04R | -29.9 / -0.23R |

The points mix the five indices. Even before any cost, the setup is a coin flip or worse on the index. V5's
holdout +0.26R is 14 trades, after -0.11R on 111 trades in design.

## How it was done

- **Pre-registration:** `research/hunt/r130_sweep_mss_fvg/PREREG.md`, commit `66d2467`, before any data was read.
  - Design results were committed in `fca117a` before the holdout.
  - The holdout was read once, guarded by a lock file that refuses a second read.
- **Code and 16 tests:** `research/hunt/r130_sweep_mss_fvg/` (`r130.py`, `test_r130.py`, `tables.py`).
  - The tests include a hand-built session, worked out by hand, with its mirror-image sell.
  - They also include a look-ahead guard: removing every bar after the entry does not change the entry.
- **Results:** `results/design.json`, `results/holdout.json`. The trade lists are in the cache (`trades_*.parquet`), not
  git.
- **Signal source: the index spot, always.**
  - Every swing, sweep, MSS, FVG, pullback, engulfing candle, stop and target is read on the index's bars.
  - The ATM option (CE for bullish, PE for bearish) is only what is bought.
  - No option-chain input is used.
- **Definitions** (wicks for levels; bodies only for the engulfing candle and the MSS close):
  - **Swing:** a high strictly above the 3 highs before it and at or above the 3 after (V5: 5). It is known only 3
    bars later. Swings from the last 5 sessions are used.
  - **Recent low:** the latest swing low not yet taken out.
  - **Sweep:** a wick at least 1 tick (0.05) below it (V6: and a close back above).
  - **MSS:** a close above the latest swing high within 12 bars.
  - **FVG:** the largest three-candle gap in the leg from the sweep's low to the MSS bar, at least 2 ticks and
    0.01% of the index.
  - **Pullback:** a low into the FVG's top within 12 bars.
  - **Engulfing:** a bullish body that engulfs a non-bullish previous body.
  - **Entry:** buy at the next minute's open, 09:15-15:00.
  - **Stop:** 0.02% beyond the FVG's far edge.
  - **Target:** the nearest pair of equal highs (within 0.03%) above the entry, else the nearest swing high (V4: 2R).
  - **Exits:** a stop and a target in the same minute count as the stop; otherwise out at 15:20.
  - **One trade at a time per index.**
- **The option:** the nearest expiry that does not expire that day.
  - On the index's own expiry day, the monthly contract is bought when it expires later. The lake has no next-week
    series. 21 of the 254 V1 trades (design plus holdout) used the monthly.
  - Days when both series expire are skipped (V1: 30 setups).
  - 1 lot at today's lots, dated charges, liquidity-aware slippage (also at 2x), lake cleaning steps 1-3.
- **Random twins (200 per trade):** the same index, day and side; a random entry minute 09:20-15:00; the same stop and
  target distances in points; the same option rule.
- **Gates:**
  - design: ≥ 300 trades; positive at 1x and 2x slippage; ≥ 3 of 4 folds positive; BH q ≤ 0.10 over the 6 versions;
  - holdout: ≥ 50 trades; positive; win rate ≥ 65%;
  - drawdown above -Rs 40,000 and no month below -Rs 13,000.

## Notes

1. **Choices the video leaves open were fixed before any data was read:**
   - 3-bar swings;
   - 12-bar windows;
   - the largest FVG;
   - a 0.02% stop buffer;
   - a 0.03% equal-highs tolerance.

   V2-V6 cover the obvious other readings: the 1-minute chart, no engulfing candle, a 2R target, longer swings and a
   close back inside. All six lose, so a different reading is unlikely to change the picture.
2. **One detail the pre-registration did not spell out, fixed in the code before any run:** while a setup waits for
   its pullback, new sweeps on that side are ignored.
3. **The funnel counts include Jan-Jul 2021 index bars.** Those were used only to know the swings; no trades or
   twins come from them, because the options start in Aug 2021.
4. **Earlier work agrees:**
   - R62 (SMC with FVG / BOS / ChoCH, 0 of 32);
   - R62b (market structure break + order block, 0 of 16);
   - R115's SMC sweep row.

   Smart-money-concepts entries on these indices are a coin flip on the index, and the option's costs then make them
   lose.
