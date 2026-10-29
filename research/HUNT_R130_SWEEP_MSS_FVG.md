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

**Nothing passes. Nothing goes on paper. Nothing changes in the app.** The wick-only versions (Amendment 1)
the 96-version improvement search with the profit lock (Amendment 2) and the 65% win-rate search (Amendment 3), all below,
fail too.

## Amendment 1: wick-only versions (Boss: "Not opening and closing candles")

**Added after the results above, and after the holdout had been read for V1-V6.** Its holdout read is therefore less
clean than a first read. Pre-registered in `PREREG_WICK.md` (commit `fad9c7c`) before any wick-only number. The
design results were committed in `68df4c7` before its own one holdout read (13:17-13:18 UTC, 29 Oct).

V1-V6 already read every level, the sweep, the FVG, the pullback, the stop and the target on wicks. Two steps used
candle bodies. The amendment makes them wick-only:
- **MSS:** a **wick** at least 1 tick beyond the recent high or low (it was a 5-minute close).
- **Engulfing:** a candle whose **wicks engulf the previous candle's wicks**: a higher high, and a low at or below the
  previous low (mirrored for sells). It used to compare bodies.

No candle open or close decides anything now. The only open used is the price paid in the entry minute, which is a
fill, not a signal. A test scrambles every candle's open and close inside its wicks, and W1's trades do not change.

**It still loses, in every version.** Design (Aug 2021 to 15 Mar 2026):

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | max drawdown | vs random (p / BH q) |
|---|---|---|---|---|---|---|---|
| **W1, as stated, wick-only** (5-min) | 485 | 44% (below) | Rs 848 / -Rs 711 | **-Rs 26** | -Rs 12,584 | -Rs 50,367 | 0.58 / 0.78 |
| W2, no confirmation (first touch of the FVG) | 1,410 | 43% (below) | Rs 718 / -Rs 587 | -Rs 27 | -Rs 37,942 | -Rs 44,049 | **0.005 / 0.02** |
| W3, 1-minute chart | 3,473 | 41% (below) | Rs 309 / -Rs 370 | -Rs 94 | -Rs 3,25,564 | -Rs 3,30,484 | 0.47 / 0.78 |
| W4, fixed 2R target | 496 | 38% (below) | Rs 1,680 / -Rs 1,028 | -Rs 7 | -Rs 3,506 | -Rs 76,831 | 0.97 / 0.97 |

Holdout, Apr to Oct 2026, read once:

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | vs random (p) |
|---|---|---|---|---|---|---|
| **W1** | 61 | 43% (below) | Rs 911 / -Rs 1,127 | **-Rs 258** | -Rs 15,747 | 0.65 |
| W2 | 177 | 32% (below) | Rs 624 / -Rs 770 | -Rs 321 | -Rs 56,811 | 0.96 |
| W3 | 443 | 37% (below) | Rs 262 / -Rs 431 | -Rs 174 | -Rs 77,184 | 0.99 |
| W4 | 62 | 27% (below) | Rs 1,753 / -Rs 1,233 | -Rs 414 | -Rs 25,695 | 0.98 |

- **The wick break catches more setups and loses less per trade than the close break.**
  - W1 had 485 design trades against V1's 218, at -Rs 26 a trade against -Rs 307.
  - In index points, W1's target and stop are about the same distance (median 0.90). The average win was 35.9 points
    and the average loss 36.2: a coin flip on the index, and costs make it a small loss.
- **W2 is the only version that beat random entries** (p 0.005, q 0.02 in design). Even so, it lost Rs 27 a trade
  after costs. In the holdout it fell to 32% wins and -Rs 321 a trade (p 0.96), so the design edge did not hold.
- **Every version fails the gates:**
  - every one lost money in design and in the holdout;
  - at most 2 of the 4 walk-forward folds were positive (W1 and W4: 2025 and 2026 Q1);
  - all are far below 65% wins;
  - over design plus holdout, every drawdown is past the -Rs 40,000 account stop (W1 -Rs 50,367 over 546 trades;
    W3 -Rs 4.07 lakh).
- **By index (W1, design):** MIDCPNIFTY +Rs 12,555 (61 trades) and SENSEX +Rs 3,057 (82). BANKNIFTY -Rs 13,676 (122),
  NIFTY -Rs 7,926 (151) and FINNIFTY -Rs 6,594 (69) lost. In the holdout, MIDCPNIFTY again made +Rs 9,218 on 12
  trades and the rest lost.
  - That is one index out of five, after the fact, on 73 trades in all. It is not a reason to trade it.
- **C3 with wick breaks:** with a sweep first, price went the break's way first 54.3% of the time (n 3,224 decided).
  Without one it was 55.2% (n 7,549), p = 0.39. Holdout: 47.3% (n 330) vs 52.8% (n 915), p = 0.09. **The sweep still
  does not help.**

**Verdict unchanged: wick-only, the setup still fails. Nothing goes on paper.**

## Amendment 2: can it be improved? (Boss: "Can we enhance it?" and "Also check what will happen if we add profit lock")

**A search on an idea that had already failed, added after its results.**
- **Pre-registration:** `PREREG_ENH.md` (commit `1564595`), before any number.
- **Design results:** committed in `c2a4c44` before the one check on Apr-Oct 2026.
- **That check is labelled "already seen":** this period had been read twice before (V1-V6, W1-W4), so even a pass
  there would be weak evidence.
- **New data:** the lake ends on 19 Oct, so there is none to test on yet.

Starting from W1 (wick-only, as stated), 96 versions crossed the three improvements Boss chose, plus the profit lock:
- **Liquidity:** any 3-bar swing (as before), or major liquidity only (yesterday's low, the opening-range low, equal
  lows).
- **Time:** all day, or the morning only (entries to 11:00).
- **Trend:** none, or the 15-minute structure must agree.
- **Stop:** any size, or at least 0.10% of the index, so charges are a smaller share.
- **Strike:** ATM or 1-in-the-money.
- **Profit lock** on the option premium: none; Boss's S5 lock (after +Rs 1,000, exit if it falls back to 60% of the
  best open profit); or the same after +Rs 500.

BH ran over 106 tests (these 96 plus R130's earlier 10).

**The answer: some versions turn positive on the past data, but none passes, and none beats random entries.**

What each change did (design, Aug 2021 - Mar 2026; each level averaged over the other factors):

| change | avg net per trade | trades per version | win rate |
|---|---|---|---|
| **Profit lock: none** | **+Rs 97** | 135 | 54% |
| Profit lock: +Rs 1,000, keep 60% | -Rs 108 | 135 | 59% |
| Profit lock: +Rs 500, keep 60% | -Rs 96 | 135 | 65% |
| No trend filter | -Rs 67 | 161 | 57% |
| **15-minute trend agrees** | **-Rs 5** | 109 | 61% |
| ATM | -Rs 49 | 136 | 58% |
| 1-ITM | -Rs 23 | 135 | 60% |
| Any stop | -Rs 39 | 166 | 56% |
| Stop ≥ 0.10% | -Rs 33 | 105 | 62% |
| 3-bar swings | -Rs 43 | 189 | 58% |
| Major liquidity only | -Rs 29 | 82 | 61% |
| All day | -Rs 31 | 217 | 55% |
| Morning only | -Rs 41 | 53 | 64% |

- **The profit lock makes it worse.** It raises the win rate but cuts the winners short. On the best version (W1 with
  the 15-minute trend, a stop of at least 0.10% and 1-ITM; 201 trades):

  | lock | win rate | avg win / avg loss | avg net per trade | total (1 lot) |
  |---|---|---|---|---|
  | **none** | 57% | Rs 1,205 / -Rs 1,080 | **+Rs 216** | +Rs 43,432 |
  | +Rs 1,000, keep 60% | 62% | Rs 676 / -Rs 1,037 | +Rs 29 | +Rs 5,740 |
  | +Rs 500, keep 60% | **67%** (meets 65%) | Rs 418 / -Rs 903 | **-Rs 16** | -Rs 3,194 |

  With the +Rs 500 lock the setup clears the 65% win rate and still loses money: a high win rate is not the same as
  making money. On plain W1 (485 trades), the lock turned -Rs 26 a trade into -Rs 75 (+Rs 1,000) and -Rs 63 (+Rs 500).
- **The 15-minute trend filter helped most.**
  - W1 with the trend filter, a stop of at least 0.10% and 1-ITM made +Rs 216 a trade (201 trades, 57% won). It was
    positive in 3 of 4 folds:

    | fold | net (1 lot) | trades |
    |---|---|---|
    | 2023 | -Rs 13,183 | 33 |
    | 2024 | +Rs 4,524 | 53 |
    | 2025 | +Rs 29,003 | 50 |
    | 2026 Q1 | +Rs 19,466 | 15 |

  - The same with ATM made +Rs 148 (201 trades).
- **But it is not the pattern.** Neither beat random entries on the same days, the same side and the same stop and
  target (p 0.36 and 0.52; BH q 0.95). The twins share the day and the trend's direction, so the gain comes from
  trading *with the 15-minute trend*. The sweep → MSS → FVG pattern does not add to it.
- **None passes the design gates.** No version had both 300+ trades and positive money. The best had only 201 trades,
  and every q was 0.95.
- **The two best, checked once on Apr-Oct 2026 ("already seen"):**

  | version | trades | win rate | avg net per trade | total (1 lot) | vs random (p) |
  |---|---|---|---|---|---|
  | trend + stop ≥ 0.10% + ATM | 25 | 56% | +Rs 182 | +Rs 4,543 | 0.19 |
  | trend + stop ≥ 0.10% + 1-ITM | 24 | 50% | -Rs 21 | -Rs 515 | 0.51 |

  - Both are below the 65% rule and under 50 trades.
  - The ATM version's profit is MIDCPNIFTY's: +Rs 12,947 on 6 trades. The other four indices lost Rs 8,404 together,
    BANKNIFTY alone -Rs 9,113 on 9.
  - Over design plus this period, they would have stayed inside Boss's account stop (drawdown -Rs 24,432 and
    -Rs 21,441; worst month -Rs 7,326 and -Rs 7,853), but that does not make up for the failed gates.

**Verdict: the improved versions fail too. Nothing goes on paper.**

The one lead is the 15-minute trend direction. If Boss wants it, it can be tested on its own, without the SMC pattern,
as a new pre-registered idea (and checked on data after 19 Oct once the lake is topped up).

## Amendment 3: pushing the win rate from 57% to 65% (Boss: "Enhance it to 57 to 65%, try your best")

**The third search on an idea that had failed, after its results.**
- **Pre-registration:** `PREREG_WR.md` (commit `cb30b1a`), before any number.
- **Design and walk-forward results:** committed in `11a2077` before the one "already seen" check (14:23 UTC). That was
  the fourth read of Apr-Oct 2026.
- **The fair test is the walk-forward:** each year's version was picked using only the years before it.

**Why these levers.** Amendment 2's best version won 72% of the time on the index but only 57% on the option. Its
targets were small (median 0.44 of the stop distance), so 1 in 5 trades that reached the target still lost money after
charges, spread and decay. The profit lock was left out because it had already lost money.

32 versions, on W1 with the 15-minute trend and the 1-ITM option, crossed:
- a minimum stop (none or 0.10%);
- a **minimum target distance** (none, 0.10%, 0.15% or 0.20% of the index);
- **the stop's placement** (at the FVG as stated, or wider, beyond the sweep's extreme);
- **the trend** (15-minute, or 15-minute and 60-minute both agreeing).

**The answer: no. No version reached 65% wins, even on the past data it was chosen from.**

- **Best win rates in design (Aug 2021 - Mar 2026):**

  | version | trades | win rate | avg net per trade |
  |---|---|---|---|
  | stop ≥ 0.10%, 15 + 60-min trend | 95 | **60.0%** | +Rs 235 |
  | stop ≥ 0.10%, wider stop, 15-min trend | 479 | 59.1% | +Rs 31 |
  | stop ≥ 0.10%, wider stop, 15 + 60-min trend | 239 | 57.7% | -Rs 41 |

- **The minimum target made more money per trade but LOWERED the win rate.** Bigger targets are reached less often.
  On the 15-minute version with a stop ≥ 0.10%:

  | minimum target | trades | win rate | avg net per trade |
  |---|---|---|---|
  | none | 201 | 57% | +Rs 216 |
  | 0.10% | 101 | 55% | +Rs 518 |
  | 0.15% | 76 | 51% | +Rs 656 |
  | 0.20% | 61 | 44% | +Rs 634 |

- **The wider stop gave more trades and about 59% wins, but almost no profit** (+Rs 31 a trade on 479).
- **None beat random entries** (p 0.36-0.89; BH q 0.94 over 138 tests). Many versions were positive on the past data
  (29 of 32 versions, +Rs 4 to +Rs 699 a trade), but random entry times with the same stop, target, day and trend direction did about
  as well.
- **The walk-forward (the honest test):** each year's version picked only from earlier years:

  | year | picked | trades | win rate | net (1 lot) |
  |---|---|---|---|---|
  | 2023 | wider stop, 15 + 60-min trend | 57 | 46% | -Rs 16,688 |
  | 2024 | stop ≥ 0.10%, 15 + 60-min trend | 29 | 55% | +Rs 5,363 |
  | 2025 | the same | 27 | 70% | +Rs 10,007 |
  | 2026 to 15 Mar | the same | 7 | 29% | +Rs 6,728 |
  | **all 4** | | **120** | **52.5%** (below 65%) | **+Rs 5,410** (+Rs 45 a trade; +Rs 24 at 2x slippage) |

  It was positive in 3 of 4 years, but it fails the 65% rule and the 150-trade minimum.
- **The version picked on all design data** (stop ≥ 0.10%, 15 + 60-min trend; 95 trades, 60% won, +Rs 235 a trade),
  checked once on Apr-Oct 2026 ("already seen"): **13 trades, 38% won, -Rs 503 a trade**, -Rs 6,533 in all.

**Verdict: it cannot be pushed to 65% honestly.**
- The best on the past data was 60%, on 95 trades.
- Picked fairly year by year, it won 52.5%.
- On the latest months it won 38%.
- The one way that did reach 65%, the +Rs 500 profit lock (67%), lost money (Amendment 2).

Nothing goes on paper.

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
- **Code and 34 tests (5 for Amendment 1, 8 for Amendment 2, 5 for Amendment 3):** `research/hunt/r130_sweep_mss_fvg/`
  (`r130.py`, `r130enh.py`, `r130wr.py`, the three `test_*.py`, `tables.py`).
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
