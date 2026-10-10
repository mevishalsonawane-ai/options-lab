# HUNT h10: a realistic plan for Liquidity 15+5, with capacity-limited fills, capacity-based lots and loss limits

Code: `research/hunt/h10/`
- `PREREG.md`: the fill model, capacity, allocation, limits and choice rule, written before any P&L.
- `cap.py`: the capacity fill simulator and the loss-limit gate.
- `run.py`, run in stages:
  - `pre`: chooses using data before 1 Oct 2025.
  - `hold`: one holdout test.
  - `extra` / `ladder`: reporting on pre-holdout data only.

Packs come from `research/hunt/h7/build.py`. Caches and logs are in `scratchpad/hunt/h10/`.

Everything here is option BUYING. Entries and exits are the app's Liquidity 15+5 arm, unchanged (the h4/h7 port). At
1 lot with no impact the simulator reproduces h7's real net to the rupee: BN 144,334 / FIN 91,911 / MIDCP 102,311.

## Verdict (plain language)

**Rs 5,000/day fits within liquidity only because of BANKNIFTY. It is not dependable, and whether it survives at all
depends on how much our own orders move the price.**

1. **Liquidity collapsed when weekly expiries ended (Nov 2024).** Every contract here is now a monthly. The traded
   volume in the 5 minutes around an exit fell 10-50×. Capacity, defined as 15% of the 25th-percentile exit volume:

   | index | Dec 2024 to Sep 2025 | holdout |
   |---|---|---|
   | BANKNIFTY | 287 lots | 129 lots |
   | FINNIFTY | 3 lots | **0 lots** |
   | MIDCPNIFTY | 11 lots | 4 lots |

   **BANKNIFTY must carry almost all of the size.** FINNIFTY monthly 1-ITM options cannot take real size, and in the
   holdout FINNIFTY lost money at every size from 1 to 11 lots once impact was counted.
2. **The pre-registered plan** (flat lots: BN 26 / FIN 3 / MIDCP 11, with Rs 75k/day and Rs 2.5L/month stops) made:

   | period | net per day | gross per day |
   |---|---|---|
   | holdout | **Rs 6,075** | Rs 14,005 |
   | holdout, without the limits | Rs 4,830 | Rs 13,933 |
   | Dec 2024 to Sep 2025 (sized to Rs 5,000 without limits) | Rs 4,928 | Rs 11,676 |

   - Costs (spread, impact and charges) eat 55-65% of the gross.
   - The best 5 holdout days made **156%** of the holdout net.
   - P(losing month) is about 45%.
   - Max drawdown is Rs 8.8 lakh with the limits and Rs 13.2 lakh without.
3. **Impact is the deciding unknown.** Measured 5-minute option volatility is about 3.5-4%. That makes κ = 0.02 (the
   pre-registered value) a central-to-optimistic choice, and κ = 0.04 a plausible pessimistic one.
   - At κ = 0.04 the same plan makes **-Rs 546/day in the holdout** (with the limits) and +Rs 957/day before it.
   - At κ = 0.01 it makes Rs 8,885/day in the holdout.
4. **Loss limits are a coin flip, not a free lunch.** The profits come from a few huge days, which often follow a bad
   start, and the limits cut exactly those days off.
   - Before the holdout, both limits together turned Rs 4,928/day into **-Rs 1,098/day** and made the drawdown worse.
   - In the holdout they **helped**: Rs 4,830 became Rs 6,075/day, and the worst month went from -6.6 lakh to -2.8 lakh.
   - Treat them as protection against ruin, not as an edge.
5. **h7 sizing against flat lots, under the capacity model:**
   - Before the holdout, flat won on Sharpe in the Dec 2024 to Sep 2025 window, 1.17 against 1.08. Flat was chosen as
     pre-registered.
   - In the holdout h7 was much better: Rs 10,172 against Rs 4,830/day without limits, Sharpe 1.50 against 0.84.
   - The h7 adds sit mostly in BANKNIFTY, where liquidity exists. One period each way: **undecided**.
6. The entries are still real. Against random entries with the same exits, lots and capacity model, p = 0.0005 both
   before and in the holdout (holdout: Rs +1,850/trade against Rs -3,331 for random entries). But over the 40
   pre-registered variants in the 10-month monthly-only window, SPA p = 0.17: not significant against zero.

## Pre-registered model (PREREG.md; never changed)

The fill model:
- Participation cap = 15% of the contract's traded lots in the 5 minutes before the order. Entries and adds are
  clipped to the cap (minimum 1 lot), and the excess is dropped.
- Exits: the cap is filled at the arm's exit fill. The rest is worked over the following minutes at up to 15% of each
  minute's volume, at the minute's close. Anything left goes at 15:29.
- Every slice pays:
  - the 'real' fill (app bps plus a 1-4 tick spread);
  - square-root impact κ·√(q / 5-min volume), with κ = 0.02 and a 10% cap;
  - app charges for each order slice.
- GROSS means the same trades and quantities at bar prints, with no costs.

Capacity, allocation and limits:
- **Capacity** comes from exit volume in Dec 2024 to Sep 2025.
- **Allocation:** FINNIFTY and MIDCPNIFTY at capacity; BANKNIFTY lots solved so that Dec 2024 to Sep 2025 net comes to
  Rs 5,000/day. For flat this gave **BN 26 / FIN 3 / MIDCP 11**. For h7 it gave units BN 7 / FIN 1 / MIDCP 4.
- **Limits:** no new entry once the day's realised net is at or below -Rs 75,000, or once the month-to-date net is at
  or below -Rs 2,50,000. Open trades keep their own stops.
- **Sensitivities:** cap 10/20% and κ 0.01/0.04. 40 daily series were counted.

## Results (flat plan BN 26 / FIN 3 / MIDCP 11, κ 0.02, cap 15%)

| | net/day | gross/day | worst day | worst month | max DD | losing months | P(lose month) | Sharpe |
|---|---|---|---|---|---|---|---|---|
| Dec 2024 to Sep 2025, no limits | 4,928 | 11,676 | -1.39 L | -2.88 L | -5.34 L | 5/10 | 39% | 1.17 |
| Dec 2024 to Sep 2025, both limits | -1,098 | 4,714 | -1.11 L | -2.72 L | -8.29 L | 6/10 | 60% | -0.32 |
| Jun 2023 to Sep 2025, no limits | 4,559 | 7,658 | -1.39 L | -2.88 L | -5.34 L | 13/28 | 42% | 1.23 |
| **HOLDOUT, both limits (the plan)** | **6,075** | **14,005** | -1.66 L | -2.77 L | **-8.76 L** | 6/13 | 45% | 1.12 |
| HOLDOUT, no limits | 4,830 | 13,933 | -2.12 L | -6.63 L | -13.2 L | 6/13 | 46% | 0.84 |
| HOLDOUT, daily limit only / monthly only | 5,423 / 3,659 | | | | | | | |
| HOLDOUT h7 sizing, no limits / both limits | 10,172 / 10,733 | 16,827 / 17,020 | -1.79 L | -5.25 L / -3.47 L | -9.1 L / -8.1 L | 6/13 | 41% / 40% | 1.50 / 1.59 |
| HOLDOUT plan at κ 0.04 / κ 0.01 | -546 / 8,885 | | | | | | | |

In the 2023 to Sep 2025 per-year results, 2025 is -3.3 L with the limits on. Holdout by month, with the limits:

| Oct | Nov | Dec | Jan | Feb | Mar | Apr | May | Jun | Jul | Aug | Sep | Oct (to date) |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| +0.07 | -0.67 | -0.43 | **+6.9** | **+6.3** | +2.5 | -2.6 | -2.5 | -2.8 | +4.0 | +0.2 | +5.0 | -0.9 L |

Holdout net by index:

| index | net | gross |
|---|---|---|
| BANKNIFTY | **+11.6 L** | 22.8 L |
| FINNIFTY | -0.5 L | 0.7 L |
| MIDCPNIFTY | +4.0 L | 11.4 L |

Fills: FINNIFTY filled 2.1 of 3 lots on average. MIDCPNIFTY filled 9.3 of 11, with 2.4 order slices per exit.

**Capital:**
- Premium tied up at the plan size: p95 Rs 13.5-14.6 lakh, max Rs 24 lakh.
- Drawdown: Rs 9-13 lakh.
- **Total: about Rs 35-40 lakh.** Option buying needs no margin beyond the premium.

## The most Rs/day this edge supports

FINNIFTY and MIDCPNIFTY are capped at a few lots by liquidity, so the maximum is a BANKNIFTY question.

| | at κ 0.02 (central) | at κ 0.04 (pessimistic) |
|---|---|---|
| curve shape (BN lots, with FIN 3 / MIDCP 11) | net rises the whole way, by less per lot | peaks near 50 BN lots |
| before the holdout (Dec 2024 to Sep 2025) | Rs 14.9k at 100 BN lots; Rs 22.9k at 287 | Rs 2.2k/day for the full plan; BANKNIFTY alone about Rs 5.3k |
| holdout | Rs 12.0k at 100 lots; Rs 16.3k at 150 lots | |
| binding limit | holdout capacity is 129 lots, and each BANKNIFTY position then ties up about Rs 30 lakh of premium | liquidity |

- **Practical ceiling: about Rs 12-15k/day net, at about 100-130 BANKNIFTY lots and Rs 1-1.5 crore of capital, if
  κ ≈ 0.02.**
- **If κ ≈ 0.04, the ceiling is about Rs 5k/day, from BANKNIFTY alone.**

**Is Rs 5,000/day within capacity? Yes.** It takes about 26-30 BANKNIFTY lots, roughly 2-3% of the 5-minute volume.
But it carries no margin of safety on the impact assumption, and it rests on a lumpy, roughly 45%-losing-months
return stream.

## The plan, in a few lines

1. Trade the app's Liquidity 15+5 unchanged. Buy the 1-ITM monthly option:
   - BANKNIFTY 15m+5m: **26 lots** (the workhorse);
   - MIDCPNIFTY 15m+5m: **4-11 lots** (pre-registered 11; holdout capacity was 4);
   - FINNIFTY 30m+5m: **3 lots pre-registered, but post-hoc drop it or hold 1 lot.** Its capacity is 0-3 lots and it
     lost money in the holdout after impact.
2. Size: flat lots, as pre-registered. h7's room and +10% adds won the holdout but lost before it, so trial it only on
   BANKNIFTY in paper.
3. Execution:
   - never put more than 15% of the last 5 minutes' volume in one order;
   - use limit orders sliced over minutes on exits in thin contracts.
4. Limits:
   - no new entries after a realised loss of Rs 75k in a day, or Rs 2.5L in a month (scale both with size);
   - expect them to help or hurt about equally;
   - their job is to cap the worst month (-2.8 L instead of -6.6 L in the holdout).
5. Expectation at the plan size:
   - net about Rs 5-6k/day (gross about Rs 12-14k);
   - drawdown Rs 9-13 L, P(losing month) about 45%, a few days make all the money;
   - capital Rs 35-40 lakh.

## Step-up ladder (rungs and bands from pre-holdout data, flat; lots BN / FIN / MIDCP)

| rung | lots | net/day (Jun 2023 to Sep 2025) | month sd | 3-month p05 | max DD | premium p95 |
|---|---|---|---|---|---|---|
| R0 | 1/1/1 | 240 | 17k | -20k | -40k | 0.6 L |
| R1 | 3/1/2 | 635 | 35k | -37k | -72k | 1.3 L |
| R2 | 6/1/3 | 1,168 | 60k | -57k | -1.2 L | 2.2 L |
| R3 | 10/2/4 | 1,904 | 94k | -84k | -1.8 L | 3.5 L |
| R4 | 15/2/6 | 2,731 | 138k | -1.2 L | -2.8 L | 5.0 L |
| R5 | 20/3/8 | 3,584 | 185k | -1.6 L | -3.9 L | 6.7 L |
| R6 | 26/3/11 | 4,559 (regime 4,928) | 243k | -2.1 L | -5.3 L | 8.7 L |

In practice keep FIN at 1 lot and MIDCP at 4 or fewer, and add the difference to BANKNIFTY.

**Start: R0 in paper for 2 months, then R0 live.**

**Move up one rung** only after at least 2 months and at least 40 trades on the current rung, and only if all of
these hold:
- (a) Measured fill slippage (fill against the signal-minute print) is no worse than 1.25× the model's cost for that
  rung.
- (b) The 3-month rolling net is above the rung's 3-month p25 band. Any month may lose: P(losing month) is about 40%,
  so one red month is not a signal.
- (c) Every order stayed at or below 15% of the 5-minute volume, and there were no rule breaches.

**Move down one rung** if either:
- the 3-month rolling net is below the rung's p05, or
- the drawdown exceeds the rung's max DD.

**Stop and re-evaluate** if the drawdown reaches 1.5× the rung's max DD.

R6 is at least 12-14 months away, which is also the time needed to learn κ live.

## Honesty notes

- **What was chosen, and on what:**
  - the choices were made on data before 2025-10-01; the holdout ran once;
  - the capacity numbers come from volume only, viewed before writing PREREG.md;
  - 40 daily series were counted. SPA/RC over them in Dec 2024 to Sep 2025: p = 0.17 / 0.14;
  - random-entry p-values ≤ 0.0015 (BH q ≤ 0.002).
- **Post-hoc and not used for any choice:**
  - the holdout capacity numbers;
  - the advice to drop FINNIFTY and cap MIDCPNIFTY at 4;
  - h7 winning the holdout.
- **Partly in-sample:** the Liquidity exits were tuned on Feb 2024 to Feb 2026 (h4). The 10-month monthly-only window
  used for sizing is short.
- **Model limits:**
  - gross is computed on bar prints, and its exit fill is approximated (about 2% off h4's gross);
  - a book may re-enter while it is still working an exit slice;
  - the daily limit acts on realised P&L only, so the worst day still overshoots it (-1.66 L against -75k);
  - exchange freeze limits are ignored (brokers slice orders automatically).
