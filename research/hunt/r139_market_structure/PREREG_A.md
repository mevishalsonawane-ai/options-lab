# R139 Amendment A pre-registration: enhancing S3 (the CHoCH reversal) with liquidity, volume, order flow, SMC and more

Written 31 Oct 2026, after the R139 results and before any Amendment A number. Research only (no app code).

Boss, 31 Oct, after reading R139: *"Enhance it, try to add multiple parameters like liquidity, volumn and order flow,
smc, and all things and find how could we enhace it"*.

## What is enhanced, and the honest limits

- **The base is S3**, R139's best rule. After a 15-minute down-trend (lower highs and lower lows), the first 15-minute
  close above the last lower high buys the ATM call; the put side is the mirror. R139's numbers:
  - design: +Rs 185 a trade on 1,663 trades, but past the account stop;
  - holdout: +Rs 61 on 291, no better than random entries.
  S3 was chosen **because** it was the best of 12, so its design edge is biased upwards. That bias carries into every
  version below.
- **The holdout months (1 Apr - 19 Oct 2026) were already read once for S3.** Amendment A gets one read of them with
  its own lock (`HOLDOUT_READ_A.json`), labelled **"already seen"**.
  - A version that passes design and that read would still need a **forward test on new days** (R134's log) before
    any paper arm.
  - Never live from this study.
- **Data limits:**
  - The index minutes have no volume (always 0). There is no tick, bid/ask or futures order-flow history in the lake
    for 2021-2026.
  - So **volume and order flow are read from the option chain**: ATM ± 5 strikes, calls and puts, minute volume and
    open interest (R123's chain features). This is context around the index signal, as CLAUDE.md allows. Signals stay
    on the index.
  - India VIX comes from the lake's INDIAVIX minutes.

## The 19 versions (one BH family) plus the base for reference

Every filter is read at the CHoCH bar's close (the minute before entry), from data up to then only. Each filter is
applied to S3's signals **before** the one-trade-at-a-time sequencing. Unless said otherwise, the entry, stop (0.02%
below the leg's low) and 2R target are S3's. Directions are for the call; the put side is the mirror.

| id | family | the add-on |
|---|---|---|
| A0 | base | S3 exactly as R139 (reference only, not a new test) |
| **L1** | liquidity (SMC) | **sweep:** the down-leg (from the lower high to the low) took out a swing low that was known and untaken at the lower high, or took the previous session's low for the first time that session |
| **L2** | SMC | **displacement:** the CHoCH bar's body (close - open) >= 1 x ATR14 |
| **L3** | SMC | **fair value gap:** a 3-bar bullish gap (bar x's low above bar x-2's high by >= 0.01%) between the low bar and the CHoCH bar, complete by the CHoCH bar |
| **L4** | SMC premium/discount | **discount:** the CHoCH close is at or below the middle of the range from the swing high before the lower high down to the leg's low |
| **L5** | SMC entry | **order-block retest** (replaces the entry): the block is the last down (red) 15-min bar from the lower high to the low. After the CHoCH, buy at the next minute after the first 1-minute low at or below the block's high, within 12 bars in the same session. Stop 0.02% below the lower of the block's low and the leg's low. 2R |
| **V1** | volume | **surge:** chain volume over the last 15 minutes >= 1.5 x the session's average 15-minute chain volume so far. Needs >= 15 minutes of baseline, so no entry before 09:45 qualifies |
| **OF1** | order flow (OI) | **PCR rose** over the last 15 minutes (put OI built faster than call OI: put writing, bullish) |
| **OF2** | order flow (volume) | **call volume > put volume** over the last 15 minutes |
| **C1** | context | India VIX (last value before entry) above the median of the previous 20 sessions' closing VIX |
| **C2** | context | the 60-minute Dow trend (R139 S6's) is not against the trade |
| **C3** | time (R113) | entries to 11:15 only |
| **C4** | network | R123's N1 expected value > 0 for the side (S12's filter, 2023 on, R132's subset test) |
| **D1** | risk | day stop: on an index, after 2 losing trades in a day, no more that day |
| **D2** | risk | stop cap: skip when the stop is more than 1.5 x ATR14 below the CHoCH close |
| **X1** | exit | index stop + 60% premium lock after +Rs 1,000 (R132 E3), no fixed target |
| **X2** | exit | breakeven at 1R, then a 1R trail (R132 E4), no fixed target |
| **K1** | stack | L1 + L2 (the SMC "sweep, then displacement" shift) |
| **K2** | stack | V1 + OF1 + OF2 (volume and both flows agree) |
| **K3** | stack | every single filter among L1, L2, L3, L4, V1, OF1, OF2, C1, C2, C3, D2 whose design average net per trade is above A0's on >= 300 trades, all together. The rule is fixed here; which filters enter it is decided by the design results. If none qualifies, K3 has no trades and fails |

## Tests and gates (R139's, unchanged)

- **Random-entry twins:** 100 per trade, entry uniform from the trade's own minute to 15:00, the same stop, target
  and exit. For C4, R132's random-subset test instead.
- **Benjamini-Hochberg** over the 19 tested versions (A0 is not tested).
- **Design (1 Aug 2021 - 15 Mar 2026)** passes only if all of these hold:
  1. >= 300 trades;
  2. average net > 0 at 1x and 2x slippage;
  3. twins p < 0.05;
  4. q <= 0.10;
  5. >= 3 of 4 folds positive;
  6. Boss's account stop on 1 lot over the five indices: drawdown above -Rs 40,000, no month below -Rs 13,000.
- **Holdout (already seen), read once after the design results are pushed:** average net > 0 at 1x and 2x on >= 50
  trades.
- **Win rate against Boss's 65%** is reported for every version, not gated.
- **Anything that passes** goes to Boss as a **forward-test proposal only** (not a paper arm), because of the seen
  holdout and the selection of S3.

## Outputs

- `r139a.py`, `test_r139a.py` (each add-on on hand-built bars, the chain flags on a made-up chain, the look-ahead
  guard).
- `results/design_a.json`, `results/holdout_a.json`.
- A section in `research/HUNT_R139_MARKET_STRUCTURE.md`; entries `research/experiments/entries/r139a-*.yaml`;
  RESULT.md.
