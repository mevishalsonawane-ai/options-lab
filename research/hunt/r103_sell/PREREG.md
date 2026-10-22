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

## Amendment 1 (22 Oct 2026, 15:05 IST; written after the lake copy and BEFORE the stores were built or any R103 number existed)

Boss's brief for the write-up asks for columns the rules above do not produce. They are added as **report columns only**:
no rule, exit, structure or gate changes, and the 24 tests and the BH family are unchanged.

1. **Margin and return on margin.** There is no SPAN file in the lake, so margin is a stated estimate, the same for every
   trade: **2.5% of the short legs' notional (2 x K0 x lot) on a normal day, 4.5% on the index's expiry day from 20 Nov
   2024** (SEBI's 2% expiry-day ELM on short options, circular of 1 Oct 2024). The 2.5% is calibrated on Zerodha
   Varsity's worked NIFTY iron condor (Rs 44,303 on two short legs of Rs 7.48 lakh notional each, 2.96%, with the
   pre-2024 ELM) and a Nov 2024 forum example (Rs 35 lakh -> Rs 62 lakh on the same condor when the 2% ELM came in,
   i.e. about 2.5% -> 4.5%). Reported: mean margin per trade and **net / mean margin**, in total and per calendar
   year. It is a planning number; the broker's calculator decides on the day.
2. **Days beyond the account stop:** the count of trades with net <= -Rs 13,000 (CLAUDE.md's monthly stop, applied to one
   day as a tail count). Gate 6 (worst calendar month) is unchanged.
3. **The BUY-side mirror**, reported beside every test and not gated: the same four legs with every sign reversed (a long
   iron fly / long iron condor, a debit), the same 09:21 fills, slippage and dated charges, and the same exits read on the
   debit instead of the credit (X1: stop at a loss of 50% of the debit; X2: that stop, lock armed at +40% out at +20%,
   target +60%). Before charges the mirror's gross is the seller's gross with the sign flipped; after charges both sides
   pay, so the pair shows what the structure itself does and what the costs do.
4. **Data as copied (22 Oct 2026, about 14:55 IST):** `index_1m_<UND>_<YEAR>.parquet` (NIFTY 2017-2026, BANKNIFTY
   2021-2026), `opt_<UND>_WEEK_2021..2026.parquet`, `participants/bhav_index/bhav_index_*.parquet` (the expiry calendar),
   `index_daily/index_daily.parquet` (for the descriptive tail table below). rclone was not in the session image; the
   Ubuntu package (rclone 1.60.1) was installed and used with the session's remote. Nothing else was read.
5. **A descriptive table, not a test** (for `research/DEEP_OPTIONS_SELLING.md`): the largest close-to-09:20 index moves
   in the option lake and what the naked 09:20 ATM short straddle and the IF (held to the square-off, no stop) lost on
   them, computed over the whole lake AFTER the holdout read, so the holdout stays unread until then. The pre-Aug-2021
   crashes are described from the daily index and the median straddle only (an estimate, labelled).

Timing twins (reported, not gated) stay as written. The random-day twin remains the rule itself.

## Amendment 2 (22 Oct 2026, about 15:35 IST; written AFTER the design results were committed and BEFORE the holdout read)

**What was found.** Six tests passed every design gate (BANKNIFTY non-expiry, IF / IFW / IC x X1 / X2). Checking them
before the holdout showed a data artefact. The lake keeps only the ATM +-10 strikes at each minute, so a held wing that
drifts outside that window stops printing. As pre-registered, the engine marks such a leg at its last close and exits it
at that close when no open exists within 5 minutes. On the BANKNIFTY passers 11-18% of exit legs had no print in the 5
minutes before the exit and 9-15% none in the 30 minutes before (430-628 stale long wings in about 1,000 trades). On
NIFTY it was 3%. A long wing frozen at an old, higher price overstates the seller's profit and hides losses from the stop
(the IF's stop fired on 6 of 998 BANKNIFTY days).

**The check (a stricter gate, added before the holdout).** `r103stale.py` reprices every exit leg with no open within 5
minutes of its exit order: Black-Scholes at the implied volatility of the leg's last print (6.5% rate), the index at the
exit minute and the calendar time to 15:30 on the expiry day (bhavcopy calendar), filled with thin-contract slippage
(4 ticks + 5 bps) and the same charges. The exit minute is the engine's (the stop path is not re-run; with fresh marks
some stops would fire earlier, which this check does not credit or charge).

* **Gate 8 (design):** the repriced design net > 0. **Holdout confirmation** now also needs the repriced holdout net > 0,
  besides the pre-registered holdout gate.
* The pre-registered numbers stay the headline numbers in every table; the repriced numbers sit beside them.
* The holdout is still read once, for the six design passers exactly as pre-registered (`r103study.py holdout`), and
  `r103stale.py holdout` reprices the same trades.

A stricter gate added after a design result can only reject more; it cannot manufacture a pass. The first repriced
design numbers (computed while diagnosing, before this note) were already negative for all six passers, so this
amendment is expected to reject them; it is written down so that the holdout read cannot change that verdict quietly.
