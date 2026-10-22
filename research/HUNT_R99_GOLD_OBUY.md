# R99: GOLDM intraday option buying, "catch the 100-point moves" (21 Oct 2026)

Boss, 21 Oct: "Now do same for commodities, find the best strategy, the markets moves 100+ multiple times per day, we
need to catch it, run it loop same as hero strategy find the best strategy, dont stop until best is found", then "Only
options buyying in the scope" and "Focus on gold options buyying".

Research only: nothing in the app changes. Code: `hunt/r99_gold_obuy/` (study.py round 1, study2.py round 2, sim.py the
trade engine, tests test_r99.py and test_r99_round2.py, 13 pass). Pre-registered: `PREREG.md` (round 1, commit 5396b7d7)
and `PREREG_ROUND2.md` (round 2, commit ddcd3497), each committed before any trade result. Results:
`hunt/r99_gold_obuy/results/` (design.md, design2.md, holdout.md, census.md).

**Signal source of every rule: the GOLDM future** (minute closes). The option's own price is never a signal; the only
premium rule is the -40% hard stop.

## The answer

**No GOLDM option-buying rule was found.** Two pre-registered rounds tested 148 rules:
- none passed the design gates;
- the luck test over all 148 gives Hansen SPA p **0.982** (White RC p 0.999);
- this adds to about 15 earlier gold option-buying studies that also found nothing (M3, M4, R2, R3, R53, R85, R89-R96, MB1).

Boss is right that gold moves 100+ points many times a day: the future makes a median **68 legs of ≥ 100 points a day**
(quartiles 40-91). But **a 100-point move cannot pay for a GOLDM option**:
- an ATM option costs about Rs 28,000 a lot (≈ 2,800 points);
- on the 100-point trades the option moved only **0.33 × the future**;
- it lost **~14 option points on the fill** before charges (the first print after an impulse);
- the charges plus half-spreads cost **~37 points** more (Rs 369 a trade).

So a trade needs roughly **150-200 future points in its favour just to break even**. Fading or following the 100-point
moves lost money **before** costs as options, although fading them as a future line beats chance (0.42 vs 0.33).

## Data and method

- **Book:** GOLDM near-month options, 1 lot (×10 a point), real 1-minute prints of the stored ATM ± 3 strikes, from the
  Drive lake (21 Oct copy).
- **Engine:** R96's fills, marks, exits and costs, unchanged:
  - the fill is the open of the first print within 5 minutes of the signal;
  - Zerodha MCX option charges, plus a half-spread of 0.30% from 17:00 and 0.60% before ("R85 strict");
  - every gate checked again at 2× the half-spreads.
- **Periods:**
  - design 5 Aug 2025 – 14 Jul 2026, with four walk-forward folds;
  - holdout 15 Jul – 19 Oct 2026, read **once** for the whole of R99 (`results/HOLDOUT_READ.lock`).
- **Gates:** ≥ 30 trades; net > 0 at 1× and 2×; twin p < 0.05 with BH q ≤ 0.10 over all 148; ≥ 3 of 4 folds; worst
  month > -Rs 13,000 and drawdown > -Rs 40,000 (Boss's account stop); net > 0 without the best 3 trades; SPA p < 0.05.

## Round 1 (104 rules): fade / follow the impulse, range breaks

| family | what | variants | best net (trades) | result |
|---|---|---|---|---|
| A fade | after ≥ X points in W minutes, buy against it; out at T back, T/2 further or 60 min | 48 | -Rs 397,653 (811) | all negative; gross ≈ 0 |
| B follow | the same, with the move | 48 | -Rs 322,126 (937) | all negative |
| C range break | the 09:00-10:00 or 17:00-18:30 range, first break, T = 1-2 × the range | 8 | **+Rs 40,034 (124)** | fails at 2× (-Rs 3,847), 2/4 folds, -Rs 74,674 w/o top 3 |

- SPA p 0.715, RC p 0.978; 0 of 104 passed even the cheap gates.
- The A/B trades lasted a median **4 minutes**: the T/2 stop (50-150 points) is inside gold's minute noise.
- C was the only family positive before costs (median hold 82 minutes, the option moved 0.57 × the future). Its puts made
  the money (+Rs 43,617 on 63); its calls did not (-Rs 3,584 on 61).
- Expiry-day cheap options ("gold hero-zero", census only): 10 design expiries, 100 option-marks. By cell (1-18 marks
  each), the median 18:15 close is 0.80-1.98× the price, and 0-50% reach 3× at their best close. Too few days for any
  rule.

## Round 2 (44 rules): at most one trade a day, held for hours

Round 1 suggested this idea (disclosed in the pre-registration), so the luck test runs over rounds 1-2 together.

| family | what | variants | best net (trades) | result |
|---|---|---|---|---|
| D1 morning ORB | break of 09:00-09:30 / 10:00 / 11:00, stop at the range's other side, out at the square-off or 2× range | 12 | -Rs 117,111 (209) | all negative |
| D2 evening ORB | break of 15:00-17:00 / 17:00-17:30 | 8 | -Rs 109,820 (223) | all negative |
| E day trend | at 17:00 / 19:00, if the move since 09:00 ≥ k × the 20-session median daily range, buy with it; stop at half that move; out at the square-off | 8 | **+Rs 20,741 (36)** | near-miss (below) |
| F big-impulse follow | ≥ 400 in 30 / 600 in 60 minutes, stop at its origin; out at 120 min or the square-off | 8 | +Rs 25,187 (173) | -Rs 33,893 at 2×; -Rs 123,263 w/o top 3 |
| G big-impulse fade | the same, against it; target and stop half the impulse | 8 | -Rs 29,592 (191) | all negative |

- Round 2 alone: SPA p 0.971. **Rounds 1-2: SPA p 0.982**, RC p 0.999. 0 of 44 passed the cheap gates.
- Morning signals often could not fill: ATM options print rarely before 17:00 (about 70% of volume is after 17:00), so
  many breaks were taken late or not at all (e.g. D1 09:00-10:00 ATM: 212 trades, 2,303 unfilled signal minutes).

### The one near-miss: E "day trend at 17:00, k 0.5, ATM"

At 17:00, if GOLDM has moved at least half its usual daily range since 09:00, buy the ATM option in that direction, stop
when half the day's move is given back, out at the square-off (~23:15).

| | trades | win | net | at 2× | folds | worst month | max DD | w/o top 3 | twin p |
|---|---|---|---|---|---|---|---|---|---|
| design | 36 | 47% | +Rs 20,741 | +Rs 12,154 | 4/4 | -Rs 5,589 | -Rs 18,685 | **-Rs 34,530** | not run (failed gate 6) |
| holdout (read once, description only) | 15 | 53% | +Rs 22,889 | +Rs 20,349 | – | -Rs 1,071 | -Rs 11,229 | **-Rs 9,613** | **0.866** |

- Average win Rs 5,448 against an average loss of Rs 3,783 (design). The median premium is Rs 27,965 a lot; the risk at
  the hard stop is ~Rs 11,471.
- **It is not an edge yet:**
  - without its 3 best trades it loses in both periods;
  - random-timed twins with the same direction on the same days did as well on the holdout (p 0.87);
  - the 2025-26 gold trend may be doing the work.
- It is the only candidate worth a **forward paper test**, if Boss wants one: about 3 trades a month, Rs 28k a lot.
  It is not frozen or added to the app unless Boss asks.

## What would change the answer

- A cheaper instrument than the option for the 100-point moves (the future itself, which earlier studies also found
  negative after costs: MB1), or measured live spreads well under R85's 0.30-0.60% (0.5× is reported in the results;
  it is never a gate).
- New data: forward days after 19 Oct 2026. The pre-registration rules out a third round on the same 11 months.
