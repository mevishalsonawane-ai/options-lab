# R104 pre-registration: "when option sellers start losing, buy in the direction of the move" (22 Oct 2026)

Boss (22 Oct 2026), after R103: "Now as we got 65-70% winrate in selling, can we use this ... as opposite indicator to
buy options", "deep research for this, i think there must be some way", and "This could give us direction right, when
sellers are losing money, means buyers are winning". Written and committed **before any R104 profit or loss, trigger
count or continuation rate was computed**. Research only: nothing in `android/**` or `.github/**` changes, nothing goes
to paper or live.

## The idea, and what is already known

* The plain opposite of selling was already measured: R103's BUY mirror (the same four legs bought) lost on all 24
  tests; LONG_VOL.md and R89 (0 of 18) found that buying straddles loses at every entry time and with every pre-entry
  filter tried. Costs on both sides make "the other side of a loser" a loser too. Not repeated here.
* Boss's direction version is new: the 09:20 sellers (short straddle / iron fly at the ATM) start losing when the index
  runs away from its 09:20 level; their stop-losses force them to BUY back the side that is going against them (calls
  on a rise, puts on a fall), which could push the move further (a "short-gamma squeeze"). If so, buying the option in
  the direction of the move once sellers are under pressure should pay.
* Closest prior: R98c (expiry days only, trigger from 13:00 at 1.5 / 2 straddles, 5 indices): continuation was 34.5%
  against the random walk's 33.3% (152 triggers): geometry, not momentum; 0 of 48 rules confirmed. R104 tests the
  morning-anchored, all-days, smaller-trigger version that R98c did not.

**Signal source: the underlying.** The trigger is the index's own distance from its 09:20 close, measured in units of
the 09:20 ATM straddle (option-chain context, allowed by CLAUDE.md). The option is only what is bought. Exits are on
the index (XI) or on the option's premium (XP), both allowed.

## Data

The R103 stores (Drive lake copied 22 Oct 2026, rebuilt after a container restart from the same lake: index_1m,
options_rolling_1m WEEK files, R51 held-contract builder with Amendment A cleaning; bhavcopy expiry calendar).
NIFTY and BANKNIFTY, Aug 2021 - 19 Oct 2026.

**Stale prices (R103 Amendment 2's lesson, applied from the start).** The lake keeps only ATM +-10 strikes a minute; an
option bought ATM that goes deep in the money on a big day can leave that window. Every minute a held leg has no print,
it is marked with Black-Scholes at the implied volatility of its last print (6.5% rate, the index at that minute, the
calendar time to 15:30 on the expiry day), floored at intrinsic value; an exit with no traded open within 5 minutes is
filled at that model price with thin-contract slippage (4 ticks + 5 bps). An ENTRY with no traded open within 5 minutes
is skipped (counted).

## Periods

* **Design:** first option day (Aug 2021) - **14 Jul 2026**. Walk-forward = the sign in each calendar year 2022-2025 and
  after 20 Nov 2024 (the rules have no fitted parameter).
* **Holdout:** **15 Jul - 19 Oct 2026**, read once by `r104study.py holdout` (writes `results/HOLDOUT_READ`, refuses a
  second read). **Semi-clean:** R102 read this window for index-direction rules (its 1-hour and daily momentum rules,
  none confirmed) and descriptively (a quiet, drifting-down quarter); R103 read BANKNIFTY non-expiry option-selling P&L
  there. No R104 rule, trigger or continuation rate has been computed on it.

## The rules (16 tests = 2 indices x 2 day types x 2 triggers x 2 exits)

* **Day types:** E = the index's own expiry day; N = every other trading day.
* **Anchor (09:20):** I0 = the index close of the 09:20 minute (row 5); S = close of C(K0) + P(K0) at that minute, K0 the
  rolling ATM strike. A day with no 09:20 straddle is skipped.
* **Trigger:** the first minute close from 09:21 to 14:30 (rows 6-315) with |index - I0| >= k x S, k in **{0.5, 1.0}**.
  Direction = the sign of index - I0. At most one trade a day. No trigger, no trade.
* **Trade:** buy 1 lot of the ATM option (the rolling ATM strike at the trigger minute) on the side of the move (call if
  up, put if down), filled at the next minute's open, held as that one contract.
* **Exits** (decided on each minute's close, filled at the next minute's open; square-off 15:10, 15:05 on expiry day):
  * **XI (index):** out when the index's distance from I0 on the trade's side falls back to (k - 0.25) x S (the move
    failed) or reaches (k + 0.5) x S (the target); plus a premium safety stop when the mark falls to 50% of the entry.
  * **XP (premium):** stop when the mark falls to 70% of the entry price; a profit lock armed at +30% that exits at
    +10%; no target.
* **Size and costs:** 1 lot in force on the day; dated charges (STT 0.15% of the sell from 1 Apr 2026, etc.); 'liq'
  slippage (5 bps + 1-4 ticks by the contract's 5-minute volume, stops x2), as R103.

## Gates (all on design; the holdout is read only for tests that pass every design gate)

1. Net > 0 and the mean net per trade > 0 with a day-bootstrap one-sided p < 0.05 (10,000 resamples, seed 104).
2. BH q <= 0.10 across the 16 tests (logged in the experiment family `patterns-indicators`, index-signal option buying).
3. Net > 0 in at least 3 of the 4 calendar years 2022-2025, and > 0 after 20 Nov 2024.
4. At least 40 trades.
5. Net > 0 without its 5 best days.
6. Worst calendar month >= -Rs 13,000 at 1 lot.
7. Net > 0 with twice the slippage ticks.
8. **Direction is real:** the trade beats its **fade twin** (the opposite option, same strike rule, bought and sold at the
   same minutes) by a mean > 0 with a day-bootstrap p < 0.05. Without this, a pass would be about timing or volatility,
   not about the sellers' side.

**Holdout gate** (design passers only): net > 0, worst month >= -Rs 13,000, the fade-twin difference > 0, and the win
rate within 15 points of design.

## Reported, not gated

* **Continuation, on the index only:** for every trigger, whether the index went a further 0.5 S in the trade's
  direction before coming back 0.25 S (by the square-off). A driftless random walk gives 1/3 (0.25 / 0.75); reported
  with a binomial p against 1/3. This is the pure "do sellers' stops push the index" measure, with no option in it.
* **Random-time twin:** the same exits, entered at a random minute 09:21-14:30 on the same day in the direction of the
  move so far (20 per traded day, 200 totals, seed 104): does the k x S trigger matter at all?
* Win rate, average win and loss, worst day, max drawdown, days at or below -Rs 13,000.

## What a pass means

A paper candidate only, under CLAUDE.md: signal on the index, stop and lock on the premium, logged and labelled,
nothing for live. Every test gets an experiment entry (r104-*), failures included.

## Checks

`research/hunt/r104_follow/tests/test_r104.py`: the trigger (first minute, both sides, the 14:30 cut, no trigger), the
look-ahead guard (prices after a decision minute never change it), XI / XP paths, the stale-mark model (intrinsic floor,
unchanged when the leg prints), the fade twin's same minutes, the continuation counter against a hand-made path, the
holdout lock, and the 16-test count. Plus the repo's research checks.
