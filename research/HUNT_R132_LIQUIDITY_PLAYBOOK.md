# R132: the liquidity playbook from Boss's two PDFs: what we were missing, fixed, and tested

29 Oct 2026. Research only: nothing in the app changes, nothing trades. Boss sent two PDFs (*"Liquidity: The Fuel of
Market"*, 13 pages; *"Market Liquidity"*, 12 pages) and asked: *"Understand and deep study the files, and find out what
are we missing, or what are we doing wrong, and resolve it."*

## The answer, first

1. **We were missing four things the PDFs teach, and one thing we did was wrong:**
   - **Missing:** sweeps of previous-week highs / lows and round numbers.
   - **Missing:** "sudden wick + reclaim" (telling a sweep from a run).
   - **Missing:** a target at the opposite liquidity.
   - **Missing:** volume on the sweep.
   - **Wrong:** R130's target was the nearest small swing, a median 0.44 of the stop's distance, too close for an
     option to pay.

   All of it is now built and tested (R132, 8 versions, pre-registered).
2. **With every gap fixed, it still loses money,** after real charges and slippage, on Aug 2021 - Mar 2026 (each year
   out of sample) and in the latest months (Apr - Oct 2026). On untouched NIFTY 2017-2020 minutes it stays below what an
   option needs. Win rates are 21-40%. Nothing comes near Boss's 65% rule.
3. **What the PDFs got right on the data:** a reclaimed sweep (a wick through a level that comes straight back) does
   reverse more often than a level that is broken and held (a "run"): 56.0% vs 49.4% (10,410 vs 4,161 cases,
   p < 0.001). The sweep / run idea is real.
4. **What the PDFs got wrong on the data:**
   - **"Wait for the BOS / CHoCH; entering early is mistake #1":** waiting lost more. On the reclaim: -Rs 116 a trade
     (4,836 trades); waiting for the BOS: -Rs 275 a trade (595).
   - **"Round numbers are magnets":** sweeps of round numbers reversed less than sweeps of fake half-way levels (53.9%
     vs 57.8%, p < 0.001).
   - **"Sweeps are followed by structure breaks":** only 15% were, within an hour (1,578 of 10,732).
   - **Confluence:** stacking it (volume, session open, HTF) left 16 trades in 4.5 years, and they lost.
5. **Why it cannot pay:** the edge on the index is about 0.5-3 index points a trade, and an ATM option round trip costs
   about 7 points of index move (charges, spread, decay). The same wall as R89, R90, R127 and R131.

**Nothing passes. Nothing goes on paper.**

## What the PDFs teach, against what we did

| the PDFs say | what we had done | in R132 |
|---|---|---|
| Liquidity sits at external levels: previous highs / lows, equal highs / lows, round numbers, range edges, trendlines | R130 swept the latest small 3-bar swing (internal). Amendment 2 added yesterday's low, the opening range and equal lows. H35 used round numbers and the weekly range as bounce / break levels (0 of 408) | **added as sweep levels:** yesterday's high / low, the opening range, **the previous week's high / low**, equal highs / lows, **round numbers** (100 NIFTY / FINNIFTY, 500 BANKNIFTY / SENSEX, 50 MIDCPNIFTY) |
| A sweep is a **sudden** wick beyond a key level and a **fast reversal back**. A break that holds is a **run** (continuation) | any wick 1 tick beyond counted (V6 asked for a close back) | **fixed:** a wick ≥ 0.02% beyond, a bar range ≥ 1.2 × the recent median, and a wick back across the level within 3 bars |
| Wait for the BOS / CHoCH after the sweep | done (MSS) | kept (P1), and "too early" tested as P7 |
| Stops beyond the liquidity pool | R130's stop was at the FVG's edge | **fixed:** 0.02% beyond the sweep's extreme |
| Target the opposite liquidity | the nearest small swing / equal highs, too close | **fixed:** the nearest fresh opposite level at least 1R away (yesterday's high, the opening range, last week's high, equal highs, a round number), else 2R |
| Volume rises on a real sweep | never (the index has no volume) | **added:** the ATM options' volume in the sweep ≥ 1.5 × normal (P3) |
| Session opens; higher-timeframe liquidity | morning and HTF tested in R130 | kept as P4 / P5 |
| OBs / FVGs as confluence | R62b (OB), R130 (FVG) | the FVG retrace (P2) |
| Order book / order flow | no depth data exists in the lake | **not testable** (stated) |
| Trendline liquidity | not tested | **left out:** a trendline needs a subjective choice of touches, and the PDFs give no rule |

The first PDF's last page is an advert (a link). It carries no trading content.

## Results

Design, Aug 2021 to 15 Mar 2026 (1,144 sessions; nothing fitted, each year out of sample):

| version | trades | win rate (vs 65%) | avg win / avg loss | avg net per trade | total (1 lot) | max drawdown | index pts | vs random (p / q) |
|---|---|---|---|---|---|---|---|---|
| **P1, the playbook** | 595 | 38% (below) | Rs 3,737 / -Rs 2,698 | **-Rs 275** | -Rs 1,63,813 | -Rs 2,12,806 | +0.7 | 1.00 / 1.00 |
| P2, FVG retrace entry | 214 | 36% (below) | Rs 3,329 / -Rs 2,392 | -Rs 307 | -Rs 65,738 | -Rs 90,784 | +2.1 | 0.80 / 1.00 |
| P3, + volume | 244 | 36% (below) | Rs 3,247 / -Rs 2,076 | -Rs 156 | -Rs 38,157 | -Rs 71,554 | -1.5 | 1.00 / 1.00 |
| P4, + session open | 145 | 40% (below) | Rs 4,124 / -Rs 3,007 | -Rs 155 | -Rs 22,426 | -Rs 52,886 | +11.6 | 0.99 / 1.00 |
| P5, + 60-min structure | 242 | 40% (below) | Rs 3,200 / -Rs 2,627 | -Rs 316 | -Rs 76,397 | -Rs 82,772 | +5.4 | 1.00 / 1.00 |
| P6, external only | 198 | 38% (below) | Rs 4,259 / -Rs 3,197 | -Rs 373 | -Rs 73,814 | -Rs 1,28,379 | -1.8 | 1.00 / 1.00 |
| **P7, "too early" (reclaim, no BOS)** | 4,836 | 24% (below) | Rs 2,585 / -Rs 972 | **-Rs 116** | -Rs 5,59,582 | -Rs 6,90,404 | +0.5 | **0.005 / 0.04** |
| P8, all the confluence | 16 | 38% (below) | Rs 3,447 / -Rs 3,747 | -Rs 1,049 | -Rs 16,784 | -Rs 20,499 | -33.1 | 0.93 / 1.00 |

Holdout, 1 Apr to 19 Oct 2026, read once:

| version | trades | win rate | avg net per trade | total (1 lot) | vs random (p) |
|---|---|---|---|---|---|
| P1 | 57 | 30% | -Rs 340 | -Rs 19,359 | 0.90 |
| P2 | 24 | 38% | -Rs 498 | -Rs 11,954 | 0.69 |
| P3 | 21 | 33% | -Rs 106 | -Rs 2,217 | 0.91 |
| P4 | 12 | 17% | -Rs 1,266 | -Rs 15,191 | 0.32 |
| P5 | 22 | 32% | -Rs 626 | -Rs 13,776 | 0.81 |
| P6 | 15 | 47% | +Rs 756 | +Rs 11,346 | 0.12 |
| P7 | 589 | 21% | -Rs 153 | -Rs 90,041 | 0.005 |
| P8 | 1 | 0% | -Rs 4,904 | -Rs 4,904 | 0.79 |

- **P6's holdout profit (15 trades) is noise.** It lost Rs 373 a trade on 198 design trades. Its +Rs 11,346 is
  MIDCPNIFTY's 4 trades (+Rs 19,494); NIFTY lost Rs 13,278 on 4.
- **P7 beats random entries but loses money.** Its stops are tight (average loss -Rs 972), so random entries with the
  same tight stops lose even more. But it wins only 21-24% of the time and still loses about Rs 150 a trade.

NIFTY 2017-2020 (925 sessions, index points; read before only by R131's trend rule, never for liquidity):

| version | trades | avg index points | needed (2021-26 NIFTY P1 estimate) | index win rate |
|---|---|---|---|---|
| P1 | 104 | +3.3 | 7.1 | 50% |
| P2 | 45 | +2.5 | 7.1 | 42% |
| P4 | 15 | +0.3 | 7.1 | 33% |
| P5 | 44 | -1.6 | 7.1 | 41% |
| P6 | 23 | +3.9 | 7.1 | 57% |
| P7 | 1,020 | -0.3 | 7.1 | 21% |

**Gates.** Every version fails:
- design net at 1x and 2x;
- at most 3 of 4 folds (P3), the rest 1-2;
- q (all but P7);
- trade count (all but P1 and P7);
- NIFTY 2017-2020 below break-even;
- the holdout's 65% rule;
- the account stop (P1 design plus holdout: drawdown -Rs 2,13,428, worst month -Rs 45,592).

## The PDFs' claims, checked (index wicks, design)

| claim | test | result |
|---|---|---|
| "Sweeps are followed by structure breaks" | the share of reclaimed sudden sweeps with a BOS (a wick above the latest swing high) within 12 bars, against every 5-minute bar from 09:30 | **No.** 14.7% of sweeps (10,732) vs 47.5% of ordinary bars (801,062). After a sweep low, the swing high is far away, so a break is harder, not easier |
| "A sweep reverses; a run continues" | a 1:1 race from the reclaim (d = the sweep's depth) after a reclaimed sweep, against the same race after a break with no reclaim within 3 bars | **Yes.** Sweeps went the reversal way first 56.0% (10,410), runs 49.4% (4,161), p < 0.001 |
| "Round numbers are magnets" | the same race for sweeps of round numbers against sweeps of half-way placebo levels (50 / 250 / 25) | **No.** Round 53.9% (4,759) vs placebo 57.8% (5,311): round numbers did worse, p 0.0001 |
| "Entering too early is a mistake" | P7 (enter on the reclaim) against P1 (wait for the BOS) | **No.** P7 lost less a trade (-Rs 116 vs -Rs 275) and beat random entries; P1 did not |
| "Confluence makes it high probability" | P8 = volume + session open + HTF | **No.** 16 trades in 4.5 years, 38% won, -Rs 1,049 a trade |

## What we were doing wrong, and what that changes

- **The target was the real flaw in R130's version.** It sat about half the stop's distance away, so even when the
  index hit it, costs ate the gain. R132 targets the opposite liquidity at least 1R away. The average win rose from
  about Rs 700-850 (R130) to Rs 3,200-4,300 a trade, but the win rate fell to 36-40%. The index edge underneath stays
  near zero, so it is still a loss.
- **Telling sweeps from runs is right, but the edge is small.** The 56% vs 49% difference is real (a few index
  points). An ATM option needs about 7 points just to break even.
- **Nothing in the PDFs can be fixed on the strategy side to beat that cost.** The two options left are the trade
  instrument (index futures cost far less than an option round trip) and the order book (no data). Both are new
  questions for Boss, not tuning.

## How it was done

- **Pre-registration:** `research/hunt/r132_liquidity_playbook/PREREG.md`, commit `b7f9cb8`, before any number.
  - Code and tests: `2f9af2c`, before any data run.
  - Design results and claims: `ccfb501`, before the NIFTY 2017-2020 and holdout reads.
- **Code and 10 tests:** `research/hunt/r132_liquidity_playbook/` (`r132.py`, `test_r132.py`). The tests cover:
  - a hand-built two-day session (sweep of the opening-range low, reclaim, BOS, target at yesterday's high), with its
    mirror sell;
  - a sweep that is not sudden, and a run that never reclaims;
  - the external, session and volume filters;
  - the reclaim entry being blocked by an earlier sell;
  - the one-position merge;
  - a look-ahead guard.
- **The sweep, point in time:** the first minute that breaches the level, the running low, then the first minute
  whose high is back above the level, within the bar and the next 2. The sweep's extreme is the lowest low between the
  breach and the reclaim. "Sudden" and the volume test use only the minutes up to the reclaim. (The pre-registration
  says "before any lower low than the sweep's lowest"; the code reads that point in time: a lower low before the
  reclaim deepens the sweep.)
- **The option, charges and twins:** as R130 and R131. 200 same-side random-time twins with the same stop and target
  distances.

## Honest notes

1. **The holdout run crashed once, before any number was written or shown.** A version with a single trade (P8) hit a
   pandas quirk in the shared statistics code. The bug was fixed (`r130.py` stats by year), the crashed lock record was
   kept (`HOLDOUT_READ_crash1.json` in the cache), and the holdout was run once more. No R130 or R131 result changes
   with the fix.
2. **Apr - Oct 2026 was read before by R123, R130 and R131 for other rules.** It is a weaker test than the design
   years and the 2017-2020 check.
3. **The container was reset during this study.** The lake was copied again from Drive (the same 19 Oct files) before
   any R132 data run.
