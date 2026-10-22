# R103 pre-registration: defined-risk option selling on NIFTY and BANKNIFTY (22 Oct 2026)

Boss (22 Oct 2026), after the strategy ranking: "Find strategies with best winrate and more profit", then "Can you
enhance it". Written and committed **before any R103 profit or loss was computed**. Research only: nothing in
`android/**` or `.github/**` changes, nothing goes to paper or live.

## Why this family, and what is not repeated

* The Donchian books (the arms with the most profit) were frozen by R75 after 40 "less loss, more win rate" levers
  failed, including a low-VIX skip, a narrow-opening skip, entry windows and expiry skips; R64-R70, R73 and R84 tried
  exits, locks, trend filters and risk caps. Re-tuning them again on the same final window is not done here.
* Option BUYING with a high win rate was searched by R48 (6.6 million setups): high design win rates became losses.
* The one family with a reason to win often AND make money is untested with capped risk (open question
  `defined-risk-selling`, research/knowledge/open_questions.yaml): R102 found the close-to-09:20 move beats the 09:20
  ATM straddle on 7.1% (NIFTY) / 6.5% (BANKNIFTY) of non-expiry days and 40.5% / 37.8% of expiry days, and India VIX
  over-states NIFTY's next 21 days on 81% of days. NONDIRECTIONAL.md found the NAKED short straddle positive in both
  years but could not price hedged versions (every leg at its worst price in a minute). R103 prices the hedged
  structures on held contracts with next-minute fills.

**Signal source:** none directional. The structure is centred on the index's ATM strike at 09:20 (the underlying).
Stops, locks and targets are on the position's premium (allowed, CLAUDE.md).

## Data

The Drive lake (copied 22 Oct 2026): `index_1m` and `opt_<UND>_WEEK_<YEAR>` (rolling ATM +-10, nearest expiry) for
NIFTY and BANKNIFTY, Aug 2021 - 19 Oct 2026. Held contracts are rebuilt per (side, strike) with R51's store builder
(`research/hunt/r51/r51data.py`, Amendment A cleaning: foreign rows and stray prints removed), so every leg is priced on
the strike traded, never on the rolling series. BANKNIFTY's nearest expiry is the monthly after 13 Nov 2024. Expiry days
come from the F&O bhavcopy calendar (R102 `r102data.expiries`).

## Periods

* **Design**: first option day (Aug 2021) - **14 Jul 2026**.
* **Holdout**: **15 Jul - 19 Oct 2026**, read once by `r103study.py holdout` (writes `results/HOLDOUT_READ`, refuses a
  second read). R102 read this window for index-direction rules only; no option-selling result on it has been read.
* Walk-forward: the rules have no fitted parameter, so walk-forward = the sign in each calendar year 2022, 2023, 2024,
  2025 and in the post-20-Nov-2024 part of the design (the new expiry regime) separately.

## The rules (24 tests = 2 indices x 2 day types x 3 structures x 2 exits)

* **Day types**: E = the index's own expiry day; N = every other trading day.
* **Entry**: decision on the 09:20 minute's close (row 5). K0 = the rolling ATM strike at that minute (offset 0; the
  index close rounded to the strike step if missing). S = close of C(K0) + close of P(K0) at that minute. Orders fill at
  the 09:21 open of each leg. A day with any leg missing at entry is skipped (counted).
* **Structures** (every distance rounded to the nearest strike step, at least 1 step, at most 10 steps from K0):
  * **IF** iron fly: sell C(K0) and P(K0); buy C(K0 + w) and P(K0 - w), w = 1.0 x S.
  * **IFW** wide iron fly: as IF with w = 1.5 x S.
  * **IC** iron condor: sell C(K0 + a) and P(K0 - a), a = 0.5 x S; buy C(K0 + a + w) and P(K0 - a - w), w = 1.0 x S.
* **Exits** (checked on each minute's close of all four legs, executed at the next minute's open):
  * **X1** stop: close everything when the position's loss reaches 50% of the net credit received; else square off at
    15:10 (N) / 15:05 (E, the app's expiry square-off).
  * **X2** stop + profit lock + target (the lock rule Boss uses for crypto): the X1 stop; once the profit has reached 40%
    of the credit, close if it falls back to 20%; take profit at 60% of the credit; else the same square-off.
  * A leg with no print in a minute is marked at its last close; a fill with no open in the next minute takes the next
    minute that has one (up to 5), else the last close.
* **Size and costs**: 1 lot, the lot in force on the day (research/obuy/config.py LOT_SCHEDULE with the Jan 2026 lots,
  NIFTY 65 and BANKNIFTY 30, NSE/FAOP/70616). Each leg pays dated charges (research/obuy/costs.py 'dated': STT on the
  sell side, exchange, SEBI, stamp on the buy, GST, Rs 20 brokerage an order, so 8 orders a trade) and slippage per
  order from the 'liq' fill model (5 bps plus 1-4 ticks by the contract's traded volume in the 5 minutes before).

## Gates (all must hold on design; the holdout is read only for tests that pass every design gate)

1. Net > 0 and the mean net per trade > 0 with a day-bootstrap one-sided p < 0.05 (10,000 resamples, seed 103).
2. BH q <= 0.10 across the 24 tests (one family, `option-structure`).
3. Net > 0 in at least 3 of the 4 calendar years 2022-2025, and > 0 after 20 Nov 2024.
4. At least 40 trades.
5. Net > 0 without its 5 best days.
6. Worst calendar month >= -Rs 13,000 at 1 lot (Boss's account stop, CLAUDE.md).
7. Net > 0 with twice the slippage ticks.

**Random twins.** The rule trades every eligible day, so a random-day twin is the rule itself; gate 1's bootstrap is the
test that matters (is the mean above zero). A timing twin is reported, not gated: the same structure and exits entered
at a random minute 09:21-13:00 on the same day (200 per day, seed 103).

**Holdout gate** (for design passers): net > 0, worst month >= -Rs 13,000, and the win rate within 15 points of design.
Too few expiry days fall in the holdout (about 14 NIFTY, 3 BANKNIFTY), so an E-test's holdout can reject, not confirm.

**Win rate.** Reported for every test with the average win and loss. "High win rate" here means >= 60% with a profit
factor >= 1.1; this is a label, not an extra gate.

## What a pass means

A pass is a **paper candidate only**. Selling is new for the app (it buys today), needs margin (an iron fly's margin is
about its wing width x lot), and is not built here. Nothing changes in paper or live. Every test gets an entry in
`research/experiments/entries/` (r103-*), failures included.

## Checks

`research/hunt/r103_sell/tests/test_r103.py`: the leg pricing and charges on hand-made days, the stop / lock / target
paths, the strike rounding and the 10-step cap, the look-ahead guard (nothing after a decision minute changes it), the
holdout lock, BH and the bootstrap. Plus the repo's research checks.
