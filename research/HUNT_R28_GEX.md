# R28: dealer gamma exposure (GEX) on Indian indices, and whether it helps any arm

Written 14 Oct 2026, for Boss's request: "Use gamma exposure too".

- **Pre-registration:** `research/hunt/r28/PREREG.md`, commit `4f481e3`. It was pushed before any GEX value or filter
  outcome was computed. The 10 filters, the gates and one locked holdout per family were frozen there; its sha256 is
  stamped in `results/design.json`.
- **Order of steps:** describe results committed `3593ff7e` (`results/describe.*`), design + walk-forward committed
  `ca57405`, then the holdout read once (`results/holdout.*`, flag in the cache).
- **Code and tests:**
  - `research/brain/gex.py` is the one GEX core.
  - `research/brain/features/gex_f.py` adds 12 L4 features to the Data Brain.
  - `research/hunt/r28/` has the lake builder, the arm rebuilds, describe, study and entries.
  - Tests: `research/brain/tests/test_gex.py` (16, with the look-ahead guard) and `research/hunt/r28/test_r28.py` (17).
- **Big tables:** the per-minute GEX of 5 indices × every lake day, 230 MB, is on Drive
  `gdrive:IraAlgo-data-lake/derived/r28_gex/`. It is not in git.
- **Experiment entries:** `research/experiments/entries/r28-*.yaml`: the feature, the describe step and the 10
  filters, all failures.
- **Research only:** nothing in the app changes.

## Verdict for Boss

- **What GEX is, in one line.** An estimate of how many rupees of index futures the option *market-makers* must buy or
  sell for each 1% move. If they are "long gamma" they sell rallies and buy dips, which calms the market. If they are
  "short gamma" they chase the move, which speeds it up.
- **Does it predict anything on Indian indices?** Only the SIZE of the rest of the day, not its direction.
  - When GEX is negative at 10:00 (the common US sign: dealers long calls, short puts), the rest of the day's range is
    bigger on all 5 indices (NIFTY is clear at 09:20, q 0.007, but not at 10:00, q 0.17). Design days only, 519-597
    days per index:

    | index | range negative GEX | range positive GEX |
    |---|---|---|
    | NIFTY | 0.68% | 0.62% |
    | BANKNIFTY | 0.87% | 0.75% |
    | FINNIFTY | 0.81% | 0.75% |
    | MIDCPNIFTY | 1.21% | 0.76% |
    | SENSEX | 0.71% | 0.60% |

    Within VIX levels and days-to-expiry groups it held in 5-6 of 6. R9 (from the evening bhavcopy) found the same
    kind of "size" effect, and its BANKNIFTY sign reversed in its holdout. So treat it as "a bigger or quieter day",
    nothing more.
  - It does **not** tell trend days from chop days, whether an opening-range break runs or fails (passed on
    MIDCPNIFTY only), whether a hero-zero blast comes on expiry, or the next morning's gap (BANKNIFTY weakly only).
- **Does it help any arm? No.** 10 pre-registered filters were tested on the arms rebuilt on the 6-year lake, with each
  arm's real charges and slippage. **0 of 10 passed.**
  - ORB and Range Fade lose money in every year. Any filter that skips days makes them "lose less". But **none of the
    GEX filters did that better than skipping the same number of days at random** (twin p 0.12 to 0.99 in design).
    Even filtered, both arms still lose in the locked holdout.
  - **Liquidity 15+5 BANKNIFTY:** skipping strongly positive GEX gave +Rs 6 a day in design and **-Rs 17 a day in the
    holdout**. After a review fix, its design twin p is 0.017. That is still not significant once all the tries are
    counted (q 0.17 within R28, 0.81 joined), and the holdout went the wrong way.
  - **Hero H0:** keeping only negative-GEX expiries would have **skipped 2 of its 3 winners** in the holdout (about
    Rs 1 lakh together; it kept the biggest, Rs 57.7k): -Rs 1,186 an expiry. Only 16 trades, too few to judge.
  - **Lottery basket at 14:30:** it loses less under negative GEX, because it skips 72% of its trades (736 → 203 in design). That is no
    better than random skipping (p 0.67 design, 0.14 holdout), and it still loses.
  - **Night R3 is a WINNING arm** (+Rs 206 a day in design). The GEX filter cut it to +Rs 119 in design and **from
    +Rs 249 to -Rs 18 a day in the holdout**. Do not filter Night by GEX.
- **What to change in the app: nothing.**
  - No filter goes to paper; nothing goes live.
  - The 12 GEX features are now in the Data Brain for research and for the models.
- **What Jarvis could say live, as information only (never a trade signal):** "Dealer gamma is negative this
  morning on BANKNIFTY. Historically such days had a somewhat wider rest-of-day range (median 0.87% vs 0.75%). That
  says nothing about direction." Plus the gamma walls (the strikes with the most call or put gamma) as levels to
  watch. On expiry afternoons, the biggest-gamma strike did NOT pin the close better than the ATM strike on 4 of 5
  indices.

## 1. The GEX feature (Step 1)

- **Inputs, each minute, point-in-time:**
  - each contract's last traded price at most 30 minutes old (earlier rows of the same session only);
  - its OI;
  - the index close.
- **IV:** Black-Scholes on spot, r 6.5%, q 0, T to 15:30 on the expiry day including the intraday time left. Rows with
  no IV take the nearest strike's IV.
- **Formula:** GEX = gamma × OI × spot² × 0.01, in Rs per 1% move.
  - **The lake's OI is already in units**, not lots. The NIFTY total does not jump 3× at the 28 Nov 2024 lot change.
    So there is no lot multiplier.
- **Sign conventions (all three are computed):**
  - S1 (primary, the US one): dealers long calls and short puts.
  - S2 (customer net buying): each contract's OI change since the open, signed by its price change; dealers take the
    other side.
  - S3: writers short everything (minus the gross).
- **Outputs, per minute:**
  - net, gross and S2, in Rs crore;
  - net / gross;
  - net / its 20-session median at that minute (`gex_norm`);
  - the zero-gamma flip (±5% grid) and spot's side of it;
  - the positive, negative and gross gamma walls;
  - the edge shares;
  - near (WEEK) vs all (WEEK + MONTH when the monthly is a different contract).
- **Expiries** come from the data (R25's time-value rule): NIFTY 271 weeklies, BANKNIFTY 195, SENSEX 175.
- **Brain features** (`python -m research.brain.features list | grep gex`): `gex_net_cr`, `gex_gross_cr`, `gex_s2_cr`,
  `gex_ratio`, `gex_norm_20d`, `gex_flip_dist_pct`, `gex_spot_above_flip`, `gex_wall_pos_dist_pct`,
  `gex_wall_neg_dist_pct`, `gex_wall_abs_dist_pct`, `gex_edge_share`, `oi_edge_share`. They are computed from the
  cube's chain (nearest expiry only) with the same core.

**Who is "long gamma" here?** Under S1, net GEX at 10:00 is positive on 71-82% of days (negative on: NIFTY 18%,
BANKNIFTY 27%, FINNIFTY 24%, MIDCPNIFTY 25%, SENSEX 29%). In 2021-22 negative days were rarer still (ORB's
GEX < 0 days held 50 of 533 holdout trades). R9 saw the same: the sign mostly measures who holds what, and that changes
with regulation.

**Coverage gap.** Only ATM±10 is stored (NIFTY ±500 points, BANKNIFTY / SENSEX ±1,000). All lake days, 10:00 and
13:45 rows:

| index | rows | share of stored gamma at ATM±9/±10 (median) | expiry day | 1-2 days to expiry | 3+ days | share of stored OI at the edge | flip found at 10:00 |
|---|---|---|---|---|---|---|---|
| NIFTY | 2,566 | 3.5% | 0.7% | 3.1% | 5.7% | 11.5% | 100% |
| BANKNIFTY | 2,530 | 5.7% | 1.3% | 4.8% | 8.1% | 11.0% | 89% |
| FINNIFTY | 2,321 | 4.6% | 0.4% | 2.5% | 6.3% | 10.7% | 78% |
| MIDCPNIFTY | 1,642 | 7.7% | 0.7% | 5.7% | 8.9% | 12.0% | 65% |
| SENSEX | 1,226 | 6.8% | 2.3% | 7.4% | 7.7% | 11.2% | 98% |

About 11% of the stored OI sits at the two outermost strikes a side, but only 3.5-7.7% of the gamma does: far-OTM
options carry little gamma. The missing far-OTM gamma is a small, mostly put-side bias that matters most 3+ days from
expiry. The flip is often outside the ±5% grid on the thin indices (MIDCPNIFTY 65% found).

## 2. Describe (Step 2; design days only: 16 Jan-17 Dec 2023 and 15 Apr 2025-12 Oct 2026)

248 comparisons, BH q < 0.10 across all of them. Full table: `research/hunt/r28/results/describe.md`.

| question | answer |
|---|---|
| Q1 negative GEX → bigger rest-of-day range / volatility | **Yes, all 5 indices** at 10:00 (q 0.000-0.025 except NIFTY, q 0.17 at 10:00 but 0.007 at 09:20). Bigger by 10-60%; holds in 5-6 of 6 VIX / DTE strata. The "all expiries" GEX gives the same. S3 (bigger gross gamma) → smaller range on NIFTY, BANKNIFTY, MIDCPNIFTY. S2 (customer flow) mostly not. |
| Q1 trend vs chop (trend ratio) | Weak: FINNIFTY and MIDCPNIFTY only (q 0.07-0.09); NIFTY, BANKNIFTY, SENSEX no. |
| Q2 ORB breaks run more under negative GEX | MIDCPNIFTY only (31% vs 21% run, q 0.07); FINNIFTY 28% vs 20% (q 0.13); others no. Failed breaks: no difference anywhere. |
| Q3 expiry pin at the biggest-gamma strike | **No.** The 15:29 close is not nearer the biggest-gamma strike than the 13:45 ATM strike; SENSEX and NIFTY significantly FARTHER (SENSEX 0.29% vs 0.16%, 107 expiries). |
| Q4 spot crossing the flip → acceleration | Statistically yes (q < 0.01 on 4 indices) but tiny: -0.1 to +1.6 basis points over 15-30 minutes vs the same minute's average. Part of it is mechanical (crossings happen when price is already moving). Not tradeable after costs. |
| Q5 hero blasts (10× before 15:05) more under negative GEX at 13:45 | **No.** NIFTY 7.5% vs 7.0% (40 / 86 expiries), BANKNIFTY 8.3% vs 5.6% (12 / 36), SENSEX 2.4% vs 10.6% (41 / 66: the opposite). |
| Q6 day-end GEX → next morning gap | BANKNIFTY only: bottom-tercile GEX 0.27% vs top 0.22% |gap| (q 0.07). Others no. |

## 3. The 10 filters (Step 3)

Each filter is a SKIP rule on its arm's unfiltered trades, rebuilt on the lake with that study's own code, fills and
charges:

- ORB = R20 V0 via R22; Range Fade = R17 signals with R22 exits; both BANKNIFTY + NIFTY.
- Liquidity 15+5 BANKNIFTY = R19b's engine walk.
- Hero H0 and lottery L3 = R25.
- Night R3 = R27's rebuild (branch `claude/r27-night-smart-exit`, commit `ec0f380`, used as a working copy, not committed here). It gives 869 trades, R27's own count.

GEX is the arm's index, nearest expiry, read no later than the decision. Twins skip the same number of index-days at
random (2,000). "change" = filtered minus unfiltered P&L per session.

**Periods:**
- Family A (ORB, Range Fade, Liquidity, Night): holdout = Aug 2021 - Dec 2022; design = 16 Jan 2023 - 12 Oct 2026.
- Family H (Hero, lottery): holdout = Jan 2024 - Mar 2025; design = the rest.

Neither block was ever a locked holdout for that family (inventory in PREREG.md). The plain arms' results on these
blocks had been seen by earlier studies; a GEX filter had never been run on them.

### Design (921 sessions family A; 202 / 321 expiries family H) and the joined BH

| id | filter | trades base → kept | Rs/day base → kept | change | twin p | q R28 | q joined (m 162) | years better | big winners cut | pass |
|---|---|---|---|---|---|---|---|---|---|---|
| G1 | ORB: keep S1 < 0 at 10:00 | 1,499 → 425 | -130 → -57 | +73 | 0.94 | 0.99 | 1.00 | 3/4 | 55 | no |
| G2 | ORB: skip norm > +1 | 1,499 → 968 | -130 → -101 | +30 | 0.88 | 0.99 | 1.00 | 3/4 | 29 | no |
| G3 | ORB: keep S2 < 0 | 1,499 → 513 | -130 → -47 | +83 | 0.57 | 0.95 | 1.00 | 4/4 | 53 | no |
| G4 | ORB: keep spot below the flip | 1,499 → 468 | -130 → -71 | +59 | 0.99 | 0.99 | 1.00 | 3/4 | 52 | no |
| G5 | Range Fade: keep S1 > 0 | 2,251 → 1,597 | -358 → -254 | +104 | 0.46 | 0.95 | 1.00 | 4/4 | 42 | no |
| G6 | Range Fade: skip norm < -1 | 2,251 → 2,094 | -358 → -322 | +36 | 0.12 | 0.60 | 1.00 | 4/4 | 11 | no |
| G7 | Liquidity BN: skip norm > +1 | 799 → 521 | +194 → +200 | +6 | 0.27 | 0.91 | 1.00 | 3/4 | 10 | no |
| G8 | Hero H0: keep S1 < 0 | 32 → 13 | -322 → -138 | +184 | 0.49 | 0.95 | 1.00 | 3/3 | 1 | no |
| G9 | Lottery L3: keep S1 < 0 at 13:45 | 736 → 203 | -703 → -208 | +496 | 0.67 | 0.96 | 1.00 | 3/3 | 28 | no |
| G10 | Night R3: keep S1 < 0 at 15:15 | 632 → 173 | +206 → +119 | -87 | 0.08 | 0.60 | 1.00 | 1/4 | 15 | no |

- Every positive change on ORB, Range Fade, Hero and the lottery is the "skip a losing arm" effect. The random twins
  skip the same number of days and gain just as much on average: +25 to +506 Rs a day.
- On the two winning arms the filter either adds a little that does not survive the multiple-testing count or the
  holdout (Liquidity) or cuts profit (Night).
- Joined family, m = 162: R28's 10, R9's 68 gamma tests (the 6 printed p-values, the rest entered as 1.0), R22's
  58, R25's 18 and R27's 8 recorded holdout p-values.

### Holdout (read once, 14 Oct 2026)

| id | period | trades base → kept | Rs/day base → kept | change | win % base → kept | worst day kept | max DD base → kept | twin p | passed |
|---|---|---|---|---|---|---|---|---|---|
| G1 | Aug 2021-Dec 2022, 351 sessions | 533 → 50 | -182 → -16 | +166 | 32 → 38 | -2,152 | -67,940 → -8,874 | 0.47 | no |
| G2 | same | 533 → 308 | -182 → -88 | +94 | 32 → 36 | -2,193 | -67,940 → -32,517 | 0.19 | no |
| G3 | same | 533 → 203 | -182 → -113 | +69 | 32 → 30 | -2,182 | -67,940 → -41,404 | 0.99 | no |
| G4 | same | 533 → 52 | -182 → -15 | +167 | 32 → 39 | -2,152 | -67,940 → -8,874 | 0.41 | no |
| G5 | same | 809 → 729 | -278 → -233 | +45 | 29 → 29 | -3,198 | -97,726 → -81,915 | 0.09 | no (failed design) |
| G6 | same | 809 → 809 | -278 → -278 | 0 | 29 → 29 | -3,198 | same | - | no |
| G7 | 348 sessions | 238 → 143 | -64 → -81 | -17 | 29 → 27 | -4,504 | -36,836 → -36,502 | 0.86 | no |
| G8 | Jan 2024-Mar 2025, 65 expiries | 16 → 7 | +1,796 → +610 | -1,186 | 19 → 14 | -3,352 | -14,876 → -9,388 | 0.42 | no |
| G9 | 166 expiries | 237 → 67 | -333 → -6 | +327 | 4 → 6 | -1,002 | -55,233 → -14,965 | 0.14 | no |
| G10 | 351 sessions | 224 → 28 | +249 → -18 | -268 | 55 → 50 | -8,852 | -35,137 → -12,800 | 0.86 | no |

Per-year walk-forward rows, average win and loss, % green days and missing-GEX counts for every filter are in
`results/design.md` and `results/holdout.md`.

## Honest notes

- **Correction after the review (14 Oct 2026, after the holdout read): G7's twin p.**
  - The twins skipped whole index-days, but Liquidity reads GEX per trade, so G7 skipped only some trades of a day (56
    days). The p-value therefore compared the twins with the wrong number (-Rs 52 a day instead of the real +Rs 6).
  - `study.py` now uses the real change, and twins skip single trades when a filter skips part of a day.
  - `study.py correct` recomputed every filter's twin p in design and in the already-read holdout. No new period was
    read and no rule changed: `results/correction.json`.
  - Only G7 moved. Design p 0.274 → 0.017; q within R28 0.17, joined 0.81. Holdout p 0.86 → 0.85, change -Rs 17 a day.
    It still fails gates 2 and 5. The original `design.json` / `holdout.json` are kept as written.
- **Deviation from PREREG: stale contracts.** PREREG says a contract whose last trade is over 30 minutes old (or that
  has not traded today) is left out. The code sets its price to NaN, but then gives it the nearest strike's IV, so it
  still counts with its OI. That is the same fallback PREREG gives for a contract with no IV.
  - The effect: such contracts (mostly far OTM, little gamma) are included rather than dropped.
  - It applies to both the brain features and the study. It was found after the holdout read, so it was not changed:
    changing it would need a new holdout.
- **Gate 6 (2× spread) defaults to a pass when the stressed run is missing** (Range Fade, Liquidity). No filter reached
  gate 6, so this changed nothing.

- **Count of tries:**
  - 1 feature;
  - 248 descriptive comparisons (not used to choose anything: the filters were fixed in Part A, before Step 2);
  - 10 filters, each run once on design, on 3-4 walk-forward folds and on its holdout.
  - No other variant was run.
- **2× spread:** this stress was available for ORB, Hero and the lottery (and 1.5× for Night). Range Fade's stressed
  re-run did not line up trade-for-trade with the base run, so it shows "-". Liquidity's code has no spread switch.
  None of this changes a verdict: every filter failed before gate 6.
- **Lake gaps in the arms:**
  - ORB and Range Fade trade the nearest expiry AFTER the day. The lake has only the expiring contract on expiry
    days, so those days have no ORB / Fade trade.
  - BANKNIFTY's lot after 31 Dec 2025 (30) is missing from the older lot tables used by R17 / R19b (they use 35).
    That changes only rupee sizes.
- **Customer-net (S2) sees only today's flow.** It is 0 at the open, and a contract entering the ATM±10 window midday
  starts from its first seen OI.
- **The sign convention cannot be settled with public data** (R9). We report S1 / S2 / S3 rather than pretend one is
  right.

## Reproduce

```bash
rclone copy gdrive:IraAlgo-data-lake ~/.cache/iraalgo/lake --include "index_1m/**" \
  --include "options_rolling_1m/opt_{NIFTY,BANKNIFTY,FINNIFTY,MIDCPNIFTY,SENSEX}_{WEEK,MONTH}_*.parquet"
python3 research/hunt/r28/r28gex.py build            # per-minute GEX tables (~1 h on 4 cores), or copy derived/r28_gex/
python3 research/hunt/r28/r28arms.py orb|liq|hero|night   # hero/night need R25 / R27 caches (flat lake: IRAALGO_LAKE)
python3 research/hunt/r28/describe.py
python3 research/hunt/r28/study.py design             # the holdout was read once: study.py holdout now refuses
python3 research/hunt/r28/entries.py
python -m pytest research/brain/tests -q research/hunt/r28/test_r28.py
```
