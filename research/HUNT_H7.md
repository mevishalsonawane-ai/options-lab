# HUNT h7: more rupees per Liquidity trade from pyramiding and room-based sizing, with entries and exits unchanged

Code: `research/hunt/h7/`
- `build.py`: one data pass that builds the path packs.
- `sim.py`: the base trades and the add-on/sizing overlays.
- `analyze.py`, run in three stages:
  - `pre`: chooses using only data before 1 Oct 2025.
  - `holdout`: one test of the frozen choice.
  - `sized`: integer lots, capital, fills, and a post-hoc diagnostic.

Caches are in `scratchpad/hunt/h7/cache/h7/` (`pre_variants.csv`, `choice.json`, `holdout.json`, `sized.csv`,
`fill.csv`, `posthoc_hold_all.csv`).

Everything here is option BUYING. The Liquidity 15+5 trades are the h4 port, which reproduces h4 to the rupee:
2,279 trades, real net BANKNIFTY 144,334 / FINNIFTY 91,911 / MIDCPNIFTY 102,311. Entries and exits are never changed.

## Verdict (plain language)

**Sizing up on far targets and adding lots to winners works, but it is only modestly better than simply trading more
flat lots, and it does not make Rs 5,000/day dependable.**

1. **At the same average number of lots, the add-on rule clearly earns more than flat lots.**
   - Before the holdout: +Rs 486/day per base unit (SPA over all 32 variants p = 0.045). The excess was positive in
     every walk-forward year: 2023 +1.35 lakh, 2024 +1.90 lakh, 2025 to September +0.63 lakh.
   - Holdout: +Rs 1,370/day per base unit (Rs 2,901 against Rs 1,531, bootstrap p = 0.037).
   - On random entries with the same add-on rule, the uplift was about half as large before the holdout and about zero
     in the holdout. So some of the gain comes from the exits' structure (stopped losers never receive adds), not only
     from Liquidity's signal.
2. **At the same risk it is roughly a tie.** Scale both to Rs 5,000/day on pre-holdout data:

   | | add-on rule, Sharpe | flat lots, Sharpe |
   |---|---|---|
   | before the holdout (Jun 2023 to Sep 2025) | 1.51 | 1.74 |
   | holdout | 2.14 | 1.90 |

   Adds raise both the gains and the drawdowns. "Just trade more flat lots" gets you almost the same thing, more simply.
3. **Index-progress adds (Y% of the way to the next level) lose to flat lots in every version tried.** Adding on
   premium +10% steps, and sizing up when the next level is far, are what work.
4. **Rs 5,000/day is reachable on paper but lumpy.**
   - At about 3 lots a base unit (9 lots maximum at entry, 18 lots maximum after adds), the holdout made Rs 8,980/day
     net (Rs 10,620 gross).
   - **88% of the holdout profit came from its best 5 days.**
   - Before the holdout, 13 of 28 months lost money, and P(losing month) is 33-42%.
   - **Rs 5,000/day realistically: NO as a dependable daily income.** It is a skewed average, much like h4's flat-lot
     result.

## The single best simple rule (chosen before the holdout: `B_prem10m3_terc`)

1. Trade the app's Liquidity 15+5 arm unchanged on BANKNIFTY, FINNIFTY (30m+5m books) and MIDCPNIFTY (15m+5m, index
   stop 8).
2. Size the first order by the **room to the next liquidity level**, measured in index stops (BANKNIFTY 30 / FINNIFTY
   15 / MIDCPNIFTY 8 points):
   - under 3 stops: 1 unit;
   - 3 to 13.8 stops: 2 units;
   - over 13.8 stops, or no level ahead: 3 units.

   The bucket edges are the pre-holdout terciles.
3. While the trade is open, **add 1 unit each time the option's 1-minute close first reaches +10%, +20% and +30% of the
   first fill** (at most 3 adds). Each add is a MARKET buy at the next minute's open, in the same contract.
4. All lots exit together on the arm's own exits:
   - the -15% stop on the first fill;
   - the index stop;
   - the 20-minute +5% time stop;
   - the next level;
   - a failed break or a new level;
   - 15:10.

One unit is 3 lots for the Rs 5,000/day size.

## What was tried (pre-registered in `analyze.py`; 32 variants, all counted)

The variants:
- **Pyramids (10):**
  - premium-step adds: X = 10/20/30% × max adds 1 or 3;
  - index-progress adds: 25/50/75%, 25% (one add), 33/67%, 50%.
- **Room sizing (2):** terciles 1/2/3 lots, or a median split 1/2 lots.
- **Both (20):** every pyramid × every sizing.

Two flat benchmarks for every variant, with exposure matched:
- **'lots'**: flat k lots, where k is the variant's mean lots per trade. This is the selection criterion.
- **'prem'**: flat k lots with the same premium deployed. The results are nearly identical.

Pre-holdout results, real fills plus app charges, per base unit, 1 Oct 2021 to 30 Sep 2025. Excess is measured
against the lots-matched flat benchmark.

| variant | mean lots | net/day | flat same lots | excess/day | excess/trade, real entries | excess/trade, random entries | p (BH q) |
|---|---|---|---|---|---|---|---|
| base (1 lot) | 1.00 | 312 (Jun 23 on) | - | - | - | - | - |
| P prem 10% ×3 | 1.45 | 616 | 331 | +285 | +173 | +97 | 0.027 (0.051) |
| P idx 25/50/75 | 1.79 | 310 | 425 | **-116** | -70 | -14 | 1.0 |
| S terciles 1/2/3 | 2.00 | 686 | 485 | +201 | +122 | +44 | 0.011 (0.035) |
| **B prem 10% ×3 + terciles (chosen)** | 2.45 | 1,098 | 612 | **+486** | +295 | +141 | 0.009 (0.035) |

- White's Reality Check over the 32 excess series: p = 0.012. Hansen SPA: p = 0.045.
- Pyramided Liquidity beats pyramided random entries in every variant (p = 0.0005).
- The full table is in `pre_variants.csv`.

## Holdout (1 Oct 2025 to 6 Oct 2026, tested once)

Per base unit, real fills:

| | variant | flat with the same lots (2.45) | flat, 1 lot |
|---|---|---|---|
| net per day | **2,901** | 1,531 | 551 |
| gross per day | 3,540 | 2,223 | 905 |

Against random entries with the same overlay:
- Real entries averaged Rs 1,111 per trade; random entries averaged -Rs 391 (p = 0.0005).
- The excess over flat lots was Rs 525 per trade on real entries and Rs 6 per trade on random entries.

Holdout net by index (variant against same-lots flat):

| index | variant | flat, same lots |
|---|---|---|
| BANKNIFTY | 301k | 162k |
| FINNIFTY | 126k | 72k |
| MIDCPNIFTY | 295k | 147k |

MIDCPNIFTY is the cleanest evidence, because no part of it was ever tuned.

Net by year, per base unit (variant / flat same lots):

| year | variant | flat, same lots |
|---|---|---|
| 2021 | -0.3k | -21k |
| 2022 | +74k | +1k |
| 2023 | +183k | +48k |
| 2024 | +455k | +265k |
| 2025 | +389k | +335k |
| 2026 to Oct | +709k | +359k |

## At the size for ~Rs 5,000/day (integer lots, brokerage per order)

The size was fixed on Jun 2023 to Sep 2025 real net. The variant uses s = 3, so it enters with 3/6/9 lots and adds
3 lots each time. The flat comparison is 12 lots.

| | variant pre | variant **holdout** | flat 12 lots pre | flat 12 lots holdout |
|---|---|---|---|---|
| net per day (real) | 4,362 | **8,982** | 4,855 | 7,970 |
| gross per day | 5,376 | **10,620** | 6,266 | 10,856 |
| worst day | -1.55 lakh | -0.88 lakh | -1.00 lakh | -1.34 lakh |
| worst month | -2.52 lakh | -2.79 lakh | -2.02 lakh | -4.28 lakh |
| max drawdown | -3.85 lakh | -4.23 lakh | -3.65 lakh | -6.61 lakh |
| losing months | 13/28 | 4/13 | 12/28 | 4/13 |
| P(losing month), bootstrap | 42% | 33% | 36% | 34% |
| premium tied up, 95th percentile / max | 5.5 / 14.2 lakh | 7.8 / 18.9 lakh | 7.1 / 18.6 lakh | 10.1 / 16.3 lakh |

- s = 4 gives about Rs 5,800/day before the holdout, with roughly 4/3 of every risk figure.
- **Capital: about Rs 20-25 lakh.** That is the peak premium plus a drawdown buffer. Option buying needs no margin
  beyond the premium.

**Fill-size realism** at this size. The table shows lots per order against the contract's traded lots in the 5 minutes
before the order.

| index | order size | median 5-min volume at entry | median 5-min volume at exit | exits larger than 20% of the 5-min volume |
|---|---|---|---|---|
| BANKNIFTY | ≤ 9 lots in, ≤ 18 out | 28,000 lots | 15,000 lots | 0.2% |
| FINNIFTY | ≤ 9 lots in, ≤ 18 out | 990 lots | 440 lots | **26%** (90th percentile of exit/volume is 1.5×) |
| MIDCPNIFTY | ≤ 9 lots in, ≤ 18 out | 550 lots | 280 lots | **14%** |

- BANKNIFTY fills are fine.
- FINNIFTY and MIDCPNIFTY monthly 1-ITM contracts are thin, and exits that happen after adds land exactly when size is
  largest. Expect fills worse than the 1-4 tick model, so the real net is lower than shown.

## Caveats

- The pre-holdout and holdout profits are **dominated by a handful of days**: the best 5 holdout days make 88% of the
  total.
- Liquidity's BANKNIFTY and FINNIFTY exits were tuned on Feb 2024 to Feb 2026, which overlaps 5 holdout months (see
  h4).
- The positive pyramid uplift on random entries before the holdout means the add-on effect is partly generic
  (momentum in premium plus truncated losers), not proof of the signal.
- Post-hoc and not used for any choice: in the holdout every premium-step and sizing variant beat its flat benchmark,
  while the pure index-progress pyramids lost again (`posthoc_hold_all.csv`). The ranking held up.
