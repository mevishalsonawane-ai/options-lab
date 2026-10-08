# HUNT h34: did "points" mean premium points, and does a premium-POINT exit change any verdict?

Written 8 Oct 2026. Option BUYING only. 1 lot. Code: `research/hunt/h34/`. Logs and tables: `scratchpad/hunt/h34/`.
Plan written before any P&L: `research/hunt/h34/PREREG.md`. Holdout (1 Oct 2025 onward) locked and read once.

## Verdict (plain language)

**NO. Exits in premium points do not change any verdict. Nothing passes. Rs 5,000/day: NO.**

- Most earlier studies did use % of premium, index points or each arm's own exits. Boss's worry was fair.
  But premium points were also tested before: SCALP17, the 30/60 and 40/80 point rules, and the ORB arms' point ladders.
- Now every strategy in the catalog has been re-run with one fixed menu in premium points:
  - targets +15 / +20 / +25 / +30 points;
  - stops -10 / -15 / -20 / -30 points;
  - time stops 15 / 30 / 60 minutes, or none (out at 15:10);
  - the point profit lock on or off (+10 moves the stop to breakeven, +20 locks +10, +30 locks +20).
  That is 128 exits. They were applied to 66 strategies, ATM and 1-ITM, nearest expiry, on all 5 indices.
- **29,696 point-exit variants were tested. None survives the corrections.**
  - The best t-stat was 2.2. Hansen SPA p = 0.99. BH on the t-test passes 0 variants.
- **Random entries lose with every one of the 128 exits, on every index, in every hour.** The loss is Rs 82-260 a trade.
  - Gross (before any cost), random entries are about Rs -1 to -15 a trade even with the best exit.
  - So the point exit does not create an edge. It only decides how fast the costs eat you.
- **For the one entry that works, points are much WORSE.**
  - Liquidity 15+5 (BANKNIFTY + FINNIFTY) with its own exits made +Rs 98,100 net before the holdout. It was positive in 3 of 3 years.
  - With point exits, all 256 point variants lost money. The best lost -Rs 62,485.
  - In the holdout, its own exits made **+Rs 42,204 (+Rs 170/day)**. The point exit that the walk-forward picked made **-Rs 57,040 (-Rs 229/day)**.
  - Why: Liquidity earns on a few breaks that run far. A +15 to +30 point target sells those winners too early.
    BANKNIFTY options cost about Rs 330-390, so +30 points is only about 8%.
- **Points "lose less" than many original exits, but they still lose.**
  - Total walk-forward over the catalog: -Rs 66.8 lakh with point exits, against -Rs 193.5 lakh with the default
    original exits.
  - This is damage control: short time stops (15 min) and tight stops (-10) cut the bleeding. It is not an edge.
- **Only 6 of 66 strategies had a positive point-exit walk-forward.** All 6 are tiny and all fail the tests.
  - The best, Hero grid entries bought ATM on expiry afternoons, made +Rs 23/day.
  - At +Rs 23/day you would need about 219 lots for Rs 5,000/day. That is not possible.
- **The same 20 points means very different things on different indices (see the tables below).**
  - On NIFTY ATM (about Rs 92), 20 points is 22% of the premium and Rs 1,300 a lot.
  - On BANKNIFTY ATM (about Rs 331), 20 points is 6% and Rs 600 a lot.
  - Cheap options (under Rs 100) lose the least per trade (about Rs -63 net). Expensive ones (Rs 400+) lose the most (about Rs -164).
  - No premium band is profitable net.
- **Wicks, not closes.** Every point target and stop was checked minute by minute on the option's own 1-minute HIGH
  and LOW, with resting orders. Only the straddle-type strategies were checked on closes (an engine rule for 2-leg
  positions). The stop was assumed first when both were hit in the same minute.
  - That happened in only **0.54% of trades** (53,452 of 9.87 million random trade-exits).
  - Scoring those trades as wins instead would add only **Rs 2.2 a trade** (0.3-2.0% by index; SENSEX is the highest).
  - So the cautious rule for same-minute hits does not hide an edge.

**What to do:** keep Liquidity 15+5 with its own exits. Do not switch it to a +20/-15 point bracket. Do not trade any
catalog strategy with point exits.

## 1. Audit: which exit units did each earlier study use?

The units are: premium % (a percent of the option price), premium POINTS (Rs of option price), index points (or an
ATR/R multiple of the index), time only, and arm exits (the strategy's own structural exits).

| study | exit units used (exact) | premium points tested? |
|---|---|---|
| OBUY_SEARCH | 52 exits: premium % stop 15/25/35% x % target 30/50/100%/none x time 30/60/120 min/15:10 (48), plus a premium-POINT set: 30-pt stop / +60 target / point ladder (+15 BE, +30 lock 15, +45 lock 30) x the 4 time exits (4) | yes, 4 of 52 (30/60 only) |
| OBUY_GA | mostly the signal's INDEX levels (index stop / target, 1/2/3R of index risk); premium % stop -30%; Pine % trail; % trailing stop; OR-06 % (SL 20/30% x TP 40/100%); TD-01 INDEX points (15/20, 10/20, 20/30, 30/60); TD-03 % (SL -/30/50%, TP 100%); time 15:15/15:25 | no |
| OBUY_GB | mostly arm/structural (opposite flip, cross, red HA candle, index swing stop, R targets) and time 15:10/15:15; premium % (-30%, 20/40, 25/50, 30/60; TI-04 % targets 5-20%); Pine % trail. POINTS only in TI-02 (-40/+80), MR-03 (-40/+80, -20/+40), MR-06 (-40/+30, -40/+60, -20/+40, -40/+60 + ladder) | yes, 3 of 22 strategies |
| OBUY_GC | premium % (stops 3.3-60%, targets 10-900%, % ladders), index R targets, % trails, time (15:15 / 15:20 / next-day exits for multi-day) | no |
| SCALP17 | premium POINTS: target 10/15/17/20/25 x stop 10/15/20/25/30 x time 15/30/60 min, 15:10 square-off; no lock | yes (all) |
| SWING_EXITS | multi-day. Options: the app's literal -30/+60 premium POINTS + point ladder, and the scaled -30%/+60% version; futures/stocks in R (ATR) multiples; Pine % trail | yes (30/60 only) |
| JARVIS_EXITS | 12 rules: C0 -30/+60 POINTS + ladder 60; X1 -40/+80 POINTS + ladder; P1-P6 % (15/30, 20/40, 25/50, with/without ladder); I1 -15% + index stop + 20-min time + 2R; T1 points + Pine % trail; T2 -20% + % trail; R1 tighter of 30 pts / 15% | yes (30/60, 40/80) |
| LIQUIDITY_EXITS_3060 | A = arm exits (-15% premium, index stop, 20-min time stop, next level, failed break, new level, 15:10) vs 12 alternatives in POINTS: 30/60, 40/80, 40/40 with ladders (keep / drop index + time stops) and arm + point ladder on 40/60/80 | yes (30-80 pt brackets) |
| HUNT_H1 | ML labels in premium % (+20/-10, +30/-15, +15/-15, +40/-20) with 30/60-min time; the chosen model used a 30-minute time exit only | no |
| HUNT_H5 | INDEX: one trailing stop at 0.6 x ATR14 from the best index close; time (no add in 60 min); 15:15 | no |
| HUNT_H12 | Liquidity arm exits unchanged | no |
| HUNT_H18 | arm exits (-15% premium, 20-min time stop unless +5%, INDEX stop 30/15/15/50/8 pts, 15:10) and the same + premium % target +30% | no |
| HUNT_H20 | ORB arms (BANKNIFTY): premium POINTS -40 / +40 (+80 Sweep), point ladder 25/50/75% of target; 6 alternatives all in POINTS: target 15; target 20 with +10 to BE; target 30 with +10 to BE and +20 to +10; tight +8/+15/+20/+30; 50% trail from +10; ladder + 30-min time exit | yes (all) |
| HUNT_H21 | ORB arms: -40/+40 POINTS with the fixed resting point lock (+10 BE, +20 +10, +30 +20); premium-ATR ladders (points scaled by the option's own ATR); ATR trails | yes (all) |
| HUNT_H27 | E1 arm (-15% + 20-min time), E2 -15%/+30%, E3 -15% stop + ladder to +40% (R = 40% of entry), E4-E6 -30% stop + time exits 10:15 / 11:15 / 15:10 | no |
| HUNT_H28 | E1 arm (-15% + 20-min), E2 -15%/+30%, E3 -30%/+60%, E4 -15% + % ladder (R = 15%), E5 60-min time exit with no stop, E6 hold to 15:10 | no |
| HUNT_H29 | E1 arm, E2 -15%/+30% + 20-min time, E3 -15% + % ladder (R = 15%), E4 30-min time exit + -30% catastrophe stop | no |

**Short answer to Boss:** 6 studies tested premium points: SCALP17, H20, H21, LIQUIDITY_EXITS_3060, JARVIS_EXITS and
SWING_EXITS. Three more had a few point exits: OBUY_SEARCH, the 3 GB strategies, and the catalog's Solo C0 (30/60) and
ORB grid (40/40). The other 8 used % of premium, index levels, time or arm exits only. **This study closes that gap.**
One point menu was run on every catalog entry, Liquidity and h18.

## 2. What was run (fixed in PREREG.md before any result)

- **Entries (not re-optimised):**
  - all 64 registry strategies, plus Liquidity 15+5 on NIFTY / SENSEX / MIDCPNIFTY (`liq_ext`) and h18's
    60-minute-break rule (`h18_r60`);
  - for each: the DEFAULT (first-declared) parameters and the IS-best parameters from the earlier catalog runs
    (pre-holdout net only).
- **Strikes:** ATM and 1-ITM, nearest expiry. Multi-day entries were made same-day. Three overnight rules (BTST,
  PCR contrarian, max-OI) enter after 15:09 and so have no point-exit trades (n/a).
- **Exits:** the 128-exit menu above. Pure points plus time: the signal's own index stop and target were switched off.
- **Costs:** the app's fills (±5 bps, stops -10 bps), charges at the rates in force on each date, and a real
  half-spread on entry and exit.
  - Half-spreads: BANKNIFTY 0.16%, NIFTY 0.16%, MIDCPNIFTY 0.21%, FINNIFTY 0.42%, SENSEX 0.20% (assumed).
  - GROSS = mid-price points x quantity, with no costs at all.
- **Random baseline:**
  - 36 random entries per index-day (random minute 09:16-15:04, coin-flip side), ATM and 1-ITM, all 128 exits.
  - Each strategy trade is compared with random entries on the same index, same day and same hour, with the same exit.
- **Variant count (honest):**
  - 29,696 point-menu variants (66 strategies x up to 2 entry sets x 2 strikes x 128 exits);
  - 123 original-exit reference rows;
  - 1,280 random-baseline configurations (5 indices x 2 strikes x 128);
  - 1,280 tie-check configurations.
  - All 29,696 menu variants enter BH and SPA.

## 3. Results (before the holdout, NET at 1 lot)

**Multiple testing**

| test | result |
|---|---|
| G1: NET > 0 with >= 30 trades | 1,626 of 29,696 (5.5%) |
| G2: t-test of daily NET > 0, BH q <= 0.10 | **0** (max t = 2.20) |
| G3: beats matched random entries, BH q <= 0.10 | 688 (entries better than random, but not better than costs) |
| G1 + G2 + G3 together | **0** |
| G4: Hansen SPA / White RC over all 29,696 | **SPA p = 0.99, RC p = 0.99: FAIL** |
| G5: strategy walk-forward > 0 and positive in most years | 3 of 66 (Hero grid, Hero zero, EXP-05 scalp 1/day; all tiny, all fail G2 and G4) |
| PASS (all gates) | **0**, so nothing went to the holdout as a candidate |

Across all menu variants, GROSS is positive in 52% of them (median Rs +4 a trade). NET is positive in 5.5% (median
Rs -98 a trade). **The costs, about Rs 100 a trade, are the whole story.**

**Did point exits change the verdict? The strategies with a positive point walk-forward, and the important ones.**

WF = anchored walk-forward by year. Each year it trades the point variant (entry set, strike, exit) that was best on
all earlier years. Test years are 2022 (or the strategy's 3rd year) to Sep 2025. "Original" = the strategy's own
exits over the same test years: default variant / IS-best variant.

| strategy | point menu: share of variants NET > 0 | WF NET (gross) | WF years + | WF Rs/day | WF max DD | lots for Rs 5k/day | original exits: default / IS-best | verdict changed? |
|---|---|---|---|---|---|---|---|---|
| Hero grid (expiry day, ATM in place of Rs 1-5 OTM) | 50% | +21,117 (+37,854) | 3/4 | +23 | -26,806 | ~219 | -255,911 / +486,280 (one-day jackpot) | no: fails BH (t 2.2) and SPA |
| Hero zero (fixed) | 88% | +3,662 (+5,566) | 2/3 | +5 | -15,628 | ~930 | +231,915 (one day) | no |
| EXP-05 +10% scalp, 1/day (expiry) | 63% | +2,635 (+28,534) | 3/4 | +3 | -22,649 | ~1,760 | -21,926 / -15,501 | no: tiny, fails BH/SPA |
| OR-05 open = low/high | 16% | +2,622 (+19,217) | 2/4 | +3 | -7,172 | ~1,770 | -45,403 / -327 | no |
| OR-08 gap fill | 2% | +1,440 (+34,362) | 2/4 | +2 | -35,749 | ~3,200 | -10,435 / -479 | no |
| TI-04 EMA9 x TWAP | 1% | +982 (+69,793) | 2/4 | +1 | -37,258 | ~4,700 | -229,361 / -24,972 | no |
| **Liquidity 15+5 BN+FIN (app)** | **0%** | **-66,284 (+60,619)** | **0/3** | **-97** | -88,429 | - | **+120,442 (3/3 yrs)** | **YES, worse: points destroy it** |
| Liquidity rules on NIFTY/SENSEX/MIDCP | 4% | -18,949 (+128,061) | 1/4 | -20 | -66,882 | - | -3,167 | no (both lose) |
| h18 60-min break | 0% | -334,067 (+178,888) | 0/4 | -360 | -337,486 | - | -326,158 | no (both lose) |
| Camarilla R3/S3 fade (MR-04) | 0% | -59,809 (+59,655) | 1/4 | -65 | - | - | +75,657 / +103,839 | worse with points |
| Max pain (EXP-04) | 48% | -6,506 (-2,326) | 3/4 | -7 | - | - | +19,287 / +24,441 | worse with points |
| Gap and go (OR-07) | 0% | -31,001 (+49,576) | 1/4 | -33 | - | - | +14,601 / +31,274 | worse with points |
| Solo midday (app) | 0% | -18,141 (+28,601) | 1/4 | -20 | - | - | -13,012 | no (both lose) |
| ORB 15 (fixed) | 0% | -262,323 (+1,679) | 0/4 | -283 | - | - | -565,231 | no (both lose; points lose less) |
| 09:20 straddle (pair premium) | 0% | -554,711 (+5,749) | 0/4 | -598 | - | - | -1,200,779 | no (both lose; points lose less) |
| Random entries (control) | 0% | -295,664 (-56,476) | 0/4 | -319 | -296,356 | - | -338,275 | no |

The full 66-row table is in `scratchpad/hunt/h34/tables.md` (and `strategies.csv`).
- 60 of 66 strategies lose money with the point menu in the walk-forward.
- In 0 of 66 is the point version significant.
- The point exit flips a positive original result to a loss in 4 strategies (default exits): Liquidity, Camarilla,
  max pain and gap-and-go.
- Against the IS-best original exits, it flips 11 (also LV-05, EMA cross, gamma blast, VIX-ORB, POS-02, POS-04 and
  Solo grid). Those IS-best numbers are flattered by selection, though.
- Points beat the default original exits in 50 of 63 strategies with trades, mostly big losers
  (ORB, straddles, EMA cross, Heikin-Ashi, 5-EMA). Points lose less than the originals there:
  the 15-minute time stop limits the damage. They still lose Rs 80-600 a day.

**Random entries with the point menu** (per trade, 1 lot, all pre-holdout days)

| index | strike | trades per exit | best exit: NET/trade | median exit NET/trade | best GROSS/trade | exits with NET > 0 |
|---|---|---|---|---|---|---|
| NIFTY | ATM / 1-ITM | 46,015 each | T30/S30/t15/L: -91 / T30/S10/t15/L: -100 | -111 / -114 | -14 / -12 | 0 of 128 |
| BANKNIFTY | ATM / 1-ITM | 36,861 | T30/S10/t15/L: -102 / -106 | -114 / -119 | -6 / -3 | 0 |
| FINNIFTY | ATM / 1-ITM | ~30,000 | T25/S10/t15/L: -133 / -149 | -150 / -162 | -7 / -9 | 0 |
| SENSEX | ATM / 1-ITM | ~21,100 | T30/S10/t15/L: -82 / -84 | -91 / -95 | -4 / -1 | 0 |
| MIDCPNIFTY | ATM / 1-ITM | ~20,100 | T25/S15/t15: -136 / T30/S20/t15: -148 | -169 / -181 | -11 / -15 | 0 |

By time of day (mean over the 128 exits): 09:15 -135, 10:15 -132, 11:15 -131, 12:15 -129, 13:15 -128,
14:15 -124 Rs a trade NET. The gross is -25 to -30 in every hour. No hour helps.

Which exits do least damage (all strategies pooled, NET per trade):
- target: +30 is the least bad (-112), +15 the worst (-119);
- stop: -10 is the least bad (-111), -30 the worst (-122);
- time: 15 minutes is the least bad (-110), holding to 15:10 the worst (-122);
- lock: on (-113) is better than off (-119).
In short: small, fast losses. No setting turns positive.

## 4. Points vs premium: rupees per point and premium bands

| index | lot now | Rs per point per lot | Rs for +20 pts | mean ATM premium | mean 1-ITM premium | 20 pts as % of ATM | half-spread |
|---|---|---|---|---|---|---|---|
| NIFTY | 65 | 65 | 1,300 | 92 | 121 | 22% | 0.16% |
| BANKNIFTY | 30 | 30 | 600 | 331 | 385 | 6% | 0.16% |
| FINNIFTY | 60 | 60 | 1,200 | 150 | 178 | 13% | 0.42% |
| SENSEX | 20 | 20 | 400 | 332 | 387 | 6% | 0.20% |
| MIDCPNIFTY | 120 | 120 | 2,400 | 114 | 128 | 17% | 0.21% |

(Mean premiums are from random entries, Aug 2020 - Sep 2025, nearest expiry, expiry days included.)

| entry premium band | random: 20 pts as % of premium | random NET/trade, T20/S15/30 min | random GROSS/trade | random NET, best exit | all strategies' menu variants: GROSS / NET per trade |
|---|---|---|---|---|---|
| under Rs 100 (avg 64) | 31% | -92 | -20 | -77 | +12 / -63 |
| Rs 100-200 (avg 141) | 14% | -124 | -27 | -108 | -9 / -103 |
| Rs 200-400 (avg 289) | 7% | -154 | -30 | -136 | -22 / -129 |
| Rs 400+ (avg 575) | 3% | -173 | -28 | -156 | -13 / -164 |

- A +20-point target on a Rs 64 option is a big +31% move. On a Rs 575 option it is a tiny 3% move.
- But the costs grow with the premium: spread, STT and exchange charges all scale with price.
- Cheap options lose the least per trade. **No band is profitable net.**

## 5. Holdout (1 Oct 2025 - 6 Oct 2026, read once)

Nothing passed, so no candidate went to the holdout. Reference rows, as pre-registered:

| reference | trades | NET | GROSS | NET at 1.5x spread | NET Rs/day | gross Rs/day | lots for Rs 5k/day | max DD | months + |
|---|---|---|---|---|---|---|---|---|---|
| Liquidity 15+5 BN+FIN, its own exits | 416 | **+42,204** | +136,068 | +19,096 | **+170** | +546 | ~30 | -36,740 | 6/13 |
| Liquidity 15+5 BN+FIN, WF-picked point exit (1-ITM, +30 / -10, to 15:10) | 411 | **-57,040** | +37,968 | -79,605 | **-229** | +152 | - | -74,210 | 4/13 |

- Random entries in the holdout: every index, both strikes, all 128 exits lost money.
  - Mean NET per trade: NIFTY -125 / -133, SENSEX -115 / -123, BANKNIFTY -210 / -218, MIDCP -264 / -285,
    FINNIFTY -302 / -359.
  - Best single exit anywhere: about -103 a trade.
- The 30-lot figure for Liquidity ignores impact and capacity. h10 showed FINNIFTY carries about 3 lots.
  At Rs 1 lakh capital, the realistic figure stays h16's (about Rs 1,100/day net in year one). **Rs 5,000/day: NO.**

## 6. Caveats

- Point exits on straddle-type signals use the pair's combined premium, checked on minute closes. Those rows are not
  in the matched-random test.
- Catalog strategies keep the indices their signals trade (mostly NIFTY / BANKNIFTY / FINNIFTY / SENSEX). MIDCPNIFTY
  is covered only by Liquidity (`liq_ext`), h18 and the random baseline.
- The breakeven rung of the lock is breakeven after charges (the engine's rule), so "+10 to BE" sits a little above
  entry.
- The spread is one snapshot (h24). At 1.5x spread every result is worse; see the holdout table.
- GROSS backs out the ±bps fill slippage approximately (divides by 1.0005 / 0.9995 / 0.999).

## Files

- `research/hunt/h34/PREREG.md`: the plan, written before any P&L.
- `research/hunt/h34/select_is.py`: IS-best entry parameters from the earlier runs (pre-holdout).
- `research/hunt/h34/core.py`: the menu, costs and spread, the fast multi-exit runner, extra strategies.
- `research/hunt/h34/rand.py`: the random baseline (`--hold` for the one holdout look).
- `research/hunt/h34/run.py`: all strategies x menu + original references, pre-holdout, in chunks.
- `research/hunt/h34/analyse.py`: gates, walk-forward, tables.
- `research/hunt/h34/holdout.py`: the single holdout read.
- `research/hunt/h34/tie.py`: the same-minute target/stop count.
- Outputs in `scratchpad/hunt/h34/`: `tables.md`, `strategies.csv`, `menu_rows.parquet`, `ties.csv`, `holdout.json`,
  `res/*.pkl`, `rand_*_agg.parquet`, `*.log`.
- Reproduce: `select_is.py` → `rand.py <U>` (x5) → `run.py plan` → `run.py chunk 0..5` → `analyse.py` →
  `rand.py <U> --hold` (x5) → `holdout.py`. Run all heavy steps under flock. The rand cells were deleted to save disk;
  `rand.py` rebuilds them in about 15 minutes.
