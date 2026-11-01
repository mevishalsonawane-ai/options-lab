# R144a pre-registration: why the breakout arms lose (R72, R51, R52), on volume, liquidity, OI, context and order flow

Written 1 Nov 2026, before any R144a number. Research only: **no app or arm code changes**. One of four parallel studies
(R144a-d) on the same question; this one owns **R72 Breadth Donchian, R51 Donchian 20 + Supertrend and R52 Open
outside value**.

Boss, 1 Nov: *"Find all the lost trades in indian market from our study for current arms, and find what all makes it
lose money ... volume, liquidity, order flow and all the other things regarding the failed orders, and also find
patterns for each arm, so it could help us to ignore those trades."*

Builds on R141 (price features, anatomy), R142 (bad-break filters, R52 no-progress exit), R143 (the unseen two years).
Not repeated: R141's price features as new filters (R140 / R142 already tested them), exits for give-backs (R105).

## 1. Trades and periods

- **Engine:** R115's (R101 units, R106 walk), today's rules, 1 lot, R17 fills, dated charges. Parity with the published
  design and final numbers is asserted (R115 `PUBLISHED`); the old store must reproduce the main store's trades on
  20 Nov - 30 Dec 2024 (R143's check).
- **Periods:**
  - **unseen two years** 1 Dec 2022 - 19 Nov 2024 (R143's old store);
  - **design** 20 Nov 2024 - 31 Mar 2026 (halves H1 to 31 Mar 2025, H2 1 Apr 2025 - 31 Mar 2026);
  - **final** 1 Apr - 13 Oct 2026.
- **Honesty note:** R140-R143 already read the arms' own trades in the final and the unseen years. What is fresh here
  is every new feature below: none has been looked at on those periods. Filters are chosen on design only; each test
  period is read once (guard files `results/FINAL_READ`, `results/UNSEEN_READ`).
- **A loser** is net < 0 after charges.

## 2. Features at entry (everything read at minute er - 1 or earlier; er = the fill row)

Signal source stays the index (CLAUDE.md). Option-chain values are context only. Windows that would start before
09:15 are clipped to the session start (R52 fills at 09:20, so its windows are short). Unknown = NaN, never a skip.

**Volume (V)**
- V1 option-market volume: ATM±2 calls + puts volume over minutes [er-5, er-1] / the median of the same minutes over
  the previous 20 sessions. *Index-futures minute volume is not in the lake before 29 Jul 2026, so it cannot be used;
  V1 is the stand-in.*
- V2 the traded strike's volume over [er-5, er-1] / the median of the same-side 1-ITM rolling row's volume over the
  same minutes in the previous 20 sessions (the signal-bar volume spike on the option itself).
- V4 index futures volume yesterday / its 20-session average (daily continuous futures).

**Liquidity (Q)** *The lake has no bid-ask quotes and no depth at all. Q2 and Q3 are proxies and labelled so.*
- Q1 premium level: the traded option's close at er-1 as % of spot.
- Q2 spread proxy: the mean of (minute high - low) / close of the traded option over [er-5, er-1].
- Q3 stale minutes: minutes in [er-15, er-1] with no print or zero volume on the traded option.
- Q4 strike OI: the traded strike's OI at er-1 / the mean OI of the same side's ATM±5 rows.

**OI / options context (O)**
- O1 OI change at the strike: OI at er-1 / OI at the session's first minute - 1.
- O2 writers' lean since the open, trade's way: s x (put OI change - call OI change) over ATM±2 / total ATM±2 OI at the
  first minute (s = +1 call, -1 put). Positive = writers adding on the side that supports the trade.
- O3 PCR trade's way: s x log(put OI / call OI) over ATM±5 at er-1.
- O4 IV level: mean of the ATM call and put IV at er-1.
- O5 IV change: O4 at er-1 minus O4 at the session's first minute with IV.
- O6 skew trade's way: s x (IV of the OTM-3 call - IV of the OTM-3 put) at er-1.
- O7 straddle vs expected move (R128): ATM call + put close at er-1 / (spot x VIX/100 / sqrt(252)). Days to expiry are
  not in the lake; this mixes expiry distance with richness, and is reported as such.

**Market context (C)**
- C7 VIX level at er-1.  C8 VIX change from the previous close to er-1 (%).
- C9 breadth agreement: R141's C4 (kept as R141's).
- C10 futures basis, previous session: (futures close - index close) / index close, % (intraday futures missing).
- C11 US overnight move, trade's way: s x the S&P 500's last close-to-close change dated before the session (Yahoo
  daily, outside the lake; US close is about 02:30 IST, before 09:15).
- C12 day regime (proxy for R41's range forecast, which is not rebuilt): the VIX-implied one-day move / the 20-session
  average daily range.

**Order flow (F)** *No historical order book exists. All are proxies.*
- F1 option volume delta: sum(sign(close - open) x volume) / sum(volume) of the traded option over [er-15, er-1].
- F2 index tick direction: share of index minutes in [er-15, er-1] closing the trade's way, minus 0.5.
- F3 option OI-price quadrant over [er-15, er-1] (traded option): long build-up (price up, OI up), short build-up
  (down, up), short covering (up, down), long unwinding (down, down). Category.
- F4 futures OI-price quadrant, previous session, relative to the trade (build-up with / against, covering with,
  unwinding against). Category.
- F5 trade-side option volume / other-side option volume, ATM±2, over [er-15, er-1].

**Plus R141's 17 price features** (A1-A11, C1-C6), for the anatomy only.

A look-ahead guard test changes every minute at or after er (and later sessions) and asserts no feature moves.

## 3. Anatomy per arm (descriptive)

Per period: loser rate; money lost by exit reason (stop, time / square-off, other) and by R141's peak type (never
worked < 5%, gave back >= 15%); bunching (losing days, all-loss index-days, longest losing streak); the worst 20
trades with their features; loser rate and average net per design-edge quintile (or category) of every feature.

**Steady:** a slice whose loser rate is above the arm's and whose average net is below 0 in **every one of the three
periods**, with >= 30 trades in each.

## 4. Skip filters (at most 3 per arm, chosen on design only)

- **Pool:** the new features V, Q, O, C7-C12, F (not R141's price features: already tested in R140 / R142).
- **Candidate slice:** for a quintile feature its lowest or highest design-edge fifth, for a category any category.
- **Qualifies** when in BOTH design halves (H1, H2): >= 30 trades, average net below 0 and below the arm's, loser
  rate above the arm's. Then the skip (drop the arm's candidates in that slice, walk again) must, on design as a
  whole: cut the sum of losing days, not worsen drawdown, worst day or worst month, and keep net > 0.
- **Ranking:** by the design t-score of the slice's mean net against the rest of the arm's trades (most negative
  first); at most one slice per feature; the top 3 per arm. Fewer, or none, if fewer qualify. The selection is written
  to `results/selection.json` and pushed before either test period is read.

## 5. Tests and gates (each in the final AND the unseen two years)

In each period, against today's rules:
1. the sum of losing days is smaller;
2. the max drawdown, worst day and worst month are no worse;
3. where today's arm made money in that period, the filtered arm still does;
4. it beats 2,000 random twins (drop as many candidates at random, walk again) on the sum of losing days: p <= 0.05;
5. >= 40 trades and >= 30 trades that differ.

The p used for BH is the larger of the two periods' p (both must pass). **BH q <= 0.10 within my group** (all R144a
tests). Every raw p goes to `results/pvalues.json` so the coordinator can apply BH across R144a-d.

Reported: win rate before -> after against Boss's 65% rule; trades; net; losing days; drawdown; worst day / month.

**A filter that passes** is proposed (not built) as a shadow log first and then a labelled paper switch, off by
default, logged in the Autopilot timeline, one-tap undo. Nothing changes without Boss's approval.

## Outputs

`r144a.py`, `r144a_feat.py`, `test_r144a.py`, `results/` (small JSON only), `research/HUNT_R144A_LOSS_BREAKOUT.md`,
`research/experiments/entries/r144a-*.yaml`.
