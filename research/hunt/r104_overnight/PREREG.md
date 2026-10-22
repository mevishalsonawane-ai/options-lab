# R104 pre-registration: defined-risk option selling held OVERNIGHT, NIFTY and BANKNIFTY (22 Oct 2026)

Boss (22 Oct 2026): "Find strategies with best winrate and more profit", "Can you enhance it", then "Continue".
Written and committed **before any R104 profit or loss was computed**. Research only: nothing in `android/**` or
`.github/**` changes, nothing goes to paper or live.

## Why

R103 found that selling the 09:20 straddle with wings and closing the same day loses: a day's decay is used up by the
intraday moves and the slippage, and eight orders cost Rs 196-255. The decay that intraday selling never collects is the
overnight one (the night, and the weekend from a Friday). NONDIRECTIONAL.md found the naked ATM straddle sold at 15:15
and bought back the next day positive in both of its years (with worst days of about -Rs 17-28k); it could not price the
hedged versions. R104 prices the hedged structures, which cap the gap risk, with R103's engine and its stale-leg repair
built in from the start (lesson of R103 amendment 1). This is the last selling variant listed as open
(`defined-risk-selling`, research/knowledge/open_questions.yaml).

**Signal source:** none directional. The structure is centred on the index's ATM strike at 15:14 (the underlying).
Stops are on the position's premium.

## Data and periods

As R103: the Drive lake, held contracts per strike from R51's store builder (Amendment A cleaning), Aug 2021 -
19 Oct 2026, the F&O bhavcopy expiry calendar. **Design**: entry days up to **14 Jul 2026**. **Holdout**: entry days
**15 Jul - 16 Oct 2026** (the exit falls inside the lake), read once by `r104study.py holdout`, only for design passers.
No selling result on the holdout has been read (R103 did not read it).

## The rules (12 tests = 2 indices x 3 structures x 2 exits)

* **Entry day**: every trading day d0 that is not the held contract's own expiry day; the exit day d1 is the next
  trading day in the lake (on or before the contract's expiry).
* **Entry**: decision on the 15:14 minute close (row 359); K0 = the rolling ATM strike then; S = the ATM straddle close
  then. Orders fill at the 15:15 open of each leg.
* **Structures**: R103's IF (wings 1.0 x S), IFW (wings 1.5 x S), IC (shorts 0.5 x S, wings 1.0 x S beyond); every
  distance rounded to whole strikes, 1-10 from K0.
* **Exits** (the stop is checked on each minute close of d1 from 09:15, filled at the next minute's open; nothing is
  checked on d0 after the entry):
  * **O1 overnight**: the stop (loss of 50% of the credit), else out at the 09:20 open of d1.
  * **O2 one day**: the same stop, else out at 15:10 of d1 (15:05 when d1 is an expiry day).
* **Prices**: a leg's value at a minute is its close if it printed within the last 5 minutes; otherwise Black-Scholes at
  the minute's index level and time to expiry with the implied volatility of its own last print (on d1, or on d0 if it
  has not printed on d1 yet); its last close if no IV can be found. Fills take the minute's open, the next open within
  5 minutes, else the re-priced value.
* **Size and costs**: as R103 (1 lot, dated lots, dated charges on 8 orders, 'liq' slippage on every fill).

## Gates (all on design; the holdout is read only for tests that pass every design gate)

1. Net > 0 and the mean per trade > 0 with a day-bootstrap one-sided p < 0.05 (10,000 resamples, seed 104).
2. BH q <= 0.10 across the 12 tests (family `option-structure`).
3. Net > 0 in at least 3 of the 4 calendar years 2022-2025, and > 0 after 20 Nov 2024.
4. At least 40 trades.
5. Net > 0 without its 5 best trades.
6. Worst calendar month >= -Rs 13,000 at 1 lot.
7. Net > 0 at twice the slippage ticks.
8. **Independent price check**: O2's time exits re-priced at the F&O bhavcopy close of every leg on d1 must also give
   net > 0 (for O1 the check is reported only: the bhavcopy has no 09:20 price).

**Holdout gate** (passers): net > 0, worst month >= -Rs 13,000, win rate within 15 points of design.

Reported, not gated: Friday entries (the weekend) and other days separately; win rate with average win and loss; the
gross before charges. A pass is a **paper candidate only** (overnight short options need margin and carry gap risk;
nothing is built). Every test is logged in `research/experiments/entries/` (r104-*), failures included.

## Checks

`research/hunt/r104_overnight/tests/test_r104.py`: two-day pricing, the repair from d0's IV when d1 has no print, the
stop at d1's open (a gap), the exits, the look-ahead guard, the holdout lock. Plus the repo's research checks.
